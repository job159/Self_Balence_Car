package com.b570.pilot.data

import com.b570.pilot.link.LinkRequest
import com.b570.pilot.link.WifiCredentials

/**
 * 一次連線的計畫（[ConnectPlanner.plan]）：先不先在手機網路（熱點）上找車、找多久、找不到能不能改直連。
 * @param hotspotExpected 這台車設定為熱點模式（或只能用熱點連）：失敗時提示開啟手機熱點
 */
data class ConnectPlan(
    val discover: Boolean,
    val discoveryMs: Long,
    val direct: Boolean,
    val hotspotExpected: Boolean,
) {
    fun toRequest(credentials: WifiCredentials, lastIp: String?): LinkRequest =
        LinkRequest(
            credentials = credentials,
            discover = discover,
            discoveryTimeoutMs = discoveryMs,
            direct = direct,
            lastIp = lastIp,
            hotspotExpected = hotspotExpected,
        )
}

/**
 * 智慧連線的決策（純函式，`ConnectPlannerTest`）：
 * | 情況 | 先找車（熱點） | 直連 |
 * |---|---|---|
 * | 車子是熱點模式、手機熱點開著 | 20 s（涵蓋 ESP 加入熱點的時間，§3.6） | 有密碼才改直連（車子找不到熱點時開的是直連 AP） |
 * | 車子是熱點模式、手機熱點沒開 | 1.5 s（也許在同一個 Wi-Fi） | 有密碼就直連 |
 * | 車子不是熱點模式、手機熱點開著 | 2 s（可能被別支手機改成熱點模式） | 有密碼就直連 |
 * | 沒有密碼（從熱點上找到、沒掃過 QR） | 熱點開著 20 s / 沒開 4 s | 不能 |
 * | 其他 | 不找 | 直連 |
 *
 * 熱點開著時找滿 20 s：車子在熱點上時通常 0.1 s 內就回覆（不會多等）。車子還沒在熱點上時有兩種情況（ESP 1.1.1，b570_netsm.h）：
 * - 剛開機：最多 12 s 加入熱點（JOIN_BUDGET_MS）。
 * - 已經改開直連 AP（先開車子、後開熱點；或走出熱點範圍）：改開 AP 後 3 分鐘內每輪「AP 閒置 6 s（FAST_IDLE_MS，
 *   第一次 12 s：FIRST_IDLE_MS）+ 找熱點」，找不到約 2–3 s 就放棄（RETRY_BUDGET_MS = 8 s 為上限）→ 熱點出現後約 10–19 s 加入。
 * 只找 4 s 就改直連的話，WifiNetworkSpecifier 會去等一個隨後就消失的 AP（車子加入熱點會關掉 AP），卡到 30 s 逾時才失敗；
 * 而且直連需要手機 Wi-Fi（熱點模式的使用者 Wi-Fi 通常關著）。3 分鐘後車子改成每 30 s 才試一次，可能更晚才出現在熱點上
 * （連線頁會繼續在熱點上找、找到就標「在熱點上」）。
 */
object ConnectPlanner {
    const val HOTSPOT_DISCOVERY_MS = 4_000L
    /**
     * 手機熱點開著、車子應該在熱點上：涵蓋 ESP 開機加入熱點（b570_netsm.h JOIN_BUDGET_MS = 12 s），以及已改開直連 AP 的車
     * 再試熱點（FIRST_IDLE_MS 12 s / FAST_IDLE_MS 6 s + 一次找熱點與加入約 2–5 s），再加一點餘裕。
     */
    const val HOTSPOT_JOIN_DISCOVERY_MS = 20_000L
    const val OPPORTUNISTIC_DISCOVERY_MS = 2_000L
    const val HOTSPOT_OFF_DISCOVERY_MS = 1_500L

    /**
     * @param car 記住的這台車（沒記住 = null）
     * @param credentials 要連的車名 + AP 密碼
     * @param phoneHotspotOn 手機熱點看起來開著（`HotspotNet.isPhoneHotspotLikelyOn`）
     * @param forceDirect 一律直連（例如剛掃 QR：OLED 顯示 QR = 車子正在直連 AP 模式）
     */
    fun plan(car: SavedCar?, credentials: WifiCredentials, phoneHotspotOn: Boolean, forceDirect: Boolean = false): ConnectPlan {
        val directOk = credentials.isValid
        if (forceDirect && directOk) return ConnectPlan(discover = false, discoveryMs = 0L, direct = true, hotspotExpected = false)
        val hotspotMode = car?.hotspotMode == true
        return when {
            !directOk -> ConnectPlan(
                discover = true,
                discoveryMs = if (phoneHotspotOn) HOTSPOT_JOIN_DISCOVERY_MS else HOTSPOT_DISCOVERY_MS,
                direct = false,
                hotspotExpected = true,
            )
            hotspotMode -> ConnectPlan(
                discover = true,
                discoveryMs = if (phoneHotspotOn) HOTSPOT_JOIN_DISCOVERY_MS else HOTSPOT_OFF_DISCOVERY_MS,
                direct = true,
                hotspotExpected = true,
            )
            phoneHotspotOn -> ConnectPlan(discover = true, discoveryMs = OPPORTUNISTIC_DISCOVERY_MS, direct = true, hotspotExpected = false)
            else -> ConnectPlan(discover = false, discoveryMs = 0L, direct = true, hotspotExpected = false)
        }
    }

    /** 這台車連線一定要手機 Wi-Fi（只會直連）：UI 決定要不要先檢查 Wi-Fi / 附近裝置權限。 */
    fun requiresWifi(car: SavedCar?, credentials: WifiCredentials, phoneHotspotOn: Boolean): Boolean =
        !plan(car, credentials, phoneHotspotOn).discover
}

/** 以後帶出去怎麼連（切換到熱點模式成功時的說明；提示與進度對話框共用）。 */
const val HOTSPOT_OUTDOOR_HOWTO_ZH = "以後帶出去：先打開手機熱點，再打開車子電源，App 會自動連上。手機 Wi-Fi 可以關掉了。"

/** 切換到熱點模式成功的提示（[CarRepository.setHotspot] 之後在熱點上連上車子）。 */
const val HOTSPOT_SWITCH_DONE_ZH = "完成！$HOTSPOT_OUTDOOR_HOWTO_ZH"

/** 切換到熱點模式的進度（[CarRepository.hotspotSwitch]）。 */
enum class HotspotSwitchPhase(val labelZh: String) {
    /** STA_SET 已送出，車子重新開機中（約 3.5 s）。 */
    RESTARTING("車子重新開機中…"),
    /** 手機熱點看起來沒開：請使用者打開（車子開機最多等 12 s；之後改開直連 AP，沒人連著時自動再試，3 分鐘內約每 8 s 一次）。 */
    WAITING_HOTSPOT("請開啟手機熱點"),
    /** 手機熱點開著，正在熱點上找車。 */
    SEARCHING("在熱點上尋找車子…"),
    /** 時間內沒找到（車子會改回直連 AP，OLED 會再顯示 QR）。 */
    FAILED("沒有找到車子"),
}

/**
 * 切換到熱點模式的進度。null = 沒有在切換。
 * @param hotspotSsid 車子要加入的手機熱點名稱（提示「請開啟手機熱點「…」」）
 * @param phoneHotspotOn 手機熱點看起來開著
 * @param startedAtMs / [deadlineMs] SystemClock.elapsedRealtime()
 * @param messageZh 給使用者看的一句話（依 [phase]）
 */
data class HotspotSwitchStatus(
    val hotspotSsid: String,
    val phase: HotspotSwitchPhase,
    val phoneHotspotOn: Boolean,
    val startedAtMs: Long,
    val deadlineMs: Long,
) {
    val messageZh: String
        get() = when (phase) {
            HotspotSwitchPhase.RESTARTING -> "車子重新開機中，接著會加入手機熱點「$hotspotSsid」"
            HotspotSwitchPhase.WAITING_HOTSPOT -> "請開啟手機熱點「$hotspotSsid」（2.4\u00A0GHz），車子會自動連上"
            HotspotSwitchPhase.SEARCHING -> "正在熱點「$hotspotSsid」上尋找車子…"
            HotspotSwitchPhase.FAILED -> "沒有在熱點「$hotspotSsid」上找到車子：請確認熱點是 2.4\u00A0GHz、名稱密碼正確，再按「重試」"
        }

    val isActive: Boolean get() = phase != HotspotSwitchPhase.FAILED
}
