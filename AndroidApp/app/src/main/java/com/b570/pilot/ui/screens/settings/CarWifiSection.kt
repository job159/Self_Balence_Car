package com.b570.pilot.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTetheringError
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.HelloAck
import com.b570.pilot.protocol.WifiInfo
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.StatusPill
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay

/**
 * 「車輛 Wi-Fi」：目前 SSID / 密碼（可顯示）、頻道、ESP-01 狀態、通訊統計，修改與恢復原廠。
 * ESP 資訊約每 3 秒更新一次（WIFI_INFO），本區塊只在那時重組。
 * 寬版（平板）：左 = 身分 / 密碼 / 提示 / 動作，右 = 模組數據 + 通訊統計；窄版依序堆疊（與直向版相同順序）。
 */
@Composable
internal fun CarWifiSection(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val esp by vm.espInfo.collectAsStateWithLifecycle()
    val car by vm.currentCar.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    // 只取「馬達運轉中」一個布林值（10 Hz 節流遙測 → derivedStateOf，不跟著遙測重組）
    val telem = vm.telemetrySlow.collectAsStateWithLifecycle()
    val motorsOn by remember { derivedStateOf { telem.value?.motorsOn == true } }

    val wifi = esp.wifi
    val hello = esp.hello
    // 真車以 ESP 回報為準；展示模式的模擬器不會套用 WIFI_SET，改以 repository 更新後的設定為準
    val espSsid = wifi?.ssid?.takeIf { it.isNotEmpty() }
    val espPw = wifi?.password?.takeIf { it.isNotEmpty() }
    val ssid = (if (demo) car?.ssid ?: espSsid else espSsid ?: car?.ssid).orEmpty()
    val password = (if (demo) car?.password ?: espPw else espPw ?: car?.password).orEmpty()
    val connected = state.isConnected

    var reveal by remember { mutableStateOf(false) }
    // 顯示密碼 15 秒後自動隱藏
    LaunchedEffect(reveal) {
        if (reveal) {
            delay(15_000)
            reveal = false
        }
    }
    var details by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var factory by remember { mutableStateOf(false) }

    val (pillText, pillColor) = when {
        demo -> "展示" to c.accent2
        state is ConnectionState.Connected -> "已連線" to c.ok
        state is ConnectionState.LinkLost -> "訊號中斷" to c.warn
        else -> state.labelZh to c.textMuted
    }
    val sub = when {
        wifi != null -> "頻道 ${wifi.channel} · ${wifi.stations} 台裝置連線中"
        state.isSession -> "正在讀取車子的 Wi-Fi 資訊…"
        else -> "連線後顯示"
    }
    val hint = when {
        connected -> null
        state is ConnectionState.LinkLost -> "訊號中斷中，恢復連線後才能修改"
        else -> "連線到車子後才能修改 Wi-Fi"
    }
    val linkLost = state is ConnectionState.LinkLost

    Column(modifier) {
        PaneHeader(SettingsCategory.WIFI)
        GlassCard(Modifier.fillMaxWidth(), glow = if (connected) c.accent.copy(alpha = 0.30f) else null) {
            if (wide) {
                AdaptiveColumns(
                    wide = true,
                    first = {
                        WifiIdentityRow(ssid, sub, connected, pillText, pillColor)
                        Spacer(Modifier.height(14.dp))
                        PasswordRow(password, reveal) { reveal = !reveal }
                        WifiHint(hint, linkLost)
                        Spacer(Modifier.height(14.dp))
                        WifiActions(connected, ssid.isNotEmpty(), onEdit = { editing = true }, onFactory = { factory = true })
                    },
                    second = {
                        FieldLabel("無線模組", trailing = "約每 3 秒更新")
                        Spacer(Modifier.height(8.dp))
                        EspStatGrid(wifi, hello)
                        Spacer(Modifier.height(6.dp))
                        CommStats(details, { details = !details }, wifi, hello)
                    },
                )
            } else {
                WifiIdentityRow(ssid, sub, connected, pillText, pillColor)
                Spacer(Modifier.height(14.dp))
                PasswordRow(password, reveal) { reveal = !reveal }
                Spacer(Modifier.height(12.dp))
                EspStatGrid(wifi, hello)
                Spacer(Modifier.height(6.dp))
                CommStats(details, { details = !details }, wifi, hello)
                WifiHint(hint, linkLost)
                Spacer(Modifier.height(12.dp))
                WifiActions(connected, ssid.isNotEmpty(), onEdit = { editing = true }, onFactory = { factory = true })
            }
        }
    }

    if (editing) {
        WifiEditDialog(
            vm = vm,
            initialSsid = ssid,
            initialPassword = password,
            currentChannel = wifi?.channel ?: hello?.channel,
            demo = demo,
            motorsOn = motorsOn,
            onDismiss = { editing = false },
            initialChannel = wifi?.configuredChannel ?: 0,
        )
    }
    if (factory) {
        FactoryResetDialog(vm = vm, demo = demo, motorsOn = motorsOn, onDismiss = { factory = false })
    }
}

/** 身分列：雷達圖示 + SSID + 頻道 / 裝置數 + 連線狀態。 */
@Composable
private fun WifiIdentityRow(ssid: String, sub: String, connected: Boolean, pillText: String, pillColor: Color) {
    val c = B570Theme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        WifiRadarBadge(active = connected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ssid.ifEmpty { "--" },
                style = B570Theme.type.titleM.copy(letterSpacing = 0.5.sp),
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(sub, style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(pillText, pillColor, pulsing = connected)
    }
}

/** 未連線 / 訊號中斷提示。 */
@Composable
private fun WifiHint(hint: String?, linkLost: Boolean) {
    AnimatedVisibility(hint != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        HintRow(
            if (linkLost) Icons.Rounded.WifiTetheringError else Icons.Rounded.LinkOff,
            hint ?: "",
            Modifier.padding(top = 8.dp),
        )
    }
}

/** 修改 / 恢復原廠。 */
@Composable
private fun WifiActions(connected: Boolean, hasSsid: Boolean, onEdit: () -> Unit, onFactory: () -> Unit) {
    val c = B570Theme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NeonButton(
            "修改",
            onEdit,
            Modifier.weight(1f),
            style = NeonButtonStyle.SECONDARY,
            icon = Icons.Rounded.Edit,
            enabled = connected && hasSsid,
        )
        NeonButton(
            "恢復原廠",
            onFactory,
            Modifier.weight(1f),
            style = NeonButtonStyle.GHOST,
            icon = Icons.Rounded.SettingsBackupRestore,
            enabled = connected,
            color = c.danger,
        )
    }
}

/** ESP-01 狀態：韌體、運作時間、頻道、連線裝置、可用記憶體、發射功率（3 × 2 格）。 */
@Composable
private fun EspStatGrid(wifi: WifiInfo?, hello: HelloAck?) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("韌體", hello?.let { "v${it.espVersion}" } ?: "--", Modifier.weight(1f))
            MiniStat("運作時間", wifi?.let { formatUptime(it.uptimeS) } ?: "--", Modifier.weight(1.25f))
            MiniStat("頻道", wifi?.channel?.toString() ?: hello?.channel?.toString() ?: "--", Modifier.weight(0.8f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("連線裝置", wifi?.stations?.toString() ?: "--", Modifier.weight(1f), unit = "台")
            MiniStat("可用記憶體", wifi?.let { formatKb(it.freeHeap) } ?: "--", Modifier.weight(1.25f), unit = "KB")
            MiniStat("發射功率", wifi?.txPowerDbm?.toString() ?: "--", Modifier.weight(0.8f), unit = "dBm")
        }
    }
}

/** 詳細通訊統計（可展開）。 */
@Composable
private fun CommStats(expanded: Boolean, onToggle: () -> Unit, wifi: WifiInfo?, hello: HelloAck?) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "arrow")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .noRippleClickable { haptics.tick(); onToggle() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "收起通訊統計" else "通訊統計", style = B570Theme.type.label, color = c.accent, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, null, tint = c.accent, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = arrow })
        }
        AnimatedVisibility(expanded, enter = expandVertically(spring(stiffness = 400f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                val ok = wifi?.uartRxOk ?: 0L
                val err = wifi?.uartRxErr ?: 0L
                val errPct = if (ok + err > 0) err * 100f / (ok + err) else 0f
                InfoRow("序列埠收到", wifi?.let { "${formatCount(it.uartRxOk)} 幀" } ?: "--")
                InfoRow(
                    "序列埠錯誤",
                    wifi?.let { "${formatCount(err)}（${String.format(java.util.Locale.US, "%.2f", errPct)} %）" } ?: "--",
                    valueColor = if (err > 0 && errPct >= 1f) c.warn else c.textPrimary,
                )
                InfoRow("Wi-Fi 收到", wifi?.let { "${formatCount(it.udpRx)} 包" } ?: "--")
                InfoRow("Wi-Fi 送出", wifi?.let { "${formatCount(it.udpTx)} 包" } ?: "--")
                InfoRow("UDP 連接埠", hello?.udpPort?.toString() ?: "--", mono = true)
                InfoRow("晶片 ID", hello?.chipIdHex ?: "--", mono = true)
            }
        }
    }
}

/** 密碼列：預設遮蔽（固定 8 點，不洩漏長度），點眼睛顯示。 */
@Composable
private fun PasswordRow(password: String, reveal: Boolean, onToggle: () -> Unit) {
    val c = B570Theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.s), fill = c.glassHigh.copy(alpha = 0.55f), borderWidth = 0.75.dp)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Key, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("密碼", style = B570Theme.type.caption, color = c.textMuted)
            AnimatedContent(
                targetState = reveal && password.isNotEmpty(),
                transitionSpec = { (fadeIn(tween(220)) togetherWith fadeOut(tween(160))) },
                label = "pw",
            ) { shown ->
                Text(
                    when {
                        password.isEmpty() -> "--"
                        shown -> password
                        else -> "••••••••"
                    },
                    style = B570Theme.type.mono.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = if (shown) 1.sp else 3.sp),
                    color = c.textPrimary,
                    maxLines = 1,
                )
            }
        }
        GlassIconButton(
            if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            if (reveal) "隱藏密碼" else "顯示密碼",
            onToggle,
            enabled = password.isNotEmpty(),
            tint = if (reveal) c.accent else c.textPrimary,
        )
    }
}

/** Wi-Fi 圖示 + 雷達漣漪（連線中才動；動畫值只在繪製階段讀取）。 */
@Composable
private fun WifiRadarBadge(active: Boolean) {
    val c = B570Theme.colors
    val tint = if (active) c.accent else c.textMuted
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        if (active) {
            val t = rememberInfiniteTransition(label = "radar")
            val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "p")
            Box(
                Modifier.size(52.dp).drawBehind {
                    val base = size.minDimension / 2f
                    val stroke = Stroke(1.2.dp.toPx())
                    for (i in 0 until 2) {
                        val q = (p.value + i * 0.5f) % 1f
                        drawCircle(tint.copy(alpha = (1f - q) * 0.55f), radius = base * (0.62f + 0.38f * q), style = stroke)
                    }
                },
            )
        }
        Box(
            Modifier
                .size(38.dp)
                .glass(CircleShape, fill = tint.copy(alpha = 0.14f), glow = if (active) tint else null, glowElevation = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Wifi, null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}
