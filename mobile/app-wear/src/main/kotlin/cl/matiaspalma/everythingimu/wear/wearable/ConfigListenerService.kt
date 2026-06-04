package cl.matiaspalma.everythingimu.wear.wearable

import cl.matiaspalma.everythingimu.core.tracking.TrackingController
import cl.matiaspalma.everythingimu.core.wearable.WearableConfigSender
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Receives `host:port` pushed by the paired phone and persists it locally so the
 * watch can connect standalone. The system binds this even when the activity is
 * not running, so a fresh install picks up the address the moment it pairs.
 *
 * Note: this only writes to DataStore via [TrackingController.persistServer] — it
 * never re-pushes, otherwise the watch would echo the DataItem it just consumed.
 */
class ConfigListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        TrackingController.ensureInit(applicationContext)
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != WearableConfigSender.SERVER_CONFIG_PATH) continue
            val map = DataMapItem.fromDataItem(item).dataMap
            val host = map.getString("host")?.takeIf { it.isNotBlank() } ?: continue
            val port = map.getInt("port", 6969)
            runBlocking { TrackingController.persistServer(host, port) }
        }
    }
}
