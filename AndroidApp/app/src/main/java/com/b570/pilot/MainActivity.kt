package com.b570.pilot

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.b570.pilot.data.CarShortcuts
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.shell.B570Root
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity：橫向（sensorLandscape）、edge-to-edge、沉浸式（隱藏狀態列與導覽列，從邊緣滑動可暫時叫出）。
 *
 * - 瀏海 / 挖孔：layoutInDisplayCutoutMode = SHORT_EDGES（橫向時瀏海在左右短邊），內容用 WindowInsets 自己避開。
 * - 左右翻轉、分割畫面調整大小不重建（manifest configChanges）；其他重建也不斷線（狀態在 ViewModel / CarRepository）。
 * - 安全：onStop、視窗失去焦點（下拉通知欄、系統對話框）時控制量一律歸零。
 * - 桌面捷徑（[CarShortcuts]）：「連線 <車名>」/「掃描配對」由 onCreate / onNewIntent 交給 [AppViewModel.handleLaunchRequest]。
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        // 橫向時瀏海在短邊：畫面延伸進瀏海區，由 Compose 依 displayCutout insets 留白
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyImmersive()

        // 桌面捷徑（連線某台車 / 掃描配對）：在畫面組合前交給 ViewModel，連線頁一出現就處理（也不會再自動連最近的車）。
        // 重建（savedInstanceState != null）或從最近使用畫面重開時不重複執行。
        if (savedInstanceState == null) handleLaunchIntent(intent)

        // 依設定保持螢幕常亮（連線期間，含斷線後自動重連的那段時間）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.settings, vm.connectionState, vm.reconnect) { s, st, r -> s.keepScreenOn && (st.isSession || r != null) }
                    .distinctUntilChanged()
                    .collect { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
            }
        }

        setContent { B570Root(vm, vm.nav) }
    }

    /** App 已在前景 / 背景時點了桌面捷徑（launchMode = singleTop）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val request = CarShortcuts.parse(intent.action, intent.getStringExtra(CarShortcuts.EXTRA_SSID)) ?: return
        vm.handleLaunchRequest(request)
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 對話框、通知欄、系統視窗關閉後重新隱藏系統列
            applyImmersive()
        } else {
            // 安全：失去焦點時觸控可能被系統攔走，控制量先歸零（搖桿若仍在追蹤手指，下一次移動會重新送出）
            vm.stopControl()
            // 自動重連時跳出系統「連線到裝置」視窗 → 那次沒連上就停止自動重連（不要一直跳視窗）
            vm.onWindowFocusLost()
        }
    }

    override fun onStop() {
        super.onStop()
        // 安全：App 退到背景時搖桿可能來不及放開，控制量一律歸零
        vm.stopControl()
    }

    /** 隱藏狀態列 + 導覽列；從邊緣滑動可暫時叫出（不改變 insets，版面不跳動）。 */
    private fun applyImmersive() {
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
    }
}
