package com.b570.pilot.ui.screens.scanner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.theme.B570Theme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * 掃描框覆蓋層：四周壓暗、會呼吸的四角框、上下掃描線、對焦環；
 * 成功時四角框「吸附」到 QR 上並轉綠、彈出勾勾；不是 B570 的 QR 時閃紅抖動。
 * 所有動畫值只在繪製階段讀取；Path / Brush / Stroke 由 drawWithCache 建立。
 *
 * @param windowSide 掃描框邊長
 * @param windowCenterX 掃描框中心距左緣（橫向時置中於安全區，瀏海換邊會跟著移）
 * @param windowCenterY 掃描框中心距頂端
 * @param focusRing 對焦環狀態（點畫面或按右側「對焦」都會播放）
 */
@Composable
internal fun ScanOverlay(
    scan: ScannerController,
    windowSide: Dp,
    windowCenterX: Dp,
    windowCenterY: Dp,
    focusRing: FocusRing,
    onTap: (Offset) -> Unit,
    onPinch: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val pinch by rememberUpdatedState(onPinch)

    val loop = rememberInfiniteTransition(label = "scan")
    val sweep = loop.animateFloat(0f, 2f, infiniteRepeatable(tween(4400, easing = LinearEasing)), label = "sweep")
    val breathe = loop.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    )
    val snap = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }

    val success = scan.success
    LaunchedEffect(success) {
        if (success != null) {
            launch { snap.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
            delay(160)
            launch { burst.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f))
        }
    }
    val rejects = scan.rejectCount
    LaunchedEffect(rejects) {
        if (rejects > 0) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(900))
        }
    }
    val streaming = scan.status == CamStatus.STREAMING

    Spacer(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    scope.launch { focusRing.play(pos) }
                    tap(pos)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) pinch(zoom) }
            }
            .drawWithCache {
                val side = windowSide.toPx()
                val cx = windowCenterX.toPx()
                val cy = windowCenterY.toPx()
                val win = Rect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
                val corner = 22.dp.toPx()
                val scrim = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addRoundRect(RoundRect(win, CornerRadius(corner)))
                }
                val hair = Stroke(1.dp.toPx())
                val bracketStroke = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                val glowStroke = Stroke(12.dp.toPx(), cap = StrokeCap.Round)
                val lineW = 2.dp.toPx()
                val inset = 14.dp.toPx()
                val lineBrush = Brush.horizontalGradient(
                    0f to Color.Transparent, 0.5f to c.accent, 1f to Color.Transparent,
                    startX = win.left + inset, endX = win.right - inset,
                )
                val trailH = side * 0.24f
                val trailBrush = Brush.verticalGradient(
                    0f to Color.Transparent, 1f to c.accent.copy(alpha = 0.22f), startY = -trailH, endY = 0f,
                )
                // 成功目標（讀在快取區：只在成功那一刻重建一次）
                val target: Rect? = scan.success?.let { s -> s.rect?.let { squareAround(it, 72.dp.toPx(), size) } ?: win.deflate(side * 0.1f) }
                val checkR = (target?.minDimension ?: side) * 0.2f
                val checkPath = Path()
                if (target != null) {
                    val tc = target.center
                    checkPath.moveTo(tc.x - checkR * 0.45f, tc.y + checkR * 0.02f)
                    checkPath.lineTo(tc.x - checkR * 0.1f, tc.y + checkR * 0.36f)
                    checkPath.lineTo(tc.x + checkR * 0.48f, tc.y - checkR * 0.32f)
                }
                val checkStroke = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val ring = Stroke(2.dp.toPx())
                val focusStroke = Stroke(1.5.dp.toPx())
                val focusOuter = 44.dp.toPx()
                val focusInner = 30.dp.toPx()
                val dot = 2.dp.toPx()
                val shakeAmp = 7.dp.toPx()
                val breatheAmp = 4.dp.toPx()

                onDrawBehind {
                    drawPath(scrim, Color.Black, alpha = 0.55f)
                    drawRoundRect(Color.White, win.topLeft, win.size, CornerRadius(corner), alpha = 0.10f, style = hair)

                    val sp = snap.value
                    val fl = flash.value
                    val l: Float
                    val t: Float
                    val r: Float
                    val b: Float
                    if (target != null) {
                        l = mix(win.left, target.left, sp)
                        t = mix(win.top, target.top, sp)
                        r = mix(win.right, target.right, sp)
                        b = mix(win.bottom, target.bottom, sp)
                    } else {
                        val g = breathe.value * breatheAmp
                        l = win.left - g
                        t = win.top - g
                        r = win.right + g
                        b = win.bottom + g
                    }
                    val color = lerp(lerp(c.accent, c.danger, fl), c.ok, sp.coerceIn(0f, 1f))
                    val shake = if (fl > 0f) sin(fl * 30f) * shakeAmp * fl else 0f
                    translate(left = shake) {
                        drawBrackets(l, t, r, b, color, glowStroke, 0.22f)
                        drawBrackets(l, t, r, b, color, bracketStroke, 1f)
                    }

                    // 掃描線（尚未成功、相機已出畫面）
                    if (target == null && streaming) {
                        val ph = sweep.value
                        val down = ph < 1f
                        val f = FastOutSlowInEasing.transform(if (down) ph else 2f - ph)
                        val y = win.top + side * (0.08f + 0.84f * f)
                        clipRect(win.left, win.top, win.right, win.bottom) {
                            translate(top = y) {
                                scale(1f, if (down) 1f else -1f, pivot = Offset.Zero) {
                                    drawRect(trailBrush, topLeft = Offset(win.left + inset, -trailH), size = Size(side - inset * 2f, trailH))
                                }
                            }
                            drawLine(lineBrush, Offset(win.left + inset, y), Offset(win.right - inset, y), lineW, StrokeCap.Round)
                        }
                    }

                    // 成功：綠色填色、擴散環、彈出的勾
                    if (target != null) {
                        val s1 = sp.coerceIn(0f, 1f)
                        drawRoundRect(c.ok, Offset(l, t), Size(r - l, b - t), CornerRadius(corner * 0.6f), alpha = 0.12f * s1)
                        val tc = target.center
                        val bu = burst.value
                        if (bu > 0.001f && bu < 0.999f) {
                            drawCircle(c.ok, mix(checkR, max(target.width, target.height), bu), tc, alpha = (1f - bu) * 0.7f, style = ring)
                        }
                        val pp = pop.value
                        if (pp > 0.001f) {
                            scale(pp, pivot = tc) {
                                drawCircle(c.ok, checkR, tc, alpha = 0.95f)
                                drawPath(checkPath, c.onAccent, style = checkStroke)
                            }
                        }
                    }

                    // 點擊對焦環
                    val fp = focusRing.position.value
                    val fa = focusRing.progress.value
                    if (fp.isSpecified && fa < 1f) {
                        val e = FastOutSlowInEasing.transform(min(fa / 0.45f, 1f))
                        val alpha = if (fa < 0.7f) 1f else (1f - fa) / 0.3f
                        drawCircle(Color.White, mix(focusOuter, focusInner, e), fp, alpha = alpha * 0.9f, style = focusStroke)
                        drawCircle(Color.White, dot, fp, alpha = alpha)
                    }
                }
            },
    )
}

/** 點擊對焦環：位置 + 0→1 的動畫進度（只在繪製階段讀取）。 */
@Stable
internal class FocusRing {
    val position = mutableStateOf(Offset.Unspecified)
    val progress = Animatable(1f)

    suspend fun play(at: Offset) {
        position.value = at
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = LinearEasing))
    }
}

/** 以 QR 中心取正方形並加外距，至少 [minSide]，限制在畫面內。 */
private fun squareAround(r: Rect, minSide: Float, bounds: Size): Rect {
    val side = max(max(r.width, r.height) * 1.3f, minSide)
    val cx = r.center.x.coerceIn(side / 2f, max(side / 2f, bounds.width - side / 2f))
    val cy = r.center.y.coerceIn(side / 2f, max(side / 2f, bounds.height - side / 2f))
    return Rect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
}

/** 四個圓角 L 形角框（不配置物件）。 */
private fun DrawScope.drawBrackets(l: Float, t: Float, r: Float, b: Float, color: Color, stroke: Stroke, alpha: Float) {
    val width = stroke.width
    val len = (min(r - l, b - t) * 0.2f).coerceAtLeast(18.dp.toPx())
    val rad = min(14.dp.toPx(), len * 0.55f)
    val d = rad * 2f
    val arc = Size(d, d)
    // 左上
    drawArc(color, 180f, 90f, false, Offset(l, t), arc, alpha = alpha, style = stroke)
    drawLine(color, Offset(l, t + rad), Offset(l, t + len), width, StrokeCap.Round, alpha = alpha)
    drawLine(color, Offset(l + rad, t), Offset(l + len, t), width, StrokeCap.Round, alpha = alpha)
    // 右上
    drawArc(color, 270f, 90f, false, Offset(r - d, t), arc, alpha = alpha, style = stroke)
    drawLine(color, Offset(r, t + rad), Offset(r, t + len), width, StrokeCap.Round, alpha = alpha)
    drawLine(color, Offset(r - rad, t), Offset(r - len, t), width, StrokeCap.Round, alpha = alpha)
    // 右下
    drawArc(color, 0f, 90f, false, Offset(r - d, b - d), arc, alpha = alpha, style = stroke)
    drawLine(color, Offset(r, b - rad), Offset(r, b - len), width, StrokeCap.Round, alpha = alpha)
    drawLine(color, Offset(r - rad, b), Offset(r - len, b), width, StrokeCap.Round, alpha = alpha)
    // 左下
    drawArc(color, 90f, 90f, false, Offset(l, b - d), arc, alpha = alpha, style = stroke)
    drawLine(color, Offset(l, b - rad), Offset(l, b - len), width, StrokeCap.Round, alpha = alpha)
    drawLine(color, Offset(l + rad, b), Offset(l + len, b), width, StrokeCap.Round, alpha = alpha)
}
