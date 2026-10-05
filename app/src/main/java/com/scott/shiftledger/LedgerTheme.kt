package com.scott.shiftledger

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily

enum class ColorPalette(val label: String, val lightAccent: Color, val darkAccent: Color, val container: Color) {
    FOREST("Forest", Color(0xFF176B51), Color(0xFF8AD6B1), Color(0xFFDDEFE4)),
    OCEAN("Ocean", Color(0xFF185FAD), Color(0xFFA1C9FF), Color(0xFFD8E8FF)),
    VIOLET("Violet", Color(0xFF6846A4), Color(0xFFD4BBFF), Color(0xFFECDDFF)),
    ROSE("Rose", Color(0xFF9A365D), Color(0xFFFFB1CE), Color(0xFFFFD9E5)),
    AMBER("Amber", Color(0xFF8A5000), Color(0xFFFFBD72), Color(0xFFFFE3BE)),
    SLATE("Slate", Color(0xFF455569), Color(0xFFB7C9E0), Color(0xFFDFE7F1)),
    BLACK("Pure Black", Color.White, Color(0xFFBFBFBF), Color.Black);

    fun colors(dark: Boolean): ColorScheme {
        if (this == BLACK) return BlackColors
        if (this == FOREST) return if (dark) DarkColors else LightColors
        val ink = lerp(Color(0xFF18212B), lightAccent, .12f)
        val muted = lerp(Color(0xFF505D69), lightAccent, .12f)
        val night = lerp(Color(0xFF11151C), lightAccent, .07f)
        val panel = lerp(Color(0xFF1B222C), lightAccent, .10f)
        val paleInk = lerp(Color(0xFFE8EDF5), darkAccent, .06f)
        val deepAccent = lerp(Color(0xFF152232), lightAccent, .36f)
        val background = lerp(Color(0xFFF7F8FB), container, .18f)
        return if (dark) DarkColors.copy(
            primary = darkAccent, onPrimary = ink,
            primaryContainer = deepAccent, onPrimaryContainer = container,
            secondary = lerp(Color(0xFFBBC8D7), darkAccent, .16f), onSecondary = ink,
            secondaryContainer = deepAccent, onSecondaryContainer = container,
            background = night, onBackground = paleInk, surface = night, onSurface = paleInk,
            surfaceVariant = lerp(panel, darkAccent, .10f), onSurfaceVariant = Color(0xFFBBC8D7),
            surfaceContainerLowest = panel, surfaceContainerLow = panel,
            surfaceContainer = lerp(panel, darkAccent, .03f), surfaceContainerHigh = lerp(panel, darkAccent, .07f),
            surfaceContainerHighest = lerp(panel, darkAccent, .11f), surfaceBright = lerp(panel, darkAccent, .14f), surfaceDim = night,
            outline = Color(0xFF8999AD), outlineVariant = lerp(panel, darkAccent, .22f),
            inverseSurface = paleInk, inverseOnSurface = ink, inversePrimary = lightAccent, surfaceTint = darkAccent,
        ) else LightColors.copy(
            primary = lightAccent, onPrimary = Color.White,
            primaryContainer = container, onPrimaryContainer = ink,
            secondary = muted, onSecondary = Color.White,
            secondaryContainer = container, onSecondaryContainer = ink,
            background = background, onBackground = ink, surface = background, onSurface = ink,
            surfaceVariant = lerp(Color.White, container, .60f), onSurfaceVariant = muted,
            surfaceContainerLowest = Color.White, surfaceContainerLow = lerp(Color.White, container, .22f),
            surfaceContainer = lerp(Color.White, container, .40f), surfaceContainerHigh = lerp(Color.White, container, .58f),
            surfaceContainerHighest = lerp(Color.White, container, .75f), surfaceBright = background, surfaceDim = lerp(Color.White, container, .90f),
            outline = Color(0xFF778493), outlineVariant = lerp(Color(0xFFD5DDE6), container, .35f),
            inverseSurface = ink, inverseOnSurface = paleInk, inversePrimary = darkAccent, surfaceTint = lightAccent,
        )
    }

    fun accents(dark: Boolean): LedgerAccentColors {
        if (this == BLACK) return LedgerAccentColors(Color.Black, Color.White, Color(0xFFCCCCCC))
        if (this == FOREST) return if (dark) DarkAccents else LightAccents
        return if (dark) LedgerAccentColors(lerp(Color(0xFF152232), lightAccent, .36f), Color(0xFFF3F6FC), container)
        else LedgerAccentColors(lightAccent, Color.White, lerp(Color.White, container, .45f))
    }
}

enum class LedgerFont(val label: String, val family: FontFamily, val description: String) {
    MODERN("Modern", FontFamily.SansSerif, "Clean and familiar"),
    CLASSIC("Classic", FontFamily.Serif, "A traditional ledger feel"),
    MONO("Mono", FontFamily.Monospace, "Evenly spaced letters and numbers");

    fun typography(): Typography {
        val base = Typography()
        return Typography(
            displayLarge = base.displayLarge.copy(fontFamily = family), displayMedium = base.displayMedium.copy(fontFamily = family), displaySmall = base.displaySmall.copy(fontFamily = family),
            headlineLarge = base.headlineLarge.copy(fontFamily = family), headlineMedium = base.headlineMedium.copy(fontFamily = family), headlineSmall = base.headlineSmall.copy(fontFamily = family),
            titleLarge = base.titleLarge.copy(fontFamily = family), titleMedium = base.titleMedium.copy(fontFamily = family), titleSmall = base.titleSmall.copy(fontFamily = family),
            bodyLarge = base.bodyLarge.copy(fontFamily = family), bodyMedium = base.bodyMedium.copy(fontFamily = family), bodySmall = base.bodySmall.copy(fontFamily = family),
            labelLarge = base.labelLarge.copy(fontFamily = family), labelMedium = base.labelMedium.copy(fontFamily = family), labelSmall = base.labelSmall.copy(fontFamily = family),
        )
    }
}

data class LedgerAppearance(val palette: ColorPalette = ColorPalette.FOREST, val font: LedgerFont = LedgerFont.MODERN)
val LocalLedgerAppearance = staticCompositionLocalOf { LedgerAppearance() }

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark");

    @Composable
    fun isDark(): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK -> true
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B51), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEFE4), onPrimaryContainer = Color(0xFF18322B),
    secondary = Color(0xFF576A5E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEFE4), onSecondaryContainer = Color(0xFF18322B),
    tertiary = Color(0xFF766023), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3ECD9), onTertiaryContainer = Color(0xFF655126),
    background = Color(0xFFF5F6F0), onBackground = Color(0xFF18322B),
    surface = Color(0xFFF5F6F0), onSurface = Color(0xFF18322B),
    surfaceVariant = Color(0xFFEBEEE6), onSurfaceVariant = Color(0xFF576A5E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3EB),
    surfaceContainer = Color(0xFFEBEEE6), surfaceContainerHigh = Color(0xFFE5EAE1),
    surfaceContainerHighest = Color(0xFFDFE5DC),
    surfaceBright = Color(0xFFF5F6F0), surfaceDim = Color(0xFFDFE5DC),
    outline = Color(0xFF7B8980), outlineVariant = Color(0xFFD8DFD6),
    inverseSurface = Color(0xFF24332B), inverseOnSurface = Color(0xFFE4EDE5),
    inversePrimary = Color(0xFF8AD6B1), surfaceTint = Color(0xFF176B51),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD6B1), onPrimary = Color(0xFF073B28),
    primaryContainer = Color(0xFF254D3B), onPrimaryContainer = Color(0xFFCBF1DA),
    secondary = Color(0xFFB3C9BB), onSecondary = Color(0xFF213B2D),
    secondaryContainer = Color(0xFF2B4939), onSecondaryContainer = Color(0xFFD5EBDD),
    tertiary = Color(0xFFE2C681), onTertiary = Color(0xFF40320B),
    tertiaryContainer = Color(0xFF40351C), onTertiaryContainer = Color(0xFFF5DB9B),
    background = Color(0xFF101813), onBackground = Color(0xFFE4EDE5),
    surface = Color(0xFF101813), onSurface = Color(0xFFE4EDE5),
    surfaceVariant = Color(0xFF2B3830), onSurfaceVariant = Color(0xFFBBC8BF),
    surfaceContainerLowest = Color(0xFF19271E), surfaceContainerLow = Color(0xFF19271E),
    surfaceContainer = Color(0xFF1F2D24), surfaceContainerHigh = Color(0xFF28372D),
    surfaceContainerHighest = Color(0xFF324137),
    surfaceBright = Color(0xFF34463A), surfaceDim = Color(0xFF101813),
    outline = Color(0xFF82958A), outlineVariant = Color(0xFF3D5043),
    inverseSurface = Color(0xFFE4EDE5), inverseOnSurface = Color(0xFF24332B),
    inversePrimary = Color(0xFF176B51), surfaceTint = Color(0xFF8AD6B1),
)

private val BlackColors = DarkColors.copy(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color.Black, onPrimaryContainer = Color.White,
    secondary = Color(0xFFCCCCCC), onSecondary = Color.Black,
    secondaryContainer = Color.Black, onSecondaryContainer = Color.White,
    tertiary = Color(0xFFE2C681), onTertiary = Color.Black,
    tertiaryContainer = Color.Black, onTertiaryContainer = Color(0xFFF5DB9B),
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color.Black, onSurfaceVariant = Color(0xFFCCCCCC),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color.Black, surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black, surfaceContainerHighest = Color.Black, surfaceBright = Color.Black, surfaceDim = Color.Black,
    outline = Color(0xFF808080), outlineVariant = Color(0xFF444444),
    inverseSurface = Color.White, inverseOnSurface = Color.Black, inversePrimary = Color(0xFF333333), surfaceTint = Color.Black,
)

@Immutable
data class LedgerAccentColors(val earnings: Color, val onEarnings: Color, val earningsDetail: Color)

private val LightAccents = LedgerAccentColors(Color(0xFF176B51), Color.White, Color(0xFFD5EBDD))
private val DarkAccents = LedgerAccentColors(Color(0xFF163E2F), Color(0xFFE3F4E8), Color(0xFFB2D5BF))
val LocalLedgerAccents = staticCompositionLocalOf { LightAccents }

@Composable
fun LedgerTheme(dark: Boolean, palette: ColorPalette = ColorPalette.FOREST, font: LedgerFont = LedgerFont.MODERN, content: @Composable () -> Unit) {
    val colors = androidx.compose.runtime.remember(dark, palette) { palette.colors(dark) }
    val typography = androidx.compose.runtime.remember(font) { font.typography() }
    CompositionLocalProvider(LocalLedgerAccents provides palette.accents(dark), LocalLedgerAppearance provides LedgerAppearance(palette, font)) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
