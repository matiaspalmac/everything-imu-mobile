package cl.matiaspalma.everythingimu.core.gamepad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.util.Log
import android.view.InputDevice
import androidx.annotation.RequiresApi
import cl.matiaspalma.everythingimu.core.net.EimuClient
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Forwards motion-capable gamepads paired to the phone (Bluetooth Classic or
 * USB) to the eimu desktop as remote trackers, using the public
 * `InputDevice.getSensorManager()` API (Android 12+).
 *
 * Raw HID over BT Classic is not reachable from an unrooted app — the SDK's
 * L2CAP sockets are LE-only and `BluetoothHidHost` is a hidden system API —
 * but since API 31 the kernel HID drivers (`hid-playstation`, `hid-nintendo`)
 * expose a controller's accelerometer + gyroscope as standard sensors hanging
 * off its InputDevice, with battery (`getBatteryState`) and rumble
 * (`getVibratorManager`) alongside. Works for DualSense / DualShock 4 on any
 * GKI kernel; Joy-Con 1 / Pro Controller depend on the vendor enabling
 * `hid-nintendo`. Pads whose kernel driver exposes no sensors are listed as
 * unsupported instead of forwarded.
 *
 * Handles live in their own range ([HANDLE_BASE]+) so they never collide with
 * the BLE manager's 1+ range.
 */
@RequiresApi(31)
class GamepadSensorForwarder(
    context: Context,
    private val eimuClient: () -> EimuClient?,
) {
    data class Entry(
        val deviceId: Int,
        val name: String,
        val kind: Int,
        val hasMotion: Boolean,
        val handle: Int = -1,
        val batteryPct: Int = -1,
    )

    private val appContext = context.applicationContext
    private val inputManager =
        appContext.getSystemService(Context.INPUT_SERVICE) as InputManager

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val handlerThread = HandlerThread("eimu-gamepads").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val forwarded = HashMap<Int, ForwardedPad>()
    private val nextHandle = AtomicInteger(HANDLE_BASE)

    // Stable handle per physical pad. A monotonic counter would mint a fresh
    // handle on every forward toggle or app reconnect, registering the same
    // controller as a brand-new device on the desktop each time — and desktop
    // haptics rules bound to the old handle would rumble into the void.
    private val descriptorHandles = HashMap<String, Int>()

    /** One streaming pad: its sensor listeners + latest accel for pairing. */
    private inner class ForwardedPad(
        val device: InputDevice,
        val handle: Int,
        val rateHz: Int,
    ) : SensorEventListener {
        @Volatile var latestAccel: FloatArray? = null
        @Volatile var lastBatteryPct: Int = -1
        var batteryTick: Runnable? = null

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                // InputDevice sensors are SI already: m/s² and rad/s.
                Sensor.TYPE_ACCELEROMETER -> latestAccel = event.values.copyOf(3)
                Sensor.TYPE_GYROSCOPE -> {
                    val accel = latestAccel ?: return
                    eimuClient()?.sendImu(
                        handle,
                        event.timestamp / 1_000,
                        floatArrayOf(event.values[0], event.values[1], event.values[2]),
                        accel,
                        null,
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()
        override fun onInputDeviceRemoved(deviceId: Int) {
            stopForwarding(deviceId)
            refresh()
        }
        override fun onInputDeviceChanged(deviceId: Int) = refresh()
    }

    fun start() {
        if (_running.value) return
        _running.value = true
        inputManager.registerInputDeviceListener(deviceListener, handler)
        refresh()
        // Auto-forward every motion-capable pad already present.
        for (entry in _entries.value) {
            if (entry.hasMotion) startForwarding(entry.deviceId)
        }
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        try {
            inputManager.unregisterInputDeviceListener(deviceListener)
        } catch (_: Throwable) {
            // not registered — fine
        }
        for (id in forwarded.keys.toList()) stopForwarding(id)
        refresh()
    }

    fun rumble(handle: Int, intensity: Float) {
        val pad = forwarded.values.firstOrNull { it.handle == handle } ?: return
        try {
            val vm = pad.device.vibratorManager
            if (intensity <= 0.01f) {
                vm.cancel()
                return
            }
            val amplitude = (intensity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(1, 255)
            vm.vibrate(
                CombinedVibration.createParallel(
                    VibrationEffect.createOneShot(120L, amplitude),
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "gamepad rumble failed", t)
        }
    }

    fun startForwarding(deviceId: Int) {
        if (forwarded.containsKey(deviceId)) return
        val device = InputDevice.getDevice(deviceId) ?: return
        val sm = device.sensorManager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return

        val kind = GamepadKinds.kindFor(device.vendorId, device.productId)
        // minDelay is µs between samples at the fastest rate; 0 = unknown.
        val rate = if (gyro.minDelay > 0) {
            (1_000_000 / gyro.minDelay).coerceIn(20, 1000)
        } else {
            DEFAULT_RATE_HZ
        }
        val handle = synchronized(descriptorHandles) {
            descriptorHandles.getOrPut(device.descriptor) { nextHandle.getAndIncrement() }
        }
        val pad = ForwardedPad(device, handle, rate)
        sm.registerListener(pad, accel, SensorManager.SENSOR_DELAY_FASTEST, handler)
        sm.registerListener(pad, gyro, SensorManager.SENSOR_DELAY_FASTEST, handler)
        forwarded[deviceId] = pad

        val hasRumble = device.vibratorManager.vibratorIds.isNotEmpty()
        eimuClient()?.announceDevice(
            EimuClient.AnnouncedDevice(
                handle = handle,
                kind = kind,
                mac = GamepadKinds.macFromDescriptor(device.descriptor),
                hasMag = false,
                hasBattery = device.batteryState.isPresent,
                hasRumble = hasRumble,
                rateHz = rate,
                name = device.name,
            ),
        )
        scheduleBatteryTick(deviceId, pad)
        refresh()
    }

    fun stopForwarding(deviceId: Int) {
        val pad = forwarded.remove(deviceId) ?: return
        try {
            pad.device.sensorManager.unregisterListener(pad)
        } catch (_: Throwable) {
            // device may already be gone
        }
        pad.batteryTick?.let { handler.removeCallbacks(it) }
        eimuClient()?.removeDevice(pad.handle)
        refresh()
    }

    fun shutdown() {
        stop()
        handlerThread.quitSafely()
    }

    private fun scheduleBatteryTick(deviceId: Int, pad: ForwardedPad) {
        val tick = object : Runnable {
            override fun run() {
                if (!forwarded.containsKey(deviceId)) return
                try {
                    val state = pad.device.batteryState
                    if (state.isPresent && !state.capacity.isNaN()) {
                        eimuClient()?.sendBattery(pad.handle, state.capacity, charging = false)
                        val pct = (state.capacity * 100).toInt()
                        if (pct != pad.lastBatteryPct) {
                            pad.lastBatteryPct = pct
                            refresh()
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "battery read failed", t)
                }
                handler.postDelayed(this, BATTERY_EVERY_MS)
            }
        }
        pad.batteryTick = tick
        handler.postDelayed(tick, BATTERY_EVERY_MS)
    }

    private fun refresh() {
        val list = ArrayList<Entry>()
        for (id in inputManager.inputDeviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            if (!device.isExternal) continue
            val sources = device.sources
            val isGamepad =
                (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!isGamepad) continue
            val sm = device.sensorManager
            val hasMotion = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null &&
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            val pad = forwarded[id]
            list.add(
                Entry(
                    deviceId = id,
                    name = device.name,
                    kind = GamepadKinds.kindFor(device.vendorId, device.productId),
                    hasMotion = hasMotion,
                    handle = pad?.handle ?: -1,
                    batteryPct = pad?.lastBatteryPct ?: -1,
                ),
            )
        }
        _entries.value = list
    }

    companion object {
        private const val TAG = "GamepadForwarder"

        /** Keeps gamepad handles clear of the BLE manager's 1+ range. */
        const val HANDLE_BASE = 1000

        private const val DEFAULT_RATE_HZ = 250
        private const val BATTERY_EVERY_MS = 30_000L
    }
}
