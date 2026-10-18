package com.b570.pilot.ui.screens.tune

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BackHand
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.protocol.Params
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.AnimatedNumber
import com.b570.pilot.ui.components.ConfirmDialog
import com.b570.pilot.ui.components.GlassDialog
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.isShortScreen
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 依 [TuneController.dialog] 顯示對話框。 */
@Composable
internal fun TuneDialogs(vm: AppViewModel, ctl: TuneController, editable: Boolean, motorsOn: Boolean) {
    val close = { ctl.dialog = null }
    when (val d = ctl.dialog) {
        null -> Unit
        TuneDialog.SaveNeedsStop -> ConfirmDialog(
            title = "請先停止馬達再儲存",
            message = "馬達運轉中無法寫入 Flash。按「停止並儲存」會先停止馬達，車子會失去平衡，請先扶好車身。",
            confirmText = "停止並儲存",
            icon = Icons.Rounded.PowerSettingsNew,
            onConfirm = { close(); ctl.save(stopFirst = true) },
            onDismiss = close,
        )
        TuneDialog.SaveWithStaged -> ConfirmDialog(
            title = "還有 ${ctl.stagedCount} 項變更未套用",
            message = "要先把暫存的變更套用到車上，再一起儲存嗎？",
            confirmText = "套用並儲存",
            icon = Icons.Rounded.Save,
            onConfirm = { close(); ctl.save(applyStagedFirst = true) },
            onDismiss = close,
        )
        TuneDialog.ConfirmReset -> ConfirmDialog(
            title = "恢復預設值？",
            message = "全部 ${Params.COUNT} 個參數（含 PID 與機械零點）會恢復原廠預設。這只改車上目前的值，按「儲存到車上」才會寫入。" +
                if (motorsOn) "\n馬達運轉中，車身可能會晃一下。" else "",
            confirmText = "恢復預設",
            danger = true,
            icon = Icons.Rounded.RestartAlt,
            onConfirm = { close(); ctl.resetDefaults() },
            onDismiss = close,
        )
        TuneDialog.ConfirmDiscard -> ConfirmDialog(
            title = "捨棄 ${ctl.stagedCount} 項變更？",
            message = "暫存的修改會清除，車上的數值不受影響。",
            confirmText = "捨棄",
            danger = true,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = { close(); ctl.discardStaged() },
            onDismiss = close,
        )
        TuneDialog.ZeroCal -> ZeroCalDialog(vm, ctl, editable, motorsOn, onDismiss = close)
        TuneDialog.NewProfile -> ProfileNameDialog(ctl, onDismiss = close)
        is TuneDialog.ApplyProfile -> ApplyProfileDialog(d.profile, ctl, motorsOn, onDismiss = close)
        is TuneDialog.DeleteProfile -> ConfirmDialog(
            title = "刪除「${d.profile.name}」？",
            message = "刪除後無法復原，車上的參數不受影響。",
            confirmText = "刪除",
            danger = true,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = { close(); ctl.deleteProfile(d.profile) },
            onDismiss = close,
        )
    }
}

/**
 * 整定頁對話框外框：共用 [GlassDialog]（沉浸式、矮螢幕緊湊內距、內容超高可捲動）+ 執行中不可關閉。
 * @param scrollable false = 呼叫端自己決定哪一塊捲動（例如按鈕固定在下方）
 */
@Composable
private fun TuneDialogFrame(
    onDismiss: () -> Unit,
    glow: Color,
    dismissible: Boolean = true,
    maxWidth: Dp = 400.dp,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        maxWidth = maxWidth,
        glow = glow.copy(alpha = 0.45f),
        scrollable = scrollable,
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
        content = content,
    )
}

@Composable
private fun DialogTitle(icon: ImageVector, title: String, tint: Color) {
    val c = B570Theme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).glass(CircleShape, fill = tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = B570Theme.type.titleM, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ====================================================================== 零點校正

private const val VISUAL_GAIN = 3f // 示意圖放大傾角，小角度也看得出來
private const val MAX_TILT = 35f

@Composable
private fun ZeroCalDialog(vm: AppViewModel, ctl: TuneController, editable: Boolean, motorsOn: Boolean, onDismiss: () -> Unit) {
    val c = B570Theme.colors
    val state = ctl.zeroState
    val running = state == ZeroCalState.Running
    val zero = ctl.param(Params.ZERO_ANGLE)
    // 韌體校正後會把 RAM 裡「全部」參數寫入 Flash：有未儲存的修改要先講清楚
    val slow = vm.telemetrySlow.collectAsStateWithLifecycle()
    val dirty by remember { derivedStateOf { slow.value?.paramsDirty == true } }
    val unsaved = dirty || ctl.inFlight > 0
    val body: @Composable () -> Unit = {
        AnimatedContent(
            targetState = state as? ZeroCalState.Done,
            transitionSpec = { (fadeIn(tween(260)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(120)) },
            label = "zeroCal",
        ) { done ->
            if (done != null) {
                ZeroDone(done)
            } else {
                ZeroInstructions(
                    vm, ctl,
                    zeroText = String.format(Locale.US, "%+.2f°", zero.display),
                    editable = editable,
                    motorsOn = motorsOn,
                    unsaved = unsaved,
                )
            }
        }
    }
    val buttons: @Composable () -> Unit = {
        if (state is ZeroCalState.Done) {
            NeonButton("完成", onDismiss, Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeonButton("取消", onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY, enabled = !running)
                NeonButton(
                    if (unsaved && !running) "校正並儲存" else "校正",
                    onClick = ctl::runZeroCal,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.CenterFocusStrong,
                    enabled = editable && !motorsOn && ctl.busyOp == null,
                    loading = running,
                )
            }
        }
    }
    if (isShortScreen()) {
        // 手機橫放（高 < 480 dp）：左 = 示意圖、右 = 說明（可捲動）+ 按鈕固定在下方
        TuneDialogFrame(onDismiss = onDismiss, glow = c.accent, dismissible = !running, maxWidth = 640.dp, scrollable = false) {
            DialogTitle(Icons.Rounded.CenterFocusStrong, "零點校正", c.accent)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().weight(1f, fill = false), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(190.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ZeroIllustration(vm, zeroDeg = { zero.display }, modifier = Modifier.fillMaxWidth().height(150.dp))
                    Spacer(Modifier.height(6.dp))
                    ZeroLegend(stacked = true)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { body() }
                    Spacer(Modifier.height(14.dp))
                    buttons()
                }
            }
        }
    } else {
        TuneDialogFrame(onDismiss = onDismiss, glow = c.accent, dismissible = !running) {
            DialogTitle(Icons.Rounded.CenterFocusStrong, "零點校正", c.accent)
            Spacer(Modifier.height(14.dp))
            ZeroIllustration(vm, zeroDeg = { zero.display }, modifier = Modifier.fillMaxWidth().height(170.dp))
            Spacer(Modifier.height(8.dp))
            ZeroLegend(stacked = false)
            Spacer(Modifier.height(16.dp))
            body()
            Spacer(Modifier.height(20.dp))
            buttons()
        }
    }
}

/** 示意圖圖例：虛線 = 目前零點、實線 = 車身。 */
@Composable
private fun ZeroLegend(stacked: Boolean) {
    val c = B570Theme.colors
    val zeroItem: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendMark(c.accent, dashed = true)
            Spacer(Modifier.width(6.dp))
            Text("目前零點", style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1)
        }
    }
    val bodyItem: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendMark(c.accent2, dashed = false)
            Spacer(Modifier.width(6.dp))
            Text("車身（放大 3 倍）", style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1)
        }
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            zeroItem()
            bodyItem()
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            zeroItem()
            Spacer(Modifier.width(16.dp))
            bodyItem()
        }
    }
}

@Composable
private fun LegendMark(color: Color, dashed: Boolean) {
    Box(
        Modifier.size(width = 16.dp, height = 3.dp).drawWithCache {
            val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
            onDrawBehind {
                drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round, effect)
            }
        },
    )
}

@Composable
private fun ZeroInstructions(
    vm: AppViewModel,
    ctl: TuneController,
    zeroText: String,
    editable: Boolean,
    motorsOn: Boolean,
    unsaved: Boolean,
) {
    val c = B570Theme.colors
    val err = (ctl.zeroState as? ZeroCalState.Error)?.messageZh
    val lastErr = remember { arrayOfNulls<String>(1) } // 收起動畫期間保留文字
    if (err != null) lastErr[0] = err
    Column(Modifier.fillMaxWidth()) {
        Text("扶住車身在平衡點，馬達停止後按「校正」", style = B570Theme.type.body, color = c.textPrimary)
        // 馬達運轉中（校正鈕停用的原因）放最前面：手機橫放時說明區很矮，放在最後會被捲到看不到
        AnimatedVisibility(visible = editable && motorsOn, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.warn.copy(alpha = 0.08f))
                    .border(1.dp, c.warn.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.WarningAmber, null, tint = c.warn, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("馬達運轉中", style = B570Theme.type.bodySmall, color = c.warn, modifier = Modifier.weight(1f))
                NeonButton(
                    "停止馬達",
                    onClick = ctl::stopMotors,
                    style = NeonButtonStyle.DANGER,
                    compact = true,
                    loading = ctl.busyOp == TuneOp.STOP,
                    enabled = ctl.busyOp == null,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        StepLine(1, "先停止馬達，車子會失去平衡，請扶好")
        StepLine(2, "扶著車身前後微調，找到放手也不會倒的位置")
        StepLine(3, "保持不動，按「校正」")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniReadout("目前傾角", Modifier.weight(1f)) {
                LivePitchText(vm, B570Theme.type.readoutS, c.textPrimary)
            }
            MiniReadout("目前零點", Modifier.weight(1f)) {
                Text(zeroText, style = B570Theme.type.readoutS, color = c.accent)
            }
        }
        AnimatedVisibility(visible = editable && unsaved, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            HintRow(
                Icons.Rounded.Save,
                "車上有未儲存的參數修改：校正會自動存檔，這些修改也會一起寫入車上（重開機後仍保留）",
                c.warn,
                Modifier.padding(top = 12.dp),
                filled = true,
            )
        }
        if (!editable) {
            HintRow(Icons.Rounded.Lock, "未連線，無法校正", c.warn, Modifier.padding(top = 12.dp))
        }
        AnimatedVisibility(visible = err != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            HintRow(Icons.Rounded.WarningAmber, lastErr[0].orEmpty(), c.danger, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun StepLine(n: Int, text: String) {
    val c = B570Theme.colors
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(c.accent.copy(alpha = 0.16f))
                .border(1.dp, c.accent.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = B570Theme.type.caption, color = c.accent)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = B570Theme.type.bodySmall, color = c.textSecondary)
    }
}

@Composable
private fun MiniReadout(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    val c = B570Theme.colors
    Column(modifier.glass(RoundedCornerShape(B570Theme.radii.s), fill = c.glassHigh).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = B570Theme.type.caption, color = c.textSecondary)
        Spacer(Modifier.height(2.dp))
        value()
    }
}

/** 傾角讀數（≤ 10 Hz 葉節點）。 */
@Composable
private fun LivePitchText(vm: AppViewModel, style: TextStyle, color: Color) {
    val t by vm.telemetrySlow.collectAsStateWithLifecycle()
    val text = t?.let { String.format(Locale.US, "%+.2f°", it.pitchDeg) } ?: "--"
    Text(text, style = style, color = color, maxLines = 1)
}

@Composable
private fun ZeroDone(done: ZeroCalState.Done) {
    val c = B570Theme.colors
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(done) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Rounded.CheckCircle,
            null,
            tint = c.ok,
            modifier = Modifier.size(44.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        )
        Spacer(Modifier.height(8.dp))
        Text("新零點", style = B570Theme.type.label, color = c.textSecondary)
        AnimatedNumber(done.after, decimals = 2, style = B570Theme.type.readoutL, color = c.ok, unit = "°", signed = true)
        done.before?.let { b ->
            Text(String.format(Locale.US, "原本 %+.2f°", b), style = B570Theme.type.caption, color = c.textMuted)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (done.savedOthers) {
                "已自動存到車上，重開機後仍會保留\n其他未儲存的參數修改也一併存入了"
            } else {
                "已自動存到車上，重開機後仍會保留"
            },
            style = B570Theme.type.bodySmall,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 示意圖：側面看的平衡車，車身依即時傾角傾斜（只在繪製階段讀遙測），
 * 虛線為目前零點，弧線顯示車身與零點的差距（< 0.5° 變綠色）。
 */
@Composable
private fun ZeroIllustration(vm: AppViewModel, zeroDeg: () -> Float, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val telem = vm.telemetry.collectAsStateWithLifecycle()
    val bobAnim = rememberInfiniteTransition(label = "hand")
    val bob by bobAnim.animateFloat(
        -1f, 1f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    Layout(
        modifier = modifier.drawWithCache {
            val groundY = size.height - 10.dp.toPx()
            val wheelR = 20.dp.toPx()
            val pivot = Offset(size.width / 2f, groundY - wheelR)
            val bodyW = 28.dp.toPx()
            val bodyH = 92.dp.toPx()
            val refLen = bodyH + 22.dp.toPx()
            val arcR = bodyH * 0.62f
            val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
            val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()))
            val thin = 1.5.dp.toPx()
            val wheelStroke = Stroke(3.dp.toPx())
            val bodyStroke = Stroke(1.5.dp.toPx())
            val arcStroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
            val corner = CornerRadius(7.dp.toPx())
            val bodyTop = Offset(pivot.x - bodyW / 2f, pivot.y - bodyH)
            val bodyBrush = Brush.verticalGradient(
                listOf(c.accent2.copy(alpha = 0.55f), c.accent.copy(alpha = 0.25f)),
                startY = pivot.y - bodyH,
                endY = pivot.y,
            )
            val glow = Brush.radialGradient(listOf(c.accent.copy(alpha = 0.16f), Color.Transparent), center = pivot, radius = refLen)
            val screenTop = Offset(bodyTop.x + 5.dp.toPx(), bodyTop.y + 10.dp.toPx())
            val screenSize = Size(bodyW - 10.dp.toPx(), 13.dp.toPx())
            val screenCorner = CornerRadius(2.dp.toPx())
            val margin = 18.dp.toPx()
            onDrawBehind {
                val pitch = telem.value?.pitchDeg ?: 0f
                val z = zeroDeg()
                val tilt = (pitch * VISUAL_GAIN).coerceIn(-MAX_TILT, MAX_TILT)
                val zeroTilt = (z * VISUAL_GAIN).coerceIn(-MAX_TILT, MAX_TILT)
                val near = abs(pitch - z) < 0.5f
                drawCircle(glow, refLen, pivot)
                drawLine(c.textMuted.copy(alpha = 0.6f), Offset(margin, groundY), Offset(size.width - margin, groundY), thin, StrokeCap.Round)
                // 真正的鉛直線（點線）
                drawLine(c.textMuted.copy(alpha = 0.7f), pivot, Offset(pivot.x, pivot.y - refLen), thin, StrokeCap.Round, dots)
                // 目前零點（虛線）
                rotate(zeroTilt, pivot) {
                    drawLine(c.accent, pivot, Offset(pivot.x, pivot.y - refLen), 2.dp.toPx(), StrokeCap.Round, dash)
                }
                // 車身與零點的夾角
                drawArc(
                    color = if (near) c.ok else c.warn,
                    startAngle = -90f + zeroTilt,
                    sweepAngle = tilt - zeroTilt,
                    useCenter = false,
                    topLeft = Offset(pivot.x - arcR, pivot.y - arcR),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = arcStroke,
                )
                // 車身
                rotate(tilt, pivot) {
                    drawRoundRect(bodyBrush, bodyTop, Size(bodyW, bodyH), corner)
                    drawRoundRect(c.accent2.copy(alpha = 0.9f), bodyTop, Size(bodyW, bodyH), corner, style = bodyStroke)
                    drawRoundRect(c.background, screenTop, screenSize, screenCorner)
                }
                // 輪子
                drawCircle(c.surface, wheelR, pivot)
                drawCircle(c.textSecondary, wheelR - 1.5.dp.toPx(), pivot, style = wheelStroke)
                drawCircle(c.textSecondary, 3.dp.toPx(), pivot)
            }
        },
        content = {
            Icon(Icons.Rounded.BackHand, null, tint = c.textPrimary.copy(alpha = 0.85f), modifier = Modifier.size(26.dp))
        },
    ) { measurables, constraints ->
        val hand = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // 手扶在車身上段的側邊，隨傾角移動（只在 placement 階段讀遙測）
            hand.placeWithLayer(0, 0) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                val groundY = h - 10.dp.toPx()
                val wheelR = 20.dp.toPx()
                val px = w / 2f
                val py = groundY - wheelR
                val pitch = telem.value?.pitchDeg ?: 0f
                val rad = Math.toRadians((pitch * VISUAL_GAIN).coerceIn(-MAX_TILT, MAX_TILT).toDouble())
                val dx = 14.dp.toPx() + hand.width / 2f // 車身半寬 + 手半寬
                val dy = 92.dp.toPx() * 0.68f
                // 車身座標 (dx, -dy) 順時針旋轉
                val x = px + (dx * cos(rad) + dy * sin(rad)).toFloat()
                val y = py + (dx * sin(rad) - dy * cos(rad)).toFloat()
                translationX = x - hand.width / 2f
                translationY = y - hand.height / 2f + bob * 2.dp.toPx()
            }
        }
    }
}

// ====================================================================== 設定檔

@Composable
private fun ProfileNameDialog(ctl: TuneController, onDismiss: () -> Unit) {
    val c = B570Theme.colors
    var text by remember { mutableStateOf("") }
    val name = text.trim()
    val tooLong = name.length > MAX_NAME
    val valid = name.isNotEmpty() && !tooLong
    val taken = valid && ctl.profileNameTaken(name)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        if (valid) {
            ctl.saveProfile(name)
            onDismiss()
        }
    }
    TuneDialogFrame(onDismiss = onDismiss, glow = c.accent) {
        DialogTitle(Icons.Rounded.BookmarkAdd, "另存設定檔", c.accent)
        Spacer(Modifier.height(8.dp))
        Text(
            "記住目前 ${Params.COUNT - TunePresets.EXCLUDED.size} 個參數（不含機械零點），之後可一鍵套用。",
            style = B570Theme.type.bodySmall,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= MAX_NAME + 4) text = it },
            singleLine = true,
            placeholder = { Text("例如：地毯用", color = c.textMuted) },
            isError = tooLong,
            textStyle = B570Theme.type.body.copy(color = c.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent,
                unfocusedBorderColor = c.glassBorder,
                cursorColor = c.accent,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        AnimatedVisibility(visible = tooLong || taken, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                if (tooLong) "名稱最多 $MAX_NAME 個字" else "已有同名設定檔，會直接覆蓋",
                style = B570Theme.type.caption,
                color = if (tooLong) c.danger else c.warn,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("取消", onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
            NeonButton(if (taken) "覆蓋" else "儲存", { submit() }, Modifier.weight(1f), enabled = valid)
        }
    }
}

private const val MAX_NAME = 16

@Composable
private fun ApplyProfileDialog(profile: TuneProfile, ctl: TuneController, motorsOn: Boolean, onDismiss: () -> Unit) {
    val c = B570Theme.colors
    val changes = remember(profile, motorsOn) { ctl.diff(profile, motorsOn) }
    val skipped = remember(profile, motorsOn) { if (motorsOn) ctl.skippedWhileRunning(profile) else emptyList() }
    val touchesGains = motorsOn && changes.any { it.def.group in GAIN_GROUPS }
    val live = ctl.liveApply
    val short = isShortScreen()
    TuneDialogFrame(onDismiss = onDismiss, glow = c.accent, maxWidth = if (short) 460.dp else 400.dp) {
        DialogTitle(Icons.Rounded.Tune, "套用「${profile.name}」？", c.accent)
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                changes.isEmpty() -> "目前的數值已經和這個設定檔相同。"
                live -> "以下 ${changes.size} 項會馬上送到車上："
                else -> "以下 ${changes.size} 項會先暫存，按「套用」才會送出："
            },
            style = B570Theme.type.bodySmall,
            color = c.textSecondary,
        )
        if (changes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (short) 132.dp else 260.dp)
                    .glass(RoundedCornerShape(B570Theme.radii.s), fill = c.glassHigh)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                changes.forEach { ch ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ch.def.labelZh, style = B570Theme.type.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                        Text(ch.def.format(ch.from), style = B570Theme.type.label, color = c.textMuted)
                        Text("  →  ", style = B570Theme.type.label, color = c.textMuted)
                        Text(ch.def.format(ch.to), style = B570Theme.type.label, color = c.accent)
                    }
                }
            }
        }
        if (touchesGains) {
            HintRow(Icons.Rounded.WarningAmber, "調整中請注意車身穩定", c.warn, Modifier.padding(top = 12.dp), filled = true)
        }
        if (skipped.isNotEmpty()) {
            HintRow(
                Icons.Rounded.Lock,
                "馬達運轉中，${skipped.joinToString("、") { "「${it.labelZh}」" }}這次不會變更",
                c.warn,
                Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        if (changes.isEmpty()) {
            NeonButton("好", onDismiss, Modifier.fillMaxWidth(), style = NeonButtonStyle.SECONDARY)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeonButton("取消", onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
                NeonButton(
                    if (live) "套用" else "暫存",
                    onClick = {
                        onDismiss()
                        ctl.applyProfile(profile, motorsOn)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
