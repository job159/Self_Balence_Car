package com.b570.pilot.ui.screens.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 掃描頁橫向配置：目標尺寸（含字級 1.3 倍、單側瀏海）下框、提示、左右控制互不重疊且不出界。 */
class ScanLayoutTest {

    private val eps = 0.01f

    private fun check(w: Float, h: Float, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f, fs: Float = 1f): ScanLayout {
        val g = scanLayoutOf(w, h, l, t, r, b, fs)
        val tag = "${w}x$h fs=$fs cut=$l/$r"
        // 框在安全內容區內
        assertTrue("$tag frame top", g.frameTop >= g.contentTop - eps)
        assertTrue("$tag frame left", g.frameLeft >= g.contentLeft - eps)
        // 提示膠囊在框下方、不超出下緣
        assertTrue("$tag pill below frame", g.pillTop >= g.frameBottom + SCAN_PILL_GAP - eps)
        assertTrue("$tag pill fits", g.pillTop + g.pillHeight <= g.contentBottom + eps)
        // 右側控制列不碰到框，左側欄也在框左邊
        assertTrue("$tag rail clear of frame", g.frameRight + SCAN_SIDE_GAP <= g.railLeft + eps)
        assertTrue("$tag panel clear of frame", g.contentLeft + g.panelWidth + SCAN_SIDE_GAP <= g.frameLeft + eps)
        // 框置中於安全區
        assertEquals("$tag centered", (g.contentLeft + g.contentRight) / 2f, g.frameCx, eps)
        // 安全區左右邊距（圓角）至少 12 dp
        assertTrue("$tag edge", g.contentLeft - l >= 12f && w - r - g.contentRight >= 12f)
        return g
    }

    @Test fun smallPhone() {
        val g = check(640f, 320f)
        assertTrue("frame big enough to aim", g.frameSide >= 220f)
        assertFalse("guide card fits", g.compactPanel)
    }

    @Test fun smallPhoneLargeFontAndCutout() {
        // 字級 1.3 倍 + 瀏海在左（翻轉後在右）
        val left = check(640f, 320f, l = 30f, fs = 1.3f)
        val right = check(640f, 320f, r = 30f, fs = 1.3f)
        assertEquals(left.frameSide, right.frameSide, eps)
        assertTrue(left.frameCx > right.frameCx) // 跟著安全區移動
        assertFalse(left.compactPanel)
    }

    @Test fun typicalPhones() {
        val a = check(780f, 360f)
        val b = check(915f, 412f)
        assertTrue(a.frameSide >= 260f)
        assertEquals(340f, b.frameSide, eps) // 手機上限
        assertTrue(b.panelWidth >= 220f)
    }

    @Test fun tablet() {
        val g = check(1280f, 800f, t = 24f, b = 48f)
        assertEquals(420f, g.frameSide, eps) // 平板上限
        assertTrue(g.panelWidth > 300f)
    }

    @Test fun narrowSplitScreenStillSane() {
        val g = check(420f, 360f)
        assertTrue(g.frameSide >= 96f)
        assertTrue(g.compactPanel)
    }
}
