package com.b570.pilot.ui.screens.drive

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import com.b570.pilot.data.Gear
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.Params
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.protocol.Telemetry
import kotlin.math.roundToInt

// ------------------------------------------------------------------ 檔位

/** 駕駛頁檔位標籤（慢 / 中 / 快）。 */
internal val Gear.driveLabel: String
    get() = when (this) {
        Gear.SLOW -> "慢"
        Gear.NORMAL -> "中"
        Gear.FAST -> "快"
    }

/** 搖桿推到底 = MAX_SPEED × 此比例（30 / 60 / 100 %，與設定頁共用 [Gear]）。 */
internal val Gear.driveSpeedFactor: Float get() = speedFactor

/** 搖桿推到底 = 1000‰ × 此比例（50 / 75 / 100 %）。 */
internal val Gear.driveTurnFactor: Float get() = turnFactor

internal val MAX_SPEED_DEFAULT: Float = Params.byId(Params.MAX_SPEED)?.default ?: 500f
internal val FALL_ANGLE_DEFAULT: Float = Params.byId(Params.FALL_ANGLE)?.default ?: 40f

// ------------------------------------------------------------------ 控制通道

/**
 * 輸入 → CTRL 的即時通道。觸控 / 感測器回呼（主執行緒）直接同步呼叫 [sink]（= vm.setControl），
 * 不經 coroutine、不 debounce；連線層固定 50 Hz 送出最新值。
 * @param sink (車速 mm/s, 轉向 ‰)
 */
@Stable
internal class DriveController(private val sink: (Int, Int) -> Unit) {
    private var forward = 0f
    private var turn = 0f
    private var maxSpeed = MAX_SPEED_DEFAULT
    private var gear = Gear.NORMAL

    /** 目前送出的車速 mm/s / 轉向 ‰（只給小葉節點讀）。 */
    val cmdSpeed = mutableIntStateOf(0)
    val cmdTurn = mutableIntStateOf(0)

    /** 檔位或 MAX_SPEED 改變：若搖桿正被推著，立即用新比例重送。 */
    fun configure(maxSpeed: Float, gear: Gear) {
        // 車上回報的 MAX_SPEED 異常（NaN / ≤ 0）→ 用預設值，不讓 roundToInt 丟例外
        val ms = if (maxSpeed.isFinite() && maxSpeed > 0f) maxSpeed else MAX_SPEED_DEFAULT
        if (ms == this.maxSpeed && gear == this.gear) return
        this.maxSpeed = ms
        this.gear = gear
        if (forward != 0f || turn != 0f) send()
    }

    /** 單搖桿 / 體感：同時設定兩軸（-1..1，前進 / 右轉為正）。 */
    fun set(forward: Float, turn: Float) {
        this.forward = forward
        this.turn = turn
        send()
    }

    /** 雙搖桿左手油門。 */
    fun setForward(value: Float) {
        forward = value
        send()
    }

    /** 雙搖桿右手方向。 */
    fun setTurn(value: Float) {
        turn = value
        send()
    }

    /** 歸零（放開、切換模式、離開畫面、退到背景、緊急停止）。 */
    fun stop() {
        forward = 0f
        turn = 0f
        sink(0, 0)
        cmdSpeed.intValue = 0
        cmdTurn.intValue = 0
    }

    private fun send() {
        val s = (axis(forward) * maxSpeed * gear.driveSpeedFactor).roundToInt()
        val t = (axis(turn) * 1000f * gear.driveTurnFactor).roundToInt()
        sink(s, t)
        cmdSpeed.intValue = s
        cmdTurn.intValue = t
    }

    /** -1..1；NaN（換算錯誤）→ 0：NaN.coerceIn 仍是 NaN，roundToInt 會在觸控事件裡丟例外讓 App 當掉。 */
    private fun axis(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(-1f, 1f)
}

// ------------------------------------------------------------------ 情境提示

/** 情境列（快捷動作那一列）要顯示的提示；NONE = 顯示快捷動作。 */
internal enum class DriveNotice(val titleZh: String, val bodyZh: String) {
    NONE("", ""),
    ESTOP("已緊急停止", "確認周圍安全後，再按「啟動平衡」"),
    PICKUP("車子被拿起，已自動停止", "放回地面扶正後，按「啟動平衡」"),
    FALL("偵測到傾倒，已自動停止", "把車子扶正後，按「啟動平衡」"),
    LOW_BATT("電量過低，已自動停止", "請先充電再繼續駕駛"),
    IMU("姿態感測器異常，已停止", "請檢查感測器後重新開機"),
    FAILSAFE("車子收不到控制訊號，已自動煞停", "靠近車子一點，訊號恢復後即可繼續"),
    SEQUENCE("編排播放中，推動搖桿即可接管", ""),
}

internal fun noticeOf(t: Telemetry?): DriveNotice = when {
    t == null -> DriveNotice.NONE
    !t.motorsOn && t.stopReason == StopReason.ESTOP -> DriveNotice.ESTOP
    !t.motorsOn && t.stopReason == StopReason.PICKUP -> DriveNotice.PICKUP
    !t.motorsOn && t.stopReason == StopReason.FALL -> DriveNotice.FALL
    !t.motorsOn && t.stopReason == StopReason.LOW_BATT -> DriveNotice.LOW_BATT
    !t.motorsOn && t.stopReason == StopReason.IMU -> DriveNotice.IMU
    t.failsafe -> DriveNotice.FAILSAFE
    t.seqPlaying -> DriveNotice.SEQUENCE
    else -> DriveNotice.NONE
}

/**
 * 蓋在操控區上的狀態（覆蓋層本身不攔截觸控；[blocksInput] 的狀態由搖桿 / 按住駕駛自己停用）。
 */
internal enum class ControlBlock(val titleZh: String, val bodyZh: String) {
    NONE("", ""),
    OFFLINE("尚未連線", "連上車子後即可駕駛"),
    CONNECTING("連線中…", "請稍候"),
    /** 斷線後自動重連中（連線層在 Failed / Idle / 連線中之間切換，提示維持同一句）。 */
    RECONNECTING("自動重連中…", "連回後請重新按下搖桿"),
    WAITING("等待車子資料…", "馬上就好"),
    LINK_LOST("訊號中斷", "車子收不到指令時會自動煞停"),
    SENSOR("姿態感測器異常", "請檢查感測器後重新開機"),
    LOW_BATT("電量過低", "請先充電，車子才能啟動"),
    MOTORS_OFF("先按「啟動平衡」", "車子站穩後就能開始駕駛"),
    ;

    /**
     * 這些狀態下搖桿 / 按住駕駛停用並歸零：訊號中斷時不再累積指令，恢復後也要先放開再重新按下才會送出
     * （避免車子一收到訊號就直接衝到還按著的速度）。
     */
    val blocksInput: Boolean get() = this == LINK_LOST || this == OFFLINE || this == CONNECTING || this == RECONNECTING
}

/**
 * @param reconnecting 自動重連中（`vm.reconnect != null`）：未連線 / 連線中改顯示「自動重連中…」（一樣封鎖輸入）
 */
internal fun controlBlockOf(st: ConnectionState, t: Telemetry?, reconnecting: Boolean = false): ControlBlock = when {
    st is ConnectionState.LinkLost -> ControlBlock.LINK_LOST
    reconnecting && !st.isSession -> ControlBlock.RECONNECTING
    st.isConnecting -> ControlBlock.CONNECTING
    !st.isSession -> ControlBlock.OFFLINE
    t == null -> ControlBlock.WAITING
    t.motorsOn -> ControlBlock.NONE
    t.stopReason == StopReason.IMU -> ControlBlock.SENSOR
    t.lowBattery -> ControlBlock.LOW_BATT
    else -> ControlBlock.MOTORS_OFF
}

/** 姿態感測器異常（韌體控制迴圈已停）：「啟動平衡」不會有作用，要停用。 */
internal fun startBlockedOf(t: Telemetry?): Boolean = t != null && !t.motorsOn && t.stopReason == StopReason.IMU

/** 最近一次由駕駛頁快捷動作開始播放的時間（離開畫面再回來，進度條仍能接續）。只在主執行緒存取。 */
internal object QuickPlayMemory {
    var id: String? = null
        private set
    var startMs: Long = 0L
        private set

    fun mark(id: String) {
        this.id = id
        startMs = SystemClock.elapsedRealtime()
    }
}
