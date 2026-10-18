package com.b570.pilot.ui.screens.connect

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.HotspotSwitchPhase
import com.b570.pilot.data.HotspotSwitchStatus
import com.b570.pilot.data.SavedCar
import com.b570.pilot.data.Severity
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.DiscoveredCar
import com.b570.pilot.link.LinkPermissions
import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.MainTab
import com.b570.pilot.ui.Route
import com.b570.pilot.ui.components.ConfirmDialog
import com.b570.pilot.ui.components.HotspotSwitchPanel
import com.b570.pilot.ui.components.LocalScreenPadding
import com.b570.pilot.ui.components.openPhoneHotspotSettings
import com.b570.pilot.ui.components.rememberPhoneHotspotOn
import com.b570.pilot.ui.components.SectionHeader
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import kotlinx.coroutines.delay

private enum class PanelPhase { IDLE, CONNECTING, FAILED }

/** 退場動畫期間保留最後一次的失敗原因（非 state，組合期間寫入安全）。 */
private class LastFailed {
    var value: ConnectionState.Failed? = null
}

/**
 * 連線頁（未連線時的起點，橫向）。
 *
 * ```
 * ┌───────────────────────────┬──────────────────────────────────┐
 * │ ● 未連線            v1.0  │ [ 掃描 QR 配對              ]   │ ← 連線中：橫向步驟條 + 取消
 * │                           │ [ 手動輸入 ] [ 展示模式 ]        │   失敗：原因 + 重試 / 開啟 Wi-Fi
 * │      ( 陀螺儀主視覺 )      │ （Wi-Fi 已關閉卡）               │
 * │                           │ ▍記住的車子                      │
 * │       B570 PILOT          │ [ 車子卡片（點一下連線）… ]       │   ← 整欄可捲動
 * │     自平衡車遙控系統        │ 展示模式說明 · 行動數據不中斷      │
 * └───────────────────────────┴──────────────────────────────────┘
 * ```
 * 寬度分配見 [connectPanesOf]；分割畫面太窄（< 560 dp）時退回單欄清單（主視覺縮小放最上面）。
 * 連線前依序處理權限與 Wi-Fi（[ConnectFlow]）；連線中顯示步驟條；失敗顯示原因與重試。
 * 連上後 shell 會切到主畫面，這裡再確保停在「駕駛」分頁。
 *
 * 快速連線（ConnectQuick.kt）：
 * - 開啟 App：權限 + Wi-Fi 就緒就 `vm.autoConnectOnLaunch()`，連線中顯示大卡片「正在連線 <名稱>…」+ 取消；
 *   桌面捷徑「連線 <名稱>」（`vm.pendingConnect`）同一張卡片。
 * - 還沒有記住的車：「掃描 QR 配對」是主視覺；剛好一台記住的車在附近：主按鈕「連線 <名稱>」。
 * - 附近掃描：記住的車標「在附近 + 訊號」或「不在範圍內」（變淡）；沒配對過的 B570- 熱點列成「附近的新車」。
 * - 第一次連某台車：RequestingWifi 超過 0.7 秒顯示「請在系統視窗按「連線」，之後就會自動連線」。
 * - 斷線後自動重連放棄時，失敗卡加上「已自動重試 n 次」。
 *
 * 熱點模式（車子加入手機個人熱點，手機照用行動網路）：
 * - 這次連線會先在熱點上找車（`vm.connectPlanFor(...).discover`）時，不要求 Wi-Fi / 附近裝置權限（開熱點時 Wi-Fi 常是關的），
 *   開 App 自動連線也照樣進行。
 * - 熱點模式的車 Wi-Fi 掃描看不到：手機熱點開著（或有熱點模式的車）時每 5 秒在熱點上找車 → 記住的車標「在熱點上」，
 *   沒記住的列成「熱點上的車」（不用掃 QR，點一下連線）。
 * - 熱點模式的車、手機熱點沒開：卡片內「請開啟手機熱點」+「開啟熱點」；失敗卡 `hotspotHint` →「開啟熱點」，熱點一開自動重試。
 * - 切換到熱點模式的進度（`vm.hotspotSwitch`）顯示在操作台最上面；沒找到時取代失敗卡（重試 / 關閉）。
 * - 掃描 QR 一律直連（OLED 顯示 QR = 車子正在直連模式）。Wi-Fi 關著時先說明「第一次配對要開一下 Wi-Fi」
 *   （[PermDialog.WIFI_FIRST_PAIR]：只開開關、不用連網路），按「開啟 Wi-Fi」才開系統面板；連上後 shell 會邀請改用熱點模式。
 *   記住的車已是熱點模式時改說「車子沒找到手機熱點」（[PermDialog.HOTSPOT_CAR]）：打開熱點後自動在熱點上連線。
 * - 最近的車是熱點模式：Wi-Fi 關著也不顯示「Wi-Fi 已關閉」卡（除非正在等 Wi-Fi 直連）。
 */
@Composable
fun ConnectScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val c = B570Theme.colors

    val state by vm.connectionState.collectAsStateWithLifecycle()
    val savedCars by vm.savedCars.collectAsStateWithLifecycle()
    val currentCar by vm.currentCar.collectAsStateWithLifecycle()
    val carName by vm.currentCarName.collectAsStateWithLifecycle()
    val isDemo by vm.isDemo.collectAsStateWithLifecycle()
    val reconnectGaveUp by vm.reconnectGaveUp.collectAsStateWithLifecycle()
    val wifiEnabled by rememberWifiEnabled()
    val permitted = rememberConnectPermitted()
    val phoneHotspotOn by rememberPhoneHotspotOn { vm.isPhoneHotspotLikelyOn() }
    val hotspotSwitch by vm.hotspotSwitch.collectAsStateWithLifecycle()
    val reconnect by vm.reconnect.collectAsStateWithLifecycle()
    // 熱點上的車（熱點模式的車 Wi-Fi 掃描看不到）：手機熱點開著或有熱點模式的車、沒在連線時每 5 秒找一次
    val discoverActive = shouldDiscoverHotspot(phoneHotspotOn, savedCars) &&
        !state.isConnecting && !state.isSession && reconnect == null
    val foundOnHotspot by rememberHotspotCars(vm, discoverActive)
    val hotspotCars = if (discoverActive) foundOnHotspot else emptyList()
    val onHotspot = remember(hotspotCars) { hotspotCars.mapTo(HashSet()) { it.ssid } }
    // 附近的車（跟著這頁的生命週期掃描；連線中不掃）
    val nearbyUi = rememberNearbyUi(vm, permitted, wifiEnabled, connecting = state.isConnecting)

    // ---------------------------------------------------------------- 連線前檢查流程
    val flow = remember(vm, nav) { ConnectFlow(vm, nav) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        flow.onPermissionsResult(it)
    }
    // Wi-Fi 面板關閉後的結果由廣播 / onResume 處理
    val wifiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    flow.env = remember(context, permLauncher, wifiLauncher) {
        ConnectFlow.Env(
            context = context,
            requestPermissions = { perms -> permLauncher.launch(perms) },
            openWifiPanel = {
                try {
                    wifiLauncher.launch(LinkPermissions.wifiPanelIntent())
                } catch (e: ActivityNotFoundException) {
                    try {
                        wifiLauncher.launch(Intent(Settings.ACTION_WIFI_SETTINGS))
                    } catch (e2: ActivityNotFoundException) {
                        vm.notice("請到系統設定開啟 Wi-Fi", Severity.WARNING)
                    }
                }
            },
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { flow.onResume() }
    LaunchedEffect(wifiEnabled) {
        if (wifiEnabled) {
            delay(350) // 剛開啟時給系統一點時間開始掃描
            flow.onWifiEnabled()
        }
    }
    // 自動連線 / 桌面捷徑正在連的車：連線中改顯示大卡片「正在連線 <名稱>…」（[autoLabel] = 卡片上的標籤）
    var autoSsid by rememberSaveable { mutableStateOf<String?>(null) }
    var autoLabel by rememberSaveable { mutableStateOf("自動連線") }
    // 使用者自己點的連線（車子卡片、掃描、手動輸入、主按鈕）：一般步驟條。direct = 剛掃 QR（一律直連）
    fun connectTo(creds: WifiCredentials, direct: Boolean = false) {
        autoSsid = null
        flow.connect(creds, direct)
    }
    // 在熱點上找到的車：直接用它的位址連（不需要 Wi-Fi / 權限）
    val connectFound: (DiscoveredCar) -> Unit = { car ->
        autoSsid = null
        flow.cancelPending()
        vm.connectDiscovered(car)
    }
    val openHotspot: () -> Unit = {
        if (!openPhoneHotspotSettings(context)) vm.notice("請到手機設定開啟「個人熱點」", Severity.WARNING)
    }

    // 設定「開啟 App 自動連線」：Wi-Fi 與權限都就緒時自動連最近的車（repository 保證每個 App 行程最多一次，
    // 使用者主動中斷回到這頁不會又被連回去；展示模式自動進入、桌面捷徑啟動時不動作）。
    // 最近的車會先在手機熱點上找（熱點模式 / 熱點開著）→ 不必等 Wi-Fi 與權限（開熱點時 Wi-Fi 常是關的）
    val firstCar = savedCars.firstOrNull()
    val autoViaHotspot = remember(firstCar?.ssid, firstCar?.hotspotMode, firstCar?.password, phoneHotspotOn) {
        firstCar != null && runCatching { vm.connectPlanFor(firstCar.credentials).discover }.getOrDefault(false)
    }
    LaunchedEffect(wifiEnabled, permitted, autoViaHotspot) {
        if (((wifiEnabled && permitted) || autoViaHotspot) &&
            vm.connectionState.value == ConnectionState.Idle && vm.pendingConnect.value == null
        ) {
            vm.autoConnectOnLaunch()?.let { car ->
                autoLabel = "自動連線"
                autoSsid = car.ssid
            }
        }
    }

    var showManual by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SavedCar?>(null) }
    var forgetTarget by remember { mutableStateOf<SavedCar?>(null) }

    // 桌面捷徑「連線 <名稱>」：一樣先走權限 / Wi-Fi 檢查
    LaunchedEffect(flow) {
        vm.pendingConnect.collect { creds ->
            if (creds != null) {
                vm.consumePendingConnect(creds)
                flow.connect(creds)
                autoLabel = "桌面捷徑"
                autoSsid = creds.ssid
            }
        }
    }

    // 掃描頁交接過來的請求
    LaunchedEffect(flow) {
        ConnectHandoff.pending.collect { req ->
            when (req) {
                is ConnectRequest.Paired -> {
                    ConnectHandoff.consume(req)
                    // OLED 顯示 QR = 車子正在直連模式：直接直連，不先在熱點上找
                    connectTo(req.credentials, direct = true)
                }
                ConnectRequest.ManualEntry -> {
                    ConnectHandoff.consume(req)
                    showManual = true
                }
                null -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- 狀態回饋
    val connected = state is ConnectionState.Connected
    LaunchedEffect(connected) {
        if (connected) {
            haptics.confirm()
            // shell 會切到主畫面（沿用上次分頁）；從連線頁進入一律先到「駕駛」
            if (nav.currentTab != MainTab.DRIVE) nav.resetTo(Route.Main(MainTab.DRIVE))
        }
    }
    val failed = state as? ConnectionState.Failed
    LaunchedEffect(failed) {
        if (failed != null) {
            haptics.reject()
            autoSsid = null
        }
    }
    val lastFailed = remember { LastFailed() }
    if (failed != null) lastFailed.value = failed
    val lastActiveSwitch = remember { arrayOfNulls<HotspotSwitchStatus>(1) }

    // 「車子沒找到手機熱點」（剛掃 QR、熱點模式的車）按了確認：手機熱點開著就在熱點上連線（不用開 Wi-Fi）
    val waitingHotspotCar = flow.waitingHotspot
    LaunchedEffect(waitingHotspotCar, phoneHotspotOn) {
        if (waitingHotspotCar != null && phoneHotspotOn) flow.onPhoneHotspotOn()
    }

    // 失敗卡按了「開啟熱點」：手機熱點一開就自動重試（給車子一點時間連上熱點）
    var waitingHotspot by remember { mutableStateOf(false) }
    LaunchedEffect(failed == null) { if (failed == null) waitingHotspot = false }
    LaunchedEffect(waitingHotspot, phoneHotspotOn) {
        if (waitingHotspot && phoneHotspotOn) {
            delay(HOTSPOT_RETRY_DELAY_MS)
            waitingHotspot = false
            if (vm.connectionState.value is ConnectionState.Failed) flow.retry()
        }
    }

    // 連線中按返回 = 取消（不直接離開 App）；覆蓋層開著時交給 shell
    BackHandler(enabled = (state.isConnecting || failed != null) && nav.overlay == null) { vm.disconnect() }

    val introStart = remember { SystemClock.uptimeMillis() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val overlayOpen = nav.overlay != null
    val mood = when (state) {
        ConnectionState.Idle -> HeroMood.IDLE
        is ConnectionState.Discovering, is ConnectionState.RequestingWifi, ConnectionState.Handshaking -> HeroMood.CONNECTING
        is ConnectionState.Connected, is ConnectionState.LinkLost -> HeroMood.CONNECTED
        is ConnectionState.Failed -> HeroMood.FAILED
    }
    val phase = when (state) {
        ConnectionState.Idle -> PanelPhase.IDLE
        is ConnectionState.Failed -> PanelPhase.FAILED
        else -> PanelPhase.CONNECTING
    }
    // 手機熱點開著時 Wi-Fi 常是關的（熱點模式的車不需要 Wi-Fi）：只有在等 Wi-Fi 直連時才提示。
    // 最近的車已是熱點模式（記住的車依最近連線排序）：不叫他開 Wi-Fi，車子卡片會提示「請開啟手機熱點」
    val showWifiCard = showWifiOffCardOf(
        wifiEnabled = wifiEnabled,
        failedWifiDisabled = failed?.wifiDisabled == true,
        waitingWifi = flow.waitingWifi,
        phoneHotspotOn = phoneHotspotOn,
        hotspotUser = savedCars.firstOrNull()?.hotspotMode == true,
    )
    val listState = rememberLazyListState()

    // 快速連線：剛好一台記住的車在附近 → 主按鈕「連線 <名稱>」；沒有記住的車 → 配對主視覺
    // （在熱點上找到也算）
    val soleNearby = remember(savedCars, nearbyUi, onHotspot) { soleReachableCarOf(savedCars, nearbyUi.nearby, onHotspot) }
    val hero = idleHeroOf(savedCars, soleNearby)
    val unpaired = remember(savedCars, nearbyUi) { unpairedNearbyOf(nearbyUi.nearby, savedCars) }
    val hotspotNew = remember(savedCars, hotspotCars) { hotspotNewCarsOf(hotspotCars, savedCars) }
    val inRangeCount = remember(savedCars, nearbyUi, onHotspot) {
        savedCars.count { it.ssid in nearbyUi.nearby || it.ssid in onHotspot }
    }
    // 連線到記住的車：在熱點上找到 → 用找到的位址；否則智慧連線
    val connectSaved: (SavedCar) -> Unit = { car ->
        val found = hotspotCars.firstOrNull { it.ssid == car.ssid }
        if (found != null) connectFound(found) else connectTo(car.credentials)
    }
    val connectingHotspotCar = savedCars.firstOrNull { it.ssid == currentCar?.ssid }?.hotspotMode == true
    val autoCard = autoSsid != null && !isDemo && currentCar?.ssid == autoSsid

    // 第一次連這台車：系統會跳出「連線到裝置」視窗 → 0.7 秒後提示按「連線」
    val requestingSsid = (state as? ConnectionState.RequestingWifi)?.ssid
    val firstApproval = requestingSsid != null && !isDemo && isFirstConnection(requestingSsid, savedCars)
    var showApproval by remember { mutableStateOf(false) }
    LaunchedEffect(requestingSsid, firstApproval) {
        showApproval = false
        if (firstApproval) {
            delay(APPROVAL_HINT_DELAY_MS)
            showApproval = true
        }
    }

    // ---------------------------------------------------------------- 右側操作台（單欄時接在主視覺後面）
    val consoleItems: LazyListScope.(Modifier) -> Unit = { itemModifier ->
        item(key = "panel") {
            Column(itemModifier.entrance(3, introStart)) {
                // 切換到熱點模式中：進度卡在最上面（重新開機 → 請開啟手機熱點 → 尋找車子）；沒找到時取代下面的失敗卡
                val sw = hotspotSwitch?.takeIf { it.isActive }
                if (sw != null) lastActiveSwitch[0] = sw
                AnimatedVisibility(sw != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    // 退場動畫期間 sw 已是 null：沿用最後一筆（非 state 暫存，組合期間寫入安全）
                    val shown = sw ?: lastActiveSwitch[0]
                    if (shown != null) {
                        HotspotSwitchPanel(
                            status = shown,
                            onOpenHotspot = openHotspot,
                            onRetry = { flow.retry() },
                            onDismiss = { vm.dismissHotspotSwitch() },
                            phoneHotspotOn = phoneHotspotOn || shown.phoneHotspotOn,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                AnimatedContent(
                    targetState = phase,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        (fadeIn(tween(260, delayMillis = 60)) +
                            scaleIn(spring(dampingRatio = 0.8f, stiffness = 380f), initialScale = 0.94f) +
                            slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it / 10 })
                            .togetherWith(fadeOut(tween(150)) + scaleOut(tween(200), targetScale = 0.97f))
                            .using(SizeTransform(clip = false))
                    },
                    contentAlignment = Alignment.TopCenter,
                    label = "connectPanel",
                ) { p ->
                    when (p) {
                        PanelPhase.IDLE -> AnimatedContent(
                            targetState = hero,
                            transitionSpec = {
                                fadeIn(tween(220, delayMillis = 40)).togetherWith(fadeOut(tween(120))).using(SizeTransform(clip = false))
                            },
                            contentAlignment = Alignment.TopCenter,
                            label = "idleHero",
                        ) { h ->
                            IdleActions(
                                hero = h,
                                quickCar = soleNearby,
                                quickDbm = soleNearby?.let { nearbyUi.nearby[it.ssid] },
                                onQuickConnect = { soleNearby?.let { connectSaved(it) } },
                                onScan = { flow.startPairing() },
                                onManual = { showManual = true },
                                onDemo = { vm.connectDemo() },
                                sheenPaused = overlayOpen,
                            )
                        }
                        PanelPhase.CONNECTING -> ConnectStepper(
                            state = state,
                            carName = carName,
                            ssid = currentCar?.ssid,
                            isDemo = isDemo,
                            onCancel = {
                                autoSsid = null
                                vm.disconnect()
                            },
                            autoLabel = autoLabel.takeIf { autoCard },
                            phoneHotspotOn = phoneHotspotOn,
                            hotspotCar = connectingHotspotCar,
                            onOpenHotspot = openHotspot,
                        )
                        PanelPhase.FAILED -> {
                            val switchFailed = hotspotSwitch?.takeIf { it.phase == HotspotSwitchPhase.FAILED }
                            if (switchFailed != null) {
                                HotspotSwitchPanel(
                                    status = switchFailed,
                                    onOpenHotspot = openHotspot,
                                    onRetry = { flow.retry() },
                                    onDismiss = { vm.dismissHotspotSwitch() },
                                    phoneHotspotOn = phoneHotspotOn,
                                )
                            } else {
                                lastFailed.value?.let { f ->
                                    FailedCard(
                                        failed = f,
                                        onRetry = { flow.retry() },
                                        onOpenWifi = { flow.enableWifiAndRetry() },
                                        onRescan = { flow.startPairing() },
                                        onDismiss = { vm.disconnect() },
                                        autoRetries = reconnectGaveUp?.attempt,
                                        onOpenHotspot = {
                                            waitingHotspot = true
                                            openHotspot()
                                        },
                                        waitingHotspot = waitingHotspot && !phoneHotspotOn,
                                        phoneHotspotOn = phoneHotspotOn,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showWifiCard) {
            item(key = "wifi") {
                WifiOffCard(
                    pendingName = flow.pending?.takeIf { flow.waitingWifi }?.let { p ->
                        savedCars.firstOrNull { it.ssid == p.ssid }?.displayName ?: p.ssid
                    },
                    onOpen = { flow.openWifiPanel() },
                    onCancelPending = { flow.cancelPending() },
                    modifier = itemModifier.animateItem(),
                )
            }
        }
        // 手機熱點上找到、還沒記住的車（別支手機設成熱點模式的）：不用掃 QR，點一下連線
        if (phase != PanelPhase.CONNECTING) {
            items(hotspotNew, key = { "hot:${it.ssid}" }) { car ->
                HotspotNewCarCard(car, onConnect = { connectFound(car) }, modifier = itemModifier.animateItem())
            }
        }
        // 附近還沒配對的新車（B570- 熱點）：點一下開掃描
        if (phase != PanelPhase.CONNECTING) {
            items(unpaired, key = { (ssid, _) -> "new:$ssid" }) { (ssid, dbm) ->
                NearbyNewCarCard(ssid, dbm, onPair = { flow.startPairing() }, modifier = itemModifier.animateItem())
            }
        }
        // 還沒有記住的車、未連線：配對主視覺已有三步驟說明，不再重複空清單
        val showSaved = savedCars.isNotEmpty() || phase != PanelPhase.IDLE
        if (showSaved) {
            item(key = "savedHeader") {
                SectionHeader(
                    "記住的車子",
                    itemModifier.padding(top = 4.dp).entrance(4, introStart),
                    subtitle = when {
                        savedCars.isEmpty() -> null
                        inRangeCount > 0 -> "$inRangeCount 台在附近 · 點一下連線，長按可改名或忘記"
                        else -> "點一下連線，長按可改名或忘記"
                    },
                    trailing = if (nearbyUi.active && savedCars.isNotEmpty()) {
                        { RefreshNearbyButton(onRefresh = { vm.refreshNearby() }) }
                    } else {
                        null
                    },
                )
            }
        }
        if (savedCars.isEmpty()) {
            if (showSaved) {
                item(key = "savedEmpty") {
                    EmptySavedCars(itemModifier.animateItem().entrance(5, introStart))
                }
            }
        } else {
            itemsIndexed(savedCars, key = { _, car -> "car:${car.ssid}" }) { i, car ->
                val connectingThis = state.isConnecting && !isDemo && currentCar?.ssid == car.ssid
                val badge = nearbyBadgeOf(car.ssid, nearbyUi.nearby, nearbyUi.usable, onHotspot, car.hotspotMode)
                SavedCarCard(
                    car = car,
                    nowMs = now,
                    connecting = connectingThis,
                    recent = i == 0 && car.lastConnected > 0L,
                    onConnect = { if (!connectingThis) connectSaved(car) },
                    onRename = { renameTarget = car },
                    onForget = { forgetTarget = car },
                    modifier = itemModifier.animateItem().entrance(5 + i.coerceAtMost(5), introStart),
                    badge = badge,
                    hotspotHint = needsHotspotHint(car, phoneHotspotOn, badge),
                    onOpenHotspot = openHotspot,
                )
            }
        }
        item(key = "footer") {
            ConnectFooter(
                onDemo = { vm.connectDemo() },
                showDemo = phase != PanelPhase.IDLE || hero == IdleHero.QUICK_CONNECT,
                modifier = itemModifier.padding(top = 4.dp).entrance(6, introStart),
            )
        }
    }

    BoxWithConstraints(modifier) {
        val pad = LocalScreenPadding.current
        val ld = LocalLayoutDirection.current
        val sL = pad.calculateLeftPadding(ld)
        val sR = pad.calculateRightPadding(ld)
        val sT = pad.calculateTopPadding()
        val sB = pad.calculateBottomPadding()
        val availW = maxWidth - sL - sR
        val panes = connectPanesOf(availW.value)

        // 開始連線 / 失敗時，步驟條或失敗卡若不在畫面內（例如從清單下方點了某台車）就捲回去，取消 / 重試一定看得到
        val panelIndex = if (panes.split) 0 else 1
        LaunchedEffect(phase, panes.split) {
            if (phase == PanelPhase.IDLE) return@LaunchedEffect
            delay(120)
            val info = listState.layoutInfo
            val panel = info.visibleItemsInfo.firstOrNull { it.key == "panel" }
            val fullyVisible = panel != null &&
                panel.offset >= info.viewportStartOffset &&
                panel.offset + panel.size <= info.viewportEndOffset
            if (!fullyVisible) listState.animateScrollToItem(panelIndex)
        }

        if (panes.split) {
            // 操作台清單左右各留 8 dp 給卡片光暈（清單會裁切到自己的邊界），外距與間距扣回來
            Row(
                Modifier
                    .fillMaxSize()
                    .absolutePadding(left = sL + panes.sideMargin.dp, right = sR + (panes.sideMargin - 8f).dp),
            ) {
                HeroPane(
                    state = state,
                    mood = mood,
                    paused = overlayOpen,
                    introStart = introStart,
                    modifier = Modifier
                        .width(panes.heroWidth.dp)
                        .fillMaxHeight()
                        .padding(top = sT + 12.dp, bottom = sB + 12.dp),
                )
                Spacer(Modifier.width((panes.gap - 8f).dp))
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    state = listState,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = sT + 12.dp, bottom = sB + 16.dp),
                    // 內容比欄矮（平板）時垂直置中，比欄高時就是一般捲動清單
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    consoleItems(Modifier.fillMaxWidth())
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = sL + 16.dp, end = sR + 16.dp, top = sT + 8.dp, bottom = sB + 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val itemModifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                item(key = "hero") {
                    Column(itemModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                        ConnectTopRow(state, Modifier.entrance(0, introStart))
                        GyroHero(mood, paused = overlayOpen, modifier = Modifier.fillMaxWidth().height(150.dp).entrance(0, introStart))
                        BrandTitle(Modifier.entrance(1, introStart), maxWidth = min(availW - 32.dp, 520.dp))
                        Text(
                            "自平衡車遙控系統",
                            style = B570Theme.type.bodySmall.copy(letterSpacing = 4.sp),
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp).entrance(2, introStart),
                        )
                    }
                }
                consoleItems(itemModifier)
            }
        }

        // 第一次連這台車：提示在系統視窗按「連線」（不攔觸控）。不能蓋住連線卡的「取消」：
        // 分欄時放在左側主視覺上緣（只佔主視覺那一欄）；單欄時連線卡會捲到最上面，提示改放下緣
        ApprovalHint(
            visible = showApproval && state is ConnectionState.RequestingWifi && !overlayOpen,
            modifier = if (panes.split) {
                Modifier
                    .align(Alignment.TopStart)
                    .absolutePadding(left = sL + panes.sideMargin.dp, top = sT + 10.dp)
                    .width(panes.heroWidth.dp)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = sL + 16.dp, end = sR + 16.dp, bottom = sB + 12.dp)
            },
            fromTop = panes.split,
        )
    }

    // ---------------------------------------------------------------- 對話框
    flow.dialog?.let { d ->
        PermissionDialog(
            kind = d,
            connectMissing = LinkPermissions.missingForConnect(context).isNotEmpty(),
            onConfirm = { flow.onDialogConfirm() },
            onDismiss = { flow.onDialogDismiss() },
            phoneHotspotOn = phoneHotspotOn,
            firstPairPromise = flow.firstPairPromise,
        )
    }
    if (showManual) {
        ManualEntryDialog(
            onConnect = { creds ->
                showManual = false
                connectTo(creds)
            },
            onDismiss = { showManual = false },
        )
    }
    renameTarget?.let { car ->
        RenameCarDialog(
            ssid = car.ssid,
            current = car.nickname,
            onSave = { nick ->
                renameTarget = null
                vm.renameCar(car.ssid, nick)
            },
            onDismiss = { renameTarget = null },
        )
    }
    forgetTarget?.let { car ->
        ConfirmDialog(
            title = "忘記這台車？",
            message = "會刪除「${car.displayName}」的連線資料，之後要重新掃描 QR Code 才能連線。",
            confirmText = "忘記",
            danger = true,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = {
                forgetTarget = null
                vm.forgetCar(car.ssid)
            },
            onDismiss = { forgetTarget = null },
        )
    }
}

/** RequestingWifi 持續這麼久（第一次連線的車）才顯示「請在系統視窗按『連線』」。 */
private const val APPROVAL_HINT_DELAY_MS = 700L

/** 失敗卡按「開啟熱點」後，手機熱點開了再等這麼久才自動重試（車子要一點時間連上熱點）。 */
private const val HOTSPOT_RETRY_DELAY_MS = 2_500L

/** 記住的車子標題旁的「重新掃描附近的車」。 */
@Composable
private fun RefreshNearbyButton(onRefresh: () -> Unit) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    IconButton(onClick = { haptics.tick(); onRefresh() }, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Rounded.Refresh, contentDescription = "重新掃描附近的車", tint = c.textSecondary, modifier = Modifier.size(20.dp))
    }
}

/**
 * 左側主視覺：狀態膠囊 + 版本、陀螺儀（填滿剩餘高度）、品牌標題（依欄寬自動縮放）、副標。
 * 平板（欄高 ≥ 520 dp）標題放大；太矮（分割畫面）省略副標。
 */
@Composable
private fun HeroPane(
    state: ConnectionState,
    mood: HeroMood,
    paused: Boolean,
    introStart: Long,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    BoxWithConstraints(modifier) {
        val paneWidth = maxWidth
        val tall = maxHeight >= 520.dp
        val showSubtitle = maxHeight >= 220.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ConnectTopRow(state, Modifier.entrance(0, introStart))
            GyroHero(mood, paused = paused, modifier = Modifier.weight(1f).fillMaxWidth().entrance(0, introStart))
            BrandTitle(Modifier.entrance(1, introStart), maxWidth = paneWidth, baseSize = if (tall) 46.sp else 34.sp)
            if (showSubtitle) {
                Text(
                    "自平衡車遙控系統",
                    style = B570Theme.type.bodySmall.copy(letterSpacing = if (tall) 6.sp else 4.sp),
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).entrance(2, introStart),
                )
            }
        }
    }
}
