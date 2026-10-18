package com.b570.pilot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import java.util.Locale

/**
 * 對話框視窗也維持沉浸式（否則對話框取得焦點時系統列會跑出來）。在自己的 Dialog 內容第一行呼叫；
 * [GlassDialog]、[ConfirmDialog]、[NumberInputDialog] 已內建。
 */
@Composable
fun DialogImmersiveEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
}

/** 對話框與螢幕左右 / 上下邊緣的最小距離（[GlassDialog]）。 */
val DialogEdgeH = 16.dp
val DialogEdgeV = 12.dp

/** 橫向矮螢幕（高 < 480 dp，手機橫放）時對話框用緊湊排版。 */
@Composable
fun isShortScreen(): Boolean = LocalConfiguration.current.screenHeightDp < 480

/**
 * 玻璃對話框外框（橫向友善）：沉浸式、內容超過高度時可捲動（鍵盤彈出也不會被切掉）、寬度上限 [maxWidth]。
 * 畫面自己的對話框建議都用這個包。
 *
 * 與螢幕邊緣一律保留 [DialogEdgeH] × [DialogEdgeV]：平台預設寬度（`usePlatformDefaultWidth = true`，預設值）
 * 在橫向手機上最寬可到整個螢幕（640 dp 寬的手機上 maxWidth 640 會貼邊），所以這裡改用
 * `usePlatformDefaultWidth = false` + 自己的外距。呼叫端自己傳 `usePlatformDefaultWidth = false` 時視為自行處理外距（不再加）。
 * @param contentPadding null = 自動（矮螢幕 20 × 16 dp，其他 24 dp）
 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 440.dp,
    glow: Color? = B570Theme.colors.accent.copy(alpha = 0.4f),
    contentPadding: PaddingValues? = null,
    scrollable: Boolean = true,
    properties: DialogProperties = DialogProperties(),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = B570Theme.colors
    val short = isShortScreen()
    val ownEdge = properties.usePlatformDefaultWidth
    val props = if (ownEdge) {
        DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = properties.decorFitsSystemWindows,
        )
    } else {
        properties
    }
    Dialog(onDismissRequest = onDismissRequest, properties = props) {
        DialogImmersiveEffect()
        Column(
            modifier
                .then(if (ownEdge) Modifier.padding(horizontal = DialogEdgeH, vertical = DialogEdgeV) else Modifier)
                .widthIn(max = maxWidth)
                .glass(RoundedCornerShape(B570Theme.radii.xl), fill = c.surface.copy(alpha = 0.97f), glow = glow)
                .clip(RoundedCornerShape(B570Theme.radii.xl))
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(contentPadding ?: if (short) PaddingValues(horizontal = 20.dp, vertical = 16.dp) else PaddingValues(24.dp)),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

/**
 * 玻璃風格確認對話框（橫向矮螢幕自動用緊湊排版）。
 * @param danger true = 紅色確認鈕（刪除、緊急動作、重設）
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "確定",
    dismissText: String = "取消",
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = B570Theme.colors
    val tint = if (danger) c.danger else c.accent
    val short = isShortScreen()
    GlassDialog(
        onDismissRequest = onDismiss,
        maxWidth = if (short) 420.dp else 380.dp,
        glow = tint.copy(alpha = 0.5f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val iconSize = if (short) 40.dp else 52.dp
        Box(Modifier.size(iconSize).glass(CircleShape, fill = tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon ?: if (danger) Icons.Outlined.WarningAmber else Icons.AutoMirrored.Outlined.HelpOutline, null, tint = tint, modifier = Modifier.size(iconSize / 2))
        }
        Spacer(Modifier.height(if (short) 10.dp else 14.dp))
        Text(title, style = B570Theme.type.titleM, color = c.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(if (short) 6.dp else 8.dp))
        Text(message, style = B570Theme.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(if (short) 16.dp else 22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton(dismissText, onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
            NeonButton(confirmText, onConfirm, Modifier.weight(1f), style = if (danger) NeonButtonStyle.DANGER else NeonButtonStyle.PRIMARY)
        }
    }
}

/**
 * 輸入精確數值的對話框（ValueSlider 點數值時使用，也可單獨使用）。
 * 超出範圍會自動夾限；格式錯誤時確定鈕停用。
 */
@Composable
fun NumberInputDialog(
    title: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
    decimals: Int = 2,
    unit: String = "",
) {
    val c = B570Theme.colors
    val start = String.format(Locale.US, "%.${decimals}f", initial)
    var text by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val parsed = text.text.replace(',', '.').toFloatOrNull()
    val valid = parsed != null && !parsed.isNaN()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val rangeText = String.format(Locale.US, "範圍 %.${decimals}f ~ %.${decimals}f %s", range.start, range.endInclusive, unit).trim()
    val submit = { if (valid) onConfirm(parsed!!.coerceIn(range.start, range.endInclusive)) }
    val short = isShortScreen()
    GlassDialog(onDismissRequest = onDismiss, maxWidth = if (short) 420.dp else 360.dp) {
        run {
            Text(title, style = B570Theme.type.titleM, color = c.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(rangeText, style = B570Theme.type.caption, color = c.textSecondary)
            Spacer(Modifier.height(if (short) 10.dp else 16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = B570Theme.type.readoutM.copy(color = c.textPrimary),
                suffix = if (unit.isNotEmpty()) ({ Text(unit, color = c.textSecondary) }) else null,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent,
                    unfocusedBorderColor = c.glassBorder,
                    cursorColor = c.accent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(if (short) 12.dp else 20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeonButton("取消", onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
                NeonButton("確定", { submit() }, Modifier.weight(1f), enabled = valid)
            }
        }
    }
}

