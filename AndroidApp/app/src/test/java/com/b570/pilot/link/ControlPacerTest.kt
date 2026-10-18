package com.b570.pilot.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPacerTest {
    private fun countSends(p: ControlPacer, from: Long, ticks: Int, value: (Long) -> Int): Int =
        (from until from + ticks).count { p.shouldSend(it, value(it)) }

    @Test fun movingStickSendsEveryTick() {
        val p = ControlPacer()
        assertEquals(50, countSends(p, 0, 50) { (it * 7 % 300).toInt() + 1 })
    }

    @Test fun idleDropsTo10Hz() {
        val p = ControlPacer()
        countSends(p, 0, 20) { 0 }
        assertEquals(10, countSends(p, 100, 50) { 0 })   // 50 週期 = 1 秒
    }

    @Test fun keepsFullRateShortlyAfterStickStops() {
        val p = ControlPacer()
        countSends(p, 0, 10) { 500 }
        assertEquals(ControlPacer.ACTIVE_TICKS.toInt(), countSends(p, 10, ControlPacer.ACTIVE_TICKS.toInt()) { 300 } )
    }

    @Test fun releaseIsSentImmediatelyEvenWhenCongested() {
        val p = ControlPacer()
        repeat(20) { p.onLatencySample(400f) }
        assertTrue(p.congested)
        assertTrue(p.shouldSend(0, 800))
        assertFalse(p.shouldSend(1, 800))
        assertTrue("release must not wait", p.shouldSend(2, 0))
    }

    @Test fun congestionThrottlesThenRecoversWithHysteresis() {
        val p = ControlPacer()
        repeat(20) { p.onLatencySample(300f) }
        assertTrue(p.congested)
        val n = countSends(p, 0, 30) { (it % 2 + 1).toInt() * 100 }   // 一直在動
        assertEquals(10, n)                                               // 每 3 週期一次
        repeat(3) { p.onLatencySample(100f) }
        assertTrue("100 ms 仍在遲滯區間內", p.congested)
        repeat(30) { p.onLatencySample(30f) }
        assertFalse(p.congested)
        assertEquals(30, countSends(p, 100, 30) { (it % 2 + 1).toInt() * 100 })
    }

    @Test fun ignoresInvalidSamples() {
        val p = ControlPacer()
        p.onLatencySample(Float.NaN)
        p.onLatencySample(-5f)
        assertFalse(p.congested)
    }
}
