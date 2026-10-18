package com.b570.pilot.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.theme.B570Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 搖桿手勢保護（[GestureGuard]）接在真的手勢上：按著的時候搖桿被版面移走、或手指座標一瞬間跳到很遠（螢幕 180° 翻轉）
 * → 立刻送 (0, 0)，舊手指之後的移動都不送，要重新按下。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w780dp-h360dp-land-xhdpi")
class JoystickGestureGuardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var last = Offset(9f, 9f)
    private val shift = mutableStateOf(0)

    private fun show(style: JoystickStyle) {
        rule.setContent {
            B570Theme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset { IntOffset(shift.value, 0) }.size(200.dp).testTag("stick")) {
                        Joystick(onMove = { x, y -> last = Offset(x, y) }, modifier = Modifier.fillMaxSize(), style = style)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun stick() = rule.onNodeWithTag("stick").fetchSemanticsNode().boundsInRoot

    @Test fun stickMovedUnderHeldFingerEndsGesture() {
        show(JoystickStyle.ANALOG_360)
        val b = stick()
        rule.onRoot().performTouchInput {
            down(b.center)
            moveBy(Offset(0f, -b.height * 0.2f))
        }
        rule.waitForIdle()
        assertTrue("pushed forward: $last", last.y > 0f)

        // 版面把搖桿往右移 80 px（例如換邊 / 切分頁的動畫），手指沒動：舊的中心已經不對 → 結束手勢
        rule.runOnUiThread { shift.value = 80 }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { moveBy(Offset(1f, 0f)) }
        rule.waitForIdle()
        assertEquals(Offset.Zero, last)
        // 舊手指繼續推：不送
        rule.onRoot().performTouchInput { moveBy(Offset(0f, -b.height * 0.1f)) }
        rule.waitForIdle()
        assertEquals(Offset.Zero, last)
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()

        // 重新按下就恢復
        val b2 = stick()
        rule.onRoot().performTouchInput {
            down(b2.center)
            moveBy(Offset(0f, -b2.height * 0.2f))
        }
        rule.waitForIdle()
        assertTrue("fresh touch drives again: $last", last.y > 0f && kotlin.math.abs(last.x) < 0.05f)
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(Offset.Zero, last)
    }

    @Test fun coordinateJumpEndsGesture() {
        show(JoystickStyle.STANDARD)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val b = stick()
        rule.onRoot().performTouchInput {
            down(b.center)
            moveBy(Offset(b.width * 0.2f, 0f))
        }
        rule.waitForIdle()
        assertTrue("pushed right: $last", last.x > 0f)

        // 螢幕 180° 翻轉：同一根手指的座標整個鏡射到另一邊（沒有這個保護會變成一瞬間推到底）
        rule.onRoot().performTouchInput { moveTo(Offset(root.width - b.center.x, root.height - b.center.y)) }
        rule.waitForIdle()
        assertEquals(Offset.Zero, last)
        rule.onRoot().performTouchInput { moveBy(Offset(-5f, 0f)) }
        rule.waitForIdle()
        assertEquals(Offset.Zero, last)
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
    }
}
