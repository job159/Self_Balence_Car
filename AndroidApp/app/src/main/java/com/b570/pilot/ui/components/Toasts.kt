package com.b570.pilot.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.CarEvent
import com.b570.pilot.data.Severity
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * 提示停留多久（純函式，`ToastDurationTest`）：一般 2.6 s、危險 4 s；長句子（例如切換熱點模式完成時的「以後帶出去怎麼連」）
 * 依字數延長到看得完（每字約 0.11 s，最多 6 s）。
 */
internal fun toastDurationMs(severity: Severity, messageLength: Int): Long {
    val base = if (severity == Severity.DANGER) 4_000L else 2_600L
    val reading = 110L * messageLength.coerceAtLeast(0)
    return maxOf(base, reading.coerceAtMost(6_000L))
}

/**
 * 顯示 [CarEvent] 的浮動提示（玻璃卡片，最多 [maxVisible] 則，自動消失、點一下關閉）。
 * shell 已放一個在最上層（橫向：內容區上緣置中、最多 2 則、寬度上限 420 dp；駕駛畫面更窄，避開左右肩鍵），
 * 畫面一般不需要再放。
 * @param maxWidth 單則提示的最大寬度
 * @param dismissOnClick false = 提示不攔截觸控（駕駛畫面：手指落在提示上仍會按到下面的控制）
 */
@Composable
fun CarEventToastHost(
    events: Flow<CarEvent>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 3,
    maxWidth: Dp = 460.dp,
    dismissOnClick: Boolean = true,
) {
    val items = remember { mutableStateListOf<ToastItem>() }
    val haptics = rememberHaptics()
    LaunchedEffect(events) {
        events.collect { e ->
            when (e.severity) {
                Severity.DANGER -> haptics.heavy()
                Severity.WARNING -> haptics.reject()
                else -> Unit
            }
            items.add(0, ToastItem(e, MutableTransitionState(false).apply { targetState = true }))
            while (items.count { it.state.targetState } > maxVisible) {
                items.lastOrNull { it.state.targetState }?.state?.targetState = false
            }
        }
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items.toList()) {
            key(item.event.id) {
                LaunchedEffect(item.event.id) {
                    delay(toastDurationMs(item.event.severity, item.event.messageZh.length))
                    item.state.targetState = false
                }
                LaunchedEffect(item.state.isIdle, item.state.currentState) {
                    if (item.state.isIdle && !item.state.currentState) items.remove(item)
                }
                AnimatedVisibility(
                    visibleState = item.state,
                    enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 400f)) { -it } + fadeIn() + expandVertically(),
                    exit = fadeOut() + scaleOut(targetScale = 0.92f) + shrinkVertically(),
                ) {
                    ToastCard(item.event, onClick = if (dismissOnClick) ({ item.state.targetState = false }) else null, maxWidth = maxWidth)
                }
            }
        }
    }
}

@Composable
fun ToastCard(event: CarEvent, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, maxWidth: Dp = 460.dp) {
    val c = B570Theme.colors
    val (icon, color) = severityStyle(event.severity)
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = maxWidth)
            .glass(RoundedCornerShape(16.dp), fill = c.surface.copy(alpha = 0.92f), glow = color.copy(alpha = 0.55f), glowElevation = 14.dp)
            .then(if (onClick != null) Modifier.noRippleClickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(event.messageZh, style = B570Theme.type.body, color = c.textPrimary)
    }
}

@Composable
fun severityStyle(s: Severity): Pair<ImageVector, Color> {
    val c = B570Theme.colors
    return when (s) {
        Severity.INFO -> Icons.Rounded.Info to c.info
        Severity.SUCCESS -> Icons.Rounded.CheckCircle to c.ok
        Severity.WARNING -> Icons.Rounded.Warning to c.warn
        Severity.DANGER -> Icons.Rounded.Error to c.danger
    }
}

private class ToastItem(val event: CarEvent, val state: MutableTransitionState<Boolean>)
