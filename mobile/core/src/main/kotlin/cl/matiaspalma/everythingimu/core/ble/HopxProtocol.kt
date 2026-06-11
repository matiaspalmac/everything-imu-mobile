package cl.matiaspalma.everythingimu.core.ble

import java.util.UUID

/** One decoded HOPX IMU record in SI units, body frame. */
data class HopxRecord(
    val seq: Int,
    /** Gyroscope, rad/s. */
    val gyro: FloatArray,
    /** Accelerometer, m/s². */
    val accel: FloatArray,
)

/**
 * HOPX ("Triki") BLE IMU tracker protocol facts. Mirrors
 * `crates/device-hopx/src/protocol.rs` in the desktop repo: Nordic UART
 * Service transport, 14-byte records framed by 0x22, gyro-first wire order,
 * LSM6DS ±2000 dps gyro (70 mdps/LSB) and ±16 g accel (2048 LSB/g), 52 Hz ODR.
 */
object HopxProtocol {
    val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** NUS TX — device → host notifications carrying IMU records. */
    val NUS_TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** NUS RX — host → device command writes (start/stop streaming). */
    val NUS_RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    val START_CMD = byteArrayOf(0x20, 0x10, 0x00, 0xD0.toByte(), 0x07, 0x34, 0x00, 0x03)
    val STOP_CMD = byteArrayOf(0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    const val NAME_PREFIX = "Triki"
    const val NATIVE_RATE_HZ = 52

    private const val STANDARD_GRAVITY = 9.80665f
    const val ACCEL_M_S2_PER_LSB = STANDARD_GRAVITY / 2048.0f
    const val GYRO_RAD_S_PER_LSB = 0.070f * (Math.PI.toFloat() / 180.0f)

    private const val FRAME_MARKER: Byte = 0x22
    private const val ALT_MARKER: Byte = 0x25
    private const val RECORD_LEN = 14

    fun nameMatches(name: String?): Boolean = name?.startsWith(NAME_PREFIX) == true

    /**
     * Reassembles fixed 14-byte records from the notification byte stream.
     * Records can span notification boundaries; bytes are buffered until a
     * full record framed by 0x22 is available, with false-marker resync.
     */
    class RecordParser {
        private val buf = ArrayList<Byte>(64)

        fun feed(data: ByteArray, length: Int): List<HopxRecord> {
            for (i in 0 until length) buf.add(data[i])
            val out = ArrayList<HopxRecord>(2)
            while (true) {
                val idx = buf.indexOfFirst { it == FRAME_MARKER }
                if (idx < 0) {
                    buf.clear()
                    break
                }
                if (idx > 0) repeat(idx) { buf.removeAt(0) }
                if (buf.size < RECORD_LEN) break
                // The byte after a real record must be the next record's marker;
                // otherwise this 0x22 was payload — step forward and rescan.
                if (buf.size > RECORD_LEN) {
                    val next = buf[RECORD_LEN]
                    if (next != FRAME_MARKER && next != ALT_MARKER) {
                        buf.removeAt(0)
                        continue
                    }
                }
                out.add(parseRecord())
                repeat(RECORD_LEN) { buf.removeAt(0) }
            }
            return out
        }

        private fun parseRecord(): HopxRecord {
            fun i16(off: Int): Int =
                ((buf[off + 1].toInt() shl 8) or (buf[off].toInt() and 0xFF)).toShort().toInt()
            // Wire order is gyro-first (offsets 2/4/6), accel second (8/10/12) —
            // confirmed on hardware in the desktop crate.
            val gyro = floatArrayOf(
                i16(2) * GYRO_RAD_S_PER_LSB,
                i16(4) * GYRO_RAD_S_PER_LSB,
                i16(6) * GYRO_RAD_S_PER_LSB,
            )
            val accel = floatArrayOf(
                i16(8) * ACCEL_M_S2_PER_LSB,
                i16(10) * ACCEL_M_S2_PER_LSB,
                i16(12) * ACCEL_M_S2_PER_LSB,
            )
            return HopxRecord(seq = buf[1].toInt() and 0xFF, gyro = gyro, accel = accel)
        }
    }
}
