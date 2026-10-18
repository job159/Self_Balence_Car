package com.b570.pilot.ui.screens.choreo

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.theme.B570Theme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val RAD2DEG = (180.0 / PI).toFloat()

/** 軌跡 mm → 畫布 px（等比例、置中、上方 = 起始方向）。 */
internal class PathFit(traj: SeqTrajectory, w: Float, h: Float, pad: Float, minExtentMm: Float) {
    private val cx = (traj.minX + traj.maxX) / 2f
    private val cy = (traj.minY + traj.maxY) / 2f
    private val mx = w / 2f
    private val my = h / 2f
    val scale: Float = min(
        (w - 2 * pad).coerceAtLeast(1f) / max(traj.maxX - traj.minX, minExtentMm),
        (h - 2 * pad).coerceAtLeast(1f) / max(traj.maxY - traj.minY, minExtentMm),
    )

    fun x(mm: Float) = mx + (mm - cx) * scale
    fun y(mm: Float) = my - (mm - cy) * scale
}

/** 格線間距 mm（約 6 格）。 */
internal fun gridSpacingMm(traj: SeqTrajectory, minExtentMm: Float): Float {
    val e = max(traj.spanMm, minExtentMm)
    val target = e / 6f
    for (c in GRID_STEPS) if (c >= target) return c
    return GRID_STEPS.last()
}

private val GRID_STEPS = floatArrayOf(50f, 100f, 200f, 250f, 500f, 1000f, 2000f, 5000f)

/** 小車圖示（尖端朝上，中心在原點）。 */
internal fun carGlyph(s: Float): Path = Path().apply {
    moveTo(0f, -s)
    lineTo(s * 0.74f, s * 0.8f)
    lineTo(0f, s * 0.4f)
    lineTo(-s * 0.74f, s * 0.8f)
    close()
}

/**
 * 循環播放的時間軸（秒，0..totalSec），結尾停留 [holdSec]。太長的編排會加速，使一輪不超過 [maxCycleSec]。
 * 只在繪製階段讀取 → 不觸發重組。
 */
@Composable
internal fun rememberLoopClock(totalSec: Float, maxCycleSec: Float, holdSec: Float, running: Boolean): FloatState {
    val state = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(totalSec, maxCycleSec, holdSec, running) {
        if (!running || totalSec <= 0f) {
            state.floatValue = 0f
            return@LaunchedEffect
        }
        val speed = max(1f, totalSec / maxCycleSec)
        val cycle = totalSec / speed + holdSec
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val e = ((now - start) / 1e9f) % cycle
            state.floatValue = min(e * speed, totalSec)
        }
    }
    return state
}

/**
 * 在時間 [t] 畫出小車（插值位置 + 航向）與漸淡尾跡。零配置。
 * @param ringRadius > 0 時在小車周圍畫航向環（原地動作用）
 */
internal fun DrawScope.drawCarAt(
    traj: SeqTrajectory,
    px: FloatArray,
    py: FloatArray,
    t: Float,
    color: Color,
    glyph: Path,
    trailSec: Float,
    trailWidth: Float,
    glowRadius: Float,
    ringRadius: Float,
    ringStroke: Stroke,
) {
    val n = traj.size
    if (n == 0) return
    val i = traj.indexAt(t)
    val j = min(i + 1, n - 1)
    val t0 = traj.ts[i]
    val t1 = traj.ts[j]
    val f = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0f, 1f) else 0f
    val cx = px[i] + (px[j] - px[i]) * f
    val cy = py[i] + (py[j] - py[i]) * f
    val hd = traj.hs[i] + (traj.hs[j] - traj.hs[i]) * f

    if (trailSec > 0f) {
        val tStart = t - trailSec
        var k = i
        var prevX = cx
        var prevY = cy
        var guard = 0
        while (k >= 0 && traj.ts[k] >= tStart && guard < 160) {
            val a = ((traj.ts[k] - tStart) / trailSec).coerceIn(0f, 1f)
            drawLine(color.copy(alpha = a * 0.9f), Offset(prevX, prevY), Offset(px[k], py[k]), trailWidth, StrokeCap.Round)
            prevX = px[k]
            prevY = py[k]
            k--
            guard++
        }
    }
    if (ringRadius > 0f) {
        drawCircle(color.copy(alpha = 0.16f), ringRadius, Offset(cx, cy), style = ringStroke)
        val sweep = (hd * RAD2DEG).coerceIn(-360f, 360f)
        if (abs(sweep) > 0.5f) {
            drawArc(
                color.copy(alpha = 0.85f), -90f, sweep, false,
                topLeft = Offset(cx - ringRadius, cy - ringRadius), size = Size(ringRadius * 2, ringRadius * 2), style = ringStroke,
            )
        }
    }
    drawCircle(color.copy(alpha = 0.22f), glowRadius, Offset(cx, cy))
    withTransform({
        translate(cx, cy)
        rotate(hd * RAD2DEG, Offset.Zero)
    }) {
        drawPath(glyph, color)
    }
}

/**
 * 小型軌跡預覽：淡色全軌跡 + 循環移動的小車與尾跡。原地動作會顯示航向環。
 */
@Composable
internal fun MiniPathPreview(
    traj: SeqTrajectory,
    color: Color,
    modifier: Modifier = Modifier,
    running: Boolean = true,
    minExtentMm: Float = 500f,
    maxCycleSec: Float = 6f,
) {
    val clock = rememberLoopClock(traj.totalSec, maxCycleSec, 0.8f, running)
    Spacer(
        modifier.drawWithCache {
            val fit = PathFit(traj, size.width, size.height, 12.dp.toPx(), minExtentMm)
            val n = traj.size
            val px = FloatArray(n) { fit.x(traj.xs[it]) }
            val py = FloatArray(n) { fit.y(traj.ys[it]) }
            val path = Path()
            path.moveTo(px[0], py[0])
            for (i in 1 until n) path.lineTo(px[i], py[i])
            val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val glyph = carGlyph(6.dp.toPx())
            val ringR = if (traj.spanMm < 60f) min(size.minDimension * 0.28f, 16.dp.toPx()) else 0f
            val ring = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
            val trailW = 2.5.dp.toPx()
            val glowR = 9.dp.toPx()
            val startR = 2.5.dp.toPx()
            onDrawBehind {
                drawPath(path, color.copy(alpha = 0.22f), style = stroke)
                drawCircle(color.copy(alpha = 0.5f), startR, Offset(px[0], py[0]))
                drawCarAt(traj, px, py, clock.floatValue, color, glyph, 1.2f, trailW, glowR, ringR, ring)
            }
        },
    )
}

/**
 * 完整軌跡預覽：格線、依步驟上色的路徑、起點 / 終點、移動中的小車。
 * @param playhead 目前時間（秒），只在繪製階段呼叫
 * @param selectedStep 強調的步驟（-1 = 不強調）
 * @param small 縮圖（約 60–90 dp）：留白、線寬、小車都縮小
 */
@Composable
internal fun PathPreview(
    traj: SeqTrajectory,
    stepColors: List<Color>,
    playhead: () -> Float,
    modifier: Modifier = Modifier,
    selectedStep: Int = -1,
    minExtentMm: Float = 600f,
    showGrid: Boolean = true,
    small: Boolean = false,
) {
    val c = B570Theme.colors
    val gridMm = gridSpacingMm(traj, minExtentMm)
    val gridColor = c.grid
    val carColor = c.textPrimary
    val fallback = c.accent
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val sk = if (small) 0.6f else 1f
            val fit = PathFit(traj, w, h, (if (small) 8.dp else 18.dp).toPx(), minExtentMm)
            val n = traj.size
            val px = FloatArray(n) { fit.x(traj.xs[it]) }
            val py = FloatArray(n) { fit.y(traj.ys[it]) }
            val sc = traj.stepCount.coerceAtLeast(1)
            val paths = Array(sc) { Path() }
            val last = IntArray(sc) { -2 }
            for (k in 1 until n) {
                val s = traj.stepAt[k].coerceIn(0, sc - 1)
                val p = paths[s]
                if (last[s] != k - 1) p.moveTo(px[k - 1], py[k - 1])
                p.lineTo(px[k], py[k])
                last[s] = k
            }
            val grid = Path()
            val g = gridMm * fit.scale
            val ox = fit.x(0f)
            val oy = fit.y(0f)
            if (showGrid && g > 6f) {
                var gx = ox - floor(ox / g) * g
                while (gx <= w) { grid.moveTo(gx, 0f); grid.lineTo(gx, h); gx += g }
                var gy = oy - floor(oy / g) * g
                while (gy <= h) { grid.moveTo(0f, gy); grid.lineTo(w, gy); gy += g }
            }
            val hair = Stroke(1f)
            val stroke = Stroke(2.5.dp.toPx() * sk, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val bold = Stroke(4.dp.toPx() * sk, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val glow = Stroke(10.dp.toPx() * sk, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val glyph = carGlyph(8.dp.toPx() * sk)
            val ringR = if (traj.spanMm < 60f) min(size.minDimension * 0.22f, 26.dp.toPx()) else 0f
            val ring = Stroke(2.dp.toPx() * sk, cap = StrokeCap.Round)
            val trailW = 3.dp.toPx() * sk
            val glowR = 13.dp.toPx() * sk
            val markR = 6.dp.toPx() * sk
            val markStroke = Stroke(2.dp.toPx() * sk)
            val cross = 5.dp.toPx() * sk
            onDrawBehind {
                drawPath(grid, gridColor, style = hair)
                drawLine(gridColor.copy(alpha = 0.6f), Offset(ox - cross, oy), Offset(ox + cross, oy), 1.5f)
                drawLine(gridColor.copy(alpha = 0.6f), Offset(ox, oy - cross), Offset(ox, oy + cross), 1.5f)
                val dim = selectedStep >= 0
                for (s in 0 until sc) {
                    val col = stepColors.getOrElse(s) { fallback }
                    if (s == selectedStep) {
                        drawPath(paths[s], col.copy(alpha = 0.22f), style = glow)
                        drawPath(paths[s], col, style = bold)
                    } else {
                        drawPath(paths[s], col.copy(alpha = if (dim) 0.28f else 0.85f), style = stroke)
                    }
                }
                // 起點（空心環）與終點（實心點）
                drawCircle(carColor.copy(alpha = 0.7f), markR, Offset(px[0], py[0]), style = markStroke)
                drawCircle(carColor.copy(alpha = 0.55f), markR * 0.55f, Offset(px[n - 1], py[n - 1]))
                drawCarAt(traj, px, py, playhead(), carColor, glyph, 1.0f, trailW, glowR, ringR, ring)
            }
        },
    )
}

/**
 * 步驟進度條：每段寬度依時間比例，已完成實色、進行中部分填滿、未到淡色。
 * @param progressSec 單趟內目前時間（秒），只在繪製階段呼叫；< 0 = 全部淡色
 * @param selectedStep 加外框的步驟（-1 = 無）
 */
@Composable
internal fun StepProgressStrip(
    stepStartSec: FloatArray,
    stepColors: List<Color>,
    progressSec: () -> Float,
    modifier: Modifier = Modifier,
    selectedStep: Int = -1,
) {
    val outline = B570Theme.colors.textPrimary
    Spacer(
        modifier.drawWithCache {
            val n = min(stepColors.size, stepStartSec.size - 1).coerceAtLeast(0)
            val total = if (n > 0) stepStartSec[n].coerceAtLeast(0.001f) else 1f
            val gap = if (n > 24) 1.dp.toPx() else 2.dp.toPx()
            val usable = (size.width - gap * (n - 1).coerceAtLeast(0)).coerceAtLeast(1f)
            val xs = FloatArray(n)
            val ws = FloatArray(n)
            var x = 0f
            for (i in 0 until n) {
                xs[i] = x
                ws[i] = max(1.5f, (stepStartSec[i + 1] - stepStartSec[i]) / total * usable)
                x += ws[i] + gap
            }
            val r = CornerRadius(size.height / 2f)
            val hgt = size.height
            val sel = Stroke(1.5.dp.toPx())
            onDrawBehind {
                val p = progressSec()
                for (i in 0 until n) {
                    val a = stepStartSec[i]
                    val b = stepStartSec[i + 1]
                    val col = stepColors[i]
                    val fill = when {
                        p < 0f -> 0f
                        p >= b -> 1f
                        p <= a -> 0f
                        else -> (p - a) / (b - a).coerceAtLeast(0.001f)
                    }
                    drawRoundRect(col.copy(alpha = 0.16f), Offset(xs[i], 0f), Size(ws[i], hgt), r)
                    if (fill > 0f) {
                        val current = fill < 1f
                        drawRoundRect(col.copy(alpha = if (current) 1f else 0.75f), Offset(xs[i], 0f), Size(ws[i] * fill, hgt), r)
                    }
                }
                if (selectedStep in 0 until n) {
                    drawRoundRect(outline.copy(alpha = 0.85f), Offset(xs[selectedStep], 0f), Size(ws[selectedStep], hgt), r, style = sel)
                }
            }
        },
    )
}

/** 進度條上的位置（0..1，依時間比例）→ 步驟索引（點進度條選步驟用；n = 0 時回傳 -1）。 */
internal fun stepIndexAtFraction(frac: Float, stepStartSec: FloatArray, n: Int): Int {
    if (n <= 0 || stepStartSec.size < n + 1) return -1
    val t = frac.coerceIn(0f, 1f) * stepStartSec[n]
    for (i in 0 until n) if (t < stepStartSec[i + 1]) return i
    return n - 1
}
