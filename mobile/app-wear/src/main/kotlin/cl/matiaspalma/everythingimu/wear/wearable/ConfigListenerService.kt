package cl.matiaspalma.everythingimu.wear.wearable

import android.util.Log
import cl.matiaspalma.everythingimu.core.prefs.AppPrefs
import cl.matiaspalma.everythingimu.core.wearable.WearableConfigSender
import cl.matiaspalma.everythingimu.wear.setup.HostAddress
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives `host:port` pushed by the paired phone and persists it locally so the
 * watch can connect standalone. The system binds this even when the activity is
 * not running, so a fresh install picks up the address the moment it pairs.
 *
 * Persists straight through [AppPrefs] rather than [cl.matiaspalma.everythingimu.core.tracking.TrackingController]
 * — a background push must not spin up the full controller (network callback +
 * perpetual battery/tps loops) on a battery-constrained watch just to write two
 * strings. It only writes; it never re-pushes, otherwise the watch would echo
 * the DataItem it just consumed.
 */
class ConfigListenerService : WearableListenerService() {

    // App-scoped so the DataStore write survives the binder callback returning.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        val prefs = AppPrefs(applicationContext)
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != WearableConfigSender.SERVER_CONFIG_PATH) continue
            val map = DataMapItem.fromDataItem(item).dataMap
            val host = map.getString("host")?.takeIf { it.isNotBlank() } ?: continue
            // Clamp on ingest: an out-of-range port typed on the phone would
            // otherwise reach InetSocketAddress(host, port) and crash connect().
            val port = HostAddress.coercePort(map.getInt("port", HostAddress.DEFAULT_PORT))
            scope.launch {
                // A DataStore edit can throw IOException; uncaught in this
                // system-bound callback it would crash the listener process.
                runCatching { prefs.setServer(host, port) }
                    .onFailure { Log.w(TAG, "Failed to persist pushed server config", it) }
            }
        }
    }

    private companion object {
        const val TAG = "ConfigListenerService"
    }
}
