package com.b570.pilot.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiPassword
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.Severity
import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class WifiStep { FORM, CONFIRM, DONE }

/** 1、6、11 彼此不重疊（2.4 GHz）。 */
private val RECOMMENDED_CHANNELS = setOf(1, 6, 11)

/** ESP_ACK 失敗 → 給使用者看的句子。 */
private fun wifiFailureZh(st: AckStatus): String = when (st) {
    AckStatus.TIMEOUT -> "車子沒有回應，請靠近一點再試一次"
    AckStatus.OFFLINE -> "目前沒有連線，請先連上車子"
    AckStatus.BUSY -> "另一支手機正在控制這台車，無法修改"
    AckStatus.BAD_ARG -> "車子不接受這組名稱或密碼，請換一組"
    AckStatus.FAIL -> "車子寫入設定失敗，請再試一次"
    else -> "修改失敗：${st.labelZh}"
}

/**
 * 修改車子 Wi-Fi：表單（即時驗證 §3.4）→ 確認（說明會重開 Wi-Fi、OLED 顯示新 QR）→ 完成。
 * 指令在 ViewModel 範圍執行：就算畫面因重新連線而離開，也會完成並由 repository 自動更新記住的車、重新連線。
 */
@Composable
internal fun WifiEditDialog(
    vm: AppViewModel,
    initialSsid: String,
    initialPassword: String,
    currentChannel: Int?,
    demo: Boolean,
    motorsOn: Boolean,
    onDismiss: () -> Unit,
    initialChannel: Int = 0,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val dismiss by rememberUpdatedState(onDismiss)

    var ssid by remember { mutableStateOf(initialSsid) }
    var password by remember { mutableStateOf(initialPassword) }
    var channel by remember { mutableIntStateOf(initialChannel) }   // 預設沿用目前設定（固定頻道不會被改回自動）
    var step by remember { mutableStateOf(WifiStep.FORM) }
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val ssidErr = WifiCredentials.validateSsid(ssid)
    val pwErr = WifiCredentials.validatePassword(password)
    val valid = ssidErr == null && pwErr == null

    LaunchedEffect(step) {
        if (step == WifiStep.DONE) {
            delay(1700)
            dismiss()
        }
    }

    fun apply() {
        if (applying) return
        applying = true
        error = null
        val s = ssid
        val p = password
        val ch = channel
        vm.launch {
            val st = setWifi(s, p, ch, notify = false)
            applying = false
            if (st.isOk) {
                haptics.confirm()
                step = WifiStep.DONE
            } else {
                haptics.reject()
                error = wifiFailureZh(st)
            }
        }
    }

    val twoPane = rememberDialogTwoPane()
    SettingsDialog(onDismissRequest = onDismiss, glow = c.accent, dismissible = !applying && step != WifiStep.DONE, wide = twoPane) {
        AnimatedContent(
            targetState = step,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = {
                val fwd = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)) { w -> if (fwd) w / 3 else -w / 3 } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(200)) { w -> if (fwd) -w / 4 else w / 4 } + fadeOut(tween(150)))
                    .using(SizeTransform(clip = false))
            },
            label = "wifiStep",
        ) { s ->
            when (s) {
                WifiStep.FORM -> WifiForm(
                    ssid = ssid,
                    onSsid = { ssid = it; error = null },
                    ssidErr = ssidErr,
                    password = password,
                    onPassword = { password = it; error = null },
                    pwErr = pwErr,
                    channel = channel,
                    onChannel = { channel = it },
                    currentChannel = currentChannel,
                    demo = demo,
                    valid = valid,
                    twoPane = twoPane,
                    onCancel = onDismiss,
                    onNext = {
                        if (valid) {
                            haptics.click()
                            step = WifiStep.CONFIRM
                        } else {
                            haptics.reject()
                        }
                    },
                )
                WifiStep.CONFIRM -> WifiConfirm(
                    ssid = ssid,
                    password = password,
                    channel = channel,
                    demo = demo,
                    motorsOn = motorsOn,
                    applying = applying,
                    error = error,
                    twoPane = twoPane,
                    onBack = { if (!applying) { error = null; step = WifiStep.FORM } },
                    onApply = { apply() },
                )
                WifiStep.DONE -> WifiDone(demo)
            }
        }
    }
}

@Composable
private fun WifiForm(
    ssid: String,
    onSsid: (String) -> Unit,
    ssidErr: String?,
    password: String,
    onPassword: (String) -> Unit,
    pwErr: String?,
    channel: Int,
    onChannel: (Int) -> Unit,
    currentChannel: Int?,
    demo: Boolean,
    valid: Boolean,
    twoPane: Boolean,
    onCancel: () -> Unit,
    onNext: () -> Unit,
) {
    val c = B570Theme.colors
    val focus = LocalFocusManager.current
    var showPw by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.glassBorder,
        cursorColor = c.accent,
        focusedLabelColor = c.accent,
        errorBorderColor = c.danger,
        errorLabelColor = c.danger,
        errorSupportingTextColor = c.danger,
        errorCursorColor = c.danger,
    )
    val ssidField: @Composable () -> Unit = {
        OutlinedTextField(
            value = ssid,
            onValueChange = { onSsid(it.take(WifiCredentials.SSID_MAX + 8)) },
            label = { Text("名稱（SSID）") },
            singleLine = true,
            isError = ssidErr != null,
            textStyle = B570Theme.type.body.copy(color = c.textPrimary, fontFamily = FontFamily.Monospace),
            supportingText = { CounterLine(ssidErr ?: "英文、數字、底線或減號", ssid.length, WifiCredentials.SSID_MAX) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val passwordField: @Composable () -> Unit = {
        OutlinedTextField(
            value = password,
            onValueChange = { onPassword(it.take(WifiCredentials.PW_MAX + 8)) },
            label = { Text("密碼") },
            singleLine = true,
            isError = pwErr != null,
            textStyle = B570Theme.type.body.copy(color = c.textPrimary, fontFamily = FontFamily.Monospace),
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPw = !showPw }) {
                    Icon(
                        if (showPw) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPw) "隱藏密碼" else "顯示密碼",
                        tint = if (showPw) c.accent else c.textSecondary,
                    )
                }
            },
            supportingText = { CounterLine(pwErr ?: "8–16 碼，不可有空白與 ; , : \\ \"", password.length, WifiCredentials.PW_MAX) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); if (valid) onNext() }),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val buttons: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("取消", onCancel, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
            NeonButton("下一步", onNext, Modifier.weight(1f), enabled = valid)
        }
    }
    val demoHint: @Composable () -> Unit = {
        HintRow(Icons.Rounded.ErrorOutline, "展示模式：只會模擬，不會修改真車", color = c.accent2)
    }

    Column(Modifier.fillMaxWidth()) {
        DialogHeader(Icons.Rounded.WifiPassword, "修改車子 Wi-Fi", "改完後車子會重新啟動熱點", c.accent)
        if (twoPane) {
            // 手機橫放：左 = 名稱 / 密碼，右 = 頻道 + 按鈕（高度約減半，鍵盤彈出時也容易找到按鈕）
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    ssidField()
                    Spacer(Modifier.height(4.dp))
                    passwordField()
                }
                Column(Modifier.weight(1f)) {
                    ChannelPicker(channel, onChannel, currentChannel)
                    if (demo) {
                        Spacer(Modifier.height(10.dp))
                        demoHint()
                    }
                    Spacer(Modifier.height(14.dp))
                    buttons()
                }
            }
        } else {
            Spacer(Modifier.height(18.dp))
            ssidField()
            Spacer(Modifier.height(6.dp))
            passwordField()
            Spacer(Modifier.height(12.dp))
            ChannelPicker(channel, onChannel, currentChannel)
            if (demo) {
                Spacer(Modifier.height(12.dp))
                demoHint()
            }
            Spacer(Modifier.height(20.dp))
            buttons()
        }
    }
}

/** 欄位下方：說明或錯誤 + 字數。 */
@Composable
internal fun CounterLine(text: String, count: Int, max: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("$count/$max", color = if (count > max) B570Theme.colors.danger else B570Theme.colors.textMuted)
    }
}

/** 頻道選擇：自動 + 1..13，1/6/11 標示建議。 */
@Composable
private fun ChannelPicker(selected: Int, onSelect: (Int) -> Unit, current: Int?) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    Column {
        FieldLabel("頻道", trailing = current?.let { "目前使用 $it" })
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (ch in 0..13) {
                val sel = ch == selected
                val bg by animateColorAsState(if (sel) c.accent.copy(alpha = 0.22f) else c.glass, label = "chBg")
                val border by animateColorAsState(if (sel) c.accent else c.glassBorderDim, label = "chBorder")
                val fg by animateColorAsState(if (sel) c.textPrimary else c.textSecondary, label = "chFg")
                Column(
                    Modifier
                        .defaultMinSize(minWidth = 40.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(12.dp))
                        .noRippleClickable { if (!sel) { haptics.tick(); onSelect(ch) } }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = sel
                        }
                        .padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(if (ch == 0) "自動" else ch.toString(), style = B570Theme.type.label, color = fg)
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(if (ch in RECOMMENDED_CHANNELS) c.ok else Color.Transparent),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(c.ok))
            Spacer(Modifier.width(6.dp))
            Text("建議 1、6、11，彼此不會互相干擾；不確定就選自動", style = B570Theme.type.caption, color = c.textSecondary)
        }
    }
}

@Composable
private fun WifiConfirm(
    ssid: String,
    password: String,
    channel: Int,
    demo: Boolean,
    motorsOn: Boolean,
    applying: Boolean,
    error: String?,
    twoPane: Boolean,
    onBack: () -> Unit,
    onApply: () -> Unit,
) {
    val c = B570Theme.colors
    val summary: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(B570Theme.radii.m), fill = c.glassHigh.copy(alpha = 0.6f), borderWidth = 0.75.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            InfoRow("名稱", ssid, mono = true)
            InfoRow("密碼", password, mono = true)
            InfoRow("頻道", if (channel == 0) "自動" else channel.toString())
        }
    }
    val consequences: @Composable () -> Unit = {
        ConsequenceRow(Icons.Rounded.RestartAlt, "車子會重新啟動 Wi-Fi，約 5 秒後自動用新設定重新連線")
        ConsequenceRow(Icons.Rounded.QrCode2, "車上 OLED 會顯示新的 QR Code，其他手機要重新掃描")
        if (motorsOn && !demo) ConsequenceRow(Icons.Rounded.PauseCircle, "馬達運轉中：Wi-Fi 重新啟動時車子會自動煞停", tint = c.warn)
        if (demo) ConsequenceRow(Icons.Rounded.ErrorOutline, "展示模式只會模擬，不會修改真車", tint = c.accent2)

        AnimatedVisibility(error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            HintRow(Icons.Rounded.ErrorOutline, error ?: "", Modifier.padding(top = 10.dp), color = c.danger)
        }
    }
    val buttons: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("返回", onBack, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY, enabled = !applying)
            NeonButton(if (error != null) "重試" else "套用", onApply, Modifier.weight(1f), loading = applying)
        }
    }

    if (twoPane) {
        // 手機橫放：左 = 標題 + 新設定，右 = 會發生什麼 + 按鈕
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                DialogHeader(Icons.Rounded.RestartAlt, "確定要套用？", "請確認新的名稱、密碼與頻道", c.accent)
                Spacer(Modifier.height(12.dp))
                summary()
            }
            Column(Modifier.weight(1.1f)) {
                consequences()
                Spacer(Modifier.height(14.dp))
                buttons()
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DialogHeader(Icons.Rounded.RestartAlt, "確定要套用？", null, c.accent, centered = true)
            Spacer(Modifier.height(16.dp))
            summary()
            Spacer(Modifier.height(14.dp))
            consequences()
            Spacer(Modifier.height(20.dp))
            buttons()
        }
    }
}

@Composable
private fun WifiDone(demo: Boolean) {
    val c = B570Theme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SuccessBurst(c.ok)
        Spacer(Modifier.height(16.dp))
        Text("已套用", style = B570Theme.type.titleM, color = c.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            if (demo) "展示車的 Wi-Fi 設定已更新" else "車子正在重新開機，稍後會自動重新連線…",
            style = B570Theme.type.bodySmall,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 成功打勾：彈簧放大 + 擴散光環。 */
@Composable
internal fun SuccessBurst(color: Color) {
    val pop = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { ring.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f))
    }
    Box(
        Modifier.size(92.dp).drawBehind {
            val r = size.minDimension / 2f
            drawCircle(color.copy(alpha = (1f - ring.value) * 0.6f), radius = r * (0.55f + 0.45f * ring.value), style = Stroke(2.dp.toPx()))
            drawCircle(color.copy(alpha = 0.16f), radius = r * 0.58f * pop.value.coerceAtLeast(0f))
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Check,
            null,
            tint = color,
            modifier = Modifier.size(44.dp).graphicsLayer {
                val s = pop.value
                scaleX = s
                scaleY = s
            },
        )
    }
}

// ------------------------------------------------------------------ 恢復原廠

/**
 * 恢復原廠 Wi-Fi：列出後果 + 「按住 2 秒」才執行（強確認，避免誤觸）。
 * 成功時 repository 會移除記住的車並中斷連線（shell 自動回到連線頁）。
 * 手機橫放：左 = 警示圖示 + 標題，右 = 後果 + 按住按鈕。
 */
@Composable
internal fun FactoryResetDialog(vm: AppViewModel, demo: Boolean, motorsOn: Boolean, onDismiss: () -> Unit) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val dismiss by rememberUpdatedState(onDismiss)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val twoPane = rememberDialogTwoPane()

    val intro: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DangerBeacon()
            Spacer(Modifier.height(14.dp))
            Text("恢復原廠 Wi-Fi？", style = B570Theme.type.titleM, color = c.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                "車子會產生全新的名稱與密碼，並立刻重新開機。",
                style = B570Theme.type.bodySmall,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
    val body: @Composable () -> Unit = {
        ConsequenceRow(Icons.Rounded.LinkOff, "這支手機會中斷連線", tint = c.danger)
        ConsequenceRow(Icons.Rounded.DeleteOutline, "記住的這台車會被移除", tint = c.danger)
        ConsequenceRow(Icons.Rounded.QrCode2, "之後要重新掃描 OLED 上的新 QR Code", tint = c.danger)
        if (motorsOn && !demo) ConsequenceRow(Icons.Rounded.PauseCircle, "馬達運轉中：斷線後車子會自動煞停", tint = c.warn)
        if (demo) ConsequenceRow(Icons.Rounded.ErrorOutline, "展示模式只會模擬，不會修改真車", tint = c.accent2)

        AnimatedVisibility(error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            HintRow(Icons.Rounded.ErrorOutline, error ?: "", Modifier.padding(top = 10.dp), color = c.danger)
        }
        Spacer(Modifier.height(if (twoPane) 14.dp else 20.dp))
        HoldToConfirmButton(
            text = "按住 2 秒恢復原廠",
            holdingText = "繼續按住…",
            icon = Icons.Rounded.SettingsBackupRestore,
            loading = busy,
            onConfirmed = {
                busy = true
                error = null
                vm.launch {
                    val st = factoryWifi(notify = false)
                    busy = false
                    if (st.isOk) {
                        if (isDemo.value) notice("展示模式：已模擬恢復原廠 Wi-Fi", Severity.SUCCESS)
                        dismiss()
                    } else {
                        haptics.reject()
                        error = wifiFailureZh(st)
                    }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        NeonButton("取消", onDismiss, Modifier.fillMaxWidth(), style = NeonButtonStyle.GHOST, enabled = !busy, color = c.textSecondary)
    }

    SettingsDialog(onDismissRequest = onDismiss, glow = c.danger, dismissible = !busy, wide = twoPane, maxWidth = if (twoPane) 640.dp else 440.dp) {
        if (twoPane) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(0.85f)) { intro() }
                Column(Modifier.weight(1.15f)) { body() }
            }
        } else {
            intro()
            Spacer(Modifier.height(14.dp))
            body()
        }
    }
}

/** 按住才會觸發的危險按鈕：進度由左往右填滿，每 1/4 輕震一次，完成重震。 */
@Composable
private fun HoldToConfirmButton(
    text: String,
    holdingText: String,
    icon: ImageVector,
    loading: Boolean,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMs: Int = 2000,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val confirmed by rememberUpdatedState(onConfirmed)
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    // 失敗（loading 結束但仍是滿格）→ 退回
    LaunchedEffect(loading) {
        if (!loading && progress.value >= 1f) progress.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 300f))
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .graphicsLayer {
                val s = 1f - 0.035f * progress.value
                scaleX = s
                scaleY = s
            }
            .glass(shape, fill = c.danger.copy(alpha = 0.10f), glow = if (holding || loading) c.danger else null, glowElevation = 16.dp)
            .clip(shape)
            .drawBehind {
                drawRect(c.danger.copy(alpha = 0.55f), size = Size(size.width * progress.value, size.height))
            }
            .pointerInput(loading) {
                if (loading) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    holding = true
                    haptics.click()
                    val job: Job = scope.launch {
                        var quarter = (progress.value * 4).toInt()
                        val remaining = ((1f - progress.value) * holdMs).toInt().coerceAtLeast(1)
                        progress.animateTo(1f, tween(remaining, easing = LinearEasing)) {
                            val q = (value * 4).toInt()
                            if (q > quarter && q < 4) {
                                quarter = q
                                haptics.tick()
                            }
                        }
                        haptics.heavy()
                        holding = false
                        confirmed()
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) {
                        job.cancel()
                        holding = false
                        scope.launch { progress.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 380f)) }
                    }
                }
            }
            .semantics {
                role = Role.Button
                onClick(label = text) {
                    if (!loading) confirmed()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(icon, null, tint = if (holding) Color.White else c.danger, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    loading -> "重設中…"
                    holding -> holdingText
                    else -> text
                },
                style = B570Theme.type.titleS,
                color = if (holding || loading) Color.White else c.danger,
            )
        }
    }
}

/** 危險警示圖示（脈動光環）。 */
@Composable
private fun DangerBeacon() {
    val c = B570Theme.colors
    val t = rememberInfiniteTransition(label = "beacon")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(
        Modifier.size(72.dp).drawBehind {
            val r = size.minDimension / 2f
            drawCircle(c.danger.copy(alpha = (1f - p.value) * 0.45f), radius = r * (0.7f + 0.3f * p.value), style = Stroke(1.5.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(52.dp).glass(CircleShape, fill = c.danger.copy(alpha = 0.14f), glow = c.danger, glowElevation = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.SettingsBackupRestore, null, tint = c.danger, modifier = Modifier.size(26.dp))
        }
    }
}

// ------------------------------------------------------------------ 共用

@Composable
internal fun DialogHeader(icon: ImageVector, title: String, subtitle: String?, tint: Color, centered: Boolean = false) {
    val c = B570Theme.colors
    if (centered) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp).glass(CircleShape, fill = tint.copy(alpha = 0.12f), glow = tint, glowElevation = 10.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = B570Theme.type.titleM, color = c.textPrimary, textAlign = TextAlign.Center)
            if (subtitle != null) Text(subtitle, style = B570Theme.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint = tint, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = B570Theme.type.titleM, color = c.textPrimary)
                if (subtitle != null) Text(subtitle, style = B570Theme.type.caption, color = c.textSecondary)
            }
        }
    }
}

/** 後果說明列（圖示 + 文字）。 */
@Composable
internal fun ConsequenceRow(icon: ImageVector, text: String, tint: Color = B570Theme.colors.accent) {
    val c = B570Theme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = B570Theme.type.bodySmall, color = c.textPrimary)
    }
}
