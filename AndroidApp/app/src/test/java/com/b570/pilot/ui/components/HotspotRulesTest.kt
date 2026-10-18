package com.b570.pilot.ui.components

import com.b570.pilot.data.HotspotSwitchPhase
import com.b570.pilot.protocol.StaInfo
import com.b570.pilot.protocol.StaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 熱點模式狀態（STA_INFO）的顯示文字、切換進度點、倒數。 */
class HotspotRulesTest {

    private fun sta(enabled: Boolean, state: StaState, ip: String = "0.0.0.0", rssi: Int? = null, ssid: String = "我的iPhone") =
        StaInfo(enabled, state, ip, rssi, ssid, "")

    @Test fun unsupportedFirmwareWins() {
        val ui = staStatusOf(sta(true, StaState.CONNECTED, "192.168.43.57"), supported = false, connected = true)
        assertEquals("Wi-Fi 模組不支援熱點模式", ui.titleZh)
        assertEquals(DiagnosisTone.WARN, ui.tone)
    }

    @Test fun notReadYet() {
        val reading = staStatusOf(null, supported = null, connected = true)
        assertTrue(reading.busy)
        assertEquals("讀取車子的連線設定…", reading.titleZh)
        val offline = staStatusOf(null, supported = null, connected = false)
        assertFalse(offline.busy)
        assertEquals("連線到車子後顯示目前狀態", offline.titleZh)
    }

    @Test fun directMode() {
        val ui = staStatusOf(sta(false, StaState.OFF), supported = true, connected = true, carApSsid = "B570-A1B2")
        assertEquals("直連模式", ui.titleZh)
        assertEquals("車子開自己的 Wi-Fi「B570-A1B2」，手機直接連", ui.detailZh)
    }

    @Test fun connectedShowsIpAndSignal() {
        val ui = staStatusOf(sta(true, StaState.CONNECTED, "192.168.43.57", rssi = -52), supported = true, connected = true)
        assertEquals("已連上熱點「我的iPhone」", ui.titleZh)
        assertEquals("IP 192.168.43.57 · 訊號 -52 dBm（很好）", ui.detailZh)
        assertEquals(DiagnosisTone.OK, ui.tone)
        // 沒有 RSSI / IP 時不顯示空白細節
        val bare = staStatusOf(sta(true, StaState.CONNECTED, "0.0.0.0"), supported = true, connected = true)
        assertNull(bare.detailZh)
    }

    @Test fun searchingAndFallback() {
        val s = staStatusOf(sta(true, StaState.SEARCHING), supported = true, connected = true)
        assertEquals("正在搜尋熱點「我的iPhone」…", s.titleZh)
        assertTrue(s.busy)
        val f = staStatusOf(sta(true, StaState.FALLBACK), supported = true, connected = true)
        assertEquals("找不到熱點「我的iPhone」，已改回直連", f.titleZh)
        assertTrue(f.detailZh!!.contains("2.4 GHz"))
        assertEquals(DiagnosisTone.WARN, f.tone)
        // 沒有熱點名稱：用「手機熱點」
        assertEquals("正在搜尋熱點「手機熱點」…", staStatusOf(sta(true, StaState.SEARCHING, ssid = ""), true, true).titleZh)
    }

    @Test fun rssiWords() {
        assertEquals("很好", rssiWordZh(-60))
        assertEquals("良好", rssiWordZh(-61))
        assertEquals("普通", rssiWordZh(-80))
        assertEquals("微弱", rssiWordZh(-81))
    }

    @Test fun switchSteps() {
        assertEquals(3, HOTSPOT_SWITCH_STEPS.size)
        assertEquals(0, hotspotSwitchStepOf(HotspotSwitchPhase.RESTARTING))
        assertEquals(1, hotspotSwitchStepOf(HotspotSwitchPhase.WAITING_HOTSPOT))
        assertEquals(2, hotspotSwitchStepOf(HotspotSwitchPhase.SEARCHING))
        assertEquals(2, hotspotSwitchStepOf(HotspotSwitchPhase.FAILED))
    }

    @Test fun secondsLeftRoundsUpAndNeverNegative() {
        assertEquals(60L, hotspotSwitchSecondsLeft(deadlineMs = 60_000, nowMs = 0))
        assertEquals(1L, hotspotSwitchSecondsLeft(deadlineMs = 1_000, nowMs = 1))
        assertEquals(0L, hotspotSwitchSecondsLeft(deadlineMs = 1_000, nowMs = 1_000))
        assertEquals(0L, hotspotSwitchSecondsLeft(deadlineMs = 1_000, nowMs = 9_000))
    }
}
