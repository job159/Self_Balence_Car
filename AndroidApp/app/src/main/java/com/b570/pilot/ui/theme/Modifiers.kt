package com.b570.pilot.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 駕駛艙背景：近黑海軍藍 + 上方柔和放射光暈 + 淡格線。
 * 只在尺寸改變時重建（drawWithCache），每幀零配置。
 */
@Composable
fun Modifier.cockpitBackground(): Modifier {
    val c = B570Theme.colors
    return this.drawWithCache {
        val glow = Brush.radialGradient(
            colors = listOf(c.backgroundGlow, c.background),
            center = Offset(size.width * 0.5f, size.height * 0.12f),
            radius = max(size.width, size.height) * 0.85f,
        )
        val accentGlow = Brush.radialGradient(
            colors = listOf(c.accent2.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width * 0.9f, size.height * 0.95f),
            radius = size.width * 0.9f,
        )
        val step = 28.dp.toPx()
        val grid = Path().apply {
            var x = (size.width % step) / 2f
            while (x < size.width) { moveTo(x, 0f); lineTo(x, size.height); x += step }
            var y = 0f
            while (y < size.height) { moveTo(0f, y); lineTo(size.width, y); y += step }
        }
        val fade = Brush.verticalGradient(0f to Color.Transparent, 0.35f to c.background.copy(alpha = 0.35f), 1f to c.background.copy(alpha = 0.85f))
        val stroke = Stroke(width = 1f)
        onDrawBehind {
            drawRect(c.background)
            drawRect(glow)
            drawPath(grid, c.grid, style = stroke)
            drawRect(fade)
            drawRect(accentGlow)
        }
    }
}

/**
 * 玻璃質感：半透明填色 + 1 dp 漸層邊框 + （可選）霓虹柔光。
 * @param glow 柔光顏色；null = 無光暈
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(B570Theme.radii.l),
    fill: Color = B570Theme.colors.glass,
    glow: Color? = null,
    glowElevation: Dp = 18.dp,
    borderWidth: Dp = 1.dp,
): Modifier {
    val c = B570Theme.colors
    val border = Brush.linearGradient(
        colors = if (glow != null) listOf(glow.copy(alpha = 0.65f), c.glassBorderDim, glow.copy(alpha = 0.25f))
        else listOf(c.glassBorder, c.glassBorderDim, c.glassBorder.copy(alpha = 0.12f)),
    )
    var m = this
    if (glow != null) m = m.shadow(glowElevation, shape, clip = false, ambientColor = glow, spotColor = glow)
    return m.background(fill, shape).border(borderWidth, border, shape)
}

/** 霓虹柔光（彩色陰影，API 28+ 硬體加速）。 */
fun Modifier.neonGlow(color: Color, shape: Shape, elevation: Dp = 16.dp): Modifier =
    this.shadow(elevation, shape, clip = false, ambientColor = color, spotColor = color)
