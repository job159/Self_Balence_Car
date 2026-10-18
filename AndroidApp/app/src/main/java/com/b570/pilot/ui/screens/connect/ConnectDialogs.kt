package com.b570.pilot.ui.screens.connect

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.link.LinkPermissions
import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.ui.components.ConfirmDialog
import com.b570.pilot.ui.components.GlassDialog
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.isShortScreen
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass

// ====================================================================== 權限

/**
 * 權限說明 / 永久拒絕 / 第一次配對開 Wi-Fi / 熱點模式的車沒找到熱點的對話框。[connectMissing] = 配對時是否也要一起要連線權限；
 * [phoneHotspotOn] = 手機熱點看起來開著（第一次配對的說明多一句「開 Wi-Fi 可能關掉熱點」）；
 * [firstPairPromise] = 連上後真的會邀請改用熱點模式（[firstPairPromiseOf]）。
 */
@Composable
internal fun PermissionDialog(
    kind: PermDialog,
    connectMissing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    phoneHotspotOn: Boolean = false,
    firstPairPromise: Boolean = true,
) {
    val nearby = Build.VERSION.SDK_INT >= 33
    when (kind) {
        PermDialog.PAIR_RATIONALE -> ConfirmDialog(
            title = "開始配對",
            message = buildString {
                append("需要相機來掃描車上 OLED 螢幕的 QR Code，影像只在手機上辨識，不會儲存。")
                if (connectMissing) {
                    append("\n\n")
                    append(LinkPermissions.rationaleZh)
                }
            },
            confirmText = "繼續",
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            icon = Icons.Rounded.PhotoCamera,
        )
        PermDialog.CONNECT_RATIONALE -> ConfirmDialog(
            title = "允許連線車子",
            message = LinkPermissions.rationaleZh,
            confirmText = "繼續",
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            icon = Icons.Rounded.WifiFind,
        )
        PermDialog.CONNECT_DENIED -> ConfirmDialog(
            title = "權限已關閉",
            message = if (nearby) "請到系統設定開啟「附近裝置」權限，才能連上車子的 Wi-Fi。"
            else "請到系統設定開啟「位置」權限並選擇「精確」，才能連上車子的 Wi-Fi。",
            confirmText = "前往設定",
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            icon = Icons.Rounded.Settings,
        )
        PermDialog.CAMERA_DENIED -> ConfirmDialog(
            title = "相機權限已關閉",
            message = "請到系統設定開啟相機權限，或改用手動輸入。",
            confirmText = "前往設定",
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            icon = Icons.Rounded.Settings,
        )
        PermDialog.WIFI_FIRST_PAIR -> ConfirmDialog(
            title = wifiFirstPairTitleZh(firstPairPromise),
            message = wifiFirstPairMessageZh(phoneHotspotOn, firstPairPromise),
            confirmText = "開啟 Wi-Fi",
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            icon = Icons.Rounded.Wifi,
        )
        PermDialog.HOTSPOT_CAR -> ConfirmDialog(
            title = HOTSPOT_CAR_TITLE_ZH,
            message = hotspotCarMessageZh(phoneHotspotOn),
            confirmText = hotspotCarConfirmZh(phoneHotspotOn),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            icon = Icons.Rounded.WifiTethering,
        )
    }
}

// ====================================================================== 手動輸入

private const val SSID_PREFIX = "B570-"
private const val PW_FORBIDDEN = ";,:\\\""

/** 手動輸入 SSID / 密碼（依 PROTOCOL §3.4 即時驗證）。 */
@Composable
internal fun ManualEntryDialog(onConnect: (WifiCredentials) -> Unit, onDismiss: () -> Unit) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    var ssid by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(SSID_PREFIX, TextRange(SSID_PREFIX.length)))
    }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val ssidError = WifiCredentials.validateSsid(ssid.text)
    val pwError = WifiCredentials.validatePassword(password)
    // 不合法字元立刻提示；長度不足等到送出或打滿 8 碼後才提示，避免一開始就滿畫面紅字
    val pwIllegal = password.any { it.code !in 0x21..0x7E || it in PW_FORBIDDEN }
    val showSsidError = ssidError != null && (submitted || ssid.text.length > SSID_PREFIX.length || ssid.text.isEmpty())
    val showPwError = pwError != null && (submitted || pwIllegal || password.length >= WifiCredentials.PW_MIN)
    val valid = ssidError == null && pwError == null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        if (valid) {
            onConnect(WifiCredentials(ssid.text, password))
        } else {
            submitted = true
            haptics.reject()
        }
    }

    val short = isShortScreen()
    // 手機橫放（矮螢幕）時鍵盤會蓋住下半部：對話框變寬、兩個欄位並排，整體矮一半（GlassDialog 內容可捲動）
    DialogSurface(
        Icons.Rounded.Keyboard, "手動輸入", "輸入車上 OLED 螢幕顯示的名稱與密碼", onDismiss,
        maxWidth = if (short) 600.dp else 420.dp,
    ) {
        val ssidField: @Composable (Modifier) -> Unit = { m ->
            OutlinedTextField(
                value = ssid,
                onValueChange = { v -> ssid = v.copy(text = v.text.filterNot { it.isWhitespace() }) },
                label = { Text("車子名稱（SSID）", maxLines = 1) },
                placeholder = { Text("B570-A1B2") },
                singleLine = true,
                isError = showSsidError,
                supportingText = {
                    Text(if (showSsidError) ssidError.orEmpty() else "${ssid.text.length}/${WifiCredentials.SSID_MAX}")
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
                textStyle = B570Theme.type.body.copy(color = c.textPrimary),
                colors = fieldColors(),
                modifier = m.focusRequester(focus),
            )
        }
        val passwordField: @Composable (Modifier) -> Unit = { m ->
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密碼", maxLines = 1) },
                singleLine = true,
                isError = showPwError,
                supportingText = {
                    Text(if (showPwError) pwError.orEmpty() else "${password.length}/${WifiCredentials.PW_MAX}")
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (showPassword) "隱藏密碼" else "顯示密碼",
                            tint = c.textSecondary,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                textStyle = B570Theme.type.body.copy(color = c.textPrimary),
                colors = fieldColors(),
                modifier = m,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 440.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    ssidField(Modifier.weight(1f))
                    passwordField(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ssidField(Modifier.fillMaxWidth())
                    passwordField(Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(if (short) 12.dp else 18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("取消", onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
            NeonButton("連線", { submit() }, Modifier.weight(1f), enabled = valid || !submitted)
        }
    }
}

// ====================================================================== 重新命名

private const val NICK_MAX = 16

@Composable
internal fun RenameCarDialog(ssid: String, current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = B570Theme.colors
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(current, TextRange(0, current.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = { onSave(text.text.trim()) }
    DialogSurface(Icons.Rounded.DriveFileRenameOutline, "重新命名", ssid, onDismiss) {
        OutlinedTextField(
            value = text,
            onValueChange = { v -> if (v.text.length <= NICK_MAX) text = v },
            label = { Text("暱稱") },
            placeholder = { Text(ssid) },
            singleLine = true,
            supportingText = { Text("留空會顯示 Wi-Fi 名稱　${text.text.length}/$NICK_MAX") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            textStyle = B570Theme.type.body.copy(color = c.textPrimary),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(if (isShortScreen()) 12.dp else 18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("取消", onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
            NeonButton("儲存", { save() }, Modifier.weight(1f))
        }
    }
}

// ====================================================================== 共用

/**
 * 與 ConfirmDialog 一致的玻璃對話框外框（[GlassDialog]：沉浸式、內容超高可捲動、矮螢幕緊湊排版）。
 * 鍵盤彈出時（手機橫放只剩約 150 dp）內容可以捲動，取得焦點的欄位會自動捲進畫面。
 */
@Composable
private fun DialogSurface(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    maxWidth: Dp = 420.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = B570Theme.colors
    val short = isShortScreen()
    GlassDialog(onDismissRequest = onDismiss, maxWidth = maxWidth) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val badge = if (short) 36.dp else 44.dp
            Box(Modifier.size(badge).glass(CircleShape, fill = c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.accent, modifier = Modifier.size(badge / 2))
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, style = B570Theme.type.titleM, color = c.textPrimary)
                Text(subtitle, style = B570Theme.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Start)
            }
        }
        Spacer(Modifier.height(if (short) 12.dp else 18.dp))
        content()
    }
}

@Composable
private fun fieldColors(): TextFieldColors {
    val c = B570Theme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.glassBorder,
        cursorColor = c.accent,
        focusedLabelColor = c.accent,
        unfocusedLabelColor = c.textSecondary,
        focusedSupportingTextColor = c.textMuted,
        unfocusedSupportingTextColor = c.textMuted,
        errorBorderColor = c.danger,
        errorLabelColor = c.danger,
        errorSupportingTextColor = c.danger,
        errorCursorColor = c.danger,
        focusedPlaceholderColor = c.textMuted,
        unfocusedPlaceholderColor = c.textMuted,
    )
}
