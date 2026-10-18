package com.b570.pilot.ui.screens.monitor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.TelemetryHistory
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.MainTab
import com.b570.pilot.ui.components.LocalScreenPadding
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.SegmentedSelector
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Material 3「強調」緩動：展開減速、收合加速。 */
private val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val Scrim = Color(0x99000000)

/** 放大檢視寬度 ≥ 這個值時，右側固定顯示「時間範圍 + 統計」側欄；較窄時統計改成可開關的浮動面板。 */
private val SidePanelMinWidth = 640.dp

/** 較窄的放大檢視：時間範圍放在標題列（需要這個寬度），否則放到底部。 */
private val HeaderSelectorMinWidth = 460.dp

/**
 * 圖卡 → 放大檢視的容器轉場狀態。
 * [progress] 0 = 卡片原位、1 = 全版；只在 layout / draw 階段讀取，轉場中不重組。
 */
@Stable
internal class ExpandState {
    var kind: ChartKind? by mutableStateOf(null)
        private set

    /** 放大檢視已完全蓋住清單（清單內的圖可暫停重畫省電）。 */
    var covering by mutableStateOf(false)
        private set

    val progress = Animatable(0f)

    /** 起點（容器座標）；null = 由中央放大。 */
    var origin: Rect? = null
        private set

    /** 畫面容器的座標（onPlaced 設定）。 */
    var container: LayoutCoordinates? = null

    private val cards = HashMap<ChartKind, LayoutCoordinates>()

    fun registerCard(k: ChartKind, coords: LayoutCoordinates) {
        cards[k] = coords
    }

    /** 原卡片在轉場中淡出。 */
    fun sourceAlpha(k: ChartKind): Float = if (kind == k) 1f - progress.value.coerceIn(0f, 1f) else 1f

    private fun boundsOf(k: ChartKind): Rect? {
        val cont = container ?: return null
        val card = cards[k] ?: return null
        if (!cont.isAttached || !card.isAttached) return null
        return cont.localBoundingBoxOf(card, clipBounds = false)
    }

    suspend fun open(k: ChartKind) {
        if (kind != null) return
        origin = boundsOf(k)
        kind = k
        progress.snapTo(0f)
        progress.animateTo(1f, tween(420, easing = EnterEasing))
        covering = true
    }

    suspend fun close() {
        val k = kind ?: return
        if (progress.isRunning && progress.targetValue == 0f) return
        covering = false
        boundsOf(k)?.let { origin = it }
        progress.animateTo(0f, tween(300, easing = ExitEasing))
        kind = null
    }
}

/**
 * 放大檢視：卡片從原位置長大到整個內容區（導覽軌右側、狀態條下方，左右各留 [gutter]）。
 * 內容以最終尺寸量測一次，轉場只改外框與裁切。導覽軌（含緊急停止）與狀態條一直在上層，不會被蓋住。
 */
@Composable
internal fun ExpandedChartOverlay(
    expand: ExpandState,
    vm: AppViewModel,
    model: MonitorViewModel,
    nav: AppNavigator,
    windowSec: Int,
    windowSamples: Int,
    windowSpanSec: Float,
    paused: Boolean,
    gutter: Dp,
    onWindow: (Int) -> Unit,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
) {
    val kind = expand.kind ?: return
    val def = ChartDefs.of(kind)
    val c = B570Theme.colors
    BackHandler(onBack = onClose)

    val pad = LocalScreenPadding.current
    val ld = LocalLayoutDirection.current
    val density = LocalDensity.current
    // [左, 上, 右, 下] 外距 px（瀏海只在一側、翻轉後換邊：依版面方向換算成實際左右）
    val insets = remember(pad, density, ld, gutter) {
        with(density) {
            val start = (pad.calculateStartPadding(ld) + gutter).roundToPx()
            val end = (pad.calculateEndPadding(ld) + gutter).roundToPx()
            intArrayOf(
                if (ld == LayoutDirection.Ltr) start else end,
                (pad.calculateTopPadding() + 4.dp).roundToPx(),
                if (ld == LayoutDirection.Ltr) end else start,
                pad.calculateBottomPadding().coerceAtLeast(12.dp).roundToPx(),
            )
        }
    }
    val target = remember { IntArray(2) }
    val shape = RoundedCornerShape(B570Theme.radii.xl)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = expand.progress.value.coerceIn(0f, 1f) }
                .background(Scrim)
                .noRippleClickable(onClick = onClose),
        )
        Box(
            Modifier
                // 外框：位置與尺寸由 progress 內插
                .layout { measurable, constraints ->
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val left = insets[0]
                    val top = insets[1]
                    val tw = (w - left - insets[2]).coerceAtLeast(1)
                    val th = (h - top - insets[3]).coerceAtLeast(1)
                    target[0] = tw
                    target[1] = th
                    val p = expand.progress.value.coerceIn(0f, 1f)
                    val o = expand.origin ?: Rect(left + tw * 0.08f, top + th * 0.08f, left + tw * 0.92f, top + th * 0.92f)
                    val cw = lerpF(o.width, tw.toFloat(), p).roundToInt().coerceAtLeast(1)
                    val ch = lerpF(o.height, th.toFloat(), p).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(cw, ch))
                    layout(w, h) {
                        placeable.place(lerpF(o.left, left.toFloat(), p).roundToInt(), lerpF(o.top, top.toFloat(), p).roundToInt())
                    }
                }
                .glass(shape, fill = c.surface.copy(alpha = 0.97f), glow = c.accent.copy(alpha = 0.45f), glowElevation = 24.dp)
                .clip(shape)
                .pointerInput(Unit) { detectTapGestures { } } // 卡片內點擊不關閉
                // 內容：固定以最終尺寸量測，超出外框的部分被裁切
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints.fixed(target[0], target[1]))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                }
                .graphicsLayer { alpha = ((expand.progress.value - 0.3f) / 0.7f).coerceIn(0f, 1f) },
        ) {
            ExpandedContent(def, vm, model, nav, windowSec, windowSamples, windowSpanSec, paused, onWindow, onTogglePause, onClose)
        }
    }
}

/**
 * 放大檢視內容（橫向）：
 * - 寬（≥ 640 dp）：標題列 + 左邊大曲線 / 右邊側欄（時間範圍、統計）
 * - 窄：標題列（含時間範圍）+ 滿版曲線；統計用標題列的「統計」鈕開關（從右側滑入，蓋在曲線上）
 */
@Composable
private fun ExpandedContent(
    def: ChartDef,
    vm: AppViewModel,
    model: MonitorViewModel,
    nav: AppNavigator,
    windowSec: Int,
    windowSamples: Int,
    windowSpanSec: Float,
    paused: Boolean,
    onWindow: (Int) -> Unit,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
) {
    val c = B570Theme.colors
    val colors = rememberSeriesColors(def.kind)
    val mask = model.hiddenMask(def)
    val series = rememberVisibleSeries(def, colors, mask)
    val yRange = rememberSymmetricRange(def, vm.history, mask, windowSamples, paused)
    var showStats by rememberSaveable { mutableStateOf(false) }
    val toTune: () -> Unit = { onClose(); nav.selectTab(MainTab.TUNE) }
    val statsTitle = if (paused) "統計 · 已暫停" else "統計 · 最近 ${fmtSec(windowSpanSec)} 秒"

    // 內容只在轉場開始時以最終尺寸量測一次
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = maxWidth >= SidePanelMinWidth
        val short = maxHeight < 320.dp
        val selectorInHeader = !side && maxWidth >= HeaderSelectorMinWidth
        val buttonSize = if (short) 34.dp else 36.dp
        val panelWidth = (maxWidth * 0.3f).coerceIn(220.dp, 300.dp)
        val floatingPanelWidth = (maxWidth * 0.5f).coerceIn(180.dp, 240.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (short) 10.dp else 14.dp)) {
            // 窄時省略圖示，把寬度留給標題與時間範圍
            ChartHeader(def, colors[0], showSubtitle = !short, iconSize = if (short) 28.dp else 34.dp, showIcon = side || !selectorInHeader) {
                if (selectorInHeader) {
                    Spacer(Modifier.width(8.dp))
                    WindowSelector(windowSec, onWindow, Modifier.width(176.dp))
                }
                if (side && def.tunable) {
                    NeonButton("整定", toTune, style = NeonButtonStyle.GHOST, icon = Icons.Rounded.Tune, compact = true)
                    Spacer(Modifier.width(4.dp))
                }
                if (!side) {
                    Spacer(Modifier.width(6.dp))
                    RoundIconButton(
                        Icons.Rounded.QueryStats,
                        if (showStats) "隱藏統計" else "顯示統計",
                        { showStats = !showStats },
                        tint = if (showStats) c.accent else c.textSecondary,
                        size = buttonSize,
                        highlighted = showStats,
                    )
                    if (def.tunable) {
                        Spacer(Modifier.width(6.dp))
                        RoundIconButton(Icons.Rounded.Tune, "到整定調參數", toTune, size = buttonSize)
                    }
                }
                Spacer(Modifier.width(6.dp))
                RoundIconButton(
                    if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    if (paused) "繼續" else "暫停",
                    onTogglePause,
                    tint = if (paused) c.warn else c.accent,
                    size = buttonSize,
                    highlighted = paused,
                )
                Spacer(Modifier.width(6.dp))
                RoundIconButton(Icons.Rounded.CloseFullscreen, "縮小", onClose, tint = c.textPrimary, size = buttonSize)
            }
            Spacer(Modifier.height(if (short) 8.dp else 12.dp))
            if (side) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        ChartLegend(def, colors, vm, model, paused)
                        Spacer(Modifier.height(10.dp))
                        ChartPlot(def, vm, series, windowSamples, windowSpanSec, paused, yRange, Modifier.weight(1f).fillMaxWidth())
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(
                        Modifier
                            .width(panelWidth)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        PanelLabel("時間範圍")
                        WindowSelector(windowSec, onWindow, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        PanelLabel(statsTitle)
                        StatsPanel(def, colors, mask, vm.history, windowSamples, paused)
                    }
                }
            } else {
                ChartLegend(def, colors, vm, model, paused)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ChartPlot(def, vm, series, windowSamples, windowSpanSec, paused, yRange, Modifier.fillMaxSize())
                    FloatingStats(showStats, Modifier.align(Alignment.TopEnd).width(floatingPanelWidth).fillMaxHeight()) {
                        PanelLabel(statsTitle)
                        StatsPanel(def, colors, mask, vm.history, windowSamples, paused)
                    }
                }
                if (!selectorInHeader) {
                    Spacer(Modifier.height(8.dp))
                    WindowSelector(windowSec, onWindow, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** 窄放大檢視的統計面板：從右側滑入、蓋在曲線上（玻璃底、可捲動）；隱藏時不組合（不跑統計）。 */
@Composable
private fun FloatingStats(visible: Boolean, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = B570Theme.colors
    val shape = RoundedCornerShape(B570Theme.radii.m)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(tween(260, easing = EnterEasing)) { it / 2 } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(200)) { it / 2 } + fadeOut(tween(160)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .glass(shape, fill = c.surface.copy(alpha = 0.95f))
                .clip(shape)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            content = content,
        )
    }
}

/** 時間範圍 5 / 10 / 30 / 60 秒（每段太窄時只顯示數字，大字級也不會擠在一起）。 */
@Composable
internal fun WindowSelector(windowSec: Int, onWindow: (Int) -> Unit, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        val withUnit = maxWidth / WINDOW_OPTIONS.size >= 44.dp * fontScale
        SegmentedSelector(
            options = WINDOW_OPTIONS,
            selected = windowSec,
            onSelect = onWindow,
            modifier = Modifier.fillMaxWidth(),
            label = { if (withUnit) "$it 秒" else "$it" },
        )
    }
}

/** 側欄小標題。 */
@Composable
private fun PanelLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        style = B570Theme.type.caption,
        color = B570Theme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 一條曲線在目前時間範圍內的統計（快照 state，4 Hz 更新）。 */
@Stable
private class SeriesStat {
    var cur by mutableFloatStateOf(Float.NaN)
    var min by mutableFloatStateOf(Float.NaN)
    var max by mutableFloatStateOf(Float.NaN)
    var avg by mutableFloatStateOf(Float.NaN)

    fun clear() {
        cur = Float.NaN; min = Float.NaN; max = Float.NaN; avg = Float.NaN
    }
}

/**
 * 目前 / 最小 / 最大 / 平均：每條可見曲線一個小區塊（窄側欄也不切字）。
 * 預先配置緩衝、零配置計算；暫停時停在暫停當下。每個區塊自己讀快照 state，只有它重組。
 */
@Composable
private fun StatsPanel(
    def: ChartDef,
    colors: List<Color>,
    hiddenMask: Int,
    history: TelemetryHistory,
    windowSamples: Int,
    paused: Boolean,
) {
    val stats = remember(def) { Array(def.series.size) { SeriesStat() } }
    val buf = remember(history) { FloatArray(history.capacity) }
    LaunchedEffect(def, history, windowSamples, paused) {
        while (isActive) {
            for (i in def.series.indices) {
                val n = history.copyLast(def.series[i].channel, windowSamples, buf)
                if (n <= 0) { stats[i].clear(); continue }
                var mn = Float.POSITIVE_INFINITY
                var mx = Float.NEGATIVE_INFINITY
                var sum = 0.0
                for (j in 0 until n) {
                    val v = buf[j]
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                    sum += v
                }
                val s = stats[i]
                s.cur = buf[n - 1]; s.min = mn; s.max = mx; s.avg = (sum / n).toFloat()
            }
            if (paused) break
            delay(250)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in def.series.indices) {
            if (hiddenMask and (1 shl i) != 0) continue
            SeriesStatBlock(def.series[i].label, colors[i], stats[i], def.decimals)
        }
    }
}

@Composable
private fun SeriesStatBlock(label: String, color: Color, s: SeriesStat, decimals: Int) {
    val c = B570Theme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.glass, RoundedCornerShape(B570Theme.radii.s))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = B570Theme.type.label,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("目前", style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(fmtNum(s.cur, decimals), style = B570Theme.type.readoutS, color = c.textPrimary, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Row {
            StatMini("最小", fmtNum(s.min, decimals), Modifier.weight(1f))
            StatMini("最大", fmtNum(s.max, decimals), Modifier.weight(1f))
            StatMini("平均", fmtNum(s.avg, decimals), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatMini(title: String, value: String, modifier: Modifier) {
    val c = B570Theme.colors
    Column(modifier) {
        Text(title, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = B570Theme.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
