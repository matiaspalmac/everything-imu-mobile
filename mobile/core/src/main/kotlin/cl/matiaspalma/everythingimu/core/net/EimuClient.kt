package cl.matiaspalma.everythingimu.core.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * eimu remote-hub client. Streams this phone's devices (handle 0 = the phone
 * itself, 1+ = BLE controllers) to the desktop app's `device-remote` UDP
 * listener. Connection liveness = HELLO/HELLO_ACK; devices re-announce every
 * 2 s as keepalive. Every datagram is built and sent on a single send thread
 * so the wire order always matches the submission order.
 */
class EimuClient(
    private val uuid: ByteArray,
    private val hubName: String,
) {
    data class AnnouncedDevice(
        val handle: Int,
        val kind: Int,
        val mac: ByteArray,
        val hasMag: Boolean,
        val hasBattery: Boolean,
        val hasRumble: Boolean,
        val rateHz: Int,
        val name: String,
    )

    private val tag = "EimuClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Bounded send queue drained by a single thread so the wire order always
     * matches submission order. IMU frames drop-oldest once the queue backs up
     * (a MIUI Wi-Fi power-save stall must not bufferbloat seconds-stale
     * samples); control messages never drop.
     */
    private val sendQueue =
        java.util.concurrent.LinkedBlockingQueue<Runnable>(SEND_QUEUE_CAPACITY)
    private val sendExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, sendQueue,
        { r -> Thread(r, "eimu-send").apply { isDaemon = true } },
    ).apply { prestartCoreThread() }

    private class SendTask(val imu: Boolean, private val body: () -> Unit) : Runnable {
        override fun run() = body()
    }

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var endpoint: InetSocketAddress? = null
    private var recvJob: Job? = null
    private var helloJob: Job? = null
    private var announceJob: Job? = null
    @Volatile private var lastAckNanos: Long = 0L

    private val devices = ConcurrentHashMap<Int, AnnouncedDevice>()

    /** Wrapping per-handle send counters for MSG_IMU2 loss measurement. */
    private val seqCounters = ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(ClientStats())
    val stats: StateFlow<ClientStats> = _stats.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Desktop-initiated rumble for one of our handles (phone vibrator / BLE controller). */
    @Volatile var onRumble: ((handle: Int, intensity: Float) -> Unit)? = null

    /**
     * Invoked with each freshly-created UDP socket before any traffic. Used to
     * bind the socket to the Wi-Fi network (see WifiBinder) so the eimu stream
     * leaves the right NIC without forcing the whole process onto Wi-Fi.
     */
    @Volatile var onSocketCreated: ((DatagramSocket) -> Unit)? = null

    @Volatile private var sentCount: Long = 0
    @Volatile private var recvCount: Long = 0
    @Volatile private var lastRttMs: Long = -1

    @Synchronized
    fun connect(host: String, port: Int) {
        disconnect()
        _state.value = ConnectionState.Connecting
        try {
            // Inside the try as defense-in-depth: a persisted out-of-range port
            // makes InetSocketAddress throw IllegalArgumentException, which must
            // surface as a Failed state rather than crash the caller coroutine.
            val target = InetSocketAddress(host, port)
            endpoint = target
            val sock = DatagramSocket().apply { soTimeout = 250 }
            // Bind to Wi-Fi (best effort) before any send/receive so datagrams
            // leave the Wi-Fi NIC even when a Bluetooth companion proxy is the
            // process default (Wear OS).
            onSocketCreated?.invoke(sock)
            socket = sock
            Log.i(tag, "eimu socket bound localPort=${sock.localPort} -> $host:$port")
            lastAckNanos = 0L
            _lastError.value = null
            recvJob = scope.launch { recvLoop() }
            helloJob = scope.launch { helloLoop() }
            announceJob = scope.launch { announceLoop() }
            updateStats(target.toString())
        } catch (t: Throwable) {
            Log.e(tag, "connect failed", t)
            _lastError.value = "Connect failed: ${t.message ?: t.javaClass.simpleName}"
            _state.value = ConnectionState.Failed
            socket?.close(); socket = null
        }
    }

    @Synchronized
    fun disconnect() {
        recvJob?.cancel(); recvJob = null
        helloJob?.cancel(); helloJob = null
        announceJob?.cancel(); announceJob = null
        socket?.close(); socket = null
        endpoint = null
        lastAckNanos = 0L
        _state.value = ConnectionState.Disconnected
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
        sendExecutor.shutdownNow()
    }

    /** Register or refresh a device; announced immediately + every 2 s. */
    fun announceDevice(device: AnnouncedDevice) {
        devices[device.handle] = device
        submit {
            EimuProtocol.announce(
                device.handle, device.kind, device.mac, device.hasMag,
                device.hasBattery, device.hasRumble, device.rateHz, device.name,
            )
        }
    }

    fun removeDevice(handle: Int) {
        devices.remove(handle)
        submit { EimuProtocol.remove(handle) }
    }

    fun sendImu(handle: Int, timestampUs: Long, gyro: FloatArray, accel: FloatArray, mag: FloatArray?) {
        val counter = seqCounters.getOrPut(handle) {
            java.util.concurrent.atomic.AtomicInteger(0)
        }
        submitImu {
            // Assign seq on the send thread so it matches the actual wire order.
            val seq = counter.getAndIncrement() and 0xFFFF
            EimuProtocol.imu2(handle, seq, timestampUs, gyro, accel, mag)
        }
    }

    fun sendBattery(handle: Int, fraction: Float, charging: Boolean) {
        submit { EimuProtocol.battery(handle, fraction, charging) }
    }

    fun sendButton(handle: Int, reset: Int) {
        submit { EimuProtocol.button(handle, reset) }
    }

    private suspend fun helloLoop() = withContext(Dispatchers.IO) {
        var misses = 0
        while (scope.isActive && socket != null) {
            submit { EimuProtocol.hello(uuid, hubName, tSendUs = System.nanoTime() / 1_000) }
            val ackAgeMs =
                if (lastAckNanos == 0L) Long.MAX_VALUE
                else (System.nanoTime() - lastAckNanos) / 1_000_000
            if (ackAgeMs < ACK_STALE_MS) {
                _state.value = ConnectionState.Connected
                misses = 0
                if (_lastError.value != null) _lastError.value = null
            } else {
                _state.value =
                    if (lastAckNanos == 0L) ConnectionState.Connecting
                    else ConnectionState.Reconnecting
                misses++
                if (misses >= 3) {
                    val ep = endpoint
                    _lastError.value =
                        "No response from eimu desktop. Check it is running and the firewall allows UDP ${ep?.port ?: EimuProtocol.DEFAULT_PORT}."
                }
            }
            delay(if (_state.value == ConnectionState.Connected) HELLO_CONNECTED_MS else HELLO_SEARCH_MS)
        }
    }

    private suspend fun announceLoop() = withContext(Dispatchers.IO) {
        while (scope.isActive && socket != null) {
            for (device in devices.values) {
                submit {
                    EimuProtocol.announce(
                        device.handle, device.kind, device.mac, device.hasMag,
                        device.hasBattery, device.hasRumble, device.rateHz, device.name,
                    )
                }
            }
            delay(ANNOUNCE_EVERY_MS)
        }
    }

    private suspend fun recvLoop() = withContext(Dispatchers.IO) {
        val sock = socket ?: return@withContext
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (scope.isActive && !sock.isClosed) {
            try {
                sock.receive(pkt)
                // Drop datagrams whose source is not the connected endpoint: on
                // an unconnected UDP socket any LAN host could forge a HELLO_ACK
                // (masking a real disconnect) or a Rumble (driving the motor).
                val ep = endpoint
                if (ep != null && (pkt.address != ep.address || pkt.port != ep.port)) {
                    continue
                }
                recvCount++
                when (val msg = EimuProtocol.parseIncoming(pkt.data, pkt.length)) {
                    is EimuProtocol.Incoming.HelloAck -> {
                        lastAckNanos = System.nanoTime()
                        msg.echoUs?.let { echo ->
                            lastRttMs = ((System.nanoTime() / 1_000 - echo) / 1_000)
                                .coerceAtLeast(0)
                        }
                        updateStats()
                    }
                    is EimuProtocol.Incoming.Rumble -> {
                        // Any reply proves the server is alive, not just ACKs.
                        lastAckNanos = System.nanoTime()
                        onRumble?.invoke(msg.handle, msg.intensity)
                    }
                    null -> {}
                }
            } catch (_: java.net.SocketTimeoutException) {
                // expected; re-arm
            } catch (t: Throwable) {
                if (!scope.isActive || sock.isClosed) break
                Log.w(tag, "recv error", t)
            }
        }
    }

    /** Control messages (hello/announce/remove/battery/button): never dropped. */
    private fun submit(build: () -> ByteArray) {
        if (sendExecutor.isShutdown) return
        val task = SendTask(imu = false) { sendNow(build) }
        while (!sendQueue.offer(task)) {
            // Make room without dropping control: evict the oldest queued IMU frame.
            if (!dropOldestImu()) sendQueue.poll()
        }
    }

    /** IMU stream: bounded, drop-oldest so a send stall can't bufferbloat. */
    private fun submitImu(build: () -> ByteArray) {
        if (sendExecutor.isShutdown) return
        val task = SendTask(imu = true) { sendNow(build) }
        while (!sendQueue.offer(task)) {
            if (sendQueue.poll() == null) break
        }
    }

    /** Remove the oldest queued IMU task, leaving control messages in place. */
    private fun dropOldestImu(): Boolean {
        val it = sendQueue.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t is SendTask && t.imu) {
                it.remove()
                return true
            }
        }
        return false
    }

    private fun sendNow(build: () -> ByteArray) {
        val sock = socket ?: return
        val ep = endpoint ?: return
        try {
            val bytes = build()
            sock.send(DatagramPacket(bytes, bytes.size, ep.address, ep.port))
            sentCount++
            updateStats()
        } catch (t: Throwable) {
            Log.w(tag, "send failed", t)
            _lastError.value = "Send failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun updateStats(target: String? = null) {
        val ageMs = if (lastAckNanos == 0L) -1L else (System.nanoTime() - lastAckNanos) / 1_000_000
        _stats.value = ClientStats(
            packetsSent = sentCount,
            packetsReceived = recvCount,
            lastHeartbeatAgeMs = ageMs,
            targetEndpoint = target ?: _stats.value.targetEndpoint,
            rttMs = lastRttMs,
        )
    }

    companion object {
        private const val HELLO_SEARCH_MS = 1_000L
        private const val HELLO_CONNECTED_MS = 3_000L
        private const val ANNOUNCE_EVERY_MS = 2_000L

        // Bounded send queue: ~2.5 s of headroom at typical IMU rates before
        // drop-oldest kicks in.
        private const val SEND_QUEUE_CAPACITY = 256

        // Four hello periods of grace before flagging Reconnecting: MIUI's
        // Wi-Fi power save can hold inbound UDP for seconds with the screen
        // off, and a single delayed ACK must not flap the connection state.
        private const val ACK_STALE_MS = 12_000L

        /** 16 raw bytes from the persisted UUID string (dashes stripped, hex). */
        fun uuidBytes(uuidHex: String): ByteArray {
            val cleaned = uuidHex.replace("-", "").padEnd(32, '0').take(32)
            return ByteArray(16) { i ->
                cleaned.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
            }
        }
    }
}
