package cl.matiaspalma.everythingimu.core.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EimuProtocolTest {

    private fun le(block: ByteBuffer.() -> Unit): ByteArray {
        val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buf.block()
        return buf.array().copyOf(buf.position())
    }

    @Test
    fun helloLayout() {
        val uuid = ByteArray(16) { 0xAA.toByte() }
        val pkt = EimuProtocol.hello(uuid, "Pixe")
        val expected = le {
            put(byteArrayOf(0x45, 0x49, 0x4D, 0x55, 0x01, 0x01))
            put(uuid)
            put(4)
            put("Pixe".toByteArray(Charsets.UTF_8))
        }
        assertArrayEquals(expected, pkt)
    }

    @Test
    fun announceLayout() {
        val pkt = EimuProtocol.announce(
            handle = 7,
            kind = EimuProtocol.KIND_JOYCON2_R,
            mac = byteArrayOf(1, 2, 3, 4, 5, 6),
            hasMag = false,
            hasBattery = true,
            hasRumble = true,
            rateHz = 120,
            name = "JC2",
        )
        val expected = le {
            put(byteArrayOf(0x45, 0x49, 0x4D, 0x55, 0x01, 0x03))
            putShort(7)
            put(EimuProtocol.KIND_JOYCON2_R.toByte())
            put(byteArrayOf(1, 2, 3, 4, 5, 6))
            put(0); put(1); put(1)
            putShort(120)
            put(3)
            put("JC2".toByteArray(Charsets.UTF_8))
        }
        assertArrayEquals(expected, pkt)
    }

    @Test
    fun imuSingleSampleWithMag() {
        val pkt = EimuProtocol.imu(
            handle = 0,
            timestampUs = 1_000L,
            gyro = floatArrayOf(0.1f, 0.2f, 0.3f),
            accel = floatArrayOf(9.8f, 0f, 0.1f),
            mag = floatArrayOf(20f, -30f, 40f),
        )
        val expected = le {
            put(byteArrayOf(0x45, 0x49, 0x4D, 0x55, 0x01, 0x05))
            putShort(0)
            put(1)
            putLong(1_000L)
            putFloat(0.1f); putFloat(0.2f); putFloat(0.3f)
            putFloat(9.8f); putFloat(0f); putFloat(0.1f)
            put(1)
            putFloat(20f); putFloat(-30f); putFloat(40f)
        }
        assertArrayEquals(expected, pkt)
        assertEquals(6 + 2 + 1 + 45, pkt.size)
    }

    @Test
    fun imuWithoutMagPadsMagBytes() {
        val pkt = EimuProtocol.imu(
            handle = 3,
            timestampUs = 2_000L,
            gyro = floatArrayOf(0.4f, 0.5f, 0.6f),
            accel = floatArrayOf(0f, 9.8f, 0f),
            mag = null,
        )
        assertEquals(6 + 2 + 1 + 45, pkt.size)
        assertEquals(0, pkt[6 + 2 + 1 + 32].toInt()) // has_mag flag
    }

    @Test
    fun batteryButtonRemoveLayout() {
        assertArrayEquals(
            le {
                put(byteArrayOf(0x45, 0x49, 0x4D, 0x55, 0x01, 0x06))
                putShort(3); putFloat(0.75f); put(1)
            },
            EimuProtocol.battery(3, 0.75f, charging = true),
        )
        assertArrayEquals(
            le {
                put(byteArrayOf(0x45, 0x49, 0x4D, 0x55, 0x01, 0x07))
                putShort(3); put(1)
            },
            EimuProtocol.button(3, EimuProtocol.RESET_FULL),
        )
        assertArrayEquals(
            le {
                put(byteArrayOf(0x45, 0x49, 0x4D, 0x55, 0x01, 0x04))
                putShort(9)
            },
            EimuProtocol.remove(9),
        )
    }

    @Test
    fun parsesHelloAckAndRumble() {
        assertEquals(
            EimuProtocol.Incoming.HelloAck,
            EimuProtocol.parseIncoming(byteArrayOf(0x45, 0x49, 0x4D, 0x55, 0x01, 0x02, 0x01), 7),
        )
        val rumble = le {
            put(byteArrayOf(0x45, 0x49, 0x4D, 0x55, 0x01, 0x08))
            putShort(2); putFloat(0.5f)
        }
        val parsed = EimuProtocol.parseIncoming(rumble, rumble.size)
        val r = parsed as EimuProtocol.Incoming.Rumble
        assertEquals(2, r.handle)
        assertEquals(0.5f, r.intensity, 1e-6f)
    }

    @Test
    fun rejectsBadMagicVersionOrTruncated() {
        assertNull(EimuProtocol.parseIncoming(ByteArray(6), 6))
        assertNull(EimuProtocol.parseIncoming(byteArrayOf(0x45, 0x49, 0x4D, 0x55, 0x02, 0x02, 0x01), 7))
        assertNull(EimuProtocol.parseIncoming(byteArrayOf(0x45, 0x49, 0x4D, 0x55, 0x01, 0x08, 0x00), 7))
    }
}