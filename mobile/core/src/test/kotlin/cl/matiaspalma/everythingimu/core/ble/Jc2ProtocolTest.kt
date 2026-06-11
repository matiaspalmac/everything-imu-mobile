package cl.matiaspalma.everythingimu.core.ble

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Jc2ProtocolTest {

    @Test
    fun detectsKindFromPidInAdvert() {
        // prefix 01 00 03 7E, byte4 misleading 0x05 (Right), PID 0x2067 (Left) wins
        val data = byteArrayOf(0x01, 0x00, 0x03, 0x7E, 0x05, 0x00, 0x67, 0x20)
        assertEquals(Jc2Kind.Left, Jc2Protocol.kindFromManufacturerData(data))
    }

    @Test
    fun rejectsWrongPrefixAndGameCube() {
        assertNull(Jc2Protocol.kindFromManufacturerData(byteArrayOf(-1, 0x00, 0x03, 0x7E, 0x06)))
        val gc = byteArrayOf(0x01, 0x00, 0x03, 0x7E, 0x05, 0x00, 0x73, 0x20)
        assertNull(Jc2Protocol.kindFromManufacturerData(gc))
        assertNull(Jc2Protocol.kindFromManufacturerData(null))
        assertNull(Jc2Protocol.kindFromManufacturerData(byteArrayOf(0x01, 0x00)))
    }

    @Test
    fun detectsProControllerFromPid() {
        val data = byteArrayOf(0x01, 0x00, 0x03, 0x7E, 0x00, 0x00, 0x69, 0x20)
        assertEquals(Jc2Kind.Pro, Jc2Protocol.kindFromManufacturerData(data))
    }

    @Test
    fun unknownByte4FallsBackToRight() {
        val data = byteArrayOf(0x01, 0x00, 0x03, 0x7E, 0x42)
        assertEquals(Jc2Kind.Right, Jc2Protocol.kindFromManufacturerData(data))
    }

    @Test
    fun commandLayouts() {
        assertArrayEquals(
            byteArrayOf(0x0C, 0x91.toByte(), 0x01, 0x02, 0x00, 0x04, 0x00, 0x00, 0x84.toByte(), 0x00, 0x00, 0x00),
            Jc2Protocol.buildFeatureSelectCmd(0x02, 0x84.toByte()),
        )
        assertArrayEquals(
            byteArrayOf(0x0A, 0x91.toByte(), 0x01, 0x02, 0x00, 0x04, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00),
            Jc2Protocol.buildRumblePresetCmd(0x01),
        )
        assertArrayEquals(
            byteArrayOf(0x09, 0x91.toByte(), 0x01, 0x01, 0x00, 0x04, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00),
            Jc2Protocol.buildPlayerLedCmd(0b1111_0011.toByte()),
        )
    }

    @Test
    fun parsesReport0x05() {
        val buf = ByteArray(0x3C)
        buf[0x05] = 0x30 // home + capture
        put16(buf, 0x1F, 3900)
        buf[0x21] = 0x34 // charging
        put16(buf, 0x30, 4096) // 1 g on X
        put16(buf, 0x36, 1000)
        val p = Jc2Protocol.parseInputReport0x05(Jc2Kind.Left, buf, buf.size)!!
        assertTrue(p.homePressed)
        assertTrue(p.capturePressed)
        assertTrue(p.charging)
        assertTrue(p.batteryFraction in 0.70f..0.80f)
        val accelNorm = sqrt(
            p.accel[0] * p.accel[0] + p.accel[1] * p.accel[1] + p.accel[2] * p.accel[2],
        )
        assertTrue(abs(accelNorm - 9.806f) < 0.25f)
        assertTrue(p.gyro.all { it.isFinite() })
    }

    @Test
    fun rejectsShortReport() {
        assertNull(Jc2Protocol.parseInputReport0x05(Jc2Kind.Left, ByteArray(0x3B), 0x3B))
    }

    @Test
    fun proAxisRemapIsBaseOnly() {
        // Pro 2 gets the SDL base remap (x, z, -y) with no standalone flip:
        // 1 g on raw Y must land on -Z.
        val buf = ByteArray(0x3C)
        put16(buf, 0x32, 4096) // 1 g on raw Y
        val p = Jc2Protocol.parseInputReport0x05(Jc2Kind.Pro, buf, buf.size)!!
        assertTrue(abs(p.accel[2] + 9.80665f) < 1e-3f)
    }

    @Test
    fun resetDetectorShortLongAndCapture() {
        val d = Jc2ResetDetector()
        // home short press → yaw on release
        assertNull(d.observe(home = true, capture = false, nowNanos = 0))
        assertEquals(
            Jc2ResetDetector.Reset.Yaw,
            d.observe(home = false, capture = false, nowNanos = 500_000_000),
        )
        // home long press → full at threshold while held
        assertNull(d.observe(home = true, capture = false, nowNanos = 2_000_000_000))
        assertEquals(
            Jc2ResetDetector.Reset.Full,
            d.observe(home = true, capture = false, nowNanos = 3_100_000_000),
        )
        // release after long emits nothing more
        assertNull(d.observe(home = false, capture = false, nowNanos = 3_200_000_000))
        // capture rising edge → yaw (after debounce window)
        assertEquals(
            Jc2ResetDetector.Reset.Yaw,
            d.observe(home = false, capture = true, nowNanos = 5_000_000_000),
        )
    }

    private fun put16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
