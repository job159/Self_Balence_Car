package com.b570.pilot.ui.screens.connect

import com.b570.pilot.data.SavedCar
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.DiscoveredCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 連線頁的熱點模式判斷：熱點上找到的車、卡片標記、開熱點提示、步驟條。 */
class ConnectHotspotTest {

    private val home = SavedCar("B570-AAAA", "password1", "", 100)
    private val hotspotCar = SavedCar("B570-BBBB", "password2", "", 50, hotspotMode = true, hotspotSsid = "我的熱點")
    private val noPassword = SavedCar("B570-CCCC", "", "", 10, hotspotMode = true)

    private fun found(ssid: String, busy: Boolean = false) = DiscoveredCar(ssid, "192.168.43.20", "00A1B2C3", viaHotspot = true, busy = busy)

    @Test fun badgeOnHotspotWins() {
        assertEquals(NearbyBadge.OnHotspot, nearbyBadgeOf("B570-BBBB", mapOf("B570-BBBB" to -50), true, onHotspot = setOf("B570-BBBB")))
    }

    @Test fun hotspotCarNotMarkedOutOfRange() {
        // 熱點模式的車不開自己的 Wi-Fi：Wi-Fi 掃描看不到不代表不在
        assertEquals(NearbyBadge.Unknown, nearbyBadgeOf("B570-BBBB", mapOf("B570-AAAA" to -50), scanUsable = true, hotspotMode = true))
        // 直連的車照舊
        assertEquals(NearbyBadge.OutOfRange, nearbyBadgeOf("B570-AAAA", mapOf("B570-ZZZZ" to -50), scanUsable = true))
        // 熱點模式的車改回直連（找不到熱點）時 AP 看得到 → 在附近
        assertEquals(NearbyBadge.InRange(-60), nearbyBadgeOf("B570-BBBB", mapOf("B570-BBBB" to -60), scanUsable = true, hotspotMode = true))
    }

    @Test fun newCarsOnHotspot() {
        val list = hotspotNewCarsOf(listOf(found("B570-ZZZZ"), found("B570-AAAA"), found("B570-1111"), found("")), listOf(home))
        assertEquals(listOf("B570-1111", "B570-ZZZZ"), list.map { it.ssid })
        assertTrue(hotspotNewCarsOf(emptyList(), listOf(home)).isEmpty())
    }

    @Test fun soleReachableIncludesHotspot() {
        assertEquals(hotspotCar, soleReachableCarOf(listOf(home, hotspotCar), emptyMap(), setOf("B570-BBBB")))
        assertEquals(home, soleReachableCarOf(listOf(home, hotspotCar), mapOf("B570-AAAA" to -50), emptySet()))
        assertNull(soleReachableCarOf(listOf(home, hotspotCar), mapOf("B570-AAAA" to -50), setOf("B570-BBBB")))
        assertNull(soleReachableCarOf(listOf(home), emptyMap(), emptySet()))
    }

    @Test fun discoverWhenHotspotOnOrHotspotCars() {
        assertTrue(shouldDiscoverHotspot(phoneHotspotOn = true, saved = listOf(home)))
        assertTrue(shouldDiscoverHotspot(phoneHotspotOn = false, saved = listOf(home, hotspotCar)))
        assertFalse(shouldDiscoverHotspot(phoneHotspotOn = false, saved = listOf(home)))
        assertFalse(shouldDiscoverHotspot(phoneHotspotOn = false, saved = emptyList()))
    }

    @Test fun hotspotHint() {
        assertTrue(needsHotspotHint(hotspotCar, phoneHotspotOn = false, badge = NearbyBadge.Unknown))
        assertTrue(needsHotspotHint(noPassword, phoneHotspotOn = false, badge = NearbyBadge.Unknown))
        assertFalse(needsHotspotHint(hotspotCar, phoneHotspotOn = true, badge = NearbyBadge.Unknown))
        assertFalse(needsHotspotHint(home, phoneHotspotOn = false, badge = NearbyBadge.Unknown))
        // 已經找到了（熱點上 / 改回直連的 AP）就不用提示
        assertFalse(needsHotspotHint(hotspotCar, phoneHotspotOn = false, badge = NearbyBadge.OnHotspot))
        assertFalse(needsHotspotHint(hotspotCar, phoneHotspotOn = false, badge = NearbyBadge.InRange(-60)))
    }

    @Test fun stepperTitles() {
        assertEquals(listOf("請求 Wi-Fi", "等待系統確認", "握手", "完成"), stepTitlesOf(ConnectPath.DIRECT))
        assertEquals(listOf("在熱點找車", "握手", "完成"), stepTitlesOf(ConnectPath.HOTSPOT))
    }

    @Test fun stepperSteps() {
        val connected = ConnectionState.Connected(null, 0L)
        // 熱點：找車 → 握手 → 完成
        assertEquals(0, connectStepOf(ConnectionState.Discovering("B570-BBBB"), ConnectPath.HOTSPOT, false, 0))
        assertEquals(1, connectStepOf(ConnectionState.Handshaking, ConnectPath.HOTSPOT, false, 0))
        assertEquals(3, connectStepOf(connected, ConnectPath.HOTSPOT, false, 1))
        // 直連：請求 → 等系統確認 → 握手 → 完成
        assertEquals(0, connectStepOf(ConnectionState.RequestingWifi("B570-AAAA"), ConnectPath.DIRECT, false, 0))
        assertEquals(1, connectStepOf(ConnectionState.RequestingWifi("B570-AAAA"), ConnectPath.DIRECT, true, 0))
        assertEquals(2, connectStepOf(ConnectionState.Handshaking, ConnectPath.DIRECT, true, 1))
        assertEquals(4, connectStepOf(connected, ConnectPath.DIRECT, true, 2))
        // 退場中（失敗）沿用上一步
        assertEquals(2, connectStepOf(ConnectionState.Failed("x"), ConnectPath.DIRECT, false, 2))
    }
}
