package com.b570.pilot.ui.screens.settings

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.b570.pilot.ui.components.GlassDialog
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 設定頁共用的小元件（僅本 package 使用）。

// ------------------------------------------------------------------ 進場動畫

/** 記錄已播過進場動畫的項目：同一次進入畫面只播一次，捲回來不重播。 */
@Stable
internal class EntranceTracker {
    val seen = HashSet<Any>()
    val startMs: Long = SystemClock.uptimeMillis()
}

/**
 * 交錯進場：由下往上淡入 + 微縮放（彈簧）。只在 graphicsLayer 讀取動畫值，不觸發重組。
 * 進入畫面後 [index] 越大越晚出現；之後才捲進畫面的項目立即播放。
 */
@Composable
internal fun Modifier.staggeredEntrance(tracker: EntranceTracker, key: Any, index: Int): Modifier {
    val first = remember(tracker, key) { tracker.seen.add(key) }
    val progress = remember(tracker, key) { Animatable(if (first) 0f else 1f) }
    if (first) {
        LaunchedEffect(tracker, key) {
            val elapsed = SystemClock.uptimeMillis() - tracker.startMs
            delay((index * 55L - elapsed).coerceIn(0L, 520L))
            progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 210f))
        }
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 36.dp.toPx()
        val s = 0.965f + 0.035f * p
        scaleX = s
        scaleY = s
    }
}

// ------------------------------------------------------------------ 列與開關

/** 圓角方形圖示底座。 */
@Composable
internal fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = B570Theme.colors.accent,
    size: Dp = 36.dp,
) {
    Box(
        modifier
            .size(size)
            .glass(RoundedCornerShape(size * 0.32f), fill = tint.copy(alpha = 0.12f), borderWidth = 0.75.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.54f))
    }
}

/** 霓虹開關：彈簧滑動的圓鈕 + 發光軌道（動畫值只在繪製階段讀取）。 */
@Composable
internal fun NeonSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = B570Theme.colors
    val t = animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.58f, stiffness = 520f), label = "switch")
    val track = animateColorAsState(if (checked) c.accent else c.textMuted, label = "switchTrack")
    Canvas(
        modifier
            .size(width = 48.dp, height = 28.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    ) {
        val p = t.value
        val col = track.value
        val r = size.height / 2f
        val cr = CornerRadius(r)
        drawRoundRect(col.copy(alpha = 0.16f + 0.22f * p.coerceIn(0f, 1f)), cornerRadius = cr)
        drawRoundRect(col.copy(alpha = 0.45f + 0.35f * p.coerceIn(0f, 1f)), cornerRadius = cr, style = Stroke(1.dp.toPx()))
        val knobR = r - 4.dp.toPx()
        val cx = r + (size.width - 2f * r) * p
        val center = Offset(cx, r)
        if (p > 0.02f) drawCircle(col.copy(alpha = 0.30f * p.coerceIn(0f, 1f)), knobR * 1.75f, center)
        drawCircle(if (checked) Color.White else c.textSecondary, knobR, center)
    }
}

/** 設定列：圖示 + 標題 / 說明 + 開關。整列可點（含無障礙 Switch 角色）。 */
@Composable
internal fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(B570Theme.radii.s))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) {
                haptics.tick()
                onCheckedChange(it)
            }
            // 右邊留 4 dp：開關圓鈕的光暈（半徑 1.75 倍）比開關寬，否則會被這一列的圓角裁切切掉一塊
            .padding(top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint = if (checked) c.accent else c.textSecondary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = B570Theme.type.titleS, color = c.textPrimary)
            if (subtitle != null) Text(subtitle, style = B570Theme.type.caption, color = c.textSecondary)
        }
        Spacer(Modifier.width(10.dp))
        NeonSwitch(checked, enabled = enabled)
    }
}

/** 卡片內的分隔線。 */
@Composable
internal fun SettingDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(B570Theme.colors.divider))
}

/** 卡片內的小標題（標籤 + 可選右側說明）。 */
@Composable
internal fun FieldLabel(title: String, modifier: Modifier = Modifier, trailing: String? = null, icon: ImageVector? = null) {
    val c = B570Theme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, style = B570Theme.type.titleS, color = c.textPrimary, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = B570Theme.type.caption, color = c.textSecondary)
    }
}

/** 一行資訊：左標籤、右數值。 */
@Composable
internal fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    valueColor: Color = B570Theme.colors.textPrimary,
    mono: Boolean = false,
) {
    val c = B570Theme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = c.textMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = B570Theme.type.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = if (mono) B570Theme.type.mono else B570Theme.type.readoutS,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 迷你數據格（ESP 狀態等）。 */
@Composable
internal fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = B570Theme.colors.textPrimary,
) {
    val c = B570Theme.colors
    Column(
        modifier
            .glass(RoundedCornerShape(B570Theme.radii.s), fill = c.glassHigh.copy(alpha = 0.45f), borderWidth = 0.75.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = B570Theme.type.readoutS, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (!unit.isNullOrEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = B570Theme.type.caption, color = c.textMuted, modifier = Modifier.padding(bottom = 1.dp))
            }
        }
    }
}

/** 玻璃圓形圖示按鈕：按下縮放 + 觸覺。 */
@Composable
internal fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = B570Theme.colors.textPrimary,
    size: Dp = 38.dp,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "iconBtn")
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = if (enabled) 1f else 0.35f
            }
            .glass(CircleShape, fill = if (pressed) c.glassHigh else c.glass, borderWidth = 0.75.dp)
            .clip(CircleShape)
            .clickable(src, indication = null, enabled = enabled, role = Role.Button) {
                haptics.click()
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** 提示列（未連線、訊號中斷等）。 */
@Composable
internal fun HintRow(icon: ImageVector, text: String, modifier: Modifier = Modifier, color: Color = B570Theme.colors.warn) {
    val c = B570Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.s), fill = color.copy(alpha = 0.10f), borderWidth = 0.75.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = B570Theme.type.bodySmall, color = c.textPrimary)
    }
}

/** 呼吸光點（「即時」指示）。 */
@Composable
internal fun LiveDot(color: Color, modifier: Modifier = Modifier, active: Boolean = true) {
    val t = rememberInfiniteTransition(label = "liveDot")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(
        modifier.size(8.dp).drawBehind {
            val r = size.minDimension / 2f
            if (active) drawCircle(color.copy(alpha = (1f - p.value) * 0.55f), r * (1f + p.value * 1.3f))
            drawCircle(if (active) color else color.copy(alpha = 0.4f), r)
        },
    )
}

// ------------------------------------------------------------------ 分頁標題 / 兩欄

/**
 * 右側詳情頁的標題（像遙控器面板的銘牌）：圖示底座 + 分類名稱 + 說明 + 可選右側元件，下方一條漸層細線。
 */
@Composable
internal fun PaneHeader(
    category: SettingsCategory,
    modifier: Modifier = Modifier,
    subtitle: String = category.subtitleZh,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = B570Theme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(category.icon, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(category.titleZh, style = B570Theme.type.titleM, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = B570Theme.type.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .drawWithCache {
                    val brush = Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.55f), c.accent2.copy(alpha = 0.25f), Color.Transparent))
                    onDrawBehind { drawRect(brush) }
                },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 寬版（詳情區 ≥ [WidePaneMin]）左右兩欄、窄版上下堆疊。兩欄之間畫一條淡分隔線（不用 intrinsic 量測，
 * 內含 SegmentedSelector 等 SubcomposeLayout 也安全）。窄版直接依序放 [first]、[second]。
 */
@Composable
internal fun AdaptiveColumns(
    wide: Boolean,
    modifier: Modifier = Modifier,
    firstWeight: Float = 1f,
    secondWeight: Float = 1f,
    gap: Dp = 24.dp,
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
) {
    if (!wide) {
        Column(modifier.fillMaxWidth()) {
            first()
            second()
        }
        return
    }
    val line = B570Theme.colors.divider
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val g = gap.toPx()
                val x = (size.width - g) * firstWeight / (firstWeight + secondWeight) + g / 2f
                drawLine(line, Offset(x, 6.dp.toPx()), Offset(x, size.height - 6.dp.toPx()), 1.dp.toPx())
            },
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        Column(Modifier.weight(firstWeight), content = first)
        Column(Modifier.weight(secondWeight), content = second)
    }
}

/** 詳情區寬度達到這個值就改用左右兩欄（平板；手機橫放約 350–550 dp，維持單欄）。 */
internal val WidePaneMin = WIDE_PANE_MIN_DP.dp

// ------------------------------------------------------------------ 對話框

/**
 * 手機橫放（螢幕高 < 520 dp 且寬 ≥ 600 dp）時，設定頁的對話框改用左右兩欄，
 * 高度減半、鍵盤彈出時也比較容易看到按鈕。平板維持單欄。
 */
@Composable
internal fun rememberDialogTwoPane(): Boolean {
    val conf = LocalConfiguration.current
    return conf.screenHeightDp < 520 && conf.screenWidthDp >= 600
}

/**
 * 設定頁對話框外框：用共用 [GlassDialog]（沉浸式、內容超高可捲動、矮螢幕緊湊留白），
 * 四周留 16 / 12 dp 不貼邊；[wide] = 兩欄版面（最寬 680 dp），否則 440 dp。
 * 內容本身**不要**再包 verticalScroll（外框已可捲動）。
 */
@Composable
internal fun SettingsDialog(
    onDismissRequest: () -> Unit,
    glow: Color,
    dismissible: Boolean = true,
    wide: Boolean = false,
    maxWidth: Dp = if (wide) 680.dp else 440.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassDialog(
        onDismissRequest = { if (dismissible) onDismissRequest() },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        maxWidth = maxWidth,
        glow = glow.copy(alpha = 0.45f),
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
        content = content,
    )
}

// ------------------------------------------------------------------ 觸覺（繞過設定）

/** 直接觸發確認震動（用於「開啟觸覺回饋」那一下：此時設定還沒生效）。 */
internal fun View.confirmBuzz() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
}

// ------------------------------------------------------------------ 格式化

/** 運作時間：45 秒、12 分 5 秒、3 小時 12 分、2 天 3 小時。 */
internal fun formatUptime(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val d = s / 86_400
    val h = (s % 86_400) / 3_600
    val m = (s % 3_600) / 60
    val sec = s % 60
    return when {
        d > 0 -> "$d 天 $h 小時"
        h > 0 -> "$h 小時 $m 分"
        m > 0 -> "$m 分 $sec 秒"
        else -> "$sec 秒"
    }
}

/** 位元組 → KB（小數 1 位）。 */
internal fun formatKb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1024f)

/** 千分位。 */
internal fun formatCount(n: Long): String = String.format(Locale.US, "%,d", n)

/** 相對時間：剛剛、5 分鐘前、3 小時前、昨天、3 天前、2026/10/02。 */
internal fun formatRelative(thenMs: Long, nowMs: Long): String {
    if (thenMs <= 0L) return "尚未連線過"
    val diff = (nowMs - thenMs).coerceAtLeast(0L)
    val min = diff / 60_000
    val hour = min / 60
    val day = hour / 24
    return when {
        min < 1 -> "剛剛"
        min < 60 -> "$min 分鐘前"
        hour < 24 -> "$hour 小時前"
        day < 2 -> "昨天"
        day < 7 -> "$day 天前"
        else -> SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN).format(Date(thenMs))
    }
}
