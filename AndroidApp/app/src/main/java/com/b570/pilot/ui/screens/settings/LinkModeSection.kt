package com.b570.pilot.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.WifiTetheringError
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.HotspotSwitchStatus
import com.b570.pilot.data.Severity
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.LinkMode
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.ConfirmDialog
import com.b570.pilot.ui.components.DiagnosisTone
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.HotspotSwitchPanel
import com.b570.pilot.ui.components.LinkModeChip
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.StaStatusUi
import com.b570.pilot.ui.components.diagnosisColor
import com.b570.pilot.ui.components.openPhoneHotspotSettings
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.components.rememberPhoneHotspotOn
import com.b570.pilot.ui.components.staStatusOf
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass

/**
 * 「連線方式」：直連（手機連車子的 Wi-Fi）⇄ 熱點模式（車子加入手機的個人熱點，手機照用行動網路）。
 * - 上：切換中的進度卡（[HotspotSwitchPanel]，關掉對話框後也看得到）
 * - 連線方式卡：兩個選項（目前車上的設定）、車子狀態（已連上熱點 / 搜尋中 / 找不到已改回直連 + IP + 訊號）、修改 / 改回直連
 * - 說明卡：2.4 GHz、WPA2、行動網路照用、延遲、自動改回直連 +「開啟手機熱點設定」
 * 套用流程比照「修改車子 Wi-Fi」：表單 → 確認 → 車子重新開機 → 提示開啟手機熱點 → 自動在熱點上找到車子（[HotspotEditDialog]）。
 * 寬版（平板）：左 = 連線方式，右 = 說明；窄版上下堆疊。
 */
@Composable
internal fun LinkModeSection(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val context = LocalContext.current
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val sta by vm.staInfo.collectAsStateWithLifecycle()
    val supported by vm.hotspotSupported.collectAsStateWithLifecycle()
    val mode by vm.linkMode.collectAsStateWithLifecycle()
    val switch by vm.hotspotSwitch.collectAsStateWithLifecycle()
    val car by vm.currentCar.collectAsStateWithLifecycle()
    val cars by vm.savedCars.collectAsStateWithLifecycle()
    val esp by vm.espInfo.collectAsStateWithLifecycle()
    // 只取「馬達運轉中」一個布林值（不跟著遙測重組）
    val telem = vm.telemetrySlow.collectAsStateWithLifecycle()
    val motorsOn by remember { derivedStateOf { telem.value?.motorsOn == true } }
    val phoneHotspotOn by rememberPhoneHotspotOn { vm.isPhoneHotspotLikelyOn() }

    val saved = car?.let { cur -> cars.firstOrNull { it.ssid == cur.ssid } }
    val connected = state.isConnected
    // 連線後 repository 會讀一次；之前沒讀到（例如逾時）就在這裡補讀。舊 ESP 韌體回 UNKNOWN_TYPE → 不支援
    LaunchedEffect(connected) {
        if (connected && vm.staInfo.value == null && vm.hotspotSupported.value != false) vm.requestStaInfo()
    }

    // 車上目前的設定：以 STA_INFO 為準，還沒讀到用記住的車
    val configuredHotspot = sta?.enabled ?: (!demo && saved?.hotspotMode == true)
    val canChange = connected && supported != false
    val hotspotName = sta?.ssid?.takeIf { it.isNotBlank() } ?: saved?.hotspotSsid?.takeIf { it.isNotBlank() }
    val apSsid = esp.wifi?.ssid?.takeIf { it.isNotEmpty() } ?: car?.ssid
    val staUi = staStatusOf(sta, supported, connected, apSsid)
    val openHotspot: () -> Unit = {
        if (!openPhoneHotspotSettings(context)) vm.notice("請到手機設定開啟「個人熱點」", Severity.WARNING)
    }

    var editing by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    val lastSwitch = remember { arrayOfNulls<HotspotSwitchStatus>(1) }

    val hint = when {
        connected -> null
        state is ConnectionState.LinkLost -> "訊號中斷中，恢復連線後才能切換"
        state.isConnecting -> "連線中…連上後才能切換"
        else -> "連線到車子後才能切換連線方式"
    }

    Column(modifier) {
        val modeChip: (@Composable () -> Unit)? = mode?.let { m -> @Composable { LinkModeChip(m) } }
        PaneHeader(SettingsCategory.LINK, trailing = modeChip)
        val sw = switch
        if (sw != null) lastSwitch[0] = sw
        AnimatedVisibility(sw != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            // 退場動畫期間 switch 已是 null：沿用最後一筆（非 state 暫存，組合期間寫入安全）
            val shown = sw ?: lastSwitch[0]
            if (shown != null) {
                HotspotSwitchPanel(
                    status = shown,
                    onOpenHotspot = openHotspot,
                    onRetry = { vm.retry() },
                    onDismiss = { vm.dismissHotspotSwitch() },
                    onCancel = { vm.cancelHotspotSwitch() },
                    phoneHotspotOn = phoneHotspotOn || shown.phoneHotspotOn,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        val modeCard: @Composable (Modifier) -> Unit = { m ->
            GlassCard(m, glow = if (connected && configuredHotspot) c.accent2.copy(alpha = 0.30f) else null) {
                FieldLabel("車子怎麼和手機連線", trailing = mode?.let { "目前：${it.labelZh}" })
                Spacer(Modifier.height(10.dp))
                ModeOptions(
                    hotspot = configuredHotspot,
                    enabled = canChange,
                    onDirect = { if (configuredHotspot) confirmOff = true },
                    onHotspot = { if (!configuredHotspot) editing = true },
                )
                Spacer(Modifier.height(12.dp))
                StaStatusRow(staUi)
                AnimatedVisibility(
                    connected && configuredHotspot && !phoneHotspotOn && mode != LinkMode.HOTSPOT,
                    enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
                ) {
                    HintRow(Icons.Rounded.WifiTethering, "手機熱點沒開：打開後，直連一斷開車子約 15 秒內就會連上熱點", Modifier.padding(top = 8.dp), color = c.accent2)
                }
                AnimatedVisibility(hint != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    HintRow(Icons.Rounded.LinkOff, hint ?: "", Modifier.padding(top = 8.dp))
                }
                if (!connected && saved != null) {
                    Text(
                        if (saved.hotspotMode) "「${saved.displayName}」上次設定為熱點模式${saved.hotspotSsid.takeIf { it.isNotBlank() }?.let { "（熱點「$it」）" } ?: ""}"
                        else "「${saved.displayName}」上次設定為直連",
                        style = B570Theme.type.caption, color = c.textSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (configuredHotspot) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeonButton(
                            "修改熱點", { editing = true }, Modifier.weight(1f),
                            style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.Edit, enabled = canChange,
                        )
                        NeonButton(
                            "改回直連", { confirmOff = true }, Modifier.weight(1f),
                            style = NeonButtonStyle.GHOST, icon = Icons.Rounded.Wifi, enabled = canChange,
                        )
                    }
                } else {
                    NeonButton(
                        "改用熱點模式", { editing = true }, Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.WifiTethering, enabled = canChange, color = c.accent2,
                    )
                }
            }
        }
        val guideCard: @Composable (Modifier) -> Unit = { m ->
            GlassCard(m) {
                FieldLabel("熱點模式怎麼用", icon = Icons.Rounded.Lightbulb)
                Spacer(Modifier.height(6.dp))
                GuideLine(Icons.Rounded.SignalCellularAlt, "熱點模式下手機照常使用行動網路（4G / 5G）")
                GuideLine(Icons.Rounded.WifiTethering, "熱點請設 2.4\u00A0GHz（iPhone：最大化相容性）、安全性 WPA2")
                GuideLine(Icons.Rounded.Speed, "延遲通常最低：手機不必同時連兩個 Wi-Fi")
                GuideLine(Icons.Rounded.QrCode2, "車子 12 秒內找不到熱點會自動改回直連，OLED 會再顯示 QR Code")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (phoneHotspotOn) c.ok else c.textMuted, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (phoneHotspotOn) "手機熱點：已開啟" else "手機熱點：未開啟",
                        style = B570Theme.type.label, color = if (phoneHotspotOn) c.ok else c.textSecondary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                NeonButton(
                    "開啟手機熱點設定", openHotspot, Modifier.fillMaxWidth(),
                    style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.WifiTethering, color = c.accent2,
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                modeCard(Modifier.weight(1.2f))
                guideCard(Modifier.weight(1f))
            }
        } else {
            modeCard(Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            guideCard(Modifier.fillMaxWidth())
        }
    }

    if (editing) {
        HotspotEditDialog(
            vm = vm,
            initialSsid = hotspotName.orEmpty(),
            initialPassword = sta?.password.orEmpty(),
            alreadyHotspot = configuredHotspot,
            demo = demo,
            motorsOn = motorsOn,
            onOpenHotspot = openHotspot,
            onDismiss = { editing = false },
        )
    }
    if (confirmOff) {
        val noPassword = saved != null && !saved.canDirect
        ConfirmDialog(
            title = "改回直連？",
            message = buildString {
                append("車子會重新開機，改回開自己的 Wi-Fi")
                apSsid?.let { append("「").append(it).append("」") }
                if (noPassword) {
                    append("。這台車沒有記住 Wi-Fi 密碼，之後請掃描車上 OLED 的 QR Code 連線。")
                } else {
                    append("，約 5 秒後 App 會自動直連。")
                }
                append("直連需要開啟手機 Wi-Fi；有些手機開著熱點時無法同時連車子。")
                if (motorsOn && !demo) append("\n馬達運轉中：重新開機時車子會自動煞停。")
            },
            confirmText = "改回直連",
            icon = Icons.Rounded.Wifi,
            onConfirm = {
                confirmOff = false
                vm.launch { setHotspot(false) }
            },
            onDismiss = { confirmOff = false },
        )
    }
}

/** 兩個選項：直連（車子 Wi-Fi）/ 熱點模式（車子連手機熱點）。選中的 = 車上目前的設定；點另一個 = 開始切換。 */
@Composable
private fun ModeOptions(hotspot: Boolean, enabled: Boolean, onDirect: () -> Unit, onHotspot: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeOption(
            icon = Icons.Rounded.Wifi,
            title = "直連",
            subtitle = "車子 Wi-Fi",
            selected = !hotspot,
            enabled = enabled,
            color = B570Theme.colors.accent,
            onClick = onDirect,
            modifier = Modifier.weight(1f),
        )
        ModeOption(
            icon = Icons.Rounded.WifiTethering,
            title = "熱點模式",
            subtitle = "車子連手機熱點",
            selected = hotspot,
            enabled = enabled,
            color = B570Theme.colors.accent2,
            onClick = onHotspot,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(B570Theme.radii.m)
    val border by animateColorAsState(if (selected) color else c.glassBorderDim, label = "modeBorder")
    val tint = if (selected) color else c.textSecondary
    Row(
        modifier
            .heightIn(min = 56.dp)
            .glass(shape, fill = if (selected) color.copy(alpha = 0.14f) else c.glass, glow = if (selected) color.copy(alpha = 0.45f) else null, glowElevation = 10.dp)
            .border(1.dp, border, shape)
            .clip(shape)
            .graphicsLayer { alpha = if (enabled || selected) 1f else 0.5f }
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                if (!selected) {
                    haptics.click()
                    onClick()
                }
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = B570Theme.type.titleS, color = if (selected) c.textPrimary else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = color, modifier = Modifier.size(18.dp))
    }
}

/** 車子目前的連線狀態（STA_INFO）：圖示 + 標題 + 細節（IP / 訊號）。 */
@Composable
private fun StaStatusRow(ui: StaStatusUi) {
    val c = B570Theme.colors
    val col = diagnosisColor(ui.tone)
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.s), fill = col.copy(alpha = 0.08f), borderWidth = 0.75.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (ui.busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = col, strokeWidth = 2.dp)
            } else {
                Icon(
                    when (ui.tone) {
                        DiagnosisTone.OK -> Icons.Rounded.CheckCircle
                        DiagnosisTone.WARN -> Icons.Rounded.WifiTetheringError
                        DiagnosisTone.BAD -> Icons.Rounded.ErrorOutline
                        DiagnosisTone.INFO, DiagnosisTone.NEUTRAL -> Icons.Rounded.Info
                    },
                    null, tint = col, modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(ui.titleZh, style = B570Theme.type.bodySmall, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ui.detailZh?.let { Text(it, style = B570Theme.type.caption, color = c.textSecondary) }
        }
    }
}

/** 說明列：小圖示 + 一句話（可換行）。 */
@Composable
internal fun GuideLine(icon: ImageVector, text: String, tint: Color = B570Theme.colors.accent2) {
    val c = B570Theme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = B570Theme.type.bodySmall, color = c.textPrimary)
    }
}
