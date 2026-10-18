package com.b570.pilot.ui.screens.choreo

import androidx.compose.ui.unit.dp
import com.b570.pilot.protocol.SeqStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 橫向版面（內容寬 = 畫面寬 − 導覽軌 80 dp − 兩側 16 dp）：
 * 640 → 528、780 → 668、915 → 803、平板 1280 → 1168。
 */
class ChoreoLayoutTest {

    @Test fun libraryColumnsPerSize() {
        val small = homeLayoutFor(528.dp)
        assertEquals(3, small.cols)
        assertEquals(1, small.seqPerLine)
        assertFalse(small.side)

        val phone = homeLayoutFor(668.dp)
        assertEquals(4, phone.cols)
        assertEquals(2, phone.seqPerLine)
        assertFalse(phone.side)

        val wide = homeLayoutFor(803.dp)
        assertEquals(5, wide.cols)
        assertEquals(2, wide.seqPerLine)
        assertFalse(wide.side)

        // 平板：我的編排放側欄，動作庫仍有 5 欄
        val tablet = homeLayoutFor(1168.dp)
        assertTrue(tablet.side)
        assertEquals(5, tablet.cols)
        assertEquals(1, tablet.seqPerLine)
        assertTrue(tablet.sideW >= 320.dp && tablet.sideW <= 400.dp)

        // 超寬（無側欄的情況不會出現，但上限仍是 6 欄）
        assertTrue(homeLayoutFor(2000.dp).cols <= 6)
    }

    @Test fun gridUnitsDivideBothCardKinds() {
        for (w in listOf(400, 528, 600, 668, 760, 803, 990, 1168, 1400)) {
            val l = homeLayoutFor(w.dp)
            assertEquals(0, l.units % l.cols)
            assertEquals(0, l.units % l.seqPerLine)
            assertTrue("card too narrow at $w", l.cardW >= 120.dp)
            assertTrue(l.previewH >= 72.dp && l.previewH <= 116.dp)
        }
    }

    @Test fun headerSitsBesideReadinessFromSmallPhoneUp() {
        assertTrue(homeLayoutFor(528.dp).headerInline)
        assertFalse(homeLayoutFor(420.dp).headerInline)
    }

    @Test fun editorSplitPerSize() {
        for (w in listOf(528, 668, 803, 1168)) {
            val e = editorLayoutFor(w.dp)
            assertTrue("timeline too narrow at $w", e.leftW >= 272.dp)
            assertTrue("preview too narrow at $w", e.rightW >= 220.dp)
            assertEquals(w.toFloat(), (e.leftW + e.rightW + EditorGap).value, 0.01f)
        }
        // 只有平板的步驟卡夠寬（5 個動作一列、滑桿兩欄）
        assertFalse(editorLayoutFor(803.dp).wideCard)
        assertTrue(editorLayoutFor(1168.dp).wideCard)
    }

    @Test fun stripTapPicksStepByTime() {
        val starts = floatArrayOf(0f, 1f, 3f, 6f)
        assertEquals(0, stepIndexAtFraction(0f, starts, 3))
        assertEquals(1, stepIndexAtFraction(0.2f, starts, 3))
        assertEquals(2, stepIndexAtFraction(0.99f, starts, 3))
        assertEquals(2, stepIndexAtFraction(1f, starts, 3))
        assertEquals(2, stepIndexAtFraction(1.5f, starts, 3))
        assertEquals(-1, stepIndexAtFraction(0.5f, starts, 0))
    }

    @Test fun stepClockFollowsCarStepAndLoop() {
        val traj = simulate(listOf(SeqStep(200, 0, 1000), SeqStep(0, 0, 500)), 2, SimLimits.DEFAULT)
        val clock = StepClock()
        // 還沒開始
        assertTrue(clock.progressInLoop(traj, false) < 0f)
        // 第 2 輪第 2 步，影格時鐘已對上這一步、經過 0.25 秒
        clock.step = 1
        clock.loop = 1
        clock.clockStep = 1
        clock.clockLoop = 1
        clock.stepStart.longValue = 0L
        clock.frame.longValue = 250_000_000L
        assertEquals(1.25f, clock.progressInLoop(traj, false), 0.02f)
        assertEquals(traj.loopSec + 1.25f, clock.playhead(traj, false), 0.02f)
        // 剛換步驟、影格時鐘還沒對上 → 停在該步開頭
        clock.step = 0
        assertEquals(0f, clock.progressInLoop(traj, false), 0.001f)
        // 完成 → 停在終點
        assertEquals(traj.totalSec, clock.playhead(traj, true), 0.001f)
    }
}
