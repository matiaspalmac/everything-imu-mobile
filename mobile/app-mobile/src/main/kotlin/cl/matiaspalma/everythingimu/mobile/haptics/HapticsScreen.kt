package cl.matiaspalma.everythingimu.mobile.haptics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.matiaspalma.everythingimu.core.haptics.HapticBridge
import cl.matiaspalma.everythingimu.core.tracking.TrackingController
import cl.matiaspalma.everythingimu.mobile.i18n.tr
import cl.matiaspalma.everythingimu.mobile.theme.EimuPalette

@Composable
fun HapticsScreen(onClose: () -> Unit) {
    val t = tr
    val bridge: HapticBridge? = TrackingController.hapticBridge()
    if (bridge == null) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(t.haptics_bridge_missing, color = EimuPalette.FgPrimary)
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t.action_back) }
        }
        return
    }

    val enabled by bridge.enabled.collectAsStateWithLifecycle()
    val state by bridge.state.collectAsStateWithLifecycle()
    val log by bridge.log.collectAsStateWithLifecycle()
    var strength by remember { mutableFloatStateOf(bridge.gain.coerceIn(0f, 1f)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t.haptics_title, style = MaterialTheme.typography.titleLarge, color = EimuPalette.FgPrimary)
        Text(
            "${t.haptics_body} (UDP ${state.port})",
            style = MaterialTheme.typography.bodyMedium,
            color = EimuPalette.FgSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (enabled) bridge.stop() else bridge.start() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (enabled) EimuPalette.Danger else EimuPalette.Accent,
                    contentColor = EimuPalette.BgBase,
                ),
                modifier = Modifier.weight(1f),
            ) { Text(if (enabled) t.haptics_stop else t.haptics_start) }

            OutlinedButton(
                onClick = { bridge.selfTest() },
                enabled = bridge.isVibratorAvailable(),
                modifier = Modifier.weight(1f),
            ) { Text(t.haptics_test) }
        }

        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t.action_back) }

        Text(
            "${t.haptics_strength}  ${"%.0f".format(strength * 100f)}%",
            style = MaterialTheme.typography.titleMedium,
            color = EimuPalette.FgPrimary,
        )
        Slider(
            value = strength,
            onValueChange = {
                strength = it
                bridge.gain = it
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = EimuPalette.Accent, activeTrackColor = EimuPalette.Accent),
        )

        AmplitudeMeter(state.activeIntensity)

        Text(t.haptics_events, style = MaterialTheme.typography.titleMedium, color = EimuPalette.FgPrimary)
        if (log.isEmpty()) {
            Text(t.haptics_waiting, style = MaterialTheme.typography.bodySmall, color = EimuPalette.FgMuted)
        } else {
            for (event in log) {
                Text(
                    "${"%.2f".format(event.intensity)}  ${event.address}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = EimuPalette.FgSecondary,
                )
            }
        }
    }
}

@Composable
private fun AmplitudeMeter(intensity: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(EimuPalette.BgElevated),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(intensity.coerceIn(0f, 1f))
                .height(18.dp)
                .background(EimuPalette.AccentBright),
        )
    }
}
