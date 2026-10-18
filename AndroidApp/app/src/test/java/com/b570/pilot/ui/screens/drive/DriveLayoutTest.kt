package com.b570.pilot.ui.screens.drive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.JoystickMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 橫向遙控器版面在目標尺寸（640 × 320 / 780 × 360 / 915 × 412 / 1280 × 800）都放得下。 */
class DriveLayoutTest {

    private val sizes = listOf(640.dp to 320.dp, 780.dp to 360.dp, 915.dp to 412.dp, 1280.dp to 800.dp, RcMinWidth to RcMinHeight)

    private fun assertAtLeast(name: String, actual: Dp, min: Dp) =
        assertTrue("$name = $actual < $min", actual >= min)

    @Test fun sizeClasses() {
        assertEquals(RcSize.COMPACT, rcMetricsOf(640.dp, 320.dp).size)
        assertEquals(RcSize.MEDIUM, rcMetricsOf(780.dp, 360.dp).size)
        assertEquals(RcSize.MEDIUM, rcMetricsOf(915.dp, 412.dp).size)
        assertEquals(RcSize.EXPANDED, rcMetricsOf(1280.dp, 800.dp).size)
    }

    @Test fun everythingFits() {
        for ((w, h) in sizes) {
            val m = rcMetricsOf(w, h)
            val tag = "${w.value.toInt()}x${h.value.toInt()}"
            // 垂直：上緣列 + 肩鍵 + 下半部 + 留白 = 高
            assertEquals(tag, h.value, (m.top + m.barH + m.barGap + m.shoulderH + m.lowerGap + m.lowerH + m.bottom).value, 0.01f)
            // 搖桿至少 140 dp（拇指行程），中控台在每個模式都至少 170 dp 寬
            assertAtLeast("$tag lowerH", m.lowerH, 140.dp)
            for (mode in JoystickMode.entries) assertAtLeast("$tag console[$mode]", m.consoleWidth(mode), 170.dp)
            // 肩鍵：「啟動平衡」/「緊急停止」字級 1.3 倍也放得下
            assertAtLeast("$tag shoulderW", m.shoulderW, 150.dp)
            // 肩鍵列中間的模式選擇：三段各至少 56 dp
            assertAtLeast("$tag modeSelector", m.shoulderCenterWidth / 3, 56.dp)
            // 貼邊控制至少離安全區 12 dp（螢幕圓角）
            assertAtLeast("$tag edge", m.edge, 12.dp)
            // 上緣列：≡ + 編排 + 檔位翹板之外，車況膠囊至少有 200 dp
            assertAtLeast("$tag chips", w - m.edge * 2 - 40.dp - 40.dp - 176.dp - 24.dp, 200.dp)
        }
    }

    @Test fun stickSizesFollowBrief() {
        val phone = rcMetricsOf(780.dp, 360.dp)
        // 360° 搖桿：手機約 170–240 dp（拇指區放得下的最大圓），平板更大但不超過 300 dp
        for ((w, h) in listOf(640.dp to 320.dp, 780.dp to 360.dp, 915.dp to 412.dp)) {
            val m = rcMetricsOf(w, h)
            assertTrue("$w x $h stick360 = ${m.stick360}", m.stick360 in 170.dp..240.dp)
            assertTrue("stick fits zone", m.stick360 <= m.sideW && m.stick360 <= m.lowerH)
        }
        val tablet = rcMetricsOf(1280.dp, 800.dp)
        assertTrue(tablet.stick360 > phone.stick360)
        assertTrue(tablet.stick360 <= 300.dp)
        // 雙搖桿上限不變
        assertTrue(minOf(tablet.sideW, tablet.lowerH, tablet.stickMax) <= 300.dp)
        // 體感：按住駕駛鈕比搖桿區窄，中控台多出空間放水平儀
        assertTrue(phone.padW < phone.sideW)
        assertTrue(phone.consoleWidth(JoystickMode.TILT) > phone.consoleWidth(JoystickMode.DUAL))
    }

    @Test fun actionPadFits() {
        for ((w, h) in sizes) {
            val m = rcMetricsOf(w, h)
            val zoneH = minOf(m.lowerH, m.stickMax + 40.dp)
            // 360° 按鍵區：菱形（扣掉檔位翹板）
            val d = ActionPadGeometry.diameterFor(m.clusterPadWidth, zoneH)
            assertTrue("d = $d", d >= 44.dp)
            assertTrue("pad width", ActionPadGeometry.width(d) <= m.clusterPadWidth + 0.5.dp)
            assertTrue("pad height ${ActionPadGeometry.height(d)} > $zoneH", ActionPadGeometry.height(d) <= zoneH + 0.5.dp)
            // 直立檔位翹板（升檔 / 檔位 / 降檔）至少 140 dp 高
            if (m.gearInCluster) assertAtLeast("rocker", minOf(zoneH, 156.dp), 140.dp)
        }
    }

    @Test fun gearMovesIntoClusterOnRealScreens() {
        for ((w, h) in listOf(640.dp to 320.dp, 780.dp to 360.dp, 915.dp to 412.dp, 1280.dp to 800.dp)) {
            val m = rcMetricsOf(w, h)
            val tag = "${w.value.toInt()}x${h.value.toInt()}"
            assertTrue("$tag gearInCluster", m.gearInCluster)
            assertAtLeast("$tag console360", m.consoleWidth(JoystickMode.SINGLE), MinConsole360 - 0.5.dp)
            assertAtLeast("$tag button", m.clusterPadWidth / (3f + 2f * ActionPadGeometry.GAP_FACTOR), MinClusterButton)
        }
        // 極窄分割畫面：檔位留在上緣列，按鍵區 = 一般手區寬
        val tiny = rcMetricsOf(RcMinWidth, RcMinHeight)
        assertFalse(tiny.gearInCluster)
        assertEquals(tiny.sideW, tiny.clusterW)
    }

    @Test fun stickSideSwapsZones() {
        assertEquals(ZoneKind.STICK_360, leftZoneOf(JoystickMode.SINGLE, stickRight = false))
        assertEquals(ZoneKind.CLUSTER, rightZoneOf(JoystickMode.SINGLE, stickRight = false))
        assertEquals(ZoneKind.CLUSTER, leftZoneOf(JoystickMode.SINGLE, stickRight = true))
        assertEquals(ZoneKind.STICK_360, rightZoneOf(JoystickMode.SINGLE, stickRight = true))
        // 雙搖桿 / 體感不受影響
        for (r in listOf(false, true)) {
            assertEquals(ZoneKind.THROTTLE, leftZoneOf(JoystickMode.DUAL, r))
            assertEquals(ZoneKind.STEERING, rightZoneOf(JoystickMode.DUAL, r))
            assertEquals(ZoneKind.PAD_LEFT, leftZoneOf(JoystickMode.TILT, r))
            assertEquals(ZoneKind.PAD_RIGHT, rightZoneOf(JoystickMode.TILT, r))
        }
        // 換邊不改變中控台寬
        val m = rcMetricsOf(780.dp, 360.dp)
        val a = m.zoneWidth(leftZoneOf(JoystickMode.SINGLE, false)) + m.zoneWidth(rightZoneOf(JoystickMode.SINGLE, false))
        val b = m.zoneWidth(leftZoneOf(JoystickMode.SINGLE, true)) + m.zoneWidth(rightZoneOf(JoystickMode.SINGLE, true))
        assertEquals(a.value, b.value, 0.01f)
    }
}
