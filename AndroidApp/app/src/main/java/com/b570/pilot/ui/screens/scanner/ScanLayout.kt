package com.b570.pilot.ui.screens.scanner

import kotlin.math.max
import kotlin.math.min

/** 貼邊控制離安全區的距離（螢幕圓角不在 insets 裡，左右至少 12 dp）。 */
internal const val SCAN_EDGE_X = 16f
internal const val SCAN_EDGE_Y = 12f

/** 右側控制列（手電筒 / 縮放 / 對焦）寬。 */
internal const val SCAN_RAIL_W = 60f

/** 左上角「關閉 + 標題」列高。 */
internal const val SCAN_CLOSE_BAR_H = 44f

/** 掃描框與左右兩側控制的間距。 */
internal const val SCAN_SIDE_GAP = 16f

/** 掃描框與下方提示膠囊的間距。 */
internal const val SCAN_PILL_GAP = 10f

private const val MIN_FRAME = 96f

/**
 * 掃描頁（橫向）的幾何配置，單位一律是 dp（純數值，方便單元測試）。
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ [✕] 掃描配對        ┌──────────┐      [手電筒] │
 * │ ┌ 說明 ┐            │  掃描框   │       ┃縮放┃ │
 * │ └──────┘            └──────────┘       ┃    ┃ │
 * │ [改用手動輸入]      ( 提示膠囊 )        [對焦] │
 * └──────────────────────────────────────────────┘
 * ```
 * 掃描框置中於安全區（左右翻轉時瀏海換邊也跟著對稱），框的下方保留一行提示膠囊；
 * 框的大小同時受高度、寬度（兩側要放得下右側控制列）與上限約束。
 */
internal data class ScanLayout(
    val frameSide: Float,
    val frameCx: Float,
    val frameCy: Float,
    val pillTop: Float,
    val pillWidth: Float,
    val pillHeight: Float,
    val contentLeft: Float,
    val contentTop: Float,
    val contentRight: Float,
    val contentBottom: Float,
    /** 左側說明欄可用寬度（contentLeft → 掃描框左緣 − 間距）。 */
    val panelWidth: Float,
) {
    val frameLeft: Float get() = frameCx - frameSide / 2f
    val frameRight: Float get() = frameCx + frameSide / 2f
    val frameTop: Float get() = frameCy - frameSide / 2f
    val frameBottom: Float get() = frameCy + frameSide / 2f
    val railLeft: Float get() = contentRight - SCAN_RAIL_W
    val contentHeight: Float get() = contentBottom - contentTop

    /** 左側欄太窄（分割畫面）時只放按鈕，不放說明卡。 */
    val compactPanel: Boolean get() = panelWidth < 150f
}

/** 提示膠囊（一行）的高度，跟著字級放大。 */
internal fun hintPillHeight(fontScale: Float): Float = 18f + 19f * max(1f, fontScale)

/**
 * @param width 整個畫面寬（含安全區）
 * @param insetLeft 安全區左緣（瀏海 / 系統列，絕對方向）
 */
internal fun scanLayoutOf(
    width: Float,
    height: Float,
    insetLeft: Float,
    insetTop: Float,
    insetRight: Float,
    insetBottom: Float,
    fontScale: Float,
): ScanLayout {
    val l = insetLeft + SCAN_EDGE_X
    val r = max(l, width - insetRight - SCAN_EDGE_X)
    val t = insetTop + SCAN_EDGE_Y
    val b = max(t, height - insetBottom - SCAN_EDGE_Y)
    val availW = r - l
    val availH = b - t
    val pillH = hintPillHeight(fontScale)
    val cap = if (availH >= 600f) 420f else 340f
    // 框置中，右側控制列要完整留在框外 → 兩側各保留 (控制列 + 間距)
    val byWidth = availW - 2f * (SCAN_RAIL_W + SCAN_SIDE_GAP)
    val side = minOf(availH - pillH - SCAN_PILL_GAP, availW * 0.42f, cap, byWidth).coerceAtLeast(MIN_FRAME)
    val cx = (l + r) / 2f
    val cy = t + max(side, availH - pillH - SCAN_PILL_GAP) / 2f
    return ScanLayout(
        frameSide = side,
        frameCx = cx,
        frameCy = cy,
        pillTop = cy + side / 2f + SCAN_PILL_GAP,
        pillWidth = min(side + 32f, availW),
        pillHeight = pillH,
        contentLeft = l,
        contentTop = t,
        contentRight = r,
        contentBottom = b,
        panelWidth = max(0f, cx - side / 2f - SCAN_SIDE_GAP - l),
    )
}
