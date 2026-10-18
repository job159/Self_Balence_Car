package com.b570.pilot.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.theme.B570Theme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 弧形儀表：發光漸層弧 + 刻度 + 中央數值。
 * min < 0 時（雙向儀表，例如 PWM）弧線從 0 開始往兩側長。
 *
 * @param warnAt / dangerAt |value| 超過時改用警告 / 危險色
 * @param valueText 自訂中央文字（null = 依 decimals 格式化）
 */
@Composable
fun ArcGauge(
    value: Float,
    min: Float,
    max: Float,
    modifier: Modifier = Modifier,
    label: String = "",
    unit: String = "",
    decimals: Int = 0,
    color: Color = B570Theme.colors.accent,
    warnAt: Float? = null,
    dangerAt: Float? = null,
    sweep: Float = 240f,
    thickness: Dp = 10.dp,
    valueText: String? = null,
    valueStyle: TextStyle = B570Theme.type.readoutM,
    animate: Boolean = true,
) {
    val c = B570Theme.colors
    val target = if (value.isNaN()) min.coerceAtLeast(0f).coerceAtMost(max) else value.coerceIn(min, max)
    val anim by animateFloatAsState(target, spring(stiffness = 220f), label = "gauge")
    val v = if (animate) anim else target
    val col = when {
        dangerAt != null && abs(v) >= dangerAt -> c.danger
        warnAt != null && abs(v) >= warnAt -> c.warn
        else -> color
    }
    val trackColor = c.textMuted.copy(alpha = 0.22f)
    val tickColor = c.textMuted.copy(alpha = 0.5f)
    val start = 90f + (360f - sweep) / 2f
    val range = (max - min).takeIf { it > 0f } ?: 1f
    val zeroFrac = if (min < 0f && max > 0f) (-min / range) else 0f
    Box(modifier.aspectRatio(1.15f), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize().drawWithCache {
                val sw = thickness.toPx()
                val d = size.minDimension - sw * 2.2f
                val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f + d * 0.06f)
                val arcSize = Size(d, d)
                val trackStroke = Stroke(sw, cap = StrokeCap.Round)
                val glowStroke = Stroke(sw * 2.4f, cap = StrokeCap.Round)
                val center = Offset(tl.x + d / 2f, tl.y + d / 2f)
                val r = d / 2f
                onDrawBehind {
                    drawArc(trackColor, start, sweep, false, tl, arcSize, style = trackStroke)
                    // 刻度
                    for (k in 0..10) {
                        val a = Math.toRadians((start + sweep * k / 10f).toDouble())
                        val r1 = r - sw * 1.3f
                        val r2 = r1 - (if (k % 5 == 0) sw * 0.9f else sw * 0.5f)
                        drawLine(
                            tickColor,
                            Offset(center.x + r1 * cos(a).toFloat(), center.y + r1 * sin(a).toFloat()),
                            Offset(center.x + r2 * cos(a).toFloat(), center.y + r2 * sin(a).toFloat()),
                            1.dp.toPx(),
                        )
                    }
                    val f = ((v - min) / range).coerceIn(0f, 1f)
                    val a0 = start + sweep * zeroFrac
                    val a1 = start + sweep * f
                    val sa = minOf(a0, a1)
                    val sw2 = abs(a1 - a0).coerceAtLeast(0.01f)
                    val brush = Brush.sweepGradient(listOf(c.accent2, col, col), center)
                    drawArc(col.copy(alpha = 0.18f), sa, sw2, false, tl, arcSize, style = glowStroke)
                    drawArc(brush, sa, sw2, false, tl, arcSize, style = trackStroke)
                    val ea = Math.toRadians(a1.toDouble())
                    val ep = Offset(center.x + r * cos(ea).toFloat(), center.y + r * sin(ea).toFloat())
                    drawCircle(col.copy(alpha = 0.35f), sw * 1.1f, ep)
                    drawCircle(Color.White, sw * 0.42f, ep)
                }
            },
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = 6.dp)) {
            val text = valueText ?: if (value.isNaN()) "--" else String.format(Locale.US, "%.${decimals}f", v)
            Text(text, style = valueStyle, color = c.textPrimary)
            if (unit.isNotEmpty()) Text(unit, style = B570Theme.type.caption, color = c.textMuted)
            if (label.isNotEmpty()) Text(label, style = B570Theme.type.label, color = c.textSecondary)
        }
    }
}
