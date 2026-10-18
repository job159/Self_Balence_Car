package com.b570.pilot.ui.shell

import com.b570.pilot.data.SavedCar
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.LinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第一次直連成功後「帶出去玩：改用熱點模式」邀請：每個條件都要成立才問。 */
class HotspotOnboardingTest {

    private val car = SavedCar("B570-A1B2", "password1", "", lastConnected = 100)
    private val connected = ConnectionState.Connected(info = null, sinceMs = 0L)

    /** 全部成立的基準；每個測試只改一個條件。 */
    private fun due(
        state: ConnectionState = connected,
        demo: Boolean = false,
        linkMode: LinkMode? = LinkMode.DIRECT,
        hotspotSupported: Boolean? = true,
        car: SavedCar? = this.car,
        carReportsHotspot: Boolean = false,
        answered: Set<String> = emptySet(),
        motorsOn: Boolean = false,
        switching: Boolean = false,
        overlayOpen: Boolean = false,
        onMain: Boolean = true,
    ) = hotspotOnboardingDue(
        state, demo, linkMode, hotspotSupported, car, carReportsHotspot, answered, motorsOn, switching, overlayOpen, onMain,
    )

    @Test fun allConditionsHold() {
        assertTrue(due())
    }

    @Test fun onlyWhenConnected() {
        assertFalse(due(state = ConnectionState.Idle))
        assertFalse(due(state = ConnectionState.Handshaking))
        assertFalse(due(state = ConnectionState.Discovering("B570-A1B2")))
        assertFalse(due(state = ConnectionState.RequestingWifi("B570-A1B2")))
        assertFalse(due(state = ConnectionState.LinkLost(0L)))
        assertFalse(due(state = ConnectionState.Failed("x")))
    }

    @Test fun notInDemo() {
        assertFalse(due(demo = true))
    }

    @Test fun onlyOnDirectLink() {
        assertFalse(due(linkMode = LinkMode.HOTSPOT))
        assertFalse(due(linkMode = null))
    }

    @Test fun waitsForStaGetAndSkipsOldFirmware() {
        assertFalse(due(hotspotSupported = null)) // 還不知道（STA_GET 還沒回）→ 先等
        assertFalse(due(hotspotSupported = false)) // 舊 ESP 韌體 → 不問
    }

    @Test fun skipsUnknownOrHotspotCars() {
        assertFalse(due(car = null))
        assertFalse(due(car = car.copy(hotspotMode = true)))
        // 車子回報已設定熱點模式（找不到熱點才改回直連）也不問
        assertFalse(due(carReportsHotspot = true))
        // 設定過熱點、之後「改回直連」的車（熱點名稱還留著）：使用者自己選的，不再邀請
        assertFalse(due(car = car.copy(hotspotSsid = "MyPhone", hotspotMode = false)))
        assertTrue(due(car = car.copy(hotspotSsid = " ")))
    }

    @Test fun askedOncePerCar() {
        assertFalse(due(answered = setOf("B570-A1B2")))
        assertTrue(due(answered = setOf("B570-ZZZZ")))
    }

    @Test fun notWhileDriving() {
        assertFalse(due(motorsOn = true))
    }

    @Test fun notDuringSwitch() {
        assertFalse(due(switching = true))
    }

    @Test fun notOverOverlaysOrOffMain() {
        assertFalse(due(overlayOpen = true))
        assertFalse(due(onMain = false))
    }

    @Test fun prefillMostRecentHotspotName() {
        assertEquals("", onboardingHotspotSsidOf(emptyList()))
        assertEquals("", onboardingHotspotSsidOf(listOf(car)))
        val old = SavedCar("B570-OLD1", "password1", "", lastConnected = 10, hotspotSsid = "舊熱點")
        val recent = SavedCar("B570-NEW1", "password1", "", lastConnected = 50, hotspotSsid = "我的手機")
        val blank = SavedCar("B570-BLNK", "password1", "", lastConnected = 90, hotspotSsid = "  ")
        assertEquals("我的手機", onboardingHotspotSsidOf(listOf(old, car, blank, recent)))
        assertEquals("舊熱點", onboardingHotspotSsidOf(listOf(old, car)))
    }

    @Test fun laterNoticePointsToSettings() {
        assertEquals("之後可以在 設定 › 連線方式 改用熱點模式", HOTSPOT_ONBOARDING_LATER_ZH)
        assertTrue(HOTSPOT_ONBOARDING_DELAY_MS in 1_000L..1_500L)
    }
}
