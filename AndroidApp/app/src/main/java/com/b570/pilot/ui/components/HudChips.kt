package com.b570.pilot.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.WifiTetheringError
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.ui.theme.B570Theme
import kotlinx.coroutines.delay
import java.util.Locale

/** HUD 膠囊高度（遙控器上緣狀態列）。 */
val HudChipHeight = 30.dp

/**
 * HUD 小膠囊（遙控器上緣狀態列用）：深色玻璃底 + 彩色細邊框，高 [HudChipHeight]。
 * @param color 邊框 / 強調色
 * @param onClick 非 null 時可點（觸覺回饋）
 */
@Composable
fun HudChip(
    modifier: Modifier = Modifier,
    color: Color = B570Theme.colors.textSecondary,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(50)
    var m = modifier
        .heightIn(min = HudChipHeight)
        .clip(shape)
        .background(c.surface.copy(alpha = 0.72f))
        .border(1.dp, color.copy(alpha = 0.38f), shape)
    if (onClick != null) m = m.clickable(role = Role.Button) { haptics.click(); onClick() }
    if (contentDescription != null) m = m.semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
    Row(m.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** 文字 HUD 膠囊：可選圖示 + 文字。 */
@Composable
fun HudChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = B570Theme.colors.textSecondary,
    textColor: Color = B570Theme.colors.textPrimary,
    onClick: (() -> Unit)? = null,
) {
    HudChip(modifier, color, onClick) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = B570Theme.type.label, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 電量膠囊：電池圖示 + 百分比（+ 電壓）。[volts] = null 顯示「--」。低於 15 % 變紅。 */
@Composable
fun BatteryChip(volts: Float?, modifier: Modifier = Modifier, showVoltage: Boolean = true) {
    val c = B570Theme.colors
    val pct = volts?.let { batteryPercent(it) }
    val col = if (pct == null) c.textMuted else batteryColor(pct)
    HudChip(modifier, col, contentDescription = if (pct == null) "電量未知" else "電量 $pct%") {
        BatteryIndicator(volts, showText = false)
        Spacer(Modifier.width(5.dp))
        Text(if (pct == null) "--" else "$pct%", style = B570Theme.type.label, color = c.textPrimary, maxLines = 1)
        if (showVoltage && volts != null) {
            Spacer(Modifier.width(5.dp))
            Text(String.format(Locale.US, "%.1f V", volts), style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1)
        }
    }
}

/** 延遲顏色：< 60 ms 正常、< 150 ms 警告、其餘危險；null = 灰。 */
@Composable
fun rttColor(rttMs: Float?): Color = when {
    rttMs == null -> B570Theme.colors.textMuted
    rttMs < 60f -> B570Theme.colors.ok
    rttMs < 150f -> B570Theme.colors.warn
    else -> B570Theme.colors.danger
}

/** 延遲膠囊：「12 ms」，依延遲變色。 */
@Composable
fun RttChip(rttMs: Float?, modifier: Modifier = Modifier) {
    val col = rttColor(rttMs)
    HudChip(modifier, col, contentDescription = rttMs?.let { "延遲 ${it.toInt()} 毫秒" } ?: "延遲未知") {
        Icon(Icons.Rounded.NetworkCheck, null, tint = col, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(rttMs?.let { String.format(Locale.US, "%.0f ms", it) } ?: "-- ms", style = B570Theme.type.label, color = B570Theme.colors.textPrimary, maxLines = 1)
    }
}

/** 訊號膠囊：訊號格（0..4）+ 可選文字（例如「-58 dBm」或延遲）。 */
@Composable
fun SignalChip(level: Int, modifier: Modifier = Modifier, text: String? = null) {
    val c = B570Theme.colors
    val col = when {
        level >= 3 -> c.accent
        level == 2 -> c.warn
        level == 1 -> c.danger
        else -> c.textMuted
    }
    HudChip(modifier, col, contentDescription = "訊號 $level 格") {
        SignalBars(level, Modifier.size(width = 14.dp, height = 11.dp), color = col)
        if (text != null) {
            Spacer(Modifier.width(5.dp))
            Text(text, style = B570Theme.type.label, color = c.textPrimary, maxLines = 1)
        }
    }
}

/** 連線品質膠囊：「品質 98%」（車子收到手機指令的比例，傳 `LinkStats.linkQualityPercent`）；null = 「--」。 */
@Composable
fun LinkQualityChip(percent: Int?, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val col = when {
        percent == null -> c.textMuted
        percent >= 80 -> c.ok
        percent >= 50 -> c.warn
        else -> c.danger
    }
    HudChip(modifier, col, contentDescription = percent?.let { "連線品質 $it%" } ?: "連線品質未知") {
        Icon(Icons.Rounded.SettingsInputAntenna, null, tint = col, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(percent?.let { "品質 $it%" } ?: "品質 --", style = B570Theme.type.label, color = c.textPrimary, maxLines = 1)
    }
}

/** 連線狀態顏色（與 shell 狀態條相同）。 */
@Composable
fun connectionColor(state: ConnectionState): Color {
    val c = B570Theme.colors
    return when (state) {
        is ConnectionState.Connected -> c.ok
        is ConnectionState.LinkLost -> c.warn
        is ConnectionState.Failed -> c.danger
        ConnectionState.Idle -> c.textMuted
        else -> c.accent
    }
}

/** 連線狀態膠囊（呼吸圓點 + 已連線 / 訊號中斷…）；[demo] = 加「展示」標籤。 */
@Composable
fun ConnectionChip(state: ConnectionState, modifier: Modifier = Modifier, demo: Boolean = false) {
    val c = B570Theme.colors
    val col by animateColorAsState(connectionColor(state), label = "conn")
    StatusPill(
        text = if (demo && state.isSession) "${state.labelZh} · 展示" else state.labelZh,
        color = col,
        modifier = modifier.heightIn(min = HudChipHeight).background(c.surface.copy(alpha = 0.5f), RoundedCornerShape(50)),
        pulsing = state is ConnectionState.Connected || state.isConnecting,
    )
}

/** 馬達狀態膠囊：運轉中（綠，亮）/ 已停止（灰）；[detail] 例如停止原因。 */
@Composable
fun MotorChip(motorsOn: Boolean?, modifier: Modifier = Modifier, detail: String? = null) {
    val c = B570Theme.colors
    val col = when (motorsOn) {
        true -> c.ok
        false -> c.textSecondary
        null -> c.textMuted
    }
    val text = when (motorsOn) {
        true -> "運轉中"
        false -> "已停止"
        null -> "馬達 --"
    }
    HudChip(modifier, col) {
        Icon(Icons.Rounded.PowerSettingsNew, null, tint = col, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(if (detail != null) "$text · $detail" else text, style = B570Theme.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * HUD 圓角方形圖示鍵（例如駕駛畫面的選單 ≡、全螢幕切換）：深色玻璃、按下縮放、觸覺。
 * @param highlighted 亮起（強調色底）
 * @param badge 右上角小紅點
 */
@Composable
fun HudIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = B570Theme.colors.textPrimary,
    highlighted: Boolean = false,
    badge: Boolean = false,
    enabled: Boolean = true,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "s")
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(if (highlighted) c.accent.copy(alpha = 0.22f) else c.surface.copy(alpha = 0.72f))
            .border(1.dp, if (highlighted) c.accent.copy(alpha = 0.7f) else c.glassBorder, shape)
            .clickable(src, indication = null, enabled = enabled, role = Role.Button) { haptics.click(); onClick() }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (highlighted) c.accent else color, modifier = Modifier.size(size * 0.55f))
        if (badge) {
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(7.dp).clip(CircleShape).background(c.danger))
        }
    }
}

/**
 * 訊號中斷警告（不透明琥珀色列，含失聯秒數與轉圈）。shell 狀態條與駕駛 HUD 共用。
 * @param compact true = 只有一行（「訊號中斷 · 3 秒」）
 */
@Composable
fun LinkLostBadge(sinceMs: Long, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = B570Theme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMs) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    val secs = ((now - sinceMs) / 1000).coerceAtLeast(0)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .clip(shape)
            .background(lerpColor(c.surface, c.warn, 0.16f))
            .border(1.dp, c.warn.copy(alpha = 0.55f), shape)
            .padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WifiTetheringError, null, tint = c.warn, modifier = Modifier.size(if (compact) 15.dp else 18.dp))
        Spacer(Modifier.width(8.dp))
        if (compact) {
            Text("訊號中斷 · $secs 秒", style = B570Theme.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        } else {
            Column(Modifier.weight(1f, fill = false)) {
                Text("訊號中斷，重新連線中…", style = B570Theme.type.bodySmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("已失聯 $secs 秒 · 收不到指令會自動煞停", style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        CircularProgressIndicator(Modifier.size(14.dp), color = c.warn, strokeWidth = 2.dp)
    }
}
