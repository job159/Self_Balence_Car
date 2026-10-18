package com.b570.pilot.ui.screens.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.HOTSPOT_OUTDOOR_HOWTO_ZH
import com.b570.pilot.data.Severity
import com.b570.pilot.link.HotspotConfig
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.HotspotSwitchPanel
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.openPhoneHotspotSettings
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.components.rememberPhoneHotspotOn
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay

private enum class HotspotStep { FORM, CONFIRM, PROGRESS }

/** STA_SET 的 ESP_ACK 失敗 → 給使用者看的句子。 */
internal fun hotspotFailureZh(st: AckStatus): String = when (st) {
    AckStatus.TIMEOUT -> "車子沒有回應，請靠近一點再試一次"
    AckStatus.OFFLINE -> "目前沒有連線，請先連上車子"
    AckStatus.BUSY -> "另一支手機正在控制這台車，無法切換"
    AckStatus.BAD_ARG -> "車子不接受這組熱點名稱或密碼，請檢查後再試"
    AckStatus.FAIL -> "車子寫入設定失敗，請再試一次"
    AckStatus.UNKNOWN_TYPE -> "車子的 Wi-Fi 模組韌體太舊，不支援熱點模式，請先更新 ESP-01 韌體"
    else -> "切換失敗：${st.labelZh}"
}

/** 「查看手機熱點名稱 / 密碼」打開系統設定頁前的提醒（手機熱點還沒開時）：先別打開熱點，按「套用」後再開。 */
internal const val HOTSPOT_LOOKUP_TOAST_ZH = "只看名稱和密碼就好，按「套用」後再打開熱點"

/** 熱點名稱的 UTF-8 位元組數（ESP 上限 32 bytes，中文一個字 3 bytes）。 */
internal fun hotspotSsidBytes(ssid: String): Int = ssid.toByteArray(Charsets.UTF_8).size

/**
 * 改用熱點模式 / 修改熱點：表單（熱點名稱 / 密碼，即時檢查 [HotspotConfig]；「查看手機熱點名稱 / 密碼」打開系統熱點設定頁）
 * → 確認（會發生什麼）→ 進度
 * （車子重新開機 → 請開啟手機熱點「…」→ 在熱點上尋找車子 → 已連上）。
 * 指令在 ViewModel 範圍執行。車子重開機、在熱點上找車時連線狀態是 Discovering，shell 留在主畫面、這個對話框繼續顯示進度；
 * 沒找到（Failed）時 shell 切到連線頁（對話框跟著消失），由連線頁 / 設定頁的切換卡顯示。
 * @param alreadyHotspot 車子已經是熱點模式（標題改「修改手機熱點」）
 * @param canBackground 進度中可以「在背景繼續」/ 返回 / 點外面關掉（設定頁：連線方式卡片會繼續顯示進度）；
 *   false = 開啟的地方沒有別的進度顯示（shell 的「改用熱點模式」邀請，在駕駛分頁上）→ 進度中只能「取消」或等完成 / 失敗
 */
@Composable
internal fun HotspotEditDialog(
    vm: AppViewModel,
    initialSsid: String,
    initialPassword: String,
    alreadyHotspot: Boolean,
    demo: Boolean,
    motorsOn: Boolean,
    onOpenHotspot: () -> Unit,
    onDismiss: () -> Unit,
    canBackground: Boolean = true,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val dismiss by rememberUpdatedState(onDismiss)
    val switchNow by vm.hotspotSwitch.collectAsStateWithLifecycle()

    var ssid by remember { mutableStateOf(initialSsid) }
    var password by remember { mutableStateOf(initialPassword) }
    var step by remember { mutableStateOf(HotspotStep.FORM) }
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val ssidErr = HotspotConfig.validateSsid(ssid)
    val pwErr = HotspotConfig.validatePassword(password)
    val valid = ssidErr == null && pwErr == null
    val context = LocalContext.current
    // 表單的「查看手機熱點名稱 / 密碼」：打開系統熱點設定頁照著填。熱點還沒開時先提醒「先別打開」：
    // 不能同時開 Wi-Fi 與熱點的手機，這時打開熱點會關掉 Wi-Fi、中斷直連（還沒按「套用」）。
    // 用系統 Toast（蓋在設定頁上面也看得到；App 內的提示這時看不到）
    val lookupHotspot: () -> Unit = {
        if (!vm.isPhoneHotspotLikelyOn()) Toast.makeText(context, HOTSPOT_LOOKUP_TOAST_ZH, Toast.LENGTH_LONG).show()
        if (!openPhoneHotspotSettings(context)) vm.notice("請到手機設定 › 個人熱點查看名稱與密碼", Severity.WARNING)
    }

    fun apply() {
        if (applying) return
        applying = true
        error = null
        val s = ssid
        val p = password
        vm.launch {
            val st = setHotspot(true, s, p, notify = false)
            applying = false
            if (st.isOk) {
                haptics.confirm()
                step = HotspotStep.PROGRESS
            } else {
                haptics.reject()
                error = hotspotFailureZh(st)
            }
        }
    }

    val twoPane = rememberDialogTwoPane()
    // 進度中不能關（canBackground = false）：返回 / 點外面都不關，只剩「取消」或等完成 / 失敗
    val progressLocked = !canBackground && step == HotspotStep.PROGRESS && switchNow?.isActive == true
    SettingsDialog(
        onDismissRequest = onDismiss,
        glow = c.accent2,
        dismissible = !applying && !progressLocked,
        wide = twoPane,
    ) {
        AnimatedContent(
            targetState = step,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = {
                val fwd = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)) { w -> if (fwd) w / 3 else -w / 3 } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(200)) { w -> if (fwd) -w / 4 else w / 4 } + fadeOut(tween(150)))
                    .using(SizeTransform(clip = false))
            },
            label = "hotspotStep",
        ) { s ->
            when (s) {
                HotspotStep.FORM -> HotspotForm(
                    title = if (alreadyHotspot) "修改手機熱點" else "改用熱點模式",
                    ssid = ssid,
                    onSsid = { ssid = it; error = null },
                    ssidErr = ssidErr,
                    password = password,
                    onPassword = { password = it; error = null },
                    pwErr = pwErr,
                    demo = demo,
                    valid = valid,
                    twoPane = twoPane,
                    onLookup = lookupHotspot,
                    onCancel = onDismiss,
                    onNext = {
                        if (valid) {
                            haptics.click()
                            step = HotspotStep.CONFIRM
                        } else {
                            haptics.reject()
                        }
                    },
                )
                HotspotStep.CONFIRM -> HotspotConfirm(
                    ssid = ssid,
                    password = password,
                    demo = demo,
                    motorsOn = motorsOn,
                    applying = applying,
                    error = error,
                    twoPane = twoPane,
                    onBack = { if (!applying) { error = null; step = HotspotStep.FORM } },
                    onApply = { apply() },
                )
                HotspotStep.PROGRESS -> HotspotProgress(vm, onOpenHotspot, onClose = { dismiss() }, canBackground = canBackground)
            }
        }
    }
}

@Composable
private fun HotspotForm(
    title: String,
    ssid: String,
    onSsid: (String) -> Unit,
    ssidErr: String?,
    password: String,
    onPassword: (String) -> Unit,
    pwErr: String?,
    demo: Boolean,
    valid: Boolean,
    twoPane: Boolean,
    onLookup: () -> Unit,
    onCancel: () -> Unit,
    onNext: () -> Unit,
) {
    val c = B570Theme.colors
    val focus = LocalFocusManager.current
    var showPw by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent2,
        unfocusedBorderColor = c.glassBorder,
        cursorColor = c.accent2,
        focusedLabelColor = c.accent2,
        errorBorderColor = c.danger,
        errorLabelColor = c.danger,
        errorSupportingTextColor = c.danger,
        errorCursorColor = c.danger,
    )
    val ssidField: @Composable () -> Unit = {
        OutlinedTextField(
            value = ssid,
            onValueChange = { onSsid(it.replace("\n", "").take(HotspotConfig.SSID_MAX_BYTES + 8)) },
            label = { Text("熱點名稱") },
            singleLine = true,
            isError = ssid.isNotEmpty() && ssidErr != null,
            textStyle = B570Theme.type.body.copy(color = c.textPrimary),
            supportingText = {
                CounterLine(
                    if (ssid.isNotEmpty() && ssidErr != null) ssidErr else "和手機熱點名稱一樣（含大小寫）",
                    hotspotSsidBytes(ssid),
                    HotspotConfig.SSID_MAX_BYTES,
                )
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
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
            onValueChange = { onPassword(it.replace("\n", "").take(HotspotConfig.PW_MAX + 8)) },
            label = { Text("熱點密碼") },
            singleLine = true,
            isError = pwErr != null,
            textStyle = B570Theme.type.body.copy(color = c.textPrimary, fontFamily = FontFamily.Monospace),
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPw = !showPw }) {
                    Icon(
                        if (showPw) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPw) "隱藏密碼" else "顯示密碼",
                        tint = if (showPw) c.accent2 else c.textSecondary,
                    )
                }
            },
            supportingText = { CounterLine(pwErr ?: "8–63 碼；開放熱點請留空", password.length, HotspotConfig.PW_MAX) },
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
    val guide: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(B570Theme.radii.s), fill = c.accent2.copy(alpha = 0.08f), borderWidth = 0.75.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // 「手機照用行動網路」已在標題下方說明，這裡只放容易設錯的兩件事
            GuideLine(Icons.Rounded.WifiTethering, "熱點請設 2.4\u00A0GHz（iPhone：最大化相容性）、安全性 WPA2")
        }
    }
    // 不記得熱點名稱 / 密碼：打開手機的熱點設定頁看一下（回來表單內容還在）
    val lookup: @Composable () -> Unit = {
        NeonButton(
            "查看手機熱點名稱 / 密碼", onLookup, Modifier.fillMaxWidth(),
            style = NeonButtonStyle.GHOST, icon = Icons.Rounded.Visibility, compact = true, color = c.accent2,
        )
    }
    val demoHint: @Composable () -> Unit = {
        HintRow(Icons.Rounded.ErrorOutline, "展示模式：只會模擬，不會修改真車", color = c.accent2)
    }
    val buttons: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("取消", onCancel, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
            NeonButton("下一步", onNext, Modifier.weight(1f), enabled = valid, color = c.accent2)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        DialogHeader(Icons.Rounded.WifiTethering, title, "車子會加入你的手機熱點，手機照用行動網路", c.accent2)
        if (twoPane) {
            // 手機橫放：左 = 名稱 / 密碼，右 = 說明 + 按鈕（鍵盤彈出時也容易找到按鈕）
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    ssidField()
                    Spacer(Modifier.height(4.dp))
                    passwordField()
                }
                // 展示模式提示留到確認頁（那裡也有）：640 × 320、字級 1.3 時按鈕才不會被擠到畫面外
                Column(Modifier.weight(1f)) {
                    guide()
                    Spacer(Modifier.height(4.dp))
                    lookup()
                    Spacer(Modifier.height(6.dp))
                    buttons()
                }
            }
        } else {
            Spacer(Modifier.height(18.dp))
            ssidField()
            Spacer(Modifier.height(6.dp))
            passwordField()
            Spacer(Modifier.height(10.dp))
            guide()
            Spacer(Modifier.height(4.dp))
            lookup()
            if (demo) {
                Spacer(Modifier.height(6.dp))
                demoHint()
            }
            Spacer(Modifier.height(14.dp))
            buttons()
        }
    }
}

@Composable
private fun HotspotConfirm(
    ssid: String,
    password: String,
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
            InfoRow("熱點名稱", ssid)
            InfoRow("熱點密碼", password.ifEmpty { "（開放熱點）" }, mono = password.isNotEmpty())
        }
    }
    val consequences: @Composable () -> Unit = {
        ConsequenceRow(Icons.Rounded.RestartAlt, "車子會重新開機，加入手機熱點「$ssid」", tint = c.accent2)
        ConsequenceRow(Icons.Rounded.WifiTethering, "請在手機開啟這個熱點（2.4\u00A0GHz、WPA2），App 會自動在熱點上找到車子", tint = c.accent2)
        ConsequenceRow(Icons.Rounded.SignalCellularAlt, "手機照常使用行動網路，不必再連車子的 Wi-Fi", tint = c.accent2)
        ConsequenceRow(Icons.Rounded.QrCode2, "找不到熱點時，車子會自動改回直連（OLED 顯示 QR Code）", tint = c.accent2)
        if (motorsOn && !demo) ConsequenceRow(Icons.Rounded.PauseCircle, "馬達運轉中：重新開機時車子會自動煞停", tint = c.warn)
        if (demo) ConsequenceRow(Icons.Rounded.ErrorOutline, "展示模式只會模擬，不會修改真車", tint = c.accent2)

        AnimatedVisibility(error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            HintRow(Icons.Rounded.ErrorOutline, error ?: "", Modifier.padding(top = 10.dp), color = c.danger)
        }
    }
    val buttons: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("返回", onBack, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY, enabled = !applying)
            NeonButton(if (error != null) "重試" else "套用", onApply, Modifier.weight(1f), loading = applying, color = c.accent2)
        }
    }

    if (twoPane) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // 按鈕放左欄摘要下方（右欄的說明在矮螢幕、大字級時很長，放右欄要捲動才按得到）
            Column(Modifier.weight(1f)) {
                DialogHeader(Icons.Rounded.RestartAlt, "確定要套用？", "請確認手機熱點的名稱與密碼", c.accent2)
                Spacer(Modifier.height(12.dp))
                summary()
                Spacer(Modifier.height(14.dp))
                buttons()
            }
            Column(Modifier.weight(1.1f)) {
                consequences()
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DialogHeader(Icons.Rounded.RestartAlt, "確定要套用？", null, c.accent2, centered = true)
            Spacer(Modifier.height(16.dp))
            summary()
            Spacer(Modifier.height(14.dp))
            consequences()
            Spacer(Modifier.height(20.dp))
            buttons()
        }
    }
}

/** 切換完成後，進度對話框顯示「完成！以後帶出去…」多久自動關閉。 */
private const val HOTSPOT_DONE_CLOSE_MS = 6_000L

/**
 * 套用後：跟著 vm.hotspotSwitch 顯示進度（重新開機 → 請開啟手機熱點 → 尋找 → 連上）。
 * 連上 → 打勾 +「以後帶出去怎麼連」，6 秒後自動關閉（或按「知道了」）；被取消（切換結束但沒連上）→ 關閉。
 * 「在背景繼續」只關對話框，進度卡留在設定頁 / 連線頁；[canBackground] = false 時不顯示（開啟的地方沒有進度卡）。
 */
@Composable
private fun HotspotProgress(vm: AppViewModel, onOpenHotspot: () -> Unit, onClose: () -> Unit, canBackground: Boolean) {
    val c = B570Theme.colors
    val switch by vm.hotspotSwitch.collectAsStateWithLifecycle()
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val phoneHotspotOn by rememberPhoneHotspotOn { vm.isPhoneHotspotLikelyOn() }
    val close by rememberUpdatedState(onClose)
    // 這個畫面會說明每一步：切換過程的提示不再另外跳出
    DisposableEffect(vm) {
        vm.setSwitchProgressOnScreen(true)
        onDispose { vm.setSwitchProgressOnScreen(false) }
    }
    var sawSwitch by remember { mutableStateOf(false) }
    LaunchedEffect(switch != null) { if (switch != null) sawSwitch = true }
    val done = sawSwitch && switch == null && state.isConnected
    val cancelled = sawSwitch && switch == null && !state.isConnected && !state.isConnecting
    LaunchedEffect(done, cancelled) {
        if (done) {
            // 留久一點讓使用者看完「以後帶出去怎麼連」（也可以按「知道了」）
            delay(HOTSPOT_DONE_CLOSE_MS)
            close()
        } else if (cancelled) {
            close()
        }
    }
    // 車子回 ACK 到切換流程開始之間（不到 1 秒）可能還沒有狀態
    val waitingStart = !sawSwitch && switch == null
    val s = switch
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            done -> {
                Spacer(Modifier.height(8.dp))
                SuccessBurst(c.ok)
                Spacer(Modifier.height(14.dp))
                Text("完成！已透過手機熱點連上車子", style = B570Theme.type.titleM, color = c.textPrimary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(HOTSPOT_OUTDOOR_HOWTO_ZH, style = B570Theme.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                NeonButton("知道了", onClose, Modifier.fillMaxWidth(), compact = true, color = c.ok)
            }
            s != null -> {
                HotspotSwitchPanel(
                    status = s,
                    onOpenHotspot = onOpenHotspot,
                    onRetry = { vm.retry() },
                    onDismiss = { vm.dismissHotspotSwitch(); close() },
                    onCancel = { vm.cancelHotspotSwitch(); close() },
                    phoneHotspotOn = phoneHotspotOn || s.phoneHotspotOn,
                )
                if (s.isActive && canBackground) {
                    Spacer(Modifier.height(10.dp))
                    NeonButton("在背景繼續", onClose, Modifier.fillMaxWidth(), style = NeonButtonStyle.GHOST, compact = true, color = c.textSecondary)
                }
            }
            else -> {
                // 套用後、切換流程開始前（不到 1 秒），或按了「重試」正在重新找車
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(Modifier.size(36.dp), color = c.accent2, strokeWidth = 3.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (waitingStart) "已套用，車子重新開機中…" else state.labelZh,
                    style = B570Theme.type.titleS, color = c.textPrimary, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
