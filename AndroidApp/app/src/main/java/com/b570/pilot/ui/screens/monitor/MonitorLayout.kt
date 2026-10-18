package com.b570.pilot.ui.screens.monitor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp

/*
 * 監測頁橫向版面（純函式，有單元測試 MonitorLayoutTest）。
 *
 *  ┌ 導覽軌 ┬──────────────── 狀態條 ────────────────────────────┐
 *  │        │ 左欄（可捲動）        │ 工具列：時間範圍 │ 暫停 │ 匯出 CSV │
 *  │        │  即時監測（車況膠囊） ├──────────────────────────────┤
 *  │        │  即時資料（2 欄數據磚）│  圖卡（寬：2 欄、窄：1 欄）       │
 *  │        │  事件紀錄             │  卡高依可用高度：手機一列剛好一屏   │
 *  └────────┴──────────────────────┴──────────────────────────────┘
 *  分割畫面等很窄的視窗（左右兩欄放不下）改成單欄堆疊，加回浮動控制鈕。
 */

/** 圖表區工具列的排法。 */
internal enum class ToolbarMode {
    /** 時間範圍 + 圓形圖示鈕（暫停 / 匯出）。 */
    NARROW,

    /** 時間範圍 + 文字按鈕。 */
    MEDIUM,

    /** 標題 / 時間範圍說明 + 時間範圍 + 文字按鈕。 */
    WIDE,
}

/**
 * 依「內容可用區」（已扣掉導覽軌、狀態條、安全區）算出的版面。
 * @param stacked 單欄堆疊（分割畫面等很窄的視窗）；false = 左欄 + 圖表區
 * @param gutter 外側與兩欄之間的留白
 * @param sideWidth 左欄寬（stacked 時 = 內容寬）
 * @param chartPaneWidth 圖表區寬（stacked 時 = 內容寬）
 * @param chartColumns 圖卡欄數（1 或 2）
 * @param cardHeight 圖卡固定高度（同列對齊；手機剛好一屏一列、平板一屏兩列）
 * @param denseCards 圖卡緊湊（省略副標、較小圖示與留白）
 * @param denseTiles 數據磚緊湊（較小數值字級與留白）
 * @param snapCards 圖卡剛好填滿可見高度：快速滑動時對齊到整張卡（像翻儀表頁）
 */
@Immutable
internal data class MonitorLayout(
    val stacked: Boolean,
    val gutter: Dp,
    val sideWidth: Dp,
    val chartPaneWidth: Dp,
    val chartColumns: Int,
    val toolbar: ToolbarMode,
    val cardHeight: Dp,
    val denseCards: Boolean,
    val denseTiles: Boolean,
    val snapCards: Boolean,
)

/** 工具列高（SegmentedSelector 44 dp）。 */
internal val ToolbarHeight = 44.dp

/** 工具列與圖卡之間。 */
internal val ToolbarGap = 10.dp

/** 圖卡之間。 */
internal val CardGap = 12.dp

/** 數據磚之間。 */
internal val TileGap = 8.dp

private val MinChartPane = 260.dp
private const val SIDE_FRACTION = 0.36f

/**
 * @param width 內容可用寬（dp）
 * @param height 內容可用高（dp）
 * @param fontScale 系統字級（≥ 1.15 時左欄加寬、兩欄門檻提高，避免切字）
 */
internal fun monitorLayoutOf(width: Dp, height: Dp, fontScale: Float = 1f): MonitorLayout {
    val big = fontScale >= 1.15f
    val gutter = if (width < 700.dp) 12.dp else 16.dp
    val minSide = if (big) 248.dp else 228.dp
    val maxSide = if (big) 340.dp else 320.dp
    val twoPaneInner = width - gutter * 3
    val dense = height < 380.dp

    if (twoPaneInner < minSide + MinChartPane) {
        // 單欄：狀態 → 工具列 → 圖卡 → 數據磚 → 事件紀錄
        val inner = (width - gutter * 2).coerceAtLeast(0.dp)
        return MonitorLayout(
            stacked = true,
            gutter = gutter,
            sideWidth = inner,
            chartPaneWidth = inner,
            chartColumns = 1,
            toolbar = toolbarModeOf(inner, big),
            cardHeight = (height * 0.85f).coerceIn(if (big) 240.dp else 220.dp, 360.dp),
            denseCards = dense,
            denseTiles = (inner - TileGap) / 2 < 130.dp,
            snapCards = false,
        )
    }

    val side = (twoPaneInner * SIDE_FRACTION).coerceIn(minSide, maxSide).coerceAtMost(twoPaneInner - MinChartPane)
    val right = twoPaneInner - side
    val columns = if (right >= (if (big) 520.dp else 480.dp)) 2 else 1

    // 卡高：可見高度剛好放一列（手機）或兩列（平板，每列至少 260 dp）
    val viewport = (height - ToolbarHeight - ToolbarGap).coerceAtLeast(0.dp)
    val rows = if ((viewport - CardGap) / 2 >= 260.dp) 2 else 1
    val fit = (viewport - CardGap * (rows - 1)) / rows
    val minCard = (if (dense) 200.dp else 240.dp) + (if (big) 20.dp else 0.dp)
    return MonitorLayout(
        stacked = false,
        gutter = gutter,
        sideWidth = side,
        chartPaneWidth = right,
        chartColumns = columns,
        toolbar = toolbarModeOf(right, big),
        cardHeight = fit.coerceIn(minCard, 420.dp),
        denseCards = dense,
        denseTiles = (side - TileGap) / 2 < 130.dp,
        snapCards = fit in minCard..420.dp,
    )
}

/** 工具列可用寬 → 排法（字級大時文字按鈕需要更寬）。 */
internal fun toolbarModeOf(width: Dp, big: Boolean): ToolbarMode = when {
    width >= 720.dp -> ToolbarMode.WIDE
    width >= (if (big) 440.dp else 400.dp) -> ToolbarMode.MEDIUM
    else -> ToolbarMode.NARROW
}
