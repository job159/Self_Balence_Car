package com.b570.pilot.ui.screens.connect

/**
 * 連線頁（橫向）左右分欄：左 = 陀螺儀主視覺 + 品牌，右 = 操作台（動作 / 連線步驟 / 記住的車子…）。
 * 單位 dp（純數值，方便單元測試）。
 *
 * | 可用寬（扣安全區） | 外距 | 間距 | 操作台 | 主視覺 |
 * |---|---|---|---|---|
 * | 640（小手機）   | 16 | 16 | 340 | 252 |
 * | 780 ~ 915     | 16 | 16 | 419 ~ 494 | 313 ~ 373 |
 * | 1280（平板）   | 32 | 32 | 560（上限） | 624 |
 * | < 560（分割畫面） | 不分欄：單欄清單，主視覺縮小放最上面 |
 *
 * @property split false = 單欄
 */
internal data class ConnectPanes(
    val split: Boolean,
    val sideMargin: Float,
    val heroWidth: Float,
    val gap: Float,
    val consoleWidth: Float,
)

internal const val CONSOLE_MIN = 300f
internal const val CONSOLE_MAX = 560f
private const val HERO_MIN = 200f

internal fun connectPanesOf(availWidth: Float): ConnectPanes {
    val side = if (availWidth >= 1000f) 32f else 16f
    val inner = availWidth - side * 2f
    val gap = if (inner >= 900f) 32f else 16f
    val console = (inner * 0.56f).coerceIn(CONSOLE_MIN, CONSOLE_MAX)
    val hero = inner - gap - console
    return if (availWidth >= 560f && hero >= HERO_MIN) {
        ConnectPanes(true, side, hero, gap, console)
    } else {
        ConnectPanes(false, 16f, 0f, 0f, inner.coerceAtMost(520f))
    }
}
