package com.b570.pilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 桌面捷徑：最近 3 台車 + 掃描配對、標籤長度、intent 解析。 */
class ShortcutsTest {

    private fun car(ssid: String, nick: String = "", last: Long = 0L) = SavedCar(ssid, "password1", nick, last)

    @Test fun threeMostRecentCarsThenScan() {
        val cars = listOf(car("B570-AAAA", last = 40), car("B570-BBBB", "小黑", 30), car("B570-CCCC", last = 20), car("B570-DDDD", last = 10))
        val plan = CarShortcuts.plan(cars)
        assertEquals(listOf("car:B570-AAAA", "car:B570-BBBB", "car:B570-CCCC", CarShortcuts.ID_SCAN), plan.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), plan.map { it.rank })
        assertEquals("連線 小黑", plan[1].shortLabel)
        assertEquals("連線 小黑", plan[1].longLabel)
        assertEquals(LaunchRequest.ConnectCar("B570-BBBB"), plan[1].request)
        assertEquals("掃描配對", plan.last().shortLabel)
        assertEquals(LaunchRequest.ScanPair, plan.last().request)
    }

    @Test fun noCarsOnlyScan() {
        val plan = CarShortcuts.plan(emptyList())
        assertEquals(1, plan.size)
        assertEquals(CarShortcuts.ID_SCAN, plan[0].id)
        assertEquals(0, plan[0].rank)
    }

    @Test fun systemLimitDropsCarsFirstKeepsScan() {
        val cars = listOf(car("B570-AAAA"), car("B570-BBBB"), car("B570-CCCC"))
        assertEquals(listOf("car:B570-AAAA", "car:B570-BBBB", CarShortcuts.ID_SCAN), CarShortcuts.plan(cars, maxTotal = 3).map { it.id })
        assertEquals(listOf(CarShortcuts.ID_SCAN), CarShortcuts.plan(cars, maxTotal = 1).map { it.id })
        assertTrue(CarShortcuts.plan(cars, maxTotal = 0).isEmpty())
    }

    @Test fun labelsRespectLauncherLimits() {
        val long = car("B570-1A2B", "我的超級無敵平衡車一號機二號機三號機四號機五號機六號機")
        val s = CarShortcuts.plan(listOf(long, car("B570-9F3C"))).first()
        assertTrue(s.shortLabel, s.shortLabel.length <= 10)
        assertTrue(s.longLabel, s.longLabel.length <= 25)
        assertTrue(s.longLabel.startsWith("連線 "))
        assertTrue(s.longLabel.endsWith("…"))
        // 「連線 B570-9F3C」超過 10 字 → 短標籤只放車名
        val plain = CarShortcuts.plan(listOf(car("B570-9F3C"))).first()
        assertEquals("B570-9F3C", plain.shortLabel)
        assertEquals("連線 B570-9F3C", plain.longLabel)
    }

    @Test fun duplicatesAndBlankSsidsSkipped() {
        val plan = CarShortcuts.plan(listOf(car("B570-AAAA"), car("B570-AAAA"), car(" "), car("B570-BBBB")))
        assertEquals(listOf("car:B570-AAAA", "car:B570-BBBB", CarShortcuts.ID_SCAN), plan.map { it.id })
    }

    @Test fun parseIntents() {
        assertEquals(LaunchRequest.ConnectCar("B570-AAAA"), CarShortcuts.parse(CarShortcuts.ACTION_CONNECT, " B570-AAAA "))
        assertNull(CarShortcuts.parse(CarShortcuts.ACTION_CONNECT, null))
        assertNull(CarShortcuts.parse(CarShortcuts.ACTION_CONNECT, ""))
        assertEquals(LaunchRequest.ScanPair, CarShortcuts.parse(CarShortcuts.ACTION_SCAN, null))
        assertNull(CarShortcuts.parse("android.intent.action.MAIN", null))
        assertNull(CarShortcuts.parse(null, "B570-AAAA"))
    }

    @Test fun idRoundTrip() {
        assertEquals("B570-AAAA", CarShortcuts.ssidOf(CarShortcuts.carId("B570-AAAA")))
        assertNull(CarShortcuts.ssidOf(CarShortcuts.ID_SCAN))
        assertNull(CarShortcuts.ssidOf("car:"))
    }
}
