package com.b570.pilot.ui.shell

import com.b570.pilot.data.ReconnectStatus
import com.b570.pilot.link.ConnectionState

/** shell 依連線狀態決定的根頁面。 */
internal enum class RootRoute {
    /** 切到主畫面（沿用上次的分頁）。 */
    MAIN,

    /** 切到連線頁。 */
    CONNECT,

    /** 留在原頁。 */
    STAY,
}

/**
 * 連線狀態 → 根頁面（純函式，單元測試見 ShellRoutingTest）：
 * - Connected → 主畫面。
 * - 自動重連中（[reconnecting]）→ 留在原頁：駕駛畫面照樣顯示、輸入被封鎖（ControlBlock），
 *   狀態條 / 駕駛上緣顯示「車子斷線，自動重連中（第 n/6 次）」。重連每次嘗試前連線層會短暫回到 Idle，也不能跳頁。
 * - Idle / Failed（沒有在重連：使用者中斷、重連放棄、沒開自動重連）→ 連線頁（失敗原因 + 重試）。
 * - 連線中 / 訊號中斷 → 留在原頁。
 */
internal fun rootRouteOf(state: ConnectionState, reconnecting: Boolean): RootRoute = when {
    state is ConnectionState.Connected -> RootRoute.MAIN
    reconnecting -> RootRoute.STAY
    state is ConnectionState.Failed || state == ConnectionState.Idle -> RootRoute.CONNECT
    else -> RootRoute.STAY
}

/**
 * 主畫面收到 Failed 時先等這麼久再切到連線頁：repository 是先設 Failed、緊接著才排自動重連，
 * UI 執行緒可能先看到「Failed 且沒有重連」——不等一下會先跳到連線頁再跳回來。
 */
internal const val ROUTE_FAILED_GRACE_MS = 250L

/** 重連提示主文字的長度級距。 */
internal enum class ReconnectTitle { FULL, SHORT, TINY }

/**
 * 重連提示的主文字：
 * [ReconnectTitle.FULL]「車子斷線，自動重連中（第 2/6 次）」、[ReconnectTitle.SHORT]「自動重連中（2/6）」、[ReconnectTitle.TINY]「重連 2/6」。
 */
internal fun reconnectTitleZh(s: ReconnectStatus, length: ReconnectTitle = ReconnectTitle.FULL): String = when (length) {
    ReconnectTitle.FULL -> "車子斷線，自動重連中（第 ${s.attempt}/${s.maxAttempts} 次）"
    ReconnectTitle.SHORT -> "自動重連中（${s.attempt}/${s.maxAttempts}）"
    ReconnectTitle.TINY -> "重連 ${s.attempt}/${s.maxAttempts}"
}

/**
 * 依提示可用寬度（dp，已除以字級倍率）選文字長度：單行版要放「圖示 + 主文字 · 倒數 + 取消」，
 * 兩行版（[compact] = false）只要放主文字 + 取消。
 */
internal fun reconnectTitleLengthOf(widthDp: Float, compact: Boolean): ReconnectTitle = when {
    compact && widthDp >= 420f -> ReconnectTitle.FULL
    compact && widthDp >= 310f -> ReconnectTitle.SHORT
    compact -> ReconnectTitle.TINY
    widthDp >= 330f -> ReconnectTitle.FULL
    else -> ReconnectTitle.SHORT
}

/** 倒數：「3 秒後重試」；正在嘗試（nextAtMs = 0 或已到時間）→「正在連線…」。[nowMs] 與 nextAtMs 同為 elapsedRealtime。 */
internal fun reconnectDetailZh(s: ReconnectStatus, nowMs: Long): String {
    val secs = reconnectSecondsLeft(s, nowMs)
    return if (secs <= 0) "正在連線…" else "$secs 秒後重試"
}

/** 下一次嘗試還要幾秒（無條件進位；正在嘗試回傳 0）。 */
internal fun reconnectSecondsLeft(s: ReconnectStatus, nowMs: Long): Int {
    if (s.nextAtMs <= 0L) return 0
    val left = s.nextAtMs - nowMs
    return if (left <= 0L) 0 else ((left + 999L) / 1000L).toInt()
}
