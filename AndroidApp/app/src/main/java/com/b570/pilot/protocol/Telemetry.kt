package com.b570.pilot.protocol

/**
 * TELEM（0x81，40 或 42 bytes）解碼成物理單位（§4.1）。解碼只要求 ≥ 40 bytes；
 * 42 bytes（v1.1 韌體）多了 [ctrlSeq] / [ctrlAge]（控制延遲量測用），舊韌體為 null。
 * 角度 °、角速度 °/s（原始值 / 16.4）、速度 mm/s、電壓 V。
 */
data class Telemetry(
    /** 控制週期計數（10 ms），u16 會溢位。 */
    val tick: Int,
    /** 平衡傾角 °，前傾為正。 */
    val pitchDeg: Float,
    val rollDeg: Float,
    /** 俯仰角速度 °/s。 */
    val gyroBalDps: Float,
    /** 偏航角速度 °/s（Z 軸）。 */
    val gyroTurnDps: Float,
    val velL: Int,
    val velR: Int,
    /** 左右馬達 PWM（±6900）。 */
    val pwmL: Int,
    val pwmR: Int,
    /** 直立環 / 速度環 / 轉向環輸出。 */
    val pwmBal: Int,
    val pwmVel: Int,
    val pwmTurn: Int,
    /** 經加速度限制後的目標速度 mm/s、目標轉向 ‰。 */
    val targetSpeed: Int,
    val targetTurn: Int,
    val batteryV: Float,
    /** 原始旗標，個別位元見下方 bool 屬性。 */
    val flags: Int,
    val stopReason: StopReason,
    /** 目前編排步驟索引；未播放 = null（線上 0xFF）。 */
    val seqStep: Int?,
    val seqLoop: Int,
    val source: ControlSource,
    /** 最近 1 秒收到的 App 幀數（飽和 255），正常約 50。 */
    val linkQ: Int,
    /** 航向 °（-180..180，陀螺積分，會漂移，僅供顯示）。 */
    val headingDeg: Float,
    /** 控制中斷執行時間 µs。 */
    val loopUs: Int,
    /** 最後收到的 CTRL 幀 SEQ（0..255，還沒收過 = 0）；40 bytes 的舊韌體 = null。 */
    val ctrlSeq: Int? = null,
    /** 那個 CTRL 到這筆遙測經過幾個 10 ms 控制週期（0..255，255 = 飽和 / 還沒收過）；舊韌體 = null。 */
    val ctrlAge: Int? = null,
) : Incoming {
    val motorsOn: Boolean get() = flags and FLAG_MOTORS_ON != 0
    val appLink: Boolean get() = flags and FLAG_APP_LINK != 0
    val seqPlaying: Boolean get() = flags and FLAG_SEQ_PLAYING != 0
    val lowBattery: Boolean get() = flags and FLAG_LOW_BATT != 0
    val upright: Boolean get() = flags and FLAG_UPRIGHT != 0
    val failsafe: Boolean get() = flags and FLAG_FAILSAFE != 0
    val paramsDirty: Boolean get() = flags and FLAG_PARAMS_DIRTY != 0
    val credOk: Boolean get() = flags and FLAG_CRED_OK != 0

    /** 平均車速 mm/s。 */
    val speedMmps: Float get() = (velL + velR) * 0.5f

    /** [ctrlSeq] 非 null 時編成 42 bytes（v1.1），否則 40 bytes。 */
    fun encode(): ByteArray {
        val b = ByteArray(if (ctrlSeq != null) SIZE_V11 else SIZE)
        Le.putU16(b, 0, tick and 0xFFFF)
        Le.putI16(b, 2, Math.round(pitchDeg * 100f))
        Le.putI16(b, 4, Math.round(rollDeg * 100f))
        Le.putI16(b, 6, Math.round(gyroBalDps * GYRO_LSB_PER_DPS))
        Le.putI16(b, 8, Math.round(gyroTurnDps * GYRO_LSB_PER_DPS))
        Le.putI16(b, 10, velL); Le.putI16(b, 12, velR)
        Le.putI16(b, 14, pwmL); Le.putI16(b, 16, pwmR)
        Le.putI16(b, 18, pwmBal); Le.putI16(b, 20, pwmVel); Le.putI16(b, 22, pwmTurn)
        Le.putI16(b, 24, targetSpeed); Le.putI16(b, 26, targetTurn)
        Le.putU16(b, 28, Math.round(batteryV * 100f).coerceIn(0, 0xFFFF))
        b[30] = flags.toByte()
        b[31] = stopReason.code.coerceAtLeast(0).toByte()
        b[32] = (seqStep ?: 0xFF).toByte()
        b[33] = seqLoop.toByte()
        b[34] = source.code.coerceAtLeast(0).toByte()
        b[35] = linkQ.coerceIn(0, 255).toByte()
        Le.putI16(b, 36, Math.round(headingDeg * 100f))
        Le.putU16(b, 38, loopUs.coerceIn(0, 0xFFFF))
        if (ctrlSeq != null) {
            b[40] = ctrlSeq.toByte()
            b[41] = (ctrlAge ?: 0).coerceIn(0, 255).toByte()
        }
        return b
    }

    companion object {
        /** 最小長度（v1；解碼只要求這麼多）。 */
        const val SIZE = 40
        /** v1.1：多了 ctrl_seq（@40）、ctrl_age（@41）。 */
        const val SIZE_V11 = 42
        /** ctrl_age 飽和值（還沒收過 CTRL 或超過 2.55 s）。 */
        const val CTRL_AGE_SATURATED = 255
        /** ctrl_age 單位：控制週期 10 ms。 */
        const val CTRL_AGE_UNIT_MS = 10
        const val GYRO_LSB_PER_DPS = 16.4f

        const val FLAG_MOTORS_ON = 1 shl 0
        const val FLAG_APP_LINK = 1 shl 1
        const val FLAG_SEQ_PLAYING = 1 shl 2
        const val FLAG_LOW_BATT = 1 shl 3
        const val FLAG_UPRIGHT = 1 shl 4
        const val FLAG_FAILSAFE = 1 shl 5
        const val FLAG_PARAMS_DIRTY = 1 shl 6
        const val FLAG_CRED_OK = 1 shl 7

        fun decode(p: ByteArray): Telemetry {
            if (p.size < SIZE) throw ProtocolException("TELEM needs $SIZE bytes, got ${p.size}")
            val step = Le.u8(p, 32)
            return Telemetry(
                tick = Le.u16(p, 0),
                pitchDeg = Le.i16(p, 2) / 100f,
                rollDeg = Le.i16(p, 4) / 100f,
                gyroBalDps = Le.i16(p, 6) / GYRO_LSB_PER_DPS,
                gyroTurnDps = Le.i16(p, 8) / GYRO_LSB_PER_DPS,
                velL = Le.i16(p, 10),
                velR = Le.i16(p, 12),
                pwmL = Le.i16(p, 14),
                pwmR = Le.i16(p, 16),
                pwmBal = Le.i16(p, 18),
                pwmVel = Le.i16(p, 20),
                pwmTurn = Le.i16(p, 22),
                targetSpeed = Le.i16(p, 24),
                targetTurn = Le.i16(p, 26),
                batteryV = Le.u16(p, 28) / 100f,
                flags = Le.u8(p, 30),
                stopReason = StopReason.of(Le.u8(p, 31)),
                seqStep = if (step == 0xFF) null else step,
                seqLoop = Le.u8(p, 33),
                source = ControlSource.of(Le.u8(p, 34)),
                linkQ = Le.u8(p, 35),
                headingDeg = Le.i16(p, 36) / 100f,
                loopUs = Le.u16(p, 38),
                ctrlSeq = if (p.size >= SIZE_V11) Le.u8(p, 40) else null,
                ctrlAge = if (p.size >= SIZE_V11) Le.u8(p, 41) else null,
            )
        }
    }
}
