package com.b570.pilot.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.MainTab
import com.b570.pilot.ui.Route
import com.b570.pilot.ui.components.CarEventToastHost
import com.b570.pilot.ui.components.ConfirmDialog
import com.b570.pilot.ui.components.LocalHapticsEnabled
import com.b570.pilot.ui.components.LocalLandscapeClass
import com.b570.pilot.ui.components.LocalScreenPadding
import com.b570.pilot.ui.components.LocalWindowDpSize
import com.b570.pilot.ui.components.WindowDpSize
import com.b570.pilot.ui.components.landscapeClassOf
import com.b570.pilot.ui.components.safeAreaInsets
import com.b570.pilot.ui.screens.choreo.ChoreoScreen
import com.b570.pilot.ui.screens.connect.ConnectScreen
import com.b570.pilot.ui.screens.drive.DriveScreen
import com.b570.pilot.ui.screens.monitor.MonitorScreen
import com.b570.pilot.ui.screens.scanner.QrScannerScreen
import com.b570.pilot.ui.screens.settings.SettingsScreen
import com.b570.pilot.ui.screens.tune.TuneScreen
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.cockpitBackground
import kotlinx.coroutines.delay

/** 左側導覽軌寬度。 */
val RailWidth = 72.dp

/** 導覽軌與安全區左緣的距離。 */
val RailMargin = 8.dp

/** 導覽軌在版面上佔的寬度（安全區左緣 → 導覽軌右緣）= [RailMargin] + [RailWidth]。 */
val RailReserve = RailMargin + RailWidth

/** 頂部狀態條高度（不含安全區上緣）。 */
val StatusStripHeight = 48.dp

/** 一般主分頁內容的下緣留白（加在安全區下緣之上）。 */
val ContentBottomMargin = 8.dp

/** 提示的最大寬度（橫向）。 */
private val ToastMaxWidth = 420.dp

/**
 * App 根（橫向）：主題、背景、尺寸級距、導覽與轉場、左側導覽軌 + 頂部狀態條（駕駛分頁全螢幕時隱藏）、
 * 駕駛分頁的滑入式導覽、提示、中斷連線確認、第一次直連後的「改用熱點模式」邀請（[HotspotOnboardingHost]）。
 */
@Composable
fun B570Root(vm: AppViewModel, nav: AppNavigator = remember { AppNavigator() }) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.connectionState.collectAsStateWithLifecycle()
    // 只關心「有沒有在重連」：第幾次 / 倒數的變化不讓根重組，也不重啟下面的切頁判斷
    val reconnectState = vm.reconnect.collectAsStateWithLifecycle()
    val reconnecting by remember { derivedStateOf { reconnectState.value != null } }

    // 連線狀態 → 自動切換頁面（規則見 rootRouteOf：自動重連中不跳回連線頁，放棄後才切過去顯示原因 + 重試）
    LaunchedEffect(state, reconnecting) {
        when (rootRouteOf(state, reconnecting)) {
            RootRoute.MAIN -> if (nav.base !is Route.Main) nav.resetTo(Route.Main(nav.currentTab))
            RootRoute.CONNECT -> if (nav.base is Route.Main) {
                if (state is ConnectionState.Failed) {
                    // repository 先設 Failed 才排重連：等一下再確認，避免先跳連線頁又跳回來
                    delay(ROUTE_FAILED_GRACE_MS)
                    if (vm.reconnect.value != null) return@LaunchedEffect
                }
                nav.resetTo(Route.Connect)
            }
            RootRoute.STAY -> Unit
        }
    }

    B570Theme(accent = settings.accent) {
        CompositionLocalProvider(LocalHapticsEnabled provides settings.hapticsEnabled) {
            BoxWithConstraints(Modifier.fillMaxSize().cockpitBackground()) {
                val safe = safeAreaInsets()
                val ld = LocalLayoutDirection.current
                val winW = maxWidth - safe.calculateStartPadding(ld) - safe.calculateEndPadding(ld)
                val winH = maxHeight - safe.calculateTopPadding() - safe.calculateBottomPadding()
                CompositionLocalProvider(
                    LocalLandscapeClass provides landscapeClassOf(winW, winH),
                    LocalWindowDpSize provides WindowDpSize(winW, winH),
                ) {
                    var askDisconnect by remember { mutableStateOf(false) }
                    val inMain = nav.base is Route.Main

                    BackHandler(enabled = nav.canGoBack || inMain) {
                        if (!nav.back() && inMain) askDisconnect = true
                    }

                    // 根頁：Connect ⇄ Main（戲劇性放大淡入）
                    AnimatedContent(
                        targetState = inMain,
                        transitionSpec = {
                            if (targetState) {
                                (fadeIn(tween(450, delayMillis = 80)) + scaleIn(tween(550, easing = FastOutSlowInEasing), initialScale = 0.86f))
                                    .togetherWith(fadeOut(tween(250)) + scaleOut(tween(400), targetScale = 1.18f))
                            } else {
                                (fadeIn(tween(400, delayMillis = 60)) + scaleIn(tween(450), initialScale = 1.12f))
                                    .togetherWith(fadeOut(tween(250)) + scaleOut(tween(350), targetScale = 0.9f))
                            }
                        },
                        label = "root",
                    ) { main ->
                        if (main) {
                            MainScaffold(vm, nav, safe, onAskDisconnect = { askDisconnect = true })
                        } else {
                            CompositionLocalProvider(LocalScreenPadding provides safe) {
                                ConnectScreen(vm, nav, Modifier.fillMaxSize())
                            }
                        }
                    }

                    // 覆蓋層（由下往上）
                    // 退場動畫期間仍要畫出剛關閉的覆蓋層（非 state 暫存，組合期間寫入安全）
                    val overlayNow = nav.overlay
                    val lastOverlay = remember { arrayOfNulls<Route>(1) }
                    if (overlayNow != null) lastOverlay[0] = overlayNow
                    val shownOverlay = overlayNow ?: lastOverlay[0]
                    AnimatedVisibility(
                        visible = overlayNow != null,
                        enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it } + fadeIn(),
                        exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(280)),
                    ) {
                        CompositionLocalProvider(LocalScreenPadding provides safe) {
                            when (shownOverlay) {
                                Route.QrScanner -> QrScannerScreen(vm, nav, Modifier.fillMaxSize())
                                else -> Unit
                            }
                        }
                    }

                    // 提示：內容區上緣置中。駕駛（全螢幕）時更窄、避開左右上角的肩鍵，且不攔截觸控（下面的控制照樣按得到）
                    val fullScreen = inMain && nav.currentTab.fullScreen && nav.overlay == null
                    val toastTop = safe.calculateTopPadding() + when {
                        fullScreen -> 8.dp
                        inMain -> StatusStripHeight + 4.dp
                        else -> 12.dp
                    }
                    val toastStart = safe.calculateStartPadding(ld) + if (inMain && !fullScreen) RailReserve else 0.dp
                    CarEventToastHost(
                        vm.events,
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(start = toastStart, end = safe.calculateEndPadding(ld), top = toastTop),
                        maxVisible = 2,
                        maxWidth = if (fullScreen) (winW * 0.42f).coerceIn(240.dp, ToastMaxWidth) else ToastMaxWidth,
                        dismissOnClick = !fullScreen,
                    )

                    // 第一次直連成功後邀請「帶出去玩：改用熱點模式」（每台車一次）。只在主畫面組合：
                    // 套用後車子重新開機、在熱點上找車時（Discovering）shell 留在主畫面，對話框繼續顯示進度；
                    // 沒找到（Failed）才切到連線頁，由連線頁的切換卡接手
                    if (inMain) HotspotOnboardingHost(vm, nav, otherDialogOpen = askDisconnect)

                    if (askDisconnect) {
                        ConfirmDialog(
                            title = "中斷連線？",
                            message = "中斷後車子會停在原地保持平衡，你可以隨時重新連線。",
                            confirmText = "中斷連線",
                            onConfirm = { askDisconnect = false; nav.closeNavOverlay(); vm.disconnect() },
                            onDismiss = { askDisconnect = false },
                            icon = Icons.Rounded.LinkOff,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 主畫面（橫向）：
 * - 一般分頁：左側玻璃導覽軌 + 右側頂部狀態條（含訊號中斷提示），內容畫在兩者「底下」（[LocalScreenPadding]）。
 * - 全螢幕分頁（駕駛）：不畫導覽軌與狀態條，[LocalScreenPadding] 只有安全區；用 [AppNavigator.openNavOverlay] 叫出滑入式導覽。
 * - 切換分頁：依導覽軌順序垂直滑動 + 淡入（往下的分頁由下往上進場）。
 * 每個分頁的 rememberSaveable 狀態（捲動位置、暫停…）由 SaveableStateHolder 保留，切換分頁回來不會重置。
 */
@Composable
private fun MainScaffold(vm: AppViewModel, nav: AppNavigator, safe: PaddingValues, onAskDisconnect: () -> Unit) {
    val ld = LocalLayoutDirection.current
    val c = B570Theme.colors
    val sStart = safe.calculateStartPadding(ld)
    val sEnd = safe.calculateEndPadding(ld)
    val sTop = safe.calculateTopPadding()
    val sBottom = safe.calculateBottomPadding()
    val mainPadding = PaddingValues(
        start = sStart + RailReserve,
        top = sTop + StatusStripHeight,
        end = sEnd,
        bottom = sBottom + ContentBottomMargin,
    )
    val tabStates = rememberSaveableStateHolder()
    val chrome = !nav.currentTab.fullScreen
    val onEStop: () -> Unit = {
        vm.stopControl()
        vm.emergencyStop()
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = nav.currentTab,
            transitionSpec = {
                val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                (slideInVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) { h -> dir * h / 4 } + fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.98f))
                    .togetherWith(slideOutVertically(tween(260)) { h -> -dir * h / 5 } + fadeOut(tween(180)) + scaleOut(tween(260), targetScale = 0.98f))
            },
            label = "tabs",
        ) { tab ->
            val m = Modifier.fillMaxSize()
            CompositionLocalProvider(LocalScreenPadding provides if (tab.fullScreen) safe else mainPadding) {
                tabStates.SaveableStateProvider(tab.name) {
                    when (tab) {
                        MainTab.DRIVE -> DriveScreen(vm, nav, m)
                        MainTab.MONITOR -> MonitorScreen(vm, nav, m)
                        MainTab.TUNE -> TuneScreen(vm, nav, m)
                        MainTab.CHOREO -> ChoreoScreen(vm, nav, m)
                        MainTab.SETTINGS -> SettingsScreen(vm, nav, m)
                    }
                }
            }
        }

        // 頂部狀態條（導覽軌右側；漸層底，捲動內容從下面穿過）
        AnimatedVisibility(
            visible = chrome,
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
        ) {
            StatusStrip(
                vm,
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0.94f), c.background.copy(alpha = 0.78f), c.background.copy(alpha = 0f))))
                    .padding(start = sStart + RailReserve, end = sEnd, top = sTop),
            )
        }

        // 左側導覽軌
        AnimatedVisibility(
            visible = chrome,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight(),
            enter = fadeIn(tween(220)) + slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)) { -it },
            exit = fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { -it },
        ) {
            NavRail(
                selected = nav.currentTab,
                onSelect = { nav.selectTab(it) },
                onEStop = onEStop,
                modifier = Modifier
                    .padding(start = sStart + RailMargin, top = sTop + 8.dp, bottom = sBottom + 8.dp)
                    .fillMaxHeight(),
            )
        }

        // 全螢幕分頁（駕駛）沒有狀態條：自動重連提示由駕駛上緣列自己畫（DriveTopBar 的車況膠囊那一格）；
        // 滑入式導覽打開時，車況面板也會顯示

        // 全螢幕分頁（駕駛）的滑入式導覽（關閉時不畫任何東西、不攔觸控；一直留在組合中讓選分頁時有退場動畫）
        NavOverlay(vm, nav, safe, onEStop = onEStop, onAskDisconnect = onAskDisconnect)
    }
}
