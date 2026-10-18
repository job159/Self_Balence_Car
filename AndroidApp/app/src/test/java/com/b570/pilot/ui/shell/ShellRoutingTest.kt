package com.b570.pilot.ui.shell

import com.b570.pilot.data.ReconnectStatus
import com.b570.pilot.link.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** shell 的根頁面判斷（自動重連中不跳回連線頁）與重連提示文字。 */
class ShellRoutingTest {

    private val failed = ConnectionState.Failed("車子的 Wi-Fi 中斷了（可能已關機或距離太遠）")

    @Test fun connectedGoesToMain() {
        assertEquals(RootRoute.MAIN, rootRouteOf(ConnectionState.Connected(null, 0L), reconnecting = false))
        // 重連成功的那一刻 reconnect 可能還沒清掉：一樣回主畫面
        assertEquals(RootRoute.MAIN, rootRouteOf(ConnectionState.Connected(null, 0L), reconnecting = true))
    }

    @Test fun reconnectingStaysOnCurrentTab() {
        // 每次嘗試之間是 Failed，嘗試前連線層會短暫回 Idle，嘗試中是連線中：都不能跳頁
        for (st in listOf(failed, ConnectionState.Idle, ConnectionState.RequestingWifi("B570-AAAA"), ConnectionState.Handshaking)) {
            assertEquals("$st", RootRoute.STAY, rootRouteOf(st, reconnecting = true))
        }
    }

    @Test fun gaveUpOrUserDisconnectGoesToConnect() {
        assertEquals(RootRoute.CONNECT, rootRouteOf(failed, reconnecting = false))
        assertEquals(RootRoute.CONNECT, rootRouteOf(ConnectionState.Idle, reconnecting = false))
    }

    @Test fun linkLostAndConnectingStay() {
        assertEquals(RootRoute.STAY, rootRouteOf(ConnectionState.LinkLost(0L), reconnecting = false))
        assertEquals(RootRoute.STAY, rootRouteOf(ConnectionState.RequestingWifi("B570-AAAA"), reconnecting = false))
        assertEquals(RootRoute.STAY, rootRouteOf(ConnectionState.Handshaking, reconnecting = false))
    }

    @Test fun graceIsShort() {
        assertTrue(ROUTE_FAILED_GRACE_MS in 100L..500L)
    }

    @Test fun reconnectTexts() {
        val s = ReconnectStatus("B570-AAAA", attempt = 2, maxAttempts = 6, nextAtMs = 10_000L)
        assertEquals("車子斷線，自動重連中（第 2/6 次）", reconnectTitleZh(s))
        assertEquals("自動重連中（2/6）", reconnectTitleZh(s, ReconnectTitle.SHORT))
        assertEquals("重連 2/6", reconnectTitleZh(s, ReconnectTitle.TINY))
        assertEquals("3 秒後重試", reconnectDetailZh(s, nowMs = 7_500L))
        assertEquals("1 秒後重試", reconnectDetailZh(s, nowMs = 9_999L))
        assertEquals("正在連線…", reconnectDetailZh(s, nowMs = 10_000L))
        assertEquals("正在連線…", reconnectDetailZh(s.copy(nextAtMs = 0L), nowMs = 7_500L))
        assertEquals(0, reconnectSecondsLeft(s, 12_000L))
        assertEquals(5, reconnectSecondsLeft(s, 5_000L))
    }

    @Test fun titleLengthByWidth() {
        assertEquals(ReconnectTitle.FULL, reconnectTitleLengthOf(560f, compact = true))
        assertEquals(ReconnectTitle.SHORT, reconnectTitleLengthOf(332f, compact = true))
        assertEquals(ReconnectTitle.TINY, reconnectTitleLengthOf(228f, compact = true))
        assertEquals(ReconnectTitle.SHORT, reconnectTitleLengthOf(208f, compact = false))
        assertEquals(ReconnectTitle.FULL, reconnectTitleLengthOf(400f, compact = false))
    }
}
