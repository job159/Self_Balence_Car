package com.b570.pilot.ui.screens.connect

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.b570.pilot.data.SavedCar
import com.b570.pilot.data.Severity
import com.b570.pilot.link.LinkPermissions
import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.openPhoneHotspotSettings

/** 連線頁的權限對話框種類。 */
internal enum class PermDialog {
    /** 配對前說明：相機（+ 尚未授予的連線權限一起要）。 */
    PAIR_RATIONALE,

    /** 連線前說明：附近裝置 / 位置。 */
    CONNECT_RATIONALE,

    /** 連線權限被永久拒絕 → 前往系統設定。 */
    CONNECT_DENIED,

    /** 相機權限被永久拒絕 → 前往系統設定。 */
    CAMERA_DENIED,

    /**
     * 第一次配對（剛掃 QR）、手機 Wi-Fi 關著：先說明為什麼要開 Wi-Fi（只開開關、不用連網路；連上後改用熱點模式，
     * 以後就不必再開），使用者按「開啟 Wi-Fi」才開系統面板。
     */
    WIFI_FIRST_PAIR,

    /**
     * 剛掃 QR 的車已經是熱點模式、手機 Wi-Fi 關著：OLED 顯示 QR 只是車子還沒找到手機熱點 →
     * 請使用者打開熱點（車子會自己連上，不用開 Wi-Fi），不跳 Wi-Fi 面板。
     */
    HOTSPOT_CAR,
}

/** 連線前 Wi-Fi 這一關的結果（[wifiGateOf]）。 */
internal enum class WifiGate {
    /** Wi-Fi 已開：直接連線。 */
    CONNECT,

    /** 已經開過 Wi-Fi 面板、正在等使用者打開：不重複開。 */
    WAIT,

    /** 剛掃 QR、記住的車是熱點模式：顯示 [PermDialog.HOTSPOT_CAR]（打開手機熱點就好），不跳 Wi-Fi 面板。 */
    EXPLAIN_HOTSPOT_CAR,

    /** 第一次配對（剛掃 QR）：先顯示 [PermDialog.WIFI_FIRST_PAIR] 說明，不直接跳系統面板。 */
    EXPLAIN_FIRST_PAIR,

    /** 重新連線記住的直連車：直接開 Wi-Fi 面板（開啟後自動連線）。 */
    OPEN_PANEL,
}

/**
 * 連線前 Wi-Fi 這一關要做什麼（純函式，`ConnectWifiGateTest`）。
 * @param wifiOn 手機 Wi-Fi 開著
 * @param pendingDirect 這次是直連配對（剛掃 QR：OLED 顯示 QR = 車子正在直連模式）
 * @param waitingWifi 已經開過面板、正在等 Wi-Fi 開啟
 * @param hotspotCar 記住的這台車是熱點模式（OLED 顯示 QR = 車子暫時沒找到手機熱點，打開熱點就會自己連上）
 */
internal fun wifiGateOf(wifiOn: Boolean, pendingDirect: Boolean, waitingWifi: Boolean, hotspotCar: Boolean = false): WifiGate = when {
    wifiOn -> WifiGate.CONNECT
    waitingWifi -> WifiGate.WAIT
    pendingDirect && hotspotCar -> WifiGate.EXPLAIN_HOTSPOT_CAR
    pendingDirect -> WifiGate.EXPLAIN_FIRST_PAIR
    else -> WifiGate.OPEN_PANEL
}

/**
 * 剛掃 QR 的這台車連上後，shell 真的會邀請改用熱點模式（`hotspotOnboardingDue`）：還沒記住、或記住了但沒連上過、
 * 沒設定過熱點模式、也還沒回答過邀請。不成立時 [PermDialog.WIFI_FIRST_PAIR] 不說「連上後會帶你改用熱點模式」。
 */
internal fun firstPairPromiseOf(saved: SavedCar?, answered: Set<String>): Boolean =
    saved == null ||
        (saved.lastConnected == 0L && !saved.hotspotMode && saved.hotspotSsid.isBlank() && saved.ssid !in answered)

/** [PermDialog.WIFI_FIRST_PAIR] 的標題；[promise] = [firstPairPromiseOf]（真的是第一次配對）。 */
internal fun wifiFirstPairTitleZh(promise: Boolean): String = if (promise) "第一次配對要開一下 Wi-Fi" else "要開一下 Wi-Fi"

/**
 * [PermDialog.WIFI_FIRST_PAIR] 的說明；[phoneHotspotOn] = 手機熱點看起來開著（有些手機開 Wi-Fi 會自動關掉熱點）；
 * [promise] = 連上後會邀請改用熱點模式（[firstPairPromiseOf]）。
 */
internal fun wifiFirstPairMessageZh(phoneHotspotOn: Boolean, promise: Boolean = true): String = buildString {
    append("只要打開 Wi-Fi 開關，不用連任何網路，手機照常用行動網路上網。")
    if (promise) append("連上車子後會帶你改用熱點模式：以後帶出去只開手機熱點，Wi-Fi 可以一直關著。")
    // 接在同一段（不換行）：640 × 320、字級 1.3 時按鈕才不會被擠出畫面（HotspotOnboardingUiTest）。
    // 熱點要等熱點模式設定好（按「套用」）再打開：先打開的話，不能同時開 Wi-Fi 與熱點的手機會中斷直連
    if (phoneHotspotOn) append(if (promise) "有些手機開 Wi-Fi 會自動關掉熱點，沒關係，設定好熱點模式再打開。" else "有些手機開 Wi-Fi 會自動關掉熱點，之後要用再打開。")
}

/** [PermDialog.HOTSPOT_CAR] 的標題。 */
internal const val HOTSPOT_CAR_TITLE_ZH = "車子沒找到手機熱點"

/**
 * [PermDialog.HOTSPOT_CAR] 的說明（車子在 FALLBACK 時約每 8 s 再試熱點，b570_netsm.h；
 * App 在熱點上找 [com.b570.pilot.data.ConnectPlanner.HOTSPOT_JOIN_DISCOVERY_MS] = 20 s）。
 * [phoneHotspotOn] = 手機熱點看起來已經開著。
 */
internal fun hotspotCarMessageZh(phoneHotspotOn: Boolean): String =
    (if (phoneHotspotOn) "手機熱點已開著：" else "打開手機熱點，") +
        "車子約 20 秒內會自己連上，不用開 Wi-Fi。要直連修改設定，請先打開 Wi-Fi 再掃描。"

/** [PermDialog.HOTSPOT_CAR] 的確認鈕：熱點沒開 =「開啟熱點」；開著 =「在熱點上找車」。 */
internal fun hotspotCarConfirmZh(phoneHotspotOn: Boolean): String = if (phoneHotspotOn) "在熱點上找車" else "開啟熱點"

/**
 * 連線前的檢查流程：權限（含說明 / 永久拒絕）→ Wi-Fi 開關 → vm.connect（剛掃 QR = vm.connectDirect）。
 * 等待中的車子放在 [pending]；Wi-Fi 開啟或從系統設定回來後自動繼續。
 * Wi-Fi 關著（[wifiGateOf]）：剛掃 QR 的第一次配對先顯示說明（[PermDialog.WIFI_FIRST_PAIR]），按「開啟 Wi-Fi」才開系統面板；
 * 剛掃 QR 的車已經是熱點模式 → [PermDialog.HOTSPOT_CAR]（打開手機熱點，熱點一開就在熱點上連線：[waitingHotspot]）；
 * 重新連線記住的直連車則直接開面板。
 *
 * 熱點模式：這次連線會先在手機熱點上找車（`vm.connectPlanFor(creds).discover`，例如記住的車是熱點模式、或手機熱點開著）
 * → **不檢查 Wi-Fi 與附近裝置權限**直接連（開熱點時 Wi-Fi 常常是關的；找不到才直連，那時缺什麼失敗卡會說）。
 */
@Stable
internal class ConnectFlow(private val vm: AppViewModel, private val nav: AppNavigator) {

    /** 由 composition 提供的環境（Context 與 Activity result launcher）。 */
    class Env(
        val context: Context,
        val requestPermissions: (Array<String>) -> Unit,
        val openWifiPanel: () -> Unit,
    )

    private enum class Purpose { PAIR, CONNECT }

    var env: Env? = null

    var dialog by mutableStateOf<PermDialog?>(null)
        private set

    /** 等權限或 Wi-Fi 的車子（null = 沒有）。 */
    var pending by mutableStateOf<WifiCredentials?>(null)
        private set

    /** 正在等使用者開啟 Wi-Fi（開啟後自動連線 [pending]）。 */
    var waitingWifi by mutableStateOf(false)
        private set

    /**
     * 等手機熱點打開後自動連線的車（[PermDialog.HOTSPOT_CAR] 按了確認）：連線頁看到熱點開著就呼叫 [onPhoneHotspotOn]
     * → 智慧連線（熱點模式的車會先在熱點上找）。
     */
    var waitingHotspot by mutableStateOf<WifiCredentials?>(null)
        private set

    /** [PermDialog.WIFI_FIRST_PAIR] 要不要說「連上後會帶你改用熱點模式」（[firstPairPromiseOf]）。 */
    var firstPairPromise by mutableStateOf(true)
        private set

    /** [pending] 要直連（剛掃 QR：OLED 顯示 QR = 車子正在直連模式），不先在熱點上找。 */
    private var pendingDirect = false

    private var purpose = Purpose.CONNECT
    private var backFromSettings: Purpose? = null

    // ------------------------------------------------------------------ 進入點

    /** 「掃描 QR 配對」：有相機權限直接開掃描，否則先說明再一起請求。 */
    fun startPairing() {
        val e = env ?: return
        if (LinkPermissions.hasCamera(e.context)) nav.openScanner() else dialog = PermDialog.PAIR_RATIONALE
    }

    /**
     * 連線到 [creds]（必要時先要權限、開 Wi-Fi）。
     * @param direct true = 一律直連（剛掃 QR）；false = 智慧連線（熱點模式的車 / 手機熱點開著 → 先在熱點上找）
     */
    fun connect(creds: WifiCredentials, direct: Boolean = false) {
        val e = env ?: return
        waitingHotspot = null
        if (!direct && runCatching { vm.connectPlanFor(creds).discover }.getOrDefault(false)) {
            cancelPending()
            vm.connect(creds)
            return
        }
        pending = creds
        pendingDirect = direct
        waitingWifi = false
        if (LinkPermissions.missingForConnect(e.context).isNotEmpty()) {
            dialog = PermDialog.CONNECT_RATIONALE
            return
        }
        proceed()
    }

    /** 失敗卡片的「重試」。 */
    fun retry() {
        val car = vm.currentCar.value
        if (vm.isDemo.value || car == null) vm.retry() else connect(car)
    }

    /** 失敗原因是 Wi-Fi 關閉：開面板，開啟後自動重試。 */
    fun enableWifiAndRetry() {
        val car = vm.currentCar.value
        if (car != null && !vm.isDemo.value) {
            pending = car
            waitingWifi = true
        }
        env?.openWifiPanel?.invoke()
    }

    /** Wi-Fi 卡片的「開啟 Wi-Fi」。 */
    fun openWifiPanel() {
        env?.openWifiPanel?.invoke()
    }

    fun cancelPending() {
        pending = null
        pendingDirect = false
        waitingWifi = false
        waitingHotspot = null
    }

    /** 手機熱點開了：連線 [waitingHotspot]（智慧連線：熱點模式的車先在熱點上找，車子約 20 s 內會加入熱點）。 */
    fun onPhoneHotspotOn() {
        val creds = waitingHotspot ?: return
        waitingHotspot = null
        connect(creds)
    }

    // ------------------------------------------------------------------ 對話框

    fun onDialogConfirm() {
        val e = env ?: return
        when (dialog) {
            PermDialog.PAIR_RATIONALE -> {
                purpose = Purpose.PAIR
                e.requestPermissions(arrayOf(LinkPermissions.CAMERA) + LinkPermissions.missingForConnect(e.context))
            }
            PermDialog.CONNECT_RATIONALE -> {
                purpose = Purpose.CONNECT
                e.requestPermissions(LinkPermissions.missingForConnect(e.context))
            }
            PermDialog.CONNECT_DENIED -> {
                backFromSettings = Purpose.CONNECT
                openAppSettings(e.context)
            }
            PermDialog.CAMERA_DENIED -> {
                backFromSettings = Purpose.PAIR
                openAppSettings(e.context)
            }
            PermDialog.WIFI_FIRST_PAIR -> if (pending != null) {
                // 開啟後由 Wi-Fi 廣播（rememberWifiEnabled → onWifiEnabled）自動繼續連線
                waitingWifi = true
                e.openWifiPanel()
            }
            PermDialog.HOTSPOT_CAR -> pending?.let { creds ->
                // 不開 Wi-Fi：等手機熱點打開（連線頁看到熱點開著 → onPhoneHotspotOn → 在熱點上連線）
                cancelPending()
                waitingHotspot = creds
                if (!vm.isPhoneHotspotLikelyOn() && !openPhoneHotspotSettings(e.context)) {
                    vm.notice("請到手機設定開啟「個人熱點」", Severity.WARNING)
                }
            }
            null -> Unit
        }
        dialog = null
    }

    fun onDialogDismiss() {
        when (dialog) {
            PermDialog.CONNECT_RATIONALE, PermDialog.CONNECT_DENIED, PermDialog.WIFI_FIRST_PAIR, PermDialog.HOTSPOT_CAR -> cancelPending()
            else -> Unit
        }
        dialog = null
    }

    // ------------------------------------------------------------------ 回呼

    fun onPermissionsResult(result: Map<String, Boolean>) {
        val e = env ?: return
        if (result.isEmpty()) {
            // 請求被系統取消（例如旋轉 / 重疊請求）：不當作拒絕
            if (purpose == Purpose.CONNECT) cancelPending()
            return
        }
        when (purpose) {
            Purpose.PAIR -> {
                if (LinkPermissions.hasCamera(e.context)) {
                    nav.openScanner()
                } else if (isPermanentlyDenied(e.context, result.filterKeys { it == LinkPermissions.CAMERA })) {
                    dialog = PermDialog.CAMERA_DENIED
                } else {
                    vm.notice("沒有相機權限就無法掃描，也可以改用手動輸入", Severity.WARNING)
                }
            }
            Purpose.CONNECT -> {
                if (LinkPermissions.missingForConnect(e.context).isEmpty()) {
                    proceed()
                } else if (approximateLocationOnly(result)) {
                    cancelPending()
                    vm.notice("請選擇「精確位置」，系統才允許連上車子的 Wi-Fi", Severity.WARNING)
                } else if (isPermanentlyDenied(e.context, result)) {
                    dialog = PermDialog.CONNECT_DENIED
                } else {
                    cancelPending()
                    vm.notice("沒有權限就無法連上車子", Severity.WARNING)
                }
            }
        }
    }

    /** 畫面回到前景（可能剛從系統設定回來）。 */
    fun onResume() {
        val e = env ?: return
        when (backFromSettings.also { backFromSettings = null }) {
            Purpose.CONNECT -> if (LinkPermissions.missingForConnect(e.context).isEmpty()) proceed() else cancelPending()
            Purpose.PAIR -> if (LinkPermissions.hasCamera(e.context)) nav.openScanner()
            null -> Unit
        }
    }

    /**
     * Wi-Fi 開啟後繼續等待中的連線（說明對話框還開著、使用者自己從快速設定開了 Wi-Fi，也直接繼續；
     * [PermDialog.HOTSPOT_CAR] 開著時自己開了 Wi-Fi = 要直連）。
     */
    fun onWifiEnabled() {
        if ((dialog == PermDialog.WIFI_FIRST_PAIR || dialog == PermDialog.HOTSPOT_CAR) && pending != null) {
            dialog = null
            proceed()
        } else if (waitingWifi) {
            proceed()
        }
    }

    // ------------------------------------------------------------------ 內部

    private fun proceed() {
        val e = env ?: return
        val creds = pending ?: return
        val saved = vm.savedCars.value.firstOrNull { it.ssid == creds.ssid }
        when (wifiGateOf(LinkPermissions.isWifiEnabled(e.context), pendingDirect, waitingWifi, hotspotCar = saved?.hotspotMode == true)) {
            WifiGate.CONNECT -> Unit
            WifiGate.WAIT -> return
            // 剛掃 QR、車子是熱點模式：OLED 的 QR 只表示還沒找到手機熱點 → 請使用者打開熱點，不叫他開 Wi-Fi
            WifiGate.EXPLAIN_HOTSPOT_CAR -> {
                dialog = PermDialog.HOTSPOT_CAR
                return
            }
            // 剛掃 QR、Wi-Fi 關著：先說明（只要打開開關；連上後改用熱點模式就不必再開），不直接跳系統面板
            WifiGate.EXPLAIN_FIRST_PAIR -> {
                firstPairPromise = firstPairPromiseOf(saved, vm.hotspotPromptAnswered.value)
                dialog = PermDialog.WIFI_FIRST_PAIR
                return
            }
            WifiGate.OPEN_PANEL -> {
                waitingWifi = true
                e.openWifiPanel()
                return
            }
        }
        val direct = pendingDirect
        pending = null
        pendingDirect = false
        waitingWifi = false
        if (direct) vm.connectDirect(creds) else vm.connect(creds)
    }

    private fun isPermanentlyDenied(context: Context, result: Map<String, Boolean>): Boolean {
        val activity = context.findActivity() ?: return false
        return result.any { (perm, granted) -> !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm) }
    }

    /** Android 12+ 使用者只給「大概位置」（API 31..32 連 Wi-Fi 需要精確位置）。 */
    private fun approximateLocationOnly(result: Map<String, Boolean>): Boolean =
        Build.VERSION.SDK_INT in 31..32 &&
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true &&
            result[Manifest.permission.ACCESS_FINE_LOCATION] == false

    private fun openAppSettings(context: Context) {
        try {
            context.startActivity(LinkPermissions.appSettingsIntent(context))
        } catch (e: ActivityNotFoundException) {
            backFromSettings = null
            vm.notice("請到系統設定 > 應用程式開啟權限", Severity.WARNING)
        }
    }
}

/**
 * 手機 Wi-Fi 是否開啟（系統廣播即時更新，回到前景時再確認一次）。
 */
@Composable
internal fun rememberWifiEnabled(): State<Boolean> {
    val context = LocalContext.current
    val enabled = remember { mutableStateOf(LinkPermissions.isWifiEnabled(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                enabled.value = when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)) {
                    WifiManager.WIFI_STATE_ENABLED -> true
                    WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING, WifiManager.WIFI_STATE_ENABLING -> false
                    else -> LinkPermissions.isWifiEnabled(c)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled.value = LinkPermissions.isWifiEnabled(context) }
    return enabled
}
