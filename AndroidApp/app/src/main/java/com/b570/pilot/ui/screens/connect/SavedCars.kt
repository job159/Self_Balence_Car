package com.b570.pilot.ui.screens.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.SavedCar
import com.b570.pilot.ui.components.CarModeTags
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 記住的車子卡片：點一下連線；長按或 ⋮ 在卡片內展開「重新命名 / 忘記這台車」。
 * 用卡片內展開而不用彈出選單：彈出視窗會搶走視窗焦點，沉浸式下系統列會跑出來。
 * 名稱旁標「熱點」/「直連」（車子設定的連線方式）與「無密碼」（只能用熱點連）；
 * [hotspotHint] = 熱點模式的車、手機熱點沒開 → 卡片內「請開啟手機熱點」+「開啟熱點」。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun SavedCarCard(
    car: SavedCar,
    nowMs: Long,
    connecting: Boolean,
    recent: Boolean,
    onConnect: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
    badge: NearbyBadge = NearbyBadge.Unknown,
    hotspotHint: Boolean = false,
    onOpenHotspot: () -> Unit = {},
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    val showMenu = menu && !connecting
    val shape = RoundedCornerShape(B570Theme.radii.l)
    // 不在範圍內：變淡（仍可點，也許車子剛開機還沒被掃到）
    val dim by animateFloatAsState(if (badge == NearbyBadge.OutOfRange && !connecting && !showMenu) 0.55f else 1f, label = "carDim")
    val inRange = badge is NearbyBadge.InRange || badge == NearbyBadge.OnHotspot
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = dim }
            .glass(shape, glow = if (connecting) c.accent.copy(alpha = 0.6f) else if (inRange) c.ok.copy(alpha = 0.35f) else null)
            .clip(shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { haptics.click(); onConnect() },
                    onLongClick = { haptics.heavy(); menu = true },
                    onLongClickLabel = "更多選項",
                )
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotBadge(active = recent || connecting || inRange, color = if (inRange && !connecting) c.ok else c.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        car.displayName, style = B570Theme.type.titleS, color = c.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    CarModeTags(hotspotMode = car.hotspotMode, hasPassword = car.canDirect)
                }
                val time = relativeTimeZh(car.lastConnected, nowMs)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (badge != NearbyBadge.Unknown && !connecting) {
                        NearbyTag(badge)
                        Text(" · ", style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
                    }
                    Text(
                        if (car.nickname.isNotBlank()) "${car.ssid} · $time" else time,
                        style = B570Theme.type.caption, color = if (connecting) c.accent else c.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { haptics.tick(); menu = !menu }) {
                        Icon(
                            if (showMenu) Icons.Rounded.Close else Icons.Rounded.MoreVert,
                            contentDescription = if (showMenu) "收起選項" else "更多選項",
                            tint = if (showMenu) c.accent else c.textSecondary,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = hotspotHint && !connecting && !showMenu,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.WifiTethering, null, tint = c.accent2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                // 熱點名稱整段換行（「我的iPhone」不會被拆成「我 / 的iPhone」）
                FlowRow(Modifier.weight(1f)) {
                    Text("請開啟手機熱點", style = B570Theme.type.bodySmall, color = c.textPrimary, maxLines = 1)
                    car.hotspotSsid.takeIf { it.isNotBlank() }?.let {
                        Text("「$it」", style = B570Theme.type.bodySmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                NeonButton("開啟熱點", onOpenHotspot, compact = true, color = c.accent2, icon = Icons.Rounded.WifiTethering)
            }
        }
        AnimatedVisibility(
            visible = showMenu,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NeonButton(
                    "重新命名", { menu = false; onRename() }, Modifier.weight(1f),
                    style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.DriveFileRenameOutline, compact = true,
                )
                NeonButton(
                    "忘記這台車", { menu = false; onForget() }, Modifier.weight(1f),
                    style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.DeleteOutline, compact = true, color = c.danger,
                )
            }
        }
    }
}

/** 還沒有記住的車子：三步驟配對說明。 */
@Composable
internal fun EmptySavedCars(modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.l))
            .padding(B570Theme.spacing.l),
    ) {
        Text("還沒有記住的車子", style = B570Theme.type.titleS, color = c.textPrimary)
        Text("配對一次之後，下次點一下就能連線", style = B570Theme.type.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(14.dp))
        PairStepsRow(Modifier.fillMaxWidth())
    }
}

/** 配對三步驟：打開電源 → OLED 顯示 QR Code → 用 App 掃描。 */
@Composable
internal fun PairStepsRow(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        PairStep(1, Icons.Rounded.PowerSettingsNew, "打開車子電源", Modifier.weight(1f))
        PairStep(2, Icons.Rounded.QrCode2, "OLED 顯示 QR\u00A0Code", Modifier.weight(1f))
        PairStep(3, Icons.Rounded.QrCodeScanner, "用 App 掃描", Modifier.weight(1f))
    }
}

@Composable
private fun PairStep(n: Int, icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).glass(CircleShape, fill = c.accent.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("$n", style = B570Theme.type.caption, color = c.accent)
        Text(label, style = B570Theme.type.caption, color = c.textSecondary, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * 相對時間（台灣用語）：剛剛、3 分鐘前、2 小時前、昨天、4 天前、9 月 3 日、2025/9/3。
 * [thenMs] ≤ 0 表示從未連線。
 */
internal fun relativeTimeZh(thenMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (thenMs <= 0L) return "尚未連線過"
    val diff = (nowMs - thenMs).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    if (minutes < 1) return "剛剛"
    if (minutes < 60) return "$minutes 分鐘前"
    val hours = minutes / 60
    if (hours < 24) return "$hours 小時前"
    val then = Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate()
    val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then, now)
    return when {
        days <= 1L -> "昨天"
        days < 7L -> "$days 天前"
        then.year == now.year -> "${then.monthValue} 月 ${then.dayOfMonth} 日"
        else -> "${then.year}/${then.monthValue}/${then.dayOfMonth}"
    }
}
