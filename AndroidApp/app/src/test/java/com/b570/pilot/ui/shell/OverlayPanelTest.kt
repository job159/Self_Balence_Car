package com.b570.pilot.ui.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 滑入式導覽車況面板寬度：膠囊兩兩並排，640 × 320、字級 1.3 不用捲動就看得到「中斷連線」。 */
class OverlayPanelTest {
    @Test fun panelWidthFollowsWindow() {
        assertEquals(236f, overlayPanelWidthOf(400.dp).value, 0.01f) // 分割畫面：下限
        assertEquals(268.8f, overlayPanelWidthOf(640.dp).value, 0.01f)
        assertEquals(300f, overlayPanelWidthOf(780.dp).value, 0.01f) // 上限
        assertEquals(300f, overlayPanelWidthOf(1280.dp).value, 0.01f)
    }
}
