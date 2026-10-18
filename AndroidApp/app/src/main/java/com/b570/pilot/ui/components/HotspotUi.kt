package com.b570.pilot.ui.components

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.b570.pilot.data.HotspotSwitchPhase
import com.b570.pilot.data.HotspotSwitchStatus
import com.b570.pilot.link.LinkPermissions
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 打開手機的「個人熱點 / 網路共用」設定頁：先試 `android.settings.TETHER_SETTINGS`，
 * 不行再依序試各家的熱點頁，最後退回「無線與網路」（`Settings.ACTION_WIRELESS_SETTINGS`）。
 * @return 有打開某個設定頁
 */
fun openPhoneHotspotSettings(context: Context): Boolean {
    try {
        context.startActivity(Intent(ACTION_TETHER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    } catch (e: Exception) {
        // ActivityNotFoundException / SecurityException：改用連線層的候選清單（最後是無線與網路設定）
    }
    return LinkPermissions.openHotspotSettings(context)
}

/** 系統「網路共用與可攜式無線基地台」設定頁（AOSP 隱藏常數 Settings.ACTION_TETHER_SETTINGS 的值）。 */
private const val ACTION_TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"

/**
 * 手機個人熱點是否開著（輪詢 [probe]，在背景執行緒；畫面不在前景或 [active] = false 時不輪詢，保留最後的值）。
 * 例：`rememberPhoneHotspotOn(active = true) { vm.isPhoneHotspotLikelyOn() }`
 */
@Composable
fun rememberPhoneHotspotOn(active: Boolean = true, periodMs: Long = 1_500L, probe: () -> Boolean): State<Boolean> {
    val state = remember { mutableStateOf(false) }
    val currentProbe by rememberUpdatedState(probe)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val life by lifecycle.currentStateFlow.collectAsState()
    val run = active && life.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(run, periodMs) {
        if (!run) return@LaunchedEffect
        while (true) {
            state.value = withContext(Dispatchers.Default) { runCatching { currentProbe() }.getOrDefault(false) }
            delay(periodMs)
        }
    }
    return state
}

/**
 * 切換到熱點模式的進度卡（設定頁、連線頁共用）：
 * 標題（重新開機中 / 請開啟手機熱點 / 尋找中 / 沒有找到）+ 說明 + 三步進度點 + 倒數；
 * 進行中：「開啟手機熱點」（熱點沒開時是主按鈕）+「取消」；沒找到：「關閉」+「重試」（+ 熱點沒開時「開啟手機熱點」）。
 * @param phoneHotspotOn 手機熱點看起來開著（預設用狀態裡的值，呼叫端可傳即時輪詢的值）
 * @param onCancel null = 不顯示取消（例如連線頁：步驟條已有「取消」）
 */
@Composable
fun HotspotSwitchPanel(
    status: HotspotSwitchStatus,
    onOpenHotspot: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    phoneHotspotOn: Boolean = status.phoneHotspotOn,
) {
    val c = B570Theme.colors
    val failed = status.phase == HotspotSwitchPhase.FAILED
    val tint by animateColorAsState(if (failed) c.danger else c.accent2, label = "switchTint")
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(failed) {
        while (!failed) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.l), glow = tint.copy(alpha = 0.5f))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(B570Theme.spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).glass(CircleShape, fill = tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                if (failed) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = tint, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Rounded.WifiTethering, null, tint = tint, modifier = Modifier.size(20.dp))
                    CircularProgressIndicator(Modifier.size(40.dp), color = tint.copy(alpha = 0.8f), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (failed) "切換熱點模式：${status.phase.labelZh}" else "切換熱點模式中",
                    style = B570Theme.type.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(status.messageZh, style = B570Theme.type.bodySmall, color = c.textPrimary)
            }
            if (!failed) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${hotspotSwitchSecondsLeft(status.deadlineMs, now)} 秒",
                    style = B570Theme.type.readoutS, color = c.textSecondary, maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SwitchSteps(hotspotSwitchStepOf(status.phase), failed, phoneHotspotOn)
        AnimatedVisibility(!failed && phoneHotspotOn, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                "手機熱點已開啟，車子通常 15 秒內會連上",
                style = B570Theme.type.caption, color = c.ok,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (failed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("關閉", onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
                NeonButton("重試", onRetry, Modifier.weight(1.3f), icon = Icons.Rounded.Refresh)
            }
            if (!phoneHotspotOn) {
                Spacer(Modifier.height(8.dp))
                NeonButton(
                    "開啟手機熱點", onOpenHotspot, Modifier.fillMaxWidth(),
                    style = NeonButtonStyle.GHOST, icon = Icons.Rounded.WifiTethering, color = c.accent2, compact = true,
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton(
                    "開啟手機熱點", onOpenHotspot, Modifier.weight(1.4f),
                    style = if (phoneHotspotOn) NeonButtonStyle.SECONDARY else NeonButtonStyle.PRIMARY,
                    icon = Icons.Rounded.WifiTethering, color = c.accent2,
                )
                if (onCancel != null) {
                    NeonButton("取消", onCancel, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
                }
            }
        }
    }
}

/**
 * 記住的車的小標籤：「熱點」/「直連」（車子設定的連線方式）+「無密碼」（從熱點上找到、沒掃過 QR：只能用熱點連）。
 * @param showDirect false = 直連的車不加標籤（清單只標特別的）
 */
@Composable
fun CarModeTags(hotspotMode: Boolean, hasPassword: Boolean, modifier: Modifier = Modifier, showDirect: Boolean = true) {
    val c = B570Theme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (hotspotMode) MiniTag("熱點", c.accent2) else if (showDirect) MiniTag("直連", c.accent)
        if (!hasPassword) MiniTag("無密碼", c.warn)
    }
}

/** 小字標籤（彩色淡底、圓角）。 */
@Composable
fun MiniTag(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = B570Theme.type.caption,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 三步進度點：車子重開機 → 開啟熱點 → 尋找車子。 */
@Composable
private fun SwitchSteps(step: Int, failed: Boolean, phoneHotspotOn: Boolean) {
    val c = B570Theme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HOTSPOT_SWITCH_STEPS.forEachIndexed { i, title ->
            // 「開啟熱點」：熱點已開就算完成
            val done = i < step || (i == 1 && phoneHotspotOn && step >= 1)
            val active = i == step && !done
            val col = when {
                failed && i == step -> c.danger
                done -> c.ok
                active -> c.accent2
                else -> c.textMuted
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(col.copy(alpha = if (done || active || (failed && i == step)) 0.9f else 0.25f)),
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (done) {
                        Icon(Icons.Rounded.Check, null, tint = col, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                    }
                    Text(
                        title, style = B570Theme.type.caption, color = if (done || active) c.textPrimary else c.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
