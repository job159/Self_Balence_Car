package com.b570.pilot.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.ControlSource
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.BatteryChip
import com.b570.pilot.ui.components.ConnectionChip
import com.b570.pilot.ui.components.ControlLatencyChip
import com.b570.pilot.ui.components.HudIconButton
import com.b570.pilot.ui.components.LinkLostBadge
import com.b570.pilot.ui.components.LinkModeChip
import com.b570.pilot.ui.components.LocalWindowDpSize
import com.b570.pilot.ui.components.LinkQualityChip
import com.b570.pilot.ui.components.MotorChip
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.SignalChip
import com.b570.pilot.ui.components.diagnosisColor
import com.b570.pilot.ui.components.latencyDetailZh
import com.b570.pilot.ui.components.latencyDiagnosisOf
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass

/**
 * 遙控器上緣的狀態膠囊列（給全螢幕的駕駛畫面用）：連線（訊號中斷時換成失聯警告、自動重連中換成重連進度 + 取消）、電量 % + 電壓、
 * 訊號、連線方式（熱點 / 直連）、控制延遲「控制 23 ms」、連線品質。自己收集 `telemetrySlow` / `linkStats`（≤ 10 Hz），只有這一列會重組。
 *
 * @param compact true = 精簡（不顯示電壓、連線品質、展示標籤），小手機用
 * @param showConnection false = 不顯示連線膠囊（訊號中斷警告仍會顯示）
 */
@Composable
fun HudStatusRow(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showConnection: Boolean = true,
) {
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val telem by vm.telemetrySlow.collectAsStateWithLifecycle()
    val stats by vm.linkStats.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val reconnect by vm.reconnect.collectAsStateWithLifecycle()
    val mode by vm.linkMode.collectAsStateWithLifecycle()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val lost = state as? ConnectionState.LinkLost
        val r = reconnect
        if (r != null) {
            ReconnectBadge(r, onCancel = { vm.cancelReconnect() }, Modifier.weight(1f, fill = false), compact = true)
        } else if (lost != null) {
            LinkLostBadge(lost.sinceMs, compact = true)
        } else if (showConnection) {
            ConnectionChip(state, demo = demo && !compact)
        }
        BatteryChip(telem?.batteryV, showVoltage = !compact)
        SignalChip(stats.signalLevel)
        if (!compact) mode?.let { LinkModeChip(it) }
        ControlLatencyChip(stats)
        if (!compact) LinkQualityChip(stats.linkQualityPercent)
    }
}

/**
 * 全螢幕分頁（駕駛）的滑入式導覽：半透明遮罩 + 左側導覽軌（含緊急停止）+ 車況面板。
 * 點遮罩 / 返回鍵 / 選分頁 → 關閉。打開時先把控制量歸零（駕駛畫面也應停用搖桿）。
 */
@Composable
internal fun NavOverlay(
    vm: AppViewModel,
    nav: AppNavigator,
    safe: PaddingValues,
    onEStop: () -> Unit,
    onAskDisconnect: () -> Unit,
) {
    val open = nav.isNavOverlayOpen
    val haptics = rememberHaptics()
    val ld = LocalLayoutDirection.current

    LaunchedEffect(open) { if (open) vm.stopControl() }
    // 打開時才進入組合 → 一定是最後註冊的返回處理（比駕駛畫面自己的 BackHandler 優先）：返回鍵先關導覽
    if (open) BackHandler { nav.closeNavOverlay() }

    AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(220))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(Unit) { detectTapGestures { nav.closeNavOverlay() } }
                .semantics {
                    contentDescription = "關閉選單"
                    onClick { nav.closeNavOverlay(); true }
                },
        )
    }
    AnimatedVisibility(
        open,
        enter = slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)) { -it } + fadeIn(tween(160)),
        exit = slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(220)),
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .padding(
                    start = safe.calculateStartPadding(ld) + RailMargin,
                    top = safe.calculateTopPadding() + 8.dp,
                    bottom = safe.calculateBottomPadding() + 8.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavRail(
                selected = nav.currentTab,
                onSelect = { nav.selectTab(it) },
                onEStop = onEStop,
                modifier = Modifier.fillMaxHeight(),
            )
            OverlayStatusPanel(
                vm,
                onClose = { haptics.click(); nav.closeNavOverlay() },
                onAskDisconnect = onAskDisconnect,
                modifier = Modifier.width(overlayPanelWidthOf(LocalWindowDpSize.current.width)).fillMaxHeight(),
            )
        }
    }
}

/**
 * 滑入式導覽右側的車況面板寬度：視窗寬 × 0.42，夾在 236..300 dp。
 * 膠囊（連線、馬達、電量、訊號、連線方式、控制延遲、品質）兩兩並排才放得下：640 × 320、字級 1.3 也不用捲動就看得到「中斷連線」。
 */
internal fun overlayPanelWidthOf(windowWidth: Dp): Dp = (windowWidth * 0.42f).coerceIn(236.dp, 300.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayStatusPanel(
    vm: AppViewModel,
    onClose: () -> Unit,
    onAskDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val name by vm.currentCarName.collectAsStateWithLifecycle()
    val telem by vm.telemetrySlow.collectAsStateWithLifecycle()
    val stats by vm.linkStats.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val info by vm.carInfo.collectAsStateWithLifecycle()
    val reconnect by vm.reconnect.collectAsStateWithLifecycle()
    val mode by vm.linkMode.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .glass(shape, fill = c.surface.copy(alpha = 0.92f), glow = c.accent.copy(alpha = 0.25f), glowElevation = 18.dp)
            .clip(shape)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("車況", style = B570Theme.type.caption, color = c.textMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name.ifEmpty { "B570" }, style = B570Theme.type.titleM, color = c.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (demo) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "展示", style = B570Theme.type.caption, color = c.accent2, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.accent2.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            HudIconButton(Icons.Rounded.Close, "關閉選單", onClose, size = 36.dp)
        }
        Spacer(Modifier.height(10.dp))
        val lost = state as? ConnectionState.LinkLost
        val r = reconnect
        if (r != null) {
            ReconnectBadge(r, onCancel = { vm.cancelReconnect() }, Modifier.fillMaxWidth(), compact = false)
            Spacer(Modifier.height(8.dp))
        } else if (lost != null) {
            LinkLostBadge(lost.sinceMs, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        val t = telem
        val detail = when {
            t == null -> null
            t.motorsOn -> when (t.source) {
                ControlSource.APP -> "手機控制"
                ControlSource.SEQUENCE -> "編排控制"
                else -> "待命"
            }
            else -> t.stopReason.labelZh
        }
        // 全部膠囊放同一個流式排列：手機橫放（高約 300 dp）也不用捲動就看得到「中斷連線」
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lost == null && r == null) ConnectionChip(state)
            MotorChip(t?.motorsOn, detail = detail)
            BatteryChip(t?.batteryV)
            SignalChip(stats.signalLevel, text = stats.wifiRssi?.let { "$it dBm" })
            mode?.let { LinkModeChip(it) }
            ControlLatencyChip(stats)
            LinkQualityChip(stats.linkQualityPercent)
        }

        info?.let {
            Spacer(Modifier.height(8.dp))
            Text("韌體 v${it.fwVersion}", style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.height(14.dp))
        NeonButton(
            "中斷連線", onAskDisconnect, Modifier.fillMaxWidth(),
            style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.LinkOff, compact = true,
        )
        // 延遲細節 + 一句診斷（例如「車上處理慢：請確認已更新 STM32 韌體」）；放在「中斷連線」下面，
        // 640 × 320 不用捲動也看得到「中斷連線」
        if (state.isSession) {
            Spacer(Modifier.height(10.dp))
            Text(latencyDetailZh(stats), style = B570Theme.type.caption, color = c.textSecondary)
            val diag = latencyDiagnosisOf(stats, mode, connected = state.isConnected, ctrlSeqFirmware = telem?.let { it.ctrlSeq != null })
            Text(diag.textZh, style = B570Theme.type.caption, color = diagnosisColor(diag.tone))
        }
    }
}
