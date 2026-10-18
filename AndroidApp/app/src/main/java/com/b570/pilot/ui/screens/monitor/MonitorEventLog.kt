package com.b570.pilot.ui.screens.monitor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.CarEvent
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.SectionHeader
import com.b570.pilot.ui.components.severityStyle
import com.b570.pilot.ui.theme.B570Theme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

/** 事件紀錄標題（含筆數與清除）。 */
@Composable
internal fun LogHeader(count: Int, onClear: () -> Unit, modifier: Modifier = Modifier) {
    SectionHeader(
        title = "事件紀錄",
        modifier = modifier,
        subtitle = if (count == 0) "保留最近 ${MonitorViewModel.MAX_LOG} 則" else "最近 ${MonitorViewModel.MAX_LOG} 則，目前 $count 則",
        trailing = {
            AnimatedVisibility(count > 0, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
                NeonButton("清除", onClear, style = NeonButtonStyle.GHOST, icon = Icons.Rounded.DeleteSweep, compact = true)
            }
        },
    )
}

/** 沒有事件時。 */
@Composable
internal fun EmptyLog(modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(c.accent.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.NotificationsNone, null, tint = c.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("目前沒有事件", style = B570Theme.type.titleS, color = c.textPrimary)
                Text(
                    "馬達啟停、傾倒、電量與連線變化都會記在這裡",
                    style = B570Theme.type.bodySmall,
                    color = c.textSecondary,
                )
            }
        }
    }
}

/**
 * 一列事件（清單中相鄰列組成一張卡：首列上圓角、末列下圓角）。
 * [fresh] = 停在本頁時才收到的事件，進場時以等級色閃一下。
 */
@Composable
internal fun EventRow(
    event: CarEvent,
    isFirst: Boolean,
    isLast: Boolean,
    fresh: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val (icon, color) = severityStyle(event.severity)
    val r = B570Theme.radii.l
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(r)
        isFirst -> RoundedCornerShape(topStart = r, topEnd = r)
        isLast -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RectangleShape
    }
    val time = remember(event.timeMs) { TIME_FMT.format(Instant.ofEpochMilli(event.timeMs).atZone(ZoneId.systemDefault())) }
    val flash = remember(event.id) { Animatable(if (fresh) 1f else 0f) }
    LaunchedEffect(event.id) {
        if (flash.value > 0f) flash.animateTo(0f, tween(1600))
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.glass)
            .drawBehind {
                val f = flash.value
                if (f > 0f) drawRect(color.copy(alpha = 0.18f * f))
                if (!isFirst) {
                    drawLine(c.divider, Offset(54.dp.toPx(), 0f), Offset(size.width - 12.dp.toPx(), 0f), 1f)
                }
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).background(color.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        // 橫向左欄較窄：時間放在第二行右側，訊息可用整列寬度
        Column(Modifier.weight(1f)) {
            Text(
                event.messageZh,
                style = B570Theme.type.bodySmall,
                color = c.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (event.isLocal) "手機" else "車子",
                    modifier = Modifier.weight(1f),
                    style = B570Theme.type.caption,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(time, style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1)
            }
        }
    }
}
