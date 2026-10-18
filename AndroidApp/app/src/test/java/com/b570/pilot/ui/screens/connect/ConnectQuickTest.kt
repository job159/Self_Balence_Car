package com.b570.pilot.ui.screens.connect

import com.b570.pilot.data.SavedCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 連線頁的快速連線判斷：附近標記、新車清單、一鍵連線、第一次連線提示。 */
class ConnectQuickTest {

    private fun car(ssid: String, last: Long = 0L) = SavedCar(ssid, "password1", "", last)

    private val home = car("B570-AAAA", last = 100)
    private val office = car("B570-BBBB", last = 50)

    @Test fun badges() {
        val nearby = mapOf("B570-AAAA" to -58)
        assertEquals(NearbyBadge.InRange(-58), nearbyBadgeOf("B570-AAAA", nearby, scanUsable = true))
        assertEquals(NearbyBadge.OutOfRange, nearbyBadgeOf("B570-BBBB", nearby, scanUsable = true))
        // 沒權限 / 還沒有掃描結果：不標記（不能說它不在範圍內）
        assertEquals(NearbyBadge.Unknown, nearbyBadgeOf("B570-BBBB", emptyMap(), scanUsable = false))
        assertEquals(NearbyBadge.InRange(-58), nearbyBadgeOf("B570-AAAA", nearby, scanUsable = false))
    }

    @Test fun unpairedHotspotsStrongestFirst() {
        val nearby = mapOf("B570-AAAA" to -40, "B570-1111" to -80, "B570-2222" to -50, "HomeWifi" to -30, "B570-3333" to -70, "B570-4444" to -90)
        val list = unpairedNearbyOf(nearby, listOf(home, office))
        assertEquals(listOf("B570-2222", "B570-3333", "B570-1111"), list.map { it.first })
        assertEquals(-50, list.first().second)
        assertTrue(unpairedNearbyOf(emptyMap(), listOf(home)).isEmpty())
        // 記住的車（就算改過名字）不會被當成新車
        assertTrue(unpairedNearbyOf(mapOf("B570-AAAA" to -40), listOf(home)).isEmpty())
    }

    @Test fun soleNearbyCar() {
        assertEquals(home, soleNearbyCarOf(listOf(home, office), mapOf("B570-AAAA" to -60, "B570-9999" to -50)))
        assertNull(soleNearbyCarOf(listOf(home, office), mapOf("B570-AAAA" to -60, "B570-BBBB" to -70)))
        assertNull(soleNearbyCarOf(listOf(home, office), emptyMap()))
        assertNull(soleNearbyCarOf(emptyList(), mapOf("B570-AAAA" to -60)))
    }

    @Test fun idleHero() {
        assertEquals(IdleHero.FIRST_PAIR, idleHeroOf(emptyList(), null))
        assertEquals(IdleHero.QUICK_CONNECT, idleHeroOf(listOf(home, office), home))
        assertEquals(IdleHero.SCAN, idleHeroOf(listOf(home, office), null))
    }

    @Test fun firstConnection() {
        assertTrue(isFirstConnection("B570-NEW1", listOf(home)))
        // 掃描後已記住、但還沒連上過：系統仍會跳確認視窗
        assertTrue(isFirstConnection("B570-NEW1", listOf(car("B570-NEW1"))))
        assertFalse(isFirstConnection("B570-AAAA", listOf(home)))
    }

    @Test fun signalLevels() {
        assertEquals(4, signalLevelOf(-50))
        assertEquals(3, signalLevelOf(-65))
        assertEquals(2, signalLevelOf(-70))
        assertEquals(1, signalLevelOf(-85))
        assertEquals(0, signalLevelOf(-95))
    }
}
