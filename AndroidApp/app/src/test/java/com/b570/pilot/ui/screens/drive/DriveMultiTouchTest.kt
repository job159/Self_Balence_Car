package com.b570.pilot.ui.screens.drive

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.b570.pilot.data.JoystickMode
import com.b570.pilot.data.ReconnectStatus
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.MainTab
import com.b570.pilot.ui.shell.B570Root
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 駕駛畫面的多點觸控與歸零（安全不變式 §11.6 1–3、9）：真 shell + 真 DriveScreen + 展示車，
 * 直接讀連線層最後送出的 CTRL 值（DemoTelemetrySource.control），不經過車子模擬的加速度限制。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w780dp-h360dp-land-xhdpi")
class DriveMultiTouchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: AppViewModel

    private fun settle(frames: Int = 4) {
        repeat(frames) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeBy(32)
        }
    }

    private fun start(mode: JoystickMode) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        vm = AppViewModel(app)
        vm.updateSettings { it.copy(joystickMode = mode) }
        vm.connectDemo()
        rule.setContent { B570Root(vm, vm.nav) }
        rule.waitUntil(15_000) { vm.telemetry.value?.motorsOn == true }
        rule.mainClock.advanceTimeBy(1500)
        settle(10)
    }

    /** 連線層目前送出的 (speed mm/s, turn ‰)。 */
    private fun sent(): Pair<Int, Int> {
        val f = vm.repo.javaClass.getDeclaredField("activeLink").apply { isAccessible = true }
        val link = (f.get(vm.repo) as MutableStateFlow<*>).value!!
        val cf = link.javaClass.getDeclaredField("control").apply { isAccessible = true }
        val c = (cf.get(link) as AtomicInteger).get()
        return (c shr 16) to (c and 0xFFFF).toShort().toInt()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Suppress("UNCHECKED_CAST")
    private fun setConnection(st: ConnectionState) {
        val f = vm.repo.javaClass.getDeclaredField("_connectionState").apply { isAccessible = true }
        rule.runOnUiThread { (f.get(vm.repo) as MutableStateFlow<ConnectionState>).value = st }
        settle(6)
    }

    @Test(timeout = 240_000)
    fun twoThumbsDriveBothAxesIndependently() {
        start(JoystickMode.DUAL)
        val t = bounds(DriveTestTags.THROTTLE)
        val s = bounds(DriveTestTags.STEERING)
        assertTrue("throttle left of steering", t.right < s.left)

        // 左拇指推油門
        rule.onRoot().performTouchInput {
            down(0, t.center)
            moveBy(0, Offset(0f, -t.height * 0.35f))
        }
        settle()
        val (sp1, tu1) = sent()
        assertTrue("speed after throttle: $sp1", sp1 > 0)
        assertEquals(0, tu1)

        // 右拇指同時推方向（左拇指仍按著）
        rule.onRoot().performTouchInput {
            down(1, s.center)
            moveBy(1, Offset(s.width * 0.3f, 0f))
        }
        settle()
        val (sp2, tu2) = sent()
        assertTrue("speed kept while steering: $sp2", sp2 > 0)
        assertTrue("turn after steering: $tu2", tu2 > 0)

        // 兩根同時移動：油門再推、方向往左
        rule.onRoot().performTouchInput {
            moveBy(0, Offset(0f, -t.height * 0.1f))
            moveBy(1, Offset(-s.width * 0.55f, 0f))
        }
        settle()
        val (sp3, tu3) = sent()
        assertTrue("more throttle: $sp3 > $sp2", sp3 > sp2)
        assertTrue("now turning left: $tu3", tu3 < 0)

        // 放開左拇指：只歸零車速，轉向保留
        rule.onRoot().performTouchInput { up(0) }
        settle()
        val (sp4, tu4) = sent()
        assertEquals(0, sp4)
        assertEquals(tu3, tu4)

        // 第二根手指落在已被追蹤的方向搖桿上 → 被忽略（不搶走控制）
        rule.onRoot().performTouchInput { down(2, s.center + Offset(s.width * 0.35f, 0f)) }
        settle()
        assertEquals(tu3, sent().second)
        rule.onRoot().performTouchInput { up(2) }
        settle()
        assertEquals(tu3, sent().second)

        // 放開右拇指 → 全部歸零
        rule.onRoot().performTouchInput { up(1) }
        settle()
        assertEquals(0 to 0, sent())
    }

    @Test(timeout = 240_000)
    fun singleStickBothAxesAndReleaseZeroes() {
        start(JoystickMode.SINGLE)
        val b = bounds(DriveTestTags.STICK)
        rule.onRoot().performTouchInput {
            down(b.center)
            moveBy(Offset(b.width * 0.25f, -b.height * 0.3f))
        }
        settle()
        val (sp, tu) = sent()
        assertTrue("single speed $sp", sp > 0)
        assertTrue("single turn $tu", tu > 0)
        rule.onRoot().performTouchInput { up() }
        settle()
        assertEquals(0 to 0, sent())

        // 觸控取消（系統攔走手勢）也歸零
        rule.onRoot().performTouchInput {
            down(b.center)
            moveBy(Offset(-b.width * 0.25f, b.height * 0.3f))
        }
        settle()
        assertTrue(sent().first < 0)
        rule.onRoot().performTouchInput { cancel() }
        settle()
        assertEquals(0 to 0, sent())
    }

    @Test(timeout = 240_000)
    fun stick360WithOtherThumbAndSideSwap() {
        start(JoystickMode.SINGLE)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val b = bounds(DriveTestTags.STICK)
        assertTrue("stick on the left by default", b.center.x < root.center.x)

        // 純前推：轉向 0
        rule.onRoot().performTouchInput {
            down(0, b.center)
            moveBy(0, Offset(0f, -b.height * 0.4f))
        }
        settle()
        val (sp1, tu1) = sent()
        assertTrue("forward $sp1", sp1 > 0)
        assertEquals(0, tu1)

        // 另一根拇指按按鍵區的「升檔」：搖桿仍按著、指令照送，而且用新檔位重送
        val gearUp = rule.onAllNodesWithContentDescription("升檔")[0].fetchSemanticsNode().boundsInRoot
        assertTrue("gear rocker in the cluster (right side)", gearUp.center.x > root.center.x)
        rule.onRoot().performTouchInput {
            down(1, gearUp.center)
            up(1)
        }
        settle(6)
        val (sp2, tu2) = sent()
        assertTrue("faster gear while holding: $sp2 > $sp1", sp2 > sp1)
        assertEquals(0, tu2)

        // 純橫推：原地旋轉（速度 0）
        rule.onRoot().performTouchInput { moveBy(0, Offset(b.width * 0.4f, b.height * 0.4f)) }
        settle()
        val (sp3, tu3) = sent()
        assertEquals(0, sp3)
        assertTrue("spin $tu3", tu3 > 0)
        rule.onRoot().performTouchInput { up(0) }
        settle()
        assertEquals(0 to 0, sent())

        // 搖桿換到右邊（上緣列膠囊），記住；換邊後一樣能駕駛
        rule.onNodeWithText("搖桿在左").performClick()
        settle(24)
        val b2 = bounds(DriveTestTags.STICK)
        assertTrue("stick moved right", b2.center.x > root.center.x)
        assertTrue(DrivePrefs.stickRight(ApplicationProvider.getApplicationContext<Application>()))
        rule.onRoot().performTouchInput {
            down(0, b2.center)
            moveBy(0, Offset(-b2.width * 0.3f, b2.height * 0.3f))
        }
        settle()
        val (sp4, tu4) = sent()
        assertTrue("back-left arc: $sp4 / $tu4", sp4 < 0 && tu4 < 0)
        rule.onRoot().performTouchInput { up(0) }
        settle()
        assertEquals(0 to 0, sent())
    }

    @Test(timeout = 240_000)
    fun stick360WhileHoldingGamepadButton() {
        start(JoystickMode.SINGLE)
        val b = bounds(DriveTestTags.STICK)
        // A 鍵（原地轉圈）：語意節點 = 圓鍵 + 下方標籤，按在圓鍵上
        val a = rule.onAllNodesWithContentDescription("原地轉圈")[0].fetchSemanticsNode().boundsInRoot
        val aPress = Offset(a.center.x, a.top + a.height * 0.35f)
        assertTrue("A button right of the stick (default side)", a.left > b.right)

        // 左拇指斜推（前右）
        rule.onRoot().performTouchInput {
            down(0, b.center)
            moveBy(0, Offset(b.width * 0.2f, -b.height * 0.25f))
        }
        settle()
        val (sp1, tu1) = sent()
        assertTrue("diagonal forward-right: $sp1 / $tu1", sp1 > 0 && tu1 > 0)

        // 右拇指按住 A 鍵不放：搖桿照常送、不被打斷
        rule.onRoot().performTouchInput { down(1, aPress) }
        settle()
        assertEquals(sp1 to tu1, sent())
        rule.onRoot().performTouchInput { moveBy(0, Offset(-b.width * 0.4f, 0f)) }
        settle()
        val (sp2, tu2) = sent()
        assertTrue("still driving while A is held: $sp2 / $tu2", sp2 > 0 && tu2 < 0)

        // 放開搖桿（A 鍵仍按著）→ 立即歸零
        rule.onRoot().performTouchInput { up(0) }
        settle()
        assertEquals(0 to 0, sent())

        // 放開 A 鍵 → 播放快捷動作（車上的編排，手機的 CTRL 保持 0）
        rule.onRoot().performTouchInput { up(1) }
        settle()
        rule.waitUntil(8_000) { vm.telemetry.value?.seqPlaying == true }
        assertEquals(0 to 0, sent())
    }

    @Test(timeout = 240_000)
    fun navOverlayZeroesAndNeedsFreshTouch() {
        start(JoystickMode.DUAL)
        val t = bounds(DriveTestTags.THROTTLE)
        rule.onRoot().performTouchInput {
            down(0, t.center)
            moveBy(0, Offset(0f, -t.height * 0.35f))
        }
        settle()
        assertTrue(sent().first > 0)
        rule.runOnUiThread { vm.nav.openNavOverlay() }
        settle(8)
        assertEquals(0 to 0, sent())
        rule.runOnUiThread { vm.nav.closeNavOverlay() }
        settle(12)
        // 同一根手指（遮罩出現前就按著）繼續移動：不會接續舊手勢
        rule.onRoot().performTouchInput { moveBy(0, Offset(0f, -t.height * 0.1f)) }
        settle()
        assertEquals(0 to 0, sent())
        rule.onRoot().performTouchInput { up(0) }
        settle()
        // 重新按下才會送出
        rule.onRoot().performTouchInput {
            down(0, t.center)
            moveBy(0, Offset(0f, -t.height * 0.3f))
        }
        settle()
        assertTrue(sent().first > 0)
        rule.onRoot().performTouchInput { up(0) }
        settle()
        assertEquals(0 to 0, sent())
    }

    @Test(timeout = 240_000)
    fun linkLostBlocksUntilFreshTouch() {
        start(JoystickMode.DUAL)
        val s = bounds(DriveTestTags.STEERING)
        rule.onRoot().performTouchInput {
            down(0, s.center)
            moveBy(0, Offset(s.width * 0.3f, 0f))
        }
        settle()
        assertTrue(sent().second > 0)
        setConnection(ConnectionState.LinkLost(System.currentTimeMillis()))
        assertEquals(0 to 0, sent())
        // 失聯期間推動：不送
        rule.onRoot().performTouchInput { moveBy(0, Offset(s.width * 0.1f, 0f)) }
        settle()
        assertEquals(0 to 0, sent())
        // 恢復連線後舊手指繼續推：仍不送（要重新按下）
        setConnection(ConnectionState.Connected(null, System.currentTimeMillis()))
        rule.onRoot().performTouchInput { moveBy(0, Offset(-s.width * 0.05f, 0f)) }
        settle()
        assertEquals(0 to 0, sent())
        rule.onRoot().performTouchInput { up(0) }
        settle()
        rule.onRoot().performTouchInput {
            down(0, s.center)
            moveBy(0, Offset(-s.width * 0.3f, 0f))
        }
        settle()
        assertTrue(sent().second < 0)
        rule.onRoot().performTouchInput { up(0) }
        settle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun setReconnect(st: ReconnectStatus?) {
        val f = vm.repo.javaClass.getDeclaredField("_reconnect").apply { isAccessible = true }
        rule.runOnUiThread { (f.get(vm.repo) as MutableStateFlow<ReconnectStatus?>).value = st }
        settle(6)
    }

    @Test(timeout = 240_000)
    fun autoReconnectStaysOnDriveBlockedUntilFreshTouch() {
        start(JoystickMode.SINGLE)
        val b = bounds(DriveTestTags.STICK)
        rule.onRoot().performTouchInput {
            down(0, b.center)
            moveBy(0, Offset(0f, -b.height * 0.3f))
        }
        settle()
        assertTrue(sent().first > 0)

        // 斷線 → 自動重連中（repository 先排重連、狀態是 Failed）：留在駕駛畫面、搖桿封鎖並歸零
        setReconnect(ReconnectStatus("B570-A1B2", 2, 6, SystemClock.elapsedRealtime() + 3_000))
        setConnection(ConnectionState.Failed("連不上車子"))
        rule.mainClock.advanceTimeBy(600) // > ROUTE_FAILED_GRACE_MS：shell 不會切到連線頁
        settle()
        assertEquals(0 to 0, sent())
        assertTrue("still on drive", rule.onAllNodesWithTag(DriveTestTags.STICK).fetchSemanticsNodes().isNotEmpty())
        assertTrue("block overlay", rule.onAllNodesWithText("自動重連中…").fetchSemanticsNodes().isNotEmpty())
        // 重連提示在上緣列（車況膠囊那一格），整個在緊急停止那一列之上
        val cancel = rule.onAllNodesWithText("取消")[0].fetchSemanticsNode().boundsInRoot
        val estop = rule.onAllNodesWithText("緊急停止")[0].fetchSemanticsNode().boundsInRoot
        assertTrue("reconnect badge above E-STOP: $cancel / $estop", cancel.bottom <= estop.top)
        // 重連期間推動：不送
        rule.onRoot().performTouchInput { moveBy(0, Offset(b.width * 0.1f, 0f)) }
        settle()
        assertEquals(0 to 0, sent())

        // 連回來：舊手指繼續推仍不送，要重新按下
        setReconnect(null)
        setConnection(ConnectionState.Connected(null, System.currentTimeMillis()))
        rule.onRoot().performTouchInput { moveBy(0, Offset(0f, -b.height * 0.05f)) }
        settle()
        assertEquals(0 to 0, sent())
        rule.onRoot().performTouchInput { up(0) }
        settle()
        rule.onRoot().performTouchInput {
            down(0, b.center)
            moveBy(0, Offset(0f, -b.height * 0.3f))
        }
        settle()
        assertTrue(sent().first > 0)
        rule.onRoot().performTouchInput { up(0) }
        settle()
        assertEquals(0 to 0, sent())

        // 重連提示的「取消」：停止重連並中斷
        setReconnect(ReconnectStatus("B570-A1B2", 3, 6, 0L))
        rule.onAllNodesWithText("取消")[0].performClick()
        settle()
        assertEquals(null, vm.reconnect.value)
        rule.waitUntil(5_000) { vm.connectionState.value == ConnectionState.Idle }
    }

    @Test(timeout = 240_000)
    fun leavingDriveWhileHoldingStickZeroesDuringExit() {
        start(JoystickMode.SINGLE)
        val b = bounds(DriveTestTags.STICK)
        rule.onRoot().performTouchInput {
            down(0, b.center)
            moveBy(0, Offset(0f, -b.height * 0.3f))
        }
        settle()
        assertTrue(sent().first > 0)

        // 另一根拇指點上緣列的「編排」：駕駛畫面退場（約 260 ms 還在組合中、搖桿還追蹤著手指，而且畫面往上滑）
        val choreo = rule.onAllNodesWithText("編排")[0].fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput {
            down(1, choreo.center)
            up(1)
        }
        rule.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0 to 0, sent())
        // 退場動畫期間舊手指繼續推：不送
        rule.onRoot().performTouchInput { moveBy(0, Offset(0f, -b.height * 0.05f)) }
        rule.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0 to 0, sent())
        assertTrue("drive still composed (exit animation)", rule.onAllNodesWithTag(DriveTestTags.STICK).fetchSemanticsNodes().isNotEmpty())
        // 時鐘保持手動推進（編排頁有持續的動畫，autoAdvance 會一直等不到 idle）
        rule.onRoot().performTouchInput { up(0) }
        settle(16)
        assertEquals(MainTab.CHOREO, vm.nav.currentTab)
        assertEquals(0 to 0, sent())
    }

    @Test(timeout = 240_000)
    fun scannerOverlayOverDriveDisablesHeldStick() {
        start(JoystickMode.SINGLE)
        val b = bounds(DriveTestTags.STICK)
        rule.onRoot().performTouchInput {
            down(0, b.center)
            moveBy(0, Offset(b.width * 0.2f, -b.height * 0.25f))
        }
        settle()
        assertTrue(sent().first > 0)
        // QR 掃描覆蓋層（例如桌面捷徑「掃描配對」）蓋住駕駛畫面：下層搖桿停用並歸零，緊急停止也被蓋住了
        rule.runOnUiThread { vm.nav.openScanner() }
        settle(8)
        assertEquals(0 to 0, sent())
        rule.onRoot().performTouchInput { moveBy(0, Offset(0f, -b.height * 0.1f)) }
        settle()
        assertEquals(0 to 0, sent())
        rule.onRoot().performTouchInput { up(0) }
        // 關掉掃描回到駕駛：重新按下才會送
        rule.runOnUiThread { vm.nav.closeOverlay() }
        settle(16)
        val b2 = bounds(DriveTestTags.STICK)
        rule.onRoot().performTouchInput {
            down(0, b2.center)
            moveBy(0, Offset(0f, -b2.height * 0.3f))
        }
        settle()
        assertTrue(sent().first > 0)
        rule.onRoot().performTouchInput { up(0) }
        settle()
        assertEquals(0 to 0, sent())
    }

    @Test(timeout = 240_000)
    @Config(qualifiers = "w400dp-h360dp-land-xhdpi")
    fun narrowSplitScreenKeepsEStopOnScreen() {
        // 分割畫面只剩 400 dp 寬（< 520，整頁可左右捲動）：肩鍵列右端的緊急停止原本在畫面外
        start(JoystickMode.SINGLE)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val fully = rule.onAllNodesWithText("緊急停止").fetchSemanticsNodes()
            .filter { n ->
                val r = n.boundsInRoot
                r.width > 0f && abs(r.width - n.size.width) < 1f && r.left >= root.left && r.right <= root.right
            }
            .map { it.boundsInRoot }
        assertTrue("a fully visible E-STOP: $fully", fully.isNotEmpty())
        rule.onRoot().performTouchInput { down(0, fully.first().center) }
        settle()
        rule.waitUntil(5_000) { vm.telemetry.value?.motorsOn == false }
        rule.onRoot().performTouchInput { up(0) }
        settle()
    }

    @Test(timeout = 240_000)
    fun modeChangePauseAndEStopZero() {
        start(JoystickMode.DUAL)
        val t = bounds(DriveTestTags.THROTTLE)
        // 切換模式
        rule.onRoot().performTouchInput {
            down(0, t.center)
            moveBy(0, Offset(0f, -t.height * 0.35f))
        }
        settle()
        assertTrue(sent().first > 0)
        rule.runOnUiThread { vm.updateSettings { it.copy(joystickMode = JoystickMode.SINGLE) } }
        settle(6)
        assertEquals(0 to 0, sent())
        rule.onRoot().performTouchInput { up(0) }
        rule.runOnUiThread { vm.updateSettings { it.copy(joystickMode = JoystickMode.DUAL) } }
        settle(20)

        // ON_PAUSE
        val t2 = bounds(DriveTestTags.THROTTLE)
        rule.onRoot().performTouchInput {
            down(0, t2.center)
            moveBy(0, Offset(0f, -t2.height * 0.35f))
        }
        settle()
        assertTrue(sent().first > 0)
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        settle()
        assertEquals(0 to 0, sent())
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onRoot().performTouchInput { up(0) }
        settle(10)

        // 緊急停止（另一根手指按，油門仍按著）
        val t3 = bounds(DriveTestTags.THROTTLE)
        rule.onRoot().performTouchInput {
            down(0, t3.center)
            moveBy(0, Offset(0f, -t3.height * 0.35f))
        }
        settle()
        assertTrue(sent().first > 0)
        val estop = rule.onAllNodesWithText("緊急停止")[0].fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { down(1, estop.center) }
        settle()
        assertEquals(0 to 0, sent())
        rule.waitUntil(5_000) { vm.telemetry.value?.motorsOn == false }
        rule.mainClock.advanceTimeBy(600)
        settle()
        // 緊急停止後還按著的油門繼續推：不送（要重新按下）
        rule.onRoot().performTouchInput { moveBy(0, Offset(0f, -t3.height * 0.1f)) }
        settle()
        assertEquals(0 to 0, sent())
        rule.onRoot().performTouchInput { up(1); up(0) }
        settle()
    }
}
