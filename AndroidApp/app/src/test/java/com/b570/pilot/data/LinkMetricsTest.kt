package com.b570.pilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LinkStats 的控制延遲（EMA / P95）與上行遺失率計算。 */
class LinkMetricsTest {

    @Test fun emaTracksSamplesAndGoesStale() {
        val w = LatencyWindow(alpha = 0.5f, staleMs = 3_000)
        assertNull(w.ema(0))
        w.add(20f, 1_000)
        assertEquals(20f, w.ema(1_000)!!, 1e-4f)
        w.add(40f, 1_020)
        assertEquals(30f, w.ema(1_020)!!, 1e-4f)
        assertEquals(30f, w.ema(4_000)!!, 1e-4f)
        assertNull("no sample for > 3 s", w.ema(4_100))
    }

    @Test fun invalidSamplesIgnored() {
        val w = LatencyWindow()
        w.add(Float.NaN, 0); w.add(-1f, 0); w.add(Float.POSITIVE_INFINITY, 0)
        assertNull(w.ema(0))
        assertEquals(0, w.samplesWithin(0))
    }

    @Test fun p95OverLastFiveSeconds() {
        val w = LatencyWindow()
        // 5 s 前的大延遲不算
        repeat(50) { w.add(500f, it.toLong()) }
        // 最近 5 s：100 個樣本 1..100 ms
        for (i in 1..100) w.add(i.toFloat(), 10_000L + i * 40)
        val now = 10_000L + 100 * 40
        assertEquals(100, w.samplesWithin(now))
        assertEquals(95f, w.p95(now)!!, 0f) // nearest-rank：第 95 個
        assertEquals(50f, w.percentile(now, 0.5f)!!, 0f)
    }

    @Test fun p95NeedsEnoughSamples() {
        val w = LatencyWindow(minSamples = 5)
        repeat(4) { w.add(10f, 100L + it) }
        assertNull(w.p95(200))
        w.add(90f, 105)
        assertEquals(90f, w.p95(200)!!, 0f)
        w.reset()
        assertNull(w.p95(200))
        assertNull(w.ema(200))
    }

    @Test fun ringOverflowKeepsNewest() {
        val w = LatencyWindow(capacity = 16)
        repeat(100) { w.add(it.toFloat(), 1_000L + it) }
        assertEquals(16, w.samplesWithin(1_100))
        assertEquals(99f, w.p95(1_100)!!, 0f)
    }

    @Test fun uplinkLoss() {
        val u = UplinkLossEstimator(maxSamples = 10, minSamples = 3)
        u.add(52, 52); u.add(52, 52)
        assertNull("too few samples", u.percent())
        u.add(52, 52)
        assertEquals(0f, u.percent()!!, 1e-4f)
        // 一半掉了
        repeat(10) { u.add(52, 26) }
        assertEquals(50f, u.percent()!!, 1e-3f)
        // 車上算到的比手機送的多（視窗不同步）→ 夾在 0
        u.reset()
        repeat(3) { u.add(50, 53) }
        assertEquals(0f, u.percent()!!, 0f)
        u.reset()
        repeat(3) { u.add(0, 0) }
        assertNull("nothing sent", u.percent())
    }

    @Test fun linkStatsDisplayLatencyPrefersControlLatency() {
        assertEquals(18f, LinkStats(rttMs = 40f, controlLatencyMs = 18f).displayLatencyMs!!, 0f)
        assertEquals(40f, LinkStats(rttMs = 40f).displayLatencyMs!!, 0f)
        assertNull(LinkStats().displayLatencyMs)
        assertFalse(LinkStats().dualWifi)
        assertTrue(LinkStats(dualWifi = true).dualWifi)
    }

    @Test fun linkQualityIsHundredMinusUplinkLoss() {
        // 不再用 link_q × 2：靜止時手機只送 10 Hz 心跳，link_q ≈ 12 也是 100 % 的品質
        assertNull(LinkStats().linkQualityPercent)
        assertEquals(100, LinkStats(uplinkLossPercent = 0f).linkQualityPercent)
        assertEquals(88, LinkStats(uplinkLossPercent = 12.4f).linkQualityPercent)
        assertEquals(0, LinkStats(uplinkLossPercent = 100f).linkQualityPercent)
    }

    @Test fun defaultUplinkEstimatorIsAboutFiveSeconds() {
        // 主控板每秒結算一次 → 每秒一個樣本：2 個就有數字，保留最近 5 個
        val u = UplinkLossEstimator()
        u.add(12, 12)
        assertNull(u.percent())
        u.add(52, 26)
        assertEquals(1 - 38f / 64f, u.percent()!! / 100f, 1e-4f)
        repeat(5) { u.add(52, 52) }
        assertEquals(0f, u.percent()!!, 0f)
    }
}
