package com.b570.pilot.ui.screens.tune

/**
 * 整定頁橫向版面的尺寸決策（純函式，單位 dp，有單元測試 `TuneLayoutTest`）。
 *
 * 輸入是**扣掉導覽軌、狀態條、安全區與左右留白後**的實際可用區域：
 * | 視窗（dp） | 可用區（約） | 版面 |
 * |---|---|---|
 * | 640 × 320 | 536 × 264 | 左右分欄（左 228）、參數單欄 + 緊湊列、曲線 56 dp、群組列跟著捲動 |
 * | 780 × 360 | 676 × 304 | 左右分欄（左 257）、參數單欄、曲線 91 dp、群組列固定 |
 * | 915 × 412 | 811 × 356 | 左右分欄（左 308）、參數單欄、曲線 107 dp |
 * | 1280 × 800 | 1176 × 744 | 左右分欄（左 420）、**參數兩欄**、曲線 220 dp |
 * | 分割畫面（寬 < 500） | | 單欄（曲線、動作、參數、設定檔依序捲動） |
 */
internal data class TuneLayout(
    /** true = 左（曲線 + 動作 + 設定檔）右（參數群組）分欄；false = 單欄捲動。 */
    val twoPane: Boolean,
    /** 左欄寬（分欄時）；單欄時 = 可用寬。 */
    val sideWidth: Float,
    /** 參數區（右欄）寬。 */
    val mainWidth: Float,
    /** 群組卡片內參數排幾欄（1 或 2）。 */
    val paramColumns: Int,
    /** 參數列太窄：說明文字放到標題列下方整行顯示。 */
    val compactRows: Boolean,
    /** 側欄窄：設定檔改直列、我的設定檔省略圖示。 */
    val narrowSide: Boolean,
    /** 即時曲線高度。 */
    val chartHeight: Float,
    /** 群組快速跳轉列固定在參數區上緣（太矮時改成清單第一項，跟著捲動）。 */
    val stickyGroupNav: Boolean,
    /** 底部浮動列用緊湊按鈕。 */
    val compactDock: Boolean,
)

internal const val TUNE_PANE_GAP = 12f
internal const val TUNE_CARD_PAD = 16f
internal const val TUNE_COL_GAP = 20f

private const val MIN_TWO_PANE = 500f
private const val SIDE_MIN = 228f
private const val SIDE_MAX = 420f
private const val SIDE_RATIO = 0.38f
private const val TWO_COL_MIN = 600f
private const val COMPACT_ROW_BELOW = 340f
private const val NARROW_SIDE_BELOW = 300f
private const val SHORT_HEIGHT = 290f
private const val STICKY_NAV_MIN_HEIGHT = 280f

internal fun tuneLayoutOf(width: Float, height: Float): TuneLayout {
    val twoPane = width >= MIN_TWO_PANE
    val side = if (twoPane) (width * SIDE_RATIO).coerceIn(SIDE_MIN, SIDE_MAX) else width
    val main = if (twoPane) width - side - TUNE_PANE_GAP else width
    val columns = if (main >= TWO_COL_MIN) 2 else 1
    val cell = (main - 2 * TUNE_CARD_PAD - (columns - 1) * TUNE_COL_GAP) / columns
    val chart = if (height < SHORT_HEIGHT) 56f else (height * 0.3f).coerceIn(80f, 220f)
    return TuneLayout(
        twoPane = twoPane,
        sideWidth = side,
        mainWidth = main,
        paramColumns = columns,
        compactRows = cell < COMPACT_ROW_BELOW,
        narrowSide = side < NARROW_SIDE_BELOW,
        chartHeight = chart,
        stickyGroupNav = twoPane && height >= STICKY_NAV_MIN_HEIGHT,
        compactDock = main < 360f,
    )
}
