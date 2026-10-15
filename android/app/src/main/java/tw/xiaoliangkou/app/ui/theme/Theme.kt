package tw.xiaoliangkou.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
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
import tw.xiaoliangkou.app.R

/** 粉圓體（jf open 粉圓，SIL OFL）：圓潤可愛的繁體中文字型 */
val Huninn = FontFamily(Font(R.font.huninn, FontWeight.Normal))

/** 主題以外的可愛配色：兩個人的代表色、經期、狀態 */
@Immutable
data class CuteColors(
    val me: Color,
    val partner: Color,
    val meSoft: Color,
    val partnerSoft: Color,
    val thread: Color,
    val peach: Color,
    val butter: Color,
    val mint: Color,
    val lavender: Color,
    val sky: Color,
    val period: Color,
    val periodSoft: Color,
    val fertile: Color,
    val fertileSoft: Color,
    val ovulation: Color,
    val safe: Color,
    val good: Color,
    val bad: Color,
    val card: Color,
    val muted: Color,
)

private val LightCute = CuteColors(
    me = Color(0xFF6FAEFF), partner = Color(0xFFFF7FA8), meSoft = Color(0xFFE1EEFF), partnerSoft = Color(0xFFFFE3EC),
    thread = Color(0xFFE8455F), peach = Color(0xFFFFB38A), butter = Color(0xFFFFD86B), mint = Color(0xFF7ED6C1),
    lavender = Color(0xFFB9A6FF), sky = Color(0xFF8EC9FF),
    period = Color(0xFFE5506A), periodSoft = Color(0xFFFFE0E6), fertile = Color(0xFF8B6BE0), fertileSoft = Color(0xFFEDE6FF),
    ovulation = Color(0xFF5B3CC4), safe = Color(0xFF3DB39A),
    good = Color(0xFF34B27B), bad = Color(0xFFE5484D), card = Color(0xFFFFFFFF), muted = Color(0xFF9A7C8A),
)

private val DarkCute = CuteColors(
    me = Color(0xFF8EC0FF), partner = Color(0xFFFF9BBB), meSoft = Color(0xFF243246), partnerSoft = Color(0xFF452632),
    thread = Color(0xFFFF6F86), peach = Color(0xFFFFB99A), butter = Color(0xFFFFDB7F), mint = Color(0xFF7ED6C1),
    lavender = Color(0xFFC3B3FF), sky = Color(0xFF9CCFFF),
    period = Color(0xFFF0657E), periodSoft = Color(0xFF4A2530), fertile = Color(0xFF9E83F0), fertileSoft = Color(0xFF332A4D),
    ovulation = Color(0xFFCDBBFF), safe = Color(0xFF3FAF97),
    good = Color(0xFF5CC995), bad = Color(0xFFFF6B70), card = Color(0xFF2E2028), muted = Color(0xFFB59AA7),
)

val LocalCute = staticCompositionLocalOf { LightCute }

private val LightScheme = lightColorScheme(
    primary = Color(0xFFFF6F96), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0E9), onPrimaryContainer = Color(0xFF5A1A30),
    secondary = Color(0xFF3FB89C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F5EC), onSecondaryContainer = Color(0xFF0E4A3C),
    tertiary = Color(0xFF8E78EC), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECE6FF), onTertiaryContainer = Color(0xFF2F2163),
    background = Color(0xFFFFF6F8), onBackground = Color(0xFF4A2B39),
    surface = Color(0xFFFFF6F8), onSurface = Color(0xFF4A2B39),
    surfaceVariant = Color(0xFFFCEAF0), onSurfaceVariant = Color(0xFF8C6B7A),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFBFC),
    outline = Color(0xFFEFC9D6), outlineVariant = Color(0xFFF6DEE6),
    error = Color(0xFFE5484D),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF8FAF), onPrimary = Color(0xFF4A0F24),
    primaryContainer = Color(0xFF5E2438), onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFF7ED6C1), onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF1F4A40), onSecondaryContainer = Color(0xFFC9F3E7),
    tertiary = Color(0xFFC3B3FF), onTertiary = Color(0xFF2B1D63),
    tertiaryContainer = Color(0xFF3E3170), onTertiaryContainer = Color(0xFFE8E0FF),
    background = Color(0xFF22161D), onBackground = Color(0xFFF7E6EC),
    surface = Color(0xFF22161D), onSurface = Color(0xFFF7E6EC),
    surfaceVariant = Color(0xFF3A2831), onSurfaceVariant = Color(0xFFD7BCC7),
    surfaceContainer = Color(0xFF2E2028), surfaceContainerHigh = Color(0xFF36262F), surfaceContainerLow = Color(0xFF281B22),
    outline = Color(0xFF6B4D5A), outlineVariant = Color(0xFF4A3540),
    error = Color(0xFFFF6B70),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
    TextStyle(fontFamily = Huninn, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)

private val CuteType = Typography(
    displayLarge = style(44, 52),
    displayMedium = style(36, 44),
    headlineLarge = style(30, 38),
    headlineMedium = style(26, 34),
    headlineSmall = style(22, 30),
    titleLarge = style(21, 28),
    titleMedium = style(17, 24, spacing = 0.1),
    titleSmall = style(15, 22, spacing = 0.1),
    bodyLarge = style(16, 24),
    bodyMedium = style(14, 21),
    bodySmall = style(12, 18),
    labelLarge = style(15, 20, spacing = 0.2),
    labelMedium = style(13, 18, spacing = 0.3),
    labelSmall = style(11, 16, spacing = 0.4),
)

private val CuteShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun XlkTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCute provides if (dark) DarkCute else LightCute) {
        val scheme = if (dark) DarkScheme else LightScheme
        MaterialTheme(colorScheme = scheme, typography = CuteType, shapes = CuteShapes) {
            // 沒有包在 Surface 裡的文字也要用主題的文字顏色（深色模式才看得清楚）
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

object Cute {
    val colors: CuteColors @Composable get() = LocalCute.current
}
