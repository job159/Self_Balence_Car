package com.b570.pilot.ui.components

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 畫面可用區域的內距（由 shell 提供，橫向）：
 * - 一般主分頁（監測 / 整定 / 編排 / 設定）：
 *   start = 安全區左緣 + 左側導覽軌（[com.b570.pilot.ui.shell.RailReserve]，剛好到導覽軌右緣，不含間距），
 *   top = 安全區上緣 + 頂部狀態條（[com.b570.pilot.ui.shell.StatusStripHeight]），
 *   end = 安全區右緣（瀏海 / 導覽列在右側時），bottom = 安全區下緣 + 8 dp。
 *   左右的閱讀留白請自己加：`LocalScreenPadding.current + PaddingValues(horizontal = 16.dp)`。
 * - 全螢幕分頁（駕駛）、Connect、QR 掃描：只有安全區（[safeAreaInsets]），沒有任何額外留白。
 * - 不含鍵盤（IME）：有輸入框的可捲動容器自己加 `Modifier.imePadding()`。
 * 畫面會畫在玻璃導覽軌與狀態條「底下」，所以可捲動內容把它當 contentPadding（捲動時內容從玻璃下穿過），固定版面用 [screenPadding]。
 */
val LocalScreenPadding = staticCompositionLocalOf { PaddingValues(0.dp) }

/** 套用 [LocalScreenPadding]（固定版面的畫面用）。 */
@Composable
fun Modifier.screenPadding(): Modifier = this.padding(LocalScreenPadding.current)

/** PaddingValues 相加（例如 LocalScreenPadding.current + PaddingValues(horizontal = 16.dp)）。 */
@Composable
operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val ld = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(ld) + other.calculateStartPadding(ld),
        top = calculateTopPadding() + other.calculateTopPadding(),
        end = calculateEndPadding(ld) + other.calculateEndPadding(ld),
        bottom = calculateBottomPadding() + other.calculateBottomPadding(),
    )
}

/** 只取上下（左右歸零），方便再加自己的水平留白。 */
fun PaddingValues.verticalOnly(extra: Dp = 0.dp): PaddingValues =
    PaddingValues(top = calculateTopPadding() + extra, bottom = calculateBottomPadding() + extra)

/** 只取左右（上下歸零）。 */
fun PaddingValues.horizontalOnly(layoutDirection: LayoutDirection, extra: Dp = 0.dp): PaddingValues =
    PaddingValues(start = calculateStartPadding(layoutDirection) + extra, end = calculateEndPadding(layoutDirection) + extra)

/**
 * 安全區 insets = 系統列（沉浸式隱藏時為 0）∪ 瀏海 / 挖孔。不含鍵盤。
 * 橫向時瀏海在左或右（翻轉後會換邊），這裡會自動跟著變。
 */
val WindowInsets.Companion.safeArea: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** [safeArea] 轉成 PaddingValues。 */
@Composable
fun safeAreaInsets(): PaddingValues = WindowInsets.safeArea.asPaddingValues()

// ---------------------------------------------------------------------------------------------- 尺寸級距

/**
 * 橫向尺寸級距（依整個視窗，不是單一畫面區塊）。版面細節仍請用 BoxWithConstraints 依實際可用區域決定。
 * - [COMPACT]：小手機（約 640 × 320 dp）、分割畫面：高 < 360 dp 或寬 < 700 dp
 * - [MEDIUM]：一般手機（780 × 360 ~ 915 × 412 dp）
 * - [EXPANDED]：平板（約 1280 × 800 dp）：高 ≥ 600 dp 且寬 ≥ 900 dp
 */
enum class LandscapeClass { COMPACT, MEDIUM, EXPANDED }

fun landscapeClassOf(width: Dp, height: Dp): LandscapeClass = when {
    height >= 600.dp && width >= 900.dp -> LandscapeClass.EXPANDED
    height < 360.dp || width < 700.dp -> LandscapeClass.COMPACT
    else -> LandscapeClass.MEDIUM
}

/** 目前視窗的 [LandscapeClass]（shell 提供）。 */
val LocalLandscapeClass = staticCompositionLocalOf { LandscapeClass.MEDIUM }

/** 視窗（扣掉安全區後）的大小，shell 提供；單位 dp。 */
@Immutable
data class WindowDpSize(val width: Dp, val height: Dp)

val LocalWindowDpSize = staticCompositionLocalOf { WindowDpSize(780.dp, 360.dp) }

// ---------------------------------------------------------------------------------------------- 螢幕旋轉

/**
 * 目前螢幕旋轉（`Surface.ROTATION_0/90/180/270`），左右翻轉（90 ⇄ 270）時也會更新。
 * 橫向左右翻轉**不會**觸發 configuration change，用感測器（體感駕駛）換算螢幕座標的程式一定要用這個，
 * 不要只在開始時讀一次，否則翻轉後前後 / 左右會相反。
 */
@Composable
fun rememberDisplayRotation(): State<Int> {
    val view = LocalView.current
    val state = remember(view) { mutableIntStateOf(rotationOf(view)) }
    DisposableEffect(view) {
        val dm = view.context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                val d = view.display ?: return
                if (d.displayId == displayId) state.intValue = d.rotation
            }

            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        dm?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        state.intValue = rotationOf(view)
        onDispose { dm?.unregisterDisplayListener(listener) }
    }
    return state
}

private fun rotationOf(view: View): Int = view.display?.rotation ?: Surface.ROTATION_90

/**
 * 水平捲動列的邊緣淡出：還能往該方向捲時，左 / 右緣 [width] 內漸漸透明，提示「還有內容」
 * （取代硬生生被切掉一半的膠囊 / 標籤）。只在繪製階段讀捲動狀態，不會重組。
 * 放在 `horizontalScroll` / `LazyRow` 之前（套在可視範圍上）。
 */
fun Modifier.horizontalEdgeFade(state: ScrollableState, width: Dp = 20.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val w = width.toPx().coerceAtMost(size.width / 3f)
        val right = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width)
        val left = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w)
        onDrawWithContent {
            drawContent()
            if (state.canScrollForward) drawRect(right, blendMode = BlendMode.DstIn)
            if (state.canScrollBackward) drawRect(left, blendMode = BlendMode.DstIn)
        }
    }
