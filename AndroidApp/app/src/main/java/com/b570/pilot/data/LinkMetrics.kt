package com.b570.pilot.data

import kotlin.math.ceil

/**
 * 控制延遲樣本的統計（純 Kotlin，非執行緒安全：呼叫端持有鎖）。
 * - [ema]：指數平滑（每個樣本 α = [alpha]，50 Hz 時約 0.4 s 時間常數）；最近 [staleMs] 沒有樣本回傳 null
 * - [p95]：最近 [windowMs] 內樣本的第 95 百分位（nearest-rank）；少於 [minSamples] 個回傳 null
 */
class LatencyWindow(
    private val capacity: Int = 512,
    val windowMs: Long = 5_000L,
    private val alpha: Float = 0.05f,
    private val staleMs: Long = 3_000L,
    private val minSamples: Int = 5,
) {
    private val times = LongArray(capacity)
    private val values = FloatArray(capacity)
    private var head = 0
    private var count = 0
    private var emaValue = Float.NaN
    private var lastAtMs = Long.MIN_VALUE
    private var scratch = FloatArray(capacity)

    fun add(ms: Float, atMs: Long) {
        if (ms.isNaN() || ms.isInfinite() || ms < 0f) return
        times[head] = atMs
        values[head] = ms
        head = (head + 1) % capacity
        if (count < capacity) count++
        emaValue = if (emaValue.isNaN()) ms else emaValue + alpha * (ms - emaValue)
        lastAtMs = atMs
    }

    /** 平滑後的延遲；沒有樣本或最近 [staleMs] 沒有新樣本 = null。 */
    fun ema(nowMs: Long): Float? = if (emaValue.isNaN() || nowMs - lastAtMs > staleMs) null else emaValue

    /** 最近 [windowMs] 的 P95；樣本不足 = null。 */
    fun p95(nowMs: Long): Float? = percentile(nowMs, 0.95f)

    fun percentile(nowMs: Long, q: Float): Float? {
        var n = 0
        for (i in 0 until count) {
            val idx = (head - 1 - i + capacity) % capacity
            if (nowMs - times[idx] > windowMs) break // 由新到舊，之後都更舊
            scratch[n++] = values[idx]
        }
        if (n < minSamples) return null
        java.util.Arrays.sort(scratch, 0, n)
        val rank = ceil(q.coerceIn(0f, 1f) * n).toInt().coerceIn(1, n)
        return scratch[rank - 1]
    }

    /** 最近 [windowMs] 內的樣本數。 */
    fun samplesWithin(nowMs: Long): Int {
        var n = 0
        for (i in 0 until count) {
            val idx = (head - 1 - i + capacity) % capacity
            if (nowMs - times[idx] > windowMs) break
            n++
        }
        return n
    }

    fun reset() {
        head = 0; count = 0; emaValue = Float.NaN; lastAtMs = Long.MIN_VALUE
    }
}

/**
 * 上行遺失率：最近 [maxSamples] 個樣本（主控板每秒結算一次 link_q → 每秒一個 → 約 5 s）的
 * `1 − Σ車上收到 / Σ手機送出`（%，夾在 0..100）。少於 [minSamples] 個或沒送任何幀 → null。
 * 每個樣本的「手機送出」要對齊主控板那 1 秒（CarConnection 用 LinkQSettleTracker 推算），否則送出速率改變時會算出假遺失。
 * 非執行緒安全（呼叫端持有鎖）。
 */
class UplinkLossEstimator(private val maxSamples: Int = 5, private val minSamples: Int = 2) {
    private val sent = IntArray(maxSamples)
    private val received = IntArray(maxSamples)
    private var head = 0
    private var count = 0

    fun add(sentFrames: Int, receivedFrames: Int) {
        if (sentFrames < 0 || receivedFrames < 0) return
        sent[head] = sentFrames
        received[head] = receivedFrames
        head = (head + 1) % maxSamples
        if (count < maxSamples) count++
    }

    fun percent(): Float? {
        if (count < minSamples) return null
        var s = 0L
        var r = 0L
        for (i in 0 until count) { s += sent[i]; r += received[i] }
        if (s <= 0L) return null
        return ((1.0 - r.toDouble() / s) * 100.0).coerceIn(0.0, 100.0).toFloat()
    }

    fun reset() {
        head = 0; count = 0
    }
}
