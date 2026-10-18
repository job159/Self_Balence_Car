package com.b570.pilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b570.pilot.data.TelemetryHistory
import com.b570.pilot.ui.theme.B570Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** 圖表的一條線。[scale] 會乘在資料上（例如把 PWM ÷ 10 與角度畫在同一張圖）。 */
@Immutable
data class ChartSeries(
    val channel: TelemetryHistory.Channel,
    val color: Color,
    val label: String = channel.labelZh,
    val scale: Float = 1f,
)

/**
 * 即時多序列折線圖，直接讀 [TelemetryHistory]。
 * 以 withFrameNanos + history.version 驅動「只重畫、不重組」，每幀零配置；沒有新資料一段時間後改成低頻輪詢。
 * 資料點比水平像素多時（30 / 60 秒視窗）每 [PX_PER_BUCKET] px 只畫該區段的最小 / 最大值（形狀不變、點數 ≤ 寬度），
 * 點數多時也省略光暈描邊。
 *
 * @param windowSamples 顯示最近幾筆（50 Hz 時 500 = 10 秒）
 * @param yRange 固定 Y 範圍；null = 自動（平滑縮放）
 * @param paused true = 凍結畫面（資料仍持續記錄）
 * @param minSpan 自動範圍時的最小跨度（避免雜訊被放大）
 * @param showLegend 顯示圖例；點圖例可隱藏 / 顯示該序列
 */
@Composable
fun LineChart(
    history: TelemetryHistory,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    windowSamples: Int = 500,
    yRange: ClosedFloatingPointRange<Float>? = null,
    paused: Boolean = false,
    showLegend: Boolean = true,
    showGrid: Boolean = true,
    minSpan: Float = 1f,
    chartHeight: Dp = 180.dp,
) {
    val c = B570Theme.colors
    val window = windowSamples.coerceIn(10, history.capacity)
    val hidden = remember(series) { mutableStateListOf<Boolean>().apply { repeat(series.size) { add(false) } } }
    val holder = remember(series, window) { ChartHolder(series.size, window) }
    val frame = remember { mutableIntStateOf(0) }
    val pausedState = rememberUpdatedState(paused)
    val rangeState = rememberUpdatedState(yRange)
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val haptics = rememberHaptics()

    LaunchedEffect(history, paused) {
        if (paused) return@LaunchedEffect
        var last = -1L
        var changedAt = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val v = history.version
            if (v != last) {
                last = v
                changedAt = now
                frame.intValue++
            } else if (now - changedAt > IDLE_AFTER_NS) {
                // 沒有新資料（未連線 / 訊號中斷）：不必每個 vsync 都醒來
                delay(IDLE_POLL_MS)
            }
        }
    }

    Column(modifier) {
        if (showLegend) {
            val legendScroll = rememberScrollState()
            Row(
                Modifier.fillMaxWidth().horizontalEdgeFade(legendScroll).horizontalScroll(legendScroll).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                series.forEachIndexed { i, s ->
                    val off = hidden.getOrElse(i) { false }
                    val shape = RoundedCornerShape(50)
                    Row(
                        Modifier
                            .clip(shape)
                            .background(if (off) Color.Transparent else s.color.copy(alpha = 0.10f))
                            .border(1.dp, s.color.copy(alpha = if (off) 0.2f else 0.45f), shape)
                            .noRippleClickable { haptics.tick(); if (i < hidden.size) hidden[i] = !hidden[i] }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .graphicsLayer { alpha = if (off) 0.45f else 1f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).background(s.color, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(s.label, style = B570Theme.type.caption, color = c.textPrimary, maxLines = 1)
                    }
                }
                if (paused) {
                    Text("已暫停", style = B570Theme.type.caption, color = c.warn, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .drawWithCache {
                    val labelStyle = TextStyle(color = c.textMuted, fontSize = 10.sp, fontFeatureSettings = "tnum")
                    val lineStroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val glowStroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val gridStroke = 1f
                    val left = 40.dp.toPx()
                    val right = size.width - 6.dp.toPx()
                    val top = 6.dp.toPx()
                    val bottom = size.height - 6.dp.toPx()
                    val dotR = 3.dp.toPx()
                    onDrawBehind {
                        frame.intValue // 讀取以訂閱重畫
                        val h = holder
                        if (!pausedState.value || !h.filled) h.fill(history, series)
                        h.updateRange(series, hidden, rangeState.value, minSpan)
                        // 矮圖表（手機橫放的整定頁）：刻度少一點，數字才不會疊在一起
                        h.updateTicks(measurer, labelStyle, divisions = if (bottom - top < 88.dp.toPx()) 3f else 4f)

                        val plotH = bottom - top
                        val span = (h.hi - h.lo).takeIf { it > 0f } ?: 1f
                        fun yOf(v: Float) = bottom - (v - h.lo) / span * plotH

                        // 格線與刻度
                        if (showGrid) {
                            // 刻度由小到大 = y 由下往上；與上一個已畫的數字太近就只畫格線
                            var lastLabelY = Float.POSITIVE_INFINITY
                            for (k in 0 until h.tickCount) {
                                val y = yOf(h.tickVals[k])
                                if (y < top - 1 || y > bottom + 1) continue
                                val zero = abs(h.tickVals[k]) < h.tickStep * 1e-3f
                                drawLine(if (zero) c.accent.copy(alpha = 0.28f) else c.grid.copy(alpha = 0.55f), Offset(left, y), Offset(right, y), gridStroke)
                                h.tickLayouts[k]?.let { tl ->
                                    if (lastLabelY - y >= tl.size.height + 1f) {
                                        drawText(tl, topLeft = Offset(left - tl.size.width - 6.dp.toPx(), y - tl.size.height / 2f))
                                        lastLabelY = y
                                    }
                                }
                            }
                        }
                        // 序列
                        val dx = (right - left) / (h.window - 1).coerceAtLeast(1)
                        val buckets = ((right - left) / PX_PER_BUCKET).toInt().coerceAtLeast(1)
                        clipRect(left, top - 2f, right + 2f, bottom + 2f) {
                            for (i in series.indices) {
                                if (hidden.getOrElse(i) { false }) continue
                                val n = h.counts[i]
                                if (n < 2) continue
                                val d = h.data[i]
                                val sc = series[i].scale
                                val p = h.paths[i]
                                p.rewind()
                                val x0 = left + (h.window - n) * dx
                                val points = if (n <= buckets * 2) {
                                    p.moveTo(x0, yOf(d[0] * sc))
                                    for (j in 1 until n) p.lineTo(x0 + j * dx, yOf(d[j] * sc))
                                    n
                                } else {
                                    buildMinMaxPath(p, d, n, buckets, { j -> x0 + j * dx }, { j -> yOf(d[j] * sc) })
                                }
                                val col = series[i].color
                                if (points <= GLOW_MAX_POINTS) drawPath(p, col.copy(alpha = 0.16f), style = glowStroke)
                                drawPath(p, col, style = lineStroke)
                                val ly = yOf(d[n - 1] * sc)
                                drawCircle(col.copy(alpha = 0.25f), dotR * 2.2f, Offset(right, ly))
                                drawCircle(col, dotR, Offset(right, ly))
                            }
                        }
                    }
                },
        )
    }
}

/** 沒有新資料超過這麼久 → 改成每 [IDLE_POLL_MS] 檢查一次。 */
private const val IDLE_AFTER_NS = 250_000_000L
private const val IDLE_POLL_MS = 100L

/** 抽樣：每個區段（px）畫一組最小 / 最大值。 */
private const val PX_PER_BUCKET = 2f

/** 點數超過這個值就不畫光暈描邊（寬描邊 + 圓角接合的路徑光柵化很貴）。 */
private const val GLOW_MAX_POINTS = 600

/**
 * 把 [n] 筆資料分成 [buckets] 段，每段依時間順序連到該段的最小值與最大值（保留尖峰），最後接到最新一筆。
 * [xAt] / [yAt] 把索引換成畫面座標。回傳路徑點數。零配置（inline，lambda 不會產生物件）。
 */
private inline fun buildMinMaxPath(p: Path, d: FloatArray, n: Int, buckets: Int, xAt: (Int) -> Float, yAt: (Int) -> Float): Int {
    var points = 0
    var lastJ = -1
    for (b in 0 until buckets) {
        val s0 = (b.toLong() * n / buckets).toInt()
        val s1 = ((b + 1).toLong() * n / buckets).toInt()
        if (s1 <= s0) continue
        var mn = s0
        var mx = s0
        for (j in s0 + 1 until s1) {
            if (d[j] < d[mn]) mn = j
            if (d[j] > d[mx]) mx = j
        }
        val first = if (mn < mx) mn else mx
        val second = if (mn < mx) mx else mn
        for (k in 0..1) {
            val j = if (k == 0) first else second
            if (j == lastJ) continue
            if (lastJ < 0) p.moveTo(xAt(j), yAt(j)) else p.lineTo(xAt(j), yAt(j))
            lastJ = j
            points++
        }
    }
    if (lastJ != n - 1) {
        p.lineTo(xAt(n - 1), yAt(n - 1))
        points++
    }
    return points
}

/** 圖表內部暫存（全部預先配置）。 */
private class ChartHolder(nSeries: Int, val window: Int) {
    val data = Array(nSeries) { FloatArray(window) }
    val counts = IntArray(nSeries)
    val paths = Array(nSeries) { Path() }
    var filled = false
    var lo = -1f
    var hi = 1f
    private var rangeInit = false

    val tickVals = FloatArray(MAX_TICKS)
    val tickLayouts = arrayOfNulls<TextLayoutResult>(MAX_TICKS)
    var tickCount = 0
    var tickStep = 0f
    private var tickFirst = Float.NaN
    private var tickDecimals = -1

    fun fill(history: TelemetryHistory, series: List<ChartSeries>) {
        for (i in series.indices) counts[i] = history.copyLast(series[i].channel, window, data[i])
        filled = true
    }

    fun updateRange(series: List<ChartSeries>, hidden: List<Boolean>, fixed: ClosedFloatingPointRange<Float>?, minSpan: Float) {
        if (fixed != null) { lo = fixed.start; hi = fixed.endInclusive; rangeInit = true; return }
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        for (i in series.indices) {
            if (hidden.getOrElse(i) { false }) continue
            val d = data[i]
            val sc = series[i].scale
            for (j in 0 until counts[i]) {
                val v = d[j] * sc
                if (v.isNaN()) continue
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
        }
        if (mn == Float.POSITIVE_INFINITY) { mn = -minSpan / 2; mx = minSpan / 2 }
        if (mx - mn < minSpan) { val mid = (mx + mn) / 2; mn = mid - minSpan / 2; mx = mid + minSpan / 2 }
        val pad = (mx - mn) * 0.08f
        val tlo = mn - pad
        val thi = mx + pad
        if (!rangeInit) { lo = tlo; hi = thi; rangeInit = true; return }
        // 擴張立即、收縮平滑
        lo = if (tlo < lo) tlo else lo + (tlo - lo) * 0.08f
        hi = if (thi > hi) thi else hi + (thi - hi) * 0.08f
    }

    fun updateTicks(measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, divisions: Float = 4f) {
        val span = hi - lo
        if (span <= 0f || span.isNaN()) { tickCount = 0; return }
        val step = niceStep(span / divisions)
        val first = ceil(lo / step) * step
        val dec = when { step >= 1f -> 0; step >= 0.1f -> 1; step >= 0.01f -> 2; else -> 3 }
        if (step == tickStep && first == tickFirst && dec == tickDecimals) return
        tickStep = step; tickFirst = first; tickDecimals = dec
        var n = 0
        var v = first
        while (v <= hi + step * 1e-3f && n < MAX_TICKS) {
            tickVals[n] = v
            val txt = String.format(Locale.US, "%.${dec}f", if (abs(v) < step * 1e-3f) 0f else v)
            tickLayouts[n] = measurer.measure(txt, style)
            n++
            v = first + n * step
        }
        tickCount = n
    }

    private fun niceStep(raw: Float): Float {
        if (raw <= 0f || raw.isNaN()) return 1f
        val exp = floor(log10(raw.toDouble()))
        val base = 10.0.pow(exp)
        val f = raw / base
        val nice = when { f < 1.5 -> 1.0; f < 3.0 -> 2.0; f < 7.0 -> 5.0; else -> 10.0 }
        return (nice * base).toFloat()
    }

    companion object {
        const val MAX_TICKS = 12
    }
}

