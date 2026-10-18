package com.b570.pilot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.AccentChoice

/** 強調色組：[primary] 主色、[secondary] 搭配色（漸層另一端）。 */
@Immutable
data class AccentPalette(val primary: Color, val secondary: Color) {
    companion object {
        val CYAN = AccentPalette(Color(0xFF22D3EE), Color(0xFF8B5CF6))
        val MAGENTA = AccentPalette(Color(0xFFF472B6), Color(0xFF8B5CF6))
        val LIME = AccentPalette(Color(0xFFA3E635), Color(0xFF22D3EE))
        val AMBER = AccentPalette(Color(0xFFFBBF24), Color(0xFFF43F5E))

        fun of(choice: AccentChoice): AccentPalette = when (choice) {
            AccentChoice.CYAN -> CYAN
            AccentChoice.MAGENTA -> MAGENTA
            AccentChoice.LIME -> LIME
            AccentChoice.AMBER -> AMBER
        }
    }
}

/** 色彩 token（只有深色主題）。 */
@Immutable
data class B570Colors(
    /** 最底層背景（近黑海軍藍）。 */
    val background: Color = Color(0xFF05070D),
    /** 背景放射漸層中心色。 */
    val backgroundGlow: Color = Color(0xFF12203D),
    /** 背景格線。 */
    val grid: Color = Color(0x1A5B7BB5),
    /** 不透明表面（對話框、下拉）。 */
    val surface: Color = Color(0xFF0D1424),
    /** 玻璃卡片填色（半透明）。 */
    val glass: Color = Color(0x990E1628),
    /** 玻璃卡片高亮填色（按下 / 選取）。 */
    val glassHigh: Color = Color(0xB3172238),
    /** 玻璃邊框亮端 / 暗端。 */
    val glassBorder: Color = Color(0x33FFFFFF),
    val glassBorderDim: Color = Color(0x0DFFFFFF),
    /** 分隔線。 */
    val divider: Color = Color(0x1FFFFFFF),
    val textPrimary: Color = Color(0xFFE6EDF7),
    val textSecondary: Color = Color(0xFF9AA8BF),
    val textMuted: Color = Color(0xFF5B6880),
    /** 強調色（可由設定切換）。 */
    val accent: Color = AccentPalette.CYAN.primary,
    val accent2: Color = AccentPalette.CYAN.secondary,
    /** 強調色上的文字。 */
    val onAccent: Color = Color(0xFF041016),
    /** 語意色。 */
    val ok: Color = Color(0xFF34D399),
    val warn: Color = Color(0xFFFBBF24),
    val danger: Color = Color(0xFFF43F5E),
    val info: Color = Color(0xFF60A5FA),
    /** 圖表多序列配色（依序使用）。 */
    val series: List<Color> = listOf(
        Color(0xFF22D3EE), Color(0xFFF472B6), Color(0xFFA3E635), Color(0xFFFBBF24),
        Color(0xFF8B5CF6), Color(0xFF60A5FA), Color(0xFFFB7185), Color(0xFF2DD4BF),
    ),
)

/** 間距 token。 */
@Immutable
data class B570Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    /** 畫面左右留白。 */
    val screen: Dp = 16.dp,
)

/** 圓角 token。 */
@Immutable
data class B570Radii(
    val s: Dp = 12.dp,
    val m: Dp = 16.dp,
    val l: Dp = 20.dp,
    val xl: Dp = 24.dp,
)

val LocalB570Colors = staticCompositionLocalOf { B570Colors() }
val LocalB570Spacing = staticCompositionLocalOf { B570Spacing() }
val LocalB570Radii = staticCompositionLocalOf { B570Radii() }
val LocalB570Type = staticCompositionLocalOf { B570Typography() }

/** 主題存取入口：`B570Theme.colors.accent`、`B570Theme.type.readoutL`、`B570Theme.spacing.l`。 */
object B570Theme {
    val colors: B570Colors @Composable @ReadOnlyComposable get() = LocalB570Colors.current
    val type: B570Typography @Composable @ReadOnlyComposable get() = LocalB570Type.current
    val spacing: B570Spacing @Composable @ReadOnlyComposable get() = LocalB570Spacing.current
    val radii: B570Radii @Composable @ReadOnlyComposable get() = LocalB570Radii.current
}

/** 深色「未來駕駛艙」主題。[accent] 由 AppSettings.accent 決定。 */
@Composable
fun B570Theme(accent: AccentChoice = AccentChoice.CYAN, content: @Composable () -> Unit) {
    val palette = AccentPalette.of(accent)
    val colors = remember(palette) { B570Colors(accent = palette.primary, accent2 = palette.secondary) }
    val type = remember { B570Typography() }
    val scheme = remember(colors) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.glassHigh,
            onPrimaryContainer = colors.textPrimary,
            secondary = colors.accent2,
            onSecondary = Color.White,
            tertiary = colors.ok,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.glassHigh,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = Color(0xFF131C30),
            surfaceContainerHighest = Color(0xFF18233A),
            surfaceContainerLow = Color(0xFF0A101D),
            outline = colors.glassBorder,
            outlineVariant = colors.divider,
            error = colors.danger,
            onError = Color.White,
            scrim = Color(0xCC000000),
        )
    }
    CompositionLocalProvider(
        LocalB570Colors provides colors,
        LocalB570Type provides type,
    ) {
        MaterialTheme(colorScheme = scheme, typography = type.material, content = content)
    }
}
