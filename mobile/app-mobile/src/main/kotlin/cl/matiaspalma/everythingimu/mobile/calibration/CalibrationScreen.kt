package cl.matiaspalma.everythingimu.mobile.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.matiaspalma.everythingimu.core.tracking.TrackingController
import cl.matiaspalma.everythingimu.mobile.i18n.tr
import cl.matiaspalma.everythingimu.mobile.theme.EimuPalette
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CalibrationScreen(onClose: () -> Unit) {
    val progress by TrackingController.magCalibrationSession.progress.collectAsStateWithLifecycle()
    val t = tr
    val ctx = LocalContext.current
    var active by remember { mutableStateOf(false) }

    DisposableEffect(active) {
        if (active) TrackingController.beginMagCalibration(ctx)
        onDispose { if (active) TrackingController.cancelMagCalibration() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t.calibrate_title, style = MaterialTheme.typography.titleLarge, color = EimuPalette.FgPrimary)
        Text(t.calibrate_body, style = MaterialTheme.typography.bodyMedium, color = EimuPalette.FgSecondary)

        FigureEightGuide()

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.calibrate_coverage, style = MaterialTheme.typography.titleMedium, color = EimuPalette.FgPrimary)
            LinearProgressIndicator(
                progress = { progress.ratio },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (progress.complete) EimuPalette.Success else EimuPalette.Accent,
                trackColor = EimuPalette.BgElevated,
            )
            Text(
                "${t.calibrate_samples} ${progress.samples} · ${t.calibrate_spread} x=${"%.1f".format(progress.coverage.x)} y=${"%.1f".format(progress.coverage.y)} z=${"%.1f".format(progress.coverage.z)} µT",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = EimuPalette.FgMuted,
            )
        }

        val gyroBias = TrackingController.currentGyroBias()
        Text(
            "${t.calibrate_gyro_bias}: " + if (TrackingController.gyroBiasCalibrated()) {
                "x=${"% .4f".format(gyroBias.x)}  y=${"% .4f".format(gyroBias.y)}  z=${"% .4f".format(gyroBias.z)} rad/s"
            } else {
                t.calibrate_estimating
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = EimuPalette.FgSecondary,
        )

        if (!active) {
            Text(t.calibrate_idle_hint, style = MaterialTheme.typography.bodySmall, color = EimuPalette.FgMuted)
            Button(
                onClick = { active = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EimuPalette.Accent,
                    contentColor = EimuPalette.BgBase,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t.calibrate_start) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (TrackingController.applyMagCalibration() != null) {
                            active = false
                            onClose()
                        }
                    },
                    enabled = progress.complete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EimuPalette.Accent,
                        contentColor = EimuPalette.BgBase,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text(if (progress.complete) t.action_apply else t.calibrate_keep_moving) }
                OutlinedButton(
                    onClick = { active = false },
                    modifier = Modifier.weight(1f),
                ) { Text(t.action_cancel) }
            }
        }

        OutlinedButton(
            onClick = { TrackingController.resetCalibration() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(t.action_reset_all_cal) }
    }
}

@Composable
private fun FigureEightGuide() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(2f)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val rx = w * 0.36f
            val ry = h * 0.36f
            val path = Path()
            val steps = 128
            for (i in 0..steps) {
                val t = (i.toFloat() / steps) * (2.0 * Math.PI).toFloat()
                val x = cx + rx * sin(t.toDouble()).toFloat()
                val y = cy + ry * (sin(t.toDouble()) * cos(t.toDouble())).toFloat() * 1.4f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = EimuPalette.Accent, style = Stroke(width = 6f))
            drawCircle(color = EimuPalette.AccentBright, radius = 10f, center = Offset(cx, cy))
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}
