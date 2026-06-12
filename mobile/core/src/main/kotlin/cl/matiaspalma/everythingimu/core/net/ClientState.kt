package cl.matiaspalma.everythingimu.core.net

enum class ConnectionState { Disconnected, Connecting, Connected, Reconnecting, Failed }

data class ClientStats(
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val lastHeartbeatAgeMs: Long = -1,
    val targetEndpoint: String? = null,
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
            val idx = (i * 2) % padded.length
            out[i] = padded.substring(idx, idx + 2).toInt(16).toByte()
        }
        // Force locally-administered + unicast bit.
        out[0] = ((out[0].toInt() and 0xFE) or 0x02).toByte()
        return out
    }
}
