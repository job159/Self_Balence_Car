package com.b570.pilot.ui.screens.scanner

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.b570.pilot.data.Severity
import com.b570.pilot.link.LinkPermissions
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.Route
import com.b570.pilot.ui.components.LocalScreenPadding
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.screens.connect.ConnectHandoff
import com.b570.pilot.ui.screens.connect.ConnectRequest
import com.b570.pilot.ui.screens.connect.findActivity
import com.b570.pilot.ui.theme.B570Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * QR 掃描（覆蓋層，由下往上滑出；橫向）。
 * CameraX 全螢幕預覽 + ML Kit（只認 QR Code）。掃到 B570 配對碼：震動 + 角框吸附轉綠 →
 * 記住這台車 → 交給連線頁（檢查權限 / Wi-Fi 後連線）→ 關閉。
 *
 * 橫向配置（[scanLayoutOf]）：掃描框置中於安全區、下方提示膠囊；
 * 左側 = 關閉 + 標題、說明卡、改用手動輸入；右側（右手拇指）= 手電筒、直立縮放列、框中央對焦。
 * 點畫面任一處對焦、兩指縮放。
 */
@Composable
fun QrScannerScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val insets = LocalScreenPadding.current
    val ld = LocalLayoutDirection.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(LinkPermissions.hasCamera(context)) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        permanentlyDenied = !ok && context.findActivity()
            ?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, LinkPermissions.CAMERA) } == true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = LinkPermissions.hasCamera(context) }

    val scan = remember { ScannerController() }
    val focusRing = remember { FocusRing() }
    // 只有底下是連線頁時才提供「手動輸入」（由連線頁開對話框）
    val canManual = nav.base is Route.Connect
    val openManual = {
        ConnectHandoff.post(ConnectRequest.ManualEntry)
        nav.closeOverlay()
    }
    val close = {
        haptics.click()
        nav.closeOverlay()
    }

    // 成功：記住 → 等動畫 → 交給連線頁 → 關閉
    val success = scan.success
    LaunchedEffect(success) {
        val s = success ?: return@LaunchedEffect
        haptics.confirm()
        vm.onQrScanned(s.credentials, connectNow = false)
        delay(750)
        val onConnectPage = nav.base is Route.Connect
        val needsChecks = LinkPermissions.missingForConnect(context).isNotEmpty() || !LinkPermissions.isWifiEnabled(context)
        if (onConnectPage || needsChecks) {
            // 連線頁負責權限 / Wi-Fi 檢查；交接值會保留到連線頁收下為止
            ConnectHandoff.post(ConnectRequest.Paired(s.credentials))
            if (!onConnectPage) vm.disconnect()
        } else {
            // OLED 顯示 QR = 車子正在直連模式：直接直連（不先在手機熱點上找）
            vm.connectDirect(s.credentials)
        }
        nav.closeOverlay()
    }

    BoxWithConstraints(modifier.background(Color.Black)) {
        val g = scanLayoutOf(
            width = maxWidth.value,
            height = maxHeight.value,
            insetLeft = insets.calculateLeftPadding(ld).value,
            insetTop = insets.calculateTopPadding().value,
            insetRight = insets.calculateRightPadding(ld).value,
            insetBottom = insets.calculateBottomPadding().value,
            fontScale = density.fontScale,
        )
        val showTitle = g.panelWidth >= 120f

        if (granted) {
            val previewView = rememberPreviewView()
            CameraBinding(scan, previewView) { msg -> vm.notice(msg, Severity.WARNING) }
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize().onSizeChanged {
                    scan.viewWidth = it.width
                    scan.viewHeight = it.height
                },
            )
            // 相機出畫面前的黑幕（淡出）
            val cover by animateFloatAsState(if (scan.status == CamStatus.STREAMING) 0f else 1f, tween(450), label = "cover")
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover }.background(Color.Black))

            ScanOverlay(
                scan = scan,
                windowSide = g.frameSide.dp,
                windowCenterX = g.frameCx.dp,
                windowCenterY = g.frameCy.dp,
                focusRing = focusRing,
                onTap = { p ->
                    haptics.tick()
                    scan.focusAt(previewView, p.x, p.y)
                },
                onPinch = { scan.pinch(it) },
                modifier = Modifier.fillMaxSize(),
            )
            if (scan.status == CamStatus.STARTING) {
                CircularProgressIndicator(
                    Modifier.absoluteOffset(x = g.frameCx.dp - 16.dp, y = g.frameCy.dp - 16.dp).size(32.dp),
                    color = B570Theme.colors.accent, strokeWidth = 2.5.dp,
                )
            }
            Box(
                Modifier
                    .absoluteOffset(x = (g.frameCx - g.pillWidth / 2f).dp, y = g.pillTop.dp)
                    .width(g.pillWidth.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                HintPill(scan)
            }

            val cameraUsable = scan.status != CamStatus.ERROR && scan.status != CamStatus.NO_CAMERA
            if (cameraUsable) {
                // 左側：說明 + 手動輸入（關閉列下方）
                val panelTop = g.contentTop + SCAN_CLOSE_BAR_H + 12f
                ScanGuidePanel(
                    canManual = canManual,
                    onManual = openManual,
                    compact = g.compactPanel,
                    modifier = Modifier
                        .absoluteOffset(x = g.contentLeft.dp, y = panelTop.dp)
                        .width(g.panelWidth.coerceAtLeast(SCAN_RAIL_W).dp)
                        .height((g.contentBottom - panelTop).coerceAtLeast(0f).dp),
                )
                // 右側：手電筒 / 縮放 / 對焦
                ScanRightRail(
                    scan = scan,
                    layout = g,
                    onTorch = {
                        haptics.click()
                        scan.toggleTorch()
                    },
                    onFocusCenter = {
                        haptics.tick()
                        val p = with(density) { Offset(g.frameCx.dp.toPx(), g.frameCy.dp.toPx()) }
                        scope.launch { focusRing.play(p) }
                        scan.focusAt(previewView, p.x, p.y)
                    },
                )
            }

            when (scan.status) {
                CamStatus.ERROR -> CenterMessage(
                    icon = Icons.Rounded.NoPhotography,
                    title = "無法開啟相機",
                    message = "相機可能正被其他 App 使用，關掉後再試一次。",
                    primaryText = "重試",
                    primaryIcon = Icons.Rounded.Refresh,
                    onPrimary = { scan.retryBind() },
                    canManual = canManual,
                    onManual = openManual,
                )
                CamStatus.NO_CAMERA -> CenterMessage(
                    icon = Icons.Rounded.NoPhotography,
                    title = "找不到可用的相機",
                    message = "這支手機沒辦法用相機掃描，請改用手動輸入。",
                    primaryText = null,
                    primaryIcon = null,
                    onPrimary = {},
                    canManual = canManual,
                    onManual = openManual,
                )
                else -> Unit
            }
        } else {
            CenterMessage(
                icon = Icons.Rounded.PhotoCamera,
                title = "需要相機權限",
                message = if (permanentlyDenied) "相機權限已關閉，請到系統設定開啟，或改用手動輸入。"
                else "用來掃描車上 OLED 螢幕的 QR Code，影像只在手機上辨識，不會儲存。",
                primaryText = if (permanentlyDenied) "前往設定" else "允許相機",
                primaryIcon = if (permanentlyDenied) Icons.Rounded.Settings else Icons.Rounded.PhotoCamera,
                onPrimary = {
                    if (permanentlyDenied) {
                        try {
                            context.startActivity(LinkPermissions.appSettingsIntent(context))
                        } catch (e: ActivityNotFoundException) {
                            vm.notice("請到系統設定 > 應用程式開啟相機權限", Severity.WARNING)
                        }
                    } else {
                        permLauncher.launch(LinkPermissions.CAMERA)
                    }
                },
                canManual = canManual,
                onManual = openManual,
                background = true,
            )
        }

        // 左上角：關閉 + 標題（永遠在最上層）
        ScanCloseBar(
            onClose = close,
            showTitle = showTitle,
            modifier = Modifier
                .absoluteOffset(x = g.contentLeft.dp, y = g.contentTop.dp)
                .width(g.panelWidth.coerceAtLeast(SCAN_RAIL_W).dp),
        )
    }
}

/**
 * 右側控制列（貼安全區右緣）：上 = 手電筒、中 = 直立縮放、下 = 框中央對焦。
 * 縮放列高度依可用空間（最高 300 dp），太矮時省略放大 / 縮小鍵、只留滑桿。
 */
@Composable
private fun ScanRightRail(
    scan: ScannerController,
    layout: ScanLayout,
    onTorch: () -> Unit,
    onFocusCenter: () -> Unit,
) {
    val g = layout
    val x = g.railLeft.dp
    val buttonH = 62f // 44 dp 圓鍵 + 小字
    val streaming = scan.status == CamStatus.STREAMING
    if (scan.hasTorch && streaming) {
        RailButton(
            icon = if (scan.torchOn) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
            label = "手電筒",
            description = if (scan.torchOn) "關閉手電筒" else "開啟手電筒",
            onClick = onTorch,
            active = scan.torchOn,
            modifier = Modifier.absoluteOffset(x = x, y = g.contentTop.dp),
        )
    }
    if (streaming) {
        RailButton(
            icon = Icons.Rounded.CenterFocusStrong,
            label = "對焦",
            description = "對準掃描框中央對焦",
            onClick = onFocusCenter,
            modifier = Modifier.absoluteOffset(x = x, y = (g.contentBottom - buttonH).dp),
        )
    }
    if (scan.zoomAvailable && streaming) {
        val top = g.contentTop + buttonH + 10f
        val bottom = g.contentBottom - buttonH - 10f
        val h = min(bottom - top, 300f)
        if (h >= 96f) {
            ZoomRail(
                scan = scan,
                showSteps = h >= 200f,
                modifier = Modifier
                    .absoluteOffset(x = x, y = (top + (bottom - top - h) / 2f).dp)
                    .height(h.dp),
            )
        }
    }
}
