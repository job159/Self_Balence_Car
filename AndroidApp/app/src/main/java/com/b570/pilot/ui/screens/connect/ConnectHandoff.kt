package com.b570.pilot.ui.screens.connect

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.b570.pilot.link.WifiCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 掃描頁交給連線頁處理的請求（連線頁負責權限 / Wi-Fi 檢查後再連線）。 */
internal sealed interface ConnectRequest {
    /** 掃到合法的 B570 配對碼。 */
    data class Paired(val credentials: WifiCredentials) : ConnectRequest

    /** 掃描頁選了「改用手動輸入」。 */
    data object ManualEntry : ConnectRequest
}

/**
 * 掃描覆蓋層 → 底下的連線頁（同一個程序、同一個畫面堆疊）。
 * 只在連線頁是底層（nav.base == Route.Connect）時使用；連線頁一收到就 consume。
 */
internal object ConnectHandoff {
    private val _pending = MutableStateFlow<ConnectRequest?>(null)
    val pending: StateFlow<ConnectRequest?> = _pending.asStateFlow()

    fun post(request: ConnectRequest) {
        _pending.value = request
    }

    fun consume(request: ConnectRequest) {
        _pending.compareAndSet(request, null)
    }
}

/** 由 Compose 的 LocalContext 找到 Activity（權限 rationale 判斷用）。 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
