package com.b570.pilot.link

/**
 * 決定每個 20 ms 週期要不要送 CTRL —— **防止控制封包在手機的傳送佇列堆積**。
 *
 * 問題：Wi-Fi 一時變慢（干擾、手機掃描、同時連兩個 Wi-Fi）時，手機照樣每秒送 50 個 CTRL，
 * 送不出去的封包在手機裡排隊（可達數百個），車子收到的是好幾秒前的舊指令（「控制超延遲」）；
 * ESP-01 緩衝很小、送不出去就丟，所以下行遙測看起來仍然很順。
 *
 * 對策（搭配 socket 傳送緩衝縮到最小，見 CarConnection.configureSocket）：
 * - 搖桿在動、或停下後 [ACTIVE_TICKS] 內：每週期送（50 Hz）。
 * - 靜止：每 [IDLE_INTERVAL] 週期送一次心跳（10 Hz；主控板失聯保護 400 ms、ESP session 1.5 s 都足夠）。
 * - 壅塞（量到的控制延遲 EMA > [CONGESTED_ON_MS]）：最多每 [CONGESTED_INTERVAL] 週期一次，讓佇列消化；
 *   EMA < [CONGESTED_OFF_MS] 才恢復（遲滯，避免來回切換）。
 * - 放開搖桿（值變成 0）那一刻**一定立即送**，停車指令不等降頻。
 * 每次送出的都是「當下最新」的搖桿值，舊值從來不會排隊。
 *
 * 只在傳送執行緒呼叫 [shouldSend]；[onLatencySample] 由接收執行緒呼叫（只寫一個 volatile）。
 */
class ControlPacer {

    private var lastSentValue = Int.MIN_VALUE
    private var lastChangeTick = Long.MIN_VALUE / 4
    private var lastSendTick = Long.MIN_VALUE / 4
    private var prevValue = Int.MIN_VALUE

    @Volatile private var latencyEma = Float.NaN
    @Volatile var congested = false
        private set

    /** 收到一個控制延遲樣本（ms）。 */
    fun onLatencySample(ms: Float) {
        if (ms.isNaN() || ms < 0f) return
        val e = latencyEma
        val n = if (e.isNaN()) ms else e * 0.8f + ms * 0.2f
        latencyEma = n
        congested = if (congested) n > CONGESTED_OFF_MS else n > CONGESTED_ON_MS
    }

    /** 這個週期（[tick]，每 20 ms +1）要不要送目前的控制值 [value]？回傳 true 時視為已送出。 */
    fun shouldSend(tick: Long, value: Int): Boolean {
        if (value != prevValue) {
            lastChangeTick = tick
            prevValue = value
        }
        val releaseNow = value == 0 && lastSentValue != 0
        val interval = when {
            congested -> CONGESTED_INTERVAL
            tick - lastChangeTick <= ACTIVE_TICKS -> 1L
            else -> IDLE_INTERVAL
        }
        val send = releaseNow || tick - lastSendTick >= interval
        if (send) {
            lastSendTick = tick
            lastSentValue = value
        }
        return send
    }

    fun reset() {
        lastSentValue = Int.MIN_VALUE
        lastChangeTick = Long.MIN_VALUE / 4
        lastSendTick = Long.MIN_VALUE / 4
        prevValue = Int.MIN_VALUE
        latencyEma = Float.NaN
        congested = false
    }

    companion object {
        /** 搖桿停下後仍維持 50 Hz 的週期數（300 ms）。 */
        const val ACTIVE_TICKS = 15L
        /** 靜止時的心跳間隔（5 × 20 ms = 10 Hz）。 */
        const val IDLE_INTERVAL = 5L
        /** 壅塞時的最小間隔（3 × 20 ms ≈ 16 Hz）。 */
        const val CONGESTED_INTERVAL = 3L
        const val CONGESTED_ON_MS = 150f
        const val CONGESTED_OFF_MS = 80f
    }
}
