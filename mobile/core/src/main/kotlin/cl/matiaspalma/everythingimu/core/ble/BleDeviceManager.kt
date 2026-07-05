package cl.matiaspalma.everythingimu.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
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
        val entry = _entries.value.firstOrNull { it.address == address } ?: return
        if (jc2Devices.containsKey(address) || hopxDevices.containsKey(address)) return
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
            update(address) { it.copy(state = Entry.State.Found, handle = -1) }
        }
    }

    fun disconnect(address: String) {
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
        for ((address, dev) in jc2Devices) {
            dev.close()
            dropHandle(address)
        }
        for ((address, dev) in hopxDevices) {
            dev.close()
            dropHandle(address)
        }
        jc2Devices.clear()
        hopxDevices.clear()
        _entries.value = emptyList()
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
            val handle = handles.computeIfAbsent(device.address) { nextHandle.getAndIncrement() }
            val kindWire = when (device.kind) {
                Jc2Kind.Left -> EimuProtocol.KIND_JOYCON2_L
                Jc2Kind.Right -> EimuProtocol.KIND_JOYCON2_R
                Jc2Kind.Pro -> EimuProtocol.KIND_PRO_CONTROLLER_2
            }
            eimuClient()?.announceDevice(
                EimuClient.AnnouncedDevice(
                    handle = handle,
                    kind = kindWire,
                    mac = macFromAddress(device.address),
                    hasMag = false,
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
            eimuClient()?.sendImu(handle, timestampUs, report.gyro, report.accel, null)
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
            jc2Devices.remove(device.address)
            dropHandle(device.address)
            update(device.address) { it.copy(state = Entry.State.Found, handle = -1) }
        }
    }

    private val hopxListener = object : HopxDevice.Listener {
        override fun onReady(device: HopxDevice) {
            val handle = handles.computeIfAbsent(device.address) { nextHandle.getAndIncrement() }
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
            hopxDevices.remove(device.address)
            dropHandle(device.address)
            update(device.address) { it.copy(state = Entry.State.Found, handle = -1) }
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
    }
}
