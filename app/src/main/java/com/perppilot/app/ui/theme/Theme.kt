package com.perppilot.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0E1116)
val Surface1 = Color(0xFF161B22)
val Surface2 = Color(0xFF1F2630)
val Outline = Color(0xFF2D3540)
val TextPrimary = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8B949E)
val Accent = Color(0xFFF0B90B)
val Green = Color(0xFF26A69A)
val Red = Color(0xFFEF5350)
val Blue = Color(0xFF58A6FF)
val Purple = Color(0xFFB392F0)

/** Up/down colours; flipped when the user prefers 紅漲綠跌. */
data class MarketColors(val up: Color, val down: Color) {
    fun of(value: Double): Color = if (value >= 0) up else down
}

val LocalMarketColors = compositionLocalOf { MarketColors(Green, Red) }

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = Blue,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    error = Red,
)

val Mono = FontFamily.Monospace

@Composable
fun PerpPilotTheme(redUp: Boolean, content: @Composable () -> Unit) {
    val colors = if (redUp) MarketColors(Red, Green) else MarketColors(Green, Red)
    androidx.compose.runtime.CompositionLocalProvider(LocalMarketColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                bodyMedium = TextStyle(fontSize = 14.sp),
                bodySmall = TextStyle(fontSize = 12.sp),
                labelSmall = TextStyle(fontSize = 11.sp),
            ),
            content = content,
        )
    }
}
