package com.b570.pilot.protocol

/** 編排步驟模式（§3.2）。 */
enum class SeqMode(val code: Int, val labelZh: String, val descZh: String) {
    HOLD(0, "定值", "速度與轉向維持固定"),
    SINE_TURN(1, "正弦轉向", "速度固定，轉向依正弦來回擺動"),
    SINE_SPEED(2, "正弦速度", "轉向固定，速度依正弦前後擺動"),
    RAMP(3, "漸變", "從上一步的結束值平滑變化到本步數值");

    companion object {
        fun of(code: Int): SeqMode = entries.firstOrNull { it.code == code } ?: HOLD
    }
}

/**
 * 一個編排步驟（線上 8 bytes：i16 speed, i16 turn, u16 dur_ms, u8 mode, u8 period）。
 * @param speed 目標車速 mm/s（正 = 前進）
 * @param turn 轉向 ‰（-1000..1000，正 = 右轉）
 * @param durMs 持續時間 20..30000 ms
 * @param period20ms 正弦週期，單位 20 ms（1..255）；0 由韌體視為 50（1 s）
 */
data class SeqStep(
    val speed: Int,
    val turn: Int,
    val durMs: Int,
    val mode: SeqMode = SeqMode.HOLD,
    val period20ms: Int = 50,
) {
    /** 實際正弦週期（ms）。 */
    val periodMs: Int get() = (if (period20ms == 0) 50 else period20ms) * 20

    /** 夾限到協定允許範圍。 */
    fun clamped(): SeqStep = copy(
        speed = speed.coerceIn(-MAX_SPEED_ABS, MAX_SPEED_ABS),
        turn = turn.coerceIn(-1000, 1000),
        durMs = durMs.coerceIn(MIN_DUR_MS, MAX_DUR_MS),
        period20ms = period20ms.coerceIn(0, 255),
    )

    fun encodeTo(w: ByteWriter) {
        w.i16(speed).i16(turn).u16(durMs).u8(mode.code).u8(period20ms)
    }

    fun encode(): ByteArray = ByteWriter(SIZE).also { encodeTo(it) }.toByteArray()

    companion object {
        const val SIZE = 8
        const val MAX_STEPS = 48
        const val MAX_PER_FRAME = 24
        const val MIN_DUR_MS = 20
        const val MAX_DUR_MS = 30000
        const val MAX_SPEED_ABS = 1000

        fun decode(r: ByteReader): SeqStep =
            SeqStep(r.i16(), r.i16(), r.u16(), SeqMode.of(r.u8()), r.u8())

        /** 整段編排總時間（單趟，ms）。 */
        fun totalDurationMs(steps: List<SeqStep>): Long = steps.sumOf { it.durMs.toLong() }
    }
}
