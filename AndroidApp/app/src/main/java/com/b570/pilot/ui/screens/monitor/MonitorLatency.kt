package com.b570.pilot.ui.screens.monitor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.LinkStats
import com.b570.pilot.link.LinkMode
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.DiagnosisTone
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.LatencyCause
import com.b570.pilot.ui.components.LatencyLevel
import com.b570.pilot.ui.components.LatencyRules
import com.b570.pilot.ui.components.LinkModeChip
import com.b570.pilot.ui.components.diagnosisColor
import com.b570.pilot.ui.components.formatMs
import com.b570.pilot.ui.components.formatPercent
import com.b570.pilot.ui.components.latencyColor
import com.b570.pilot.ui.components.latencyDiagnosisOf
import com.b570.pilot.ui.components.latencyLevelOf
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass

/**
 * 「延遲診斷」：控制延遲（平滑 + 最近 5 秒 P95）、端到端來回、Wi-Fi 來回、上行遺失、連線方式、
 * 雙 Wi-Fi 警告，以及一句白話診斷（慢在 Wi-Fi？車上？指令遺失？）。
 * 只收集 linkStats（約 2 Hz）、連線方式、連線狀態與「主控板有沒有回報 ctrl_seq」一個布林值。
 */
@Composable
internal fun LatencyCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val stats by vm.linkStats.collectAsStateWithLifecycle()
    val mode by vm.linkMode.collectAsStateWithLifecycle()
    val st by vm.connectionState.collectAsStateWithLifecycle()
    val telem = vm.telemetrySlow.collectAsStateWithLifecycle()
    val ctrlSeqFw by remember { derivedStateOf { telem.value?.let { it.ctrlSeq != null } } }
    val connected = st.isConnected
    val diag = latencyDiagnosisOf(stats, mode, connected, ctrlSeqFw)
    val diagColor by animateColorAsState(diagnosisColor(diag.tone), tween(400), label = "diag")

    GlassCard(
        modifier.fillMaxWidth(),
        glow = if (connected && diag.tone == DiagnosisTone.BAD) c.danger.copy(alpha = 0.4f) else null,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NetworkCheck, null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("延遲診斷", style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
            mode?.let { LinkModeChip(it) }
        }
        Spacer(Modifier.height(10.dp))
        ControlLatencyReadout(stats, connected)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LatencyMini("端到端來回", stats.rttMs.takeIf { connected }, Modifier.weight(1f))
            LatencyMini("Wi-Fi 來回", stats.espRttMs.takeIf { connected }, Modifier.weight(1f), wifi = true)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LossMini(stats.uplinkLossPercent.takeIf { connected }, Modifier.weight(1f))
            ModeMini(mode.takeIf { connected }, Modifier.weight(1f))
        }
        AnimatedVisibility(connected && stats.dualWifi, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            DiagRow(Icons.Rounded.Warning, "手機同時連著其他 Wi-Fi，延遲會變高：中斷其他 Wi-Fi，或改用熱點模式", c.warn, Modifier.padding(top = 10.dp))
        }
        // 雙 Wi-Fi 已經在上面那行說了：診斷是同一件事時不重複
        if (diag.cause != LatencyCause.DUAL_WIFI) {
            DiagRow(diagIcon(diag.tone), diag.textZh, diagColor, Modifier.padding(top = 10.dp))
        }
    }
}

/** 控制延遲大數字 + P95。 */
@Composable
private fun ControlLatencyReadout(stats: LinkStats, connected: Boolean) {
    val c = B570Theme.colors
    val ctrl = stats.controlLatencyMs.takeIf { connected }
    val p95 = stats.controlLatencyP95Ms.takeIf { connected }
    val col by animateColorAsState(latencyColor(latencyLevelOf(ctrl)), tween(400), label = "ctrl")
    val p95Col = latencyColor(latencyLevelOf(p95))
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = if (ctrl == null) "控制延遲量測中" else "控制延遲 ${formatMs(ctrl)} 毫秒，P95 ${formatMs(p95)} 毫秒"
            },
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text("控制延遲", style = B570Theme.type.label, color = c.textSecondary, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatMs(ctrl), style = B570Theme.type.readoutL, color = col, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Text("ms", style = B570Theme.type.caption, color = c.textMuted, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("最近 5 秒 P95", style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatMs(p95), style = B570Theme.type.readoutM, color = if (p95 == null) c.textMuted else p95Col, maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Text("ms", style = B570Theme.type.caption, color = c.textMuted, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
    Text(
        "手機送出指令 → 車子真正執行（綠 < ${LatencyRules.GOOD_MS.toInt()}、黃 < ${LatencyRules.FAIR_MS.toInt()} ms）",
        style = B570Theme.type.caption, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

/** 小格：標籤 + 毫秒（依燈號變色）。[wifi] = Wi-Fi 來回用自己的門檻（≥ 30 ms 偏高）。 */
@Composable
private fun LatencyMini(label: String, ms: Float?, modifier: Modifier, wifi: Boolean = false) {
    val c = B570Theme.colors
    val level = if (wifi && ms != null) {
        when {
            ms >= LatencyRules.FAIR_MS -> LatencyLevel.POOR
            ms >= LatencyRules.WIFI_SLOW_MS -> LatencyLevel.FAIR
            else -> LatencyLevel.GOOD
        }
    } else {
        latencyLevelOf(ms)
    }
    MiniCell(label, formatMs(ms), "ms", if (ms == null) c.textMuted else latencyColor(level), modifier)
}

@Composable
private fun LossMini(pct: Float?, modifier: Modifier) {
    val c = B570Theme.colors
    val col = when {
        pct == null -> c.textMuted
        pct >= LatencyRules.LOSS_HIGH_PCT -> c.danger
        pct >= 2f -> c.warn
        else -> c.ok
    }
    MiniCell("上行遺失", formatPercent(pct), "%", col, modifier)
}

@Composable
private fun ModeMini(mode: LinkMode?, modifier: Modifier) {
    val c = B570Theme.colors
    MiniCell(
        "連線方式",
        mode?.labelZh ?: "--",
        null,
        when (mode) {
            LinkMode.HOTSPOT -> c.accent2
            LinkMode.DIRECT -> c.accent
            null -> c.textMuted
        },
        modifier,
    )
}

/** 玻璃小格：標籤 + 數值（+ 單位）。單行、省略號，字級 1.3 倍也不切字。 */
@Composable
private fun MiniCell(label: String, value: String, unit: String?, valueColor: Color, modifier: Modifier) {
    val c = B570Theme.colors
    Column(
        modifier
            .glass(RoundedCornerShape(B570Theme.radii.s), fill = c.glassHigh.copy(alpha = 0.45f), borderWidth = 0.75.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(label, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = B570Theme.type.readoutS, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.padding(bottom = 1.dp))
            }
        }
    }
}

/** 診斷 / 警告列：彩色底 + 圖示 + 文字（可換行）。 */
@Composable
private fun DiagRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(B570Theme.radii.s))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = B570Theme.type.bodySmall, color = c.textPrimary)
    }
}

private fun diagIcon(tone: DiagnosisTone) = when (tone) {
    DiagnosisTone.OK -> Icons.Rounded.CheckCircle
    DiagnosisTone.WARN -> Icons.Rounded.Warning
    DiagnosisTone.BAD -> Icons.Rounded.ErrorOutline
    DiagnosisTone.INFO, DiagnosisTone.NEUTRAL -> Icons.Rounded.Info
}
