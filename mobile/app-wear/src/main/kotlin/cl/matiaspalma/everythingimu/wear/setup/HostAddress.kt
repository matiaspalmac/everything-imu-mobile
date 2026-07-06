package cl.matiaspalma.everythingimu.wear.setup

/**
 * Pure IPv4 `host:port` helpers backing the wheel picker. No Android types so it
 * is unit-testable on the JVM without Robolectric.
 */
object HostAddress {
    const val DEFAULT_PORT = 6969
    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    private val FALLBACK_OCTETS = intArrayOf(192, 168, 1, 100)

    /** Parse a dotted-quad into 4 octets, or null if not a strict IPv4 literal. */
    fun parseOctets(host: String): IntArray? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in parts.indices) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3) return null
            if (p.length > 1 && p[0] == '0') return null // reject "01", "007"
            // Strict ASCII only — Char.isDigit() also accepts Unicode Nd digits
            // (Arabic-Indic/fullwidth), which would violate the IPv4 contract.
            if (!p.all { it in '0'..'9' }) return null
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }

    /** Seed the octet wheels from a saved host, or a sensible LAN default. */
    fun initialOctets(host: String): IntArray =
        parseOctets(host)?.copyOf() ?: FALLBACK_OCTETS.copyOf()

    /** Render 4 octets as a dotted-quad, clamping each to 0..255 defensively. */
    fun format(octets: IntArray): String {
        require(octets.size == 4) { "expected 4 octets, got ${octets.size}" }
        return octets.joinToString(".") { it.coerceIn(0, 255).toString() }
    }

    fun coercePort(port: Int): Int = port.coerceIn(MIN_PORT, MAX_PORT)

    /** Seed the port wheel from a saved port, or the SlimeVR default. */
    fun initialPort(port: Int): Int = if (port in MIN_PORT..MAX_PORT) port else DEFAULT_PORT

    /**
     * Decide which host string to persist when the picker is saved.
     *
     * The wheels can only represent an IPv4 dotted-quad, so a host that is not
     * one (a hostname or IPv6 pushed from the phone) seeds them with the LAN
     * fallback. If the user never moved the wheels off that seed, reconstructing
     * from them would clobber the working host with the fallback. In that case
     * keep [originalHost] verbatim; otherwise persist the address the wheels now
     * describe (the user chose to type an IP).
     */
    fun resolveHost(originalHost: String, currentOctets: IntArray, initialOctets: IntArray): String {
        val untouched = currentOctets.contentEquals(initialOctets)
        if (untouched && parseOctets(originalHost) == null) return originalHost
        return format(currentOctets)
    }
}
