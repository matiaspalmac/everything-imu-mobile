package cl.matiaspalma.everythingimu.core.gamepad

import cl.matiaspalma.everythingimu.core.net.EimuProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GamepadKindsTest {

    @Test
    fun mapsSonyPids() {
        assertEquals(EimuProtocol.KIND_DUALSENSE, GamepadKinds.kindFor(0x054C, 0x0CE6))
        assertEquals(EimuProtocol.KIND_DUALSENSE, GamepadKinds.kindFor(0x054C, 0x0DF2))
        assertEquals(EimuProtocol.KIND_DUALSHOCK4, GamepadKinds.kindFor(0x054C, 0x05C4))
        assertEquals(EimuProtocol.KIND_DUALSHOCK4, GamepadKinds.kindFor(0x054C, 0x09CC))
        assertEquals(EimuProtocol.KIND_GAMEPAD, GamepadKinds.kindFor(0x054C, 0x1234))
    }

    @Test
    fun mapsNintendoPids() {
        assertEquals(EimuProtocol.KIND_JOYCON_L, GamepadKinds.kindFor(0x057E, 0x2006))
        assertEquals(EimuProtocol.KIND_JOYCON_R, GamepadKinds.kindFor(0x057E, 0x2007))
        assertEquals(EimuProtocol.KIND_PRO_CONTROLLER, GamepadKinds.kindFor(0x057E, 0x2009))
        assertEquals(EimuProtocol.KIND_GAMEPAD, GamepadKinds.kindFor(0x057E, 0x9999))
    }

    @Test
    fun unknownVendorIsGenericGamepad() {
        assertEquals(EimuProtocol.KIND_GAMEPAD, GamepadKinds.kindFor(0x045E, 0x02FD))
    }

    @Test
    fun macFromDescriptorIsStableAndLocallyAdministered() {
        val a = GamepadKinds.macFromDescriptor("abc123descriptor")
        val b = GamepadKinds.macFromDescriptor("abc123descriptor")
        val c = GamepadKinds.macFromDescriptor("other")
        assertArrayEquals(a, b)
        assertEquals(6, a.size)
        assertEquals(0x02.toByte(), a[0])
        assert(!a.contentEquals(c))
    }
}
