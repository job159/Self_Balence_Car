package com.b570.pilot.ui.components

import com.b570.pilot.data.LinkStats
import com.b570.pilot.link.LinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HUD 延遲顏色門檻、主要數字的退回規則、細節字串、延遲診斷的判斷順序。 */
class LatencyRulesTest {

    @Test fun levelThresholds() {
        assertEquals(LatencyLevel.UNKNOWN, latencyLevelOf(null))
        assertEquals(LatencyLevel.UNKNOWN, latencyLevelOf(Float.NaN))
        assertEquals(LatencyLevel.UNKNOWN, latencyLevelOf(-1f))
        assertEquals(LatencyLevel.GOOD, latencyLevelOf(0f))
        assertEquals(LatencyLevel.GOOD, latencyLevelOf(39.9f))
        assertEquals(LatencyLevel.FAIR, latencyLevelOf(40f)) // 綠 < 40
        assertEquals(LatencyLevel.FAIR, latencyLevelOf(99.9f))
        assertEquals(LatencyLevel.POOR, latencyLevelOf(100f)) // 黃 < 100，其餘紅
        assertEquals(LatencyLevel.POOR, latencyLevelOf(800f))
    }

    @Test fun formatting() {
        assertEquals("23", formatMs(23.4f))
        assertEquals("24", formatMs(23.5f))
        assertEquals("--", formatMs(null))
        assertEquals("--", formatMs(Float.POSITIVE_INFINITY))
        assertEquals("0", formatMs(-3f))
        assertEquals("1.5", formatPercent(1.46f))
        assertEquals("--", formatPercent(null))
        assertEquals("100.0", formatPercent(130f))
    }

    @Test fun hudPrefersControlLatency() {
        val h = hudLatencyOf(LinkStats(rttMs = 18f, controlLatencyMs = 23f))
        assertTrue(h.isControl)
        assertEquals("控制 23 ms", h.textZh)
        assertEquals(LatencyLevel.GOOD, h.level)
        assertEquals("控制延遲 23 毫秒", h.descriptionZh)
    }

    @Test fun hudFallsBackToRttOnOldFirmware() {
        val h = hudLatencyOf(LinkStats(rttMs = 120f))
        assertFalse(h.isControl)
        assertEquals("延遲 120 ms", h.textZh)
        assertEquals(LatencyLevel.POOR, h.level)
        // 跟 LinkStats.displayLatencyMs 一致
        assertEquals(LinkStats(rttMs = 120f).displayLatencyMs, h.ms)
    }

    @Test fun hudWithoutData() {
        val h = hudLatencyOf(LinkStats())
        assertEquals("控制 -- ms", h.textZh)
        assertEquals(LatencyLevel.UNKNOWN, h.level)
    }

    @Test fun detailLine() {
        assertEquals(
            "控制 23 · P95 41 · 來回 18 · Wi-Fi 6 ms",
            latencyDetailZh(LinkStats(rttMs = 18f, espRttMs = 6f, controlLatencyMs = 23f, controlLatencyP95Ms = 41f)),
        )
        assertEquals("來回 18 ms", latencyDetailZh(LinkStats(rttMs = 18f)))
        assertEquals("延遲量測中…", latencyDetailZh(LinkStats()))
    }

    @Test fun modeLabels() {
        assertEquals("熱點", linkModeShortZh(LinkMode.HOTSPOT))
        assertEquals("直連", linkModeShortZh(LinkMode.DIRECT))
    }

    // ------------------------------------------------------------------ 診斷

    private fun diag(s: LinkStats, mode: LinkMode? = LinkMode.DIRECT, connected: Boolean = true, fw: Boolean? = true) =
        latencyDiagnosisOf(s, mode, connected, fw)

    @Test fun notConnectedAndMeasuring() {
        assertEquals(LatencyCause.NOT_CONNECTED, diag(LinkStats(controlLatencyMs = 20f), connected = false).cause)
        assertEquals(LatencyCause.MEASURING, diag(LinkStats()).cause)
        // 新韌體但控制延遲還沒有樣本：量測中（不要說韌體太舊）
        val m = diag(LinkStats(rttMs = 15f, espRttMs = 5f), fw = true)
        assertEquals(LatencyCause.MEASURING, m.cause)
        assertEquals(DiagnosisTone.NEUTRAL, m.tone)
    }

    @Test fun carSideSlowWhenWifiIsFast() {
        // 使用者回報的情況：Wi-Fi 很快、控制延遲很高 → 車上處理慢（STM32 韌體）
        val d = diag(LinkStats(rttMs = 140f, espRttMs = 6f, controlLatencyMs = 150f, controlLatencyP95Ms = 220f))
        assertEquals(LatencyCause.CAR_SLOW, d.cause)
        assertEquals("車上處理慢：請確認已更新 STM32 韌體", d.textZh)
        assertEquals(DiagnosisTone.BAD, d.tone)
        // 稍高（黃）也一樣判斷是車上
        val w = diag(LinkStats(espRttMs = 5f, controlLatencyMs = 60f))
        assertEquals(LatencyCause.CAR_SLOW, w.cause)
        assertEquals(DiagnosisTone.WARN, w.tone)
        // 只多一點（序列埠 + 控制週期，正常）→ 不是車上慢
        assertEquals(LatencyCause.OK, diag(LinkStats(espRttMs = 8f, controlLatencyMs = 22f)).cause)
    }

    @Test fun wifiSlowAdvice() {
        val d = diag(LinkStats(espRttMs = 45f, controlLatencyMs = 60f))
        assertEquals(LatencyCause.WIFI_SLOW, d.cause)
        assertEquals("Wi-Fi 延遲偏高：關閉手機其他 Wi-Fi 或改用熱點模式", d.textZh)
        // 熱點模式：不能再叫人改用熱點模式
        val h = diag(LinkStats(espRttMs = 45f, controlLatencyMs = 60f), mode = LinkMode.HOTSPOT)
        assertEquals(LatencyCause.WIFI_SLOW, h.cause)
        assertTrue(h.textZh.contains("靠近手機"))
        assertFalse(h.textZh.contains("改用熱點模式"))
        // Wi-Fi 慢的時候不會誤判成車上慢（控制延遲高是 Wi-Fi 造成的）
        assertEquals(LatencyCause.WIFI_SLOW, diag(LinkStats(espRttMs = 80f, controlLatencyMs = 200f)).cause)
    }

    @Test fun dualWifiWarning() {
        // 雙 Wi-Fi 造成 Wi-Fi 慢 → 雙 Wi-Fi 說明（含建議）
        val slow = diag(LinkStats(espRttMs = 60f, controlLatencyMs = 70f, dualWifi = true))
        assertEquals(LatencyCause.DUAL_WIFI, slow.cause)
        assertTrue(slow.textZh.startsWith("手機同時連著其他 Wi-Fi，延遲會變高"))
        // 延遲還沒變高也提醒
        val ok = diag(LinkStats(espRttMs = 5f, controlLatencyMs = 15f, dualWifi = true))
        assertEquals(LatencyCause.DUAL_WIFI, ok.cause)
        assertEquals("手機同時連著其他 Wi-Fi，延遲會變高", ok.textZh)
        assertEquals(DiagnosisTone.WARN, ok.tone)
    }

    @Test fun uplinkLoss() {
        val d = diag(LinkStats(espRttMs = 5f, controlLatencyMs = 20f, uplinkLossPercent = 12.5f))
        assertEquals(LatencyCause.UPLINK_LOSS, d.cause)
        assertEquals("指令遺失 12.5 %：讓手機靠近車子、避開干擾", d.textZh)
        assertEquals(LatencyCause.OK, diag(LinkStats(espRttMs = 5f, controlLatencyMs = 20f, uplinkLossPercent = 9.9f)).cause)
    }

    @Test fun highWithoutWifiData() {
        val d = diag(LinkStats(controlLatencyMs = 130f))
        assertEquals(LatencyCause.HIGH, d.cause)
        assertEquals(DiagnosisTone.BAD, d.tone)
    }

    @Test fun spikes() {
        val d = diag(LinkStats(espRttMs = 6f, controlLatencyMs = 25f, controlLatencyP95Ms = 140f))
        assertEquals(LatencyCause.SPIKES, d.cause)
        assertEquals("偶爾卡頓（P95 140 ms）：多半是 Wi-Fi 干擾，靠近車子或改用熱點模式", d.textZh)
    }

    @Test fun oldFirmware() {
        val d = diag(LinkStats(rttMs = 20f, espRttMs = 6f), fw = false)
        assertEquals(LatencyCause.OLD_FIRMWARE, d.cause)
        assertTrue(d.textZh.contains("更新 STM32 韌體"))
        assertEquals(DiagnosisTone.INFO, d.tone)
        assertEquals(DiagnosisTone.BAD, diag(LinkStats(rttMs = 150f), fw = false).tone)
    }

    @Test fun fairAndOk() {
        val f = diag(LinkStats(controlLatencyMs = 55f))
        assertEquals(LatencyCause.FAIR, f.cause)
        assertEquals(DiagnosisTone.INFO, f.tone)
        val ok = diag(LinkStats(rttMs = 15f, espRttMs = 5f, controlLatencyMs = 18f, controlLatencyP95Ms = 30f, uplinkLossPercent = 0f))
        assertEquals(LatencyCause.OK, ok.cause)
        assertEquals(DiagnosisTone.OK, ok.tone)
        assertEquals("延遲正常，操控很跟手", ok.textZh)
    }

    @Test fun garbageValuesIgnored() {
        // NaN / 無限大不能讓診斷誤判
        val d = diag(LinkStats(espRttMs = Float.NaN, controlLatencyMs = 20f, uplinkLossPercent = Float.POSITIVE_INFINITY))
        assertEquals(LatencyCause.OK, d.cause)
    }
}
