package com.b570.pilot.ui.screens.settings

import com.b570.pilot.data.HOTSPOT_OUTDOOR_HOWTO_ZH
import com.b570.pilot.data.HOTSPOT_SWITCH_DONE_ZH
import com.b570.pilot.link.HotspotConfig
import com.b570.pilot.protocol.AckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 設定頁「連線方式」：熱點表單的字數計算、失敗訊息、完成說明。 */
class HotspotDialogsTest {

    @Test fun switchDoneExplainsOutdoorUse() {
        assertEquals("完成！以後帶出去：先打開手機熱點，再打開車子電源，App 會自動連上。手機 Wi-Fi 可以關掉了。", HOTSPOT_SWITCH_DONE_ZH)
        assertTrue(HOTSPOT_SWITCH_DONE_ZH.endsWith(HOTSPOT_OUTDOOR_HOWTO_ZH))
    }

    @Test fun ssidBytesCountUtf8() {
        assertEquals(6, hotspotSsidBytes("iPhone"))
        assertEquals(9, hotspotSsidBytes("我的熱"))        // 中文一個字 3 bytes
        assertEquals(32, hotspotSsidBytes("a".repeat(32)))
        // 表單的字數上限與驗證一致：33 bytes 不合法
        assertNull(HotspotConfig.validateSsid("a".repeat(32)))
        assertNotNull(HotspotConfig.validateSsid("我".repeat(11)))
    }

    @Test fun failureMessages() {
        assertTrue(hotspotFailureZh(AckStatus.UNKNOWN_TYPE).contains("ESP-01"))
        assertEquals("另一支手機正在控制這台車，無法切換", hotspotFailureZh(AckStatus.BUSY))
        assertEquals("車子沒有回應，請靠近一點再試一次", hotspotFailureZh(AckStatus.TIMEOUT))
        AckStatus.entries.forEach { st ->
            // 每一種都有中文句子、沒有英文代碼
            val msg = hotspotFailureZh(st)
            assertTrue(msg.isNotBlank())
            assertTrue("$st → $msg", !msg.contains(st.name))
        }
    }
}
