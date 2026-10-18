package com.b570.pilot.ui.screens.tune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整定頁橫向版面決策。輸入 = 視窗扣掉導覽軌（80）、狀態條（48）、下緣留白（8）與左右邊距（12 + 12）後的可用區。
 */
class TuneLayoutTest {

    /** 視窗 dp → 可用區（無瀏海、沉浸式）。 */
    private fun forWindow(w: Float, h: Float) = tuneLayoutOf(w - 80f - 24f, h - 48f - 8f)

    @Test fun smallPhoneTwoPaneCompact() {
        val l = forWindow(640f, 320f)
        assertTrue(l.twoPane)
        assertEquals(228f, l.sideWidth, 0.01f)
        assertEquals(1, l.paramColumns)
        assertTrue("說明改放標題下方", l.compactRows)
        assertTrue(l.narrowSide)
        assertEquals(56f, l.chartHeight, 0.01f)
        assertFalse("太矮：群組列跟著捲動", l.stickyGroupNav)
        assertTrue(l.compactDock)
        // 右欄至少放得下一列參數（−、滑軌、＋）
        assertTrue(l.mainWidth >= 260f)
    }

    @Test fun typicalPhones() {
        val a = forWindow(780f, 360f)
        assertTrue(a.twoPane)
        assertEquals(1, a.paramColumns)
        assertFalse(a.compactRows)
        assertTrue(a.stickyGroupNav)
        assertTrue(a.chartHeight in 80f..120f)

        val b = forWindow(915f, 412f)
        assertTrue(b.twoPane)
        assertEquals(1, b.paramColumns)
        assertFalse(b.narrowSide)
        assertTrue(b.stickyGroupNav)
        assertTrue(b.sideWidth / (b.sideWidth + b.mainWidth + TUNE_PANE_GAP) in 0.36f..0.40f)
    }

    @Test fun tabletUsesTwoParamColumns() {
        val l = forWindow(1280f, 800f)
        assertTrue(l.twoPane)
        assertEquals(420f, l.sideWidth, 0.01f)
        assertEquals(2, l.paramColumns)
        assertFalse(l.compactRows)
        assertFalse(l.narrowSide)
        assertEquals(220f, l.chartHeight, 0.01f)
        assertTrue(l.stickyGroupNav)
        assertFalse(l.compactDock)
    }

    @Test fun narrowWindowFallsBackToSingleColumn() {
        val l = tuneLayoutOf(420f, 600f)
        assertFalse(l.twoPane)
        assertEquals(420f, l.mainWidth, 0.01f)
        assertEquals(1, l.paramColumns)
        assertFalse("單欄沒有固定的群組列", l.stickyGroupNav)
    }

    @Test fun sidePaneIsClamped() {
        assertEquals(228f, tuneLayoutOf(500f, 300f).sideWidth, 0.01f)
        assertEquals(420f, tuneLayoutOf(2000f, 900f).sideWidth, 0.01f)
        // 兩欄時每欄至少 276 dp
        val l = tuneLayoutOf(1032f, 700f)
        assertEquals(2, l.paramColumns)
        assertTrue((l.mainWidth - 2 * TUNE_CARD_PAD - TUNE_COL_GAP) / 2 >= 276f)
    }
}
