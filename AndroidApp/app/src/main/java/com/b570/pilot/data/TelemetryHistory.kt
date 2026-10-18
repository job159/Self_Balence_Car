package com.b570.pilot.data

import com.b570.pilot.protocol.Telemetry
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 遙測歷史環形緩衝（預設 3000 筆 = 50 Hz × 60 s）。
 *
 * - 寫入：接收執行緒每收到一筆 TELEM 呼叫 [add]。
 * - 讀取：UI 以 [version] 判斷是否有新資料，再用 [copyLast] 複製到自己預先配置的 FloatArray（零配置）。
 * 執行緒安全（內部鎖，臨界區只有陣列複製）。
 */
class TelemetryHistory(val capacity: Int = 3000) {

    /** 可記錄的通道。[labelZh] / [unit] 給圖表圖例用。 */
    enum class Channel(val labelZh: String, val unit: String) {
        PITCH("傾角", "°"),
        ROLL("橫滾", "°"),
        GYRO_BAL("俯仰角速度", "°/s"),
        GYRO_TURN("偏航角速度", "°/s"),
        VEL_L("左輪速度", "mm/s"),
        VEL_R("右輪速度", "mm/s"),
        PWM_L("左馬達 PWM", ""),
        PWM_R("右馬達 PWM", ""),
        PWM_BAL("直立環輸出", ""),
        PWM_VEL("速度環輸出", ""),
        PWM_TURN("轉向環輸出", ""),
        TGT_SPEED("目標速度", "mm/s"),
        TGT_TURN("目標轉向", "‰"),
        BATTERY("電池電壓", "V"),
        HEADING("航向", "°"),
    }

    private val lock = Any()
    private val data = Array(Channel.entries.size) { FloatArray(capacity) }
    private val times = LongArray(capacity)
    private var head = 0 // 下一個寫入位置
    private var count = 0
    private val _version = AtomicLong(0)

    /** 每寫入一筆 +1（清除時也 +1）。UI 比對此值決定是否重畫。 */
    val version: Long get() = _version.get()

    /** 目前筆數（≤ capacity）。 */
    val size: Int get() = synchronized(lock) { count }

    fun add(t: Telemetry, timeMs: Long) {
        synchronized(lock) {
            val i = head
            data[0][i] = t.pitchDeg
            data[1][i] = t.rollDeg
            data[2][i] = t.gyroBalDps
            data[3][i] = t.gyroTurnDps
            data[4][i] = t.velL.toFloat()
            data[5][i] = t.velR.toFloat()
            data[6][i] = t.pwmL.toFloat()
            data[7][i] = t.pwmR.toFloat()
            data[8][i] = t.pwmBal.toFloat()
            data[9][i] = t.pwmVel.toFloat()
            data[10][i] = t.pwmTurn.toFloat()
            data[11][i] = t.targetSpeed.toFloat()
            data[12][i] = t.targetTurn.toFloat()
            data[13][i] = t.batteryV
            data[14][i] = t.headingDeg
            times[i] = timeMs
            head = (i + 1) % capacity
            if (count < capacity) count++
        }
        _version.incrementAndGet()
    }

    fun clear() {
        synchronized(lock) { head = 0; count = 0 }
        _version.incrementAndGet()
    }

    /**
     * 把 [channel] 最近 [n] 筆（由舊到新）複製到 out[outOffset ..]。零配置。
     * @return 實際複製筆數（資料不足時 < n）
     */
    fun copyLast(channel: Channel, n: Int, out: FloatArray, outOffset: Int = 0): Int =
        synchronized(lock) { copyRing(data[channel.ordinal], n, out, outOffset) }

    /** 最近 [n] 筆的時間戳（System.currentTimeMillis，由舊到新）。 */
    fun copyLastTimes(n: Int, out: LongArray, outOffset: Int = 0): Int = synchronized(lock) {
        val m = minOf(n, count, out.size - outOffset)
        if (m <= 0) return 0
        val start = (head - m + capacity) % capacity
        val first = minOf(m, capacity - start)
        System.arraycopy(times, start, out, outOffset, first)
        if (m > first) System.arraycopy(times, 0, out, outOffset + first, m - first)
        m
    }

    /** 最新一筆的值；沒有資料回傳 NaN。 */
    fun latest(channel: Channel): Float = synchronized(lock) {
        if (count == 0) Float.NaN else data[channel.ordinal][(head - 1 + capacity) % capacity]
    }

    /**
     * 最近 [n] 筆的最小/最大值寫入 outMinMax[0], [1]。沒有資料時寫入 NaN。零配置。
     */
    fun minMax(channel: Channel, n: Int, outMinMax: FloatArray) {
        synchronized(lock) {
            val m = minOf(n, count)
            if (m <= 0) { outMinMax[0] = Float.NaN; outMinMax[1] = Float.NaN; return }
            val arr = data[channel.ordinal]
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            var idx = (head - m + capacity) % capacity
            repeat(m) {
                val v = arr[idx]
                if (v < lo) lo = v
                if (v > hi) hi = v
                idx++; if (idx == capacity) idx = 0
            }
            outMinMax[0] = lo; outMinMax[1] = hi
        }
    }

    private fun copyRing(src: FloatArray, n: Int, out: FloatArray, outOffset: Int): Int {
        val m = minOf(n, count, out.size - outOffset)
        if (m <= 0) return 0
        val start = (head - m + capacity) % capacity
        val first = minOf(m, capacity - start)
        System.arraycopy(src, start, out, outOffset, first)
        if (m > first) System.arraycopy(src, 0, out, outOffset + first, m - first)
        return m
    }

    /** 匯出 CSV（UTF-8，含表頭，時間為相對秒數）。 */
    fun exportCsv(writer: Writer) {
        val n: Int
        val snapshot: Array<FloatArray>
        val ts: LongArray
        synchronized(lock) {
            n = count
            ts = LongArray(n)
            snapshot = Array(data.size) { FloatArray(n) }
            copyLastTimesLocked(n, ts)
            for (c in data.indices) copyRing(data[c], n, snapshot[c], 0)
        }
        writer.append("time_s")
        Channel.entries.forEach { writer.append(',').append(it.name.lowercase(Locale.US)) }
        writer.append('\n')
        val t0 = if (n > 0) ts[0] else 0L
        for (i in 0 until n) {
            writer.append(String.format(Locale.US, "%.3f", (ts[i] - t0) / 1000.0))
            for (c in snapshot.indices) writer.append(',').append(String.format(Locale.US, "%.3f", snapshot[c][i]))
            writer.append('\n')
        }
        writer.flush()
    }

    fun exportCsv(out: OutputStream) = exportCsv(OutputStreamWriter(out, Charsets.UTF_8))

    private fun copyLastTimesLocked(n: Int, out: LongArray) {
        val m = minOf(n, count)
        if (m <= 0) return
        val start = (head - m + capacity) % capacity
        val first = minOf(m, capacity - start)
        System.arraycopy(times, start, out, 0, first)
        if (m > first) System.arraycopy(times, 0, out, first, m - first)
    }
}
