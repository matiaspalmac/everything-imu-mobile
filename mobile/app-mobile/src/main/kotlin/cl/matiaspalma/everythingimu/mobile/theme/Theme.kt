package cl.matiaspalma.everythingimu.mobile.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Token bundle mirroring apps/ui styles.css :root variables. */
data class Palette(
    val BgBase: Color,
    val BgPanel: Color,
    val BgElevated: Color,
    val FgPrimary: Color,
    val FgSecondary: Color,
    val FgMuted: Color,
    val Accent: Color,
    val AccentBright: Color,
    val AccentDeep: Color,
    val Danger: Color,
    val Success: Color,
    val Warn: Color,
    val Outline: Color,
    val WarnSoft: Color,
    val AccentSoft: Color,
)

/** Ember dark — charcoal neutrals, orange accent hue 34. */
val DarkPalette = Palette(
    BgBase = Color(0xFF101013),
    BgPanel = Color(0xFF16171B),
    BgElevated = Color(0xFF1F2025),
    FgPrimary = Color(0xFFEBEBED),
    FgSecondary = Color(0xFFA4A4A8),
    FgMuted = Color(0xFF6B6B6F),
    Accent = Color(0xFFF7502A),
    AccentBright = Color(0xFFFF7444),
    AccentDeep = Color(0xFFCC361E),
    Danger = Color(0xFFDA3F58),
    Success = Color(0xFF47B777),
    Warn = Color(0xFFF9AD26),
    Outline = Color(0x33EBEBED),
    WarnSoft = Color(0x24F9AD26),
    AccentSoft = Color(0x24F7502A),
)

/** Ember light — same hue anchors, inverted lightness. */
val LightPalette = Palette(
    BgBase = Color(0xFFF3F3F5),
    BgPanel = Color(0xFFE9E9EB),
    BgElevated = Color(0xFFDDDEE1),
    FgPrimary = Color(0xFF1A1A1D),
    FgSecondary = Color(0xFF424246),
    FgMuted = Color(0xFF68696C),
    Accent = Color(0xFFD63911),
    AccentBright = Color(0xFFF65311),
    AccentDeep = Color(0xFFAC1D03),
    Danger = Color(0xFFBD1F42),
    Success = Color(0xFF008548),
    Warn = Color(0xFFC78200),
    Outline = Color(0x331A1A1D),
    WarnSoft = Color(0x2AC78200),
    AccentSoft = Color(0x2AD63911),
)

enum class ThemeMode(val code: String) {
    Dark("dark"),
    Light("light"),
    System("system"),
    ;
    companion object {
        fun fromCode(code: String?): ThemeMode = when (code) {
            "light" -> Light
            "system" -> System
            else -> Dark
        }
    }
}

/**
 * Backing store mirrored across the app. Reads via @Composable observe via Compose snapshot;
 * non-composable reads (e.g. ColorScheme builders) snapshot the current value at call time.
 */
object EimuPalette {
    var active: Palette by mutableStateOf(DarkPalette)

    val BgBase: Color get() = active.BgBase
    val BgPanel: Color get() = active.BgPanel
    val BgElevated: Color get() = active.BgElevated
    val FgPrimary: Color get() = active.FgPrimary
    val FgSecondary: Color get() = active.FgSecondary
    val FgMuted: Color get() = active.FgMuted
    val Accent: Color get() = active.Accent
    val AccentBright: Color get() = active.AccentBright
    val AccentDeep: Color get() = active.AccentDeep
    val Danger: Color get() = active.Danger
    val Success: Color get() = active.Success
    val Warn: Color get() = active.Warn
    val Outline: Color get() = active.Outline
    val WarnSoft: Color get() = active.WarnSoft
    val AccentSoft: Color get() = active.AccentSoft
}

@Composable
private fun eimuScheme(): ColorScheme {
    val p = EimuPalette.active
    val base = if (p == LightPalette) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = p.Accent,
        onPrimary = p.BgBase,
        primaryContainer = p.AccentDeep,
        onPrimaryContainer = p.FgPrimary,
        secondary = p.AccentBright,
        onSecondary = p.BgBase,
        background = p.BgBase,
        onBackground = p.FgPrimary,
        surface = p.BgPanel,
        onSurface = p.FgPrimary,
        surfaceVariant = p.BgElevated,
        onSurfaceVariant = p.FgSecondary,
        outline = p.Outline,
        outlineVariant = p.FgMuted,
        error = p.Danger,
        onError = p.BgBase,
        tertiary = p.Success,
    )
}

private val EimuTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
)

/** Resolves the active palette before composing children — call once at root. */
@Composable
fun applyThemeMode(mode: ThemeMode) {
    val sysDark = isSystemInDarkTheme()
    val next = when (mode) {
        ThemeMode.Dark -> DarkPalette
        ThemeMode.Light -> LightPalette
        ThemeMode.System -> if (sysDark) DarkPalette else LightPalette
    }
    if (EimuPalette.active != next) EimuPalette.active = next
}

@Composable
fun EimuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = eimuScheme(),
        typography = EimuTypography,
        content = content,
    )
}
