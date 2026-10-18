package com.b570.pilot.ui.screens.tune

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.TelemetryHistory
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.Params
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.ChartSeries
import com.b570.pilot.ui.components.LineChart
import com.b570.pilot.ui.components.StatusPill
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlin.math.roundToInt

/**
 * 「示波器」卡：馬達 / 有未儲存修改 / 同步中 晶片 + 最近 5 秒的即時曲線（傾角、直立環、速度環）。
 * 橫向時固定在左欄上緣（不跟著捲動），右邊調參數時可以馬上看到效果；曲線可收合（記住設定）。
 *
 * @param chartHeight 曲線繪圖區高度（依可用高度決定，見 [TuneLayout.chartHeight]）
 */
@Composable
internal fun TuneScopeCard(
    vm: AppViewModel,
    ctl: TuneController,
    conn: ConnectionState,
    motorsOn: Boolean,
    dirty: Boolean,
    chartHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val connected = conn is ConnectionState.Connected
    val shape = RoundedCornerShape(B570Theme.radii.l)
    Column(
        modifier
            .fillMaxWidth()
            .glass(shape, fill = c.surface.copy(alpha = 0.93f))
            .clip(shape)
            .scopeBezel(c.accent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TuneMotorChip(conn, motorsOn)
                AnimatedVisibility(
                    visible = connected && dirty,
                    enter = fadeIn(tween(200)) + expandHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)),
                    exit = fadeOut(tween(150)) + shrinkHorizontally(),
                ) {
                    StatusPill("有未儲存修改", c.warn)
                }
                AnimatedVisibility(
                    visible = ctl.syncing,
                    enter = fadeIn(tween(160)) + expandHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)),
                    exit = fadeOut(tween(260, delayMillis = 120)) + shrinkHorizontally(tween(260, delayMillis = 120)),
                ) {
                    SyncChip()
                }
            }
            Spacer(Modifier.width(6.dp))
            ChartToggle(expanded = !ctl.chartCollapsed, onClick = { haptics.tick(); ctl.toggleChart() })
        }
        AnimatedVisibility(
            visible = !ctl.chartCollapsed,
            enter = expandVertically(spring(dampingRatio = 0.86f, stiffness = 380f), expandFrom = Alignment.Top) + fadeIn(tween(220, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top) + fadeOut(tween(140)),
        ) {
            Box(Modifier.padding(top = 6.dp)) {
                TuneChart(vm, ctl, chartHeight)
                if (!connected) OfflineChartScrim(conn, Modifier.matchParentSize())
            }
        }
    }
}

/** 示波器邊框：左右上角的細亮角標，像遙控器上的小螢幕。 */
private fun Modifier.scopeBezel(accent: Color): Modifier = this.drawWithCache {
    val len = 14.dp.toPx()
    val inset = 3.dp.toPx()
    val w = 1.5.dp.toPx()
    val col = accent.copy(alpha = 0.55f)
    val glow = Brush.verticalGradient(listOf(accent.copy(alpha = 0.06f), Color.Transparent), endY = size.height * 0.5f)
    onDrawBehind {
        drawRect(glow)
        // 左上
        drawLine(col, Offset(inset, inset), Offset(inset + len, inset), w)
        drawLine(col, Offset(inset, inset), Offset(inset, inset + len), w)
        // 右上
        drawLine(col, Offset(size.width - inset, inset), Offset(size.width - inset - len, inset), w)
        drawLine(col, Offset(size.width - inset, inset), Offset(size.width - inset, inset + len), w)
    }
}

@Composable
private fun TuneMotorChip(conn: ConnectionState, motorsOn: Boolean) {
    val c = B570Theme.colors
    when {
        conn is ConnectionState.LinkLost -> StatusPill("訊號中斷", c.warn, icon = Icons.Rounded.LinkOff)
        conn !is ConnectionState.Connected -> StatusPill("未連線", c.danger, icon = Icons.Rounded.LinkOff)
        motorsOn -> StatusPill("馬達運轉", c.ok, pulsing = true)
        else -> StatusPill("馬達停止", c.textMuted)
    }
}

/** 同步中：與 StatusPill 同外觀，圓點換成轉圈。 */
@Composable
private fun SyncChip() {
    val c = B570Theme.colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(c.accent.copy(alpha = 0.12f))
            .border(1.dp, c.accent.copy(alpha = 0.35f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(11.dp), color = c.accent, strokeWidth = 1.6.dp)
        Spacer(Modifier.width(6.dp))
        Text("同步中", style = B570Theme.type.label, color = c.textPrimary, maxLines = 1)
    }
}

@Composable
private fun ChartToggle(expanded: Boolean, onClick: () -> Unit) {
    val c = B570Theme.colors
    val tint by animateColorAsState(if (expanded) c.accent else c.textMuted, label = "chartTint")
    val fill by animateColorAsState(if (expanded) c.accent.copy(alpha = 0.14f) else c.glass, label = "chartFill")
    val scale by animateFloatAsState(if (expanded) 1f else 0.92f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "chartScale")
    Box(
        Modifier
            .size(32.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glass(CircleShape, fill = fill)
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ShowChart, contentDescription = if (expanded) "收合曲線" else "顯示曲線", tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** 最近 5 秒（依 TELEM_HZ 換算筆數）。PWM ÷ 1000 與傾角同一張圖。 */
@Composable
private fun TuneChart(vm: AppViewModel, ctl: TuneController, chartHeight: Dp) {
    val c = B570Theme.colors
    val hz = ctl.param(Params.TELEM_HZ)
    val window by remember(hz) { derivedStateOf { (hz.base * 5f).roundToInt().coerceIn(50, 500) } }
    val series = remember(c.series) {
        listOf(
            ChartSeries(TelemetryHistory.Channel.PITCH, c.series[0], "傾角 °"),
            ChartSeries(TelemetryHistory.Channel.PWM_BAL, c.series[1], "直立環 ÷1000", scale = 0.001f),
            ChartSeries(TelemetryHistory.Channel.PWM_VEL, c.series[2], "速度環 ÷1000", scale = 0.001f),
        )
    }
    LineChart(
        history = vm.history,
        series = series,
        modifier = Modifier.fillMaxWidth().scopeScreen(c.background, c.glassBorderDim),
        windowSamples = window,
        minSpan = 2f,
        chartHeight = chartHeight,
    )
}

/** 曲線（含圖例）底下的深色「小螢幕」框。 */
private fun Modifier.scopeScreen(bg: Color, border: Color): Modifier = this
    .drawWithCache {
        val r = CornerRadius(10.dp.toPx())
        val stroke = Stroke(1.dp.toPx())
        val fill = bg.copy(alpha = 0.35f)
        onDrawBehind {
            drawRoundRect(fill, cornerRadius = r)
            drawRoundRect(border, cornerRadius = r, style = stroke)
        }
    }
    .padding(start = 4.dp, end = 4.dp, top = 5.dp)

@Composable
private fun OfflineChartScrim(conn: ConnectionState, modifier: Modifier) {
    val c = B570Theme.colors
    val lost = conn is ConnectionState.LinkLost
    Box(
        modifier
            .clip(RoundedCornerShape(B570Theme.radii.s))
            .background(c.surface.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LinkOff, null, tint = if (lost) c.warn else c.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (lost) "訊號中斷，曲線暫停" else "未連線",
                style = B570Theme.type.titleS,
                color = c.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
