package cl.matiaspalma.everythingimu.core.haptics

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Listens on UDP 9001 (VRChat outgoing OSC), maps `/avatar/parameters/(name)`
 * events to vibration, and feeds the live log to the UI.
 *
 * Auto-off watchdog: if no OSC packet arrives for [silenceTimeoutMs], any
 * sustained vibration is cancelled so a motor never sticks on after VRChat
 * exits.
 */
class HapticBridge(context: Context) {

    private val appContext = context.applicationContext
    private val vibrator: Vibrator? = obtainVibrator(appContext)
    /** Serializes motor access so interleaved vibrate()/cancel() calls from the
     * OSC, watchdog, and desktop-rumble threads cannot stomp each other. */
    private val vibratorLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var recvJob: Job? = null
    private var watchdog: Job? = null

    @Volatile private var lastPacketNanos: Long = 0L
    @Volatile private var lastIntensity: Float = 0f
    @Volatile private var lastVibrateNanos: Long = 0L

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _state = MutableStateFlow(HapticState())
    val state: StateFlow<HapticState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<HapticEvent>>(emptyList())
    val log: StateFlow<List<HapticEvent>> = _log.asStateFlow()

    /** Whitelist of address prefixes. Empty = any /avatar/parameters/(name) address. */
    @Volatile var prefixes: List<String> = emptyList()

    /** Minimum proximity float that still triggers a buzz. */
    @Volatile var minThreshold: Float = 0.05f

    /** Multiplier applied to incoming float before mapping to amplitude. */
    @Volatile var gain: Float = 1f

    /** Hard cap on vibration rate to protect the motor / coalesce updates. */
    @Volatile var minIntervalMs: Long = 25L

    private val silenceTimeoutMs: Long = 2_000

    fun isVibratorAvailable(): Boolean = vibrator?.hasVibrator() == true

    @Synchronized
    fun start(port: Int = 9001) {
        if (_enabled.value) return
        try {
            socket = DatagramSocket(port).apply { soTimeout = 500 }
        } catch (t: Throwable) {
            Log.w("HapticBridge", "bind UDP:$port failed", t)
            return
        }
        _enabled.value = true
        recvJob = scope.launch { receive() }
        watchdog = scope.launch { watchdog() }
        _state.update { it.copy(port = port) }
    }

    @Synchronized
    fun stop() {
        _enabled.value = false
        recvJob?.cancel(); recvJob = null
        watchdog?.cancel(); watchdog = null
        socket?.close(); socket = null
        cancelVibration()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun receive() = withContext(Dispatchers.IO) {
        val sock = socket ?: return@withContext
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (scope.isActive && !sock.isClosed) {
            try {
                sock.receive(pkt)
                lastPacketNanos = System.nanoTime()
                val messages = Osc.parse(pkt.data, pkt.length)
                for (msg in messages) {
                    handleMessage(msg)
                }
            } catch (_: java.net.SocketTimeoutException) {
                // expected
            } catch (t: Throwable) {
                // stop() closes the socket out from under a blocking receive(),
                // which surfaces here as SocketException ("recvfrom failed:
                // EBADF"). That's a normal teardown — exit quietly. Only a fault
                // while we're still meant to be listening is worth logging.
                if (!scope.isActive || socket == null || sock.isClosed) break
                Log.w("HapticBridge", "recv error", t)
            }
        }
    }

    private fun handleMessage(msg: OscMessage) {
        if (!msg.address.startsWith("/avatar/parameters/")) return
        if (prefixes.isNotEmpty()) {
            val name = msg.address.removePrefix("/avatar/parameters/")
            if (prefixes.none { name.startsWith(it) }) return
        }
        val intensity = (msg.intensity() * gain).coerceIn(0f, 1f)
        appendLog(msg.address, intensity)
        if (intensity < minThreshold) {
            if (lastIntensity > minThreshold) cancelVibration()
            lastIntensity = intensity
            return
        }
        val now = System.nanoTime()
        // Latch the most recent intensity; the interval cap actually throttles
        // even when the value changes every packet (VRChat proximity sweeps).
        lastIntensity = intensity
        if ((now - lastVibrateNanos) / 1_000_000 < minIntervalMs) return
        lastVibrateNanos = now
        triggerVibration(intensity)
    }

    @SuppressLint("MissingPermission")
    private fun triggerVibration(intensity: Float) {
        val vib = vibrator ?: return
        // `gain` was already applied (and clamped) in handleMessage; do not
        // multiply it in again here or the motor is driven by gain squared.
        val boosted = intensity.coerceIn(0f, 1f)
        val amplitude = (boosted * 255f).toInt().coerceIn(1, 255)
        val durationMs = (60L + (boosted * 220f).toLong()).coerceAtLeast(40L)
        try {
            synchronized(vibratorLock) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = if (vib.hasAmplitudeControl()) {
                        VibrationEffect.createOneShot(durationMs, amplitude)
                    } else {
                        VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    // Attribute the buzz as media/game rumble. A bare vibrate()
                    // defaults to USAGE_TOUCH, which the system drops with
                    // IGNORED_FOR_SETTINGS whenever the user has touch haptic
                    // feedback disabled (MIUI ships it off).
                    if (Build.VERSION.SDK_INT >= 33) {
                        vib.vibrate(
                            effect,
                            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(
                            effect,
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build(),
                        )
                    }
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(durationMs)
                }
            }
            _state.update { it.copy(activeIntensity = boosted, lastEventNanos = System.nanoTime()) }
        } catch (t: Throwable) {
            Log.w("HapticBridge", "vibrate failed", t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun cancelVibration() {
        try {
            synchronized(vibratorLock) {
                vibrator?.cancel()
            }
        } catch (_: Throwable) {
            // best-effort
        }
        _state.update { it.copy(activeIntensity = 0f) }
    }

    private suspend fun watchdog() {
        while (scope.isActive) {
            delay(500)
            val last = lastPacketNanos
            if (last == 0L) continue
            val ageMs = (System.nanoTime() - last) / 1_000_000
            if (ageMs > silenceTimeoutMs && lastIntensity > 0f) {
                lastIntensity = 0f
                cancelVibration()
                appendLog("(watchdog) silence → off", 0f)
            }
        }
    }

    private fun appendLog(address: String, intensity: Float) {
        val event = HapticEvent(address, intensity, System.nanoTime())
        val current = _log.value
        val next = ArrayList<HapticEvent>(MAX_LOG)
        next.add(event)
        for (e in current) {
            if (next.size >= MAX_LOG) break
            next.add(e)
        }
        _log.value = next
    }

    private fun obtainVibrator(ctx: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun selfTest() {
        triggerVibration(1.0f)
    }

    /** Direct one-shot pulse from the eimu desktop rumble backchannel. */
    fun pulse(intensity: Float) {
        val clamped = intensity.coerceIn(0f, 1f)
        // Surface desktop rumble in the same log the OSC bridge uses, so the
        // haptics screen shows the pulses are arriving even if the motor is
        // silenced by OEM settings.
        appendLog("(eimu) desktop rumble", clamped)
        _state.update { it.copy(eimuPulses = it.eimuPulses + 1) }
        if (clamped <= 0f) cancelVibration() else triggerVibration(clamped)
    }

    companion object {
        private const val MAX_LOG = 32
    }
}

data class HapticEvent(val address: String, val intensity: Float, val timestampNanos: Long)
data class HapticState(
    val port: Int = 9001,
    val activeIntensity: Float = 0f,
    val lastEventNanos: Long = 0L,
    /** Count of desktop-initiated rumble pulses received this session. */
    val eimuPulses: Long = 0L,
)
