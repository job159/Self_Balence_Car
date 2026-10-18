package com.b570.pilot.ui.screens.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 連線頁左右分欄：目標尺寸都分欄、寬度加總剛好、操作台寬度在上下限內；分割畫面退回單欄。 */
class ConnectLayoutTest {

    private val eps = 0.01f

    private fun split(w: Float): ConnectPanes {
        val p = connectPanesOf(w)
        assertTrue("$w split", p.split)
        assertEquals("$w sum", w, p.sideMargin * 2 + p.heroWidth + p.gap + p.consoleWidth, eps)
        assertTrue("$w console range", p.consoleWidth in CONSOLE_MIN..CONSOLE_MAX)
        assertTrue("$w hero", p.heroWidth >= 200f)
        return p
    }

    @Test fun targets() {
        val small = split(640f)
        assertTrue(small.consoleWidth >= 330f) // 兩顆並排按鈕 + 說明，字級 1.3 倍也放得下
        split(780f)
        split(915f)
        val tablet = split(1280f)
        assertEquals(CONSOLE_MAX, tablet.consoleWidth, eps)
        assertTrue(tablet.heroWidth > 600f)
    }

    @Test fun narrowFallsBackToSingleColumn() {
        assertFalse(connectPanesOf(500f).split)
        assertFalse(connectPanesOf(400f).split)
        assertTrue(connectPanesOf(400f).consoleWidth <= 520f)
    }

    @Test fun relativeTime() {
        val now = 1_700_000_000_000L
        assertEquals("尚未連線過", relativeTimeZh(0L, now))
        assertEquals("剛剛", relativeTimeZh(now - 20_000L, now))
        assertEquals("5 分鐘前", relativeTimeZh(now - 5 * 60_000L, now))
        assertEquals("3 小時前", relativeTimeZh(now - 3 * 3_600_000L, now))
    }
}
