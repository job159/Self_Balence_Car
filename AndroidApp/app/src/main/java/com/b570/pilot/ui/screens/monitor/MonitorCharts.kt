package com.b570.pilot.ui.screens.monitor

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.TelemetryHistory
import com.b570.pilot.data.TelemetryHistory.Channel
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.ChartSeries
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.LineChart
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.max

/** 時間軸文字列高度（放大檢視算圖高用）。 */
internal val TimeAxisHeight = 22.dp

// ====================================================================== 清單中的圖卡

/**
 * 一張即時曲線卡：標題、可切換的圖例（含即時數值）、LineChart、時間軸。
 * 卡片高度由呼叫端固定（同列圖卡對齊、手機剛好一屏一列），曲線填滿剩下的高度。
 * 高頻資料只在 LineChart 繪製階段與圖例數值小葉節點讀取；本體只在切換曲線 / 縱軸級距時重組。
 * @param dense 矮螢幕：省略副標、較小的圖示與留白，把高度留給曲線
 */
@Composable
internal fun ChartCard(
    def: ChartDef,
    vm: AppViewModel,
    model: MonitorViewModel,
    windowSamples: Int,
    windowSpanSec: Float,
    paused: Boolean,
    expand: ExpandState,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    val colors = rememberSeriesColors(def.kind)
    val mask = model.hiddenMask(def)
    val series = rememberVisibleSeries(def, colors, mask)
    val yRange = rememberSymmetricRange(def, vm.history, mask, windowSamples, paused)
    val haptics = rememberHaptics()
    GlassCard(
        modifier
            .fillMaxWidth()
            .onPlaced { expand.registerCard(def.kind, it) }
            .graphicsLayer { alpha = expand.sourceAlpha(def.kind) },
        contentPadding = if (dense) {
            PaddingValues(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 6.dp)
        } else {
            PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)
        },
    ) {
        ChartHeader(
            def,
            colors[0],
            Modifier.noRippleClickable { haptics.click(); onExpand() },
            showSubtitle = !dense,
            iconSize = if (dense) 28.dp else 34.dp,
        ) {
            RoundIconButton(Icons.Rounded.OpenInFull, "放大${def.title}圖", onExpand, size = if (dense) 32.dp else 34.dp)
        }
        Spacer(Modifier.height(if (dense) 8.dp else 12.dp))
        ChartLegend(def, colors, vm, model, paused)
        Spacer(Modifier.height(if (dense) 6.dp else 10.dp))
        ChartPlot(def, vm, series, windowSamples, windowSpanSec, paused, yRange, Modifier.weight(1f).fillMaxWidth())
    }
}

/** 圖卡標題列：圖示（可省略）、標題、單位、副標（可省略），右側自訂動作。 */
@Composable
internal fun ChartHeader(
    def: ChartDef,
    tint: Color,
    modifier: Modifier = Modifier,
    showSubtitle: Boolean = true,
    iconSize: Dp = 34.dp,
    showIcon: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = B570Theme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showIcon) {
            Box(
                Modifier
                    .size(iconSize)
                    .background(tint.copy(alpha = 0.12f), CircleShape)
                    .border(1.dp, tint.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(def.icon, null, tint = tint, modifier = Modifier.size(iconSize * 0.53f))
            }
            Spacer(Modifier.width(if (iconSize < 34.dp) 8.dp else 10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    def.title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = B570Theme.type.titleS,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (def.unit.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    UnitBadge(def.unit)
                }
            }
            if (showSubtitle) {
                Text(def.subtitle, style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
private fun UnitBadge(unit: String) {
    val c = B570Theme.colors
    val shape = RoundedCornerShape(6.dp)
    Text(
        unit,
        style = B570Theme.type.caption,
        color = c.textSecondary,
        maxLines = 1,
        modifier = Modifier
            .background(c.glassHigh, shape)
            .border(1.dp, c.glassBorderDim, shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// ====================================================================== 圖例

/** 圖例膠囊列：點一下切換曲線（最後一條不能關），顯示即時數值。 */
@Composable
internal fun ChartLegend(
    def: ChartDef,
    colors: List<Color>,
    vm: AppViewModel,
    model: MonitorViewModel,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        def.series.forEachIndexed { i, s ->
            LegendChip(
                label = s.label,
                color = colors[i],
                visible = !model.isHidden(def, i),
                onClick = { if (model.toggle(def, i)) haptics.tick() else haptics.reject() },
            ) {
                LiveValue(vm, s.channel, def.decimals, paused)
            }
        }
    }
}

@Composable
private fun LegendChip(
    label: String,
    color: Color,
    visible: Boolean,
    onClick: () -> Unit,
    value: @Composable () -> Unit,
) {
    val c = B570Theme.colors
    val on = animateFloatAsState(if (visible) 1f else 0f, spring(stiffness = 500f), label = "chip")
    Row(
        Modifier
            .drawWithCache {
                val r = CornerRadius(size.height / 2f)
                val stroke = Stroke(1.dp.toPx())
                val inset = stroke.width / 2f
                val strokeSize = Size(size.width - stroke.width, size.height - stroke.width)
                onDrawBehind {
                    val p = on.value
                    drawRoundRect(color.copy(alpha = 0.13f * p), cornerRadius = r)
                    drawRoundRect(color.copy(alpha = 0.18f + 0.34f * p), Offset(inset, inset), strokeSize, r, style = stroke)
                }
            }
            .toggleable(
                value = visible,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = { onClick() },
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .graphicsLayer { alpha = 0.45f + 0.55f * on.value },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).drawWithCache {
                val ring = Stroke(1.5.dp.toPx())
                onDrawBehind {
                    val p = on.value
                    drawCircle(color, radius = size.minDimension / 2f - ring.width / 2f, style = ring)
                    drawCircle(color, radius = (size.minDimension / 2f) * p)
                }
            },
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        value()
    }
}

/**
 * 圖例上的即時數值（≤ 10 Hz 葉節點）。暫停時停在暫停當下的值，與凍結的曲線一致。
 */
@Composable
private fun LiveValue(vm: AppViewModel, channel: Channel, decimals: Int, paused: Boolean) {
    val t by vm.telemetrySlow.collectAsStateWithLifecycle()
    // 非 state 暫存（組合期間寫入安全）：暫停時保留最後顯示的值
    val hold = remember { floatArrayOf(Float.NaN) }
    val tt = t
    if (!paused || hold[0].isNaN()) {
        hold[0] = if (tt != null) tt.valueOf(channel) else vm.history.latest(channel)
    }
    Text(
        fmtNum(hold[0], decimals),
        style = B570Theme.type.label,
        color = B570Theme.colors.textPrimary,
        maxLines = 1,
    )
}

/** 遙測欄位對應歷史通道。 */
internal fun Telemetry.valueOf(ch: Channel): Float = when (ch) {
    Channel.PITCH -> pitchDeg
    Channel.ROLL -> rollDeg
    Channel.GYRO_BAL -> gyroBalDps
    Channel.GYRO_TURN -> gyroTurnDps
    Channel.VEL_L -> velL.toFloat()
    Channel.VEL_R -> velR.toFloat()
    Channel.PWM_L -> pwmL.toFloat()
    Channel.PWM_R -> pwmR.toFloat()
    Channel.PWM_BAL -> pwmBal.toFloat()
    Channel.PWM_VEL -> pwmVel.toFloat()
    Channel.PWM_TURN -> pwmTurn.toFloat()
    Channel.TGT_SPEED -> targetSpeed.toFloat()
    Channel.TGT_TURN -> targetTurn.toFloat()
    Channel.BATTERY -> batteryV
    Channel.HEADING -> headingDeg
}

// ====================================================================== 提示

/**
 * 馬達停止時在曲線左上角說明「輸出維持 0」（疊在圖上，不改變卡片高度；只在狀態改變時重組）。
 */
@Composable
private fun MotorOffBadge(vm: AppViewModel, modifier: Modifier) {
    val c = B570Theme.colors
    val tState = vm.telemetrySlow.collectAsStateWithLifecycle()
    val stop by remember { derivedStateOf { tState.value?.let { if (it.motorsOn) null else it.stopReason } } }
    val last = remember { arrayOfNulls<StopReason>(1) }
    stop?.let { last[0] = it }
    AnimatedVisibility(
        visible = stop != null,
        modifier = modifier,
        enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
        exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
    ) {
        val r = stop ?: last[0] ?: StopReason.UNKNOWN
        val shape = RoundedCornerShape(50)
        Row(
            Modifier
                .background(c.surface.copy(alpha = 0.86f), shape)
                .border(1.dp, c.glassBorderDim, shape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PowerSettingsNew, null, tint = c.textMuted, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text(motorOffText(r), style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun motorOffText(r: StopReason): String = when (r) {
    StopReason.RUNNING, StopReason.UNKNOWN -> "馬達停止中，輸出維持 0"
    else -> "馬達停止中（${r.labelZh}），輸出維持 0"
}

/** 還沒有任何資料時蓋在圖上的提示。 */
@Composable
private fun NoDataHint(vm: AppViewModel, modifier: Modifier) {
    val tState = vm.telemetrySlow.collectAsStateWithLifecycle()
    val history = vm.history
    val empty by remember(history) { derivedStateOf { tState.value == null && history.size == 0 } }
    AnimatedVisibility(empty, modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("等待遙測資料…", style = B570Theme.type.bodySmall, color = B570Theme.colors.textMuted)
        }
    }
}

// ====================================================================== 繪圖區

/**
 * LineChart + 無資料提示 + 馬達停止標記 + 時間軸，填滿 [modifier] 給的區域（曲線高 = 可用高 − 時間軸）。
 * 只有可用區域改變（轉場開始、視窗大小改變）時才重新量測。
 */
@Composable
internal fun ChartPlot(
    def: ChartDef,
    vm: AppViewModel,
    series: List<ChartSeries>,
    windowSamples: Int,
    windowSpanSec: Float,
    paused: Boolean,
    yRange: ClosedFloatingPointRange<Float>?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        val chartHeight = (maxHeight - TimeAxisHeight).coerceAtLeast(MinPlotHeight)
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                LineChart(
                    history = vm.history,
                    series = series,
                    modifier = Modifier.fillMaxWidth(),
                    windowSamples = windowSamples,
                    yRange = yRange,
                    paused = paused,
                    showLegend = false,
                    minSpan = def.minSpan,
                    chartHeight = chartHeight,
                )
                NoDataHint(vm, Modifier.matchParentSize())
                if (def.motorHint) {
                    MotorOffBadge(vm, Modifier.align(Alignment.TopStart).padding(start = 44.dp, top = 4.dp, end = 4.dp))
                }
            }
            TimeAxis(windowSpanSec)
        }
    }
}

/** 曲線最低高度（極矮時寧可裁掉時間軸，也不要壓扁成一條線）。 */
private val MinPlotHeight = 56.dp

/** 時間軸：−N 秒 … 現在（對齊 LineChart 左側 40 dp 刻度區）。 */
@Composable
private fun TimeAxis(spanSec: Float) {
    val c = B570Theme.colors
    val style = B570Theme.type.caption
    val left = remember(spanSec) { "−${fmtSec(spanSec)} 秒" }
    val mid = remember(spanSec) { "−${fmtSec(spanSec / 2f)} 秒" }
    Box(Modifier.fillMaxWidth().height(TimeAxisHeight).padding(start = 40.dp, end = 4.dp, top = 4.dp)) {
        Text(left, Modifier.align(Alignment.CenterStart), style = style, color = c.textMuted, maxLines = 1)
        Text(mid, Modifier.align(Alignment.Center), style = style, color = c.textMuted, maxLines = 1)
        Text("現在", Modifier.align(Alignment.CenterEnd), style = style, color = c.textSecondary, maxLines = 1)
    }
}

// ====================================================================== 共用 state

/** 各圖配色（跟隨主題強調色）。 */
@Composable
internal fun rememberSeriesColors(kind: ChartKind): List<Color> {
    val c = B570Theme.colors
    return remember(kind, c) {
        when (kind) {
            ChartKind.ATTITUDE -> listOf(c.accent, c.accent2)
            ChartKind.WHEELS -> listOf(c.series[2], c.series[1], c.textSecondary)
            ChartKind.PWM -> listOf(c.series[0], c.series[3], c.series[4], c.series[5], c.series[6])
            ChartKind.GYRO -> listOf(c.series[7], c.series[3])
            ChartKind.BATTERY -> listOf(c.ok)
        }
    }
}

/** 目前可見的曲線（只在切換時重建，LineChart 的暫存因此保持不變）。 */
@Composable
internal fun rememberVisibleSeries(def: ChartDef, colors: List<Color>, mask: Int): List<ChartSeries> =
    remember(def, colors, mask) {
        def.series.mapIndexedNotNull { i, s ->
            if (mask and (1 shl i) != 0) null else ChartSeries(s.channel, colors[i], s.label)
        }
    }

/**
 * 以 0 為中心的縱軸（[ChartDef.symmetricLevels]）：放大立即、縮小需持續 1.5 秒（避免跳動）。
 * 每 200 ms 用 history.minMax 估一次峰值（零配置），只有級距改變時才重組。
 */
@Composable
internal fun rememberSymmetricRange(
    def: ChartDef,
    history: TelemetryHistory,
    hiddenMask: Int,
    windowSamples: Int,
    paused: Boolean,
): ClosedFloatingPointRange<Float>? {
    val levels = def.symmetricLevels ?: return null
    val level = remember(def) { mutableFloatStateOf(levels[1]) }
    LaunchedEffect(def, history, hiddenMask, windowSamples, paused) {
        val mm = FloatArray(2)
        var shrinkSince = 0L
        while (isActive) {
            var peak = 0f
            for (i in def.series.indices) {
                if (hiddenMask and (1 shl i) != 0) continue
                history.minMax(def.series[i].channel, windowSamples, mm)
                if (!mm[0].isNaN()) peak = max(peak, max(abs(mm[0]), abs(mm[1])))
            }
            val need = peak * 1.12f
            var want = levels[levels.size - 1]
            for (l in levels) if (l >= need) { want = l; break }
            val cur = level.floatValue
            when {
                want > cur -> { level.floatValue = want; shrinkSince = 0L }
                want < cur -> {
                    val now = SystemClock.uptimeMillis()
                    if (shrinkSince == 0L) shrinkSince = now
                    else if (now - shrinkSince > 1500L) { level.floatValue = want; shrinkSince = 0L }
                }
                else -> shrinkSince = 0L
            }
            if (paused) break
            delay(200)
        }
    }
    val lv = level.floatValue
    return remember(lv) { -lv..lv }
}

// ====================================================================== 小元件

/**
 * 圓形玻璃圖示按鈕（按下彈性縮放 + 觸覺）。
 * @param highlighted 亮起（切換中的開關，例如統計面板已打開）
 * @param loading 轉圈並停用點擊（例如匯出中）
 */
@Composable
internal fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = B570Theme.colors.textSecondary,
    size: Dp = 36.dp,
    highlighted: Boolean = false,
    loading: Boolean = false,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "press")
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .background(
                when {
                    highlighted -> tint.copy(alpha = 0.18f)
                    pressed -> c.glassHigh
                    else -> c.glass
                },
                CircleShape,
            )
            .border(1.dp, tint.copy(alpha = if (highlighted) 0.6f else 0.28f), CircleShape)
            .clickable(src, indication = null, enabled = !loading, role = Role.Button, onClickLabel = contentDescription) {
                haptics.click()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(size * 0.45f), color = tint, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
        }
    }
}
