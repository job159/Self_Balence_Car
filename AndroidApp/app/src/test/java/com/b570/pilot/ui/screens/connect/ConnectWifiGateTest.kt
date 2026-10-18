package com.b570.pilot.ui.screens.connect

import com.b570.pilot.data.SavedCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 連線前的 Wi-Fi 這一關：剛掃 QR 先說明再開面板、重新連線直接開面板；Wi-Fi 關閉卡的說明。 */
class ConnectWifiGateTest {

    @Test fun wifiOnAlwaysConnects() {
        for (direct in listOf(false, true)) for (waiting in listOf(false, true)) for (hs in listOf(false, true)) {
            assertEquals(WifiGate.CONNECT, wifiGateOf(wifiOn = true, pendingDirect = direct, waitingWifi = waiting, hotspotCar = hs))
        }
    }

    @Test fun rescanHotspotCarAsksForHotspotNotWifi() {
        // 熱點模式的車 OLED 顯示 QR = 還沒找到手機熱點：請他開熱點，不說「第一次配對」、不跳 Wi-Fi 面板
        assertEquals(WifiGate.EXPLAIN_HOTSPOT_CAR, wifiGateOf(wifiOn = false, pendingDirect = true, waitingWifi = false, hotspotCar = true))
        // 已經在等 Wi-Fi（使用者自己選了直連）：照樣等
        assertEquals(WifiGate.WAIT, wifiGateOf(wifiOn = false, pendingDirect = true, waitingWifi = true, hotspotCar = true))
        // 重新連線（不是剛掃 QR）不受影響
        assertEquals(WifiGate.OPEN_PANEL, wifiGateOf(wifiOn = false, pendingDirect = false, waitingWifi = false, hotspotCar = true))
        assertEquals(WifiGate.EXPLAIN_FIRST_PAIR, wifiGateOf(wifiOn = false, pendingDirect = true, waitingWifi = false, hotspotCar = false))
    }

    @Test fun hotspotCarCopy() {
        assertEquals("車子沒找到手機熱點", HOTSPOT_CAR_TITLE_ZH)
        val off = hotspotCarMessageZh(phoneHotspotOn = false)
        assertTrue(off.startsWith("打開手機熱點，"))
        assertTrue(off.contains("約 20 秒內") && off.contains("不用開 Wi-Fi"))
        assertTrue(hotspotCarMessageZh(phoneHotspotOn = true).startsWith("手機熱點已開著："))
        assertEquals("開啟熱點", hotspotCarConfirmZh(phoneHotspotOn = false))
        assertEquals("在熱點上找車", hotspotCarConfirmZh(phoneHotspotOn = true))
    }

    @Test fun firstPairPromiseOnlyForNewCars() {
        val fresh = SavedCar("B570-A1B2", "password1") // 剛掃 QR：已記住、還沒連上過
        assertTrue(firstPairPromiseOf(null, emptySet()))
        assertTrue(firstPairPromiseOf(fresh, emptySet()))
        assertFalse(firstPairPromiseOf(fresh.copy(lastConnected = 100), emptySet()))          // 連過的車
        assertFalse(firstPairPromiseOf(fresh, setOf("B570-A1B2")))                             // 回答過邀請
        assertTrue(firstPairPromiseOf(fresh, setOf("B570-ZZZZ")))
        assertFalse(firstPairPromiseOf(fresh.copy(hotspotMode = true), emptySet()))
        assertFalse(firstPairPromiseOf(fresh.copy(hotspotSsid = "MyPhone"), emptySet()))       // 設定過熱點
        assertEquals("第一次配對要開一下 Wi-Fi", wifiFirstPairTitleZh(promise = true))
        assertEquals("要開一下 Wi-Fi", wifiFirstPairTitleZh(promise = false))
    }

    @Test fun firstPairExplainsBeforeOpeningPanel() {
        // 剛掃 QR、Wi-Fi 關著：不直接跳系統面板，先說明
        assertEquals(WifiGate.EXPLAIN_FIRST_PAIR, wifiGateOf(wifiOn = false, pendingDirect = true, waitingWifi = false))
    }

    @Test fun reconnectSavedCarOpensPanelDirectly() {
        // 記住的直連車重新連線：維持原本行為（直接開面板，開啟後自動連線）
        assertEquals(WifiGate.OPEN_PANEL, wifiGateOf(wifiOn = false, pendingDirect = false, waitingWifi = false))
    }

    @Test fun alreadyWaitingDoesNotReopen() {
        // 面板開過了（說明按了「開啟 Wi-Fi」或重新連線）：等廣播，不重複開
        assertEquals(WifiGate.WAIT, wifiGateOf(wifiOn = false, pendingDirect = true, waitingWifi = true))
        assertEquals(WifiGate.WAIT, wifiGateOf(wifiOn = false, pendingDirect = false, waitingWifi = true))
    }

    @Test fun firstPairMessage() {
        val plain = wifiFirstPairMessageZh(phoneHotspotOn = false)
        assertEquals(
            "只要打開 Wi-Fi 開關，不用連任何網路，手機照常用行動網路上網。連上車子後會帶你改用熱點模式：以後帶出去只開手機熱點，Wi-Fi 可以一直關著。",
            plain,
        )
        assertFalse(plain.contains("自動關掉熱點"))
        // 手機熱點開著：多一句（有些手機開 Wi-Fi 會關掉熱點）
        val hot = wifiFirstPairMessageZh(phoneHotspotOn = true)
        assertTrue(hot.startsWith(plain))
        // 熱點等熱點模式設定好再打開（先開會讓不能同時開 Wi-Fi 與熱點的手機中斷直連）
        assertTrue(hot.endsWith("有些手機開 Wi-Fi 會自動關掉熱點，沒關係，設定好熱點模式再打開。"))
        // 不會邀請改用熱點模式的車：不承諾「連上後會帶你改用熱點模式」
        val noPromise = wifiFirstPairMessageZh(phoneHotspotOn = false, promise = false)
        assertEquals("只要打開 Wi-Fi 開關，不用連任何網路，手機照常用行動網路上網。", noPromise)
        val noPromiseHot = wifiFirstPairMessageZh(phoneHotspotOn = true, promise = false)
        assertFalse(noPromiseHot.contains("熱點模式"))
        assertTrue(noPromiseHot.startsWith(noPromise) && noPromiseHot.contains("自動關掉熱點"))
    }

    @Test fun wifiOffCardOnlyForDirectUsersOrWhenWaiting() {
        fun show(wifi: Boolean = false, failedWifi: Boolean = false, waiting: Boolean = false, hot: Boolean = false, hsUser: Boolean = false) =
            showWifiOffCardOf(wifi, failedWifi, waiting, hot, hsUser)
        assertTrue(show())                                   // 直連使用者、Wi-Fi 關著
        assertFalse(show(wifi = true))
        assertFalse(show(failedWifi = true))                 // 失敗卡已在說 Wi-Fi
        assertFalse(show(failedWifi = true, waiting = true))
        assertFalse(show(hot = true))                        // 手機熱點開著
        assertFalse(show(hsUser = true))                     // 最近的車是熱點模式：不叫他開 Wi-Fi
        assertTrue(show(hsUser = true, waiting = true))      // 自己選了直連、正在等 Wi-Fi
        assertTrue(show(hot = true, waiting = true))
    }

    @Test fun wifiOffCardHint() {
        assertEquals("打開開關就好，不用連網路；開啟後自動連上「我的車」", wifiOffHintZh("我的車"))
        assertEquals("連車子只要打開 Wi-Fi 開關，不用連網路；改用熱點模式就不必開", wifiOffHintZh(null))
    }
}
