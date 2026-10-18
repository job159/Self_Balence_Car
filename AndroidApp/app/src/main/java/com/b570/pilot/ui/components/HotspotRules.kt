package com.b570.pilot.ui.components

import com.b570.pilot.data.HotspotSwitchPhase
import com.b570.pilot.protocol.StaInfo
import com.b570.pilot.protocol.StaState

/*
 * 熱點模式在畫面上的說法（純函式，單元測試 HotspotRulesTest）。設定頁「連線方式」、連線頁、切換提示共用。
 */

/** 車子熱點模式狀態的一行標題 + 細節。[busy] = 進行中（顯示轉圈 / 呼吸點）。 */
data class StaStatusUi(val titleZh: String, val detailZh: String?, val tone: DiagnosisTone, val busy: Boolean = false)

/** 訊號強度的白話：很好 / 良好 / 普通 / 微弱。 */
fun rssiWordZh(dbm: Int): String = when {
    dbm >= -60 -> "很好"
    dbm >= -70 -> "良好"
    dbm >= -80 -> "普通"
    else -> "微弱"
}

/**
 * STA_INFO → 顯示文字。
 * @param supported false = ESP 韌體太舊（回 UNKNOWN_TYPE）
 * @param connected 目前連著這台車（還沒讀到 STA_INFO 時顯示「讀取中」）
 * @param carApSsid 車子自己的 Wi-Fi 名稱（直連模式的說明）
 */
fun staStatusOf(sta: StaInfo?, supported: Boolean?, connected: Boolean, carApSsid: String? = null): StaStatusUi {
    if (supported == false) {
        return StaStatusUi("Wi-Fi 模組不支援熱點模式", "請先更新車上 ESP-01 的韌體", DiagnosisTone.WARN)
    }
    if (sta == null) {
        return if (connected) StaStatusUi("讀取車子的連線設定…", null, DiagnosisTone.NEUTRAL, busy = true)
        else StaStatusUi("連線到車子後顯示目前狀態", null, DiagnosisTone.NEUTRAL)
    }
    if (!sta.enabled) {
        val ap = carApSsid?.takeIf { it.isNotBlank() }
        return StaStatusUi(
            "直連模式",
            if (ap != null) "車子開自己的 Wi-Fi「$ap」，手機直接連" else "車子開自己的 Wi-Fi，手機直接連",
            DiagnosisTone.NEUTRAL,
        )
    }
    val name = sta.ssid.ifBlank { "手機熱點" }
    return when (sta.state) {
        StaState.CONNECTED -> StaStatusUi(
            "已連上熱點「$name」",
            listOfNotNull(sta.carIp?.let { "IP $it" }, sta.rssiDbm?.let { "訊號 $it dBm（${rssiWordZh(it)}）" })
                .joinToString(" · ").ifEmpty { null },
            DiagnosisTone.OK,
        )
        StaState.SEARCHING -> StaStatusUi("正在搜尋熱點「$name」…", "請開啟手機熱點（2.4\u00A0GHz）", DiagnosisTone.WARN, busy = true)
        StaState.FALLBACK -> StaStatusUi(
            "找不到熱點「$name」，已改回直連",
            "手機沒連著車子時車子會自動再試；請確認熱點是 2.4\u00A0GHz、名稱密碼正確",
            DiagnosisTone.WARN,
        )
        StaState.OFF -> StaStatusUi("熱點模式已設定", "車子重新開機後會加入熱點「$name」", DiagnosisTone.INFO)
        StaState.UNKNOWN -> StaStatusUi("熱點模式", "狀態未知（車子韌體版本不同）", DiagnosisTone.NEUTRAL)
    }
}

/** 切換到熱點模式的三個步驟（切換提示的進度點）。 */
val HOTSPOT_SWITCH_STEPS: List<String> = listOf("車子重開機", "開啟熱點", "尋找車子")

/** 切換階段 → 目前在第幾步（0..2）。熱點早就開著時會從 0 直接跳到 2。 */
fun hotspotSwitchStepOf(phase: HotspotSwitchPhase): Int = when (phase) {
    HotspotSwitchPhase.RESTARTING -> 0
    HotspotSwitchPhase.WAITING_HOTSPOT -> 1
    HotspotSwitchPhase.SEARCHING, HotspotSwitchPhase.FAILED -> 2
}

/** 切換剩下的秒數（倒數，不會是負的）。 */
fun hotspotSwitchSecondsLeft(deadlineMs: Long, nowMs: Long): Long = ((deadlineMs - nowMs + 999) / 1000).coerceAtLeast(0)
