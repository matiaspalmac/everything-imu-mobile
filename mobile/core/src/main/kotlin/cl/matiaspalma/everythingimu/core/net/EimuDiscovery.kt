package cl.matiaspalma.everythingimu.core.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    ): String? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = RECV_TIMEOUT_MS
                val hello = EimuProtocol.hello(uuid, hubName)
                val broadcast = InetAddress.getByName("255.255.255.255")
                val buf = ByteArray(64)
                val pkt = DatagramPacket(buf, buf.size)
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                while (System.nanoTime() < deadline) {
                    sock.send(DatagramPacket(hello, hello.size, broadcast, port))
                    try {
                        sock.receive(pkt)
                        if (EimuProtocol.parseIncoming(pkt.data, pkt.length)
                            is EimuProtocol.Incoming.HelloAck
                        ) {
                            return@withContext pkt.address.hostAddress
                        }
                    } catch (_: SocketTimeoutException) {
                        // re-broadcast and keep listening
                    }
                }
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "discovery failed", t)
            null
        }
    }
}
