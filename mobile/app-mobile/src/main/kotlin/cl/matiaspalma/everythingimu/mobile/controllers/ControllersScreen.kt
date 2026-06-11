package cl.matiaspalma.everythingimu.mobile.controllers

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.matiaspalma.everythingimu.core.ble.BleDeviceManager
import cl.matiaspalma.everythingimu.core.gamepad.GamepadKinds
import cl.matiaspalma.everythingimu.core.gamepad.GamepadSensorForwarder
import cl.matiaspalma.everythingimu.core.tracking.TrackingController
import cl.matiaspalma.everythingimu.mobile.i18n.tr
import cl.matiaspalma.everythingimu.mobile.theme.CardTitle
import cl.matiaspalma.everythingimu.mobile.theme.EimuCard
import cl.matiaspalma.everythingimu.mobile.theme.EimuPalette
import cl.matiaspalma.everythingimu.mobile.theme.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow

private val emptyEntries = MutableStateFlow<List<BleDeviceManager.Entry>>(emptyList())
private val falseFlow = MutableStateFlow(false)

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
fun ControllersScreen() {
    val ctx = LocalContext.current
    val t = tr
    val manager = TrackingController.bleManager()

    var granted by remember {
        mutableStateOf(blePermissions().all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> granted = result.values.all { it } }

    val entries by (manager?.entries ?: emptyEntries).collectAsStateWithLifecycle()
    val scanning by (manager?.scanning ?: falseFlow).collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t.controllers_title, style = MaterialTheme.typography.titleLarge, color = EimuPalette.FgPrimary)

        if (TrackingController.targetMode != "eimu") {
            EimuCard {
                Text(
                    t.controllers_requires_eimu,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EimuPalette.Warn,
                )
            }
        }

        SectionHeader(t.controllers_title)
        EimuCard {
            CardTitle(t.controllers_title)
            Text(t.controllers_body, style = MaterialTheme.typography.bodySmall, color = EimuPalette.FgMuted)

            if (!granted) {
                Button(
                    onClick = { permLauncher.launch(blePermissions()) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EimuPalette.Accent,
                        contentColor = EimuPalette.BgBase,
                    ),
                ) { Text(t.controllers_perm) }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { if (scanning) manager?.stopScan() else manager?.startScan() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EimuPalette.Accent,
                            contentColor = EimuPalette.BgBase,
                        ),
                    ) { Text(if (scanning) t.controllers_stop_scan else t.controllers_scan) }
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(start = 4.dp),
                            color = EimuPalette.Accent,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            if (entries.isEmpty()) {
                Text(t.controllers_empty, style = MaterialTheme.typography.bodySmall, color = EimuPalette.FgMuted)
            }
            for (entry in entries) {
                DeviceRow(entry, manager)
            }
        }

        val forwarder = TrackingController.gamepadForwarder()
        SectionHeader(t.gamepads_title)
        EimuCard {
            CardTitle(t.gamepads_title)
            Text(t.gamepads_body, style = MaterialTheme.typography.bodySmall, color = EimuPalette.FgMuted)
            if (Build.VERSION.SDK_INT < 31) {
                Text(
                    t.gamepads_needs_android12,
                    style = MaterialTheme.typography.bodySmall,
                    color = EimuPalette.Warn,
                )
            } else if (forwarder != null) {
                val pads by forwarder.entries.collectAsStateWithLifecycle()
                if (pads.isEmpty()) {
                    Text(t.gamepads_empty, style = MaterialTheme.typography.bodySmall, color = EimuPalette.FgMuted)
                }
                for (pad in pads) {
                    GamepadRow(pad, forwarder)
                }
            }
        }

        EimuCard {
            Text(
                t.controllers_bt_classic_note,
                style = MaterialTheme.typography.bodySmall,
                color = EimuPalette.FgMuted,
            )
        }
    }
}

@Composable
private fun GamepadRow(pad: GamepadSensorForwarder.Entry, forwarder: GamepadSensorForwarder) {
    val t = tr
    val streaming = pad.handle >= 0
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pad.name, style = MaterialTheme.typography.bodyMedium, color = EimuPalette.FgPrimary)
            val status = when {
                !pad.hasMotion -> "${GamepadKinds.label(pad.kind)} · ${t.gamepads_no_motion}"
                streaming -> "${GamepadKinds.label(pad.kind)} · ${t.controllers_streaming}" +
                    if (pad.batteryPct >= 0) " · ${pad.batteryPct}%" else ""
                else -> GamepadKinds.label(pad.kind)
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    !pad.hasMotion -> EimuPalette.Warn
                    streaming -> EimuPalette.Success
                    else -> EimuPalette.FgMuted
                },
                fontFamily = FontFamily.Monospace,
            )
        }
        if (pad.hasMotion) {
            OutlinedButton(
                onClick = {
                    if (streaming) forwarder.stopForwarding(pad.deviceId)
                    else forwarder.startForwarding(pad.deviceId)
                },
            ) {
                Text(
                    if (streaming) t.gamepads_stop else t.gamepads_forward,
                    color = if (streaming) EimuPalette.FgSecondary else EimuPalette.Accent,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(entry: BleDeviceManager.Entry, manager: BleDeviceManager?) {
    val t = tr
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium, color = EimuPalette.FgPrimary)
            val kindLabel = when (entry.kind) {
                BleDeviceManager.BleKind.Jc2Left -> "Joy-Con 2 (L)"
                BleDeviceManager.BleKind.Jc2Right -> "Joy-Con 2 (R)"
                BleDeviceManager.BleKind.Pro2 -> "Pro Controller 2"
                BleDeviceManager.BleKind.Hopx -> "HOPX tracker"
            }
            val status = when (entry.state) {
                BleDeviceManager.Entry.State.Found -> kindLabel
                BleDeviceManager.Entry.State.Connecting -> "$kindLabel · ${t.controllers_connecting}"
                BleDeviceManager.Entry.State.Streaming ->
                    "$kindLabel · ${t.controllers_streaming}" +
                        if (entry.batteryPct >= 0) " · ${entry.batteryPct}%" else ""
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.state == BleDeviceManager.Entry.State.Streaming) {
                    EimuPalette.Success
                } else {
                    EimuPalette.FgMuted
                },
                fontFamily = FontFamily.Monospace,
            )
        }
        when (entry.state) {
            BleDeviceManager.Entry.State.Found -> OutlinedButton(
                onClick = { manager?.connect(entry.address) },
            ) { Text(t.controllers_connect, color = EimuPalette.Accent) }
            BleDeviceManager.Entry.State.Connecting -> CircularProgressIndicator(
                color = EimuPalette.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(end = 8.dp),
            )
            BleDeviceManager.Entry.State.Streaming -> OutlinedButton(
                onClick = { manager?.disconnect(entry.address) },
            ) { Text(t.controllers_disconnect, color = EimuPalette.FgSecondary) }
        }
    }
}
