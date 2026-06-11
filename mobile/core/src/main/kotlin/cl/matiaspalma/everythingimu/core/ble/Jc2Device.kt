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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * One connected Joy-Con 2 / Pro Controller 2 over Android GATT.
 *
 * Lifecycle: [connect] → onConnectionStateChange → requestMtu(247) +
 * CONNECTION_PRIORITY_HIGH (the Android equivalent of the desktop's
 * ThroughputOptimized request — without it the report rate sits near 16 Hz) →
 * discoverServices → subscribe input char → feature-select 0x02 then
 * (after 500 ms) 0x04, both mask 0xFF → notifications stream report 0x05.
 *
 * All writes go through the safe builders in [Jc2Protocol]; 0x15 is a brick.
 */
@SuppressLint("MissingPermission") // caller (BleDeviceManager) gates on BLUETOOTH_CONNECT
class Jc2Device(
    private val context: Context,
    private val btDevice: BluetoothDevice,
    val kind: Jc2Kind,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(device: Jc2Device)
        fun onSample(device: Jc2Device, report: Jc2Report, timestampUs: Long)
        fun onReset(device: Jc2Device, reset: Jc2ResetDetector.Reset)
        fun onBattery(device: Jc2Device, fraction: Float, charging: Boolean)
        fun onDisconnected(device: Jc2Device)
    }

    val address: String get() = btDevice.address
    val name: String get() = btDevice.name ?: "JoyCon2-$address"

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val resetDetector = Jc2ResetDetector()
    private var lastBattery = Float.NaN
    @Volatile private var closed = false

    fun connect() {
        gatt = btDevice.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun rumble(intensity: Float) {
        // JC2 rumble is preset-quantized — any positive intensity fires the
        // documented buzz preset 0x01.
        val preset: Byte = if (intensity > 0.01f) 0x01 else 0x00
        write(Jc2Protocol.buildRumblePresetCmd(preset))
    }

    fun close() {
        closed = true
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
        gatt = null
    }

    private fun write(cmd: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return
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
                if (!closed) listener.onDisconnected(this@Jc2Device)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var input: BluetoothGattCharacteristic? = null
            for (service in g.services) {
                for (c in service.characteristics) {
                    when (c.uuid) {
                        Jc2Protocol.INPUT_COMMON_UUID -> input = c
                        Jc2Protocol.WRITE_COMMAND_UUID -> writeChar = c
                        else -> {}
                    }
                }
            }
            val inputChar = input
            if (inputChar == null || writeChar == null) {
                Log.w(TAG, "jc2 characteristics not found; disconnecting")
                listener.onDisconnected(this@Jc2Device)
                return
            }
            g.setCharacteristicNotification(inputChar, true)
            val cccd = inputChar.getDescriptor(CCCD_UUID)
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
            // CCCD armed → IMU enable sequence: select features then enable.
            write(Jc2Protocol.buildFeatureSelectCmd(0x02, 0xFF.toByte()))
            Handler(Looper.getMainLooper()).postDelayed({
                if (closed) return@postDelayed
                write(Jc2Protocol.buildFeatureSelectCmd(0x04, 0xFF.toByte()))
                listener.onReady(this@Jc2Device)
            }, 500)
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
        if (c.uuid != Jc2Protocol.INPUT_COMMON_UUID) return
        val report = Jc2Protocol.parseInputReport0x05(kind, value, value.size) ?: return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        listener.onSample(this, report, nowNanos / 1_000)
        if (lastBattery.isNaN() || kotlin.math.abs(report.batteryFraction - lastBattery) > 0.01f) {
            lastBattery = report.batteryFraction
            listener.onBattery(this, report.batteryFraction, report.charging)
        }
        resetDetector.observe(report.homePressed, report.capturePressed, nowNanos)?.let {
            listener.onReset(this, it)
        }
    }

    companion object {
        private const val TAG = "Jc2Device"
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
