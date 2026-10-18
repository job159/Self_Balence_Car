package com.b570.pilot.link

import com.b570.pilot.protocol.Info

/** 連線狀態（CarRepository.connectionState）。 */
sealed class ConnectionState {
    /** 尚未連線（或使用者主動中斷）。 */
    data object Idle : ConnectionState()

    /**
     * 熱點模式：正在手機的網路（個人熱點）上送 DISCOVER 找車子（[ssid] = 車名）。
     * 找不到時可能接著改用直連（[RequestingWifi]）。UI 比照 [RequestingWifi] 顯示「連線中」。
     */
    data class Discovering(val ssid: String) : ConnectionState()

    /** 正在請系統連上車子的 Wi-Fi（可能跳出系統對話框）。 */
    data class RequestingWifi(val ssid: String) : ConnectionState()

    /** Wi-Fi 已連上，正在與 ESP / 車子握手。 */
    data object Handshaking : ConnectionState()

    /** 連線中。[info] = 主控板資訊（主控板沒回 INFO 時為 null）；[sinceMs] = System.currentTimeMillis()。 */
    data class Connected(val info: Info?, val sinceMs: Long) : ConnectionState()

    /** 超過 1.5 秒沒收到車子的資料；資料恢復後自動回到 [Connected]。[sinceMs] = 失聯開始時間。 */
    data class LinkLost(val sinceMs: Long) : ConnectionState()

    /**
     * 連線失敗或被中斷。
     * @param reasonZh 給使用者看的中文原因
     * @param retryable 可以直接重試
     * @param wifiDisabled 手機 Wi-Fi 關閉中（UI 可引導開啟 Wi-Fi 面板）
     * @param hotspotHint 熱點模式相關（車子設定為熱點模式、手機熱點沒開或找不到車子）：UI 可提示開啟手機熱點
     *   （`LinkPermissions.openHotspotSettings(context)`）
     */
    data class Failed(
        val reasonZh: String,
        val retryable: Boolean = true,
        val wifiDisabled: Boolean = false,
        val hotspotHint: Boolean = false,
    ) : ConnectionState()

    /** Connected 或 LinkLost（主畫面應保持顯示）。 */
    val isSession: Boolean get() = this is Connected || this is LinkLost

    /** 正在建立連線中（找車 / 連 Wi-Fi / 握手）。 */
    val isConnecting: Boolean get() = this is Discovering || this is RequestingWifi || this is Handshaking

    /** 可以送指令（Connected）。 */
    val isConnected: Boolean get() = this is Connected

    /** 簡短中文狀態（狀態膠囊用）。 */
    val labelZh: String
        get() = when (this) {
            Idle -> "未連線"
            is Discovering -> "尋找車子…"
            is RequestingWifi -> "連線 Wi-Fi…"
            Handshaking -> "握手中…"
            is Connected -> "已連線"
            is LinkLost -> "訊號中斷"
            is Failed -> "連線失敗"
        }
}
