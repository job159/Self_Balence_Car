package com.b570.pilot.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.theme.B570Theme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** 搖桿軸向。 */
enum class JoystickAxes { BOTH, VERTICAL, HORIZONTAL }

/** 搖桿外觀（只影響繪製與刻度觸覺，輸出規則完全相同）。 */
enum class JoystickStyle {
    /** 圓形 / 膠囊底座（原本外觀）；大尺寸（≥ 150 dp）時多畫同心刻度圈與 8 方位刻度。 */
    STANDARD,

    /** 油門：直立滑槽 + LED 刻度 + 上下「前進 / 後退」標籤 + 中央定位線，T 形把手。建議 axes = VERTICAL。 */
    THROTTLE,

    /** 轉向：水平弧形軌道 + LED 刻度 + 「左 / 右」標籤。建議 axes = HORIZONTAL。 */
    STEERING,

    /**
     * 360° 類比搖桿（像手把的類比搖桿）：圓形凹槽底座 + 360° 刻度環（前 / 後 / 左 / 右 + 斜向刻度）、
     * 外環發光弧指向推桿方向（長度 / 亮度 = 推桿量）、凹面旋鈕帽 + 陰影、上次指令殘影、角度° / % 讀數。
     * 一律雙軸（忽略 axes）；換算見 [analogStickMap]：真圓形行程（徑向夾限）、圓形死區、expo 只套在推桿量（方向不變）、
     * 正方向吸附（直線前進轉向 = 0、原地旋轉速度 = 0）。
     * 觸覺：離開死區 tick、經過前 / 後 / 左 / 右 輕 tick、推到邊緣輕撞（click）。
     */
    ANALOG_360,
}

/** 360° 搖桿的正方向吸附角度（前 / 後 / 左 / 右 ±5° 內輸出正方向）。 */
const val ANALOG_SNAP_DEG = 5f

/** 搖桿兩端的刻度標籤（[positive] = 上 / 右，[negative] = 下 / 左）。空字串 = 不畫。 */
@Immutable
data class JoystickLabels(val positive: String, val negative: String) {
    companion object {
        val Throttle = JoystickLabels("前進", "後退")
        val Steering = JoystickLabels("右", "左")
        val None = JoystickLabels("", "")
    }
}

/**
 * 霓虹搖桿：發光底座、彈簧回中（平衡車的安全設計，放開一定回 0）、死區、指數曲線、觸覺刻度（離開死區、推到底；
 * THROTTLE / STEERING 另有每 25 % 一格的輕刻度）。
 * 每個搖桿只追蹤「按下它的那根手指」，兩個搖桿可同時用兩根拇指操作（多點觸控獨立）；
 * 同一個搖桿上的第二根手指會被忽略，直到第一根放開。
 * 按著的時候搖桿本身被移動 / 改變大小（換邊、切換分頁的動畫、視窗大小改變…）或手指座標跳動（螢幕 180° 翻轉）→
 * 結束手勢並送 (0, 0)，要重新按下（見 [GestureGuard]）。
 *
 * 尺寸：用 Modifier 給大小（例如 `Modifier.size(220.dp)` 或 `.fillMaxHeight()`），外觀會依大小縮放；
 * 旋鈕半徑 = 短邊 × 0.22，夾在 14..36 dp，所以 180–240 dp 的大搖桿行程更長、更細膩。
 * 預設長寬比：BOTH 1:1、VERTICAL 0.42、HORIZONTAL 2.4（給了確切寬高時以 Modifier 為準的是外層尺寸）。
 *
 * @param onMove 每次移動都會**同步**呼叫（同一個觸控事件內，無 coroutine），x 右正、y 上正，-1..1（已套死區與 expo）；
 *   放開、觸控取消、enabled 改變、離開畫面時一定呼叫 (0, 0)
 * @param axes BOTH 二維 / VERTICAL 只有上下 / HORIZONTAL 只有左右
 * @param expo 0 = 線性，1 = 中心最細膩
 * @param deadzone 死區比例（預設 0.06，徑向）
 * @param enabled false = 不接受觸控並立刻結束進行中的手勢（會送 (0,0)），重新啟用後要重新按下
 * @param onRelease 放開 / 中斷後（已送 (0,0) 之後）呼叫
 * @param style 外觀，見 [JoystickStyle]
 * @param showReadout 在旋鈕上顯示目前輸出百分比（只在按住時；VERTICAL 顯示 y、HORIZONTAL 顯示 x、BOTH 顯示合成量）
 * @param ghost 放開後在最後位置留下淡出的殘影（約 1.4 s），方便回到同一個推桿量
 * @param feedback 回授標記（-1..1，x 右正、y 上正），例如車子實際的目標速度 / 轉向；**在繪製階段呼叫**，
 *   可直接讀 50 Hz 的 State（`{ Offset(turn, speed) }`），不會造成重組
 * @param labels 兩端標籤；null = 依 style 預設（THROTTLE 前進/後退、STEERING 左/右、STANDARD 無）
 * @param onPress 手指按下時（第一個 onMove 之前）呼叫
 * @param excludeSystemGestures true = 搖桿區域排除系統返回手勢（貼近螢幕左右邊緣時拇指不會被「返回」搶走）
 * @param snapDeg 只用於 [JoystickStyle.ANALOG_360]：正方向吸附角度（0 = 不吸附）
 */
@Composable
fun Joystick(
    onMove: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
    axes: JoystickAxes = JoystickAxes.BOTH,
    expo: Float = 0f,
    deadzone: Float = 0.06f,
    enabled: Boolean = true,
    accent: Color = B570Theme.colors.accent,
    onRelease: (() -> Unit)? = null,
    style: JoystickStyle = JoystickStyle.STANDARD,
    showReadout: Boolean = false,
    ghost: Boolean = false,
    feedback: (() -> Offset)? = null,
    labels: JoystickLabels? = null,
    onPress: (() -> Unit)? = null,
    excludeSystemGestures: Boolean = true,
    snapDeg: Float = ANALOG_SNAP_DEG,
) {
    if (style == JoystickStyle.ANALOG_360) {
        AnalogJoystick(
            onMove = onMove, modifier = modifier, expo = expo, deadzone = deadzone, enabled = enabled, accent = accent,
            onRelease = onRelease, showReadout = showReadout, ghost = ghost, feedback = feedback,
            showLabels = labels != JoystickLabels.None, onPress = onPress,
            excludeSystemGestures = excludeSystemGestures, snapDeg = snapDeg,
        )
    } else {
        ClassicJoystick(
            onMove = onMove, modifier = modifier, axes = axes, expo = expo, deadzone = deadzone, enabled = enabled,
            accent = accent, onRelease = onRelease, style = style, showReadout = showReadout, ghost = ghost,
            feedback = feedback, labels = labels, onPress = onPress, excludeSystemGestures = excludeSystemGestures,
        )
    }
}

/** 原本的搖桿（STANDARD / THROTTLE / STEERING）。 */
@Composable
private fun ClassicJoystick(
    onMove: (x: Float, y: Float) -> Unit,
    modifier: Modifier,
    axes: JoystickAxes,
    expo: Float,
    deadzone: Float,
    enabled: Boolean,
    accent: Color,
    onRelease: (() -> Unit)?,
    style: JoystickStyle,
    showReadout: Boolean,
    ghost: Boolean,
    feedback: (() -> Offset)?,
    labels: JoystickLabels?,
    onPress: (() -> Unit)?,
    excludeSystemGestures: Boolean,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val hapticsNow by rememberUpdatedState(haptics)
    val scope = rememberCoroutineScope()
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    val press by rememberUpdatedState(onPress)
    val expoState by rememberUpdatedState(expo.coerceIn(0f, 1f))
    val ghostOn by rememberUpdatedState(ghost)
    val feedbackNow by rememberUpdatedState(feedback)

    // 以下 state 只在繪製 / 版面階段讀取（不會讓搖桿或父層重組）
    var knob by remember { mutableStateOf(Offset.Zero) } // 旋鈕位移 px（相對中心，未含弧形下垂）
    var output by remember { mutableStateOf(Offset.Zero) } // 目前輸出（-1..1，y 上正）
    var magnitude by remember { mutableFloatStateOf(0f) }
    var touching by remember { mutableStateOf(false) }
    val readout = remember { mutableIntStateOf(0) }
    var ghostPos by remember { mutableStateOf(Offset.Zero) }
    val ghostAlpha = remember { Animatable(0f) }
    val jobs = remember { arrayOfNulls<Job>(2) } // [0] 回中彈簧、[1] 殘影淡出
    val guard = remember { GestureGuard() }
    // 轉向弧形（travelX, arcTop, sag；px）：drawWithCache 依尺寸寫入，讀數位置在版面階段讀（非 state）
    val arcInfo = remember { FloatArray(3) }

    val lbl = labels ?: when (style) {
        JoystickStyle.THROTTLE -> JoystickLabels.Throttle
        JoystickStyle.STEERING -> JoystickLabels.Steering
        JoystickStyle.STANDARD, JoystickStyle.ANALOG_360 -> JoystickLabels.None
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = B570Theme.type.caption

    val shapeMod = when (axes) {
        JoystickAxes.BOTH -> Modifier.aspectRatio(1f)
        JoystickAxes.VERTICAL -> Modifier.aspectRatio(0.42f)
        JoystickAxes.HORIZONTAL -> Modifier.aspectRatio(2.4f)
    }

    Box(
        modifier
            .then(shapeMod)
            .then(if (excludeSystemGestures) Modifier.systemGestureExclusion() else Modifier)
            .pointerInput(axes, enabled, deadzone, style) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id: PointerId = down.id
                    jobs[0]?.cancel() // 殘影（jobs[1]）繼續淡出：按住時還看得到上次的位置
                    touching = true
                    press?.invoke()
                    val g = joyGeometry(size.width.toFloat(), size.height.toFloat(), axes, style)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    var inDead = true
                    var atEdge = false
                    var bucket = 0
                    val detents = style != JoystickStyle.STANDARD
                    val tmp = FloatArray(4) // 每個手勢配置一次，移動事件零配置

                    fun update(pos: Offset) {
                        val rr = joystickMap(
                            dx = if (g.travelX > 0f) (pos.x - center.x) / g.travelX else 0f,
                            dy = if (g.travelY > 0f) (pos.y - center.y) / g.travelY else 0f,
                            deadzone = deadzone, expo = expoState, out = tmp,
                        )
                        val outX = tmp[0]
                        val outY = tmp[1]
                        knob = Offset(tmp[2] * g.travelX, tmp[3] * g.travelY)
                        magnitude = rr
                        val nowDead = rr < deadzone
                        val h = hapticsNow
                        if (inDead && !nowDead) h.tick()
                        val nowEdge = rr >= 0.985f
                        if (nowEdge && !atEdge) h.heavy()
                        if (detents && !nowEdge) {
                            val b = (rr * 4f).toInt().coerceIn(0, 3)
                            if (b != bucket && b > 0) h.tick()
                            bucket = b
                        }
                        inDead = nowDead
                        atEdge = nowEdge
                        output = Offset(outX, outY)
                        readout.intValue = when (axes) {
                            JoystickAxes.VERTICAL -> (outY * 100f).roundToInt()
                            JoystickAxes.HORIZONTAL -> (outX * 100f).roundToInt()
                            JoystickAxes.BOTH -> (minOf(hypot(outX, outY), 1f) * 100f).roundToInt()
                        }
                        move(outX, outY)
                    }

                    guard.begin(down.position, size)
                    val driftPx = LAYOUT_DRIFT.toPx()
                    try {
                        update(down.position)
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            // 搖桿在手指下面移動 / 改變大小、或座標跳動 → 結束手勢（送 0），要重新按下
                            if (guard.broken(ch.position, ch.previousPosition, size, driftPx)) { ch.consume(); break }
                            update(ch.position)
                            ch.consume()
                        }
                    } finally {
                        // 正常放開、觸控被取消、enabled 改變或離開畫面（協程被取消）都一定歸零
                        touching = false
                        magnitude = 0f
                        output = Offset.Zero
                        readout.intValue = 0
                        move(0f, 0f)
                        release?.invoke()
                        val from = knob
                        if (ghostOn && from != Offset.Zero) {
                            jobs[1]?.cancel()
                            ghostPos = from
                            jobs[1] = scope.launch {
                                ghostAlpha.snapTo(0.85f)
                                ghostAlpha.animateTo(0f, tween(1400, easing = FastOutSlowInEasing))
                            }
                        }
                        jobs[0] = scope.launch {
                            animate(Offset.VectorConverter, from, Offset.Zero, animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                                knob = v
                            }
                        }
                    }
                }
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val g = joyGeometry(w, h, axes, style)
                arcInfo[0] = g.travelX
                arcInfo[1] = g.arcTop
                arcInfo[2] = g.sag
                val center = Offset(w / 2f, h / 2f)
                val minD = minOf(w, h)
                val baseCorner = CornerRadius(minD / 2f)
                val ringStroke = Stroke(1.5.dp.toPx())
                val thin = 1.dp.toPx()
                val trailStroke = 3.dp.toPx()
                val glowStroke = Stroke(10.dp.toPx())
                val hairStroke = Stroke(1f)
                val thinStroke = Stroke(thin)
                val markStroke = Stroke(2 * thin)
                val ghostStroke = Stroke(1.5f * thin)
                val baseFill = Brush.radialGradient(
                    listOf(c.glassHigh, c.glass, c.background.copy(alpha = 0.6f)),
                    center, maxOf(w, h) / 2f,
                )
                // 旋鈕畫在中心，再 translate 到目前位置（光澤跟著旋鈕走）
                val kw = g.knobW
                val kh = g.knobH
                val kCorner = CornerRadius(minOf(kw, kh) / 2f)
                val kTopLeft = Offset(center.x - kw / 2f, center.y - kh / 2f)
                val kSize = Size(kw, kh)
                val knobFill = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.95f), accent, accent.copy(alpha = 0.7f)),
                    Offset(center.x - kw * 0.15f, center.y - kh * 0.15f),
                    maxOf(kw, kh) * 0.7f,
                )
                val guide = c.textMuted.copy(alpha = 0.35f)
                val ledOff = c.textMuted.copy(alpha = 0.22f)
                val big = minD >= 150.dp.toPx()
                val posLayout = if (lbl.positive.isNotEmpty()) measurer.measure(lbl.positive, labelStyle) else null
                val negLayout = if (lbl.negative.isNotEmpty()) measurer.measure(lbl.negative, labelStyle) else null

                // 弧形轉向軌道（polyline，只在尺寸改變時建立）
                val arcPath = if (style == JoystickStyle.STEERING) Path().apply {
                    val n = 32
                    for (i in 0..n) {
                        val xn = -1f + 2f * i / n
                        val p = Offset(center.x + xn * g.travelX, center.y + g.arcTop + g.sag * xn * xn)
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                } else null
                val trackStroke = Stroke(width = minOf(kw, kh) * 0.62f, cap = StrokeCap.Round)
                val trackEdge = Stroke(width = minOf(kw, kh) * 0.62f + 2 * thin, cap = StrokeCap.Round)

                onDrawBehind {
                    val k = knob
                    val m = magnitude
                    val out = output
                    val alpha = if (enabled) 1f else 0.35f
                    val sagY = if (style == JoystickStyle.STEERING && g.travelX > 0f) {
                        val xn = (k.x / g.travelX).coerceIn(-1f, 1f)
                        g.arcTop + g.sag * xn * xn
                    } else 0f

                    // 底座
                    drawRoundRect(baseFill, size = size, cornerRadius = baseCorner, alpha = alpha)
                    drawRoundRect(accent.copy(alpha = (0.25f + 0.55f * m) * alpha), size = size, cornerRadius = baseCorner, style = ringStroke)
                    if (m > 0f) drawRoundRect(accent.copy(alpha = 0.12f * m * m), size = size, cornerRadius = baseCorner, style = glowStroke)

                    when (style) {
                        JoystickStyle.STANDARD, JoystickStyle.ANALOG_360 -> {
                            // 十字準線與死區圈
                            val gd = guide.copy(alpha = guide.alpha * alpha)
                            if (axes != JoystickAxes.VERTICAL) drawLine(gd, Offset(kw * 0.3f, center.y), Offset(w - kw * 0.3f, center.y), 1f)
                            if (axes != JoystickAxes.HORIZONTAL) drawLine(gd, Offset(center.x, kh * 0.3f), Offset(center.x, h - kh * 0.3f), 1f)
                            drawCircle(gd, minOf(kw, kh) * 0.25f, center, style = hairStroke)
                            if (big && axes == JoystickAxes.BOTH) {
                                // 大搖桿：50 % 行程圈 + 8 方位刻度
                                val travel = minOf(g.travelX, g.travelY)
                                drawCircle(gd, travel * 0.5f, center, style = thinStroke)
                                val outer = minD / 2f - 4.dp.toPx()
                                for (i in 0 until 8) {
                                    val a = i * (Math.PI / 4).toFloat()
                                    val len = if (i % 2 == 0) 12.dp.toPx() else 6.dp.toPx()
                                    val dxs = cos(a)
                                    val dys = sin(a)
                                    drawLine(
                                        if (i % 2 == 0) accent.copy(alpha = 0.45f * alpha) else gd,
                                        Offset(center.x + dxs * outer, center.y + dys * outer),
                                        Offset(center.x + dxs * (outer - len), center.y + dys * (outer - len)),
                                        thin * 1.5f, StrokeCap.Round,
                                    )
                                }
                            }
                            // 軌跡
                            if (k != Offset.Zero) drawLine(accent.copy(alpha = 0.45f * alpha), center, center + k, trailStroke, StrokeCap.Round)
                        }

                        JoystickStyle.THROTTLE -> {
                            // 滑槽
                            val slotW = (w * 0.2f).coerceAtLeast(6.dp.toPx())
                            val top = center.y - g.travelY
                            val bottom = center.y + g.travelY
                            drawRoundRect(
                                c.background.copy(alpha = 0.55f * alpha),
                                topLeft = Offset(center.x - slotW / 2f, top - kh * 0.25f),
                                size = Size(slotW, bottom - top + kh * 0.5f),
                                cornerRadius = CornerRadius(slotW / 2f),
                            )
                            // LED 刻度：從中心亮到目前輸出（前進 accent、後退 accent2）
                            val n = 10
                            val step = g.travelY / n
                            val ledH = (step * 0.42f).coerceAtLeast(1.5f)
                            val ledW = slotW * 0.62f
                            val lit = abs(out.y)
                            for (i in 1..n) {
                                val frac = i / n.toFloat()
                                val on = lit >= frac - 0.05f
                                val colUp = if (on && out.y > 0f) accent else ledOff
                                val colDn = if (on && out.y < 0f) c.accent2 else ledOff
                                val yUp = center.y - i * step
                                val yDn = center.y + i * step
                                drawRoundRect(colUp.copy(alpha = colUp.alpha * alpha), Offset(center.x - ledW / 2f, yUp - ledH / 2f), Size(ledW, ledH), CornerRadius(ledH / 2f))
                                drawRoundRect(colDn.copy(alpha = colDn.alpha * alpha), Offset(center.x - ledW / 2f, yDn - ledH / 2f), Size(ledW, ledH), CornerRadius(ledH / 2f))
                                // 兩側刻度（50 % / 100 % 較長）
                                val major = i == 5 || i == n
                                val tl = if (major) slotW * 0.9f else slotW * 0.45f
                                val tc = if (major) guide.copy(alpha = 0.7f * alpha) else guide.copy(alpha = guide.alpha * alpha)
                                drawLine(tc, Offset(center.x - slotW / 2f - 3.dp.toPx() - tl, yUp), Offset(center.x - slotW / 2f - 3.dp.toPx(), yUp), thin)
                                drawLine(tc, Offset(center.x + slotW / 2f + 3.dp.toPx(), yUp), Offset(center.x + slotW / 2f + 3.dp.toPx() + tl, yUp), thin)
                                drawLine(tc, Offset(center.x - slotW / 2f - 3.dp.toPx() - tl, yDn), Offset(center.x - slotW / 2f - 3.dp.toPx(), yDn), thin)
                                drawLine(tc, Offset(center.x + slotW / 2f + 3.dp.toPx(), yDn), Offset(center.x + slotW / 2f + 3.dp.toPx() + tl, yDn), thin)
                            }
                            // 中央定位線（0）
                            drawLine(accent.copy(alpha = 0.75f * alpha), Offset(center.x - slotW * 1.6f, center.y), Offset(center.x + slotW * 1.6f, center.y), 2 * thin, StrokeCap.Round)
                            posLayout?.let { drawText(it, color = c.textSecondary.copy(alpha = alpha), topLeft = Offset(center.x - it.size.width / 2f, g.labelPad * 0.5f - it.size.height / 2f + 2.dp.toPx())) }
                            negLayout?.let { drawText(it, color = c.textSecondary.copy(alpha = alpha), topLeft = Offset(center.x - it.size.width / 2f, h - g.labelPad * 0.5f - it.size.height / 2f - 2.dp.toPx())) }
                        }

                        JoystickStyle.STEERING -> {
                            arcPath?.let {
                                drawPath(it, c.glassBorder.copy(alpha = 0.5f * alpha), style = trackEdge)
                                drawPath(it, c.background.copy(alpha = 0.6f * alpha), style = trackStroke)
                            }
                            // LED 點：從中心亮到目前輸出（右 accent、左 accent2）
                            val n = 12
                            val dot = (minOf(kw, kh) * 0.09f).coerceAtLeast(1.5f)
                            val lit = abs(out.x)
                            for (i in -n..n) {
                                if (i == 0) continue
                                val xn = i / n.toFloat()
                                val on = lit >= abs(xn) - 0.04f && sign(out.x) == sign(xn)
                                val col = if (on) (if (xn > 0f) accent else c.accent2) else ledOff
                                drawCircle(col.copy(alpha = col.alpha * alpha), if (i % 3 == 0) dot * 1.4f else dot, Offset(center.x + xn * g.travelX, center.y + g.arcTop + g.sag * xn * xn))
                            }
                            // 中央定位
                            drawLine(accent.copy(alpha = 0.75f * alpha), Offset(center.x, center.y + g.arcTop - kh * 0.42f), Offset(center.x, center.y + g.arcTop - kh * 0.22f), 2 * thin, StrokeCap.Round)
                            val ly = center.y + g.arcTop + g.sag
                            negLayout?.let { drawText(it, color = c.textSecondary.copy(alpha = alpha), topLeft = Offset(g.labelPad * 0.5f - it.size.width / 2f + 2.dp.toPx(), ly - it.size.height / 2f)) }
                            posLayout?.let { drawText(it, color = c.textSecondary.copy(alpha = alpha), topLeft = Offset(w - g.labelPad * 0.5f - it.size.width / 2f - 2.dp.toPx(), ly - it.size.height / 2f)) }
                        }
                    }

                    // 回授標記（例如車子實際目標）
                    feedbackNow?.let { fb ->
                        val f = fb()
                        val fx = f.x.coerceIn(-1f, 1f) * g.travelX
                        val fy = -f.y.coerceIn(-1f, 1f) * g.travelY
                        val fs = if (style == JoystickStyle.STEERING && g.travelX > 0f) {
                            val xn = fx / g.travelX
                            g.arcTop + g.sag * xn * xn
                        } else 0f
                        val p = Offset(center.x + fx, center.y + fy + fs)
                        val r = minOf(kw, kh) * 0.2f
                        drawCircle(c.accent2.copy(alpha = 0.9f * alpha), r, p, style = markStroke)
                        drawCircle(c.accent2.copy(alpha = 0.25f * alpha), r * 1.8f, p)
                    }

                    // 殘影
                    val ga = ghostAlpha.value
                    if (ga > 0.01f) {
                        val gp = ghostPos
                        val gs = if (style == JoystickStyle.STEERING && g.travelX > 0f) {
                            val xn = (gp.x / g.travelX).coerceIn(-1f, 1f)
                            g.arcTop + g.sag * xn * xn
                        } else 0f
                        translate(gp.x, gp.y + gs) {
                            drawRoundRect(accent.copy(alpha = 0.18f * ga * alpha), kTopLeft, kSize, kCorner)
                            drawRoundRect(accent.copy(alpha = 0.7f * ga * alpha), kTopLeft, kSize, kCorner, style = ghostStroke)
                        }
                    }

                    // 旋鈕（含外光暈）
                    translate(k.x, k.y + sagY) {
                        val glowR = maxOf(kw, kh) * 0.72f
                        drawCircle(accent.copy(alpha = (if (touching) 0.30f else 0.16f) * alpha), glowR, center)
                        drawRoundRect(knobFill, kTopLeft, kSize, kCorner, alpha = alpha)
                        drawRoundRect(Color.White.copy(alpha = 0.35f * alpha), kTopLeft, kSize, kCorner, style = ringStroke)
                        if (style == JoystickStyle.THROTTLE) {
                            // T 形把手的止滑紋（顯示讀數時讓位給數字）
                            if (!showReadout) {
                                for (i in -1..1) {
                                    drawLine(c.background.copy(alpha = 0.35f * alpha), Offset(center.x + i * kw * 0.12f, center.y - kh * 0.22f), Offset(center.x + i * kw * 0.12f, center.y + kh * 0.22f), thin * 1.5f, StrokeCap.Round)
                                }
                            }
                        } else if (!showReadout) {
                            drawRoundRect(
                                c.background.copy(alpha = 0.35f * alpha),
                                topLeft = Offset(center.x - kw * 0.16f, center.y - kh * 0.03f),
                                size = Size(kw * 0.32f, kh * 0.06f),
                                cornerRadius = CornerRadius(kh * 0.03f),
                            )
                        }
                    }
                }
            },
    ) {
        if (showReadout) {
            // 讀數只在這個葉節點重組（數值是整數百分比，字串預先建好，零配置）
            Text(
                text = readoutText(readout.intValue, axes == JoystickAxes.BOTH),
                style = B570Theme.type.label.copy(fontWeight = FontWeight.SemiBold),
                color = c.onAccent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset {
                        val k = knob
                        val tx = arcInfo[0]
                        val sy = if (style == JoystickStyle.STEERING && tx > 0f) {
                            val xn = (k.x / tx).coerceIn(-1f, 1f)
                            arcInfo[1] + arcInfo[2] * xn * xn
                        } else 0f
                        IntOffset(k.x.roundToInt(), (k.y + sy).roundToInt())
                    }
                    .graphicsLayer { alpha = if (touching) 1f else 0f },
            )
        }
    }
}

/** 指數曲線：(1-e)·v + e·v³，保持符號。 */
fun joystickCurve(v: Float, expo: Float): Float = curve(v, expo)

/**
 * 搖桿換算（純函式，手勢與單元測試共用）：
 * 正規化位移（[dx] 右正、[dy] **下**正；超過半徑 1 會夾回圓周）→ 徑向死區 → 重新縮放 → expo。
 * 結果寫入 [out]：`out[0]` = x（右正）、`out[1]` = y（**上**正）、`out[2]/out[3]` = 夾限後的旋鈕位置（dx, dy）。
 * @return 夾限後的半徑 0..1（死區判斷用）
 */
internal fun joystickMap(dx: Float, dy: Float, deadzone: Float, expo: Float, out: FloatArray): Float {
    var x = if (dx.isNaN()) 0f else dx
    var y = if (dy.isNaN()) 0f else dy
    val r = hypot(x, y)
    if (!r.isFinite()) {
        out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 0f
        return 0f
    }
    if (r > 1f) { x /= r; y /= r }
    val rr = minOf(r, 1f)
    out[2] = x
    out[3] = y
    // 設定值異常（NaN / 超出範圍）也不能算出 NaN：NaN 會讓 roundToInt 丟例外，或讓車子收到亂數
    val dz = if (deadzone.isNaN()) 0f else deadzone.coerceIn(0f, 0.9f)
    val e = if (expo.isNaN()) 0f else expo.coerceIn(0f, 1f)
    if (rr < dz || rr == 0f) {
        out[0] = 0f
        out[1] = 0f
    } else {
        val scaled = (rr - dz) / (1f - dz)
        out[0] = curve(x / rr * scaled, e)
        out[1] = curve(-y / rr * scaled, e)
    }
    return rr
}

private fun curve(v: Float, e: Float): Float {
    val a = abs(v).coerceAtMost(1f)
    return sign(v) * ((1f - e) * a + e * a * a * a)
}

/** 幾何：旋鈕大小、行程、標籤保留區、轉向弧形下垂量（手勢與繪製共用同一份計算）。 */
private class JoyGeometry(
    val knobW: Float,
    val knobH: Float,
    val travelX: Float,
    val travelY: Float,
    val labelPad: Float,
    /** 弧頂相對中心的 y 位移（px），弧頂在中央。 */
    val arcTop: Float,
    /** 兩端相對弧頂下垂量（px）。 */
    val sag: Float,
)

private fun Density.joyGeometry(w: Float, h: Float, axes: JoystickAxes, style: JoystickStyle): JoyGeometry {
    val minD = minOf(w, h)
    val r = (minD * 0.22f).coerceIn(14.dp.toPx(), 36.dp.toPx()).coerceAtMost(minD * 0.42f)
    val margin = 2.dp.toPx()
    return when (style) {
        JoystickStyle.THROTTLE -> {
            val kw = minOf(w * 0.84f, r * 3.2f)
            val kh = (r * 1.25f).coerceAtLeast(20.dp.toPx()).coerceAtMost(h * 0.3f)
            val pad = 20.dp.toPx().coerceAtMost(h * 0.12f)
            JoyGeometry(
                kw, kh,
                travelX = if (axes == JoystickAxes.VERTICAL) 0f else (w / 2f - kw / 2f - margin).coerceAtLeast(1f),
                travelY = if (axes == JoystickAxes.HORIZONTAL) 0f else (h / 2f - kh / 2f - pad).coerceAtLeast(1f),
                labelPad = pad, arcTop = 0f, sag = 0f,
            )
        }

        JoystickStyle.STEERING -> {
            val k = r * 2f
            val pad = 22.dp.toPx().coerceAtMost(w * 0.12f)
            val sag = (h * 0.16f).coerceAtMost(h / 2f - k / 2f - margin).coerceAtLeast(0f)
            JoyGeometry(
                k, k,
                travelX = if (axes == JoystickAxes.VERTICAL) 0f else (w / 2f - k / 2f - pad).coerceAtLeast(1f),
                travelY = if (axes == JoystickAxes.HORIZONTAL) 0f else (h / 2f - k / 2f - margin - sag).coerceAtLeast(1f),
                labelPad = pad, arcTop = -sag / 2f, sag = sag,
            )
        }

        JoystickStyle.STANDARD, JoystickStyle.ANALOG_360 -> {
            val k = r * 2f
            JoyGeometry(
                k, k,
                travelX = if (axes == JoystickAxes.VERTICAL) 0f else (w / 2f - r).coerceAtLeast(1f),
                travelY = if (axes == JoystickAxes.HORIZONTAL) 0f else (h / 2f - r).coerceAtLeast(1f),
                labelPad = 0f, arcTop = 0f, sag = 0f,
            )
        }
    }
}

private val PCT_SIGNED = Array(201) { i ->
    val v = i - 100
    if (v > 0) "+$v" else "$v"
}
private val PCT_PLAIN = Array(101) { "$it" }

private fun readoutText(v: Int, unsigned: Boolean): String =
    if (unsigned) PCT_PLAIN[v.coerceIn(0, 100)] else PCT_SIGNED[v.coerceIn(-100, 100) + 100]

// ================================================================================================ 手勢保護

/** 手勢期間搖桿本身被移動超過這個距離（手指沒動）→ 結束手勢。 */
private val LAYOUT_DRIFT = 2.dp

/** 一個觸控事件內手指位移超過搖桿長邊的這個倍數 → 視為座標跳動，結束手勢。 */
internal const val MAX_JUMP_FACTOR = 1.5f

/**
 * 手勢期間的版面 / 觸控異常檢查（兩種搖桿共用；每個搖桿一個，移動事件零配置）。
 * 換算用的是「按下當時」的搖桿大小與中心，所以：
 * - 搖桿自己在手指下面移動、縮放或改變大小（換邊 / 切換分頁 / 切換模式的動畫、視窗大小改變、整頁捲動、瀏海換邊…）：
 *   手指沒動、送出的指令卻會跳（例如切到「編排」分頁時駕駛畫面往上滑，按著前進的拇指會變成倒退）→ 結束手勢；
 * - 一個事件內手指跳了超過搖桿長邊 [MAX_JUMP_FACTOR] 倍（螢幕 180° 翻轉時座標整個鏡射、觸控面板把兩根手指搞混）
 *   → 結束手勢（否則會變成一瞬間推到底）。
 * 結束 = finally 送 (0, 0)；awaitEachGesture 要等全部手指放開才開始下一個手勢 → 一定要重新按下。
 */
internal class GestureGuard {
    private var fingerX = 0f
    private var fingerY = 0f
    private var w = 0
    private var h = 0

    fun begin(position: Offset, size: IntSize) {
        fingerX = position.x
        fingerY = position.y
        w = size.width
        h = size.height
    }

    /**
     * 每個移動事件呼叫一次。[position] / [previousPosition] 是這個事件的本地座標：Compose 用「目前」的版面換算兩者，
     * 所以兩者的差 = 手指實際移動量；手指累積移動量與目前本地座標的差 = 搖桿自己動了。
     * @return true = 這個手勢已不可信，要結束
     */
    fun broken(position: Offset, previousPosition: Offset, size: IntSize, driftPx: Float): Boolean {
        val dx = position.x - previousPosition.x
        val dy = position.y - previousPosition.y
        if (!dx.isFinite() || !dy.isFinite()) return true
        fingerX += dx
        fingerY += dy
        if (size.width != w || size.height != h) return true
        if (abs(position.x - fingerX) > driftPx || abs(position.y - fingerY) > driftPx) return true
        val jump = maxOf(w, h) * MAX_JUMP_FACTOR
        return dx * dx + dy * dy > jump * jump
    }
}

// ================================================================================================ 360° 類比搖桿

/** [analogStickMap] 的 out 陣列大小。 */
internal const val ANALOG_OUT_SIZE = 6

/** 半徑超過這個比例 = 推到邊緣（輕撞觸覺），回到 [EDGE_OFF] 以下才會再觸發。 */
private const val EDGE_ON = 0.985f
private const val EDGE_OFF = 0.93f

/** 推桿量低於這個值不做方向刻度觸覺（中心附近方向很不穩，會一直震）。 */
private const val DETENT_MIN_MAG = 0.2f

/**
 * 360° 類比搖桿換算（純函式，手勢與單元測試共用；零配置）：
 * 1. 正規化位移（[dx] 右正、[dy] **下**正，1 = 行程半徑）**徑向**夾回單位圓 → 真圓形行程（斜推不會變成 √2 倍）
 * 2. 圓形死區 [deadzone]（半徑比例）→ 重新縮放成 0..1（剛出死區從 0 連續開始）
 * 3. expo 只套在「推桿量」（[joystickCurve]），方向不變
 * 4. 正方向吸附 [snapDeg]：前 / 後 / 左 / 右 ±snapDeg 內輸出正方向（直線前進 x = 0、原地旋轉 y = 0），
 *    其餘角度連續展開（45° 斜向不變、沒有跳格）；0 = 不吸附
 *
 * 結果寫入 [out]（大小 ≥ [ANALOG_OUT_SIZE]）：
 * - `out[0]` x（右正）、`out[1]` y（**上**正），x² + y² ≤ 1
 * - `out[2]/out[3]` 夾限後的旋鈕位置（dx, dy，下正，跟手指走、不吸附）
 * - `out[4]` 推桿量 0..1（已套死區 + expo）
 * - `out[5]` 輸出方位角°（0 = 前、90 = 右、180 = 後、270 = 左；推桿量 0 時為 -1）
 * @return 夾限後的半徑 0..1（死區 / 推到底判斷用）
 */
internal fun analogStickMap(dx: Float, dy: Float, deadzone: Float, expo: Float, snapDeg: Float, out: FloatArray): Float {
    var x = if (dx.isNaN()) 0f else dx
    var y = if (dy.isNaN()) 0f else dy
    val r = hypot(x, y)
    if (!r.isFinite()) {
        out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 0f; out[4] = 0f; out[5] = -1f
        return 0f
    }
    if (r > 1f) { x /= r; y /= r }
    val rr = minOf(r, 1f)
    out[2] = x
    out[3] = y
    // 設定值異常（NaN）也不能算出 NaN（NaN.coerceIn 仍是 NaN）
    val dz = if (deadzone.isNaN()) 0f else deadzone.coerceIn(0f, 0.9f)
    if (rr == 0f || rr < dz) {
        out[0] = 0f; out[1] = 0f; out[4] = 0f; out[5] = -1f
        return rr
    }
    val scaled = ((rr - dz) / (1f - dz)).coerceIn(0f, 1f)
    val m = curve(scaled, if (expo.isNaN()) 0f else expo.coerceIn(0f, 1f))
    val b = analogSnap(analogBearing(x, -y), if (snapDeg.isNaN()) 0f else snapDeg)
    bearingUnit(b, out)
    out[0] = out[0] * m + 0f // + 0f：-0f → 0f
    out[1] = out[1] * m + 0f
    out[4] = m
    out[5] = if (m > 0f) b else -1f
    return rr
}

/** 方位角°（0 = 前 / 上、90 = 右、180 = 後、270 = 左），[x] 右正、[yUp] 上正；原點回傳 0。結果在 [0, 360)。 */
internal fun analogBearing(x: Float, yUp: Float): Float {
    if (x == 0f && yUp == 0f) return 0f
    var d = Math.toDegrees(atan2(x.toDouble(), yUp.toDouble())).toFloat()
    if (d < 0f) d += 360f
    if (d >= 360f) d -= 360f
    return d
}

/** 正方向吸附：每個象限內 [0, snap] → 0°、[90 - snap, 90) → 90°，中間線性展開（45° 不變、連續）。 */
internal fun analogSnap(bearing: Float, snapDeg: Float): Float {
    val s = snapDeg.coerceIn(0f, 44f)
    if (s <= 0f) return bearing
    val q = floor(bearing / 90f)
    val a = bearing - q * 90f
    val a2 = when {
        a <= s -> 0f
        a >= 90f - s -> 90f
        else -> (a - s) * 90f / (90f - 2f * s)
    }
    val b = q * 90f + a2
    return if (b >= 360f) b - 360f else b
}

/** 方位角 → 單位向量寫入 out[0]（x 右正）/ out[1]（y 上正）；正方向（0 / 90 / 180 / 270）精確為 0 / ±1。 */
private fun bearingUnit(b: Float, out: FloatArray) {
    val q = floor(b / 90f).toInt()
    val a = b - q * 90f
    var ux: Float
    var uy: Float
    if (a == 0f) {
        ux = 0f
        uy = 1f
    } else {
        val rad = Math.toRadians(a.toDouble())
        ux = sin(rad).toFloat()
        uy = cos(rad).toFloat()
    }
    // 每多 90°（順時針）：(x, y) → (y, -x)
    repeat(((q % 4) + 4) % 4) {
        val t = ux
        ux = uy
        uy = -t
    }
    out[0] = ux
    out[1] = uy
}

/** 360° 搖桿讀數字串（預建，零配置）。 */
internal object AnalogText {
    private val ANGLES = Array(360) { it.toString().padStart(3, '0') + "°" }
    private val PERCENTS = Array(101) { "$it%" }

    /** 「045°」 */
    fun angle(deg: Int): String = ANGLES[((deg % 360) + 360) % 360]

    /** 「72%」 */
    fun percent(p: Int): String = PERCENTS[p.coerceIn(0, 100)]
}

/** 360° 搖桿幾何（px）：外框（刻度環）、凹槽、旋鈕、行程（手勢與繪製共用同一份計算）。 */
private class AnalogGeometry(
    val outerR: Float,
    val ringInner: Float,
    val wellR: Float,
    val knobR: Float,
    /** 旋鈕中心最遠可以到的半徑 = 推桿量 100 %。 */
    val travel: Float,
)

private fun Density.analogGeometry(w: Float, h: Float): AnalogGeometry {
    val s = minOf(w, h)
    val outer = (s / 2f - 1.dp.toPx()).coerceAtLeast(4f)
    val ringW = (s * 0.085f).coerceIn(14.dp.toPx(), 30.dp.toPx()).coerceAtMost(outer * 0.22f)
    val ringInner = outer - ringW
    val well = (ringInner - 3.dp.toPx()).coerceAtLeast(2f)
    val knob = (well * 0.40f).coerceIn(14.dp.toPx(), 52.dp.toPx()).coerceAtMost(well * 0.5f)
    // 旋鈕帽可以稍微蓋到刻度環內緣（像真的類比搖桿），但不會蓋到外緣的發光弧
    val travel = (well - knob * 0.7f).coerceAtLeast(1f)
    return AnalogGeometry(outer, ringInner, well, knob, travel)
}

/**
 * 360° 類比搖桿（[JoystickStyle.ANALOG_360]）。手勢規則與其他搖桿相同：同步 onMove、只追蹤按下它的那根手指、
 * 放開 / 取消 / 停用 / 離開一定送 (0, 0)、停用後要重新按下。
 */
@Composable
private fun AnalogJoystick(
    onMove: (x: Float, y: Float) -> Unit,
    modifier: Modifier,
    expo: Float,
    deadzone: Float,
    enabled: Boolean,
    accent: Color,
    onRelease: (() -> Unit)?,
    showReadout: Boolean,
    ghost: Boolean,
    feedback: (() -> Offset)?,
    showLabels: Boolean,
    onPress: (() -> Unit)?,
    excludeSystemGestures: Boolean,
    snapDeg: Float,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val hapticsNow by rememberUpdatedState(haptics)
    val scope = rememberCoroutineScope()
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    val press by rememberUpdatedState(onPress)
    val expoNow by rememberUpdatedState(expo.coerceIn(0f, 1f))
    val snapNow by rememberUpdatedState(snapDeg)
    val ghostOn by rememberUpdatedState(ghost)
    val feedbackNow by rememberUpdatedState(feedback)

    // 以下 state 只在繪製 / 版面階段讀取（不會讓搖桿或父層重組）
    val knobX = remember { mutableFloatStateOf(0f) } // 旋鈕位移 px（相對中心，跟著手指、圓形夾限）
    val knobY = remember { mutableFloatStateOf(0f) }
    val mag = remember { mutableFloatStateOf(0f) } // 輸出推桿量 0..1
    val dir = remember { mutableFloatStateOf(0f) } // 輸出方位角°（最後一次非 0 的值）
    val touching = remember { mutableStateOf(false) }
    // 讀數（整數；只有讀數葉節點重組）：方位角 -1 = 不顯示
    val angle = remember { mutableIntStateOf(-1) }
    val pct = remember { mutableIntStateOf(0) }
    // 殘影：最後一次指令的 旋鈕 x, y（px）、推桿量、方位角
    val ghostVec = remember { FloatArray(4) }
    val ghostAlpha = remember { Animatable(0f) }
    val jobs = remember { arrayOfNulls<Job>(2) } // [0] 回中彈簧、[1] 殘影淡出
    val guard = remember { GestureGuard() }
    val measurer = rememberTextMeasurer(cacheSize = 8)

    Box(
        modifier
            .aspectRatio(1f)
            .then(if (excludeSystemGestures) Modifier.systemGestureExclusion() else Modifier)
            .pointerInput(enabled, deadzone) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id: PointerId = down.id
                    jobs[0]?.cancel() // 殘影（jobs[1]）繼續淡出：按住時還看得到上次的指令
                    touching.value = true
                    press?.invoke()
                    val g = analogGeometry(size.width.toFloat(), size.height.toFloat())
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val travel = g.travel
                    val tmp = FloatArray(ANALOG_OUT_SIZE) // 每個手勢配置一次，移動事件零配置
                    var inDead = true
                    var atEdge = false
                    var detent = -1 // 目前停在哪個正方向的吸附區（0 前 / 1 右 / 2 後 / 3 左），-1 = 沒有
                    var lastNear = -1
                    var lastSide = 0

                    fun update(pos: Offset) {
                        val rr = analogStickMap((pos.x - cx) / travel, (pos.y - cy) / travel, deadzone, expoNow, snapNow, tmp)
                        knobX.floatValue = tmp[2] * travel
                        knobY.floatValue = tmp[3] * travel
                        val m = tmp[4]
                        mag.floatValue = m
                        val h = hapticsNow
                        val nowDead = m <= 0f
                        if (inDead && !nowDead) h.tick()
                        inDead = nowDead
                        // 推到邊緣：輕撞一下（click），退回一點才會再觸發
                        if (!atEdge && rr >= EDGE_ON) {
                            h.click()
                            atEdge = true
                        } else if (atEdge && rr < EDGE_OFF) {
                            atEdge = false
                        }
                        // 經過 前 / 後 / 左 / 右：輕 tick（進入吸附區，或一次掃過正方向）
                        if (m >= DETENT_MIN_MAG) {
                            val raw = analogBearing(tmp[2], -tmp[3])
                            val near = ((raw + 45f) / 90f).toInt() % 4
                            var d = raw - near * 90f
                            if (d > 180f) d -= 360f
                            val side = if (d >= 0f) 1 else -1
                            val zone = maxOf(snapNow, 2f)
                            if (abs(d) <= zone) {
                                if (detent != near) {
                                    h.tick()
                                    detent = near
                                }
                            } else {
                                val was = detent >= 0
                                if (was && (detent != near || abs(d) > zone + 3f)) detent = -1
                                if (!was && lastNear == near && side != lastSide) h.tick()
                            }
                            lastNear = near
                            lastSide = side
                        } else {
                            detent = -1
                            lastNear = -1
                        }
                        if (!nowDead) dir.floatValue = tmp[5]
                        angle.intValue = if (nowDead) -1 else tmp[5].roundToInt() % 360
                        pct.intValue = (m * 100f).roundToInt()
                        move(tmp[0], tmp[1])
                    }

                    guard.begin(down.position, size)
                    val driftPx = LAYOUT_DRIFT.toPx()
                    try {
                        update(down.position)
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == id } ?: break
                            if (!ch.pressed) {
                                ch.consume()
                                break
                            }
                            // 搖桿在手指下面移動 / 改變大小（換邊、切分頁的動畫…）、或座標跳動（螢幕翻轉）→ 結束手勢（送 0），要重新按下
                            if (guard.broken(ch.position, ch.previousPosition, size, driftPx)) {
                                ch.consume()
                                break
                            }
                            update(ch.position)
                            ch.consume()
                        }
                    } finally {
                        // 正常放開、觸控被取消、enabled 改變或離開畫面（協程被取消）都一定歸零
                        touching.value = false
                        val lastMag = mag.floatValue
                        mag.floatValue = 0f
                        angle.intValue = -1
                        pct.intValue = 0
                        move(0f, 0f)
                        release?.invoke()
                        val fx = knobX.floatValue
                        val fy = knobY.floatValue
                        if (ghostOn && lastMag > 0f) {
                            jobs[1]?.cancel()
                            ghostVec[0] = fx
                            ghostVec[1] = fy
                            ghostVec[2] = lastMag
                            ghostVec[3] = dir.floatValue
                            jobs[1] = scope.launch {
                                ghostAlpha.snapTo(0.9f)
                                ghostAlpha.animateTo(0f, tween(1400, easing = FastOutSlowInEasing))
                            }
                        }
                        // 彈簧回中（稍微過衝，像真的搖桿；輸出已經是 0）
                        jobs[0] = scope.launch {
                            animate(
                                Offset.VectorConverter, Offset(fx, fy), Offset.Zero,
                                animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
                            ) { v, _ ->
                                knobX.floatValue = v.x
                                knobY.floatValue = v.y
                            }
                        }
                    }
                }
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val g = analogGeometry(w, h)
                val ctr = Offset(w / 2f, h / 2f)
                val outer = g.outerR
                val ringW = outer - g.ringInner
                val ringMid = (outer + g.ringInner) / 2f
                val kr = g.knobR
                val travel = g.travel
                val hair = 1.dp.toPx()
                val hairStroke = Stroke(hair)
                val dashStroke = Stroke(hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
                val bandStroke = Stroke(ringW * 0.72f)
                val tickBold = 2.dp.toPx()
                val vecW = 3.dp.toPx()
                val markStroke = Stroke(2.dp.toPx())
                val ghostStroke = Stroke(1.5.dp.toPx())
                val guide = c.textMuted.copy(alpha = 0.24f)

                // 發光弧：刻度環外緣（旋鈕推到底也蓋不到）
                val arcCoreW = (ringW * 0.2f).coerceAtLeast(2.5.dp.toPx())
                val arcR = outer - arcCoreW / 2f - hair
                val arcCore = Stroke(arcCoreW, cap = StrokeCap.Round)
                val arcGlow = Stroke(arcCoreW * 3.2f, cap = StrokeCap.Round)
                val arcTL = Offset(ctr.x - arcR, ctr.y - arcR)
                val arcSize = Size(arcR * 2f, arcR * 2f)

                // 外框（刻度環）：上亮下暗的金屬環
                val bezelFill = Brush.verticalGradient(
                    listOf(lerpColor(c.surface, Color.White, 0.12f), lerpColor(c.surface, Color.Black, 0.45f)),
                    startY = ctr.y - outer, endY = ctr.y + outer,
                )
                // 凹槽：中心偏下較亮（光從上方打進碗底）+ 上緣內側陰影 = 往下凹
                val wellFill = Brush.radialGradient(
                    listOf(lerpColor(c.background, c.surface, 0.6f), c.background, lerpColor(c.background, Color.Black, 0.55f)),
                    center = Offset(ctr.x, ctr.y + g.wellR * 0.22f), radius = g.wellR * 1.12f,
                )
                val wellShade = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f), 0.4f to Color.Transparent, 1f to Color.White.copy(alpha = 0.05f),
                    startY = ctr.y - g.wellR, endY = ctr.y + g.wellR,
                )
                // 唇邊（刻度環與凹槽之間的斜面）：上緣受光、下緣陰影
                val lipW = 3.dp.toPx()
                val lipR = g.wellR + lipW / 2f
                val lipStroke = Stroke(lipW)
                val lipLine = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round)
                val lipTL = Offset(ctr.x - lipR, ctr.y - lipR)
                val lipSize = Size(lipR * 2f, lipR * 2f)
                val lipColor = lerpColor(c.surface, Color.Black, 0.25f)

                // 刻度：每 15° 一格；45° 斜向較長；正方向有字時不畫刻度
                val ticks = FloatArray(24 * 4)
                for (i in 0 until 24) {
                    val rad = Math.toRadians((i * 15 - 90).toDouble())
                    val half = if (i % 3 == 0) ringW * 0.30f else ringW * 0.13f
                    val cs = cos(rad).toFloat()
                    val sn = sin(rad).toFloat()
                    ticks[i * 4] = ctr.x + cs * (ringMid - half)
                    ticks[i * 4 + 1] = ctr.y + sn * (ringMid - half)
                    ticks[i * 4 + 2] = ctr.x + cs * (ringMid + half)
                    ticks[i * 4 + 3] = ctr.y + sn * (ringMid + half)
                }
                // 方向字：前 / 右 / 後 / 左（字級跟著刻度環寬，不受系統字級影響 → 一定放得下）
                val labelStyle = TextStyle(fontSize = (ringW * 0.56f).toSp(), fontWeight = FontWeight.Bold)
                val labels = if (showLabels) {
                    arrayOf(
                        measurer.measure("前", labelStyle), measurer.measure("右", labelStyle),
                        measurer.measure("後", labelStyle), measurer.measure("左", labelStyle),
                    )
                } else {
                    null
                }
                val labelPos = FloatArray(8)
                if (labels != null) {
                    for (k in 0..3) {
                        val rad = Math.toRadians((k * 90 - 90).toDouble())
                        val lw = labels[k].size.width
                        val lh = labels[k].size.height
                        labelPos[k * 2] = ctr.x + cos(rad).toFloat() * ringMid - lw / 2f
                        labelPos[k * 2 + 1] = ctr.y + sin(rad).toFloat() * ringMid - lh / 2f
                    }
                }

                // 旋鈕（畫在中心，再 translate 到目前位置；光線方向固定在左上）
                val bodyBrush = Brush.radialGradient(
                    listOf(lerpColor(c.surface, Color.White, 0.28f), c.surface, lerpColor(c.surface, Color.Black, 0.62f)),
                    center = Offset(ctr.x - kr * 0.38f, ctr.y - kr * 0.46f), radius = kr * 1.7f,
                )
                val knurlR = kr * 0.84f
                val knurlSeg = (2.0 * PI * knurlR / 30.0).toFloat()
                val knurl = Stroke(kr * 0.12f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(knurlSeg * 0.42f, knurlSeg * 0.58f)))
                val rimW = 1.5.dp.toPx()
                val rimStroke = Stroke(rimW)
                val capR = kr * 0.64f
                // 凹面帽：碗底（偏下）較亮、上緣被帽緣擋住較暗
                val capFill = Brush.radialGradient(
                    listOf(lerpColor(c.surface, Color.Black, 0.12f), lerpColor(c.surface, Color.Black, 0.5f)),
                    center = Offset(ctr.x, ctr.y + capR * 0.35f), radius = capR * 1.25f,
                )
                val capShade = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.5f), 0.45f to Color.Transparent, 1f to Color.White.copy(alpha = 0.08f),
                    startY = ctr.y - capR, endY = ctr.y + capR,
                )
                val capTL = Offset(ctr.x - capR, ctr.y - capR)
                val capSize = Size(capR * 2f, capR * 2f)
                val capLine = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round)
                val specTL = Offset(ctr.x - kr * 0.92f, ctr.y - kr * 0.92f)
                val specSize = Size(kr * 1.84f, kr * 1.84f)
                val specStroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                val dot = kr * 0.13f
                val shaftColor = lerpColor(c.surface, Color.Black, 0.5f)

                onDrawBehind {
                    val a = if (enabled) 1f else 0.4f
                    val kx = knobX.floatValue
                    val ky = knobY.floatValue
                    val m = mag.floatValue
                    val d = dir.floatValue
                    val held = touching.value

                    // 1. 外框（刻度環）
                    drawCircle(bezelFill, outer, ctr, alpha = a)
                    drawCircle(c.background.copy(alpha = 0.5f * a), ringMid, ctr, style = bandStroke)
                    drawCircle(c.glassBorder.copy(alpha = 0.8f * a), outer - hair / 2f, ctr, style = hairStroke)
                    drawCircle(accent.copy(alpha = (0.18f + 0.5f * m) * a), outer - hair * 1.5f, ctr, style = hairStroke)

                    // 2. 360° 刻度
                    for (i in 0 until 24) {
                        val cardinal = i % 6 == 0
                        if (cardinal && labels != null) continue
                        val diag = i % 6 == 3
                        val col = when {
                            cardinal -> accent.copy(alpha = 0.6f * a)
                            diag -> accent.copy(alpha = 0.4f * a)
                            else -> c.textMuted.copy(alpha = 0.45f * a)
                        }
                        drawLine(
                            col, Offset(ticks[i * 4], ticks[i * 4 + 1]), Offset(ticks[i * 4 + 2], ticks[i * 4 + 3]),
                            if (cardinal || diag) tickBold else hair, StrokeCap.Round,
                        )
                    }

                    // 3. 發光弧：指向推桿方向，長度 / 亮度 = 推桿量
                    if (m > 0f) {
                        val sweep = 14f + 76f * m
                        val start = d - 90f - sweep / 2f
                        drawArc(accent.copy(alpha = (0.10f + 0.22f * m) * a), start, sweep, false, arcTL, arcSize, style = arcGlow)
                        drawArc(accent.copy(alpha = (0.55f + 0.45f * m) * a), start, sweep, false, arcTL, arcSize, style = arcCore)
                    }

                    // 4. 方向字：推向哪邊，哪個字就亮
                    if (labels != null) {
                        for (k in 0..3) {
                            val t = if (m > 0f) {
                                val cs = cos(Math.toRadians((d - k * 90f).toDouble())).toFloat().coerceAtLeast(0f)
                                cs * cs * m
                            } else {
                                0f
                            }
                            val col = lerpColor(c.textSecondary, accent, t)
                            drawText(labels[k], color = col.copy(alpha = a), topLeft = Offset(labelPos[k * 2], labelPos[k * 2 + 1]))
                        }
                    }

                    // 5. 凹槽 + 唇邊
                    drawCircle(wellFill, g.wellR, ctr, alpha = a)
                    drawCircle(wellShade, g.wellR, ctr, alpha = a)
                    drawCircle(lipColor.copy(alpha = a), lipR, ctr, style = lipStroke)
                    drawArc(Color.White.copy(alpha = 0.22f * a), 205f, 130f, false, lipTL, lipSize, style = lipLine)
                    drawArc(Color.Black.copy(alpha = 0.45f * a), 25f, 130f, false, lipTL, lipSize, style = lipLine)
                    // 導引：50 % 行程圈、100 % 行程虛線、淡十字
                    val gd = guide.copy(alpha = guide.alpha * a)
                    drawCircle(gd, travel * 0.5f, ctr, style = hairStroke)
                    drawCircle(gd, travel, ctr, style = dashStroke)
                    drawLine(gd, Offset(ctr.x - g.wellR * 0.86f, ctr.y), Offset(ctr.x + g.wellR * 0.86f, ctr.y), hair)
                    drawLine(gd, Offset(ctr.x, ctr.y - g.wellR * 0.86f), Offset(ctr.x, ctr.y + g.wellR * 0.86f), hair)

                    // 6. 回授標記（車子實際目標，圓形夾限）
                    feedbackNow?.let { fb ->
                        val f = fb()
                        var fx = f.x.coerceIn(-1f, 1f)
                        var fy = -f.y.coerceIn(-1f, 1f)
                        val fl = hypot(fx, fy)
                        if (fl > 1f) {
                            fx /= fl
                            fy /= fl
                        }
                        val p = Offset(ctr.x + fx * travel, ctr.y + fy * travel)
                        drawCircle(c.accent2.copy(alpha = 0.22f * a), kr * 0.36f, p)
                        drawCircle(c.accent2.copy(alpha = 0.9f * a), kr * 0.2f, p, style = markStroke)
                    }

                    // 7. 殘影：上次指令的向量（放開後約 1.4 s 淡出）
                    val ga = ghostAlpha.value
                    if (ga > 0.01f) {
                        val gp = Offset(ctr.x + ghostVec[0], ctr.y + ghostVec[1])
                        val gs = 14f + 76f * ghostVec[2]
                        drawLine(accent.copy(alpha = 0.35f * ga * a), ctr, gp, vecW, StrokeCap.Round)
                        drawCircle(accent.copy(alpha = 0.12f * ga * a), kr * 0.92f, gp)
                        drawCircle(accent.copy(alpha = 0.6f * ga * a), kr * 0.92f, gp, style = ghostStroke)
                        drawArc(accent.copy(alpha = 0.45f * ga * a), ghostVec[3] - 90f - gs / 2f, gs, false, arcTL, arcSize, style = arcCore)
                    }

                    // 8. 防塵套開口 + 搖桿軸 + 指令向量
                    drawCircle(Color.Black.copy(alpha = 0.42f * a), kr * 0.72f, ctr)
                    if (kx != 0f || ky != 0f) {
                        drawLine(shaftColor.copy(alpha = a), ctr, Offset(ctr.x + kx * 0.6f, ctr.y + ky * 0.6f), kr * 0.5f, StrokeCap.Round)
                        if (m > 0f) drawLine(accent.copy(alpha = (0.25f + 0.45f * m) * a), ctr, Offset(ctr.x + kx, ctr.y + ky), vecW, StrokeCap.Round)
                    }

                    // 9. 旋鈕：陰影 → 光暈 → 本體 → 止滑紋 → 外圈 → 凹面帽 → 中心點 → 高光
                    translate(kx, ky) {
                        drawCircle(Color.Black.copy(alpha = 0.16f * a), kr * 1.12f, Offset(ctr.x + kr * 0.05f, ctr.y + kr * 0.24f))
                        drawCircle(Color.Black.copy(alpha = 0.26f * a), kr * 1.02f, Offset(ctr.x + kr * 0.03f, ctr.y + kr * 0.14f))
                        if (held) drawCircle(accent.copy(alpha = (0.14f + 0.22f * m) * a), kr * 1.42f, ctr)
                        drawCircle(bodyBrush, kr, ctr, alpha = a)
                        drawCircle(Color.Black.copy(alpha = 0.3f * a), knurlR, ctr, style = knurl)
                        drawCircle(accent.copy(alpha = (if (held) 0.9f else 0.55f) * a), kr - rimW / 2f, ctr, style = rimStroke)
                        drawCircle(capFill, capR, ctr, alpha = a)
                        drawCircle(capShade, capR, ctr, alpha = a)
                        drawArc(Color.White.copy(alpha = 0.16f * a), 25f, 130f, false, capTL, capSize, style = capLine)
                        drawArc(Color.Black.copy(alpha = 0.45f * a), 205f, 130f, false, capTL, capSize, style = capLine)
                        if (held) drawCircle(accent.copy(alpha = 0.25f * a), dot * 2.4f, ctr)
                        drawCircle(accent.copy(alpha = (if (held) 0.9f else 0.4f) * a), dot, ctr)
                        drawArc(Color.White.copy(alpha = 0.32f * a), 195f, 70f, false, specTL, specSize, style = specStroke)
                    }
                }
            },
    ) {
        if (showReadout) AnalogReadout(angle, pct, knobX, knobY, accent, Modifier.align(Alignment.Center))
    }
}

/**
 * 360° 搖桿讀數（角度° + %）：放在旋鈕「靠中心」那一側（旋鈕邊緣再往內 26 dp），不會被拇指蓋住；只在推出死區時顯示。
 * 獨立的葉節點：只有它跟著整數讀數重組；位置在擺放階段讀旋鈕位置（旋鈕半徑由搖桿大小算，不依賴繪製階段）。
 * 必須直接放在搖桿 Box 內（版面限制 = 搖桿大小）。
 */
@Composable
private fun AnalogReadout(
    angle: IntState,
    pct: IntState,
    knobX: FloatState,
    knobY: FloatState,
    accent: Color,
    modifier: Modifier,
) {
    val c = B570Theme.colors
    val a = angle.intValue
    Column(
        modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                // 父層（搖桿 Box）給的最大限制 = 搖桿大小
                val dist = analogGeometry(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()).knobR + 26.dp.toPx()
                layout(p.width, p.height) {
                    val kx = knobX.floatValue
                    val ky = knobY.floatValue
                    val len = hypot(kx, ky)
                    if (len < 0.5f) {
                        p.place(0, 0)
                    } else {
                        p.place((kx - kx / len * dist).roundToInt(), (ky - ky / len * dist).roundToInt())
                    }
                }
            }
            .graphicsLayer { alpha = if (angle.intValue >= 0) 1f else 0f }
            .background(c.background.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            AnalogText.angle(maxOf(a, 0)),
            style = B570Theme.type.label.copy(fontWeight = FontWeight.SemiBold),
            color = c.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
        Text(AnalogText.percent(pct.intValue), style = B570Theme.type.caption, color = accent, maxLines = 1, softWrap = false)
    }
}
