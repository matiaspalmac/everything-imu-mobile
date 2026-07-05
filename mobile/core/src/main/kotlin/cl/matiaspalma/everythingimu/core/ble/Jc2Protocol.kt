package cl.matiaspalma.everythingimu.core.ble

import android.util.Log
import java.util.UUID

enum class Jc2Kind { Left, Right, Pro }

data class Jc2Report(
    /** rad/s, SDL base + standalone remap applied. */
    val gyro: FloatArray,
    /** m/s², same remap. */
    val accel: FloatArray,
    val batteryFraction: Float,
    val charging: Boolean,
    val homePressed: Boolean,
    val capturePressed: Boolean,
)

/**
 * Joy-Con 2 / Pro Controller 2 BLE protocol facts. Mirrors
 * `crates/device-joycon/src/jc2.rs` in the desktop repo.
 *
 * SAFETY: sub-command 0x15 soft-bricks pairing. Only builders for the
 * known-safe sub-commands 0x01 / 0x02 / 0x04 exist here — never add a raw
 * write helper that takes an arbitrary sub-command byte.
 */
object Jc2Protocol {
    private const val TAG = "Jc2Protocol"

    const val NINTENDO_MFR_ID = 0x0553

    val INPUT_COMMON_UUID: UUID = UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd2")
    val WRITE_COMMAND_UUID: UUID = UUID.fromString("649d4ac9-8eb7-4e6c-af44-1ea54fe5f005")

    val ADV_PREFIX = byteArrayOf(0x01, 0x00, 0x03, 0x7E)
    private const val REPORT_0X05_LEN = 0x3C

    private const val ACCEL_M_S2_PER_LSB = 9.80665f / 4096.0f
    private const val GYRO_RAD_S_PER_LSB = 34.8f / 32767.0f
    private const val BATTERY_MV_MIN = 3000.0f
    private const val BATTERY_MV_MAX = 4200.0f

    fun kindFromManufacturerData(data: ByteArray?): Jc2Kind? {
        if (data == null || data.size < 5) return null
        for (i in 0 until 4) if (data[i] != ADV_PREFIX[i]) return null
        // Primary: scan for a known Switch 2 USB PID anywhere in the block —
        // the offset shifts between firmware revisions.
        if (data.size >= 6) {
            for (off in 4..data.size - 2) {
                when (((data[off + 1].toInt() and 0xFF) shl 8) or (data[off].toInt() and 0xFF)) {
                    0x2067 -> return Jc2Kind.Left
                    0x2066, 0x2068 -> return Jc2Kind.Right
                    0x2069 -> return Jc2Kind.Pro
                    // NSO GameCube 2 uses a different report layout — skip it.
                    0x2073 -> return null
                }
            }
        }
        // Fallback: legacy advert byte-4 hint, then assume a Joy-Con so a valid
        // Switch 2 advert with no recognizable PID is never dropped. Unlike the
        // desktop, mobile does not read flash PID (0x13012) over GATT to confirm
        // the kind, so the axis remap (Left vs Right sign flips on gyro+accel)
        // may be inverted for an advert that lacks the embedded PID.
        val hint = data[4].toInt() and 0xFF
        val kind = when (hint) {
            0x06 -> Jc2Kind.Left
            0x05 -> Jc2Kind.Right
            0x09 -> Jc2Kind.Pro
            else -> Jc2Kind.Right
        }
        Log.w(
            TAG,
            "JC2 advert carries no recognizable PID; using unreliable byte-4 hint " +
                "0x${hint.toString(16)} -> $kind (axis remap may be inverted, " +
                "no flash-PID correction)",
        )
        return kind
    }

    fun buildFeatureSelectCmd(subcmd: Byte, mask: Byte): ByteArray = byteArrayOf(
        0x0C, 0x91.toByte(), 0x01, subcmd, 0x00, 0x04, 0x00, 0x00, mask, 0x00, 0x00, 0x00,
    )

    fun buildRumblePresetCmd(preset: Byte): ByteArray = byteArrayOf(
        0x0A, 0x91.toByte(), 0x01, 0x02, 0x00, 0x04, 0x00, 0x00, preset, 0x00, 0x00, 0x00,
    )

    fun buildPlayerLedCmd(mask: Byte): ByteArray = byteArrayOf(
        0x09, 0x91.toByte(), 0x01, 0x01, 0x00, 0x04, 0x00, 0x00,
        (mask.toInt() and 0x0F).toByte(), 0x00, 0x00, 0x00,
    )

    fun parseInputReport0x05(kind: Jc2Kind, buf: ByteArray, length: Int): Jc2Report? {
        if (length < REPORT_0X05_LEN) return null
        fun i16(off: Int): Int =
            ((buf[off + 1].toInt() shl 8) or (buf[off].toInt() and 0xFF)).toShort().toInt()
        fun u16(off: Int): Int =
            ((buf[off + 1].toInt() and 0xFF) shl 8) or (buf[off].toInt() and 0xFF)

        val accel = remapAxes(
            kind,
            floatArrayOf(
                i16(0x30) * ACCEL_M_S2_PER_LSB,
                i16(0x32) * ACCEL_M_S2_PER_LSB,
                i16(0x34) * ACCEL_M_S2_PER_LSB,
            ),
        )
        val gyro = remapAxes(
            kind,
            floatArrayOf(
                i16(0x36) * GYRO_RAD_S_PER_LSB,
                i16(0x38) * GYRO_RAD_S_PER_LSB,
                i16(0x3A) * GYRO_RAD_S_PER_LSB,
            ),
        )
        val batteryMv = u16(0x1F).toFloat()
        val fraction = ((batteryMv - BATTERY_MV_MIN) / (BATTERY_MV_MAX - BATTERY_MV_MIN))
            .coerceIn(0f, 1f)
        val charging = (buf[0x21].toInt() and 0xFF) >= 0x30
        val home = (buf[0x05].toInt() and 0x10) != 0
        val capture = (buf[0x05].toInt() and 0x20) != 0
        return Jc2Report(gyro, accel, fraction, charging, home, capture)
    }

    /** SDL base remap (x, z, -y) + standalone Joy-Con ±90° about Y. */
    private fun remapAxes(kind: Jc2Kind, raw: FloatArray): FloatArray {
        val base = floatArrayOf(raw[0], raw[2], -raw[1])
        return when (kind) {
            Jc2Kind.Left -> floatArrayOf(base[2], base[1], -base[0])
            Jc2Kind.Right -> floatArrayOf(-base[2], base[1], base[0])
            Jc2Kind.Pro -> base
        }
    }
}
