package cl.matiaspalma.everythingimu.core.gamepad

import cl.matiaspalma.everythingimu.core.net.EimuProtocol

/**
 * Map a gamepad's USB vendor/product ID to an eimu remote-protocol kind.
 * Unrecognized motion-capable pads fall back to [EimuProtocol.KIND_GAMEPAD].
 *
 * Pure function — unit-tested on the JVM without Android classes.
 */
object GamepadKinds {

    fun kindFor(vendorId: Int, productId: Int): Int = when (vendorId) {
        0x054C -> when (productId) {
            0x0CE6, 0x0DF2 -> EimuProtocol.KIND_DUALSENSE // DualSense / Edge
            0x05C4, 0x09CC -> EimuProtocol.KIND_DUALSHOCK4 // DS4 v1 / v2
            else -> EimuProtocol.KIND_GAMEPAD
        }
        0x057E -> when (productId) {
            0x2006 -> EimuProtocol.KIND_JOYCON_L
            0x2007 -> EimuProtocol.KIND_JOYCON_R
            0x2009 -> EimuProtocol.KIND_PRO_CONTROLLER
            else -> EimuProtocol.KIND_GAMEPAD
        }
        else -> EimuProtocol.KIND_GAMEPAD
    }

    fun label(kind: Int): String = when (kind) {
        EimuProtocol.KIND_DUALSENSE -> "DualSense"
        EimuProtocol.KIND_DUALSHOCK4 -> "DualShock 4"
        EimuProtocol.KIND_JOYCON_L -> "Joy-Con (L)"
        EimuProtocol.KIND_JOYCON_R -> "Joy-Con (R)"
        EimuProtocol.KIND_PRO_CONTROLLER -> "Pro Controller"
        else -> "Gamepad"
    }

    /** Stable 6-byte locally-administered MAC from the InputDevice descriptor (FNV-1a). */
    fun macFromDescriptor(descriptor: String): ByteArray {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis
        for (b in descriptor.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return byteArrayOf(
            0x02,
            (hash and 0xFF).toByte(),
            ((hash shr 8) and 0xFF).toByte(),
            ((hash shr 16) and 0xFF).toByte(),
            ((hash shr 24) and 0xFF).toByte(),
            ((hash shr 32) and 0xFF).toByte(),
        )
    }
}
