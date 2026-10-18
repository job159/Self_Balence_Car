package com.b570.pilot.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.SavedCar
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.Route
import com.b570.pilot.ui.components.CarModeTags
import com.b570.pilot.ui.components.ConfirmDialog
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.StatusPill
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme

/** 暱稱最多字數（狀態條一行放得下）。 */
private const val NICKNAME_MAX = 16

// ------------------------------------------------------------------ 展示模式

/** 展示模式會模擬的內容（說明用）。 */
private val DEMO_FEATURES = listOf(
    Icons.Rounded.Timeline to "50 Hz 遙測：平衡晃動、加速前傾、電池慢慢下降",
    Icons.Rounded.TouchApp to "搖桿、快捷動作、編排播放都會有反應",
    Icons.Rounded.FlashOn to "指令比照韌體回覆，例如運轉中不能存檔",
    Icons.Rounded.PanTool to "閒置一陣子會模擬「被拿起」自動停機",
)

/** 「展示模式」：開機自動進入的開關 + 立即試玩 / 離開 + 模擬內容說明。 */
@Composable
internal fun DemoSection(vm: AppViewModel, nav: AppNavigator, wide: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val settings by vm.settings.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val carName by vm.currentCarName.collectAsStateWithLifecycle()
    var askSwitch by remember { mutableStateOf(false) }

    Column(modifier) {
        PaneHeader(SettingsCategory.DEMO)
        GlassCard(Modifier.fillMaxWidth(), glow = if (demo) c.accent2.copy(alpha = 0.35f) else null, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            AdaptiveColumns(
                wide = wide,
                first = {
                    SettingSwitchRow(
                        icon = Icons.Rounded.Science,
                        title = "開啟 App 時自動進入",
                        subtitle = "適合展覽、教學或練習操作",
                        checked = settings.demoMode,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(demoMode = on) } },
                    )
                    SettingDivider()
                    AnimatedContent(
                        targetState = demo,
                        transitionSpec = {
                            (fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.75f, stiffness = 400f), initialScale = 0.94f))
                                .togetherWith(fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.96f))
                        },
                        label = "demoRow",
                    ) { inDemo ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (inDemo) {
                                StatusPill("展示中", c.accent2, pulsing = true)
                                Spacer(Modifier.width(10.dp))
                                Text("所有資料都是模擬的", style = B570Theme.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                NeonButton(
                                    "離開",
                                    {
                                        vm.disconnect()
                                        nav.resetTo(Route.Connect)
                                    },
                                    style = NeonButtonStyle.SECONDARY,
                                    compact = true,
                                    color = c.accent2,
                                )
                            } else {
                                Column(Modifier.weight(1f)) {
                                    Text("想先試試看？", style = B570Theme.type.titleS, color = c.textPrimary)
                                    Text("模擬車子會回應所有操作", style = B570Theme.type.caption, color = c.textSecondary)
                                }
                                Spacer(Modifier.width(8.dp))
                                NeonButton(
                                    "立即試玩",
                                    { if (state.isSession) askSwitch = true else vm.connectDemo() },
                                    icon = Icons.Rounded.PlayArrow,
                                    compact = true,
                                )
                            }
                        }
                    }
                },
                second = {
                    if (!wide) SettingDivider()
                    Spacer(Modifier.height(10.dp))
                    FieldLabel("會模擬什麼")
                    Spacer(Modifier.height(4.dp))
                    DEMO_FEATURES.forEach { (icon, text) -> FeatureRow(icon, text) }
                    Spacer(Modifier.height(8.dp))
                },
            )
        }
    }

    if (askSwitch) {
        ConfirmDialog(
            title = "切換到展示模式？",
            message = "會先中斷與「$carName」的連線，車子會停在原地保持平衡。",
            confirmText = "切換",
            icon = Icons.Rounded.Science,
            onConfirm = {
                askSwitch = false
                vm.connectDemo()
            },
            onDismiss = { askSwitch = false },
        )
    }
}

/** 說明列：小圖示 + 文字。 */
@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    val c = B570Theme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = c.accent2, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = B570Theme.type.bodySmall, color = c.textSecondary)
    }
}

// ------------------------------------------------------------------ 記住的車子

/**
 * 「記住的車子」：自動連線 / 斷線自動重連開關 + 記住的車清單（重新命名、忘記）。
 * 寬版（平板）：左 = 車子清單，右 = 連線偏好；窄版：連線偏好在上、清單在下。
 * 用一般 Column（不是 Lazy）：對話框不會因為捲出畫面而被回收；清單增減用 animateContentSize 平滑過渡。
 */
@Composable
internal fun CarsSection(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val savedCars by vm.savedCars.collectAsStateWithLifecycle()
    val currentCar = vm.currentCar.collectAsStateWithLifecycle()
    val demo = vm.isDemo.collectAsStateWithLifecycle()
    val state = vm.connectionState.collectAsStateWithLifecycle()
    // 「使用中」的車：只有它改變時才重組清單（Connected ⇄ LinkLost 不影響）
    val activeSsid by remember {
        derivedStateOf { if (!demo.value && state.value.isSession) currentCar.value?.ssid else null }
    }
    val nowMs = remember(savedCars) { System.currentTimeMillis() }

    var renameTarget by remember { mutableStateOf<SavedCar?>(null) }
    var forgetTarget by remember { mutableStateOf<SavedCar?>(null) }

    Column(modifier) {
        PaneHeader(
            SettingsCategory.CARS,
            subtitle = if (savedCars.isEmpty()) "連線過的車會記在這裡" else "${savedCars.size} 台 · 最近連線的在最上面",
        )
        val list: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (savedCars.isEmpty()) {
                    SavedCarsEmpty()
                } else {
                    savedCars.forEach { car ->
                        key(car.ssid) {
                            SavedCarRow(
                                car = car,
                                active = car.ssid == activeSsid,
                                nowMs = nowMs,
                                onRename = { renameTarget = car },
                                onForget = { forgetTarget = car },
                            )
                        }
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1.25f)) { list() }
                AutoConnectCard(vm, Modifier.weight(1f))
            }
        } else {
            AutoConnectCard(vm, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            list()
        }
    }

    renameTarget?.let { car ->
        RenameCarDialog(
            car = car,
            onConfirm = { name ->
                vm.renameCar(car.ssid, name)
                renameTarget = null
                haptics.confirm()
            },
            onDismiss = { renameTarget = null },
        )
    }
    forgetTarget?.let { car ->
        ConfirmDialog(
            title = "忘記「${car.displayName}」？",
            message = "會刪除這台車的密碼，下次要重新掃描車上的 QR Code 才能連線。",
            confirmText = "忘記",
            danger = true,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = {
                vm.forgetCar(car.ssid)
                forgetTarget = null
                vm.notice("已忘記「${car.displayName}」")
            },
            onDismiss = { forgetTarget = null },
        )
    }
}

/** 連線偏好：開啟 App 自動連線、斷線自動重連（AppSettings.autoConnect / autoReconnect）。 */
@Composable
private fun AutoConnectCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    GlassCard(modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        SettingSwitchRow(
            icon = Icons.Rounded.FlashOn,
            title = "開啟 App 自動連線",
            subtitle = if (settings.demoMode) "已開啟展示模式，開啟 App 時會先進入展示" else "打開 App 就自動連上最近使用的車",
            checked = settings.autoConnect,
            onCheckedChange = { on -> vm.updateSettings { it.copy(autoConnect = on) } },
        )
        SettingDivider()
        SettingSwitchRow(
            icon = Icons.Rounded.Autorenew,
            title = "斷線自動重連",
            subtitle = "意外斷線（關機、走太遠）時，停在原畫面自動重試約 45\u00A0秒",
            checked = settings.autoReconnect,
            onCheckedChange = { on -> vm.updateSettings { it.copy(autoReconnect = on) } },
        )
    }
}

/** 單台記住的車：頭像、暱稱 / SSID + 連線方式標籤（熱點 / 直連、無密碼）、上次連線，重新命名與忘記。 */
@Composable
internal fun SavedCarRow(
    car: SavedCar,
    active: Boolean,
    nowMs: Long,
    onRename: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    GlassCard(
        modifier.fillMaxWidth(),
        glow = if (active) c.accent.copy(alpha = 0.40f) else null,
        contentPadding = PaddingValues(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) Brush.linearGradient(listOf(c.accent, c.accent2))
                        else Brush.linearGradient(listOf(c.glassHigh, c.glassHigh)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SmartToy, null, tint = if (active) c.onAccent else c.textSecondary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        car.displayName,
                        style = B570Theme.type.titleS,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    CarModeTags(hotspotMode = car.hotspotMode, hasPassword = car.canDirect)
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill("使用中", c.ok)
                    }
                }
                val last = formatRelative(car.lastConnected, nowMs)
                Text(
                    if (car.nickname.isNotBlank()) "${car.ssid} · $last" else "上次連線：$last",
                    style = B570Theme.type.caption,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Rounded.Edit, "重新命名 ${car.displayName}", onRename, size = 36.dp)
            Spacer(Modifier.width(8.dp))
            GlassIconButton(
                Icons.Rounded.DeleteOutline,
                if (active) "使用中的車不能忘記" else "忘記 ${car.displayName}",
                onForget,
                enabled = !active,
                tint = c.danger,
                size = 36.dp,
            )
        }
    }
}

/** 沒有記住任何車時。 */
@Composable
internal fun SavedCarsEmpty(modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.SmartToy, tint = c.textSecondary, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("還沒有記住的車", style = B570Theme.type.titleS, color = c.textPrimary)
                Text("掃描車上的 QR Code 連線後，會自動記在這裡", style = B570Theme.type.caption, color = c.textSecondary)
            }
        }
    }
}

/** 重新命名：留空 = 顯示 SSID。手機橫放時輸入框與按鈕排成一列（鍵盤彈出時仍看得到按鈕）。 */
@Composable
internal fun RenameCarDialog(car: SavedCar, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val c = B570Theme.colors
    val twoPane = rememberDialogTwoPane()
    val start = car.nickname
    var text by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = { onConfirm(text.text.trim()) }

    SettingsDialog(onDismissRequest = onDismiss, glow = c.accent, wide = twoPane, maxWidth = if (twoPane) 560.dp else 440.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.DriveFileRenameOutline, size = if (twoPane) 40.dp else 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("幫車子取個名字", style = B570Theme.type.titleM, color = c.textPrimary)
                Text(car.ssid, style = B570Theme.type.mono, color = c.textSecondary)
            }
        }
        Spacer(Modifier.height(if (twoPane) 12.dp else 18.dp))
        val field: @Composable (Modifier) -> Unit = { m ->
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.text.length <= NICKNAME_MAX) text = it },
                singleLine = true,
                label = { Text("暱稱") },
                placeholder = { Text(car.ssid, color = c.textMuted) },
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Text("留空會顯示 Wi-Fi 名稱", modifier = Modifier.weight(1f))
                        Text("${text.text.length}/$NICKNAME_MAX", color = c.textMuted)
                    }
                },
                textStyle = B570Theme.type.body.copy(color = c.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent,
                    unfocusedBorderColor = c.glassBorder,
                    cursorColor = c.accent,
                    focusedLabelColor = c.accent,
                ),
                modifier = m.focusRequester(focus),
            )
        }
        if (twoPane) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                field(Modifier.weight(1f))
                Column(Modifier.width(132.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("儲存", { submit() }, Modifier.fillMaxWidth(), compact = true)
                    NeonButton("取消", onDismiss, Modifier.fillMaxWidth(), style = NeonButtonStyle.SECONDARY, compact = true)
                }
            }
        } else {
            field(Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeonButton("取消", onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
                NeonButton("儲存", { submit() }, Modifier.weight(1f))
            }
        }
    }
}

// ------------------------------------------------------------------ 中斷連線

/**
 * 危險動作：中斷連線（展示模式為「離開展示模式」），確認後回到連線頁。
 * @param compact 放在左側分類面板底部（窄）：小按鈕 + 一行說明
 */
@Composable
internal fun DisconnectSection(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = B570Theme.colors
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val carName by vm.currentCarName.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        NeonButton(
            if (demo) "離開展示模式" else "中斷連線",
            { ask = true },
            Modifier.fillMaxWidth(),
            style = NeonButtonStyle.DANGER,
            icon = Icons.Rounded.LinkOff,
            compact = compact,
        )
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
        Text(
            when {
                demo -> "回到連線頁，可以改連真的車子"
                compact -> "車子會停在原地保持平衡"
                else -> "中斷後車子會停在原地保持平衡，隨時可以重新連線"
            },
            style = B570Theme.type.caption,
            color = c.textMuted,
            textAlign = TextAlign.Center,
        )
    }

    if (ask) {
        ConfirmDialog(
            title = if (demo) "離開展示模式？" else "中斷連線？",
            message = if (demo) "會結束模擬，回到連線頁。"
            else "會中斷與「${carName.ifEmpty { "車子" }}」的連線，車子會停在原地保持平衡。",
            confirmText = if (demo) "離開" else "中斷連線",
            danger = true,
            icon = Icons.Rounded.LinkOff,
            onConfirm = {
                ask = false
                vm.disconnect()
                nav.resetTo(Route.Connect)
            },
            onDismiss = { ask = false },
        )
    }
}
