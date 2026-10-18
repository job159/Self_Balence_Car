package com.b570.pilot.ui.screens.choreo

import androidx.compose.runtime.Immutable
import com.b570.pilot.protocol.Params
import com.b570.pilot.protocol.SeqMode
import com.b570.pilot.protocol.SeqStep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 模擬用的車子限制（取自車上參數，未知時用預設）。 */
@Immutable
internal data class SimLimits(val accel: Float, val turnAccel: Float, val maxSpeed: Float) {
    companion object {
        private val DEFAULTS = Params.defaults()

        private fun pick(params: Map<Int, Float>, id: Int, fallback: Float) = params[id] ?: DEFAULTS[id] ?: fallback

        fun from(params: Map<Int, Float>) = SimLimits(
            accel = pick(params, Params.ACCEL, 1200f).coerceAtLeast(50f),
            turnAccel = pick(params, Params.TURN_ACCEL, 6000f).coerceAtLeast(100f),
            maxSpeed = pick(params, Params.MAX_SPEED, 500f).coerceIn(50f, 1000f),
        )

        val DEFAULT = from(emptyMap())
    }
}

/**
 * 編排的 2D 軌跡（俯視，起點 = 原點、起始方向朝上）。
 * 角度順時針為正（與轉向 ‰ 正 = 右轉一致）。陣列建立後不再修改。
 */
@Immutable
internal class SeqTrajectory(
    /** 位置 mm。 */
    val xs: FloatArray,
    val ys: FloatArray,
    /** 累積航向 rad（不取模，方便插值）。 */
    val hs: FloatArray,
    /** 時間 s（遞增）。 */
    val ts: FloatArray,
    /** 每個點所屬的步驟索引（點 i-1 → i 的線段屬於 stepAt[i]）。 */
    val stepAt: IntArray,
    val size: Int,
    val stepCount: Int,
    /** 單趟時間 s。 */
    val loopSec: Float,
    /** 模擬了幾趟（無限循環時為 2）。 */
    val simLoops: Int,
    /** 單趟內每一步的開始時間 s（長度 = stepCount + 1，最後一個 = loopSec）。 */
    val stepStartSec: FloatArray,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    /** 單趟移動距離 mm。 */
    val loopDistanceMm: Float,
) {
    val totalSec: Float get() = if (size > 0) ts[size - 1] else 0f
    val spanMm: Float get() = max(maxX - minX, maxY - minY)

    /** 最後一個 ts ≤ t 的索引（二分搜尋，零配置）。 */
    fun indexAt(t: Float): Int {
        if (size <= 1 || t <= ts[0]) return 0
        if (t >= ts[size - 1]) return size - 1
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] <= t) lo = mid else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY = SeqTrajectory(
            FloatArray(1), FloatArray(1), FloatArray(1), FloatArray(1), IntArray(1), 1, 0, 0f, 1,
            FloatArray(1), 0f, 0f, 0f, 0f, 0f,
        )
    }
}

private const val BASE_DT = 0.02f
private const val MAX_ITER = 12_000
private const val MAX_SIM_SEC = 240f
private const val YAW_RAD_FULL = (YAW_DPS_FULL / 180f * PI).toFloat()

/**
 * 依韌體規則（§3.2：HOLD / SINE_TURN / SINE_SPEED / RAMP，再經 ACCEL、TURN_ACCEL 限制）
 * 積分出軌跡。假設轉向 1000‰ = 180°/s。計算量有上限（約 1.2 萬次迭代），可在組合期間呼叫。
 * @param loops 0 = 無限（預覽 2 趟）
 * @param maxPoints 記錄點上限（另加每步邊界點）
 */
internal fun simulate(steps: List<SeqStep>, loops: Int, limits: SimLimits, maxPoints: Int = 900): SeqTrajectory {
    if (steps.isEmpty()) return SeqTrajectory.EMPTY
    val list = steps.map { it.clamped() }
    val n = list.size
    val stepStart = FloatArray(n + 1)
    for (i in 0 until n) stepStart[i + 1] = stepStart[i] + list[i].durMs / 1000f
    val loopSec = stepStart[n].coerceAtLeast(0.02f)
    val wanted = if (loops <= 0) 2 else loops.coerceAtMost(10)
    val simLoops = wanted.coerceAtMost(max(1, (MAX_SIM_SEC / loopSec).toInt()))
    val dt = max(BASE_DT, loopSec * simLoops / MAX_ITER)

    var iters = 0
    for (st in list) iters += kotlin.math.ceil(st.durMs / 1000f / dt).toInt().coerceAtLeast(1)
    iters *= simLoops
    val stride = max(1, iters / maxPoints.coerceAtLeast(16))
    val cap = iters / stride + n * simLoops + 4

    val xs = FloatArray(cap)
    val ys = FloatArray(cap)
    val hs = FloatArray(cap)
    val ts = FloatArray(cap)
    val sa = IntArray(cap)
    var count = 0

    var x = 0f
    var y = 0f
    var h = 0f
    var t = 0f
    var curS = 0f
    var curT = 0f
    var fromS = 0f
    var fromT = 0f
    var minX = 0f
    var maxX = 0f
    var minY = 0f
    var maxY = 0f
    var dist = 0f
    var iter = 0
    var lastRecIter = -1

    fun record(step: Int) {
        if (count >= cap) return
        xs[count] = x; ys[count] = y; hs[count] = h; ts[count] = t; sa[count] = step
        count++
        lastRecIter = iter
    }
    record(0)

    val maxS = limits.maxSpeed
    for (loop in 0 until simLoops) {
        for (i in 0 until n) {
            val st = list[i]
            val d = st.durMs / 1000f
            val w = 2f * PI.toFloat() / (st.periodMs / 1000f)
            var el = 0f
            while (el < d - 1e-6f) {
                val hdt = min(dt, d - el)
                val mid = el + hdt * 0.5f
                el += hdt
                var ws: Float
                val wt: Float
                when (st.mode) {
                    SeqMode.HOLD -> { ws = st.speed.toFloat(); wt = st.turn.toFloat() }
                    SeqMode.SINE_TURN -> { ws = st.speed.toFloat(); wt = st.turn * sin(w * mid) }
                    SeqMode.SINE_SPEED -> { ws = st.speed * sin(w * mid); wt = st.turn.toFloat() }
                    SeqMode.RAMP -> {
                        val f = (mid / d).coerceIn(0f, 1f)
                        ws = fromS + (st.speed - fromS) * f
                        wt = fromT + (st.turn - fromT) * f
                    }
                }
                ws = ws.coerceIn(-maxS, maxS)
                curS = approach(curS, ws, limits.accel * hdt)
                curT = approach(curT, wt, limits.turnAccel * hdt)
                h += curT / 1000f * YAW_RAD_FULL * hdt
                x += curS * sin(h) * hdt
                y += curS * cos(h) * hdt
                t += hdt
                if (loop == 0) dist += abs(curS) * hdt
                if (x < minX) minX = x else if (x > maxX) maxX = x
                if (y < minY) minY = y else if (y > maxY) maxY = y
                iter++
                if (iter % stride == 0) record(i)
            }
            // 步驟結束值 = 下一個 RAMP 的起點（同韌體）
            when (st.mode) {
                SeqMode.HOLD, SeqMode.RAMP -> { fromS = st.speed.toFloat(); fromT = st.turn.toFloat() }
                SeqMode.SINE_TURN -> { fromS = st.speed.toFloat(); fromT = st.turn * sin(w * d) }
                SeqMode.SINE_SPEED -> { fromS = st.speed * sin(w * d); fromT = st.turn.toFloat() }
            }
            if (lastRecIter != iter) record(i)
        }
    }
    return SeqTrajectory(xs, ys, hs, ts, sa, count, n, loopSec, simLoops, stepStart, minX, maxX, minY, maxY, dist)
}

private fun approach(cur: Float, want: Float, maxDelta: Float): Float {
    val d = want - cur
    return when {
        d > maxDelta -> cur + maxDelta
        d < -maxDelta -> cur - maxDelta
        else -> want
    }
}
