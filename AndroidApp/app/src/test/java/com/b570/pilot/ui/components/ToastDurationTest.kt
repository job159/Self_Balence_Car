package com.b570.pilot.ui.components

import com.b570.pilot.data.HOTSPOT_SWITCH_DONE_ZH
import com.b570.pilot.data.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提示停留時間：短句照舊，長句（例如切換熱點模式完成的說明）延長到看得完。 */
class ToastDurationTest {

    @Test fun shortMessagesKeepDefaults() {
        assertEquals(2_600L, toastDurationMs(Severity.SUCCESS, "已連線 B570-A1B2".length))
        assertEquals(2_600L, toastDurationMs(Severity.INFO, 0))
        assertEquals(4_000L, toastDurationMs(Severity.DANGER, 8))
    }

    @Test fun longMessagesStayLongerButCapped() {
        val done = toastDurationMs(Severity.SUCCESS, HOTSPOT_SWITCH_DONE_ZH.length)
        assertTrue("$done", done >= 5_000L)
        assertEquals(6_000L, toastDurationMs(Severity.WARNING, 500))
        assertEquals(6_000L, toastDurationMs(Severity.DANGER, 500))
    }
}
