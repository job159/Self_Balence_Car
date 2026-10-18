package com.b570.pilot.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 主畫面分頁（順序 = 左側導覽軌由上到下的順序，也決定切換動畫方向：往下的分頁由下往上滑入）。
 * @property fullScreen true = 全螢幕分頁（不顯示導覽軌與頂部狀態條，例如駕駛的遙控器畫面）；
 *   全螢幕分頁用 [AppNavigator.openNavOverlay] 叫出滑入式導覽。
 */
enum class MainTab(val labelZh: String, val icon: ImageVector, val fullScreen: Boolean = false) {
    DRIVE("駕駛", Icons.Rounded.SportsEsports, fullScreen = true),
    MONITOR("監測", Icons.Rounded.Insights),
    TUNE("整定", Icons.Rounded.Tune),
    CHOREO("編排", Icons.Rounded.AutoAwesome),
    SETTINGS("設定", Icons.Rounded.Settings),
}

/** 路由。[isOverlay] = 由下往上滑出、蓋在目前畫面上（下層畫面保持組合）。 */
sealed interface Route {
    val isOverlay: Boolean get() = false

    /** 連線頁（未連線時的起點）。 */
    data object Connect : Route

    /** QR 掃描（覆蓋層）。 */
    data object QrScanner : Route {
        override val isOverlay: Boolean get() = true
    }

    /** 主畫面的某個分頁。 */
    data class Main(val tab: MainTab) : Route
}

/**
 * 自訂導覽（不使用 navigation-compose）：返回堆疊 + 分頁狀態。所有屬性都是 Compose state。
 *
 * - 連線狀態變化時 shell 會自動切換：Connected → 主畫面；Idle / Failed → Connect。畫面不需要自己處理。
 * - 分頁不疊加堆疊：在非「駕駛」分頁按返回 → 回到駕駛；在駕駛按返回 → shell 詢問是否中斷連線。
 * - 全螢幕分頁（駕駛）的滑入式導覽：[openNavOverlay] / [closeNavOverlay] / [toggleNavOverlay]；
 *   返回鍵先關導覽、選分頁會自動關、離開主畫面也會關。
 */
@Stable
class AppNavigator(start: Route = Route.Connect) {
    private val stack = mutableStateListOf(start)

    /** 唯讀返回堆疊（底 → 頂）。 */
    val backStack: List<Route> get() = stack

    /** 最上層路由（可能是覆蓋層）。 */
    val current: Route get() = stack.last()

    /** 最上層的「非覆蓋層」路由（覆蓋層下面那一頁）。 */
    val base: Route get() = stack.lastOrNull { !it.isOverlay } ?: Route.Connect

    /** 目前顯示中的覆蓋層，沒有則 null。 */
    val overlay: Route? get() = stack.last().takeIf { it.isOverlay }

    /** 目前（或最後）選取的主分頁。 */
    var currentTab: MainTab by mutableStateOf(if (start is Route.Main) start.tab else MainTab.DRIVE)
        private set

    /** 上一個分頁（shell 決定滑動方向用）。 */
    var previousTab: MainTab by mutableStateOf(currentTab)
        private set

    /**
     * 全螢幕分頁（駕駛）上的滑入式導覽是否打開。只在目前分頁 [MainTab.fullScreen] 且位於主畫面時有效
     * （其他分頁本來就有導覽軌，shell 不會畫這個覆蓋層）。
     */
    var isNavOverlayOpen: Boolean by mutableStateOf(false)
        private set

    val canGoBack: Boolean
        get() = isNavOverlayOpen || stack.size > 1 || (current is Route.Main && currentTab != MainTab.DRIVE)

    /** 叫出滑入式導覽（駕駛畫面的 ≡ 按鈕呼叫）。不在全螢幕分頁時不動作。 */
    fun openNavOverlay() {
        if (current is Route.Main && currentTab.fullScreen) isNavOverlayOpen = true
    }

    /** 關閉滑入式導覽（沒打開則不動）。 */
    fun closeNavOverlay() {
        isNavOverlayOpen = false
    }

    /** 切換滑入式導覽。 */
    fun toggleNavOverlay() {
        if (isNavOverlayOpen) closeNavOverlay() else openNavOverlay()
    }

    /** 前往路由。Main 會轉成 [selectTab]；覆蓋層疊在上面。 */
    fun navigate(route: Route) {
        if (route == current) return
        when (route) {
            is Route.Main -> selectTab(route.tab)
            else -> {
                isNavOverlayOpen = false
                stack.add(route)
            }
        }
    }

    /** 切換主分頁（若目前不在主畫面，會疊一個主畫面上去）。 */
    fun selectTab(tab: MainTab) {
        isNavOverlayOpen = false
        if (tab != currentTab) previousTab = currentTab
        currentTab = tab
        // 收掉覆蓋層
        while (stack.size > 1 && stack.last().isOverlay) stack.removeAt(stack.lastIndex)
        if (stack.last() is Route.Main) stack[stack.lastIndex] = Route.Main(tab) else stack.add(Route.Main(tab))
    }

    /** 開啟 QR 掃描覆蓋層。 */
    fun openScanner() = navigate(Route.QrScanner)

    /** 關閉最上層覆蓋層（沒有則不動）。 */
    fun closeOverlay() {
        if (stack.size > 1 && stack.last().isOverlay) stack.removeAt(stack.lastIndex)
    }

    /** 返回。回傳 false 表示已在根（由 shell 決定離開或詢問）。 */
    fun back(): Boolean {
        if (isNavOverlayOpen) {
            isNavOverlayOpen = false
            return true
        }
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            (stack.last() as? Route.Main)?.let { if (it.tab != currentTab) { previousTab = currentTab; currentTab = it.tab } }
            return true
        }
        if (current is Route.Main && currentTab != MainTab.DRIVE) {
            selectTab(MainTab.DRIVE)
            return true
        }
        return false
    }

    /** 清空堆疊並只留 [route]。 */
    fun resetTo(route: Route) {
        isNavOverlayOpen = false
        stack.clear()
        stack.add(route)
        if (route is Route.Main && route.tab != currentTab) { previousTab = currentTab; currentTab = route.tab }
    }
}
