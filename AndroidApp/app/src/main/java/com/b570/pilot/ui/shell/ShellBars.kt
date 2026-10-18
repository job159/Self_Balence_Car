package com.b570.pilot.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.ReconnectStatus
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.ControlSource
import com.b570.pilot.protocol.Info
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.MainTab
import com.b570.pilot.ui.components.BatteryChip
import com.b570.pilot.ui.components.ControlLatencyChip
import com.b570.pilot.ui.components.EStopStyle
import com.b570.pilot.ui.components.EmergencyStopButton
import com.b570.pilot.ui.components.LandscapeClass
import com.b570.pilot.ui.components.LinkLostBadge
import com.b570.pilot.ui.components.LinkModeChip
import com.b570.pilot.ui.components.LocalLandscapeClass
import com.b570.pilot.ui.components.SignalChip
import com.b570.pilot.ui.components.StatusPill
import com.b570.pilot.ui.components.connectionColor
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass

/**
 * 頂部狀態條（橫向，畫在導覽軌右側）：車名 · 馬達狀態、連線膠囊、電量、訊號、連線方式（熱點 / 直連）、
 * 控制延遲「控制 23 ms」（點一下到監測頁看延遲診斷）。
 * 訊號中斷（LinkLost）時，左側換成不透明的失聯警告（含失聯秒數）；斷線後自動重連中換成重連進度（第 n/6 次 + 倒數 + 取消）
 * ——都畫在狀態條內，不蓋住分頁內容。
 * 高度固定 [StatusStripHeight]（不含安全區；安全區上緣由呼叫端用 padding 加）。
 */
@Composable
fun StatusStrip(vm: AppViewModel, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val name by vm.currentCarName.collectAsStateWithLifecycle()
    val telem by vm.telemetrySlow.collectAsStateWithLifecycle()
    val stats by vm.linkStats.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val info by vm.carInfo.collectAsStateWithLifecycle()
    val reconnect by vm.reconnect.collectAsStateWithLifecycle()
    val mode by vm.linkMode.collectAsStateWithLifecycle()
    val compact = LocalLandscapeClass.current == LandscapeClass.COMPACT
    Row(
        modifier.height(StatusStripHeight).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val lost = state as? ConnectionState.LinkLost
        // 退場動畫期間 state 已變：沿用最後的失聯起點 / 重連進度（非 state 暫存，組合期間寫入安全）
        val lostSince = remember { longArrayOf(0L) }
        if (lost != null) lostSince[0] = lost.sinceMs
        val lastReconnect = remember { arrayOfNulls<ReconnectStatus>(1) }
        reconnect?.let { lastReconnect[0] = it }
        val left = when {
            reconnect != null -> StripLeft.RECONNECT
            lost != null -> StripLeft.LINK_LOST
            else -> StripLeft.CAR
        }
        AnimatedContent(
            targetState = left,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(160))) },
            contentAlignment = Alignment.CenterStart,
            label = "stripLeft",
        ) { which ->
            when (which) {
                StripLeft.RECONNECT -> lastReconnect[0]?.let { r ->
                    ReconnectBadge(r, onCancel = { vm.cancelReconnect() }, Modifier.padding(end = 8.dp), compact = true)
                }
                StripLeft.LINK_LOST -> LinkLostBadge(lostSince[0], Modifier.padding(end = 8.dp))
                StripLeft.CAR -> CarSummary(name, demo, telem, info)
            }
        }
        if (reconnect != null) {
            // 重連等待中連線層是「連線失敗」：膠囊改說「重連中」，不要讓人以為已經放棄
            StatusPill("重連中", c.warn, pulsing = true)
        } else {
            StatusPill(state.labelZh, connectionColor(state), pulsing = state is ConnectionState.Connected || state.isConnecting)
        }
        Spacer(Modifier.width(8.dp))
        BatteryChip(telem?.batteryV, showVoltage = !compact)
        Spacer(Modifier.width(8.dp))
        SignalChip(stats.signalLevel)
        val m = mode
        if (!compact && m != null) {
            Spacer(Modifier.width(8.dp))
            LinkModeChip(m)
        }
        Spacer(Modifier.width(8.dp))
        // 點一下到監測頁（延遲診斷：控制 / 來回 / Wi-Fi / 上行遺失 + 白話建議）
        ControlLatencyChip(stats, onClick = { vm.nav.selectTab(MainTab.MONITOR) })
    }
}

/** 狀態條左側顯示什麼。 */
private enum class StripLeft { CAR, LINK_LOST, RECONNECT }

/** 狀態條左側（正常）：車名 + 展示標籤 · 馬達狀態（橫向一行）。 */
@Composable
private fun CarSummary(name: String, demo: Boolean, t: Telemetry?, info: Info?) {
    val c = B570Theme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name.ifEmpty { "B570" }, style = B570Theme.type.titleS, color = c.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (demo) {
            Spacer(Modifier.width(6.dp))
            Text(
                "展示", style = B570Theme.type.caption, color = c.accent2, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.accent2.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        val sub = when {
            t == null -> info?.let { "韌體 v${it.fwVersion}" } ?: "等待資料…"
            t.motorsOn -> when (t.source) {
                ControlSource.APP -> "馬達運轉中 · 手機控制"
                ControlSource.SEQUENCE -> "馬達運轉中 · 編排控制"
                else -> "馬達運轉中 · 待命"
            }
            else -> "馬達停止 · ${t.stopReason.labelZh}"
        }
        Text(
            sub, style = B570Theme.type.label, color = if (t?.motorsOn == true) c.ok else c.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * 左側玻璃導覽軌（橫向主導覽）：圖示 + 短標籤、垂直滑動的發光指示塊、觸覺；底部可放緊急停止。
 * 寬度固定 [RailWidth]，高度由呼叫端決定（通常 fillMaxHeight）；太矮時整條可捲動，不會切字。
 *
 * @param onEStop 非 null 時在底部顯示「緊急停止」（觸碰即觸發 + 重震動）
 */
@Composable
fun NavRail(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    onEStop: (() -> Unit)? = null,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val tabs = MainTab.entries
    val shape = RoundedCornerShape(24.dp)
    BoxWithConstraints(
        modifier
            .width(RailWidth)
            .glass(shape, fill = c.surface.copy(alpha = 0.80f), glow = c.accent.copy(alpha = 0.30f), glowElevation = 18.dp)
            .clip(shape),
    ) {
        val pad = 6.dp
        val eH = if (onEStop != null) 54.dp else 0.dp
        val eGap = if (onEStop != null) 8.dp else 0.dp
        val avail = maxHeight - pad * 2 - eH - eGap
        val itemH = (avail / tabs.size).coerceIn(44.dp, 60.dp) // 640 × 320：5 × 44 + 緊急停止 54 剛好不用捲動
        val needScroll = itemH * tabs.size + eH + eGap + pad * 2 > maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (needScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxHeight())
                .padding(pad),
        ) {
            Box(Modifier.fillMaxWidth().height(itemH * tabs.size)) {
                val y by animateDpAsState(itemH * selected.ordinal, spring(dampingRatio = 0.72f, stiffness = 420f), label = "rail")
                // 發光指示塊 + 左側光條
                Box(
                    Modifier
                        .offset { IntOffset(0, y.roundToPx()) }
                        .fillMaxWidth()
                        .height(itemH)
                        .shadow(12.dp, RoundedCornerShape(18.dp), clip = false, ambientColor = c.accent, spotColor = c.accent)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.26f), c.accent2.copy(alpha = 0.16f))))
                        .border(1.dp, c.accent.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 2.dp)
                            .size(width = 3.dp, height = itemH * 0.45f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(c.accent),
                    )
                }
                Column(Modifier.fillMaxSize().selectableGroup()) {
                    tabs.forEach { tab ->
                        val sel = tab == selected
                        val tint by animateColorAsState(if (sel) c.accent else c.textSecondary, label = "tint")
                        val lift by animateFloatAsState(if (sel) 1.06f else 1f, label = "lift")
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .height(itemH)
                                // 無障礙：分頁角色 + 選取狀態（TalkBack 會念「已選取」），標籤只念一次
                                .selectable(
                                    selected = sel,
                                    interactionSource = null,
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = { if (!sel) { haptics.tick(); onSelect(tab) } },
                                )
                                .graphicsLayer { scaleX = lift; scaleY = lift },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(3.dp))
                            Text(
                                tab.labelZh,
                                style = B570Theme.type.caption.copy(letterSpacing = 0.sp),
                                color = if (sel) c.textPrimary else c.textSecondary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            if (onEStop != null) {
                if (needScroll) Spacer(Modifier.height(eGap)) else Spacer(Modifier.weight(1f))
                EmergencyStopButton(
                    onStop = onEStop,
                    modifier = Modifier.fillMaxWidth().height(eH),
                    style = EStopStyle.STACKED,
                )
            }
        }
    }
}
