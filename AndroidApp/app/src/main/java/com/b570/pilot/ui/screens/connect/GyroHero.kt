package com.b570.pilot.ui.screens.connect

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.theme.B570Theme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 主視覺的情緒：決定顏色與轉速。 */
internal enum class HeroMood { IDLE, CONNECTING, FAILED, CONNECTED }

// 所有週期都是 LOOP 秒的整數分之一 → 時鐘在 LOOP 處折返也不會跳動
private const val LOOP = 120f
private const val TAU = 6.2831855f

/** n 圈 / LOOP 秒 → rad/s。 */
private fun w(n: Int): Float = TAU * n / LOOP

// 三個陀螺環：半徑比例、平面傾角（起始 / 每秒度數 = 3n）、翻轉（起始 rad / 頻率 n）、環上光點（起始 / 頻率 n）、線寬 dp
private val RING_R = floatArrayOf(1.0f, 0.80f, 0.60f)
private val RING_TILT0 = floatArrayOf(-20f, 38f, 100f)
private val RING_TILT_RATE = floatArrayOf(6f, -9f, 15f)
private val RING_FLIP0 = floatArrayOf(0.9f, 0.3f, 1.7f)
private val RING_FLIP_W = floatArrayOf(w(11), w(-15), w(21))
private val RING_BEAD0 = floatArrayOf(0f, 2f, 4f)
private val RING_BEAD_W = floatArrayOf(w(31), w(-41), w(53))
private val RING_WIDTH_DP = floatArrayOf(2.2f, 2.0f, 1.8f)

/** 漂浮粒子（固定種子，畫面每次看起來都一樣）。 */
private class ParticleField(val count: Int, seed: Int) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val speed = FloatArray(count)
    val radiusDp = FloatArray(count)
    val phase = FloatArray(count)
    val twinkle = FloatArray(count)

    init {
        val rnd = java.util.Random(seed.toLong())
        for (i in 0 until count) {
            x[i] = 0.04f + rnd.nextFloat() * 0.92f
            y[i] = rnd.nextFloat()
            speed[i] = (2 + rnd.nextInt(6)) / LOOP          // 每秒移動畫面高度的比例（整數 / LOOP）
            radiusDp[i] = 0.7f + rnd.nextFloat() * 1.4f
            phase[i] = rnd.nextFloat() * TAU
            twinkle[i] = w(20 + rnd.nextInt(40))
        }
    }
}

/** 系統「移除動畫」開啟時，主視覺改為靜態。 */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/**
 * 會呼吸的陀螺儀：三個 3D 翻轉的環、脈動核心、漂浮粒子、HUD 虛線軌道。
 * 只在繪製階段讀取時鐘（不重組），筆刷與 Stroke 在 drawWithCache 內建立；顏色動畫時才重建快取。
 * @param paused true = 停止動畫（例如被掃描覆蓋層蓋住）
 */
@Composable
internal fun GyroHero(mood: HeroMood, paused: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val primary = animateColorAsState(
        when (mood) {
            HeroMood.FAILED -> c.danger
            HeroMood.CONNECTED -> c.ok
            else -> c.accent
        },
        tween(700), label = "gyroPrimary",
    )
    val secondary = animateColorAsState(if (mood == HeroMood.FAILED) c.warn else c.accent2, tween(700), label = "gyroSecondary")
    val speed = animateFloatAsState(
        when (mood) {
            HeroMood.IDLE -> 1f
            HeroMood.CONNECTING -> 2.6f
            HeroMood.FAILED -> 0.35f
            HeroMood.CONNECTED -> 3.4f
        },
        tween(1200, easing = FastOutSlowInEasing), label = "gyroSpeed",
    )
    val energy = animateFloatAsState(
        if (mood == HeroMood.CONNECTING || mood == HeroMood.CONNECTED) 1f else 0f,
        tween(900), label = "gyroEnergy",
    )
    val clock = remember { mutableFloatStateOf(7.5f) }
    val still = rememberReduceMotion()
    LaunchedEffect(paused, still) {
        if (paused || still) return@LaunchedEffect
        var acc = clock.floatValue.toDouble()
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000.0).coerceIn(0.0, 0.05)
                last = now
                acc += dt * speed.value
                if (acc >= LOOP) acc -= LOOP
                clock.floatValue = acc.toFloat()
            }
        }
    }
    val field = remember { ParticleField(36, 570) }

    Spacer(
        modifier.drawWithCache {
            // 讀在快取區：只有顏色動畫期間會重建
            val p = primary.value
            val s = secondary.value
            val r = min(size.width, size.height) * 0.36f
            val ctr = Offset(size.width / 2f, size.height / 2f)
            val aura = Brush.radialGradient(
                0f to p.copy(alpha = 0.30f), 0.45f to p.copy(alpha = 0.10f), 1f to Color.Transparent,
                center = ctr, radius = r * 1.65f,
            )
            val coreR = r * 0.17f
            val coreGlow = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.75f), 0.35f to p.copy(alpha = 0.45f), 1f to Color.Transparent,
                center = ctr, radius = coreR * 3f,
            )
            val coreBody = Brush.radialGradient(
                listOf(Color.White, lerp(p, Color.White, 0.35f), p),
                center = Offset(ctr.x - coreR * 0.35f, ctr.y - coreR * 0.35f), radius = coreR * 1.5f,
            )
            val dash = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx())))
            val back = Array(3) { Stroke(RING_WIDTH_DP[it].dp.toPx() * 0.7f, cap = StrokeCap.Round) }
            val front = Array(3) { Stroke(RING_WIDTH_DP[it].dp.toPx(), cap = StrokeCap.Round) }
            val frontGlow = Stroke(7.dp.toPx(), cap = StrokeCap.Round)
            val ringColor = arrayOf(p, lerp(p, s, 0.5f), s)
            val particleR = FloatArray(field.count) { field.radiusDp[it].dp.toPx() }
            val bead = 2.6.dp.toPx()
            val tick = 6.dp.toPx()
            val axisW = 1.dp.toPx()
            val hud = r * 1.3f

            onDrawBehind {
                val t = clock.floatValue
                val e = energy.value

                // 1) 光暈（呼吸）
                scale(1f + 0.05f * sin(w(20) * t) + 0.08f * e, pivot = ctr) {
                    drawCircle(aura, radius = r * 1.65f, center = ctr, alpha = 0.8f + 0.2f * e)
                }

                // 2) HUD 虛線軌道 + 刻度
                rotate(t * 3f, pivot = ctr) {
                    drawCircle(p, radius = hud, center = ctr, alpha = 0.22f, style = dash)
                    for (k in 0 until 4) {
                        rotate(k * 90f, pivot = ctr) {
                            drawLine(p, Offset(ctr.x, ctr.y - hud - tick * 0.5f), Offset(ctr.x, ctr.y - hud + tick * 0.5f), axisW * 1.6f, StrokeCap.Round, alpha = 0.55f)
                        }
                    }
                }

                // 3) 漂浮粒子（由下往上）
                val h = size.height
                for (i in 0 until field.count) {
                    val yy = field.y[i] - t * field.speed[i]
                    val fy = yy - floor(yy)
                    val edge = (min(fy, 1f - fy) * 5f).coerceIn(0f, 1f)
                    val a = (0.18f + 0.5f * (0.5f + 0.5f * sin(t * field.twinkle[i] + field.phase[i]))) * edge
                    val px = (field.x[i] + 0.015f * sin(t * w(9) + field.phase[i])) * size.width
                    drawCircle(if (i % 4 == 0) s else p, particleR[i], Offset(px, fy * h), alpha = a)
                }

                // 4) 旋轉軸（微幅進動）
                rotate(8f * sin(w(14) * t), pivot = ctr) {
                    val top = Offset(ctr.x, ctr.y - r * 1.18f)
                    val bottom = Offset(ctr.x, ctr.y + r * 1.18f)
                    drawLine(p, top, bottom, axisW, StrokeCap.Round, alpha = 0.28f)
                    drawCircle(p, axisW * 2.2f, top, alpha = 0.6f)
                    drawCircle(p, axisW * 2.2f, bottom, alpha = 0.6f)
                }

                // 5) 環的後半 + 後方光點（在核心之後畫 → 有前後層次）
                for (i in 0 until 3) {
                    val k = cos(RING_FLIP0[i] + RING_FLIP_W[i] * t)
                    val rr = r * RING_R[i]
                    val hh = rr * max(abs(k), 0.07f)
                    val start = if (k >= 0f) 180f else 0f
                    rotate(RING_TILT0[i] + RING_TILT_RATE[i] * t, pivot = ctr) {
                        drawArc(
                            ringColor[i], start, 180f, false,
                            topLeft = Offset(ctr.x - rr, ctr.y - hh), size = Size(rr * 2f, hh * 2f),
                            alpha = 0.26f, style = back[i],
                        )
                        val a = RING_BEAD0[i] + RING_BEAD_W[i] * t
                        if (sin(a) <= 0f) {
                            drawCircle(ringColor[i], bead * 0.75f, Offset(ctr.x + rr * cos(a), ctr.y + rr * k * sin(a)), alpha = 0.45f)
                        }
                    }
                }

                // 6) 核心
                scale(1f + 0.07f * sin(w(60) * t) + 0.12f * e, pivot = ctr) {
                    drawCircle(coreGlow, radius = coreR * 3f, center = ctr)
                    drawCircle(coreBody, radius = coreR, center = ctr)
                    drawCircle(Color.White, radius = coreR * 0.3f, center = ctr, alpha = 0.9f)
                }

                // 7) 環的前半（柔光 + 亮線）+ 前方光點
                for (i in 0 until 3) {
                    val k = cos(RING_FLIP0[i] + RING_FLIP_W[i] * t)
                    val rr = r * RING_R[i]
                    val hh = rr * max(abs(k), 0.07f)
                    val start = if (k >= 0f) 0f else 180f
                    val tl = Offset(ctr.x - rr, ctr.y - hh)
                    val sz = Size(rr * 2f, hh * 2f)
                    rotate(RING_TILT0[i] + RING_TILT_RATE[i] * t, pivot = ctr) {
                        drawArc(ringColor[i], start, 180f, false, topLeft = tl, size = sz, alpha = 0.12f + 0.08f * e, style = frontGlow)
                        drawArc(ringColor[i], start, 180f, false, topLeft = tl, size = sz, alpha = 0.95f, style = front[i])
                        val a = RING_BEAD0[i] + RING_BEAD_W[i] * t
                        if (sin(a) > 0f) {
                            val bp = Offset(ctr.x + rr * cos(a), ctr.y + rr * k * sin(a))
                            drawCircle(ringColor[i], bead * 3f, bp, alpha = 0.22f)
                            drawCircle(Color.White, bead, bp, alpha = 0.95f)
                        }
                    }
                }
            }
        },
    )
}
