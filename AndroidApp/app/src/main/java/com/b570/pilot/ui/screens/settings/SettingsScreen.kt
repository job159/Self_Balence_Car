package com.b570.pilot.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.LocalScreenPadding
import com.b570.pilot.ui.theme.B570Theme

/**
 * 「設定」分頁（橫向主從式）：左 = 分類面板（車輛 Wi-Fi、連線方式（直連 / 熱點模式）、操控、外觀、遙測、展示模式、記住的車子、關於 + 中斷連線），
 * 右 = 該分類的詳情（可捲動，從頂部狀態條的玻璃底下穿過）。
 *
 * 尺寸：
 * - 640 × 320（COMPACT）：分類面板 168 dp + 詳情 348 dp（單欄卡片，等於直向手機寬）。
 * - 780 × 360 ~ 915 × 412（MEDIUM）：分類面板 200–241 dp + 詳情 456–550 dp。
 * - 1280 × 800（EXPANDED）：分類面板 264 dp + 詳情 884 dp，詳情內改左右兩欄（[WidePaneMin]）。
 * - 分類面板與詳情各自捲動（字級 1.3 倍也不切字）；面板最下面是「中斷連線」。
 * - 內容區 < [SINGLE_PANE_MAX_DP]（分割畫面、極窄視窗）：單欄，選分類後進詳情，返回鍵 / ← 回到清單。
 * 寬度決策是純函式 [settingsPanesOf]（有單元測試）。
 *
 * 效能：分類面板只收集低頻資料；各分類自己收集需要的資料流，遙測只在小葉節點以 derivedStateOf 讀布林值。
 * 每個分類的捲動位置與展開狀態以 SaveableStateHolder 保留（切換分類、切換分頁回來都還在）。
 */
@Composable
fun SettingsScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier) {
    val tracker = remember { EntranceTracker() }
    val holder = rememberSaveableStateHolder()
    var selectedName by rememberSaveable { mutableStateOf(SettingsCategory.WIFI.name) }
    val selected = SettingsCategory.fromName(selectedName)
    var detailOpen by rememberSaveable { mutableStateOf(false) }   // 只有單欄模式使用

    val pad = LocalScreenPadding.current
    val ld = LocalLayoutDirection.current
    val gutter = B570Theme.spacing.screen

    BoxWithConstraints(modifier) {
        val start = pad.calculateStartPadding(ld) + gutter
        val end = pad.calculateEndPadding(ld) + gutter
        val top = pad.calculateTopPadding()
        val bottom = pad.calculateBottomPadding()
        val panes = settingsPanesOf((maxWidth - start - end).value)

        if (!panes.singlePane) {
            val listW = panes.listWidth.dp
            val gap = panes.gap.dp
            Row(Modifier.fillMaxSize()) {
                CategoryPanel(
                    vm = vm,
                    nav = nav,
                    selected = selected,
                    onSelect = { selectedName = it.name },
                    tracker = tracker,
                    modifier = Modifier
                        .padding(start = start, top = top + 4.dp, bottom = bottom)
                        .width(listW)
                        .fillMaxHeight(),
                )
                Spacer(Modifier.width(gap))
                DetailHost(
                    vm = vm,
                    nav = nav,
                    category = selected,
                    holder = holder,
                    tracker = tracker,
                    contentPadding = PaddingValues(start = DETAIL_START_PAD_DP.dp, end = end, top = top + 4.dp, bottom = bottom + 8.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            BackHandler(enabled = detailOpen && nav.overlay == null) { detailOpen = false }
            AnimatedContent(
                targetState = detailOpen,
                transitionSpec = {
                    val fwd = targetState
                    (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)) { w -> if (fwd) w / 4 else -w / 4 } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally(tween(200)) { w -> if (fwd) -w / 5 else w / 5 } + fadeOut(tween(150)))
                },
                label = "settingsSingle",
            ) { open ->
                if (!open) {
                    CategoryPanel(
                        vm = vm,
                        nav = nav,
                        selected = null,
                        onSelect = {
                            selectedName = it.name
                            detailOpen = true
                        },
                        tracker = tracker,
                        modifier = Modifier
                            .padding(start = start, end = end, top = top + 4.dp, bottom = bottom)
                            .fillMaxSize(),
                    )
                } else {
                    holder.SaveableStateProvider(selected.name) {
                        DetailPane(
                            vm = vm,
                            nav = nav,
                            category = selected,
                            tracker = tracker,
                            contentPadding = PaddingValues(start = start, end = end, top = top + 4.dp, bottom = bottom + 8.dp),
                            onBack = { detailOpen = false },
                        )
                    }
                }
            }
        }
    }
}

/** 右側詳情：依分類順序垂直滑動 + 淡入（往下的分類由下往上進場，與導覽軌一致）。 */
@Composable
private fun DetailHost(
    vm: AppViewModel,
    nav: AppNavigator,
    category: SettingsCategory,
    holder: SaveableStateHolder,
    tracker: EntranceTracker,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = category,
        modifier = modifier,
        transitionSpec = {
            val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
            (slideInVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) { h -> dir * h / 6 } + fadeIn(tween(240)))
                .togetherWith(slideOutVertically(tween(220)) { h -> -dir * h / 8 } + fadeOut(tween(160)))
        },
        label = "settingsPane",
    ) { cat ->
        holder.SaveableStateProvider(cat.name) {
            DetailPane(vm, nav, cat, tracker, contentPadding)
        }
    }
}

/**
 * 一個分類的詳情（可捲動）。用一般 Column + verticalScroll（不是 Lazy）：分類內容不多，
 * 而且區塊內的對話框（修改 Wi-Fi、恢復原廠、重新命名）不會因為捲出畫面而被回收。
 */
@Composable
private fun DetailPane(
    vm: AppViewModel,
    nav: AppNavigator,
    category: SettingsCategory,
    tracker: EntranceTracker,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)? = null,
) {
    val ld = LocalLayoutDirection.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inner: Dp = maxWidth - contentPadding.calculateStartPadding(ld) - contentPadding.calculateEndPadding(ld)
        val wide = inner >= WidePaneMin
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (onBack != null) BackRow(onBack)
            // 只有第一次進入設定頁時顯示的那個分類有交錯進場；之後切換分類由 AnimatedContent 負責
            val entrance = Modifier.staggeredEntrance(tracker, "pane", 2)
            when (category) {
                SettingsCategory.WIFI -> CarWifiSection(vm, wide, entrance)
                SettingsCategory.LINK -> LinkModeSection(vm, wide, entrance)
                SettingsCategory.CONTROL -> ControlSection(vm, wide, entrance)
                SettingsCategory.APPEARANCE -> AppearanceSection(vm, wide, entrance)
                SettingsCategory.TELEMETRY -> TelemetrySection(vm, wide, entrance)
                SettingsCategory.DEMO -> DemoSection(vm, nav, wide, entrance)
                SettingsCategory.CARS -> CarsSection(vm, wide, entrance)
                SettingsCategory.ABOUT -> AboutSection(vm, wide, entrance)
            }
        }
    }
}

/** 單欄模式詳情頂端的返回列。 */
@Composable
private fun BackRow(onBack: () -> Unit) {
    val c = B570Theme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回設定分類", onBack, size = 36.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            Text("設定", style = B570Theme.type.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
