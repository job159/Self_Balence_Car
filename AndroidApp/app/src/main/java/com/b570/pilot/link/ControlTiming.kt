package com.b570.pilot.link

import com.b570.pilot.protocol.Telemetry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

/**
 * 控制延遲 / 上行遺失的量測（純 Kotlin，可單元測試）。傳送執行緒寫、接收執行緒讀，全部無鎖、零配置。
 *
 * **控制延遲**（§4.1 ctrl_seq / ctrl_age）：每個 SEQ（0..255）記住「送出 SEQ = s 的 CTRL 的時間」。
 * 收到 TELEM(ctrl_seq = s, ctrl_age = a) 時：
 * `延遲 = (收到這筆遙測的時間 − 送出 s 的時間) − max(a − 1, 0) × 10 ms`
 * = 手機 → ESP → UART → 主控板真正套用（含等下一個 10 ms 控制週期），再加上這筆遙測的下行時間（約 3–8 ms）—— 是「控制延遲」的上限。
 * 為什麼是 a − 1：主控板記的是「收到時的 tick」，CTRL 在**下一個**控制週期才套用，同一個週期送出的遙測 a = 1
 * （韌體實測：中斷間收到、或控制週期開頭輪詢 DMA 收到，都是 a = 1）。直接減 a × 10 會把每個樣本少算 10 ms。
 * SEQ 每 256 幀才會重複（50 Hz CTRL + 其他幀 ≈ 4.8 s），ctrl_age 最大 2.55 s，所以不會對錯幀；
 * 每次分配 SEQ 都先清掉那一格（[onSeqAllocated]），非 CTRL 的幀不會被當成 CTRL。
 *
 * **上行遺失**：主控板 TELEM.link_q = 「最近 1 秒收到的 App 幀數」（TYPE < 0x40，每秒結算一次）。
 * 對照手機**同一段時間**送出的同類幀數（[carFramesForSettle]，結算時刻由 [LinkQSettleTracker] 推算）就是上行（手機 → 主控板）遺失率。
 * （不用「沒被回報的 ctrl_seq」：CTRL 與遙測都是 50 Hz，兩個 CTRL 落在同一個遙測間隔時前一個會被蓋掉，
 * 實際沒掉也會被算成遺失。）
 */
class ControlTiming {

    /** SEQ → 送出那個 CTRL 的 System.nanoTime()；0 = 這個 SEQ 目前不是 CTRL。 */
    private val ctrlSentNs = AtomicLongArray(256)

    /** 往主控板（TYPE < 0x40）的幀送出時間，環形。 */
    private val carSentNs = AtomicLongArray(CAR_RING)
    private val carSentIdx = AtomicInteger(0)

    /** 每次分配 SEQ（任何 TYPE）都呼叫：清掉這一格，舊的 CTRL 時間不會被後來的非 CTRL 幀沿用。 */
    fun onSeqAllocated(seq: Int) {
        ctrlSentNs.set(seq and 0xFF, 0L)
    }

    /** CTRL 送出（呼叫 socket.send 之前一刻）。 */
    fun onCtrlSent(seq: Int, nowNs: Long) {
        ctrlSentNs.set(seq and 0xFF, if (nowNs == 0L) 1L else nowNs)
    }

    /** 任何往主控板（TYPE 0x01..0x3F）的幀送出。 */
    fun onCarFrameSent(nowNs: Long) {
        carSentNs.set(carSentIdx.getAndIncrement() and (CAR_RING - 1), if (nowNs == 0L) 1L else nowNs)
    }

    /** (sinceNs, nowNs] 之間送出的主控板幀數。 */
    fun carFramesSentSince(sinceNs: Long, nowNs: Long): Int {
        var n = 0
        for (i in 0 until CAR_RING) {
            val t = carSentNs.get(i)
            if (t != 0L && t - sinceNs > 0 && nowNs - t >= 0) n++
        }
        return n
    }

    /** 送出 SEQ = [seq] 的 CTRL 的時間；不是 CTRL / 沒記錄回傳 0。 */
    fun ctrlSentNs(seq: Int): Long = ctrlSentNs.get(seq and 0xFF)

    /** 收到 TELEM(ctrl_seq, ctrl_age) 時算一個控制延遲樣本 ms；無效回傳 NaN（不配置）。 */
    fun latencyMs(ctrlSeq: Int, ctrlAge: Int, arrivalNs: Long): Float =
        computeLatencyMs(ctrlSentNs(ctrlSeq), arrivalNs, ctrlAge)

    fun reset() {
        for (i in 0 until 256) ctrlSentNs.set(i, 0L)
        for (i in 0 until CAR_RING) carSentNs.set(i, 0L)
        carSentIdx.set(0)
    }

    /**
     * 上行樣本的手機端時間窗：主控板在 [settleAgeTicks] 個 10 ms 週期之前結算了 link_q（前 1 秒收到的幀數）；
     * 那 1 秒的幀是手機在 `[結算 − 1 s, 結算]` 再往前一個 [delayNs]（上行 + 下行延遲）送出的。回傳那段時間送出的主控板幀數。
     */
    fun carFramesForSettle(arrivalNs: Long, settleAgeTicks: Int, delayNs: Long): Int {
        val endNs = arrivalNs - settleAgeTicks * TICK_NS - delayNs.coerceIn(0L, MAX_VALID_NS)
        return carFramesSentSince(endNs - 1_000_000_000L, endNs)
    }

    companion object {
        /** 主控板控制週期（TELEM.tick、ctrl_age 的單位）。 */
        const val TICK_NS = 10_000_000L

        /** 往主控板的幀送出時間保留幾筆（50 Hz CTRL + PING + 請求，1 秒內遠少於這個數）。 */
        const val CAR_RING = 256

        /** 送出到收到超過這麼久的樣本不採用（主控板早就失聯保護了，也避免 SEQ 重複造成誤配）。 */
        const val MAX_VALID_NS = 2_000_000_000L

        /**
         * 控制延遲 ms = (arrival − sent) − [appliedTicksAgo]（ctrlAge）× 10 ms（夾在 ≥ 0）。
         * 無效（沒送過這個 SEQ、時間倒退、超過 [MAX_VALID_NS]、ctrl_age 飽和 255）回傳 NaN。
         */
        fun computeLatencyMs(sentNs: Long, arrivalNs: Long, ctrlAge: Int): Float {
            if (sentNs == 0L || ctrlAge < 0 || ctrlAge >= Telemetry.CTRL_AGE_SATURATED) return Float.NaN
            val dt = arrivalNs - sentNs
            if (dt <= 0L || dt > MAX_VALID_NS) return Float.NaN
            val ms = dt / 1_000_000f - appliedTicksAgo(ctrlAge) * Telemetry.CTRL_AGE_UNIT_MS
            return if (ms < 0f) 0f else ms
        }

        /**
         * ctrl_age（§4.1：收到那個 CTRL 之後經過的控制週期數）→ 這筆遙測送出時，那個 CTRL 已經**套用**了幾個週期。
         * 收到後的下一個週期才套用，所以是 ctrl_age − 1；ctrl_age = 0（同一週期剛收到、下一週期才套用，極少見）當 0。
         */
        fun appliedTicksAgo(ctrlAge: Int): Int = if (ctrlAge > 1) ctrlAge - 1 else 0
    }
}

/**
 * 主控板 link_q 的結算時刻（韌體在控制週期 `ticks % 100 == 0` 時結算「前 1 秒收到的 App 幀數」）。純 Kotlin、只在接收執行緒用。
 *
 * 為什麼要對齊：手機送出速率會變（[ControlPacer]：靜止 10 Hz、操作中 50 Hz）。拿「收到遙測前 1 秒」去比
 * 「主控板最多 1 秒前結算的那 1 秒」，一開始操作就會把還沒結算的幀算成遺失（實測假遺失 10 % 以上）、
 * 連線品質也會在靜止時掉到 20 % 多。對齊到同一段時間後，速率怎麼變都不影響。
 *
 * 遙測只帶 u16 tick，看不出 ticks % 100：用 link_q **改變**的那一刻學相位（結算在上一筆與這一筆遙測之間），
 * 之後每 100 tick 推算一次（link_q 沒變也有樣本；u16 加 100 = u32 ticks 加 100，溢位也準）。
 * tick 倒退（主控板重開機）→ 重新學。
 */
class LinkQSettleTracker {
    private var lastTick = -1
    private var lastQ = -1
    /** 下一次結算的 tick（u16）；-1 = 還不知道相位。 */
    private var nextSettle = -1

    /**
     * 每筆遙測呼叫（tick = TELEM.tick、linkQ = TELEM.link_q）。
     * @return 這筆遙測離「剛發生的那次結算」幾個 tick（0..99）；上一筆之後沒有結算 / 還不知道相位 → -1
     */
    fun onTelemetry(tick: Int, linkQ: Int): Int {
        val t = tick and 0xFFFF
        val prevTick = lastTick
        val prevQ = lastQ
        lastTick = t
        lastQ = linkQ
        if (prevTick < 0) return -1
        val d = (t - prevTick) and 0xFFFF
        if (d == 0) return -1
        if (d >= 0x8000) { // 倒退：主控板重開機（ticks 從 0 開始）
            nextSettle = -1
            return -1
        }
        if (linkQ != prevQ && d <= PERIOD) {
            // 結算發生在 (prevTick, t]：取中間（50 Hz 遙測誤差 ±10 ms）
            val age = d / 2
            nextSettle = (t - age + PERIOD) and 0xFFFF
            return age
        }
        val ns = nextSettle
        if (ns < 0) return -1
        val since = s16(t - ns)
        if (since < 0) return -1
        val missed = since / PERIOD
        nextSettle = (ns + (missed + 1) * PERIOD) and 0xFFFF
        return since - missed * PERIOD
    }

    fun reset() {
        lastTick = -1
        lastQ = -1
        nextSettle = -1
    }

    companion object {
        /** 結算週期（tick）。 */
        const val PERIOD = 100

        private fun s16(v: Int): Int = (v shl 16) shr 16
    }
}
