package com.b570.pilot.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 設定頁主從式版面：目標尺寸都分欄、寬度加總剛好、分類面板在上下限內；
 * 手機橫放詳情單欄、平板詳情兩欄；分割畫面退回單欄。內容寬 = 視窗寬 − 80 dp 導覽軌 − 2 × 16 dp 留白。
 */
class SettingsLayoutTest {

    private val eps = 0.01f

    private fun content(window: Float) = window - 80f - 32f

    private fun split(window: Float): SettingsPanes {
        val w = content(window)
        val p = settingsPanesOf(w)
        assertFalse("$window split", p.singlePane)
        assertEquals("$window sum", w, p.listWidth + p.gap + DETAIL_START_PAD_DP + p.detailWidth, eps)
        assertTrue("$window list range", p.listWidth in LIST_MIN_DP..LIST_MAX_DP)
        return p
    }

    @Test fun phonesUseSingleColumnDetail() {
        val small = split(640f)
        assertEquals(LIST_MIN_DP, small.listWidth, eps)
        assertTrue("640 detail ≥ 340 (直向手機寬的卡片放得下)", small.detailWidth >= 340f)
        assertFalse(small.detailTwoColumn)
        for (w in listOf(780f, 840f, 915f)) {
            val p = split(w)
            assertFalse("$w detail single column", p.detailTwoColumn)
            assertTrue("$w detail wider than small phone", p.detailWidth > small.detailWidth)
        }
    }

    @Test fun tabletUsesTwoColumnDetail() {
        val tablet = split(1280f)
        assertEquals(LIST_MAX_DP, tablet.listWidth, eps)
        assertTrue(tablet.detailTwoColumn)
        assertTrue(tablet.detailWidth >= WIDE_PANE_MIN_DP)
    }

    @Test fun narrowWindowFallsBackToSinglePane() {
        assertTrue(settingsPanesOf(content(500f)).singlePane)
        assertTrue(settingsPanesOf(SINGLE_PANE_MAX_DP - 1f).singlePane)
        assertFalse(settingsPanesOf(SINGLE_PANE_MAX_DP).singlePane)
        val p = settingsPanesOf(300f)
        assertEquals(300f, p.detailWidth, eps)
        assertFalse(p.detailTwoColumn)
        assertTrue(settingsPanesOf(-5f).singlePane)
    }

    @Test fun formatters() {
        assertEquals("45 秒", formatUptime(45))
        assertEquals("12 分 5 秒", formatUptime(12 * 60 + 5))
        assertEquals("3 小時 12 分", formatUptime(3 * 3600 + 12 * 60))
        assertEquals("2 天 3 小時", formatUptime(2 * 86_400 + 3 * 3600))
        assertEquals("0 秒", formatUptime(-3))
        val now = 1_700_000_000_000L
        assertEquals("尚未連線過", formatRelative(0L, now))
        assertEquals("剛剛", formatRelative(now - 10_000, now))
        assertEquals("5 分鐘前", formatRelative(now - 5 * 60_000, now))
        assertEquals("3 小時前", formatRelative(now - 3 * 3_600_000, now))
        assertEquals("昨天", formatRelative(now - 30 * 3_600_000L, now))
        assertEquals("3 天前", formatRelative(now - 3 * 86_400_000L, now))
        assertEquals("1,234", formatCount(1234))
        assertEquals("1.5", formatKb(1536))
    }
}
