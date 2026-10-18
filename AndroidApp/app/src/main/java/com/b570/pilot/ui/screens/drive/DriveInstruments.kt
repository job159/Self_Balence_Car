package com.b570.pilot.ui.screens.drive

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.ui.components.AnimatedNumber
import com.b570.pilot.ui.components.AttitudeIndicator
import com.b570.pilot.ui.components.lerpColor
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 速度弧：240° 雙向，0 在正上方。 */
private const val ARC_START = 150f
private const val ARC_SWEEP = 240f
private const val ARC_ZERO = 270f
private const val TICKS = 20
private const val SMOOTH = 0.45f
private const val SMOOTH_TARGET = 0.6f

/** 超過此速度才判定前進 / 後退（避免平衡晃動時箭頭亂跳）。 */
private const val DIR_THRESHOLD = 25f

private val ArcStroke = 7.dp
private val MarkerOut = ArcStroke * 1.7f
private val InstrumentGap = 8.dp
private val InstrumentMax = 300.dp

/**
 * 中控台儀表：（體感模式）水平儀 ＋ 姿態儀（傾角 + 航向）＋ 目標 / 實際車速弧形儀表與左右輪速。
 * 依可用寬高排成一列、置中，平板上限 [InstrumentMax]。
 * 50 Hz 遙測只在繪製階段讀取（父層不重組）；文字讀 telemetrySlow。
 * @param tilt 體感模式的狀態（非 null = 多畫一個水平儀）
 */
@Composable
internal fun DriveInstruments(
    telem: State<Telemetry?>,
    slow: State<Telemetry?>,
    motorsOn: Boolean,
    maxSpeed: Float,
    fallAngle: Float,
    zeroAngle: Float,
    tilt: TiltSession?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val n = if (tilt != null) 3 else 2
        val avail = maxWidth - InstrumentGap * (n - 1)
        val sq: Dp
        val speedW: Dp
        if (tilt != null) {
            sq = minOf(maxHeight, avail / 3, InstrumentMax)
            speedW = sq
        } else {
            sq = minOf(maxHeight, avail * 0.48f, InstrumentMax)
            speedW = minOf(avail - sq, InstrumentMax)
        }
        val speedH = minOf(maxHeight, maxOf(sq, speedW * 1.25f))
        val headingAt = when {
            sq >= 150.dp -> HeadingAt.TOP_END
            maxHeight - sq >= 26.dp -> HeadingAt.BELOW
            else -> HeadingAt.BOTTOM_END
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(InstrumentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tilt != null) TiltScope(tilt, Modifier.size(sq))
            val tape = sq >= 120.dp
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(sq)) {
                    AttitudeIndicator(
                        pitchDeg = { telem.value?.pitchDeg ?: Float.NaN },
                        modifier = Modifier.fillMaxSize(),
                        headingDeg = { telem.value?.headingDeg ?: Float.NaN },
                        rollDeg = { telem.value?.rollDeg ?: Float.NaN },
                        fallAngle = fallAngle,
                        zeroAngle = zeroAngle,
                        active = motorsOn,
                        showHeadingTape = tape,
                    )
                    // 小儀表：左上角的傾角讀數會撞到航向 → 航向改放儀表下方（有空間時）或右下
                    when (headingAt) {
                        HeadingAt.TOP_END -> HeadingReadout(slow, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp))
                        HeadingAt.BOTTOM_END -> HeadingReadout(slow, Modifier.align(Alignment.BottomEnd).padding(bottom = if (tape) 34.dp else 6.dp, end = 8.dp))
                        HeadingAt.BELOW -> Unit
                    }
                }
                if (headingAt == HeadingAt.BELOW) {
                    Spacer(Modifier.height(4.dp))
                    HeadingReadout(slow, Modifier)
                }
            }
            SpeedCluster(telem, slow, maxSpeed, Modifier.size(speedW, speedH))
        }
    }
}

private enum class HeadingAt { TOP_END, BOTTOM_END, BELOW }

@Composable
private fun HeadingReadout(slow: State<Telemetry?>, modifier: Modifier) {
    val c = B570Theme.colors
    val h = slow.value?.headingDeg
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Explore, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            if (h == null || h.isNaN()) "--" else String.format(Locale.US, "%03d°", ((h.roundToInt() % 360) + 360) % 360),
            style = B570Theme.type.caption,
            color = c.textSecondary,
            maxLines = 1,
        )
    }
}

/** 車速儀表卡：上 = 目標 / 實際速度弧，下 = 左右輪速。 */
@Composable
private fun SpeedCluster(telem: State<Telemetry?>, slow: State<Telemetry?>, maxSpeed: Float, modifier: Modifier) {
    val max = maxSpeed.coerceAtLeast(1f)
    Column(
        modifier
            .glass(RoundedCornerShape(B570Theme.radii.l))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        SpeedArc(telem, slow, max, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        WheelRow("左", left = true, telem, slow, max)
        Spacer(Modifier.height(5.dp))
        WheelRow("右", left = false, telem, slow, max)
    }
}

/** 弧形儀表的幾何（dp；繪製與文字定位共用同一套算式）。 */
private class ArcGeometry(val radius: Dp, val centerY: Dp, val centerOffsetY: Dp)

private fun arcGeometry(w: Dp, h: Dp): ArcGeometry {
    val r = minOf(w / 2 - MarkerOut, (h - MarkerOut - ArcStroke * 0.6f) / 1.5f).coerceAtLeast(6.dp)
    val total = r * 1.5f + MarkerOut + ArcStroke * 0.5f
    val cy = (h - total) / 2 + MarkerOut + r
    return ArcGeometry(r, cy, cy - h / 2)
}

/**
 * 目標 vs 實際車速：實際速度 = 發光弧（從 0 往前 / 往後長），目標速度 = 外圈三角指標。
 * 中央：實際車速（mm/s + km/h），空間夠時再加目標數字。
 * 在繪製階段讀 50 Hz 遙測並做輕微平滑，零配置；中央數字讀 telemetrySlow。
 */
@Composable
private fun SpeedArc(telem: State<Telemetry?>, slow: State<Telemetry?>, max: Float, modifier: Modifier) {
    val c = B570Theme.colors
    BoxWithConstraints(modifier) {
        val geo = arcGeometry(maxWidth, maxHeight)
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val sw = ArcStroke.toPx()
                val markerOut = MarkerOut.toPx()
                val r = geo.radius.toPx()
                val cx = size.width / 2f
                val cy = geo.centerY.toPx()
                val arcCenter = Offset(cx, cy)
                val tl = Offset(cx - r, cy - r)
                val arcSize = Size(r * 2f, r * 2f)
                val track = Stroke(sw, cap = StrokeCap.Round)
                val glow = Stroke(sw * 2.6f, cap = StrokeCap.Round)
                val tickPts = FloatArray((TICKS + 1) * 4)
                for (i in 0..TICKS) {
                    val a = Math.toRadians((ARC_START + ARC_SWEEP * i / TICKS).toDouble())
                    val r1 = r - sw * 1.25f
                    val r2 = r1 - if (i % 5 == 0) sw * 0.9f else sw * 0.45f
                    tickPts[i * 4] = cx + r1 * cos(a).toFloat()
                    tickPts[i * 4 + 1] = cy + r1 * sin(a).toFloat()
                    tickPts[i * 4 + 2] = cx + r2 * cos(a).toFloat()
                    tickPts[i * 4 + 3] = cy + r2 * sin(a).toFloat()
                }
                val tickThin = 1.dp.toPx()
                val tickBold = 1.6.dp.toPx()
                // 目標指標：畫在 0°（3 點鐘方向），繪製時旋轉到目標角度
                val marker = Path().apply {
                    moveTo(cx + r + sw * 0.6f, cy)
                    lineTo(cx + r + markerOut, cy - sw * 0.8f)
                    lineTo(cx + r + markerOut, cy + sw * 0.8f)
                    close()
                }
                val markerEdge = Stroke(1.dp.toPx())
                val trackColor = c.textMuted.copy(alpha = 0.22f)
                val tickColor = c.textMuted.copy(alpha = 0.5f)
                // 平滑狀態（存在 cache 範圍內，跨幀保留）
                var sv = 0f
                var st = 0f
                onDrawBehind {
                    val t = telem.value
                    sv += ((t?.speedMmps ?: 0f) - sv) * SMOOTH
                    st += ((t?.targetSpeed ?: 0).toFloat() - st) * SMOOTH_TARGET

                    drawArc(trackColor, ARC_START, ARC_SWEEP, false, tl, arcSize, style = track)
                    for (i in 0..TICKS) {
                        drawLine(
                            tickColor,
                            Offset(tickPts[i * 4], tickPts[i * 4 + 1]),
                            Offset(tickPts[i * 4 + 2], tickPts[i * 4 + 3]),
                            if (i % 10 == 0) tickBold else tickThin,
                        )
                    }

                    // 實際車速（接近上限變警告色）
                    val f = (sv / max).coerceIn(-1f, 1f)
                    val col = if (abs(f) > 0.92f) c.warn else c.accent
                    val sweep = f * ARC_SWEEP / 2f
                    if (abs(sweep) > 0.5f) {
                        val s0 = if (sweep >= 0f) ARC_ZERO else ARC_ZERO + sweep
                        drawArc(col.copy(alpha = 0.16f), s0, abs(sweep), false, tl, arcSize, style = glow)
                        drawArc(col, s0, abs(sweep), false, tl, arcSize, style = track)
                    }

                    // 目標車速指標
                    val ft = (st / max).coerceIn(-1f, 1f)
                    rotate(ARC_ZERO + ft * ARC_SWEEP / 2f, arcCenter) {
                        drawPath(marker, c.accent2)
                        drawPath(marker, Color.White.copy(alpha = 0.55f), style = markerEdge)
                    }
                }
            },
        )
        SpeedCenter(slow, geo.radius, Modifier.align(Alignment.Center).offset(y = geo.centerOffsetY + geo.radius * 0.12f))
    }
}

/** 弧心讀數：實際車速（方向箭頭 + 數字）、mm/s、km/h、目標（依半徑決定顯示多少）。 */
@Composable
private fun SpeedCenter(slow: State<Telemetry?>, radius: Dp, modifier: Modifier) {
    val c = B570Theme.colors
    val type = B570Theme.type
    val t = slow.value
    val v = t?.speedMmps ?: Float.NaN
    val dir = when {
        v.isNaN() -> 0
        v > DIR_THRESHOLD -> 1
        v < -DIR_THRESHOLD -> -1
        else -> 0
    }
    val rot by animateFloatAsState(if (dir < 0) 180f else 0f, spring(dampingRatio = 0.62f, stiffness = 320f), label = "dir")
    val arrow by animateColorAsState(
        when (dir) {
            1 -> c.accent
            -1 -> c.warn
            else -> c.textMuted
        },
        label = "dirColor",
    )
    val numStyle = when {
        radius >= 70.dp -> type.readoutL
        radius >= 42.dp -> type.readoutM
        else -> type.readoutS
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Navigation, contentDescription = null, tint = arrow,
                modifier = Modifier.size(if (radius >= 42.dp) 14.dp else 11.dp).graphicsLayer { rotationZ = rot },
            )
            Spacer(Modifier.width(2.dp))
            AnimatedNumber(abs(v), style = numStyle, decimals = 0)
        }
        Text("mm/s", style = type.caption, color = c.textMuted, maxLines = 1)
        if (radius >= 46.dp) {
            Text(
                if (v.isNaN()) "-- km/h" else String.format(Locale.US, "%.2f km/h", abs(v) * 0.0036f),
                style = type.caption, color = c.textSecondary, maxLines = 1,
            )
        }
        if (radius >= 62.dp) {
            val target = t?.targetSpeed
            Text(
                "目標 " + when (target) {
                    null -> "--"
                    0 -> "0"
                    else -> String.format(Locale.US, "%+d", target)
                },
                style = type.caption, color = c.accent2, maxLines = 1,
            )
        }
    }
}

/** 輪速條：中線 = 0，往右 = 前進（主色）、往左 = 後退（警告色）；條在繪製階段讀 50 Hz，數字 10 Hz。 */
@Composable
private fun WheelRow(label: String, left: Boolean, telem: State<Telemetry?>, slow: State<Telemetry?>, max: Float) {
    val c = B570Theme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1, softWrap = false, modifier = Modifier.width(18.dp))
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .drawWithCache {
                    val radius = CornerRadius(size.height / 2f)
                    val track = c.textMuted.copy(alpha = 0.18f)
                    val mid = size.width / 2f
                    var sv = 0f
                    onDrawBehind {
                        val t = telem.value
                        val raw = if (t == null) 0f else (if (left) t.velL else t.velR).toFloat()
                        sv += (raw - sv) * SMOOTH
                        drawRoundRect(track, cornerRadius = radius)
                        val f = (sv / max).coerceIn(-1f, 1f)
                        val w = abs(f) * mid
                        if (w > 0.5f) {
                            val col = if (f >= 0f) c.accent else c.warn
                            val x = if (f >= 0f) mid else mid - w
                            drawRoundRect(col, Offset(x, 0f), Size(w, size.height), radius)
                        }
                        drawLine(c.textSecondary.copy(alpha = 0.6f), Offset(mid, -2f), Offset(mid, size.height + 2f), 1f)
                    }
                },
        )
        WheelValue(slow, left, Modifier.width(36.dp))
    }
}

@Composable
private fun WheelValue(slow: State<Telemetry?>, left: Boolean, modifier: Modifier) {
    val t = slow.value
    val v = if (t == null) null else if (left) t.velL else t.velR
    Text(
        when (v) {
            null -> "--"
            0 -> "0"
            else -> String.format(Locale.US, "%+d", v)
        },
        style = B570Theme.type.caption,
        color = B570Theme.colors.textSecondary,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier,
    )
}

// ------------------------------------------------------------------ 體感水平儀

/**
 * 體感水平儀（中控台）：圓形準星 + 死區圈 + 會跟著轉的「方向盤地平線」+ 發光氣泡（上 = 前進、右 = 右轉）。
 * 只在繪製階段讀取傾斜量（零配置）。
 */
@Composable
private fun TiltScope(session: TiltSession, modifier: Modifier) {
    val c = B570Theme.colors
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val heldAnim = animateFloatAsState(if (session.held.value) 1f else 0f, tween(240), label = "tiltHeld")
    Box(
        modifier
            .glass(CircleShape)
            .drawWithCache {
                val ctr = Offset(size.width / 2f, size.height / 2f)
                val labelStyle = TextStyle(color = c.textMuted, fontSize = 10.sp)
                val fwd = measurer.measure("前", labelStyle)
                val back = measurer.measure("後", labelStyle)
                val left = measurer.measure("左", labelStyle)
                val right = measurer.measure("右", labelStyle)
                val inset = fwd.size.height + 4.dp.toPx()
                val radius = (size.minDimension / 2f - inset).coerceAtLeast(4f)
                val thin = Stroke(1.dp.toPx())
                val dead = radius * TiltSession.DEADZONE
                val bubbleR = (radius * 0.13f).coerceIn(5.dp.toPx(), 10.dp.toPx())
                val haloR = bubbleR * 2.2f
                val trail = 2.5.dp.toPx()
                val horizonW = 2.dp.toPx()
                val guide = c.textMuted.copy(alpha = 0.3f)
                val fwdPos = Offset(ctr.x - fwd.size.width / 2f, ctr.y - radius - fwd.size.height - 1.dp.toPx())
                val backPos = Offset(ctr.x - back.size.width / 2f, ctr.y + radius + 1.dp.toPx())
                val leftPos = Offset(ctr.x - radius - left.size.width - 2.dp.toPx(), ctr.y - left.size.height / 2f)
                val rightPos = Offset(ctr.x + radius + 2.dp.toPx(), ctr.y - right.size.height / 2f)
                onDrawBehind {
                    val h = heldAnim.value
                    val t = session.tilt.value
                    val len = sqrt(t.x * t.x + t.y * t.y)
                    val k = if (len > 1f) 1f / len else 1f
                    val b = Offset(ctr.x + t.x * k * radius, ctr.y - t.y * k * radius)

                    drawCircle(c.textMuted.copy(alpha = 0.35f), radius, ctr, style = thin)
                    drawCircle(c.textMuted.copy(alpha = 0.2f), radius * 0.5f, ctr, style = thin)
                    drawLine(guide, Offset(ctr.x, ctr.y - radius), Offset(ctr.x, ctr.y + radius), 1f)
                    // 方向盤地平線：跟著轉向旋轉、跟著油門上下移（按住駕駛時亮起）
                    val hy = ctr.y + t.y * radius * 0.45f
                    rotate(-t.x * 35f, Offset(ctr.x, hy)) {
                        val half = radius * 0.86f
                        drawLine(lerpColor(guide, c.accent, h), Offset(ctr.x - half, hy), Offset(ctr.x + half, hy), horizonW, StrokeCap.Round)
                        drawLine(lerpColor(guide, c.accent, h), Offset(ctr.x, hy), Offset(ctr.x, hy - radius * 0.14f), horizonW, StrokeCap.Round)
                    }
                    drawCircle(c.accent.copy(alpha = 0.10f + 0.25f * h), dead.coerceAtLeast(3f), ctr)
                    drawText(fwd, topLeft = fwdPos)
                    drawText(back, topLeft = backPos)
                    drawText(left, topLeft = leftPos)
                    drawText(right, topLeft = rightPos)

                    if (h > 0.01f) drawLine(c.accent.copy(alpha = 0.55f * h), ctr, b, trail, StrokeCap.Round)
                    val col = lerpColor(c.textMuted, c.accent, h)
                    drawCircle(col.copy(alpha = 0.10f + 0.20f * h), haloR, b)
                    drawCircle(col, bubbleR, b)
                    drawCircle(Color.White.copy(alpha = 0.15f + 0.5f * h), bubbleR * 0.38f, Offset(b.x - bubbleR * 0.3f, b.y - bubbleR * 0.3f))
                }
            },
    )
}

/** 平板（中控台很高）時加在儀表上方的大車速讀數。 */
@Composable
internal fun BigSpeedReadout(slow: State<Telemetry?>, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val type = B570Theme.type
    val v = slow.value?.speedMmps ?: Float.NaN
    val target = slow.value?.targetSpeed
    val style = remember(type) { type.readoutXL }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        AnimatedNumber(abs(v), style = style, decimals = 0)
        Spacer(Modifier.width(8.dp))
        Column {
            Text("mm/s", style = type.label, color = c.textSecondary, maxLines = 1)
            Text(
                if (v.isNaN()) "-- km/h" else String.format(Locale.US, "%.2f km/h", abs(v) * 0.0036f),
                style = type.caption, color = c.textMuted, maxLines = 1,
            )
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text("目標", style = type.caption, color = c.textMuted, maxLines = 1)
            Text(
                when (target) {
                    null -> "--"
                    0 -> "0"
                    else -> String.format(Locale.US, "%+d", target)
                },
                style = type.readoutM, color = c.accent2, maxLines = 1,
            )
        }
    }
}
