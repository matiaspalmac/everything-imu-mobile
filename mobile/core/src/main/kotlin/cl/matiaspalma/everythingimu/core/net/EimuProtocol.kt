package cl.matiaspalma.everythingimu.core.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * eimu remote protocol v1 encoders/decoders. Byte-for-byte parity with
 * `crates/device-remote/src/protocol.rs` in the desktop repo — both sides
 * have mirrored fixture tests; change one only with the other.
 *
 * Envelope: `45 49 4D 55` ("EIMU") + version u8 + msg u8, little-endian payload.
 */
object EimuProtocol {
    const val VERSION: Int = 1
    const val DEFAULT_PORT: Int = 9320

    const val MSG_HELLO = 0x01
    const val MSG_HELLO_ACK = 0x02
    const val MSG_ANNOUNCE = 0x03
    const val MSG_REMOVE = 0x04
    const val MSG_IMU = 0x05
    const val MSG_BATTERY = 0x06
    const val MSG_BUTTON = 0x07
    const val MSG_RUMBLE = 0x08

    /** MSG_IMU plus a wrapping `seq` u16 after `handle`, for loss measurement. */
    const val MSG_IMU2 = 0x09

    const val KIND_PHONE = 1
    const val KIND_WATCH = 2
    const val KIND_JOYCON2_L = 3
    const val KIND_JOYCON2_R = 4
    const val KIND_PRO_CONTROLLER_2 = 5
    const val KIND_HOPX = 6
    const val KIND_DUALSENSE = 7
    const val KIND_DUALSHOCK4 = 8
    const val KIND_JOYCON_L = 9
    const val KIND_JOYCON_R = 10
    const val KIND_PRO_CONTROLLER = 11
    const val KIND_GAMEPAD = 12

    const val RESET_YAW = 0
    const val RESET_FULL = 1

    sealed interface Incoming {
        /** [echoUs] = our hello timestamp echoed back (null from old desktops). */
        data class HelloAck(val echoUs: Long?) : Incoming
        data class Rumble(val handle: Int, val intensity: Float) : Incoming
    }

    private fun header(msg: Int, payloadSize: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(6 + payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf(0x45, 0x49, 0x4D, 0x55))
        buf.put(VERSION.toByte())
        buf.put(msg.toByte())
        return buf
    }

    fun hello(uuid: ByteArray, name: String, tSendUs: Long? = null): ByteArray {
        require(uuid.size == 16) { "uuid must be 16 bytes" }
        val nameBytes = name.toByteArray(Charsets.UTF_8).let {
            if (it.size > 64) it.copyOf(64) else it
        }
        val buf = header(MSG_HELLO, 16 + 1 + nameBytes.size + (if (tSendUs != null) 8 else 0))
        buf.put(uuid)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        // Trailing monotonic timestamp for RTT: old desktops ignore the extra
        // bytes, new ones echo it back at the end of the ack.
        if (tSendUs != null) buf.putLong(tSendUs)
        return buf.array()
    }

    fun announce(
        handle: Int,
        kind: Int,
        mac: ByteArray,
        hasMag: Boolean,
        hasBattery: Boolean,
        hasRumble: Boolean,
        rateHz: Int,
        name: String,
    ): ByteArray {
        require(mac.size == 6) { "mac must be 6 bytes" }
        val nameBytes = name.toByteArray(Charsets.UTF_8).let {
            if (it.size > 64) it.copyOf(64) else it
        }
        val buf = header(MSG_ANNOUNCE, 2 + 1 + 6 + 3 + 2 + 1 + nameBytes.size)
        buf.putShort(handle.toShort())
        buf.put(kind.toByte())
        buf.put(mac)
        buf.put(if (hasMag) 1 else 0)
        buf.put(if (hasBattery) 1 else 0)
        buf.put(if (hasRumble) 1 else 0)
        buf.putShort(rateHz.toShort())
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        return buf.array()
    }

    fun remove(handle: Int): ByteArray {
        val buf = header(MSG_REMOVE, 2)
        buf.putShort(handle.toShort())
        return buf.array()
    }

    /** Single-sample IMU message (count = 1). 45-byte sample body. */
    fun imu(
        handle: Int,
        timestampUs: Long,
        gyro: FloatArray,
        accel: FloatArray,
        mag: FloatArray?,
    ): ByteArray {
        val buf = header(MSG_IMU, 2 + 1 + 45)
        buf.putShort(handle.toShort())
        buf.put(1)
        buf.putLong(timestampUs)
        buf.putFloat(gyro[0]); buf.putFloat(gyro[1]); buf.putFloat(gyro[2])
        buf.putFloat(accel[0]); buf.putFloat(accel[1]); buf.putFloat(accel[2])
        if (mag != null) {
            buf.put(1)
            buf.putFloat(mag[0]); buf.putFloat(mag[1]); buf.putFloat(mag[2])
        } else {
            buf.put(0)
            buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f)
        }
        return buf.array()
    }

    /** Single-sample IMU message with a wrapping send counter (v2 of MSG_IMU). */
    fun imu2(
        handle: Int,
        seq: Int,
        timestampUs: Long,
        gyro: FloatArray,
        accel: FloatArray,
        mag: FloatArray?,
    ): ByteArray {
        val buf = header(MSG_IMU2, 2 + 2 + 1 + 45)
        buf.putShort(handle.toShort())
        buf.putShort(seq.toShort())
        buf.put(1)
        buf.putLong(timestampUs)
        buf.putFloat(gyro[0]); buf.putFloat(gyro[1]); buf.putFloat(gyro[2])
        buf.putFloat(accel[0]); buf.putFloat(accel[1]); buf.putFloat(accel[2])
        if (mag != null) {
            buf.put(1)
            buf.putFloat(mag[0]); buf.putFloat(mag[1]); buf.putFloat(mag[2])
        } else {
            buf.put(0)
            buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f)
        }
        return buf.array()
    }

    fun battery(handle: Int, fraction: Float, charging: Boolean): ByteArray {
        val buf = header(MSG_BATTERY, 2 + 4 + 1)
        buf.putShort(handle.toShort())
        buf.putFloat(fraction)
        buf.put(if (charging) 1 else 0)
        return buf.array()
    }

    fun button(handle: Int, reset: Int): ByteArray {
        val buf = header(MSG_BUTTON, 2 + 1)
        buf.putShort(handle.toShort())
        buf.put(reset.toByte())
        return buf.array()
    }

    fun parseIncoming(data: ByteArray, length: Int): Incoming? {
        if (length < 6) return null
        if (data[0] != 0x45.toByte() || data[1] != 0x49.toByte() ||
            data[2] != 0x4D.toByte() || data[3] != 0x55.toByte()
        ) return null
        if (data[4].toInt() != VERSION) return null
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(5)
        return when (buf.get().toInt()) {
            MSG_HELLO_ACK -> {
                val echo = if (length >= 7 + 8) {
                    buf.position(7)
                    buf.long
                } else {
                    null
                }
                Incoming.HelloAck(echo)
            }
            MSG_RUMBLE -> {
                if (length < 6 + 6) return null
                val handle = buf.short.toInt() and 0xFFFF
                val intensity = buf.float
                Incoming.Rumble(handle, intensity)
            }
            else -> null
        }
    }
}