package com.b570.pilot.ui.screens.choreo

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp

// 橫向版面計算（純函式，有單元測試 ChoreoLayoutTest）。
// 輸入一律是「內容寬」= 畫面寬 − LocalScreenPadding 左右 − 兩側 16 dp 留白。
// 典型內容寬：小手機 640 → 528、一般手機 780 → 668、915 → 803、平板 1280 → 1168。

/** 卡片間距。 */
internal val CardGap = 12.dp

/** 動作卡最小寬度（決定動作庫欄數：3–6 欄）。 */
internal val PresetMinWidth = 148.dp

/** 動作庫與側欄之間的距離。 */
internal val PaneGap = 16.dp

/** 內容寬 ≥ 這個值（平板）時「我的編排」放右側側欄，否則放在動作庫下方。 */
internal val SidePaneMinContent = 1000.dp

/** 編輯器左右兩欄之間的距離。 */
internal val EditorGap = 16.dp

/**
 * 編排首頁的格狀版面。
 * @property side 「我的編排」放右側側欄（平板）
 * @property cols 動作庫欄數（3–6）
 * @property seqPerLine 「我的編排」在動作庫下方時一列幾張
 * @property headerInline 標題與車況橫幅並排
 */
@Immutable
internal data class HomeLayout(
    val side: Boolean,
    val sideW: Dp,
    val gridW: Dp,
    val cols: Int,
    val seqPerLine: Int,
    val headerInline: Boolean,
) {
    /** LazyVerticalGrid 的格子數：動作卡佔 [seqPerLine] 格、編排列佔 [cols] 格，兩種都能整除。 */
    val units: Int get() = cols * seqPerLine

    /** 一張動作卡的寬度。 */
    val cardW: Dp get() = (gridW - CardGap * (cols - 1)) / cols

    /** 動作卡路徑縮圖的高度。 */
    val previewH: Dp get() = (cardW * 0.56f).coerceIn(72.dp, 116.dp)
}

internal fun homeLayoutFor(contentW: Dp): HomeLayout {
    val w = contentW.coerceAtLeast(240.dp)
    val side = w >= SidePaneMinContent
    val sideW = if (side) (w * 0.3f).coerceIn(320.dp, 400.dp) else 0.dp
    val gridW = if (side) w - sideW - PaneGap else w
    val cols = ((gridW + CardGap) / (PresetMinWidth + CardGap)).toInt().coerceIn(3, 6)
    val seqPerLine = when {
        side -> 1
        w >= 600.dp -> if (cols >= 6) 3 else 2
        else -> 1
    }
    return HomeLayout(side, sideW, gridW, cols, seqPerLine, headerInline = gridW >= 500.dp)
}

/**
 * 編輯器左右分欄。
 * @property leftW 步驟時間軸寬
 * @property rightW 預覽 / 播放欄寬
 * @property wideCard 步驟卡夠寬：動作 5 個一列、滑桿兩欄
 */
@Immutable
internal data class EditorLayout(val leftW: Dp, val rightW: Dp, val wideCard: Boolean)

internal fun editorLayoutFor(contentW: Dp): EditorLayout {
    val w = contentW.coerceAtLeast(360.dp)
    val leftW = (w * 0.52f).coerceIn(272.dp, 620.dp).coerceAtMost(w - 220.dp - EditorGap)
    // 步驟卡 = 左欄扣掉時間軸節點 40 dp
    return EditorLayout(leftW, w - leftW - EditorGap, wideCard = leftW - 40.dp >= 520.dp)
}
