package com.b570.pilot.ui.screens.drive

import com.b570.pilot.data.Gear
import com.b570.pilot.ui.components.ANALOG_OUT_SIZE
import com.b570.pilot.ui.components.ANALOG_SNAP_DEG
import com.b570.pilot.ui.components.analogStickMap
import com.b570.pilot.ui.components.joystickCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 360° 搖桿 → CTRL（arcade 換算）：搖桿輸出 (x, y) 與 [Stick360Zone] 相同地交給
 * `controller.set(forward = y, turn = x)` → speed = y × MAX_SPEED × 檔位速度比、turn = x × 1000 × 檔位轉向比。
 */
class Drive360MappingTest {

    private var sent = 0 to 0
    private var calls = 0
    private val controller = DriveController { s, t ->
        sent = s to t
        calls++
    }
    private val out = FloatArray(ANALOG_OUT_SIZE)

    /** 往方位角 [deg]（0 = 前、90 = 右）推 [r]（行程比例，> 1 = 推出圓外）。 */
    private fun push(deg: Double, r: Float = 1f, gear: Gear = Gear.FAST, maxSpeed: Float = 500f, expo: Float = 0f): Pair<Int, Int> {
        controller.configure(maxSpeed, gear)
        val rad = Math.toRadians(deg)
        analogStickMap(r * sin(rad).toFloat(), -r * cos(rad).toFloat(), 0.06f, expo, ANALOG_SNAP_DEG, out)
        controller.set(out[1], out[0]) // = Stick360Zone 的 onMove { x, y -> controller.set(y, x) }
        return sent
    }

    @Test fun pureForwardAndBackAreStraight() {
        assertEquals(500 to 0, push(0.0))
        assertEquals(-500 to 0, push(180.0))
        // 手指偏一點（吸附區內）仍然直線：轉向精確 0
        assertEquals(500 to 0, push(4.0))
        assertEquals(500 to 0, push(356.0))
        assertEquals(-500 to 0, push(183.0))
    }

    @Test fun pureSidewaysSpinsInPlace() {
        assertEquals(0 to 1000, push(90.0))
        assertEquals(0 to -1000, push(270.0))
        assertEquals(0 to 1000, push(93.0))
        assertEquals(0 to -1000, push(268.0))
    }

    @Test fun diagonalsAreArcs() {
        // 右前 45°：速度與轉向各 70.7 %（圓形夾限，不是 100 % + 100 %）
        val (s, t) = push(45.0, r = 2f)
        assertEquals((0.70710677f * 500).roundToInt(), s)
        assertEquals((0.70710677f * 1000).roundToInt(), t)
        // 左後 225°：倒車 + 左轉
        val (s2, t2) = push(225.0)
        assertTrue(s2 < 0 && t2 < 0)
        // 30°：前進為主、右轉較少
        val (s3, t3) = push(30.0)
        assertTrue(s3 > 0 && t3 > 0 && s3 > t3 / 2)
    }

    @Test fun gearAndMaxSpeedScale() {
        assertEquals(300 to 0, push(0.0, gear = Gear.NORMAL)) // 500 × 0.6
        assertEquals(0 to 750, push(90.0, gear = Gear.NORMAL)) // 1000 × 0.75
        assertEquals(150 to 0, push(0.0, gear = Gear.SLOW)) // 500 × 0.3
        assertEquals(0 to -500, push(270.0, gear = Gear.SLOW)) // 1000 × 0.5
        assertEquals(800 to 0, push(0.0, maxSpeed = 800f))
    }

    @Test fun expoSoftensMagnitudeOnly() {
        val (s, t) = push(0.0, r = 0.53f, expo = 0.35f)
        val m = joystickCurve((0.53f - 0.06f) / 0.94f, 0.35f)
        assertEquals((m * 500).roundToInt(), s)
        assertEquals(0, t)
        // 斜推一半：速度 / 轉向比例仍是 45°（expo 不扭曲方向）
        val (s2, t2) = push(45.0, r = 0.6f, expo = 1f)
        assertTrue(abs(s2 * 2 - t2) <= 2) // speed / 500 == turn / 1000
    }

    @Test fun everyMoveSendsImmediatelyAndReleaseZeroes() {
        val before = calls
        push(10.0)
        push(20.0)
        push(30.0)
        assertEquals(before + 3, calls) // 每次移動都同步送出，沒有 debounce
        // 死區內 = 0
        assertEquals(0 to 0, push(45.0, r = 0.05f))
        // 放開（搖桿送 (0, 0)）
        push(60.0)
        controller.set(0f, 0f)
        assertEquals(0 to 0, sent)
        // 停止（切換模式 / 換邊 / 離開畫面）
        push(60.0)
        controller.stop()
        assertEquals(0 to 0, sent)
        assertEquals(0, controller.cmdSpeed.intValue)
        assertEquals(0, controller.cmdTurn.intValue)
    }
}
