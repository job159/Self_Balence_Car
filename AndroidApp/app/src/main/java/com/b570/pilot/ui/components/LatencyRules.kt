package com.b570.pilot.ui.components

import com.b570.pilot.data.LinkStats
import com.b570.pilot.link.LinkMode
import java.util.Locale

/*
 * 延遲的顯示規則與診斷（純函式，單元測試 LatencyRulesTest）。
 * 駕駛 HUD、狀態條、滑入式導覽的車況面板、監測頁「延遲診斷」共用，同一組門檻、同一套說法。
 *
 * 名詞：
 * - 控制延遲 = 手機送出 CTRL → 車上真正套用（+ 回報那筆遙測的下行時間）。需要 v1.1 主控板韌體（TELEM 42 bytes）。
 * - 端到端（來回）= PING → PONG（手機 ⇄ ESP ⇄ STM32）。
 * - Wi-Fi 來回 = ESP_PING → ESP_PONG（只有手機 ⇄ Wi-Fi 模組）。
 */

/** 延遲燈號。 */
enum class LatencyLevel { UNKNOWN, GOOD, FAIR, POOR }

/** 延遲判斷的門檻（ms / %）。 */
object LatencyRules {
    /** < 40 ms：綠（跟手）。 */
    const val GOOD_MS = 40f

    /** < 100 ms：黃（感覺得到），其餘紅。 */
    const val FAIR_MS = 100f

    /** Wi-Fi 來回 ≥ 30 ms：Wi-Fi 本身偏慢（正常直連 / 熱點約 2–15 ms）。 */
    const val WIFI_SLOW_MS = 30f

    /** 控制延遲比 Wi-Fi 來回多出 ≥ 30 ms：慢在車上（序列埠 / STM32 處理）。正常只多 3–15 ms。 */
    const val CAR_EXCESS_MS = 30f

    /** 上行遺失 ≥ 10 %：手機送出的指令常常沒到車上。 */
    const val LOSS_HIGH_PCT = 10f

    /** P95 ≥ 100 ms（平均還可以）：偶爾卡頓。 */
    const val SPIKE_P95_MS = 100f
}

/** 延遲 → 燈號：綠 < 40、黃 < 100、紅 ≥ 100；null / NaN / 負數 = 未知。 */
fun latencyLevelOf(ms: Float?): LatencyLevel = when {
    ms == null || ms.isNaN() || ms < 0f -> LatencyLevel.UNKNOWN
    ms < LatencyRules.GOOD_MS -> LatencyLevel.GOOD
    ms < LatencyRules.FAIR_MS -> LatencyLevel.FAIR
    else -> LatencyLevel.POOR
}

/** 毫秒數字（四捨五入、不含單位）："23"；沒有資料 = "--"。 */
fun formatMs(ms: Float?): String =
    if (ms == null || ms.isNaN() || ms.isInfinite()) "--" else String.format(Locale.US, "%.0f", ms.coerceAtLeast(0f))

/** 百分比（一位小數）："0.5"；沒有資料 = "--"。 */
fun formatPercent(pct: Float?): String =
    if (pct == null || pct.isNaN() || pct.isInfinite()) "--" else String.format(Locale.US, "%.1f", pct.coerceIn(0f, 100f))

/**
 * HUD 上的主要延遲數字。
 * @param isControl true = 控制延遲（「控制 23 ms」）；false = 舊主控板韌體量不到控制延遲，退回 PING 來回（「延遲 18 ms」）
 */
data class HudLatency(val labelZh: String, val ms: Float?, val isControl: Boolean) {
    val level: LatencyLevel get() = latencyLevelOf(ms)

    /** 「控制 23 ms」/「延遲 18 ms」/「控制 -- ms」。 */
    val textZh: String get() = "$labelZh ${formatMs(ms)} ms"

    /** 無障礙念法。 */
    val descriptionZh: String
        get() = when {
            ms == null -> "${if (isControl) "控制延遲" else "延遲"}量測中"
            else -> "${if (isControl) "控制延遲" else "來回延遲"} ${formatMs(ms)} 毫秒"
        }
}

/** 主要延遲：有控制延遲用它，否則用 PING 來回（= [LinkStats.displayLatencyMs]），都沒有 =「控制 -- ms」。 */
fun hudLatencyOf(stats: LinkStats): HudLatency {
    val ctrl = stats.controlLatencyMs
    val rtt = stats.rttMs
    return when {
        ctrl != null -> HudLatency("控制", ctrl, isControl = true)
        rtt != null -> HudLatency("延遲", rtt, isControl = false)
        else -> HudLatency("控制", null, isControl = true)
    }
}

/**
 * 點開延遲膠囊後的細節（一行）：「控制 23 · P95 41 · 來回 18 · Wi-Fi 6 ms」。
 * 只列有資料的項目；全部沒有 =「延遲量測中…」。
 */
fun latencyDetailZh(stats: LinkStats): String {
    val parts = ArrayList<String>(4)
    stats.controlLatencyMs?.let { parts += "控制 ${formatMs(it)}" }
    stats.controlLatencyP95Ms?.let { parts += "P95 ${formatMs(it)}" }
    stats.rttMs?.let { parts += "來回 ${formatMs(it)}" }
    stats.espRttMs?.let { parts += "Wi-Fi ${formatMs(it)}" }
    return if (parts.isEmpty()) "延遲量測中…" else parts.joinToString(" · ") + " ms"
}

/** 連線方式的短標籤（HUD 膠囊）：熱點 / 直連。 */
fun linkModeShortZh(mode: LinkMode): String = when (mode) {
    LinkMode.HOTSPOT -> "熱點"
    LinkMode.DIRECT -> "直連"
}

// ====================================================================== 診斷

/** 診斷語氣（顏色）。 */
enum class DiagnosisTone { NEUTRAL, OK, INFO, WARN, BAD }

/** 延遲的主要原因（依優先順序判斷，只回一個）。 */
enum class LatencyCause {
    NOT_CONNECTED,
    MEASURING,
    WIFI_SLOW,
    DUAL_WIFI,
    CAR_SLOW,
    UPLINK_LOSS,
    HIGH,
    SPIKES,
    OLD_FIRMWARE,
    FAIR,
    OK,
}

/** 一句白話診斷：先說原因、再說怎麼做。 */
data class LatencyDiagnosis(val cause: LatencyCause, val tone: DiagnosisTone, val textZh: String)

/**
 * 依延遲數據判斷「慢在哪裡」（監測頁「延遲診斷」與車況面板用）。判斷順序：
 * 1. 沒連線 / 還沒有任何數據
 * 2. Wi-Fi 來回 ≥ 30 ms → Wi-Fi 慢（同時連兩個 Wi-Fi → 雙 Wi-Fi 提示；熱點模式 → 車子靠近手機）
 * 3. 控制延遲 ≥ 40 且比 Wi-Fi 來回多 ≥ 30 ms → 車上處理慢（更新 STM32 韌體）
 * 4. 手機同時連著其他 Wi-Fi（延遲還沒變高也提醒）
 * 5. 上行遺失 ≥ 10 %
 * 6. 控制延遲 ≥ 100 ms（不知道 Wi-Fi 來回）
 * 7. P95 ≥ 100 ms → 偶爾卡頓
 * 8. 主控板韌體不回報 ctrl_seq（舊版）→ 只能看來回延遲
 * 9. 控制延遲 40–100 ms → 稍高
 * 10. 正常
 *
 * @param connected 已連線（Connected）
 * @param ctrlSeqFirmware 主控板遙測有 ctrl_seq（v1.1）；null = 還沒收到遙測
 */
fun latencyDiagnosisOf(
    stats: LinkStats,
    mode: LinkMode?,
    connected: Boolean,
    ctrlSeqFirmware: Boolean? = null,
): LatencyDiagnosis {
    if (!connected) return LatencyDiagnosis(LatencyCause.NOT_CONNECTED, DiagnosisTone.NEUTRAL, "連線後開始量測延遲")
    val ctrl = stats.controlLatencyMs.valid()
    val p95 = stats.controlLatencyP95Ms.valid()
    val rtt = stats.rttMs.valid()
    val wifi = stats.espRttMs.valid()
    val loss = stats.uplinkLossPercent.valid()
    val hotspot = mode == LinkMode.HOTSPOT
    if (ctrl == null && rtt == null && wifi == null) {
        return LatencyDiagnosis(LatencyCause.MEASURING, DiagnosisTone.NEUTRAL, "延遲量測中…")
    }
    val overall = latencyLevelOf(ctrl ?: rtt ?: wifi)
    val severe = if (overall == LatencyLevel.POOR) DiagnosisTone.BAD else DiagnosisTone.WARN

    if (wifi != null && wifi >= LatencyRules.WIFI_SLOW_MS) {
        return when {
            stats.dualWifi -> LatencyDiagnosis(LatencyCause.DUAL_WIFI, severe, "手機同時連著其他 Wi-Fi，延遲會變高：中斷其他 Wi-Fi，或改用熱點模式")
            hotspot -> LatencyDiagnosis(LatencyCause.WIFI_SLOW, severe, "Wi-Fi 延遲偏高：讓車子靠近手機，熱點請設 2.4\u00A0GHz")
            else -> LatencyDiagnosis(LatencyCause.WIFI_SLOW, severe, "Wi-Fi 延遲偏高：關閉手機其他 Wi-Fi 或改用熱點模式")
        }
    }
    if (ctrl != null && wifi != null && ctrl >= LatencyRules.GOOD_MS && ctrl - wifi >= LatencyRules.CAR_EXCESS_MS) {
        return LatencyDiagnosis(LatencyCause.CAR_SLOW, severe, "車上處理慢：請確認已更新 STM32 韌體")
    }
    if (stats.dualWifi) {
        return LatencyDiagnosis(LatencyCause.DUAL_WIFI, DiagnosisTone.WARN, "手機同時連著其他 Wi-Fi，延遲會變高")
    }
    if (loss != null && loss >= LatencyRules.LOSS_HIGH_PCT) {
        val where = if (hotspot) "讓車子靠近手機" else "讓手機靠近車子"
        return LatencyDiagnosis(LatencyCause.UPLINK_LOSS, DiagnosisTone.WARN, "指令遺失 ${formatPercent(loss)} %：$where、避開干擾")
    }
    if (ctrl != null && ctrl >= LatencyRules.FAIR_MS) {
        return LatencyDiagnosis(LatencyCause.HIGH, DiagnosisTone.BAD, "控制延遲偏高：先確認 STM32 韌體已更新，再試熱點模式")
    }
    if (p95 != null && p95 >= LatencyRules.SPIKE_P95_MS) {
        val fix = if (hotspot) "讓車子靠近手機" else "靠近車子或改用熱點模式"
        return LatencyDiagnosis(LatencyCause.SPIKES, DiagnosisTone.WARN, "偶爾卡頓（P95 ${formatMs(p95)} ms）：多半是 Wi-Fi 干擾，$fix")
    }
    if (ctrl == null) {
        return if (ctrlSeqFirmware == false) {
            LatencyDiagnosis(
                LatencyCause.OLD_FIRMWARE,
                if (overall == LatencyLevel.POOR) DiagnosisTone.BAD else DiagnosisTone.INFO,
                "主控板韌體較舊，量不到控制延遲（目前顯示來回延遲）：請更新 STM32 韌體",
            )
        } else {
            LatencyDiagnosis(LatencyCause.MEASURING, DiagnosisTone.NEUTRAL, "控制延遲量測中…")
        }
    }
    if (latencyLevelOf(ctrl) == LatencyLevel.FAIR) {
        val fix = if (hotspot) "讓車子靠近手機可以再降低" else "靠近車子或改用熱點模式可以再降低"
        return LatencyDiagnosis(LatencyCause.FAIR, DiagnosisTone.INFO, "延遲稍高但可操控：$fix")
    }
    return LatencyDiagnosis(LatencyCause.OK, DiagnosisTone.OK, "延遲正常，操控很跟手")
}

private fun Float?.valid(): Float? = this?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0f }
