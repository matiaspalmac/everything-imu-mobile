package cl.matiaspalma.everythingimu.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import java.lang.ref.WeakReference
import java.net.DatagramSocket
import java.util.Collections

/**
 * Route the eimu UDP sockets out the Wi-Fi NIC without forcing the whole
 * process onto Wi-Fi.
 *
 * Wear OS in particular defaults to a Bluetooth proxy network when paired with
 * a phone — that proxy can't reach the eimu desktop on the LAN, so our sockets
 * must go out Wi-Fi. The earlier approach bound the entire process to the
 * Wi-Fi network (`bindProcessToNetwork`), which also dragged incidental traffic
 * (e.g. the update check's HTTPS GET) onto a possibly LAN-only Wi-Fi where it
 * fails even when LTE works. Instead we learn the Wi-Fi [Network] and bind only
 * the sockets handed to [bindSocket]; everything else keeps the default route.
 */
class WifiBinder(context: Context) {

    private val cm: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var boundNetwork: Network? = null

    // Sockets that want to ride Wi-Fi. Weak so a closed/GC'd socket does not
    // leak, and re-bound when the Wi-Fi network (re)appears after they were
    // created.
    private val sockets = Collections.synchronizedList(mutableListOf<WeakReference<DatagramSocket>>())

    fun bindToWifi() {
        if (callback != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        // Transport only — do NOT require NET_CAPABILITY_INTERNET. A LAN with no
        // WAN, or a Wear Wi-Fi that drops "validated internet" when the screen
        // turns off, would otherwise stop matching and leave our sockets on the
        // Bluetooth companion proxy that can't reach the desktop.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                boundNetwork = network
                // Bind any sockets created before Wi-Fi came up (e.g. a connect
                // issued during cold start).
                rebindAll(network)
            }

            override fun onLost(network: Network) {
                // Under make-before-break handovers (onAvailable(A) →
                // onAvailable(B) → onLost(A)), the delayed onLost(A) must not
                // clear the newer, still-valid network B.
                if (network != boundNetwork) return
                boundNetwork = null
                // Leave the now-orphaned sockets alone: a DatagramSocket cannot
                // be re-bound off a dead network, so the client's reconnect will
                // build a fresh socket that registers here again.
            }
        }
        try {
            cm.requestNetwork(request, cb)
            callback = cb
        } catch (t: Throwable) {
            // CHANGE_NETWORK_STATE may be denied on some OEM ROMs; that's fine —
            // the phone is already on Wi-Fi when the user has any LAN at all.
            Log.w("WifiBinder", "requestNetwork denied; falling back to default route", t)
        }
    }

    /**
     * Bind [socket] to the Wi-Fi network so its datagrams leave the Wi-Fi NIC.
     * Registers the socket so it is (re)bound if Wi-Fi appears later. Best
     * effort: a failure leaves the socket on the default route.
     */
    fun bindSocket(socket: DatagramSocket) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        sockets.add(WeakReference(socket))
        pruneDeadRefs()
        val net = boundNetwork ?: return
        try {
            net.bindSocket(socket)
        } catch (t: Throwable) {
            Log.w("WifiBinder", "bindSocket failed; leaving on default route", t)
        }
    }

    private fun rebindAll(network: Network) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val snapshot = synchronized(sockets) { sockets.toList() }
        for (ref in snapshot) {
            val s = ref.get() ?: continue
            if (s.isClosed) continue
            try {
                network.bindSocket(s)
            } catch (t: Throwable) {
                Log.w("WifiBinder", "rebind failed", t)
            }
        }
        pruneDeadRefs()
    }

    private fun pruneDeadRefs() {
        synchronized(sockets) {
            sockets.removeAll { it.get()?.isClosed != false }
        }
    }

    fun release() {
        sockets.clear()
        boundNetwork = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val cb = callback ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
            // best-effort
        }
        callback = null
    }
}
