package cl.matiaspalma.everythingimu.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * One connected HOPX ("Triki") BLE IMU tracker over Android GATT.
 *
 * Lifecycle: connect → MTU + high priority → discover NUS → subscribe TX →
 * write START_CMD to RX → 14-byte records stream in at 52 Hz.
 */
@SuppressLint("MissingPermission") // caller (BleDeviceManager) gates on BLUETOOTH_CONNECT
class HopxDevice(
    private val context: Context,
    private val btDevice: BluetoothDevice,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(device: HopxDevice)
        fun onSample(device: HopxDevice, record: HopxRecord, timestampUs: Long)
        fun onDisconnected(device: HopxDevice)
    }

    val address: String get() = btDevice.address
    val name: String get() = btDevice.name ?: "Triki-$address"

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private val parser = HopxProtocol.RecordParser()
    @Volatile private var closed = false

    fun connect() {
        // The TRANSPORT_LE overload needs API 23; BLE scanning itself is
        // gated on API 31 permissions upstream, so the fallback is theoretical.
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            btDevice.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            btDevice.connectGatt(context, false, callback)
        }
    }

    fun close() {
        closed = true
        try {
            rxChar?.let { write(it, HopxProtocol.STOP_CMD) }
        } catch (t: Throwable) {
            Log.w(TAG, "stop write failed", t)
        }
        releaseGatt()
    }

    /** Idempotently disconnect and release the native GATT client interface.
     *  connectGatt(autoConnect=false) allocates one client_if per BluetoothGatt;
     *  failing to close it on an involuntary drop leaks the interface until the
     *  ~32-slot pool is exhausted and every connect fails with status 133. */
    private fun releaseGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "gatt release failed", t)
        }
        gatt = null
    }

    private fun write(c: BluetoothGattCharacteristic, cmd: ByteArray) {
        val g = gatt ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, cmd, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                c.value = cmd
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "write failed", t)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Remote-initiated drop: release the GATT interface before
                // notifying so it is not leaked (only user-driven close() did
                // this before).
                val notify = !closed
                releaseGatt()
                if (notify) listener.onDisconnected(this@HopxDevice)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(HopxProtocol.NUS_SERVICE_UUID)
            val tx = service?.getCharacteristic(HopxProtocol.NUS_TX_UUID)
            rxChar = service?.getCharacteristic(HopxProtocol.NUS_RX_UUID)
            if (tx == null || rxChar == null) {
                Log.w(TAG, "hopx NUS characteristics not found; disconnecting")
                // Link is live but unusable — release the GATT interface so it
                // is not leaked while we abandon the connection.
                releaseGatt()
                listener.onDisconnected(this@HopxDevice)
                return
            }
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(Jc2Device.CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            rxChar?.let { write(it, HopxProtocol.START_CMD) }
            listener.onReady(this@HopxDevice)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(c, value)
        }

        @Deprecated("pre-33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotification(c, c.value ?: return)
        }
    }

    private fun handleNotification(c: BluetoothGattCharacteristic, value: ByteArray) {
        if (c.uuid != HopxProtocol.NUS_TX_UUID) return
        val records = parser.feed(value, value.size)
        if (records.isEmpty()) return
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000
        // Records inside one notification are 1/52 s apart; back-date so each
        // carries a distinct, monotonic timestamp.
        val stepUs = 1_000_000L / HopxProtocol.NATIVE_RATE_HZ
        val baseUs = nowUs - stepUs * (records.size - 1)
        records.forEachIndexed { i, record ->
            listener.onSample(this, record, baseUs + stepUs * i)
        }
    }

    companion object {
        private const val TAG = "HopxDevice"
    }
}
