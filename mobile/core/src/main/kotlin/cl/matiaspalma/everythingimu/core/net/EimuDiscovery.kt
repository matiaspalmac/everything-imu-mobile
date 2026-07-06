package cl.matiaspalma.everythingimu.core.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds the eimu desktop on the LAN without typing an IP: broadcast a HELLO
 * to the listener port and take the source address of the first HELLO_ACK.
 * No dedicated discovery message needed — the desktop acks every HELLO.
 */
object EimuDiscovery {

    private const val TAG = "EimuDiscovery"
    private const val RECV_TIMEOUT_MS = 500

    suspend fun discover(
        uuid: ByteArray,
        hubName: String,
        port: Int = EimuProtocol.DEFAULT_PORT,
        timeoutMs: Long = 3_000,
        bindSocket: ((DatagramSocket) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { sock ->
                // Bind to Wi-Fi (best effort) so the broadcast leaves the Wi-Fi
                // NIC, not a Bluetooth companion proxy that can't reach the LAN.
                bindSocket?.invoke(sock)
                sock.broadcast = true
                sock.soTimeout = RECV_TIMEOUT_MS
                val hello = EimuProtocol.hello(uuid, hubName)
                // Global broadcast plus each interface's directed broadcast: many
                // AP-isolation/mesh setups drop 255.255.255.255 while still
                // forwarding 192.168.x.255.
                val targets = broadcastTargets()
                val buf = ByteArray(64)
                val pkt = DatagramPacket(buf, buf.size)
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                sock.sendHellos(hello, targets, port)
                while (System.nanoTime() < deadline) {
                    // Restore capacity: a short prior datagram shrinks pkt.length
                    // and would truncate a following HELLO_ACK below 6 bytes.
                    pkt.setLength(buf.size)
                    try {
                        sock.receive(pkt)
                        if (EimuProtocol.parseIncoming(pkt.data, pkt.length)
                            is EimuProtocol.Incoming.HelloAck
                        ) {
                            return@withContext pkt.address.hostAddress
                        }
                        // Non-matching datagram: keep listening, don't re-broadcast.
                    } catch (_: SocketTimeoutException) {
                        sock.sendHellos(hello, targets, port) // re-broadcast only when idle
                    }
                }
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "discovery failed", t)
            null
        }
    }

    private fun DatagramSocket.sendHellos(hello: ByteArray, targets: List<InetAddress>, port: Int) {
        for (target in targets) {
            try {
                send(DatagramPacket(hello, hello.size, target, port))
            } catch (_: java.io.IOException) {
                // One interface's broadcast is unreachable; try the rest.
            }
        }
    }

    /** Limited broadcast plus each up interface's directed broadcast address. */
    private fun broadcastTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        targets += InetAddress.getByName("255.255.255.255")
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return targets
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.interfaceAddresses) {
                    addr.broadcast?.let { targets += it }
                }
            }
        } catch (_: SocketException) {
            // Enumeration failed; the global broadcast still stands.
        }
        return targets.distinct()
    }
}
