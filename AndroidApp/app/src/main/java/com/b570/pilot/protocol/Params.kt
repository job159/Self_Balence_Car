package com.b570.pilot.protocol

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** 參數分組（整定頁的區塊）。 */
enum class ParamGroup(val labelZh: String) {
    BALANCE("直立環"),
    VELOCITY("速度環"),
    TURN("轉向環"),
    MOTION("運動限制"),
    SAFETY("安全保護"),
    SYSTEM("系統"),
}

/**
 * 參數定義（§4.2）。UI 的唯一資料來源。
 * @param step UI 微調步進
 * @param options 列舉型參數的選項（值 → 中文），null = 連續數值
 * @param requiresMotorsOff 馬達停止時才可修改
 */
data class ParamDef(
    val id: Int,
    val key: String,
    val labelZh: String,
    val descZh: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val step: Float,
    val isInteger: Boolean,
    val group: ParamGroup,
    val requiresMotorsOff: Boolean = false,
    val options: List<Pair<Float, String>>? = null,
) {
    /** 依步進推算的顯示小數位數。 */
    val decimals: Int
        get() = if (isInteger || step >= 1f) 0 else (-log10(step.toDouble()) + 0.999).toInt().coerceIn(0, 4)

    /** 夾限並（整數型）四捨五入，與韌體行為一致。 */
    fun clamp(v: Float): Float {
        var c = if (v.isNaN()) default else v.coerceIn(min, max)
        // 與韌體一致：遙測實際頻率 = 100 Hz / 整數分頻（例如設 70 → 100）
        if (id == Params.TELEM_HZ) c = 100f / (100f / c).roundToInt().coerceAtLeast(1)
        return if (isInteger) c.roundToInt().toFloat() else c
    }

    /** 格式化顯示（含單位）。 */
    fun format(v: Float, withUnit: Boolean = true): String {
        options?.firstOrNull { abs(it.first - v) < 0.001f }?.let { return it.second }
        val s = String.format(java.util.Locale.US, "%.${decimals}f", v)
        return if (withUnit && unit.isNotEmpty()) "$s $unit" else s
    }
}

/** 全部 17 個參數。 */
object Params {
    const val ALL_ID = 0xFF

    const val BAL_KP = 0
    const val BAL_KD = 1
    const val VEL_KP = 2
    const val VEL_KI = 3
    const val TURN_KP = 4
    const val TURN_KD = 5
    const val ZERO_ANGLE = 6
    const val MAX_SPEED = 7
    const val MAX_TURN = 8
    const val ACCEL = 9
    const val TURN_ACCEL = 10
    const val FAILSAFE_MS = 11
    const val TELEM_HZ = 12
    const val ANGLE_ALGO = 13
    const val PICKUP_EN = 14
    const val LOWBAT_V = 15
    const val FALL_ANGLE = 16

    val ALL: List<ParamDef> = listOf(
        ParamDef(BAL_KP, "BAL_KP", "直立 Kp", "站穩的力道，越大越硬，太大會高頻抖動", "", 0f, 60000f, 22500f, 100f, false, ParamGroup.BALANCE),
        ParamDef(BAL_KD, "BAL_KD", "直立 Kd", "抑制前後晃動的阻尼，太大會發出嗡嗡聲", "", 0f, 600f, 108f, 1f, false, ParamGroup.BALANCE),
        ParamDef(ZERO_ANGLE, "ZERO_ANGLE", "機械零點", "平衡時的傾角偏移；車子自己往前或往後跑就調這裡", "°", -10f, 10f, 0f, 0.05f, false, ParamGroup.BALANCE),
        ParamDef(VEL_KP, "VEL_KP", "速度 Kp", "追上目標速度的力道，太大會前後擺盪", "", 0f, 40000f, 16000f, 100f, false, ParamGroup.VELOCITY),
        ParamDef(VEL_KI, "VEL_KI", "速度 Ki", "消除速度誤差，讓車子停得住", "", 0f, 400f, 80f, 1f, false, ParamGroup.VELOCITY),
        ParamDef(TURN_KP, "TURN_KP", "轉向 Kp", "轉向反應的力道", "", 0f, 12000f, 4200f, 50f, false, ParamGroup.TURN),
        ParamDef(TURN_KD, "TURN_KD", "轉向 Kd", "行進時抑制左右擺頭（陀螺阻尼）", "", 0f, 300f, 0f, 1f, false, ParamGroup.TURN),
        ParamDef(MAX_SPEED, "MAX_SPEED", "最高車速", "搖桿推到底時的速度", "mm/s", 100f, 1000f, 500f, 10f, false, ParamGroup.MOTION),
        ParamDef(MAX_TURN, "MAX_TURN", "最大轉向", "搖桿推到底時的轉向量（原廠一般 27、高速 54）", "", 5f, 80f, 40f, 1f, false, ParamGroup.MOTION),
        ParamDef(ACCEL, "ACCEL", "加速度上限", "速度變化的最快速率，越小越平順", "mm/s²", 100f, 6000f, 2000f, 50f, false, ParamGroup.MOTION),
        ParamDef(TURN_ACCEL, "TURN_ACCEL", "轉向變化率", "轉向變化的最快速率，越小越柔和", "‰/s", 500f, 20000f, 12000f, 100f, false, ParamGroup.MOTION),
        ParamDef(FAILSAFE_MS, "FAILSAFE_MS", "失聯保護", "超過這段時間沒收到指令就自動煞停", "ms", 150f, 2000f, 400f, 50f, true, ParamGroup.SAFETY),
        ParamDef(PICKUP_EN, "PICKUP_EN", "拿起停機", "偵測到車子被拿起時自動停止馬達", "", 0f, 1f, 1f, 1f, true, ParamGroup.SAFETY,
            options = listOf(0f to "關閉", 1f to "開啟")),
        ParamDef(LOWBAT_V, "LOWBAT_V", "低電壓門檻", "電池低於此電壓自動停機", "V", 9f, 12f, 10f, 0.1f, false, ParamGroup.SAFETY),
        ParamDef(FALL_ANGLE, "FALL_ANGLE", "傾倒角度", "傾角超過此值判定傾倒並停機", "°", 20f, 60f, 40f, 1f, false, ParamGroup.SAFETY),
        ParamDef(TELEM_HZ, "TELEM_HZ", "遙測頻率", "車子每秒回傳資料的次數", "Hz", 10f, 100f, 50f, 10f, true, ParamGroup.SYSTEM),
        ParamDef(ANGLE_ALGO, "ANGLE_ALGO", "姿態演算法", "計算傾角的方法，儲存到車上並重新開機後才切換", "", 1f, 3f, 2f, 1f, true, ParamGroup.SYSTEM,
            options = listOf(1f to "DMP", 2f to "卡爾曼", 3f to "互補")),
    ).sortedBy { it.id }

    private val byIdArr: Array<ParamDef?> = arrayOfNulls<ParamDef>(ALL.size).also { a -> ALL.forEach { a[it.id] = it } }

    val COUNT: Int get() = ALL.size

    fun byId(id: Int): ParamDef? = byIdArr.getOrNull(id)
    fun byKey(key: String): ParamDef? = ALL.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /** 依 UI 分組（保持 [ParamGroup] 順序）。 */
    val BY_GROUP: Map<ParamGroup, List<ParamDef>> =
        ParamGroup.entries.associateWith { g -> ALL.filter { it.group == g } }

    /** 全部預設值。 */
    fun defaults(): Map<Int, Float> = ALL.associate { it.id to it.default }

    /** 夾限；未知 id 原樣回傳。 */
    fun clamp(id: Int, v: Float): Float = byId(id)?.clamp(v) ?: v
}
