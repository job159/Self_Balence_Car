package com.b570.pilot.data

import com.b570.pilot.link.LinkRequest
import com.b570.pilot.link.WifiCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 智慧連線（smartConnect）的決策：先在手機熱點上找車還是直連。 */
class ConnectPlannerTest {

    private val creds = WifiCredentials("B570-A1B2", "pass1234")
    private val direct = SavedCar("B570-A1B2", "pass1234")
    private val hotspot = direct.copy(hotspotMode = true, lastIp = "192.168.43.57", hotspotSsid = "MyPhone")

    @Test fun directCarWithoutHotspotGoesStraightToWifi() {
        val p = ConnectPlanner.plan(direct, creds, phoneHotspotOn = false)
        assertEquals(ConnectPlan(discover = false, discoveryMs = 0, direct = true, hotspotExpected = false), p)
        assertTrue(ConnectPlanner.requiresWifi(direct, creds, phoneHotspotOn = false))
    }

    @Test fun unknownCarBehavesLikeDirect() {
        assertEquals(ConnectPlanner.plan(direct, creds, false), ConnectPlanner.plan(null, creds, false))
    }

    @Test fun hotspotCarWithHotspotOnDiscoversThroughEspJoinBudgetThenDirect() {
        val p = ConnectPlanner.plan(hotspot, creds, phoneHotspotOn = true)
        assertTrue(p.discover)
        // ESP 開機最多花 12 s 加入熱點（b570_netsm.h JOIN_BUDGET_MS）；已改開直連 AP 的車再試熱點要
        // FIRST_IDLE_MS 12 s / FAST_IDLE_MS 6 s + 找熱點與加入 2–5 s：找車要涵蓋它，否則直連會去等隨後消失的 AP
        assertEquals(20_000L, ConnectPlanner.HOTSPOT_JOIN_DISCOVERY_MS)
        assertTrue(ConnectPlanner.HOTSPOT_JOIN_DISCOVERY_MS >= 12_000L + 2_000L + 5_000L) // 第一次重試 + 開 AP 選頻 + 加入
        assertEquals(ConnectPlanner.HOTSPOT_JOIN_DISCOVERY_MS, p.discoveryMs)
        assertTrue("car with a password can still go direct when it never shows up", p.direct)
        assertTrue(p.hotspotExpected)
        assertFalse(ConnectPlanner.requiresWifi(hotspot, creds, phoneHotspotOn = true))
    }

    @Test fun hotspotCarWithHotspotOffTriesBrieflyThenDirect() {
        val p = ConnectPlanner.plan(hotspot, creds, phoneHotspotOn = false)
        assertTrue(p.discover)
        assertEquals(ConnectPlanner.HOTSPOT_OFF_DISCOVERY_MS, p.discoveryMs)
        assertTrue(p.direct)
        assertTrue(p.hotspotExpected)
    }

    @Test fun directCarButPhoneHotspotOnTriesDiscoveryFirst() {
        val p = ConnectPlanner.plan(direct, creds, phoneHotspotOn = true)
        assertTrue(p.discover)
        assertEquals(ConnectPlanner.OPPORTUNISTIC_DISCOVERY_MS, p.discoveryMs)
        assertTrue(p.direct)
        assertFalse(p.hotspotExpected)
    }

    @Test fun carWithoutApPasswordCanOnlyUseHotspot() {
        val noPw = WifiCredentials("B570-A1B2", "")
        for (on in listOf(true, false)) {
            val p = ConnectPlanner.plan(hotspot.copy(password = ""), noPw, phoneHotspotOn = on)
            val ms = if (on) ConnectPlanner.HOTSPOT_JOIN_DISCOVERY_MS else ConnectPlanner.HOTSPOT_DISCOVERY_MS
            assertEquals(ConnectPlan(discover = true, discoveryMs = ms, direct = false, hotspotExpected = true), p)
        }
    }

    @Test fun forceDirectAfterQrScan() {
        assertEquals(
            ConnectPlan(discover = false, discoveryMs = 0, direct = true, hotspotExpected = false),
            ConnectPlanner.plan(hotspot, creds, phoneHotspotOn = true, forceDirect = true),
        )
        // 沒有密碼不能直連：仍然找車
        assertTrue(ConnectPlanner.plan(null, WifiCredentials("B570-A1B2", ""), phoneHotspotOn = true, forceDirect = true).discover)
    }

    @Test fun planToLinkRequest() {
        val r = ConnectPlanner.plan(hotspot, creds, phoneHotspotOn = false).toRequest(creds, hotspot.lastIp)
        assertEquals(
            LinkRequest(creds, discover = true, discoveryTimeoutMs = 1_500, direct = true, lastIp = "192.168.43.57", hotspotExpected = true),
            r,
        )
        assertTrue(r.discoveryTimeoutMs <= LinkRequest.DEFAULT_DISCOVERY_MS)
        val on = ConnectPlanner.plan(hotspot, creds, phoneHotspotOn = true).toRequest(creds, hotspot.lastIp)
        assertEquals(20_000L, on.discoveryTimeoutMs)
    }

    @Test fun savedCarHelpers() {
        assertTrue(direct.canDirect)
        assertFalse(direct.copy(password = "").canDirect)
        assertEquals(creds, direct.credentials)
        assertFalse(hotspot.toString().contains("pass1234"))
    }

    @Test fun hotspotSwitchMessages() {
        val s = HotspotSwitchStatus("MyPhone", HotspotSwitchPhase.WAITING_HOTSPOT, false, 0, 60_000)
        assertTrue(s.messageZh.contains("MyPhone"))
        assertTrue(s.messageZh.contains("2.4 GHz")) // 不換行空白：「2.4」與「GHz」不會被拆到兩行
        assertTrue(s.isActive)
        assertFalse(s.copy(phase = HotspotSwitchPhase.FAILED).isActive)
        HotspotSwitchPhase.entries.forEach { assertTrue(s.copy(phase = it).messageZh.isNotBlank()) }
    }
}
