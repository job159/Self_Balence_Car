package com.b570.pilot.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 搖桿換算（死區、夾限、expo、方向）——兩個搖桿共用同一個純函式，各自獨立呼叫。 */
class JoystickMathTest {

    private val out = FloatArray(4)
    private val eps = 1e-4f

    @Test fun centerAndDeadzoneAreZero() {
        assertEquals(0f, joystickMap(0f, 0f, 0.06f, 0.35f, out), eps)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        // 死區內（徑向 0.05 < 0.06）一定輸出 0
        joystickMap(0.03f, -0.04f, 0.06f, 0f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }

    @Test fun directionsAndFullScale() {
        // 右推到底 → x = 1；往上（dy 負）→ y = +1（前進）
        joystickMap(1f, 0f, 0.06f, 0.35f, out)
        assertEquals(1f, out[0], eps)
        assertEquals(0f, out[1], eps)
        joystickMap(0f, -1f, 0.06f, 0.35f, out)
        assertEquals(0f, out[0], eps)
        assertEquals(1f, out[1], eps)
        joystickMap(0f, 1f, 0.06f, 0f, out)
        assertEquals(-1f, out[1], eps)
        joystickMap(-1f, 0f, 0.06f, 0f, out)
        assertEquals(-1f, out[0], eps)
    }

    @Test fun beyondEdgeIsClampedToUnitCircle() {
        val rr = joystickMap(3f, -4f, 0.06f, 0f, out)
        assertEquals(1f, rr, eps)
        assertEquals(0.6f, out[2], eps)
        assertEquals(-0.8f, out[3], eps)
        assertTrue(hypot(out[0], out[1]) <= 1f + eps)
        assertEquals(0.6f, out[0], eps)
        assertEquals(0.8f, out[1], eps)
    }

    @Test fun expoKeepsSignAndSoftensCenter() {
        joystickMap(0f, 0.53f, 0f, 1f, out) // 下推一半，expo = 1 → -(0.53³)
        assertEquals(-(0.53f * 0.53f * 0.53f), out[1], eps)
        joystickMap(0f, -0.53f, 0f, 0f, out) // 線性
        assertEquals(0.53f, out[1], eps)
        assertEquals(0.5f, joystickCurve(0.5f, 0f), eps)
        assertEquals(-0.5f, joystickCurve(-0.5f, 0f), eps)
        assertTrue(joystickCurve(0.5f, 0.35f) < 0.5f)
    }

    @Test fun deadzoneEdgeRescalesContinuously() {
        // 剛出死區時輸出從 0 連續開始（不會跳一格）
        joystickMap(0.0601f, 0f, 0.06f, 0f, out)
        assertTrue(out[0] in 0f..0.001f)
        joystickMap(0.53f, 0f, 0.06f, 0f, out)
        assertEquals((0.53f - 0.06f) / 0.94f, out[0], eps)
    }

    @Test fun nanInputIsSafe() {
        joystickMap(Float.NaN, Float.NaN, 0.06f, 0.35f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }

    // ------------------------------------------------------------------ 360° 類比搖桿（analogStickMap）

    private val a = FloatArray(ANALOG_OUT_SIZE)
    private val diag = 0.70710677f

    /** 從中心往方位角 [deg]（0 = 前、90 = 右）推 [r]（行程比例）：回傳 (dx, dy 下正)。 */
    private fun at(deg: Double, r: Float): Pair<Float, Float> {
        val rad = Math.toRadians(deg)
        return (r * sin(rad).toFloat()) to (-r * cos(rad).toFloat())
    }

    @Test fun analogCircularClamp() {
        // 斜推到方形外角：夾回圓周 → 輸出長度 1（不是 √2），旋鈕在圓周上
        val rr = analogStickMap(1f, -1f, 0.06f, 0f, 0f, a)
        assertEquals(1f, rr, eps)
        assertEquals(diag, a[2], eps)
        assertEquals(-diag, a[3], eps)
        assertEquals(diag, a[0], eps)
        assertEquals(diag, a[1], eps)
        assertEquals(1f, hypot(a[0], a[1]), eps)
        // 3-4-5：超出行程夾回圓周、方向保留
        assertEquals(1f, analogStickMap(3f, -4f, 0.06f, 0f, 0f, a), eps)
        assertEquals(0.6f, a[2], eps)
        assertEquals(-0.8f, a[3], eps)
        assertEquals(0.6f, a[0], eps)
        assertEquals(0.8f, a[1], eps)
        assertEquals(1f, a[4], eps)
        // 任何方向推到底：推桿量 1、向量長度 1（真圓形行程）
        for (deg in 0 until 360 step 7) {
            val (dx, dy) = at(deg.toDouble(), 2f)
            analogStickMap(dx, dy, 0.06f, 0.35f, ANALOG_SNAP_DEG, a)
            assertEquals("deg $deg", 1f, a[4], eps)
            assertEquals("deg $deg", 1f, hypot(a[0], a[1]), eps)
            assertTrue(hypot(a[2], a[3]) <= 1f + eps)
        }
    }

    @Test fun analogCircularDeadzone() {
        // 圓形死區：每個方向半徑 0.059 都是 0；0.065 都 > 0（不是方形 / 十字死區）
        for (deg in 0 until 360 step 15) {
            val (ix, iy) = at(deg.toDouble(), 0.059f)
            analogStickMap(ix, iy, 0.06f, 0f, 0f, a)
            assertEquals(0f, a[0], 0f)
            assertEquals(0f, a[1], 0f)
            assertEquals(0f, a[4], 0f)
            assertEquals(-1f, a[5], 0f)
            val (ox, oy) = at(deg.toDouble(), 0.065f)
            analogStickMap(ox, oy, 0.06f, 0f, 0f, a)
            assertTrue("deg $deg", a[4] > 0f)
        }
        // 中心 = 0
        assertEquals(0f, analogStickMap(0f, 0f, 0.06f, 0.35f, ANALOG_SNAP_DEG, a), 0f)
        assertEquals(0f, a[4], 0f)
        // 剛出死區從 0 連續開始（不跳格），之後線性重新縮放
        analogStickMap(0f, -0.0601f, 0.06f, 0f, 0f, a)
        assertTrue(a[1] in 0f..0.001f)
        analogStickMap(0f, -0.53f, 0.06f, 0f, 0f, a)
        assertEquals((0.53f - 0.06f) / 0.94f, a[1], eps)
        assertEquals((0.53f - 0.06f) / 0.94f, a[4], eps)
    }

    @Test fun analogExpoOnMagnitudeKeepsDirection() {
        // 3-4-5 方向推一半、expo = 1：推桿量 = 0.5³，方向（x : y = 3 : 4）不變
        analogStickMap(0.3f, -0.4f, 0f, 1f, 0f, a)
        assertEquals(0.125f, a[4], eps)
        assertEquals(0.6f * 0.125f, a[0], eps)
        assertEquals(0.8f * 0.125f, a[1], eps)
        assertEquals(0.75f, a[0] / a[1], eps)
        // 對照：逐軸 expo（舊搖桿）會把方向扭曲
        joystickMap(0.3f, -0.4f, 0f, 1f, out)
        assertTrue(abs(out[0] / out[1] - 0.75f) > 0.1f)
        // 預設 expo 0.35：推桿量 = joystickCurve(推桿比例)
        analogStickMap(0f, -0.5f, 0f, 0.35f, 0f, a)
        assertEquals(joystickCurve(0.5f, 0.35f), a[1], eps)
        assertEquals(joystickCurve(0.5f, 0.35f), a[4], eps)
        // 斜向一樣：長度 = curve，方向 45°
        val (dx, dy) = at(45.0, 0.5f)
        analogStickMap(dx, dy, 0f, 0.35f, 0f, a)
        assertEquals(joystickCurve(0.5f, 0.35f), hypot(a[0], a[1]), eps)
        assertEquals(a[0], a[1], eps)
    }

    @Test fun analogAngleMapping() {
        // 前 0°、右 90°、後 180°、左 270°：另一軸精確為 0
        analogStickMap(0f, -1f, 0.06f, 0.35f, ANALOG_SNAP_DEG, a)
        assertEquals(0f, a[5], eps)
        assertEquals(0f, a[0], 0f)
        assertEquals(1f, a[1], eps)
        analogStickMap(1f, 0f, 0.06f, 0.35f, ANALOG_SNAP_DEG, a)
        assertEquals(90f, a[5], eps)
        assertEquals(1f, a[0], eps)
        assertEquals(0f, a[1], 0f)
        analogStickMap(0f, 1f, 0.06f, 0.35f, ANALOG_SNAP_DEG, a)
        assertEquals(180f, a[5], eps)
        assertEquals(0f, a[0], 0f)
        assertEquals(-1f, a[1], eps)
        analogStickMap(-1f, 0f, 0.06f, 0.35f, ANALOG_SNAP_DEG, a)
        assertEquals(270f, a[5], eps)
        assertEquals(-1f, a[0], eps)
        assertEquals(0f, a[1], 0f)
        // 斜向：右前 45°、左後 225°（吸附不影響斜向）
        analogStickMap(1f, -1f, 0.06f, 0f, ANALOG_SNAP_DEG, a)
        assertEquals(45f, a[5], eps)
        assertEquals(diag, a[0], eps)
        assertEquals(diag, a[1], eps)
        analogStickMap(-1f, 1f, 0.06f, 0f, ANALOG_SNAP_DEG, a)
        assertEquals(225f, a[5], eps)
        assertEquals(-diag, a[0], eps)
        assertEquals(-diag, a[1], eps)
        // 方位角（x 右正、y 上正）
        assertEquals(30f, analogBearing(0.5f, 0.8660254f), 0.01f)
        assertEquals(330f, analogBearing(-0.5f, 0.8660254f), 0.01f)
        assertEquals(135f, analogBearing(1f, -1f), 0.01f)
        // 不吸附時角度照手指（每 10° 檢查）
        for (deg in 0 until 360 step 10) {
            val (dx, dy) = at(deg.toDouble(), 1f)
            analogStickMap(dx, dy, 0.06f, 0f, 0f, a)
            assertEquals("deg $deg", deg.toFloat(), a[5], 0.01f)
            assertEquals(deg.toFloat(), analogBearing(a[0], a[1]), 0.01f)
        }
    }

    @Test fun analogSnapGivesStraightAndSpin() {
        // 前推偏右 3° / 偏左 3°：吸附 → x 精確 0（直線前進、轉向 0）
        for (deg in listOf(3.0, 357.0)) {
            val (dx, dy) = at(deg, 1f)
            analogStickMap(dx, dy, 0.06f, 0.35f, ANALOG_SNAP_DEG, a)
            assertEquals("deg $deg", 0f, a[0], 0f)
            assertEquals(1f, a[1], eps)
        }
        // 橫推偏 4°：y 精確 0（原地旋轉）
        for (deg in listOf(86.0, 94.0, 266.0, 274.0)) {
            val (dx, dy) = at(deg, 1f)
            analogStickMap(dx, dy, 0.06f, 0.35f, ANALOG_SNAP_DEG, a)
            assertEquals("deg $deg", 0f, a[1], 0f)
            assertEquals(1f, abs(a[0]), eps)
        }
        // 後退偏 2°：x 精確 0
        val (bx, by) = at(182.0, 0.8f)
        analogStickMap(bx, by, 0.06f, 0f, ANALOG_SNAP_DEG, a)
        assertEquals(0f, a[0], 0f)
        assertTrue(a[1] < 0f)
        // 吸附外連續：剛出吸附區接近 0、45° 不變、吸附區邊界正好是正方向
        assertEquals(0f, analogSnap(5f, 5f), 0f)
        assertTrue(analogSnap(6f, 5f) in 0.5f..2f)
        assertEquals(45f, analogSnap(45f, 5f), eps)
        assertEquals(90f, analogSnap(85f, 5f), 0f)
        assertEquals(0f, analogSnap(357f, 5f), 0f)
        assertEquals(180f, analogSnap(183f, 5f), 0f)
        // 單調：角度越大，吸附後也越大（不會倒退）
        var prev = -1f
        for (i in 0 until 360) {
            val s = analogSnap(i.toFloat(), 5f)
            val unwrapped = if (i > 300 && s < 90f) s + 360f else s
            assertTrue("i=$i $unwrapped >= $prev", unwrapped >= prev)
            prev = unwrapped
        }
        // 關閉吸附：3° 照實輸出
        val (dx, dy) = at(3.0, 1f)
        analogStickMap(dx, dy, 0f, 0f, 0f, a)
        assertEquals(sin(Math.toRadians(3.0)).toFloat(), a[0], eps)
    }

    @Test fun analogNanAndInfinityAreSafe() {
        analogStickMap(Float.NaN, Float.NaN, 0.06f, 0.35f, ANALOG_SNAP_DEG, a)
        assertEquals(0f, a[0], 0f)
        assertEquals(0f, a[1], 0f)
        assertEquals(0f, a[4], 0f)
        analogStickMap(Float.POSITIVE_INFINITY, 0f, 0.06f, 0.35f, ANALOG_SNAP_DEG, a)
        assertEquals(0f, a[0], 0f)
        assertEquals(0f, a[1], 0f)
    }

    @Test fun nanSettingsNeverProduceNaN() {
        // 設定值異常（NaN expo / 死區 / 吸附角）也只能輸出單位圓內的有限值
        analogStickMap(0.5f, -0.5f, Float.NaN, Float.NaN, Float.NaN, a)
        assertTrue(a[0].isFinite() && a[1].isFinite() && a[4].isFinite())
        assertTrue(hypot(a[0], a[1]) <= 1f + eps)
        assertTrue(a[0] > 0f && a[1] > 0f)
        joystickMap(0.5f, -0.5f, Float.NaN, Float.NaN, out)
        assertTrue(out[0].isFinite() && out[1].isFinite())
        assertTrue(out[0] > 0f && out[1] > 0f)
        joystickMap(Float.POSITIVE_INFINITY, 0f, 0.06f, 0.35f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        // 死區設成 1（或更大）：推到底也不會算出 0 / 0
        joystickMap(1f, 0f, 1f, 0f, out)
        assertTrue(out[0].isFinite())
    }

    @Test fun gestureGuardEndsWhenStickMovesUnderFinger() {
        val g = GestureGuard()
        val size = IntSize(400, 400)
        g.begin(Offset(200f, 200f), size)
        // 手指正常移動：不中斷
        assertFalse(g.broken(Offset(230f, 170f), Offset(200f, 200f), size, 4f))
        assertFalse(g.broken(Offset(260f, 150f), Offset(230f, 170f), size, 4f))
        // 手指沒動、搖桿往左移了 50 px（換邊 / 切分頁的動畫）：本地座標 +50 → 中斷
        assertTrue(g.broken(Offset(310f, 150f), Offset(310f, 150f), size, 4f))

        // 搖桿改變大小 → 中斷
        g.begin(Offset(200f, 200f), size)
        assertTrue(g.broken(Offset(201f, 200f), Offset(200f, 200f), IntSize(380, 380), 4f))
    }

    @Test fun gestureGuardEndsOnCoordinateJump() {
        val g = GestureGuard()
        val size = IntSize(400, 400)
        // 很快推到底（一個事件 300 px < 1.5 × 400）不算跳動
        g.begin(Offset(200f, 200f), size)
        assertFalse(g.broken(Offset(500f, 200f), Offset(200f, 200f), size, 4f))
        // 螢幕 180° 翻轉：座標鏡射，一個事件跳 1000 px → 中斷
        g.begin(Offset(200f, 200f), size)
        assertTrue(g.broken(Offset(1200f, 500f), Offset(200f, 200f), size, 4f))
        // NaN 座標 → 中斷
        g.begin(Offset(200f, 200f), size)
        assertTrue(g.broken(Offset(Float.NaN, 200f), Offset(200f, 200f), size, 4f))
    }

    @Test fun analogReadoutStrings() {
        assertEquals("045°", AnalogText.angle(45))
        assertEquals("000°", AnalogText.angle(360))
        assertEquals("359°", AnalogText.angle(-1))
        assertEquals("72%", AnalogText.percent(72))
        assertEquals("100%", AnalogText.percent(140))
    }
}
