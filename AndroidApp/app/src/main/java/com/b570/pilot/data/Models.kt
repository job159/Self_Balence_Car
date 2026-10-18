package com.b570.pilot.data

import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.protocol.HelloAck
import com.b570.pilot.protocol.SeqStep
import com.b570.pilot.protocol.WifiInfo
import kotlin.math.roundToInt

/** 連線品質（約每 0.5 秒更新一次）。null = 尚無資料。 */
data class LinkStats(
    /** App ⇄ 車子（經 ESP 到 STM32）來回延遲 ms（平滑後）。 */
    val rttMs: Float? = null,
    /** App ⇄ ESP 純 Wi-Fi 來回延遲 ms（平滑後）。 */
    val espRttMs: Float? = null,
    /** 每秒收到的遙測筆數。 */
    val telemetryFps: Float = 0f,
    /** 遙測遺失率 %（由 tick 間隔推算，最近 1 秒）。 */
    val lossPercent: Float = 0f,
    /** 手機端 Wi-Fi 訊號 dBm。 */
    val wifiRssi: Int? = null,
    /** 累計 CRC 錯誤幀數。 */
    val crcErrors: Long = 0,
    /** 本次連線累計收到的幀數。 */
    val rxFrames: Long = 0,
    /**
     * **控制延遲** ms（平滑後）：手機送出 CTRL → 車上真正套用，再加上回報那筆遙測的下行時間（上限值）。
     * 需要 v1.1 主控板韌體（TELEM 42 bytes）；舊韌體或最近 3 s 沒有樣本 = null（請改顯示 [rttMs]）。
     */
    val controlLatencyMs: Float? = null,
    /** 控制延遲最近 5 s 的 P95 ms（偶發的卡頓看這個）；樣本不足 = null。 */
    val controlLatencyP95Ms: Float? = null,
    /** 上行（手機 → 主控板）遺失率 %（最近約 5 s，對照遙測 link_q）；連線前 2.5 s / 樣本不足 = null。 */
    val uplinkLossPercent: Float? = null,
    /**
     * 直連時手機同時連著另一個 Wi-Fi（STA + STA 併行）→ 無線電要分時，延遲會變高。
     * UI 提示「手機同時連著其他 Wi-Fi，延遲會變高」。
     */
    val dualWifi: Boolean = false,
) {
    /** 給 HUD 顯示的「延遲」：有控制延遲就用它，否則用 PING 來回延遲。 */
    val displayLatencyMs: Float? get() = controlLatencyMs ?: rttMs

    /**
     * 連線品質 %（車子收到手機指令的比例 = 100 − [uplinkLossPercent]）；還沒有樣本 = null。
     * 不能再用「link_q × 2」：手機靜止時只送 10 Hz 心跳（ControlPacer），link_q 本來就只有 12 左右。
     */
    val linkQualityPercent: Int? get() = uplinkLossPercent?.let { (100f - it).roundToInt().coerceIn(0, 100) }

    /** 訊號格數 0..4（給 SignalBars）。 */
    val signalLevel: Int
        get() = when (val r = wifiRssi) {
            null -> 0
            else -> when {
                r >= -55 -> 4
                r >= -65 -> 3
                r >= -75 -> 2
                r >= -85 -> 1
                else -> 0
            }
        }
}

/** ESP-01 資訊：握手回覆 + 最近一次 WIFI_INFO。 */
data class EspInfo(val hello: HelloAck? = null, val wifi: WifiInfo? = null)

/**
 * 記住的車子。[nickname] 空字串時 UI 顯示 [ssid]。
 * @param ssid 車名（車子 AP 的 SSID，例如 B570-A1B2）
 * @param password 車子 AP 的密碼（直連用）；從熱點上找到、還沒掃過 QR 的車為空字串（只能用熱點模式連線）
 * @param hotspotMode 車子設定為熱點模式（加入手機的個人熱點）：連線時先在熱點上找車
 * @param lastIp 車子上次在熱點網段的位址（DISCOVER 先單播給它）
 * @param hotspotSsid 車子要加入的手機熱點名稱（UI 提示「請開啟手機熱點「…」」用；不知道 = 空字串）
 */
data class SavedCar(
    val ssid: String,
    val password: String,
    val nickname: String = "",
    val lastConnected: Long = 0L,
    val hotspotMode: Boolean = false,
    val lastIp: String? = null,
    val hotspotSsid: String = "",
) {
    val displayName: String get() = nickname.ifBlank { ssid }

    /** 有車子 AP 的合法密碼（可以直連）。 */
    val canDirect: Boolean get() = WifiCredentials(ssid, password).isValid

    val credentials: WifiCredentials get() = WifiCredentials(ssid, password)

    override fun toString(): String =
        "SavedCar(ssid=$ssid, password=***, nickname=$nickname, lastConnected=$lastConnected, hotspotMode=$hotspotMode, lastIp=$lastIp, hotspotSsid=$hotspotSsid)"
}

/**
 * 搖桿操作模式（順序 = 駕駛頁 / 設定頁的選擇順序）。
 * - [SINGLE]：360° 類比搖桿（**預設**）：一支圓形搖桿往任何方向推，前後 = 速度、左右 = 轉向、斜推 = 弧線（像手把的類比搖桿）
 * - [DUAL]：雙搖桿：左手直立油門 + 右手橫向方向
 * - [TILT]：體感：按住駕駛、傾斜手機
 *
 * enum 名稱會存進 SharedPreferences（[SettingsStore]），不要改名。
 */
enum class JoystickMode(val labelZh: String) {
    SINGLE("360° 搖桿"),
    DUAL("雙搖桿"),
    TILT("體感"),
}

/**
 * 檔位：搖桿推到底時的速度 / 轉向比例（乘上 MAX_SPEED 與 1000‰）。
 * 慢 30 % / 50 %、中 60 % / 75 %、快 100 % / 100 %（駕駛頁與設定頁共用）。
 */
enum class Gear(val labelZh: String, val speedFactor: Float, val turnFactor: Float) {
    SLOW("慢速", 0.30f, 0.50f),
    NORMAL("中速", 0.60f, 0.75f),
    FAST("高速", 1.0f, 1.0f),
}

/** 主題強調色（對應 ui.theme.AccentPalette）。 */
enum class AccentChoice(val labelZh: String) {
    CYAN("電光青"),
    MAGENTA("霓虹粉"),
    LIME("螢光綠"),
    AMBER("琥珀橘"),
}

/** App 設定（SharedPreferences 保存）。 */
data class AppSettings(
    val hapticsEnabled: Boolean = true,
    /** 預設 360° 搖桿（舊版預設雙搖桿；升級時一次性改成 360°，見 SettingsStore）。 */
    val joystickMode: JoystickMode = JoystickMode.SINGLE,
    /** 搖桿指數曲線 0（線性）..1（中心最細膩）。 */
    val expo: Float = 0.35f,
    val defaultGear: Gear = Gear.NORMAL,
    /** 體感靈敏度 0..1。 */
    val tiltSensitivity: Float = 0.5f,
    val accent: AccentChoice = AccentChoice.CYAN,
    val keepScreenOn: Boolean = true,
    /** 啟動 App 時自動進入展示模式（不需要車子）。 */
    val demoMode: Boolean = false,
    /** 開啟 App 時自動連上最近使用的車。 */
    val autoConnect: Boolean = true,
    /** 連線中的車子意外斷線時自動重連。 */
    val autoReconnect: Boolean = true,
)

/** 使用者儲存的編排。[loops] 0 = 無限循環。 */
data class SavedSequence(
    val id: String,
    val name: String,
    val steps: List<SeqStep>,
    val loops: Int = 1,
    val updatedMs: Long = System.currentTimeMillis(),
) {
    val totalDurationMs: Long get() = SeqStep.totalDurationMs(steps)
}
