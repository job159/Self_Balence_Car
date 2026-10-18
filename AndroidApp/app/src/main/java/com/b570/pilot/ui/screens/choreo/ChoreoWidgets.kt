package com.b570.pilot.ui.screens.choreo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.components.GlassDialog
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.isShortScreen
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass

internal const val MAX_NAME_LEN = 20

/** 動作種類 3×3 排列（前進 後退 停頓 / 左轉 右轉 弧線 / 搖擺 點頭 漸變）。 */
internal val KIND_ROWS: List<List<MoveKind>> = MoveKind.entries.chunked(3)

/** 按下時縮小（彈簧回彈）。 */
@Composable
internal fun Modifier.pressScale(src: InteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "press")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

/**
 * 交錯進場：[progress] 由 0 跑到 2（約 2 秒），第 [index] 個元件延後 0.06 開始。只在 layer 階段讀取。
 */
internal fun Modifier.staggerIn(progress: () -> Float, index: Int): Modifier = this.graphicsLayer {
    val local = ((progress() - index.coerceAtMost(20) * 0.06f) / 0.45f).coerceIn(0f, 1f)
    val e = FastOutSlowInEasing.transform(local)
    alpha = e
    translationY = (1f - e) * 26.dp.toPx()
    val s = 0.97f + 0.03f * e
    scaleX = s
    scaleY = s
}

/** 圓形玻璃圖示鈕。 */
@Composable
internal fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = B570Theme.colors.textPrimary,
    fill: Color = B570Theme.colors.glass,
    glow: Color? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(src, 0.88f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .glass(CircleShape, fill = fill, glow = glow, glowElevation = 10.dp)
            .clip(CircleShape)
            .clickable(src, indication = null, enabled = enabled, role = Role.Button) { haptics.click(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** 動作種類小膠囊（圖示 + 名稱）。 */
@Composable
internal fun KindChip(
    kind: MoveKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showIcon: Boolean = true,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val kc = kind.color(c)
    val bg by animateColorAsState(if (selected) kc.copy(alpha = 0.2f) else c.glass, label = "chipBg")
    val border by animateColorAsState(if (selected) kc.copy(alpha = 0.75f) else c.glassBorderDim, label = "chipBorder")
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .height(40.dp)
            .pressScale(src, 0.93f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(src, indication = null, enabled = enabled, role = Role.Button) { haptics.tick(); onClick() }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            Icon(kind.icon, null, tint = if (selected) kc else kc.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(kind.labelZh, style = B570Theme.type.label, color = when { selected -> c.textPrimary; showIcon -> c.textSecondary; else -> kc }, maxLines = 1, softWrap = false)
    }
}

/** 動作選擇格：預設 3×3；寬的卡片用 [perRow] = 5（5 + 4）。 */
@Composable
internal fun KindGrid(
    selected: MoveKind?,
    onSelect: (MoveKind) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    perRow: Int = 3,
) {
    val rows = if (perRow == 3) KIND_ROWS else remember(perRow) { MoveKind.entries.chunked(perRow.coerceAtLeast(1)) }
    val fs = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        // 窄的步驟卡（小手機、大字級）放不下「圖示 + 兩個字」時只顯示文字（文字改用動作顏色）
        val chipW = (maxWidth - 8.dp * (perRow - 1)) / perRow
        val showIcon = chipW >= 40.dp + 26.dp * fs
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in row) KindChip(k, k == selected, { onSelect(k) }, Modifier.weight(1f), enabled, showIcon)
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 小數據塊（標籤 + 數值）。 */
@Composable
internal fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val c = B570Theme.colors
    Column(
        modifier
            .glass(RoundedCornerShape(12.dp), fill = c.glass)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, style = B570Theme.type.readoutS, color = accent ?: c.textPrimary, maxLines = 1)
    }
}

/** 輸入名稱的對話框（新增 / 重新命名）。沉浸式、可捲動（橫向鍵盤彈出時不會被切掉）。 */
@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "確定",
) {
    val c = B570Theme.colors
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val trimmed = text.text.trim()
    val valid = trimmed.isNotEmpty()
    val focus = remember { FocusRequester() }
    GlassDialog(onDismissRequest = onDismiss, maxWidth = 380.dp) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Text(title, style = B570Theme.type.titleM, color = c.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text("最多 $MAX_NAME_LEN 個字", style = B570Theme.type.caption, color = c.textSecondary)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.text.length <= MAX_NAME_LEN) text = it },
            singleLine = true,
            textStyle = B570Theme.type.titleS.copy(color = c.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(trimmed) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent,
                unfocusedBorderColor = c.glassBorder,
                cursorColor = c.accent,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("取消", onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
            NeonButton(confirmText, { if (valid) onConfirm(trimmed) }, Modifier.weight(1f), enabled = valid)
        }
    }
}

/** 離開編輯器時詢問是否儲存（點外面 = 繼續編輯）。 */
@Composable
internal fun SaveChangesDialog(name: String, onSave: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    val c = B570Theme.colors
    val short = isShortScreen()
    GlassDialog(
        onDismissRequest = onDismiss,
        maxWidth = 400.dp,
        glow = c.accent.copy(alpha = 0.45f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!short) {
            Box(Modifier.size(52.dp).glass(CircleShape, fill = c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Save, null, tint = c.accent, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
        }
        Text("要儲存變更嗎？", style = B570Theme.type.titleM, color = c.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("「$name」有還沒儲存的修改。", style = B570Theme.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(if (short) 16.dp else 22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("不儲存", onDiscard, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY, color = c.danger)
            NeonButton("儲存", onSave, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        NeonButton("繼續編輯", onDismiss, Modifier.fillMaxWidth(), style = NeonButtonStyle.GHOST, compact = true, color = c.textSecondary)
    }
}

/** 動作選單的一個項目。 */
@Immutable
internal class SheetAction(val text: String, val icon: ImageVector, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * 動作選單（取代下拉選單：沉浸式下拉選單的彈出視窗會叫出系統列；對話框維持沉浸式、橫向置中好按）。
 * 點項目會先關閉選單再執行。
 */
@Composable
internal fun ActionSheetDialog(title: String, subtitle: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val c = B570Theme.colors
    GlassDialog(onDismissRequest = onDismiss, maxWidth = 360.dp, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            Text(title, style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        for (a in actions) SheetRow(a, onDismiss)
        Spacer(Modifier.height(4.dp))
        NeonButton("取消", onDismiss, Modifier.fillMaxWidth(), style = NeonButtonStyle.GHOST, compact = true, color = c.textSecondary)
    }
}

@Composable
private fun SheetRow(a: SheetAction, dismiss: () -> Unit) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val tint = if (a.danger) c.danger else c.textPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressScale(src, 0.97f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(src, indication = null, role = Role.Button) { haptics.click(); dismiss(); a.onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).glass(CircleShape, fill = (if (a.danger) c.danger else c.accent).copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(a.icon, null, tint = if (a.danger) c.danger else c.textSecondary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(a.text, style = B570Theme.type.body, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
