package com.b570.pilot.ui.screens.choreo

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.SeqMode
import com.b570.pilot.protocol.SeqStep
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.ui.theme.B570Colors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================== 常數

/** 預覽假設：轉向 1000‰ 約 180°/s（實際依車上的 MAX_TURN 而定）。 */
internal const val YAW_DPS_FULL = 180f

/** 範例與新步驟的速度上限 mm/s（車上另受 MAX_SPEED 夾限）。 */
internal const val GENTLE_SPEED_MAX = 450

// ============================================================== 動作種類

/**
 * 編輯器的「動作」：對應到 SeqStep 的 speed / turn / mode 組合。
 * [colorIndex] = 主題 series 色序（-1 = 中性色）。
 */
internal enum class MoveKind(val labelZh: String, val icon: ImageVector, val colorIndex: Int) {
    FORWARD("前進", Icons.Rounded.North, 0),
    BACKWARD("後退", Icons.Rounded.South, 3),
    PAUSE("停頓", Icons.Rounded.Pause, -1),
    LEFT("左轉", Icons.AutoMirrored.Rounded.RotateLeft, 1),
    RIGHT("右轉", Icons.AutoMirrored.Rounded.RotateRight, 4),
    ARC("弧線", Icons.Rounded.TurnSlightRight, 2),
    WIGGLE("搖擺", Icons.Rounded.Waves, 6),
    NOD("點頭", Icons.Rounded.SwapVert, 5),
    RAMP("漸變", Icons.AutoMirrored.Rounded.TrendingUp, 7);

    fun color(c: B570Colors): Color = if (colorIndex < 0) c.textSecondary else c.series[colorIndex % c.series.size]
}

/** 由步驟內容推回動作種類。 */
internal val SeqStep.kind: MoveKind
    get() = when (mode) {
        SeqMode.RAMP -> MoveKind.RAMP
        SeqMode.SINE_TURN -> MoveKind.WIGGLE
        SeqMode.SINE_SPEED -> MoveKind.NOD
        SeqMode.HOLD -> when {
            speed == 0 && turn == 0 -> MoveKind.PAUSE
            turn == 0 -> if (speed > 0) MoveKind.FORWARD else MoveKind.BACKWARD
            speed == 0 -> if (turn < 0) MoveKind.LEFT else MoveKind.RIGHT
            else -> MoveKind.ARC
        }
    }

/** 原地轉向的角度（°，忽略加速度限制）。 */
internal fun SeqStep.turnAngleDeg(): Float = abs(turn) / 1000f * YAW_DPS_FULL * durMs / 1000f

/** 以角度換算原地轉向需要的時間 ms。 */
internal fun durForAngle(angleDeg: Float, turnPermille: Int): Int {
    val rate = abs(turnPermille).coerceAtLeast(10) / 1000f * YAW_DPS_FULL
    return (angleDeg / rate * 1000f).roundToInt().coerceIn(SeqStep.MIN_DUR_MS, SeqStep.MAX_DUR_MS)
}

/** 新增步驟的預設值。 */
internal fun defaultStep(kind: MoveKind): SeqStep = when (kind) {
    MoveKind.FORWARD -> SeqStep(200, 0, 1500)
    MoveKind.BACKWARD -> SeqStep(-150, 0, 1500)
    MoveKind.PAUSE -> SeqStep(0, 0, 500)
    MoveKind.LEFT -> SeqStep(0, -600, durForAngle(90f, 600))
    MoveKind.RIGHT -> SeqStep(0, 600, durForAngle(90f, 600))
    MoveKind.ARC -> SeqStep(200, 400, 2000)
    MoveKind.WIGGLE -> SeqStep(0, 700, 2000, SeqMode.SINE_TURN, 50)
    MoveKind.NOD -> SeqStep(220, 0, 2400, SeqMode.SINE_SPEED, 60)
    MoveKind.RAMP -> SeqStep(0, 0, 500, SeqMode.RAMP)
}

/** 切換動作種類：盡量保留原本的速度 / 轉向 / 時間。 */
internal fun SeqStep.convertTo(target: MoveKind, maxSpeed: Int): SeqStep {
    if (target == kind) return this
    val lim = maxSpeed.coerceAtLeast(50)
    val sp = (if (abs(speed) >= 50) abs(speed) else 200).coerceAtMost(lim)
    val tn = if (abs(turn) >= 50) abs(turn) else 600
    val per = if (period20ms in 10..255) period20ms else 50
    return when (target) {
        MoveKind.FORWARD -> SeqStep(sp, 0, durMs)
        MoveKind.BACKWARD -> SeqStep(-sp, 0, durMs)
        MoveKind.PAUSE -> SeqStep(0, 0, durMs)
        MoveKind.LEFT -> SeqStep(0, -tn, durMs)
        MoveKind.RIGHT -> SeqStep(0, tn, durMs)
        MoveKind.ARC -> SeqStep(if (speed != 0) speed.coerceIn(-lim, lim) else 200.coerceAtMost(lim), if (turn != 0) turn else 400, durMs)
        MoveKind.WIGGLE -> SeqStep(speed.coerceIn(-lim, lim), tn, durMs.coerceAtLeast(per * 20 * 2), SeqMode.SINE_TURN, per)
        MoveKind.NOD -> SeqStep(sp, 0, durMs.coerceAtLeast(per * 20 * 2), SeqMode.SINE_SPEED, per)
        MoveKind.RAMP -> SeqStep(speed, turn, durMs, SeqMode.RAMP)
    }
}

// ============================================================== 文字格式

internal fun fmtDur(ms: Int): String = when {
    ms < 10_000 -> String.format(Locale.US, "%.2f 秒", ms / 1000f)
    else -> String.format(Locale.US, "%.1f 秒", ms / 1000f)
}

/** 總時間：「12.6 秒」或「1 分 05 秒」。 */
internal fun fmtTotal(ms: Long): String {
    if (ms < 60_000) return String.format(Locale.US, "%.1f 秒", ms / 1000f)
    val s = (ms / 1000).toInt()
    return String.format(Locale.US, "%d 分 %02d 秒", s / 60, s % 60)
}

internal fun fmtSpeedSigned(v: Int): String = when {
    v > 0 -> "+$v mm/s"
    v < 0 -> "−${-v} mm/s"
    else -> "0 mm/s"
}

/** 轉向 ‰ → 「右 40 %」／「左 40 %」／「直行」。 */
internal fun fmtTurnSigned(t: Int): String {
    val p = (abs(t) / 10f).roundToInt()
    return when {
        p == 0 -> "直行"
        t > 0 -> "右 $p %"
        else -> "左 $p %"
    }
}

internal fun fmtPct(t: Int): String = "${(abs(t) / 10f).roundToInt()} %"

internal fun fmtDistance(mm: Float): String = when {
    mm < 1000f -> "${(mm / 10f).roundToInt()} cm"
    else -> String.format(Locale.US, "%.1f m", mm / 1000f)
}

/** 搖擺：單側擺幅 °。 */
internal fun SeqStep.wiggleAmpDeg(): Float = abs(turn) / 1000f * YAW_DPS_FULL * (periodMs / 1000f) / (2f * PI.toFloat())

/** 點頭：前後位移幅度 mm。 */
internal fun SeqStep.nodAmpMm(): Float = abs(speed) * (periodMs / 1000f) / (2f * PI.toFloat())

/** 弧線半徑 mm（轉向為 0 時回傳 null）。 */
internal fun SeqStep.arcRadiusMm(): Float? {
    if (turn == 0) return null
    val w = abs(turn) / 1000f * PI.toFloat() // rad/s（180°/s = π）
    return abs(speed) / w
}

/** 步驟摘要（一行）。 */
internal fun SeqStep.summaryZh(): String = when (kind) {
    MoveKind.FORWARD, MoveKind.BACKWARD -> "${abs(speed)} mm/s"
    MoveKind.LEFT, MoveKind.RIGHT -> "轉速 ${fmtPct(turn)} · 約 ${turnAngleDeg().roundToInt()}°"
    MoveKind.ARC -> "${fmtSpeedSigned(speed)} · ${fmtTurnSigned(turn)}"
    MoveKind.WIGGLE -> (if (speed != 0) "${fmtSpeedSigned(speed)} · " else "原地 · ") +
        "擺幅 ${fmtPct(turn)} · 週期 ${fmtDur(periodMs)}"
    MoveKind.NOD -> "擺動 ${abs(speed)} mm/s · 週期 ${fmtDur(periodMs)}"
    MoveKind.PAUSE -> "原地保持平衡"
    MoveKind.RAMP -> if (speed == 0 && turn == 0) "平滑停下" else "平滑變到 ${fmtSpeedSigned(speed)} · ${fmtTurnSigned(turn)}"
}

/** 產生不重複的名稱：「base」、「base 2」、「base 3」… */
internal fun uniqueName(base: String, existing: Collection<String>): String {
    if (base !in existing) return base
    var i = 2
    while ("$base $i" in existing) i++
    return "$base $i"
}

// ============================================================== 車況

/** 從遙測取出編排頁需要的狀態（變化時才重組）。 */
@Immutable
internal data class CarStatus(
    val motorsOn: Boolean,
    val upright: Boolean,
    val seqPlaying: Boolean,
    val lowBattery: Boolean,
    val stopReason: StopReason,
) {
    companion object {
        fun of(t: Telemetry) = CarStatus(t.motorsOn, t.upright, t.seqPlaying, t.lowBattery, t.stopReason)
    }
}

/** 播放進度（高頻遙測 → 只在步驟 / 循環改變時更新）。 */
@Immutable
internal data class PlayTick(val playing: Boolean, val step: Int, val loop: Int) {
    companion object {
        val IDLE = PlayTick(false, -1, 0)
        fun of(t: Telemetry?): PlayTick =
            if (t == null || !t.seqPlaying) IDLE else PlayTick(true, t.seqStep ?: -1, t.seqLoop)
    }
}

/** 能不能播放，以及原因。 */
internal enum class Readiness(val canPlay: Boolean) {
    OFFLINE(false),
    LINK_LOST(false),
    NO_DATA(false),
    /** 姿態感測器異常（stop_reason IMU）：韌體控制迴圈已停，啟動也不會動，需重新開機。 */
    SENSOR(false),
    /** 電壓低於 LOWBAT_V：韌體拒絕啟動（can_start）。 */
    LOW_BATT(false),
    NOT_UPRIGHT(false),
    MOTORS_OFF(true),
    READY(true);

    val titleZh: String
        get() = when (this) {
            OFFLINE -> "尚未連線"
            LINK_LOST -> "訊號中斷"
            NO_DATA -> "等待車子狀態"
            SENSOR -> "感測器異常"
            LOW_BATT -> "電量過低"
            NOT_UPRIGHT -> "車子倒下了"
            MOTORS_OFF -> "馬達未啟動"
            READY -> "準備就緒"
        }

    /** 不能播放時給使用者看的原因（短句，含下一步）。 */
    val reasonZh: String
        get() = when (this) {
            OFFLINE -> "連上車子後才能播放"
            LINK_LOST -> "恢復連線後才能播放"
            NO_DATA -> "還沒收到車子狀態，請稍候"
            SENSOR -> "請檢查姿態感測器後重新開機"
            LOW_BATT -> "請先充電，車子才能啟動"
            NOT_UPRIGHT -> "請先把車子扶正再播放"
            MOTORS_OFF -> "播放前會先幫你啟動平衡"
            READY -> "點任一動作就會開始播放"
        }

    /** 點了卻不能播放時的提示。 */
    val blockedZh: String
        get() = when (this) {
            OFFLINE -> "尚未連線，連上車子後才能播放"
            LINK_LOST -> "訊號中斷，恢復連線後才能播放"
            NO_DATA -> "還沒收到車子狀態，請稍候再試"
            SENSOR -> "姿態感測器異常，請檢查後重新開機"
            LOW_BATT -> "電量過低，請先充電再播放"
            NOT_UPRIGHT -> "車子倒下了，請先扶正再播放"
            MOTORS_OFF, READY -> ""
        }

    companion object {
        fun of(conn: ConnectionState, s: CarStatus?): Readiness = when {
            conn is ConnectionState.LinkLost -> LINK_LOST
            !conn.isSession -> OFFLINE
            s == null -> NO_DATA
            s.motorsOn -> READY
            s.stopReason == StopReason.IMU -> SENSOR
            s.lowBattery -> LOW_BATT
            !s.upright -> NOT_UPRIGHT
            else -> MOTORS_OFF
        }
    }
}

// ============================================================== 動作庫

/** 動作庫的一個範例。 */
@Immutable
internal data class ChoreoPreset(
    val id: String,
    val name: String,
    val descZh: String,
    val icon: ImageVector,
    val colorIndex: Int,
    val loops: Int,
    val steps: List<SeqStep>,
) {
    val loopMs: Long = SeqStep.totalDurationMs(steps)
    val totalMs: Long = loopMs * loops.coerceAtLeast(1)

    fun toRequest() = PlayRequest(id, name, steps, loops)
}

private fun hold(speed: Int, turn: Int, ms: Int) = SeqStep(speed, turn, ms)
private fun ramp(speed: Int, turn: Int, ms: Int) = SeqStep(speed, turn, ms, SeqMode.RAMP)
private fun pause(ms: Int) = SeqStep(0, 0, ms)
private fun sineTurn(speed: Int, amp: Int, ms: Int, period20: Int) = SeqStep(speed, amp, ms, SeqMode.SINE_TURN, period20)
private fun sineSpeed(amp: Int, turn: Int, ms: Int, period20: Int) = SeqStep(amp, turn, ms, SeqMode.SINE_SPEED, period20)

/**
 * 內建動作。設計原則（平衡車要溫和）：|speed| ≤ 450、動作之間用 RAMP 漸變、穿插短暫停頓；
 * 正弦動作前先反向轉半個擺幅，讓擺動以原方向為中心；整段結束時盡量回到起點與原方向
 * （以 180°/s @1000‰ 計算，見 ChoreoTrajectory）。
 */
internal object ChoreoPresets {
    val ALL: List<ChoreoPreset> = listOf(
        ChoreoPreset(
            "lib-spin", "原地旋轉 360°", "原地優雅轉一整圈", Icons.Rounded.Autorenew, 0, 1,
            listOf(ramp(0, 600, 400), hold(0, 600, 2933), ramp(0, 0, 400), pause(300)),
        ),
        ChoreoPreset(
            "lib-sway", "左右搖擺", "原地左右扭動身體", Icons.Rounded.SwapHoriz, 1, 1,
            listOf(
                pause(200), ramp(0, -573, 250), ramp(0, 0, 250), sineTurn(0, 900, 4000, 50),
                ramp(0, 573, 250), ramp(0, 0, 250), pause(300),
            ),
        ),
        ChoreoPreset(
            "lib-nod", "前後點頭", "前後小幅晃動像在點頭", Icons.Rounded.SwapVert, 2, 1,
            listOf(
                pause(200), ramp(-232, 0, 250), ramp(0, 0, 250), sineSpeed(260, 0, 4200, 70),
                ramp(232, 0, 250), ramp(0, 0, 250), pause(300),
            ),
        ),
        ChoreoPreset(
            "lib-circle", "畫圓", "繞一圈回到原點", Icons.Rounded.Explore, 3, 1,
            listOf(ramp(300, 300, 600), hold(300, 300, 6067), ramp(0, 0, 600), pause(300)),
        ),
        ChoreoPreset(
            "lib-figure8", "8 字繞行", "右圈接左圈畫出 8 字", Icons.Rounded.AllInclusive, 4, 1,
            listOf(
                ramp(250, 0, 500), ramp(250, 400, 300), hold(250, 400, 4750), ramp(250, -400, 400),
                hold(250, -400, 4750), ramp(250, 0, 300), ramp(0, 0, 500), pause(300),
            ),
        ),
        ChoreoPreset(
            "lib-square", "正方形", "直走右轉，重複 4 次", Icons.Rounded.CropSquare, 5, 4,
            listOf(
                ramp(250, 0, 400), hold(250, 0, 1200), ramp(0, 0, 400), pause(200),
                ramp(0, 600, 250), hold(0, 600, 583), ramp(0, 0, 250), pause(200),
            ),
        ),
        ChoreoPreset(
            "lib-zigzag", "之字前進", "左右切換，Z 字向前", Icons.Rounded.Timeline, 6, 1,
            listOf(
                ramp(250, 0, 400),
                ramp(250, 700, 357), ramp(250, 0, 357), hold(250, 0, 800),
                ramp(250, -900, 556), ramp(250, 0, 556), hold(250, 0, 800),
                ramp(250, 900, 556), ramp(250, 0, 556), hold(250, 0, 800),
                ramp(250, -900, 556), ramp(250, 0, 556), hold(250, 0, 800),
                ramp(250, 700, 357), ramp(250, 0, 357), ramp(0, 0, 400), pause(300),
            ),
        ),
        ChoreoPreset(
            "lib-moonwalk", "太空漫步", "一邊後退一邊扭動", Icons.Rounded.RocketLaunch, 7, 1,
            listOf(
                pause(200), ramp(0, -509, 250), ramp(0, 0, 250), ramp(-150, 0, 400),
                sineTurn(-150, 800, 4000, 50), ramp(0, 0, 400), ramp(0, 509, 250), ramp(0, 0, 250), pause(300),
            ),
        ),
        ChoreoPreset(
            "lib-sprint", "衝刺折返", "加速衝刺，迴轉再衝回來", Icons.Rounded.Bolt, 3, 1,
            List(2) {
                listOf(
                    ramp(450, 0, 700), hold(450, 0, 800), ramp(0, 0, 700), pause(300),
                    ramp(0, 800, 300), hold(0, 800, 950), ramp(0, 0, 300), pause(300),
                )
            }.flatten(),
        ),
        ChoreoPreset(
            "lib-party", "派對舞", "搖擺、點頭、旋轉組合技", Icons.Rounded.Celebration, 6, 1,
            listOf(
                // 搖擺
                ramp(0, -573, 250), ramp(0, 0, 250), sineTurn(0, 900, 2000, 50), ramp(0, 573, 250), ramp(0, 0, 250),
                // 點頭
                ramp(-232, 0, 250), ramp(0, 0, 250), sineSpeed(260, 0, 2800, 70), ramp(232, 0, 250), ramp(0, 0, 250),
                pause(200),
                // 右轉一圈、左轉一圈
                ramp(0, 800, 300), hold(0, 800, 2200), ramp(0, 0, 300), pause(300),
                ramp(0, -800, 300), hold(0, -800, 2200), ramp(0, 0, 300), pause(300),
                // 恰恰
                ramp(200, 0, 300), ramp(0, 0, 300), ramp(-200, 0, 300), ramp(0, 0, 300),
                ramp(200, 0, 300), ramp(0, 0, 300), ramp(-200, 0, 300), ramp(0, 0, 300),
                // 收尾抖動
                sineTurn(0, 550, 1800, 30), pause(400),
            ),
        ),
        ChoreoPreset(
            "lib-waltz", "華爾滋", "緩慢的弧線加優雅轉身", Icons.Rounded.MusicNote, 4, 1,
            listOf(
                ramp(0, -597, 400), ramp(0, 0, 400), ramp(180, 0, 500), sineTurn(180, 500, 6000, 150),
                ramp(0, 0, 500), ramp(0, 500, 400), hold(0, 500, 3600), ramp(0, 0, 400),
                ramp(0, 597, 400), ramp(0, 0, 400), pause(300),
            ),
        ),
        ChoreoPreset(
            "lib-patrol", "巡邏", "直走、迴轉，來回 2 趟", Icons.Rounded.Shield, 0, 4,
            listOf(
                ramp(300, 0, 500), hold(300, 0, 1500), ramp(0, 0, 500), pause(300),
                ramp(0, 700, 300), hold(0, 700, 1129), ramp(0, 0, 300), pause(300),
            ),
        ),
    )
}
