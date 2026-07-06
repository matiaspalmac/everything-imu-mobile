package cl.matiaspalma.everythingimu.wear.setup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HostAddressTest {

    @Test
    fun parsesValidDottedQuad() {
        assertArrayEquals(intArrayOf(192, 168, 1, 100), HostAddress.parseOctets("192.168.1.100"))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), HostAddress.parseOctets("0.0.0.0"))
        assertArrayEquals(intArrayOf(255, 255, 255, 255), HostAddress.parseOctets("255.255.255.255"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertArrayEquals(intArrayOf(10, 0, 0, 1), HostAddress.parseOctets("  10.0.0.1 "))
    }

    @Test
    fun rejectsMalformedInput() {
        assertNull(HostAddress.parseOctets(""))
        assertNull(HostAddress.parseOctets("192.168.1"))         // too few octets
        assertNull(HostAddress.parseOctets("192.168.1.1.1"))     // too many
        assertNull(HostAddress.parseOctets("192.168.1.256"))     // out of range
        assertNull(HostAddress.parseOctets("192.168.1.-1"))      // negative
        assertNull(HostAddress.parseOctets("192.168.1.01"))      // leading zero
        assertNull(HostAddress.parseOctets("192.168.1.x"))       // non-digit
        assertNull(HostAddress.parseOctets("slimevr.local"))     // hostname
        assertNull(HostAddress.parseOctets("192.168.1.1000"))    // >3 digits
        assertNull(HostAddress.parseOctets("١٩٢.168.1.1")) // Arabic-Indic digits
        assertNull(HostAddress.parseOctets("１９２.168.1.1")) // fullwidth digits
        assertNull(HostAddress.parseOctets("192.16 8.1.1"))      // embedded whitespace
    }

    @Test
    fun formatRejectsWrongSizedArray() {
        assertThrows(IllegalArgumentException::class.java) {
            HostAddress.format(intArrayOf(192, 168, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostAddress.format(intArrayOf(192, 168, 1, 1, 1))
        }
    }

    @Test
    fun initialOctetsFallsBackToLanDefault() {
        assertArrayEquals(intArrayOf(192, 168, 1, 100), HostAddress.initialOctets(""))
        assertArrayEquals(intArrayOf(192, 168, 1, 100), HostAddress.initialOctets("garbage"))
        assertArrayEquals(intArrayOf(10, 0, 0, 5), HostAddress.initialOctets("10.0.0.5"))
    }

    @Test
    fun formatRoundTripsAndClamps() {
        assertEquals("192.168.1.100", HostAddress.format(intArrayOf(192, 168, 1, 100)))
        assertEquals("255.0.0.0", HostAddress.format(intArrayOf(300, -5, 0, 0)))
    }

    @Test
    fun portCoercionAndSeeding() {
        assertEquals(6969, HostAddress.coercePort(6969))
        assertEquals(HostAddress.MIN_PORT, HostAddress.coercePort(0))
        assertEquals(HostAddress.MAX_PORT, HostAddress.coercePort(70000))
        assertEquals(6969, HostAddress.initialPort(0))
        assertEquals(6969, HostAddress.initialPort(-1))
        assertEquals(9000, HostAddress.initialPort(9000))
    }

    @Test
    fun resolveHostReconstructsWhenIpv4Edited() {
        val seed = HostAddress.initialOctets("192.168.1.100")
        val edited = intArrayOf(10, 0, 0, 5)
        assertEquals("10.0.0.5", HostAddress.resolveHost("192.168.1.100", edited, seed))
    }

    @Test
    fun resolveHostReconstructsWhenIpv4Unchanged() {
        val seed = HostAddress.initialOctets("10.0.0.5")
        assertEquals("10.0.0.5", HostAddress.resolveHost("10.0.0.5", seed.copyOf(), seed))
    }

    @Test
    fun resolveHostPreservesHostnameWhenUntouched() {
        val seed = HostAddress.initialOctets("desktop.local")
        assertEquals("desktop.local", HostAddress.resolveHost("desktop.local", seed.copyOf(), seed))
    }

    @Test
    fun resolveHostPreservesIpv6WhenUntouched() {
        val seed = HostAddress.initialOctets("fe80::1")
        assertEquals("fe80::1", HostAddress.resolveHost("fe80::1", seed.copyOf(), seed))
    }

    @Test
    fun resolveHostReconstructsWhenHostnameEdited() {
        val seed = HostAddress.initialOctets("myserver.lan")
        val edited = intArrayOf(192, 168, 1, 50)
        assertEquals("192.168.1.50", HostAddress.resolveHost("myserver.lan", edited, seed))
    }
}
