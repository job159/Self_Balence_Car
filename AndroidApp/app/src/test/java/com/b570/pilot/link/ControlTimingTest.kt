package com.b570.pilot.link

import com.b570.pilot.data.UplinkLossEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 控制延遲（ctrl_seq / ctrl_age）與上行幀數的量測。 */
class ControlTimingTest {

    private val ms = 1_000_000L

    @Test fun latencyIsArrivalMinusSentMinusTicksSinceApplied() {
        // 主控板記「收到時的 tick」、下一個控制週期才套用：套用那一週期送出的遙測 ctrl_age = 1（已套用 0 個週期）
        assertEquals(45f, ControlTiming.computeLatencyMs(1_000 * ms, 1_045 * ms, 1), 1e-3f)
        // ctrl_age = 2：遙測是套用後的下一個週期送的 → 扣 10 ms
        assertEquals(35f, ControlTiming.computeLatencyMs(1_000 * ms, 1_045 * ms, 2), 1e-3f)
        // ctrl_age = 0：同一週期剛收到、還沒套用（極少見）→ 不扣
        assertEquals(45f, ControlTiming.computeLatencyMs(1_000 * ms, 1_045 * ms, 0), 1e-3f)
        // 量化誤差造成的負值夾成 0
        assertEquals(0f, ControlTiming.computeLatencyMs(1_000 * ms, 1_008 * ms, 3), 0f)
    }

    @Test fun ticksSinceAppliedMatchesFirmware() {
        // 韌體（rc_link.c / control.c）：ctrl_tick = 收到時的 ticks；Motion_Tick 先 ticks++ 再套用；同一中斷內接著送遙測
        assertEquals(0, ControlTiming.appliedTicksAgo(0))
        assertEquals(0, ControlTiming.appliedTicksAgo(1))
        assertEquals(1, ControlTiming.appliedTicksAgo(2))
        assertEquals(9, ControlTiming.appliedTicksAgo(10))
    }

    @Test fun invalidSamplesAreNaN() {
        assertTrue(ControlTiming.computeLatencyMs(0L, 1_000 * ms, 0).isNaN()) // 沒送過這個 SEQ
        assertTrue(ControlTiming.computeLatencyMs(1_000 * ms, 900 * ms, 0).isNaN()) // 時間倒退
        assertTrue(ControlTiming.computeLatencyMs(1_000 * ms, 3_100 * ms, 0).isNaN()) // 超過 2 s
        assertTrue(ControlTiming.computeLatencyMs(1_000 * ms, 1_030 * ms, 255).isNaN()) // ctrl_age 飽和 / 還沒收過
        assertTrue(ControlTiming.computeLatencyMs(1_000 * ms, 1_030 * ms, -1).isNaN())
    }

    @Test fun trackerMatchesBySeq() {
        val t = ControlTiming()
        t.onSeqAllocated(7)
        t.onCtrlSent(7, 5_000 * ms)
        t.onSeqAllocated(8) // 例如 PING：不是 CTRL
        t.onSeqAllocated(9)
        t.onCtrlSent(9, 5_020 * ms)
        assertEquals(18f, t.latencyMs(7, 2, 5_028 * ms), 1e-3f)
        assertEquals(8f, t.latencyMs(9, 1, 5_028 * ms), 1e-3f)
        assertTrue("non-CTRL seq", t.latencyMs(8, 0, 5_028 * ms).isNaN())
        assertTrue("never sent", t.latencyMs(200, 0, 5_028 * ms).isNaN())
    }

    @Test fun seqReuseInvalidatesOldCtrlTime() {
        val t = ControlTiming()
        t.onCtrlSent(42, 1_000 * ms)
        // 256 幀之後 SEQ 42 被分配給一個非 CTRL 的幀：舊的 CTRL 時間不能再被用來算延遲
        t.onSeqAllocated(42)
        assertTrue(t.latencyMs(42, 0, 1_010 * ms).isNaN())
        // 又被 CTRL 用到：用新的時間
        t.onCtrlSent(42 + 256, 2_000 * ms)
        assertEquals(12f, t.latencyMs(42, 0, 2_012 * ms), 1e-3f)
    }

    @Test fun carFramesSentWithinWindow() {
        val t = ControlTiming()
        // 50 Hz CTRL 共 2 秒 + 每 500 ms 一個 PING
        var now = 10_000 * ms
        repeat(100) { i ->
            t.onCarFrameSent(now)
            if (i % 25 == 0) t.onCarFrameSent(now)
            now += 20 * ms
        }
        val last = now - 20 * ms
        assertEquals(52, t.carFramesSentSince(last - 1_000 * ms, last))
        assertEquals(0, t.carFramesSentSince(last, last))
        t.reset()
        assertEquals(0, t.carFramesSentSince(0L, last))
    }

    @Test fun carRingWrapsWithoutOverCounting() {
        val t = ControlTiming()
        var now = 1_000 * ms
        repeat(ControlTiming.CAR_RING * 3) {
            t.onCarFrameSent(now)
            now += 2 * ms // 500 幀 / 秒（遠超實際）
        }
        val last = now - 2 * ms
        assertEquals(ControlTiming.CAR_RING, t.carFramesSentSince(last - 1_000 * ms, last))
    }

    // ------------------------------------------------------------------ link_q 結算對齊（上行遺失 / 連線品質）

    @Test fun settleTrackerLearnsPhaseFromLinkQChange() {
        val tr = LinkQSettleTracker()
        assertEquals(-1, tr.onTelemetry(1000, 12))
        assertEquals(-1, tr.onTelemetry(1002, 12)) // 還不知道相位
        // link_q 改變：結算在 (1002, 1004] → 取中間，離這筆 1 tick；下一次 ≈ 1103
        assertEquals(1, tr.onTelemetry(1004, 13))
        assertEquals(-1, tr.onTelemetry(1102, 13))
        // link_q 沒變也照推算的相位給樣本
        assertEquals(1, tr.onTelemetry(1104, 13))
        // 中間掉了好幾筆遙測（跳過 1203）：回傳離最近一次（1303）的距離
        assertEquals(20, tr.onTelemetry(1323, 13))
        assertEquals(-1, tr.onTelemetry(1401, 13))
        assertEquals(1, tr.onTelemetry(1404, 13))
        // 隔太久才改變（> 100 tick）無法定位結算 → 用推算的相位
        assertEquals(20, tr.onTelemetry(1523, 40))
        // tick 倒退（主控板重開機）→ 重新學
        assertEquals(-1, tr.onTelemetry(5, 0))
        assertEquals(-1, tr.onTelemetry(7, 0))
        assertEquals(1, tr.onTelemetry(9, 30))
        tr.reset()
        assertEquals(-1, tr.onTelemetry(11, 31))
    }

    @Test fun settleTrackerAcrossU16Wrap() {
        val tr = LinkQSettleTracker()
        tr.onTelemetry(65_498, 10)
        assertEquals(1, tr.onTelemetry(65_500, 11)) // 結算 ≈ 65499 → 下一次 ≈ 63（溢位後）
        assertEquals(-1, tr.onTelemetry(62, 11))
        assertEquals(1, tr.onTelemetry(64, 11))
        // u16 加 100 就是 u32 ticks 加 100：溢位後推算的相位照樣準
        assertEquals(-1, tr.onTelemetry(162, 11))
        assertEquals(1, tr.onTelemetry(164, 11))
    }

    @Test fun carFramesForSettleShiftsWindowBack() {
        val t = ControlTiming()
        for (i in 0 until 50) t.onCarFrameSent((1_000 + i * 20L) * ms) // 1.00..1.98 s：50 Hz（操作中）
        for (i in 0 until 10) t.onCarFrameSent((2_000 + i * 100L) * ms) // 2.0..2.9 s：10 Hz（靜止）
        // 收到遙測 = 2.990 s、結算就在這筆之前（age 0）、延遲 10 ms → 手機端 (1.98, 2.98] → 10 幀
        assertEquals(10, t.carFramesForSettle(2_990 * ms, 0, 10 * ms))
        // 結算在 50 tick（500 ms）前 → (1.48, 2.48] → 25 + 5 幀
        assertEquals(30, t.carFramesForSettle(2_990 * ms, 50, 10 * ms))
    }

    /**
     * 模擬：手機靜止 10 Hz → 操作中 50 Hz → 靜止（ControlPacer），主控板每 100 tick 結算、u16 tick 中途溢位、相位未知。
     * 對齊到主控板那 1 秒之後不會出現假遺失；舊算法（收到遙測前 1 秒 vs 最多 1 秒前結算的 link_q）一開始操作就會。
     */
    @Test fun alignedUplinkHasNoFakeLossWhenRateChanges() {
        val timing = ControlTiming()
        val tracker = LinkQSettleTracker()
        val aligned = UplinkLossEstimator(maxSamples = 5, minSamples = 2)
        val naive = UplinkLossEstimator(maxSamples = 10, minSamples = 3)
        val upMs = 4L
        val downMs = 5L
        val base = 65_037L // ticks（u32）：約 5 s 後 u16 溢位
        val arrivals = ArrayDeque<Long>()
        val telem = ArrayDeque<LongArray>() // (到手機的時間 ms, tick16, link_q)
        var count = 0
        var linkQ = 0
        var nextSend = 0L
        var lastNaive = 0L
        var maxAligned = 0f
        var maxNaive = 0f
        var alignedSamples = 0
        for (t in 0L until 20_000L) {
            val active = t in 6_000L until 12_000L
            if (t >= nextSend) {
                timing.onCarFrameSent(t * ms)
                arrivals.addLast(t + upMs)
                nextSend = t + if (active) 20 else 100
            }
            if (t % 500 == 250L) { // PING
                timing.onCarFrameSent(t * ms)
                arrivals.addLast(t + upMs)
            }
            while (arrivals.isNotEmpty() && arrivals.first() <= t) {
                arrivals.removeFirst()
                count++
            }
            if (t % 10 == 3L) {
                val ticks = base + (t - 3) / 10
                if (ticks % 100 == 0L) {
                    linkQ = count
                    count = 0
                }
                if (ticks % 2 == 0L) telem.addLast(longArrayOf(t + downMs, ticks and 0xFFFF, linkQ.toLong()))
            }
            while (telem.isNotEmpty() && telem.first()[0] <= t) {
                val (rx, tick, q) = telem.removeFirst().let { Triple(it[0], it[1].toInt(), it[2].toInt()) }
                val age = tracker.onTelemetry(tick, q)
                if (t < 3_000) continue
                if (age >= 0) {
                    aligned.add(timing.carFramesForSettle(rx * ms, age, (upMs + downMs) * ms), q)
                    alignedSamples++
                }
                aligned.percent()?.let { maxAligned = maxOf(maxAligned, it) }
                if (rx - lastNaive >= 500) {
                    lastNaive = rx
                    naive.add(timing.carFramesSentSince((rx - 1_000) * ms, rx * ms), q)
                }
                naive.percent()?.let { maxNaive = maxOf(maxNaive, it) }
            }
        }
        assertTrue("samples $alignedSamples", alignedSamples in 15..18)
        assertTrue("aligned max loss $maxAligned %", maxAligned < 3f)
        assertTrue("naive max loss $maxNaive %（這次修正的問題）", maxNaive > 8f)
    }
}
