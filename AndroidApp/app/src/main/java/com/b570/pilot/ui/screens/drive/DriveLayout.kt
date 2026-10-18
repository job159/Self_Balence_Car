package com.b570.pilot.ui.screens.drive

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.JoystickMode

/** 遙控器版面級距（依駕駛畫面實際可用區域，不是整個視窗）。 */
internal enum class RcSize { COMPACT, MEDIUM, EXPANDED }

/** 小於這個大小（極窄的分割畫面）改成可捲動，控制不會被壓扁。 */
internal val RcMinWidth = 520.dp
internal val RcMinHeight = 260.dp

/** 360° 模式的中控台至少留這麼寬（儀表 + 向量讀數），按鍵區（菱形 + 檔位翹板）用剩下的。 */
internal val MinConsole360 = 200.dp

/** 菱形動作鍵直徑至少這麼大，檔位翹板才搬進按鍵區（否則留在上緣列）。 */
internal val MinClusterButton = 44.dp

/** 左右手區放什麼。 */
internal enum class ZoneKind {
    /** 360° 類比搖桿。 */
    STICK_360,

    /** 360° 模式另一手：A/B/X/Y 菱形快捷動作 + 檔位翹板。 */
    CLUSTER,

    /** 雙搖桿左手：直立油門。 */
    THROTTLE,

    /** 雙搖桿右手：橫向方向。 */
    STEERING,

    /** 體感：左「按住駕駛」。 */
    PAD_LEFT,

    /** 體感：右「按住駕駛」。 */
    PAD_RIGHT,
}

/** 左手區（360° 模式 [stickRight] = 搖桿在右、按鍵在左）。 */
internal fun leftZoneOf(mode: JoystickMode, stickRight: Boolean): ZoneKind = when (mode) {
    JoystickMode.SINGLE -> if (stickRight) ZoneKind.CLUSTER else ZoneKind.STICK_360
    JoystickMode.DUAL -> ZoneKind.THROTTLE
    JoystickMode.TILT -> ZoneKind.PAD_LEFT
}

/** 右手區。 */
internal fun rightZoneOf(mode: JoystickMode, stickRight: Boolean): ZoneKind = when (mode) {
    JoystickMode.SINGLE -> if (stickRight) ZoneKind.STICK_360 else ZoneKind.CLUSTER
    JoystickMode.DUAL -> ZoneKind.STEERING
    JoystickMode.TILT -> ZoneKind.PAD_RIGHT
}

/**
 * 橫向遙控器的版面尺寸（純函式，單元測試驗證 640 × 320 / 780 × 360 / 915 × 412 / 1280 × 800）：
 *
 * ```
 * ┌ edge ─────────────────────────────────────────────────────────── edge ┐ top
 * │ [≡] 車名 · 連線 · 馬達 · 電量 · 訊號 · 延遲 · 品質  [搖桿在左] [編排] [檔位] │ barH
 * │ ╭ 啟動平衡 / 停止 ╮    [ 360° 搖桿 | 雙搖桿 | 體感 ]     ╭ 緊急停止 ╮ │ shoulderH
 * │ ┌ 左手區 ┐ colGap ┌─── 中控台（姿態 + 車速）───┐ colGap ┌ 右手區 ┐ │
 * │ │ 360° 搖桿 │       │  向量讀數 / 提示 / 快捷 slotH│        │ ABXY + 檔│ │ lowerH
 * └───────────────────────────────────────────────────────────────────────┘ bottom
 * ```
 * - 360°：搖桿區 [sideW]（搖桿 ≤ [stick360]）+ 按鍵區 [clusterW]（菱形 + 直立檔位翹板，[gearInCluster]）；可左右對調。
 * - 雙搖桿：兩邊都是 [sideW]。
 * - 體感：左右兩區改成較窄的「按住駕駛」按鈕（[padW]），中控台多放一個水平儀。
 */
@Immutable
internal data class RcMetrics(
    val size: RcSize,
    /** 左右留白（安全區之內；螢幕圓角不在 insets 裡，貼邊控制至少 12 dp）。 */
    val edge: Dp,
    val top: Dp,
    val bottom: Dp,
    /** 上緣 HUD 列高。 */
    val barH: Dp,
    val barGap: Dp,
    /** 肩鍵（啟動平衡 / 緊急停止）高與寬。 */
    val shoulderH: Dp,
    val shoulderW: Dp,
    val lowerGap: Dp,
    /** 左右手區 / 中控台之間的間距。 */
    val colGap: Dp,
    /** 搖桿區寬（360° 搖桿、雙搖桿）。 */
    val sideW: Dp,
    /** 體感模式「按住駕駛」按鈕寬。 */
    val padW: Dp,
    /** 360° 模式按鍵區寬（菱形動作鍵 + 檔位翹板）。 */
    val clusterW: Dp,
    /** 360° 模式檔位翹板放在按鍵區（菱形旁、靠中控台那側）；false = 太窄，留在上緣列。 */
    val gearInCluster: Boolean,
    /** 按鍵區直立檔位翹板寬與它和菱形的間距。 */
    val rockerW: Dp,
    val rockerGap: Dp,
    /** 下半部（搖桿 + 中控台）高。 */
    val lowerH: Dp,
    /** 雙搖桿最大邊長（平板上不要大到拇指推不到底）。 */
    val stickMax: Dp,
    /** 360° 搖桿直徑上限：手機約 185–240 dp、平板 300 dp。 */
    val stick360: Dp,
    /** 中控台下方提示 / 快捷動作 / 向量讀數列高。 */
    val slotH: Dp,
    /** 版面寬（≥ [RcMinWidth]）。 */
    val width: Dp,
) {
    val compact: Boolean get() = size == RcSize.COMPACT
    val expanded: Boolean get() = size == RcSize.EXPANDED

    /** 某個手區的寬。 */
    fun zoneWidth(kind: ZoneKind): Dp = when (kind) {
        ZoneKind.PAD_LEFT, ZoneKind.PAD_RIGHT -> padW
        ZoneKind.CLUSTER -> clusterW
        ZoneKind.STICK_360, ZoneKind.THROTTLE, ZoneKind.STEERING -> sideW
    }

    /** 該模式的中控台寬（與 360° 搖桿在左或右無關）。 */
    fun consoleWidth(mode: JoystickMode): Dp =
        width - edge * 2 - colGap * 2 - zoneWidth(leftZoneOf(mode, false)) - zoneWidth(rightZoneOf(mode, false))

    /** 按鍵區的菱形動作鍵可用寬（扣掉檔位翹板）。 */
    val clusterPadWidth: Dp get() = if (gearInCluster) clusterW - rockerW - rockerGap else clusterW

    /** 肩鍵列中間（模式選擇）的寬。 */
    val shoulderCenterWidth: Dp get() = width - edge * 2 - shoulderW * 2 - ShoulderInnerGap * 2
}

/** 肩鍵與中間模式選擇的距離。 */
internal val ShoulderInnerGap = 8.dp

internal fun rcMetricsOf(width: Dp, height: Dp): RcMetrics {
    val w = maxOf(width, RcMinWidth)
    val h = maxOf(height, RcMinHeight)
    val size = when {
        h >= 600.dp && w >= 900.dp -> RcSize.EXPANDED
        h < 360.dp || w < 700.dp -> RcSize.COMPACT
        else -> RcSize.MEDIUM
    }
    fun pick(compact: Dp, medium: Dp, expanded: Dp): Dp = when (size) {
        RcSize.COMPACT -> compact
        RcSize.MEDIUM -> medium
        RcSize.EXPANDED -> expanded
    }
    val edge = pick(12.dp, 16.dp, 24.dp)
    val top = pick(6.dp, 8.dp, 16.dp)
    val bottom = pick(8.dp, 12.dp, 24.dp)
    val barH = pick(44.dp, 44.dp, 48.dp)
    val barGap = pick(4.dp, 6.dp, 12.dp)
    val shoulderH = pick(46.dp, 52.dp, 60.dp)
    val lowerGap = pick(8.dp, 10.dp, 20.dp)
    val colGap = pick(8.dp, 12.dp, 24.dp)
    val sideW = (w * 0.29f).coerceIn(150.dp, 340.dp)
    val lowerH = h - top - barH - barGap - shoulderH - lowerGap - bottom
    val stickMax = pick(240.dp, 260.dp, 300.dp)

    // 360° 按鍵區：菱形（目標直徑）+ 直立檔位翹板；中控台至少留 MinConsole360
    val rockerW = pick(52.dp, 60.dp, 64.dp)
    val rockerGap = pick(6.dp, 8.dp, 12.dp)
    val wantButton = pick(52.dp, 60.dp, 72.dp)
    val room = w - edge * 2 - colGap * 2 - sideW - MinConsole360
    val withGear = minOf(ActionPadGeometry.width(wantButton) + rockerGap + rockerW, room)
    val gearFits = (withGear - rockerGap - rockerW) / (3f + 2f * ActionPadGeometry.GAP_FACTOR) >= MinClusterButton
    val clusterW = if (gearFits) withGear else sideW

    return RcMetrics(
        size = size,
        edge = edge,
        top = top,
        bottom = bottom,
        barH = barH,
        barGap = barGap,
        shoulderH = shoulderH,
        shoulderW = (w * 0.25f).coerceIn(150.dp, 280.dp),
        lowerGap = lowerGap,
        colGap = colGap,
        sideW = sideW,
        padW = (sideW * 0.66f).coerceIn(120.dp, 220.dp),
        clusterW = clusterW,
        gearInCluster = gearFits,
        rockerW = rockerW,
        rockerGap = rockerGap,
        lowerH = lowerH,
        stickMax = stickMax,
        stick360 = minOf(sideW, lowerH, pick(240.dp, 240.dp, 300.dp)),
        slotH = pick(44.dp, 44.dp, 52.dp),
        width = w,
    )
}
