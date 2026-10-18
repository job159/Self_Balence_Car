package com.b570.pilot.ui.screens.monitor

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.b570.pilot.data.CarEvent
import com.b570.pilot.data.Severity
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.ControlSource
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.LocalScreenPadding
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.SectionHeader
import com.b570.pilot.ui.components.StatusPill
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 單欄堆疊時清單底部多留的空間（讓最後一張卡片不被浮動控制鈕擋住）。 */
private val FloatingClearance = 64.dp

/**
 * 「監測」分頁（橫向）：左欄 = 即時監測狀態 + 即時資料磚（2 欄）+ 事件紀錄（可捲動）；
 * 右側 = 工具列（時間範圍 / 暫停 / 匯出 CSV）+ 五張即時曲線（寬螢幕 2 欄、窄 1 欄，可放大到整個內容區）。
 * 分割畫面等很窄的視窗改成單欄堆疊（同直向版，加回浮動控制鈕）。版面規則見 [monitorLayoutOf]。
 *
 * 效能：50 Hz 遙測只在 LineChart 繪製階段讀取；數據磚與圖例數值是各自收集 ≤ 10 Hz 資料的小葉節點；
 * 放大轉場只在 layout / draw 階段讀動畫值；事件紀錄只讓左欄重組。清單項目皆有穩定 key。
 */
@Composable
fun MonitorScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier) {
    val model: MonitorViewModel = viewModel { MonitorViewModel(vm.recentEvents, vm::clearEventLog) }
    val params by vm.params.collectAsStateWithLifecycle()

    val history = vm.history
    val hz = telemHz(params)
    val windowSec = model.windowSec
    val windowSamples = remember(windowSec, hz, history) { (windowSec * hz).roundToInt().coerceIn(10, history.capacity) }
    val windowSpanSec = windowSamples / hz

    val paused = model.paused
    val expand = remember { ExpandState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val entrance = remember { EntranceTracker() }
    val openedAtMs = remember { System.currentTimeMillis() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME)) { uri ->
        if (uri != null) {
            model.export({ vm.exportHistoryCsv(uri) }) { ok -> if (ok) haptics.confirm() else haptics.reject() }
        }
    }
    val onExport: () -> Unit = {
        if (history.size == 0) {
            haptics.reject()
            vm.notice("還沒有資料可以匯出", Severity.WARNING)
        } else {
            runCatching { exportLauncher.launch(csvFileName()) }
                .onFailure { vm.notice("找不到可以儲存檔案的程式", Severity.DANGER) }
        }
    }
    val togglePause: () -> Unit = { model.paused = !model.paused }
    val setWindow: (Int) -> Unit = { model.windowSec = it }
    val cycleWindow: () -> Unit = {
        val i = WINDOW_OPTIONS.indexOf(model.windowSec)
        model.windowSec = WINDOW_OPTIONS[(i + 1) % WINDOW_OPTIONS.size]
    }
    val openChart: (ChartKind) -> Unit = { k -> scope.launch { expand.open(k) } }
    val closeExpanded: () -> Unit = { scope.launch { expand.close() } }
    // 放大檢視完全蓋住時，清單裡的圖暫停重畫
    val chartsPaused = paused || expand.covering

    val pad = LocalScreenPadding.current
    val ld = LocalLayoutDirection.current
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(modifier.onPlaced { expand.container = it }) {
        val insets = PaneInsets(
            start = pad.calculateStartPadding(ld),
            top = pad.calculateTopPadding(),
            end = pad.calculateEndPadding(ld),
            bottom = pad.calculateBottomPadding(),
        )
        val availW = maxWidth - insets.start - insets.end
        val availH = maxHeight - insets.top - insets.bottom
        val layout = remember(availW, availH, fontScale) { monitorLayoutOf(availW, availH, fontScale) }

        val chartCard: @Composable (ChartDef, Modifier) -> Unit = { def, m ->
            ChartCard(
                def = def,
                vm = vm,
                model = model,
                windowSamples = windowSamples,
                windowSpanSec = windowSpanSec,
                paused = chartsPaused,
                expand = expand,
                onExpand = { openChart(def.kind) },
                modifier = m,
                dense = layout.denseCards,
            )
        }
        val toolbar: @Composable (Modifier) -> Unit = { m ->
            ChartToolbar(
                mode = layout.toolbar,
                paused = paused,
                windowSec = windowSec,
                windowSpanSec = windowSpanSec,
                exporting = model.exporting,
                onWindow = setWindow,
                onTogglePause = togglePause,
                onExport = onExport,
                modifier = m,
            )
        }

        if (layout.stacked) {
            StackedMonitor(
                vm = vm,
                model = model,
                layout = layout,
                insets = insets,
                hz = hz,
                paused = paused,
                windowSec = windowSec,
                expandOpen = expand.kind != null,
                entrance = entrance,
                openedAtMs = openedAtMs,
                onTogglePause = togglePause,
                onCycleWindow = cycleWindow,
                toolbar = toolbar,
                chartCard = chartCard,
            )
        } else {
            val g = layout.gutter
            Row(Modifier.fillMaxSize()) {
                SidePane(
                    vm = vm,
                    model = model,
                    hz = hz,
                    paused = paused,
                    denseTiles = layout.denseTiles,
                    entrance = entrance,
                    openedAtMs = openedAtMs,
                    // 兩欄之間的留白算在左欄（清單會裁切，留白讓危險數據磚 / 暫停卡片的光暈不被切掉）
                    modifier = Modifier.width(insets.start + g + layout.sideWidth + g).fillMaxHeight(),
                    contentPadding = PaddingValues(start = insets.start + g, end = g, top = insets.top, bottom = insets.bottom),
                )
                ChartPane(
                    layout = layout,
                    entrance = entrance,
                    toolbar = toolbar,
                    chartCard = chartCard,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    padding = PaneInsets(start = 0.dp, top = insets.top, end = g + insets.end, bottom = insets.bottom),
                )
            }
        }

        ExpandedChartOverlay(
            expand = expand,
            vm = vm,
            model = model,
            nav = nav,
            windowSec = windowSec,
            windowSamples = windowSamples,
            windowSpanSec = windowSpanSec,
            paused = paused,
            gutter = layout.gutter,
            onWindow = setWindow,
            onTogglePause = togglePause,
            onClose = closeExpanded,
        )
    }
}

/** 已依版面方向換算好的四邊留白。 */
private data class PaneInsets(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp)

// ====================================================================== 左欄

/** 左欄：即時監測狀態、延遲診斷、即時資料磚（2 欄）、事件紀錄。事件紀錄在這裡收集，新事件只讓左欄重組。 */
@Composable
private fun SidePane(
    vm: AppViewModel,
    model: MonitorViewModel,
    hz: Float,
    paused: Boolean,
    denseTiles: Boolean,
    entrance: EntranceTracker,
    openedAtMs: Long,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val log by model.log.collectAsStateWithLifecycle()
    LazyColumn(state = rememberLazyListState(), modifier = modifier, contentPadding = contentPadding) {
        item(key = "status", contentType = "status") {
            StatusCard(vm, hz, paused, Modifier.entrance(entrance, "status", 0))
        }
        item(key = "latency", contentType = "latency") {
            LatencyCard(vm, Modifier.padding(top = 12.dp).entrance(entrance, "latency", 1))
        }
        statsSection(vm, denseTiles, entrance, firstIndex = 2)
        logSection(log, onClear = { model.clearLog() }, openedAtMs = openedAtMs)
    }
}

/** 「即時資料」標題 + 六列數據磚。 */
private fun LazyListScope.statsSection(vm: AppViewModel, dense: Boolean, entrance: EntranceTracker, firstIndex: Int) {
    item(key = "stats-title", contentType = "section") {
        SectionHeader(
            "即時資料",
            Modifier.padding(top = 14.dp).entrance(entrance, "stats-title", firstIndex),
            subtitle = "車況每 0.1 秒、連線品質每 0.5 秒更新",
        )
    }
    items(STAT_ROWS, key = { "stats-$it" }, contentType = { "stats" }) { row ->
        StatRow(
            row,
            vm,
            Modifier.padding(top = if (row == 0) 4.dp else TileGap).entrance(entrance, "stats-$row", firstIndex + 1 + row),
            dense = dense,
        )
    }
}

/** 「事件紀錄」標題（含清除）+ 事件列（相鄰列組成一張卡）。 */
private fun LazyListScope.logSection(log: List<CarEvent>, onClear: () -> Unit, openedAtMs: Long) {
    item(key = "log-title", contentType = "section") {
        LogHeader(log.size, onClear = onClear, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    }
    if (log.isEmpty()) {
        item(key = "log-empty", contentType = "log-empty") {
            EmptyLog(Modifier.animateItem())
        }
    } else {
        itemsIndexed(log, key = { _, e -> "ev-${e.id}" }, contentType = { _, _ -> "event" }) { i, e ->
            EventRow(
                event = e,
                isFirst = i == 0,
                isLast = i == log.lastIndex,
                fresh = e.timeMs >= openedAtMs && System.currentTimeMillis() - e.timeMs < 2_000,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

// ====================================================================== 右側圖表區

/**
 * 圖表區：固定在上方的工具列 + 圖卡格（寬 2 欄、窄 1 欄；電池圖在 2 欄時橫跨整列）。
 * 卡片剛好填滿可見高度時，快速滑動會對齊整張卡（像翻儀表頁），1 欄時右側有頁碼點。
 */
@Composable
private fun ChartPane(
    layout: MonitorLayout,
    entrance: EntranceTracker,
    toolbar: @Composable (Modifier) -> Unit,
    chartCard: @Composable (ChartDef, Modifier) -> Unit,
    modifier: Modifier,
    padding: PaneInsets,
) {
    val gridState = rememberLazyGridState()
    val columns = layout.chartColumns
    Column(modifier) {
        Spacer(Modifier.height(padding.top))
        toolbar(Modifier.padding(start = padding.start, end = padding.end).entrance(entrance, "toolbar", 0))
        Spacer(Modifier.height(ToolbarGap))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(start = padding.start, end = padding.end, bottom = padding.bottom),
                verticalArrangement = Arrangement.spacedBy(CardGap),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
                flingBehavior = if (layout.snapCards) {
                    rememberSnapFlingBehavior(gridState, SnapPosition.Start)
                } else {
                    ScrollableDefaults.flingBehavior()
                },
            ) {
                items(
                    ChartDefs.ALL,
                    key = { "chart-${it.kind.name}" },
                    span = { def ->
                        // 2 欄時最後一張（電池，奇數張）橫跨整列
                        if (columns > 1 && def.kind.ordinal == ChartDefs.ALL.lastIndex && ChartDefs.ALL.size % columns != 0) {
                            GridItemSpan(maxLineSpan)
                        } else {
                            GridItemSpan(1)
                        }
                    },
                    contentType = { "chart" },
                ) { def ->
                    chartCard(def, Modifier.height(layout.cardHeight).entrance(entrance, def.kind, 1 + def.kind.ordinal))
                }
            }
            if (columns == 1) {
                ChartPagerDots(
                    gridState,
                    ChartDefs.ALL.size,
                    Modifier
                        .align(Alignment.CenterEnd)
                        // 置中在卡片右側的留白（gutter）裡
                        .padding(end = padding.end - layout.gutter + (layout.gutter - PagerDotsWidth) / 2),
                )
            }
        }
    }
}

/** 頁碼點寬度（放在右側留白裡）。 */
private val PagerDotsWidth = 4.dp

/**
 * 1 欄時的頁碼點（右側留白中央，裝飾用，TalkBack 略過）：目前那張卡的位置亮起並滑動過去（只在繪製階段讀動畫值）。
 */
@Composable
private fun ChartPagerDots(state: LazyGridState, count: Int, modifier: Modifier) {
    val c = B570Theme.colors
    val current by remember(state, count) {
        derivedStateOf {
            val first = state.layoutInfo.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf 0
            val h = first.size.height
            // 捲出去超過一半 → 算下一張
            val i = if (h > 0 && -first.offset.y > h / 2) first.index + 1 else first.index
            i.coerceIn(0, count - 1)
        }
    }
    val pos = animateFloatAsState(current.toFloat(), spring(dampingRatio = 0.7f, stiffness = 400f), label = "dot")
    Box(
        modifier
            .size(width = PagerDotsWidth, height = (count * 14).dp)
            .clearAndSetSemantics { }
            .drawBehind {
                val step = size.height / count
                val w = size.width
                val r = CornerRadius(w / 2f)
                for (i in 0 until count) {
                    drawRoundRect(c.textMuted.copy(alpha = 0.4f), Offset(0f, i * step + step * 0.3f), Size(w, step * 0.4f), r)
                }
                val y = pos.value * step
                drawRoundRect(c.accent.copy(alpha = 0.25f), Offset(-w * 0.5f, y), Size(w * 2f, step), CornerRadius(w))
                drawRoundRect(c.accent, Offset(0f, y + step * 0.12f), Size(w, step * 0.76f), r)
            },
    )
}

// ====================================================================== 工具列

/**
 * 圖表工具列（固定在圖表區上方）：時間範圍、暫停 / 繼續、匯出 CSV。
 * WIDE 另有「即時曲線」標題、目前時間範圍 / 已暫停與操作提示；NARROW 用圓形圖示鈕。
 */
@Composable
private fun ChartToolbar(
    mode: ToolbarMode,
    paused: Boolean,
    windowSec: Int,
    windowSpanSec: Float,
    exporting: Boolean,
    onWindow: (Int) -> Unit,
    onTogglePause: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = ToolbarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (mode == ToolbarMode.WIDE) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("即時曲線", style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    WindowBadge(windowSpanSec, paused)
                }
                Text(
                    "點圖例可隱藏曲線，點標題放大",
                    style = B570Theme.type.caption,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WindowSelector(windowSec, onWindow, Modifier.width(264.dp))
        } else {
            WindowSelector(windowSec, onWindow, Modifier.weight(1f))
        }
        val pauseIcon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause
        val pauseText = if (paused) "繼續" else "暫停"
        if (mode == ToolbarMode.NARROW) {
            RoundIconButton(pauseIcon, pauseText, onTogglePause, tint = if (paused) c.warn else c.accent, size = ToolbarHeight, highlighted = paused)
            RoundIconButton(Icons.Rounded.FileDownload, "匯出 CSV", onExport, tint = c.accent, size = ToolbarHeight, loading = exporting)
        } else {
            NeonButton(
                pauseText,
                onTogglePause,
                Modifier.height(ToolbarHeight),
                style = NeonButtonStyle.SECONDARY,
                icon = pauseIcon,
                compact = true,
                color = if (paused) c.warn else null,
            )
            NeonButton(
                "匯出 CSV",
                onExport,
                Modifier.height(ToolbarHeight),
                icon = Icons.Rounded.FileDownload,
                loading = exporting,
                compact = true,
            )
        }
    }
}

/** 時間範圍 / 已暫停（工具列標題右側）。 */
@Composable
private fun WindowBadge(spanSec: Float, paused: Boolean) {
    val c = B570Theme.colors
    AnimatedContent(
        targetState = if (paused) "已暫停" else "最近 ${fmtSec(spanSec)} 秒",
        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
        label = "badge",
    ) { text ->
        Text(text, style = B570Theme.type.label, color = if (text == "已暫停") c.warn else c.textSecondary, maxLines = 1)
    }
}

// ====================================================================== 狀態卡（左欄頂部）

/**
 * 即時監測狀態：標題 + 車名 · 已記錄秒數、連線異常說明、車況膠囊（即時 / 已暫停、馬達、控制來源、編排、失聯保護、電量、未存檔、展示）。
 * 膠囊自動換行（窄欄不必橫向捲動）。
 */
@Composable
private fun StatusCard(vm: AppViewModel, hz: Float, paused: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    GlassCard(
        modifier.fillMaxWidth(),
        glow = if (paused) c.warn.copy(alpha = 0.5f) else null,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(paused)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("即時監測", style = B570Theme.type.titleM, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RecordedCaption(vm, hz)
            }
        }
        ConnectionNotice(vm)
        Spacer(Modifier.height(10.dp))
        CarStatePills(vm, paused)
        AnimatedVisibility(paused, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                "曲線已凍結，資料仍在背景持續記錄",
                style = B570Theme.type.caption,
                color = c.warn,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 標題圖示：即時更新時有呼吸光環（只在繪製階段動畫）。 */
@Composable
private fun HeaderIcon(paused: Boolean) {
    val c = B570Theme.colors
    val tint = if (paused) c.warn else c.accent
    // 暫停時不跑無限動畫（不必每幀重畫）
    val p = if (paused) null else {
        rememberInfiniteTransition(label = "hdr")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    }
    Box(
        Modifier
            .size(40.dp)
            .drawBehind {
                val r = size.minDimension / 2f
                if (p != null) {
                    val v = p.value
                    drawCircle(tint.copy(alpha = 0.35f * (1f - v)), radius = r * (0.8f + 0.45f * v))
                }
                drawCircle(tint.copy(alpha = 0.14f), radius = r * 0.82f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Insights, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** 「車名 · 已記錄 N 秒」——只在整秒改變時重組。 */
@Composable
private fun RecordedCaption(vm: AppViewModel, hz: Float) {
    val c = B570Theme.colors
    val name by vm.currentCarName.collectAsStateWithLifecycle()
    val tState = vm.telemetrySlow.collectAsStateWithLifecycle()
    val history = vm.history
    val seconds by remember(history, hz) {
        derivedStateOf { if (tState.value == null && history.size == 0) 0 else (history.size / hz).toInt() }
    }
    val full by remember(history) {
        derivedStateOf { tState.value != null && history.size >= history.capacity }
    }
    val text = buildString {
        if (name.isNotEmpty()) append(name).append(" · ")
        if (full) append("保留最近 ").append(seconds).append(" 秒") else append("已記錄 ").append(seconds).append(" 秒")
    }
    Text(text, style = B570Theme.type.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 即時 / 展示 / 訊號中斷 / 連線中 / 已暫停。 */
@Composable
private fun LiveStatusPill(vm: AppViewModel, paused: Boolean) {
    val c = B570Theme.colors
    val st by vm.connectionState.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val s = st
    val text: String
    val color: Color
    val pulsing: Boolean
    when {
        paused -> { text = "已暫停"; color = c.warn; pulsing = false }
        s is ConnectionState.Connected -> if (demo) { text = "展示中"; color = c.accent2; pulsing = true } else { text = "即時"; color = c.ok; pulsing = true }
        s is ConnectionState.LinkLost -> { text = "訊號中斷"; color = c.warn; pulsing = true }
        s.isConnecting -> { text = s.labelZh; color = c.info; pulsing = true }
        else -> { text = "未連線"; color = c.textMuted; pulsing = false }
    }
    StatusPill(text, color, pulsing = pulsing)
}

/** 連線不正常時，說明曲線為什麼停住。 */
@Composable
private fun ConnectionNotice(vm: AppViewModel) {
    val c = B570Theme.colors
    val st by vm.connectionState.collectAsStateWithLifecycle()
    val s = st
    val msg: String? = when {
        s is ConnectionState.LinkLost -> "訊號中斷：曲線停在最後一筆資料，恢復後自動接續"
        s.isConnecting -> "正在連線，資料暫時不會更新"
        !s.isSession -> "尚未連線：目前顯示的是上次記錄的資料"
        else -> null
    }
    val warn = s is ConnectionState.LinkLost
    // 退場動畫期間保留最後的訊息（非 state 暫存，組合期間寫入安全）
    val last = remember { arrayOfNulls<String>(1) }
    if (msg != null) last[0] = msg
    AnimatedVisibility(msg != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val tint = if (warn) c.warn else c.info
        Row(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(B570Theme.radii.s))
                .background(tint.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (warn) Icons.Rounded.Warning else Icons.Rounded.Info, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(msg ?: last[0].orEmpty(), style = B570Theme.type.bodySmall, color = c.textPrimary)
        }
    }
}

/** 車況快照（derivedStateOf 比對，只有變化時重組）。 */
private data class CarStateSnap(
    val motorsOn: Boolean,
    val stop: StopReason,
    val source: ControlSource,
    val seqPlaying: Boolean,
    val lowBattery: Boolean,
    val failsafe: Boolean,
    val paramsDirty: Boolean,
)

/** 即時狀態、馬達、控制來源、編排、失聯保護、電量、未存檔參數、展示資料（自動換行）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarStatePills(vm: AppViewModel, paused: Boolean) {
    val c = B570Theme.colors
    val tState = vm.telemetrySlow.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val snap by remember {
        derivedStateOf {
            tState.value?.let { CarStateSnap(it.motorsOn, it.stopReason, it.source, it.seqPlaying, it.lowBattery, it.failsafe, it.paramsDirty) }
        }
    }
    val s = snap
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LiveStatusPill(vm, paused)
        AnimatedContent(
            targetState = motorText(s),
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(150)) },
            label = "motor",
        ) { text ->
            val on = text == MOTOR_ON
            StatusPill(text, if (on) c.ok else c.textMuted, pulsing = on)
        }
        val pillIn = fadeIn() + expandHorizontally()
        val pillOut = fadeOut() + shrinkHorizontally()
        AnimatedVisibility(s != null && (s.source == ControlSource.APP || s.source == ControlSource.SEQUENCE), enter = pillIn, exit = pillOut) {
            StatusPill(if (s?.source == ControlSource.SEQUENCE) "編排控制" else "手機控制", c.info)
        }
        AnimatedVisibility(s?.seqPlaying == true, enter = pillIn, exit = pillOut) {
            StatusPill("編排播放中", c.accent2, pulsing = true)
        }
        AnimatedVisibility(s?.failsafe == true, enter = pillIn, exit = pillOut) {
            StatusPill("失聯保護", c.warn)
        }
        AnimatedVisibility(s?.lowBattery == true, enter = pillIn, exit = pillOut) {
            StatusPill("電量過低", c.danger, pulsing = true)
        }
        AnimatedVisibility(s?.paramsDirty == true, enter = pillIn, exit = pillOut) {
            StatusPill("參數未儲存", c.warn)
        }
        AnimatedVisibility(demo, enter = pillIn, exit = pillOut) {
            StatusPill("展示資料", c.accent2)
        }
    }
}

private const val MOTOR_ON = "馬達運轉"

private fun motorText(s: CarStateSnap?): String = when {
    s == null -> "等待車子資料"
    s.motorsOn -> MOTOR_ON
    s.stop == StopReason.RUNNING || s.stop == StopReason.UNKNOWN -> "馬達停止"
    else -> "馬達停止（${s.stop.labelZh}）"
}

// ====================================================================== 單欄堆疊（很窄的視窗）

/**
 * 單欄：狀態 → 延遲診斷 → 工具列 → 圖卡 → 即時資料 → 事件紀錄；捲過工具列後右下角出現浮動控制鈕（暫停 / 切換時間範圍）。
 */
@Composable
private fun StackedMonitor(
    vm: AppViewModel,
    model: MonitorViewModel,
    layout: MonitorLayout,
    insets: PaneInsets,
    hz: Float,
    paused: Boolean,
    windowSec: Int,
    expandOpen: Boolean,
    entrance: EntranceTracker,
    openedAtMs: Long,
    onTogglePause: () -> Unit,
    onCycleWindow: () -> Unit,
    toolbar: @Composable (Modifier) -> Unit,
    chartCard: @Composable (ChartDef, Modifier) -> Unit,
) {
    val log by model.log.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // 捲過工具列（狀態 0、延遲診斷 1、工具列 2）後才出現浮動控制鈕
    val showFloating by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    val g = layout.gutter
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = insets.start + g,
                end = insets.end + g,
                top = insets.top,
                bottom = insets.bottom + FloatingClearance,
            ),
        ) {
            item(key = "status", contentType = "status") {
                StatusCard(vm, hz, paused, Modifier.entrance(entrance, "status", 0))
            }
            item(key = "latency", contentType = "latency") {
                LatencyCard(vm, Modifier.padding(top = 12.dp).entrance(entrance, "latency", 1))
            }
            item(key = "toolbar", contentType = "toolbar") {
                toolbar(Modifier.padding(top = 12.dp).entrance(entrance, "toolbar", 2))
            }
            items(ChartDefs.ALL, key = { "chart-${it.kind.name}" }, contentType = { "chart" }) { def ->
                chartCard(def, Modifier.padding(top = CardGap).height(layout.cardHeight).entrance(entrance, def.kind, 3 + def.kind.ordinal))
            }
            statsSection(vm, layout.denseTiles, entrance, firstIndex = 9)
            logSection(log, onClear = { model.clearLog() }, openedAtMs = openedAtMs)
        }

        FloatingControls(
            visible = showFloating && !expandOpen,
            paused = paused,
            windowSec = windowSec,
            onTogglePause = onTogglePause,
            onCycleWindow = onCycleWindow,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = insets.end + g, bottom = insets.bottom),
        )
    }
}

/** 捲過工具列後出現：暫停 / 繼續、切換時間範圍（單欄堆疊時）。 */
@Composable
private fun FloatingControls(
    visible: Boolean,
    paused: Boolean,
    windowSec: Int,
    onTogglePause: () -> Unit,
    onCycleWindow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.6f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.7f),
    ) {
        val tint = if (paused) c.warn else c.accent
        Row(
            Modifier
                .glass(RoundedCornerShape(50), fill = c.surface.copy(alpha = 0.92f), glow = tint.copy(alpha = 0.45f), glowElevation = 14.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(
                if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                if (paused) "繼續" else "暫停",
                onTogglePause,
                tint = tint,
                size = 40.dp,
            )
            Box(
                Modifier
                    .height(40.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = "時間範圍 $windowSec 秒，點一下切換" }
                    .noRippleClickable { haptics.tick(); onCycleWindow() }
                    .padding(start = 12.dp, end = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = windowSec,
                    transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                    label = "win",
                ) { s ->
                    Text("$s 秒", style = B570Theme.type.readoutS, color = c.textPrimary)
                }
            }
        }
    }
}

// ====================================================================== 進場動畫

/** 記錄哪些項目已播過進場動畫，捲回來不再重播；首批依序錯開。 */
@Stable
internal class EntranceTracker {
    private val startMs = SystemClock.uptimeMillis()
    private val seen = HashSet<Any>()

    /** 第一次出現回傳 true。 */
    fun firstTime(key: Any): Boolean = seen.add(key)

    fun delayFor(index: Int): Long =
        (startMs + index.coerceAtMost(10) * 45L - SystemClock.uptimeMillis()).coerceAtLeast(0L)
}

/** 由下往上淡入（彈簧），只在繪製階段讀動畫值。 */
@Composable
internal fun Modifier.entrance(tracker: EntranceTracker, key: Any, index: Int): Modifier {
    val anim = remember { Animatable(if (tracker.firstTime(key)) 0f else 1f) }
    LaunchedEffect(anim) {
        if (anim.value < 1f) {
            delay(tracker.delayFor(index))
            anim.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 260f))
        }
    }
    return this.graphicsLayer {
        val p = anim.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 28.dp.toPx()
    }
}
