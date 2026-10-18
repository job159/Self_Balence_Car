package com.b570.pilot.data

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.b570.pilot.link.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自動重連的進度（[CarRepository.reconnect]）。null = 沒有在重連。
 * @param attempt 第幾次嘗試（1 起算）
 * @param nextAtMs 下一次嘗試的時間（SystemClock.elapsedRealtime）；0 = 正在嘗試中
 */
data class ReconnectStatus(
    val ssid: String,
    val attempt: Int,
    val maxAttempts: Int,
    val nextAtMs: Long,
)

/**
 * 斷線後自動重連的節奏：車子剛斷線多半是暫時（走遠、重開機），前幾次快、之後放慢，
 * 總共約 45 秒（每次嘗試本身另計）。只對「這次已經成功連上過」的車子重連 ——
 * 系統已記住使用者的同意，不會一直跳出連線對話框。
 */
object ReconnectPolicy {
    private val DELAYS_MS = longArrayOf(1_500, 3_000, 5_000, 8_000, 12_000, 15_000)
    val maxAttempts: Int get() = DELAYS_MS.size

    /** 第 [attempt] 次（1 起算）嘗試前要等多久；超過次數回傳 null。 */
    fun delayBefore(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt - 1)

    /**
     * 這次失敗之後還要不要繼續自動重連：不能重試的錯誤、Wi-Fi 關閉 → 不要；
     * 這次嘗試跳出了系統「連線到裝置」同意視窗（[approvalShown]，系統沒有記住同意）卻沒連上（使用者按取消、沒理它逾時）
     * → 不要，否則每一次重試都會再跳一次視窗。
     */
    fun shouldRetry(failed: ConnectionState.Failed, approvalShown: Boolean): Boolean =
        failed.retryable && !failed.wifiDisabled && !approvalShown
}

/** 平衡車的 Wi-Fi 名稱前綴（ESP-01 出廠值 `B570-XXXX`）。 */
const val CAR_SSID_PREFIX = "B570-"

/**
 * 偵測附近的平衡車（Wi-Fi 掃描結果）：記住的車是否在範圍內、以及還沒配對過的 `B570-` 熱點。
 *
 * 需要 Wi-Fi 掃描權限（Android 13+ NEARBY_WIFI_DEVICES，舊版 ACCESS_FINE_LOCATION）；
 * 沒權限、Wi-Fi 關閉或系統節流（前景每 2 分鐘 4 次）時只讀快取結果，不會丟例外。
 */
class NearbyCars(private val app: Application, private val savedSsids: () -> Set<String>) {

    private val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val _nearby = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 附近的車：SSID → 訊號強度 dBm（記住的車 + `B570-` 開頭的熱點）。 */
    val nearby: StateFlow<Map<String, Int>> = _nearby.asStateFlow()

    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = readResults()
    }

    /** 開始監聽掃描結果並觸發一次掃描（連線頁顯示時呼叫）。 */
    @Synchronized
    fun start() {
        if (!registered) {
            runCatching {
                ContextCompat.registerReceiver(
                    app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
            }
        }
        refresh()
    }

    /** 停止監聽（連線頁離開時呼叫）。 */
    @Synchronized
    fun stop() {
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
    }

    /** 觸發掃描（被系統節流也沒關係）並立即讀一次快取結果。 */
    @Suppress("DEPRECATION")
    fun refresh() {
        runCatching { wm.startScan() }
        readResults()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readResults() {
        val results = try {
            wm.scanResults
        } catch (e: SecurityException) {
            return
        } ?: return
        val saved = savedSsids()
        val found = HashMap<String, Int>()
        for (r in results) {
            val ssid = r.SSID?.removeSurrounding("\"") ?: continue
            if (ssid.isEmpty()) continue
            if (ssid.startsWith(CAR_SSID_PREFIX) || ssid in saved) {
                val prev = found[ssid]
                if (prev == null || r.level > prev) found[ssid] = r.level
            }
        }
        _nearby.value = found
    }
}
