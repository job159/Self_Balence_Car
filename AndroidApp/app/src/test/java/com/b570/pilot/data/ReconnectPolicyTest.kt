package com.b570.pilot.data

import com.b570.pilot.link.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun delaysGrowAndEnd() {
        val delays = (1..ReconnectPolicy.maxAttempts).map { ReconnectPolicy.delayBefore(it)!! }
        assertEquals(6, delays.size)
        assertTrue(delays.zipWithNext().all { (a, b) -> b > a })
        assertTrue(delays.first() <= 2_000L)
        assertTrue(delays.sum() in 30_000L..60_000L)
        assertNull(ReconnectPolicy.delayBefore(ReconnectPolicy.maxAttempts + 1))
        assertNull(ReconnectPolicy.delayBefore(0))
    }

    @Test fun stopsWhenAttemptNeededSystemApproval() {
        val unavailable = ConnectionState.Failed("連不上車子：車子未開機、密碼錯誤或已拒絕連線", retryable = true)
        // 一般暫時失敗：繼續重連
        assertTrue(ReconnectPolicy.shouldRetry(unavailable, approvalShown = false))
        // 這次嘗試跳出系統「連線到裝置」視窗卻沒連上（取消 / 逾時）：停止，不要每次重試都再跳一次
        assertFalse(ReconnectPolicy.shouldRetry(unavailable, approvalShown = true))
        // 不能重試 / Wi-Fi 關閉：停止
        assertFalse(ReconnectPolicy.shouldRetry(ConnectionState.Failed("格式錯誤", retryable = false), approvalShown = false))
        assertFalse(ReconnectPolicy.shouldRetry(ConnectionState.Failed("Wi-Fi 關閉", wifiDisabled = true), approvalShown = false))
    }

    @Test fun defaultsEnableQuickConnect() {
        val s = AppSettings()
        assertTrue(s.autoConnect)
        assertTrue(s.autoReconnect)
    }
}
