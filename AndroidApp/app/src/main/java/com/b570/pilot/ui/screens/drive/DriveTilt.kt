package com.b570.pilot.ui.screens.drive

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.b570.pilot.ui.components.Haptics
import com.b570.pilot.ui.components.joystickCurve
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 手機姿態追蹤：TYPE_GAME_ROTATION_VECTOR（無磁力計漂移），沒有時改用加速度計（低通濾波）。
 * 輸出（見 [tiltAnglesOf]）：油門軸（仰角，平放 0°、直立 90°）與轉向軸（像方向盤轉，右轉為正）。
 * 回呼在主執行緒（registerListener 未指定 Handler）；每筆樣本零配置。
 * 橫向左右翻轉（90 ⇄ 270）不會重建 Activity：螢幕旋轉由 [setRotation] 即時更新。
 */
internal class TiltTracker(private val sm: SensorManager?) : SensorEventListener {
    private val sensor: Sensor? =
        sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val fused = sensor?.type == Sensor.TYPE_GAME_ROTATION_VECTOR
    private val quat = FloatArray(4)
    private val rot = FloatArray(9)
    private val lp = FloatArray(3)
    private val angles = FloatArray(2)
    private var hasLp = false
    private var displayRotation = Surface.ROTATION_90
    private var running = false

    val available: Boolean get() = sensor != null

    /** (油門軸仰角°, 轉向角°) */
    var onSample: ((Float, Float) -> Unit)? = null

    fun start(rotation: Int) {
        val s = sensor ?: return
        displayRotation = rotation
        if (running) return
        hasLp = false
        running = sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME) == true
    }

    /** 螢幕旋轉改變（左右翻轉）：之後的樣本改用新的螢幕座標。 */
    fun setRotation(rotation: Int) {
        displayRotation = rotation
    }

    fun stop() {
        if (!running) return
        sm?.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val x: Float
        val y: Float
        val z: Float
        if (fused) {
            // 部分機型給 5 個值：只取四元數前 4 個
            if (v.size > 4) {
                System.arraycopy(v, 0, quat, 0, 4)
                SensorManager.getRotationMatrixFromVector(rot, quat)
            } else {
                SensorManager.getRotationMatrixFromVector(rot, v)
            }
            // 世界座標「上」在裝置座標中的方向 = 旋轉矩陣第三列
            x = rot[6]; y = rot[7]; z = rot[8]
        } else {
            if (v.size < 3) return
            if (!hasLp) {
                lp[0] = v[0]; lp[1] = v[1]; lp[2] = v[2]
                hasLp = true
            } else {
                for (i in 0..2) lp[i] += (v[i] - lp[i]) * ACC_LOW_PASS
            }
            x = lp[0]; y = lp[1]; z = lp[2]
        }
        if (tiltAnglesOf(x, y, z, displayRotation, angles)) onSample?.invoke(angles[0], angles[1])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val ACC_LOW_PASS = 0.2f
    }
}

private const val RAD_TO_DEG = 57.29578f

/** 螢幕平面分量（= sin 離平放的角度）低於此值視為「平拿」，高於 [WHEEL_FULL] 視為「方向盤式直拿」。 */
private const val WHEEL_START = 0.26f // ≈ 15°
private const val WHEEL_FULL = 0.5f // ≈ 30°

/**
 * 「上方向量」（裝置座標，任意長度）→ 兩個控制軸（橫向遙控器：手機像方向盤一樣拿在面前）：
 * - `out[0]` 油門軸 = 仰角°（平放 0、直立 90；螢幕上緣往外倒 → 變小 = 前進）
 * - `out[1]` 轉向角°（右側往下 / 像方向盤往右轉 = 正）
 *
 * 先依螢幕旋轉換成**螢幕座標**（x 右、y 上、z 出螢幕），所以兩個橫向方向（ROTATION_90 / 270）結果一致。
 * 方向盤式直拿（離平放 > 30°）：轉向 = 繞螢幕法線的旋轉角（方向盤角度），油門 = 螢幕離水平的角度，
 * 兩軸互不影響（轉方向盤不會改變油門）。平拿（< 15°）時方向盤角度沒有意義，改用「右側往下」的側傾角與帶正負的仰角
 * （上緣往下壓仍是前進）；中間平滑過渡。上緣朝下（倒拿）時也退回側傾角。
 * 回傳 false = 向量無效。
 */
internal fun tiltAnglesOf(x: Float, y: Float, z: Float, displayRotation: Int, out: FloatArray): Boolean {
    // 裝置座標 → 螢幕座標（ROTATION_90：裝置上緣在左；ROTATION_270：裝置上緣在右）
    val sx: Float
    val sy: Float
    when (displayRotation) {
        Surface.ROTATION_90 -> { sx = -y; sy = x }
        Surface.ROTATION_180 -> { sx = -x; sy = -y }
        Surface.ROTATION_270 -> { sx = y; sy = -x }
        else -> { sx = x; sy = y }
    }
    val n = sqrt(sx * sx + sy * sy + z * z)
    if (n < 1e-3f || n.isNaN()) return false
    val ux = sx / n
    val uy = sy / n
    val uz = z / n
    val planar = sqrt(ux * ux + uy * uy)

    // 平拿 ↔ 直拿的權重（直拿且上緣朝上才用方向盤角度）
    val wTilt = ((planar - WHEEL_START) / (WHEEL_FULL - WHEEL_START)).coerceIn(0f, 1f)
    val wUp = if (planar > 1e-4f) (uy / planar / 0.5f).coerceIn(0f, 1f) else 0f
    val w = wTilt * wUp

    val elevSigned = atan2(uy, uz)
    val elevFree = atan2(planar, uz)
    val bank = asin((-ux).coerceIn(-1f, 1f))
    val wheel = atan2(-ux, uy)
    out[0] = (elevSigned + (elevFree - elevSigned) * w) * RAD_TO_DEG
    out[1] = (bank + (wheel - bank) * w) * RAD_TO_DEG
    return true
}

/**
 * 體感駕駛狀態（橫向）：左右兩個「按住駕駛」鈕，**至少按住一個**才送指令；第一個按下的瞬間 = 零點。
 * 螢幕上緣往外倒（遠離自己）= 前進，像方向盤往右轉 = 右轉。
 *
 * 安全：
 * - 全部放開 → [release]（歸零）。
 * - 相對零點轉超過 [ABORT_DEG]（手機被翻轉、掉落、亂甩）→ [abort]：歸零並要求**放開後重新按下**。
 * - [abort] 會遞增 [resetGen]；按鈕的 pointerInput 以它為 key，所以還按著的手指會被結束，不會接續。
 */
@Stable
internal class TiltSession(private val controller: DriveController) {
    // 由 SideEffect 同步的設定
    var expo = 0f
    var sensitivity = 0.5f
    var haptics: Haptics? = null

    /** 因傾斜過大被中止（畫面跳提示用）。 */
    var onAbort: (() -> Unit)? = null

    /** 正在體感駕駛（至少按住一個鈕）。 */
    val held = mutableStateOf(false)

    /** 目前傾斜量（-1..1；x 右轉正、y 前進正），只在繪製階段讀取。 */
    val tilt = mutableStateOf(Offset.Zero)

    /** 每次 [abort] +1：按鈕的手勢以它為 key，強制結束還按著的手指。 */
    val resetGen = mutableIntStateOf(0)

    private val pads = BooleanArray(2)
    private var calibrate = false
    private var refElev = 0f
    private var refRoll = 0f
    private var outside = false
    private var atEdge = false

    /** 推到底需要的傾角：靈敏度 0 → 30°，1 → 10°。 */
    private val maxDeg: Float get() = 30f - 20f * sensitivity.coerceIn(0f, 1f)

    private val anyPad: Boolean get() = pads[0] || pads[1]

    /** 按下第 [i] 個「按住駕駛」鈕。回傳 true = 開始駕駛（這次按下設定了零點）。 */
    fun pressPad(i: Int): Boolean {
        val was = anyPad
        pads[i] = true
        if (was && held.value) return false
        press()
        return true
    }

    /** 放開第 [i] 個鈕。回傳 true = 全部放開、已停止駕駛。 */
    fun releasePad(i: Int): Boolean {
        pads[i] = false
        if (anyPad || !held.value) return false
        release()
        return true
    }

    fun press() {
        calibrate = true
        outside = false
        atEdge = false
        tilt.value = Offset.Zero
        held.value = true
    }

    fun release() {
        if (!held.value) return
        held.value = false
        calibrate = false
        tilt.value = Offset.Zero
        controller.stop()
    }

    /** 歸零、忘記哪些鈕按著，並強制結束還按著的手勢（要重新按下）。 */
    fun abort() {
        pads[0] = false
        pads[1] = false
        release()
        controller.stop()
        resetGen.intValue++
    }

    fun onSample(elevDeg: Float, rollDeg: Float) {
        if (!held.value) return
        if (calibrate) {
            refElev = elevDeg
            refRoll = rollDeg
            calibrate = false
        }
        val dElev = wrap180(refElev - elevDeg)
        val dRoll = wrap180(rollDeg - refRoll)
        if (abs(dElev) > ABORT_DEG || abs(dRoll) > ABORT_DEG) {
            abort()
            onAbort?.invoke()
            return
        }
        val m = maxDeg
        val fy = (dElev / m).coerceIn(-1f, 1f)
        val fx = (dRoll / m).coerceIn(-1f, 1f)
        tilt.value = Offset(fx, fy)

        // 觸覺：離開死區、推到底（含遲滯，避免抖動連續震）
        val mag = maxOf(abs(fx), abs(fy))
        val nowOutside = if (outside) mag >= DEADZONE else mag > DEADZONE + HYSTERESIS
        if (nowOutside && !outside) haptics?.tick()
        outside = nowOutside
        val nowEdge = if (atEdge) mag >= 0.94f else mag >= 0.999f
        if (nowEdge && !atEdge) haptics?.click()
        atEdge = nowEdge

        controller.set(shape(fy), shape(fx))
    }

    private fun shape(v: Float): Float {
        val a = abs(v)
        if (a <= DEADZONE) return 0f
        return joystickCurve(sign(v) * (a - DEADZONE) / (1f - DEADZONE), expo)
    }

    private fun wrap180(d: Float): Float {
        var x = d % 360f
        if (x > 180f) x -= 360f
        if (x < -180f) x += 360f
        return x
    }

    companion object {
        /** 每軸死區（比例）。 */
        const val DEADZONE = 0.10f
        private const val HYSTERESIS = 0.04f

        /** 相對零點超過這個角度 = 不是在駕駛（翻轉 / 掉落）→ 中止。 */
        const val ABORT_DEG = 75f
    }
}
