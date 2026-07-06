package cl.matiaspalma.everythingimu.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cl.matiaspalma.everythingimu.core.net.EimuClient
import cl.matiaspalma.everythingimu.core.net.EimuProtocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns BLE discovery + the set of connected controllers/trackers the phone
 * forwards to the eimu desktop as remote handles 1+ (handle 0 = the phone).
 *
 * Supported: Joy-Con 2 / Pro Controller 2 (GATT input reports) and HOPX
 * "Triki" IMU pucks (Nordic UART stream). Bluetooth Classic HID devices
 * (Joy-Con 1, DualSense, Wii Remote) cannot be reached from an unrooted
 * Android app — the public API exposes no raw HID host channel — so those
 * connect directly to the desktop instead.
 */
@SuppressLint("MissingPermission") // UI gates scan/connect behind runtime permission
class BleDeviceManager(
    private val context: Context,
    private val eimuClient: () -> EimuClient?,
) {
    enum class BleKind { Jc2Left, Jc2Right, Pro2, Hopx }

    data class Entry(
        val address: String,
        val name: String,
        val kind: BleKind,
        val state: State,
        val handle: Int = -1,
        val batteryPct: Int = -1,
    ) {
        enum class State { Found, Connecting, Streaming }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // Concurrent: mutated on Bluetooth binder threads (onReady/onDisconnected)
    // and iterated by rumble()/shutdown() on other threads.
    private val jc2Devices = ConcurrentHashMap<String, Jc2Device>()
    private val hopxDevices = ConcurrentHashMap<String, HopxDevice>()
    private val handles = ConcurrentHashMap<String, Int>()
    private val nextHandle = AtomicInteger(1)

    // Bounded auto-reconnect state. `onDisconnected` fires only on an involuntary
    // drop (a user disconnect()/shutdown() closes the device, which suppresses
    // the callback); the intentional marker is a defensive double-check that also
    // cancels a reconnect already scheduled when the user then disconnects.
    // Used as a set: presence of the key marks an address as user-disconnected.
    private val intentionalDisconnect = ConcurrentHashMap<String, Boolean>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val reconnectTokens = ConcurrentHashMap<String, Runnable>()
    private val reconnectHandler = Handler(Looper.getMainLooper())

    private val scanner
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    fun startScan() {
        val s = scanner ?: return
        if (_scanning.value) return
        _scanning.value = true
        // Two filters: Switch 2 manufacturer-data prefix, and the Triki NUS
        // service. A result matching either is delivered.
        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(
                    Jc2Protocol.NINTENDO_MFR_ID,
                    Jc2Protocol.ADV_PREFIX,
                    byteArrayOf(-1, -1, -1, -1),
                )
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(HopxProtocol.NUS_SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            s.startScan(filters, settings, scanCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "startScan failed", t)
            _scanning.value = false
        }
    }

    fun stopScan() {
        if (!_scanning.value) return
        _scanning.value = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "stopScan failed", t)
        }
    }

    fun connect(address: String) {
        // A fresh user-initiated connect clears any intentional-disconnect marker
        // and cancels/resets pending auto-reconnect for this address.
        intentionalDisconnect.remove(address)
        cancelPendingReconnect(address)
        reconnectAttempts.remove(address)
        val entry = _entries.value.firstOrNull { it.address == address } ?: return
        if (jc2Devices.containsKey(address) || hopxDevices.containsKey(address)) return
        startConnection(entry)
    }

    /** Build a FRESH device object (fresh BluetoothGatt / client_if) and connect.
     *  Never reuses a gatt already closed by [Jc2Device.releaseGatt]. */
    private fun startConnection(entry: Entry) {
        val address = entry.address
        update(address) { it.copy(state = Entry.State.Connecting) }
        try {
            val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter.getRemoteDevice(address)
            when (entry.kind) {
                BleKind.Hopx -> {
                    val dev = HopxDevice(context, bt, hopxListener)
                    hopxDevices[address] = dev
                    dev.connect()
                }
                else -> {
                    val kind = when (entry.kind) {
                        BleKind.Jc2Left -> Jc2Kind.Left
                        BleKind.Jc2Right -> Jc2Kind.Right
                        else -> Jc2Kind.Pro
                    }
                    val dev = Jc2Device(context, bt, kind, jc2Listener)
                    jc2Devices[address] = dev
                    dev.connect()
                }
            }
        } catch (e: SecurityException) {
            // connectGatt reached without BLUETOOTH_CONNECT (UI gate bypassed):
            // clean up any partial device and revert the row to Found.
            Log.w(TAG, "connect failed (missing BLUETOOTH_CONNECT?)", e)
            jc2Devices.remove(address)?.close()
            hopxDevices.remove(address)?.close()
            reconnectAttempts.remove(address)
            update(address) { it.copy(state = Entry.State.Found, handle = -1) }
        }
    }

    fun disconnect(address: String) {
        // User-initiated: mark intentional and cancel any pending reconnect so the
        // involuntary-drop path cannot resurrect the link.
        intentionalDisconnect[address] = true
        cancelPendingReconnect(address)
        reconnectAttempts.remove(address)
        jc2Devices.remove(address)?.close()
        hopxDevices.remove(address)?.close()
        dropHandle(address)
        update(address) { it.copy(state = Entry.State.Found, handle = -1) }
    }

    fun rumble(handle: Int, intensity: Float) {
        val address = handles.entries.firstOrNull { it.value == handle }?.key ?: return
        jc2Devices[address]?.rumble(intensity)
        // HOPX has no rumble motor — ignore.
    }

    fun shutdown() {
        stopScan()
        // Cancel every pending auto-reconnect before tearing devices down so a
        // scheduled runnable cannot start a fresh connect after shutdown.
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectTokens.clear()
        reconnectAttempts.clear()
        for ((address, dev) in jc2Devices) {
            intentionalDisconnect[address] = true
            dev.close()
            dropHandle(address)
        }
        for ((address, dev) in hopxDevices) {
            intentionalDisconnect[address] = true
            dev.close()
            dropHandle(address)
        }
        jc2Devices.clear()
        hopxDevices.clear()
        _entries.value = emptyList()
    }

    // --- Bounded auto-reconnect ------------------------------------------------

    /** Involuntary-drop entry point shared by both listeners' `onDisconnected`.
     *  The dropped device has already been removed from its map by the caller. */
    private fun onInvoluntaryDrop(address: String) {
        if (intentionalDisconnect.remove(address) != null) {
            // User disconnect / shutdown raced the callback — surface, no reconnect.
            surfaceDisconnected(address)
            return
        }
        val attempts = reconnectAttempts[address] ?: 0
        if (!ReconnectPolicy.shouldReconnect(
                intentional = false,
                attempts = attempts,
                maxAttempts = MAX_RECONNECT_ATTEMPTS,
            )
        ) {
            // Budget exhausted — give up and surface disconnected.
            surfaceDisconnected(address)
            return
        }
        reconnectAttempts[address] = attempts + 1
        // Keep the desktop handle + announce alive across the blip; show the row
        // as reconnecting rather than dropping it.
        if (_entries.value.any { it.address == address }) {
            update(address) { it.copy(state = Entry.State.Connecting) }
        }
        scheduleReconnect(address, ReconnectPolicy.backoffMillis(attempts))
    }

    /** Give up: clear reconnect state, drop the desktop handle, revert the row. */
    private fun surfaceDisconnected(address: String) {
        reconnectAttempts.remove(address)
        dropHandle(address)
        update(address) { it.copy(state = Entry.State.Found, handle = -1) }
    }

    private fun scheduleReconnect(address: String, delayMs: Long) {
        cancelPendingReconnect(address)
        val token = Runnable {
            reconnectTokens.remove(address)
            attemptReconnect(address)
        }
        reconnectTokens[address] = token
        reconnectHandler.postDelayed(token, delayMs)
    }

    private fun cancelPendingReconnect(address: String) {
        reconnectTokens.remove(address)?.let { reconnectHandler.removeCallbacks(it) }
    }

    private fun attemptReconnect(address: String) {
        // Re-check: the user may have disconnected during the backoff window.
        if (intentionalDisconnect.containsKey(address)) return
        if (jc2Devices.containsKey(address) || hopxDevices.containsKey(address)) return
        val entry = _entries.value.firstOrNull { it.address == address } ?: return
        startConnection(entry)
    }

    private fun dropHandle(address: String) {
        handles.remove(address)?.let { handle -> eimuClient()?.removeDevice(handle) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            if (_entries.value.any { it.address == address }) return
            val mfr = result.scanRecord?.getManufacturerSpecificData(Jc2Protocol.NINTENDO_MFR_ID)
            val deviceName = result.scanRecord?.deviceName ?: result.device.name
            val jc2Kind = Jc2Protocol.kindFromManufacturerData(mfr)
            val entry = when {
                jc2Kind != null -> {
                    val kind = when (jc2Kind) {
                        Jc2Kind.Left -> BleKind.Jc2Left
                        Jc2Kind.Right -> BleKind.Jc2Right
                        Jc2Kind.Pro -> BleKind.Pro2
                    }
                    Entry(address, deviceName ?: "JoyCon2-${address.takeLast(5)}", kind, Entry.State.Found)
                }
                HopxProtocol.nameMatches(deviceName) ->
                    Entry(address, deviceName ?: "Triki", BleKind.Hopx, Entry.State.Found)
                else -> return
            }
            _entries.value = _entries.value + entry
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            _scanning.value = false
        }
    }

    private val jc2Listener = object : Jc2Device.Listener {
        override fun onReady(device: Jc2Device) {
            // A live link clears the reconnect budget so a later independent blip
            // starts its attempt count from zero.
            reconnectAttempts.remove(device.address)
            // putIfAbsent (not computeIfAbsent, which is API 24+) keeps this
            // atomic on the minSdk-21 floor.
            val handle = handles[device.address] ?: run {
                val fresh = nextHandle.getAndIncrement()
                handles.putIfAbsent(device.address, fresh) ?: fresh
            }
            val kindWire = when (device.kind) {
                Jc2Kind.Left -> EimuProtocol.KIND_JOYCON2_L
                Jc2Kind.Right -> EimuProtocol.KIND_JOYCON2_R
                Jc2Kind.Pro -> EimuProtocol.KIND_PRO_CONTROLLER_2
            }
            eimuClient()?.announceDevice(
                EimuClient.AnnouncedDevice(
                    handle = handle,
                    kind = kindWire,
                    // Joy-Con 2 / Pro Controller 2 carry a magnetometer; announce
                    // 9DOF so the desktop rebuilds mag-fused (drift-free) heading.
                    mac = macFromAddress(device.address),
                    hasMag = true,
                    hasBattery = true,
                    hasRumble = true,
                    rateHz = 60,
                    name = device.name,
                ),
            )
            update(device.address) { it.copy(state = Entry.State.Streaming, handle = handle) }
        }

        override fun onSample(device: Jc2Device, report: Jc2Report, timestampUs: Long) {
            val handle = handles[device.address] ?: return
            eimuClient()?.sendImu(handle, timestampUs, report.gyro, report.accel, report.mag)
        }

        override fun onReset(device: Jc2Device, reset: Jc2ResetDetector.Reset) {
            val handle = handles[device.address] ?: return
            val wire = if (reset == Jc2ResetDetector.Reset.Full) EimuProtocol.RESET_FULL else EimuProtocol.RESET_YAW
            eimuClient()?.sendButton(handle, wire)
        }

        override fun onBattery(device: Jc2Device, fraction: Float, charging: Boolean) {
            val handle = handles[device.address] ?: return
            eimuClient()?.sendBattery(handle, fraction, charging)
            update(device.address) { it.copy(batteryPct = (fraction * 100).toInt()) }
        }

        override fun onDisconnected(device: Jc2Device) {
            // Involuntary drop only (voluntary close() suppresses this callback):
            // remove the closed device then run the bounded-reconnect decision.
            jc2Devices.remove(device.address)
            onInvoluntaryDrop(device.address)
        }
    }

    private val hopxListener = object : HopxDevice.Listener {
        override fun onReady(device: HopxDevice) {
            // A live link clears the reconnect budget so a later independent blip
            // starts its attempt count from zero.
            reconnectAttempts.remove(device.address)
            // putIfAbsent (not computeIfAbsent, which is API 24+) keeps this
            // atomic on the minSdk-21 floor.
            val handle = handles[device.address] ?: run {
                val fresh = nextHandle.getAndIncrement()
                handles.putIfAbsent(device.address, fresh) ?: fresh
            }
            eimuClient()?.announceDevice(
                EimuClient.AnnouncedDevice(
                    handle = handle,
                    kind = EimuProtocol.KIND_HOPX,
                    mac = macFromAddress(device.address),
                    hasMag = false,
                    hasBattery = false,
                    hasRumble = false,
                    rateHz = HopxProtocol.NATIVE_RATE_HZ,
                    name = device.name,
                ),
            )
            update(device.address) { it.copy(state = Entry.State.Streaming, handle = handle) }
        }

        override fun onSample(device: HopxDevice, record: HopxRecord, timestampUs: Long) {
            val handle = handles[device.address] ?: return
            eimuClient()?.sendImu(handle, timestampUs, record.gyro, record.accel, null)
        }

        override fun onDisconnected(device: HopxDevice) {
            // Involuntary drop only (voluntary close() suppresses this callback):
            // remove the closed device then run the bounded-reconnect decision.
            hopxDevices.remove(device.address)
            onInvoluntaryDrop(device.address)
        }
    }

    private fun update(address: String, f: (Entry) -> Entry) {
        _entries.value = _entries.value.map { if (it.address == address) f(it) else it }
    }

    private fun macFromAddress(address: String): ByteArray {
        val parts = address.split(":")
        if (parts.size == 6) {
            return ByteArray(6) { i -> parts[i].toInt(16).toByte() }
        }
        val h = address.hashCode()
        return byteArrayOf(0x02, (h shr 24).toByte(), (h shr 16).toByte(), (h shr 8).toByte(), h.toByte(), 0x01)
    }

    companion object {
        private const val TAG = "BleDeviceManager"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
}

/**
 * Pure decision logic for [BleDeviceManager]'s bounded auto-reconnect. Kept
 * side-effect-free so it can be unit-tested without a live GATT stack.
 */
object ReconnectPolicy {
    /** Reconnect only for an involuntary drop that still has budget left. */
    fun shouldReconnect(intentional: Boolean, attempts: Int, maxAttempts: Int): Boolean =
        !intentional && attempts < maxAttempts

    /**
     * Exponential backoff for a 0-based attempt index: 1s, 2s, 4s, 8s, 16s,
     * capped at 16s so an out-of-range index cannot overflow the shift.
     */
    fun backoffMillis(attempt: Int): Long = 1000L shl attempt.coerceIn(0, 4)
}
