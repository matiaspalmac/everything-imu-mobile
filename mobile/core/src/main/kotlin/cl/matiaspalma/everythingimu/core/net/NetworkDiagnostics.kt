package cl.matiaspalma.everythingimu.core.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.getSystemService
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiagnosticsReport(
    val wifiConnected: Boolean,
    val wifiSsid: String?,
    val localIp: String?,
    val hostReachable: Boolean,
    val serverResponded: Boolean,
    val hints: List<String>,
)

/**
 * One-shot network sanity check. Most "Connection failed" reports trace back
 * to the phone joining a guest network, the PC sitting on Ethernet behind a
 * different subnet, or the firewall blocking UDP. This surfaces those
 * conditions in plain language.
 */
object NetworkDiagnostics {

    // WifiInfo.connectionInfo/ipAddress are deprecated (API 31) in favour of an
    // async NetworkCallback + WifiInfo from TransportInfo, which also needs a
    // location permission just to read the SSID. For a one-shot diagnostic the
    // legacy synchronous reads are the pragmatic choice; suppress the noise.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun run(
        context: Context,
        host: String,
        port: Int,
    ): DiagnosticsReport {
        val cm = context.getSystemService<ConnectivityManager>()
        val wifi = context.getSystemService<WifiManager>()
        val wifiConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = cm?.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            wifi?.isWifiEnabled == true && (wifi.connectionInfo?.networkId ?: -1) != -1
        }
        val ssid = wifi?.connectionInfo?.ssid?.trim('"').takeIf { !it.isNullOrBlank() && it != "<unknown ssid>" }
        val localIp = wifi?.connectionInfo?.ipAddress?.takeIf { it != 0 }?.let { ip ->
            "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
        }

        // Probe the eimu desktop hub: HELLO out, HELLO_ACK back. The legacy
        // SlimeVR default port maps to the hub port, same as connect() does.
        val effectivePort = if (port == 6969) EimuProtocol.DEFAULT_PORT else port
        val (hostReachable, serverResponded) = if (host.isBlank()) {
            false to false
        } else {
            probeEimu(host, effectivePort)
        }

        val serverName = "the eimu desktop app"
        val hints = buildList {
            if (!wifiConnected) add("Phone is not on Wi-Fi. Tracking needs LAN, mobile data won't reach the PC.")
            if (wifiConnected && ssid == null) {
                add("SSID hidden by Android — reading it needs the Location permission and location services on.")
            }
            if (wifiConnected && localIp == null) add("Wi-Fi connected but no IPv4 address. Network may be captive.")
            if (host.isNotBlank() && !hostReachable) add("Cannot send UDP to $host:$effectivePort. Check the address is the PC's IPv4.")
            if (hostReachable && !serverResponded) add("Server didn't reply. Make sure $serverName is running and the Windows firewall allows it (Private network).")
            if (localIp != null && host.isNotBlank() && !sameSubnet24(localIp, host)) {
                add("Phone IP ($localIp) and server IP ($host) look like different /24 subnets — guest network or VLAN isolation likely.")
            }
        }

        return DiagnosticsReport(
            wifiConnected = wifiConnected,
            wifiSsid = ssid,
            localIp = localIp,
            hostReachable = hostReachable,
            serverResponded = serverResponded,
            hints = hints,
        )
    }

    private const val PROBE_TIMEOUT_MS = 1500
    private const val RESOLVE_TIMEOUT_MS = 1500L

    /** eimu hub probe: send a HELLO, expect a HELLO_ACK back. */
    private suspend fun probeEimu(host: String, port: Int): Pair<Boolean, Boolean> {
        // Bound name resolution: soTimeout only caps receive(), not the
        // synchronous lookup, so a black-hole resolver could otherwise hang the
        // diagnostic spinner. IPv4/IPv6 literals resolve instantly (no lookup).
        val addr = resolveBounded(host) ?: return false to false

        return withContext(Dispatchers.IO) {
            var sock: DatagramSocket? = null
            try {
                val s = DatagramSocket()
                sock = s
                s.soTimeout = PROBE_TIMEOUT_MS
                // connect() so a closed UDP port surfaces as PortUnreachableException
                // (reachable, no server) instead of send() silently "succeeding"
                // against a dead host — send success is not reachability.
                s.connect(addr, port)
                val hello = EimuProtocol.hello(ByteArray(16), "diagnostics")
                s.send(DatagramPacket(hello, hello.size, addr, port))
                val buf = ByteArray(64)
                val recv = DatagramPacket(buf, buf.size)
                s.receive(recv)
                val acked =
                    EimuProtocol.parseIncoming(recv.data, recv.length) is EimuProtocol.Incoming.HelloAck
                true to acked
            } catch (_: java.net.PortUnreachableException) {
                // ICMP came back: host reachable, nothing listening on the port.
                true to false
            } catch (_: java.net.SocketTimeoutException) {
                // Total silence — can't confirm the host is reachable over UDP.
                false to false
            } catch (_: Throwable) {
                false to false
            } finally {
                sock?.close()
            }
        }
    }

    /**
     * Resolve [host] with a hard wall-clock cap. `getByName` is a blocking call
     * that coroutine cancellation cannot interrupt, so the lookup runs on a
     * throwaway daemon thread that is abandoned if it exceeds the deadline.
     */
    private suspend fun resolveBounded(host: String): InetAddress? = withContext(Dispatchers.IO) {
        val slot = java.util.concurrent.atomic.AtomicReference<InetAddress?>()
        val worker = Thread {
            slot.set(runCatching { InetAddress.getByName(host) }.getOrNull())
        }.apply { isDaemon = true }
        worker.start()
        worker.join(RESOLVE_TIMEOUT_MS)
        slot.get()
    }

    private fun sameSubnet24(a: String, b: String): Boolean {
        val pa = a.split(".")
        val pb = b.split(".")
        if (pa.size != 4 || pb.size != 4) return true // can't compare — don't warn
        return pa[0] == pb[0] && pa[1] == pb[1] && pa[2] == pb[2]
    }
}
