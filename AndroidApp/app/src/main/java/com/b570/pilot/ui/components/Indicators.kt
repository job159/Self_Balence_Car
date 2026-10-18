package com.b570.pilot.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import java.util.Locale

/**
 * 數據磚：小標 + 大數字 + 單位 + 可選圖示 / 註解。
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    icon: ImageVector? = null,
    accent: Color = B570Theme.colors.accent,
    caption: String? = null,
    valueStyle: TextStyle = B570Theme.type.readoutM,
) {
    val c = B570Theme.colors
    Column(modifier.glass(RoundedCornerShape(B570Theme.radii.m)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = B570Theme.type.label, color = c.textSecondary, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = valueStyle, color = c.textPrimary, maxLines = 1)
            if (!unit.isNullOrEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = B570Theme.type.caption, color = c.textMuted, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (caption != null) Text(caption, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/**
 * 狀態膠囊：彩色圓點 + 文字。[pulsing] = 圓點呼吸閃爍（連線中 / 運轉中）。
 */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = B570Theme.colors
    val col by animateColorAsState(color, label = "pill")
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(col.copy(alpha = 0.12f))
            .border(1.dp, col.copy(alpha = 0.35f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = col, modifier = Modifier.size(14.dp))
        } else {
            PulseDot(col, pulsing)
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = B570Theme.type.label, color = c.textPrimary, maxLines = 1)
    }
}

@Composable
private fun PulseDot(color: Color, pulsing: Boolean) {
    if (pulsing) {
        val t = rememberInfiniteTransition(label = "dot")
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "p")
        Box(
            Modifier.size(8.dp).drawBehind {
                drawCircle(color.copy(alpha = (1f - p) * 0.6f), radius = size.minDimension / 2 * (1f + p * 1.2f))
                drawCircle(color, radius = size.minDimension / 2)
            },
        )
    } else {
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

/** 3S 鋰電池電壓 → 電量 %（10.0 V = 0 %，12.6 V = 100 %，依放電曲線微調非線性）。 */
fun batteryPercent(volts: Float): Int {
    val v = BATT_V
    val p = BATT_P
    if (volts <= v.first()) return 0
    if (volts >= v.last()) return 100
    for (i in 1 until v.size) {
        if (volts <= v[i]) {
            val f = (volts - v[i - 1]) / (v[i] - v[i - 1])
            return (p[i - 1] + (p[i] - p[i - 1]) * f).toInt().coerceIn(0, 100)
        }
    }
    return 100
}

private val BATT_V = floatArrayOf(10.0f, 10.5f, 10.8f, 11.1f, 11.4f, 11.7f, 12.0f, 12.3f, 12.6f)
private val BATT_P = floatArrayOf(0f, 8f, 18f, 38f, 58f, 74f, 86f, 95f, 100f)

/** 電量顏色：> 30 % 正常、> 15 % 警告、其餘危險。 */
@Composable
fun batteryColor(percent: Int): Color = when {
    percent > 30 -> B570Theme.colors.ok
    percent > 15 -> B570Theme.colors.warn
    else -> B570Theme.colors.danger
}

/**
 * 電池圖示 + 百分比（或電壓）。[volts] = null 顯示「--」。
 */
@Composable
fun BatteryIndicator(
    volts: Float?,
    modifier: Modifier = Modifier,
    showVoltage: Boolean = false,
    showText: Boolean = true,
) {
    val c = B570Theme.colors
    val pct = volts?.let { batteryPercent(it) }
    val col = if (pct == null) c.textMuted else batteryColor(pct)
    val fill by animateFloatAsState((pct ?: 0) / 100f, spring(stiffness = 120f), label = "batt")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 24.dp, height = 12.dp)) {
            val sw = 1.2.dp.toPx()
            val bodyW = size.width - 2.5.dp.toPx()
            val r = CornerRadius(2.5.dp.toPx())
            drawRoundRect(col.copy(alpha = 0.7f), size = Size(bodyW, size.height), cornerRadius = r, style = Stroke(sw))
            drawRoundRect(col.copy(alpha = 0.7f), topLeft = Offset(bodyW + 0.5.dp.toPx(), size.height * 0.3f), size = Size(2.dp.toPx(), size.height * 0.4f), cornerRadius = CornerRadius(1.dp.toPx()))
            val pad = 2.2.dp.toPx()
            drawRoundRect(col, topLeft = Offset(pad, pad), size = Size((bodyW - pad * 2) * fill, size.height - pad * 2), cornerRadius = CornerRadius(1.dp.toPx()))
        }
        if (showText) {
            Spacer(Modifier.width(6.dp))
            val txt = when {
                volts == null -> "--"
                showVoltage -> String.format(Locale.US, "%.2f V", volts)
                else -> "$pct%"
            }
            Text(txt, style = B570Theme.type.label, color = c.textPrimary)
        }
    }
}

/** 訊號格（0..4 格）。 */
@Composable
fun SignalBars(
    level: Int,
    modifier: Modifier = Modifier,
    color: Color = B570Theme.colors.accent,
) {
    val dim = B570Theme.colors.textMuted.copy(alpha = 0.4f)
    Canvas(modifier.size(width = 18.dp, height = 14.dp)) {
        val n = 4
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = size.height * (0.35f + 0.65f * (i + 1) / n)
            drawRoundRect(
                if (i < level) color else dim,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

/** 以 RSSI（dBm）顯示訊號格；null = 0 格。 */
@Composable
fun SignalBars(rssi: Int?, modifier: Modifier = Modifier, color: Color = B570Theme.colors.accent) {
    val level = when {
        rssi == null -> 0
        rssi >= -55 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
    SignalBars(level, modifier, color)
}

/**
 * 平滑變化的數字（等寬數字，不會左右跳）。
 * @param decimals 小數位數
 * @param animate false = 直接顯示（高頻數值建議關閉）
 */
@Composable
fun AnimatedNumber(
    value: Float,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    style: TextStyle = B570Theme.type.readoutL,
    color: Color = B570Theme.colors.textPrimary,
    unit: String? = null,
    unitColor: Color = B570Theme.colors.textMuted,
    signed: Boolean = false,
    animate: Boolean = true,
) {
    val shown by animateFloatAsState(if (value.isNaN()) 0f else value, spring(stiffness = 300f), label = "num")
    val v = if (animate) shown else value
    val fmt = if (signed) "%+.${decimals}f" else "%.${decimals}f"
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Start) {
        Text(if (value.isNaN()) "--" else String.format(Locale.US, fmt, v), style = style, color = color, maxLines = 1)
        if (!unit.isNullOrEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(unit, style = B570Theme.type.label, color = unitColor, modifier = Modifier.padding(bottom = (style.fontSize.value * 0.12f).dp))
        }
    }
}
