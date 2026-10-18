package com.b570.pilot.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.LinkStats
import com.b570.pilot.link.LinkMode
import com.b570.pilot.ui.theme.B570Theme

/** 延遲燈號顏色：綠 / 黃 / 紅；未知 = 灰。 */
@Composable
fun latencyColor(level: LatencyLevel): Color {
    val c = B570Theme.colors
    return when (level) {
        LatencyLevel.UNKNOWN -> c.textMuted
        LatencyLevel.GOOD -> c.ok
        LatencyLevel.FAIR -> c.warn
        LatencyLevel.POOR -> c.danger
    }
}

/** 診斷語氣顏色。 */
@Composable
fun diagnosisColor(tone: DiagnosisTone): Color {
    val c = B570Theme.colors
    return when (tone) {
        DiagnosisTone.NEUTRAL -> c.textSecondary
        DiagnosisTone.OK -> c.ok
        DiagnosisTone.INFO -> c.info
        DiagnosisTone.WARN -> c.warn
        DiagnosisTone.BAD -> c.danger
    }
}

/** 連線方式顏色：熱點 = 紫（accent2）、直連 = 強調色。 */
@Composable
fun linkModeColor(mode: LinkMode): Color =
    if (mode == LinkMode.HOTSPOT) B570Theme.colors.accent2 else B570Theme.colors.accent

/**
 * 控制延遲膠囊（HUD 主要延遲數字）：「控制 23 ms」，綠 < 40、黃 < 100、紅 ≥ 100；
 * 舊主控板韌體量不到控制延遲時顯示「延遲 18 ms」（PING 來回）。
 * @param expanded true = 一行細節「控制 23 · P95 41 · 來回 18 · Wi-Fi 6 ms」
 * @param onClick 非 null 時可點（例如切換細節、跳到監測頁）
 */
@Composable
fun ControlLatencyChip(
    stats: LinkStats,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val hud = hudLatencyOf(stats)
    val col = latencyColor(hud.level)
    HudChip(
        modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = 500f)),
        col,
        onClick,
        contentDescription = if (expanded) latencyDetailZh(stats) else hud.descriptionZh,
    ) {
        Icon(Icons.Rounded.NetworkCheck, null, tint = col, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            if (expanded) latencyDetailZh(stats) else hud.textZh,
            style = B570Theme.type.label,
            color = B570Theme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 連線方式膠囊：「熱點」（車子連手機熱點）/「直連」（手機連車子的 Wi-Fi）。 */
@Composable
fun LinkModeChip(mode: LinkMode, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val col = linkModeColor(mode)
    HudChip(modifier, col, onClick, contentDescription = "連線方式：${mode.labelZh}") {
        Icon(if (mode == LinkMode.HOTSPOT) Icons.Rounded.WifiTethering else Icons.Rounded.Wifi, null, tint = col, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(linkModeShortZh(mode), style = B570Theme.type.label, color = B570Theme.colors.textPrimary, maxLines = 1, softWrap = false)
    }
}
