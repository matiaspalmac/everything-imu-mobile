package cl.matiaspalma.everythingimu.mobile.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tabs mirror eimu desktop sidebar (House / Pulse / VibrateIcon / Bug / GearSix from @phosphor-icons/react). */
enum class EimuTab(
    val labelEn: String,
    val labelEs: String,
    val filled: ImageVector,
    val outlined: ImageVector,
) {
    Home("Home", "Inicio", Icons.Filled.Home, Icons.Outlined.Home),
    Controllers("Pads", "Mandos", Icons.Filled.SportsEsports, Icons.Outlined.SportsEsports),
    Calibrate("Calibrate", "Calibrar", Icons.Filled.GraphicEq, Icons.Outlined.GraphicEq),
    Haptics("Haptics", "Vibración", Icons.Filled.Vibration, Icons.Outlined.Vibration),
    Settings("Settings", "Ajustes", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/**
 * Shell with the ember flat background (apps/ui styles.css) + bottom NavigationBar
 * styled 1:1 with eimu desktop nav: accent fg selected, muted fg unselected.
 */
@Composable
fun EimuShell(
    current: EimuTab,
    onSelect: (EimuTab) -> Unit,
    labelOf: (EimuTab) -> String,
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(EimuPalette.BgBase)) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = EimuPalette.FgPrimary,
            bottomBar = { EimuBottomNav(current, onSelect, labelOf) },
        ) { inner ->
            // Cap content width so inputs/text don't stretch edge-to-edge on
            // landscape / tablet widths; centred, no effect on phone portrait.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                    content(inner)
                }
            }
        }
    }
}

@Composable
private fun EimuBottomNav(
    current: EimuTab,
    onSelect: (EimuTab) -> Unit,
    labelOf: (EimuTab) -> String,
) {
    NavigationBar(
        containerColor = EimuPalette.BgPanel,
        contentColor = EimuPalette.FgPrimary,
    ) {
        for (tab in EimuTab.values()) {
            val selected = tab == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.filled else tab.outlined,
                        contentDescription = labelOf(tab),
                    )
                },
                label = {
                    Text(
                        labelOf(tab),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        // 10sp keeps the longest labels ("Calibrate"/"Vibración")
                        // from ellipsizing on ~320dp small phones.
                        fontSize = 10.sp,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = EimuPalette.Accent,
                    unselectedIconColor = EimuPalette.FgMuted,
                    selectedTextColor = EimuPalette.Accent,
                    unselectedTextColor = EimuPalette.FgMuted,
                    indicatorColor = EimuPalette.AccentSoft,
                ),
            )
        }
    }
}
