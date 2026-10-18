package com.b570.pilot.ui.screens.monitor

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 監測頁橫向版面（monitorLayoutOf）。尺寸是「內容可用區」：視窗 − 導覽軌 80 dp − 狀態條 48 dp − 下緣 8 dp。
 */
class MonitorLayoutTest {

    private fun content(windowW: Int, windowH: Int): Pair<Dp, Dp> = (windowW - 80).dp to (windowH - 48 - 8).dp

    private fun layoutFor(windowW: Int, windowH: Int, fontScale: Float = 1f): MonitorLayout {
        val (w, h) = content(windowW, windowH)
        return monitorLayoutOf(w, h, fontScale)
    }

    @Test
    fun smallPhone_twoPane_oneColumn_narrowToolbar() {
        val l = layoutFor(640, 320)
        assertFalse(l.stacked)
        assertEquals(1, l.chartColumns)
        assertEquals(ToolbarMode.NARROW, l.toolbar)
        assertEquals(228f, l.sideWidth.value, 0.01f)
        assertTrue(l.denseCards)
        assertTrue(l.denseTiles)
        // 一張卡剛好填滿工具列下方的可見高度
        assertEquals(264f - 44f - 10f, l.cardHeight.value, 0.01f)
        assertTrue(l.snapCards)
    }

    @Test
    fun typicalPhones() {
        val a = layoutFor(780, 360)
        assertFalse(a.stacked)
        assertEquals(1, a.chartColumns)
        assertEquals(ToolbarMode.MEDIUM, a.toolbar)
        assertEquals(304f - 54f, a.cardHeight.value, 0.01f)

        val b = layoutFor(915, 412)
        assertFalse(b.stacked)
        assertEquals(2, b.chartColumns)
        assertTrue(b.sideWidth.value in 270f..300f)
        assertEquals(356f - 54f, b.cardHeight.value, 0.01f)
        assertTrue(b.denseCards)
    }

    @Test
    fun tablet_twoColumns_twoRowsPerScreen_wideToolbar() {
        val l = layoutFor(1280, 800)
        assertFalse(l.stacked)
        assertEquals(2, l.chartColumns)
        assertEquals(ToolbarMode.WIDE, l.toolbar)
        assertEquals(320f, l.sideWidth.value, 0.01f)
        assertFalse(l.denseCards)
        assertFalse(l.denseTiles)
        // 兩列圖卡 + 間距剛好放進可見高度
        assertEquals(744f - 54f, l.cardHeight.value * 2 + CardGap.value, 0.01f)
        assertTrue(l.snapCards)
    }

    @Test
    fun splitScreen_stacksIntoOneColumn() {
        val l = layoutFor(457, 412)
        assertTrue(l.stacked)
        assertEquals(1, l.chartColumns)
        assertFalse(l.snapCards)
        assertTrue(l.cardHeight.value in 220f..360f)
    }

    @Test
    fun largeFont_widensSideColumn_andRaisesThresholds() {
        val small = layoutFor(640, 320, fontScale = 1.3f)
        assertFalse(small.stacked)
        assertEquals(248f, small.sideWidth.value, 0.01f)
        assertEquals(ToolbarMode.NARROW, small.toolbar)

        // 915 × 412：一般字級 2 欄，1.3 倍字級每欄太窄 → 1 欄
        assertEquals(1, layoutFor(915, 412, fontScale = 1.3f).chartColumns)
    }

    @Test
    fun twoPaneWidthsAlwaysAddUp_andChartPaneStaysUsable() {
        for (w in 560..1400 step 7) {
            for (h in listOf(264, 304, 356, 544, 744)) {
                for (fs in listOf(1f, 1.3f)) {
                    val l = monitorLayoutOf(w.dp, h.dp, fs)
                    if (l.stacked) continue
                    assertEquals("w=$w fs=$fs", w.toFloat(), (l.sideWidth + l.chartPaneWidth + l.gutter * 3).value, 0.05f)
                    assertTrue("w=$w fs=$fs", l.chartPaneWidth.value >= 260f - 0.01f)
                    assertTrue("w=$w fs=$fs", l.sideWidth.value >= 228f - 0.01f)
                    assertTrue("h=$h", l.cardHeight.value in 200f..420f)
                    if (l.chartColumns == 2) assertTrue((l.chartPaneWidth - CardGap).value / 2 >= 234f)
                }
            }
        }
    }

    @Test
    fun toolbarModes() {
        assertEquals(ToolbarMode.NARROW, toolbarModeOf(296.dp, big = false))
        assertEquals(ToolbarMode.MEDIUM, toolbarModeOf(417.dp, big = false))
        assertEquals(ToolbarMode.NARROW, toolbarModeOf(417.dp, big = true))
        assertEquals(ToolbarMode.WIDE, toolbarModeOf(832.dp, big = false))
    }

    @Test
    fun lastVisibleSeriesCannotBeHidden() {
        // 兩條曲線都顯示：可以關一條
        assertTrue(canToggle(seriesCount = 2, hiddenMask = 0, index = 0))
        // 只剩第 0 條：不能關
        assertFalse(canToggle(seriesCount = 2, hiddenMask = 0b10, index = 0))
        // 隱藏中的隨時可以打開
        assertTrue(canToggle(seriesCount = 2, hiddenMask = 0b10, index = 1))
        assertFalse(canToggle(seriesCount = 2, hiddenMask = 0, index = 5))
    }

    @Test
    fun numberFormatting() {
        assertEquals("--", fmtNum(Float.NaN, 1))
        assertEquals("--", fmtNum(null, 1))
        assertEquals("0.0", fmtNum(-0.01f, 1))
        assertEquals("12.3", fmtNum(12.34f, 1))
        assertEquals("-7", fmtNum(-7f, 0))
        assertEquals("10", fmtSec(10f))
        assertEquals("2.5", fmtSec(2.5f))
    }
}
