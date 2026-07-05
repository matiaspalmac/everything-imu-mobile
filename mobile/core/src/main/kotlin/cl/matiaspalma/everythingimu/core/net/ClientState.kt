package cl.matiaspalma.everythingimu.core.net

enum class ConnectionState { Disconnected, Connecting, Connected, Reconnecting, Failed }

data class ClientStats(
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val lastHeartbeatAgeMs: Long = -1,
    val targetEndpoint: String? = null,
    /** Hello→ack round trip in ms; -1 until the first echoed ack arrives. */
    val rttMs: Long = -1,
)

object DeviceIdentity {
    /**
     * Stable 6-byte MAC derived from the UUID persisted to DataStore. The
     * desktop keys trackers by this MAC, so it must never change across
     * sessions for the same install.
     */
    fun macFromUuid(uuidHex: String): ByteArray {
        val cleaned = uuidHex.replace("-", "").ifBlank { "000000000000" }
        val padded = if (cleaned.length < 12) cleaned.padEnd(12, '0') else cleaned
        val out = ByteArray(6)
        for (i in 0 until 6) {
            // First 48 bits of the (random v4) UUID. The MAC must stay stable
            // across sessions for the same install, so the source bits are
            // fixed; the previous `% padded.length` was a dead no-op (idx is
            // always < 12) that only obscured this. A non-hex nibble falls back
            // to 0 instead of throwing (matches EimuClient.uuidBytes).
            val idx = i * 2
            out[i] = (padded.substring(idx, idx + 2).toIntOrNull(16) ?: 0).toByte()
        }
        // Force locally-administered + unicast bit.
        out[0] = ((out[0].toInt() and 0xFE) or 0x02).toByte()
        return out
    }
}
