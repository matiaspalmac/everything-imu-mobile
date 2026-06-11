package cl.matiaspalma.everythingimu.core.ble

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HopxProtocolTest {

    /** Build a wire record: `[0x22][seq][gx gy gz][ax ay az]` (i16 LE, gyro first). */
    private fun record(seq: Int, gyro: IntArray, accel: IntArray): ByteArray {
        val out = ByteArray(14)
        out[0] = 0x22
        out[1] = seq.toByte()
        var off = 2
        for (v in gyro + accel) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v shr 8) and 0xFF).toByte()
            off += 2
        }
        return out
    }

    @Test
    fun nameMatchesTrikiUnitsOnly() {
        assertTrue(HopxProtocol.nameMatches("Triki 257739387"))
        assertTrue(HopxProtocol.nameMatches("Triki"))
        assertFalse(HopxProtocol.nameMatches("JoyCon2-AA:BB"))
        assertFalse(HopxProtocol.nameMatches("triki"))
        assertFalse(HopxProtocol.nameMatches(null))
    }

    @Test
    fun accelDecodesFromSecondTriple() {
        val bytes = record(7, intArrayOf(0, 0, 0), intArrayOf(2048, 0, 0))
        val recs = HopxProtocol.RecordParser().feed(bytes, bytes.size)
        assertEquals(1, recs.size)
        assertEquals(7, recs[0].seq)
        assertTrue(abs(recs[0].accel[0] - 9.80665f) < 1e-3f)
        assertEquals(0f, recs[0].gyro[0])
    }

    @Test
    fun gyroDecodesFromFirstTriple() {
        val bytes = record(2, intArrayOf(1000, 0, 0), intArrayOf(0, 0, 0))
        val recs = HopxProtocol.RecordParser().feed(bytes, bytes.size)
        assertEquals(1, recs.size)
        assertTrue(abs(recs[0].gyro[0] - 1000f * HopxProtocol.GYRO_RAD_S_PER_LSB) < 1e-6f)
        assertEquals(0f, recs[0].accel[0])
    }

    @Test
    fun reassemblesRecordSplitAcrossFeeds() {
        val bytes = record(3, intArrayOf(10, -20, 30), intArrayOf(40, -50, 60))
        val parser = HopxProtocol.RecordParser()
        assertTrue(parser.feed(bytes.copyOfRange(0, 5), 5).isEmpty())
        val rest = bytes.copyOfRange(5, bytes.size)
        val recs = parser.feed(rest, rest.size)
        assertEquals(1, recs.size)
        assertEquals(3, recs[0].seq)
    }

    @Test
    fun discardsLeadingGarbageAndParsesBackToBack() {
        val garbage = byteArrayOf(0x00, -1, 0x13)
        val two = record(1, intArrayOf(1, 0, 0), intArrayOf(0, 0, 0)) +
            record(2, intArrayOf(0, 1, 0), intArrayOf(0, 0, 0))
        val bytes = garbage + two
        val recs = HopxProtocol.RecordParser().feed(bytes, bytes.size)
        assertEquals(2, recs.size)
        assertEquals(1, recs[0].seq)
        assertEquals(2, recs[1].seq)
    }

    @Test
    fun resyncsOnFalseMarker() {
        val bytes = ByteArray(1) { 0x22 } + ByteArray(13) { 0xAA.toByte() } +
            byteArrayOf(0x00) + record(5, intArrayOf(0, 0, 0), intArrayOf(0, 0, 0))
        val recs = HopxProtocol.RecordParser().feed(bytes, bytes.size)
        assertTrue(recs.any { it.seq == 5 })
    }
}
