package com.b570.pilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 記住的車子 JSON：舊版資料（沒有熱點欄位）照常讀得到，新欄位存得回來。（org.json 需要 Robolectric。） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedCarsCodecTest {

    @Test fun legacyJsonWithoutHotspotFields() {
        val legacy = """[{"ssid":"B570-AAAA","pw":"password12","nick":"小藍","last":200},{"ssid":"B570-BBBB","pw":"abcd1234","nick":"","last":300}]"""
        val cars = SavedCarsCodec.decode(legacy)
        assertEquals(listOf("B570-BBBB", "B570-AAAA"), cars.map { it.ssid }) // 最近連線在前
        val a = cars.single { it.ssid == "B570-AAAA" }
        assertEquals("password12", a.password)
        assertEquals("小藍", a.nickname)
        assertEquals(200L, a.lastConnected)
        assertFalse(a.hotspotMode)
        assertNull(a.lastIp)
        assertEquals("", a.hotspotSsid)
    }

    @Test fun roundTripWithHotspotFields() {
        val cars = listOf(
            SavedCar("B570-A1B2", "pass1234", "客廳", 500L, hotspotMode = true, lastIp = "192.168.43.57", hotspotSsid = "我的手機"),
            SavedCar("B570-7F3A", "", "", 100L), // 從熱點上找到、沒有 AP 密碼
        )
        val back = SavedCarsCodec.decode(SavedCarsCodec.encode(cars))
        assertEquals(cars, back)
    }

    @Test fun malformedInputIsTolerated() {
        assertEquals(emptyList<SavedCar>(), SavedCarsCodec.decode(null))
        assertEquals(emptyList<SavedCar>(), SavedCarsCodec.decode("not json"))
        // 單筆壞掉（缺 ssid / pw）只略過那一筆
        val cars = SavedCarsCodec.decode("""[{"pw":"x"},{"ssid":"B570-AAAA"},{"ssid":"B570-CCCC","pw":"password12","hs":true,"ip":null}]""")
        assertEquals(1, cars.size)
        assertTrue(cars[0].hotspotMode)
        assertNull(cars[0].lastIp)
    }
}
