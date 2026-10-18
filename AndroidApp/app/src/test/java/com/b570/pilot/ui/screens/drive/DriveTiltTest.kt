package com.b570.pilot.ui.screens.drive

import android.view.Surface
import com.b570.pilot.data.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 體感（橫向、手機像方向盤）：軸向換算與「按住駕駛」安全規則。 */
class DriveTiltTest {

    private fun rad(d: Double) = Math.toRadians(d)

    /** 螢幕座標的「上方向量」→ 裝置座標（反推 [tiltAnglesOf] 的旋轉）。 */
    private fun device(rotation: Int, sx: Double, sy: Double, sz: Double): FloatArray = when (rotation) {
        Surface.ROTATION_90 -> floatArrayOf(sy.toFloat(), (-sx).toFloat(), sz.toFloat())
        Surface.ROTATION_270 -> floatArrayOf((-sy).toFloat(), sx.toFloat(), sz.toFloat())
        else -> floatArrayOf(sx.toFloat(), sy.toFloat(), sz.toFloat())
    }

    /** 手機往後仰 [backDeg]（0 = 直立面向自己），像方向盤往右轉 [wheelDeg]。 */
    private fun angles(rotation: Int, backDeg: Double, wheelDeg: Double): FloatArray {
        val b = rad(backDeg)
        val w = rad(wheelDeg)
        val v = device(rotation, -sin(w) * cos(b), cos(w) * cos(b), sin(b))
        val out = FloatArray(2)
        assertTrue(tiltAnglesOf(v[0], v[1], v[2], rotation, out))
        return out
    }

    @Test fun wheelAngleIsSteeringInBothLandscapeDirections() {
        for (rot in listOf(Surface.ROTATION_90, Surface.ROTATION_270)) {
            val upright = angles(rot, 0.0, 20.0)
            assertEquals(90f, upright[0], 0.5f)
            assertEquals(20f, upright[1], 0.5f)
            val left = angles(rot, 0.0, -15.0)
            assertEquals(-15f, left[1], 0.5f)
        }
    }

    @Test fun steeringDoesNotChangeThrottleWhenHeldLikeAWheel() {
        // 往後仰 45°（方向盤式拿法），轉 25°：轉向 = 25°、油門軸仍是 45°（兩軸互不影響）
        for (rot in listOf(Surface.ROTATION_90, Surface.ROTATION_270)) {
            val a = angles(rot, 45.0, 25.0)
            assertEquals(45f, a[0], 0.5f)
            assertEquals(25f, a[1], 0.5f)
        }
        // 上緣往外倒（往後仰更多）→ 仰角變小 = 前進
        assertTrue(angles(Surface.ROTATION_90, 55.0, 0.0)[0] < angles(Surface.ROTATION_90, 45.0, 0.0)[0])
    }

    @Test fun flatGripUsesBank() {
        // 平放、右側往下 10°：方向盤角度沒有意義 → 用側傾角
        val phi = rad(10.0)
        val v = device(Surface.ROTATION_90, -sin(phi), 0.0, cos(phi))
        val out = FloatArray(2)
        assertTrue(tiltAnglesOf(v[0], v[1], v[2], Surface.ROTATION_90, out))
        assertEquals(10f, out[1], 0.5f)
        assertEquals(0f, out[0], 0.5f)
        // 無效向量
        assertFalse(tiltAnglesOf(0f, 0f, 0f, Surface.ROTATION_90, out))
    }

    private class Sink {
        var speed = 0
        var turn = 0
        var calls = 0
    }

    private fun session(sink: Sink): TiltSession {
        val ctl = DriveController { s, t -> sink.speed = s; sink.turn = t; sink.calls++ }
        ctl.configure(500f, Gear.FAST)
        return TiltSession(ctl).apply { sensitivity = 0.5f; expo = 0f } // 20° 推到底
    }

    @Test fun holdEitherPadToDrive() {
        val sink = Sink()
        val s = session(sink)
        // 沒按住：不送任何指令
        s.onSample(45f, 0f)
        assertEquals(0, sink.calls)

        assertTrue(s.pressPad(0))
        s.onSample(45f, 0f) // 零點
        s.onSample(35f, 10f) // 往外倒 10°、往右轉 10°
        assertTrue(sink.speed > 0)
        assertTrue(sink.turn > 0)

        // 第二個鈕按下不重設零點
        assertFalse(s.pressPad(1))
        s.onSample(35f, 10f)
        assertTrue(sink.speed > 0)

        // 放開一個仍在駕駛，全部放開才停
        assertFalse(s.releasePad(0))
        assertTrue(s.held.value)
        assertTrue(s.releasePad(1))
        assertFalse(s.held.value)
        assertEquals(0, sink.speed)
        assertEquals(0, sink.turn)
    }

    @Test fun extremeTiltAbortsAndRequiresFreshPress() {
        val sink = Sink()
        val s = session(sink)
        var aborted = 0
        s.onAbort = { aborted++ }
        val gen = s.resetGen.intValue
        s.pressPad(0)
        s.onSample(45f, 0f)
        s.onSample(45f, 15f)
        assertTrue(sink.turn > 0)
        // 手機被翻轉 / 掉落：轉超過 75°
        s.onSample(45f, 90f)
        assertEquals(1, aborted)
        assertFalse(s.held.value)
        assertEquals(0, sink.speed)
        assertEquals(0, sink.turn)
        assertEquals(gen + 1, s.resetGen.intValue) // 按鈕的手勢會被結束
        // 之後的樣本不再送指令
        val calls = sink.calls
        s.onSample(45f, 10f)
        assertEquals(calls, sink.calls)
        // 舊手指的放開不會誤觸發
        assertFalse(s.releasePad(0))
    }
}
