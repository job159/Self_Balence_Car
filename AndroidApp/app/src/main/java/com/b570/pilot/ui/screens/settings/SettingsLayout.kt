package com.b570.pilot.ui.screens.settings

/*
 * 設定頁的版面決策（純函式、單位 dp 的 Float，方便單元測試 SettingsLayoutTest）。
 *
 * contentWidth = 畫面寬 − LocalScreenPadding 左右（導覽軌 + 瀏海）− 左右留白 16 dp。
 * 目標尺寸（橫向，扣掉 80 dp 導覽軌與 2 × 16 dp 留白）：
 *   640 → 528：分類 168 + 詳情 348（單欄卡片）
 *   780 → 668：分類 200 + 詳情 456
 *   915 → 803：分類 241 + 詳情 550
 *  1280 → 1168：分類 264 + 詳情 884（詳情內左右兩欄）
 * 分割畫面等 contentWidth < 480：單欄（先選分類、再進詳情）。
 */

/** 內容區窄於這個寬度（dp）改成單欄。 */
internal const val SINGLE_PANE_MAX_DP = 480f

/** 分類面板寬度上下限（dp）：最窄時字級 1.3 倍的「車輛 Wi-Fi」仍放得下。 */
internal const val LIST_MIN_DP = 168f
internal const val LIST_MAX_DP = 264f

/** 詳情區（扣掉自己的左內距）達到這個寬度（dp）就在區塊內改左右兩欄。 */
internal const val WIDE_PANE_MIN_DP = 600f

/** 詳情區自己的左內距（讓卡片光暈不貼著分類面板）。 */
internal const val DETAIL_START_PAD_DP = 4f

/**
 * @property singlePane 單欄模式（清單 → 詳情）
 * @property listWidth 分類面板寬（單欄時 = 全寬）
 * @property gap 分類面板與詳情區之間的空隙（不含 [DETAIL_START_PAD_DP]）
 * @property detailWidth 詳情內容可用寬度（已扣掉 [DETAIL_START_PAD_DP]）
 * @property detailTwoColumn 詳情區塊內是否左右兩欄
 */
internal data class SettingsPanes(
    val singlePane: Boolean,
    val listWidth: Float,
    val gap: Float,
    val detailWidth: Float,
    val detailTwoColumn: Boolean,
)

internal fun settingsPanesOf(contentWidth: Float): SettingsPanes {
    val w = contentWidth.coerceAtLeast(0f)
    if (w < SINGLE_PANE_MAX_DP) {
        return SettingsPanes(singlePane = true, listWidth = w, gap = 0f, detailWidth = w, detailTwoColumn = false)
    }
    val list = (w * 0.3f).coerceIn(LIST_MIN_DP, LIST_MAX_DP)
    val gap = if (w >= 900f) 16f else 8f
    val detail = w - list - gap - DETAIL_START_PAD_DP
    return SettingsPanes(singlePane = false, listWidth = list, gap = gap, detailWidth = detail, detailTwoColumn = detail >= WIDE_PANE_MIN_DP)
}
