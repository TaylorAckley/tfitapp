package com.tfitapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tfitapp.R

/**
 * The palette is the driveway: concrete under foot, chalk marks, cast iron, a rope with
 * safety-orange tips, and the shade under the eaves. Orange is spent in two places only —
 * the one primary action on a screen and the WORK phase of the timer.
 */
@Immutable
data class TFitColors(
    val concrete: Color,
    val chalk: Color,
    val iron: Color,
    val ironMuted: Color,
    val rule: Color,
    val signal: Color,
    val onSignal: Color,
    val shade: Color,
    val onShade: Color,
    val moss: Color,
    val amber: Color,
)

private val LightColors = TFitColors(
    concrete = Color(0xFFE8E9E5),
    chalk = Color(0xFFFBFBF9),
    iron = Color(0xFF1F2326),
    ironMuted = Color(0xFF5F6569),
    rule = Color(0xFFCBCDC8),
    signal = Color(0xFFE8540F),
    onSignal = Color(0xFFFFFFFF),
    shade = Color(0xFF2F5D7C),
    onShade = Color(0xFFFFFFFF),
    moss = Color(0xFF4F7A3A),
    amber = Color(0xFFC98A12),
)

private val DarkColors = TFitColors(
    concrete = Color(0xFF202325),
    chalk = Color(0xFF2B2E31),
    iron = Color(0xFFECEDEA),
    ironMuted = Color(0xFFA2A6A8),
    rule = Color(0xFF3F4346),
    signal = Color(0xFFF26A2A),
    onSignal = Color(0xFF1F1410),
    shade = Color(0xFF6C9CBE),
    onShade = Color(0xFF0E1A24),
    moss = Color(0xFF8DB87A),
    amber = Color(0xFFE0A63A),
)

val LocalTFitColors = staticCompositionLocalOf { LightColors }

/** Words are set in Barlow; every number in Barlow Condensed. Width is the label. */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

private val TFitTypography = Typography(
    // Numbers
    displayLarge = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 152.sp, lineHeight = 152.sp, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-0.5).sp),
    displaySmall = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 34.sp),
    headlineLarge = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 26.sp),
    // Words
    headlineMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

private val TFitShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun TFitTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkColors else LightColors
    // Material components read from the scheme; our own composables read TFit.colors directly.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.signal, onPrimary = c.onSignal,
            secondary = c.shade, onSecondary = c.onShade,
            tertiary = c.amber,
            background = c.concrete, onBackground = c.iron,
            surface = c.concrete, onSurface = c.iron,
            surfaceVariant = c.chalk, onSurfaceVariant = c.ironMuted,
            surfaceContainer = c.chalk, surfaceContainerLow = c.chalk, surfaceContainerHigh = c.chalk, surfaceContainerHighest = c.chalk,
            outline = c.rule, outlineVariant = c.rule,
            secondaryContainer = c.chalk, onSecondaryContainer = c.iron,
            error = Color(0xFFF08A7A),
        )
    } else {
        lightColorScheme(
            primary = c.signal, onPrimary = c.onSignal,
            secondary = c.shade, onSecondary = c.onShade,
            tertiary = c.amber,
            background = c.concrete, onBackground = c.iron,
            surface = c.concrete, onSurface = c.iron,
            surfaceVariant = c.chalk, onSurfaceVariant = c.ironMuted,
            surfaceContainer = c.chalk, surfaceContainerLow = c.chalk, surfaceContainerHigh = c.chalk, surfaceContainerHighest = c.chalk,
            outline = c.rule, outlineVariant = c.rule,
            secondaryContainer = c.iron, onSecondaryContainer = c.chalk,
            error = Color(0xFFB3261E),
        )
    }
    CompositionLocalProvider(LocalTFitColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = TFitTypography, shapes = TFitShapes, content = content)
    }
}

object TFit {
    val colors: TFitColors
        @Composable get() = LocalTFitColors.current
}
