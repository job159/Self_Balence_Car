package com.b570.pilot.link

import com.b570.pilot.protocol.Frame
import com.b570.pilot.protocol.MotorAction
import com.b570.pilot.protocol.MsgType
import kotlinx.coroutines.flow.StateFlow

/**
 * 「一台車」的傳輸抽象。實作：[CarConnection]（真車，UDP）與 data.DemoTelemetrySource（展示模式）。
 * 一般畫面不需要直接使用，請透過 CarRepository。
 */
interface CarLink {
    val state: StateFlow<ConnectionState>

    /** 開始連線（會先停止前一次連線）。直連車子的 AP。 */
    fun start(credentials: WifiCredentials)

    /**
     * 開始連線（會先停止前一次連線），依 [request] 先在手機網路（熱點）上找車，再視需要直連。
     * 預設實作（展示模式）= [start] (credentials)。
     */
    fun start(request: LinkRequest) = start(request.credentials)

    /** 中斷連線並釋放所有資源，狀態回到 Idle。 */
    fun stop()

    /** 設定 50 Hz CTRL 的內容（不阻塞、原子操作）。 */
    fun setControl(speedMmps: Int, turnPermille: Int)

    /** 送出一個幀（不等回覆，自動編 SEQ）。未連線時忽略並回傳 false。 */
    fun send(type: Int, payload: ByteArray): Boolean

    /**
     * 可靠請求：送出後等待 [matcher] 接受的回覆，逾時 [timeoutMs] 重送，最多重送 [retries] 次。
     * 同一時間只有一個請求在等（內部序列化，因為 ACK 不帶 SEQ）。
     * @return 回覆的幀；逾時或未連線回傳 null。
     */
    suspend fun request(
        type: Int,
        payload: ByteArray,
        timeoutMs: Long = 400,
        retries: Int = 3,
        matcher: (Frame) -> Boolean,
    ): Frame?

    /**
     * 緊急停止 / 停止時呼叫：中止所有「會讓車子動起來」的請求（[isMotionCommand]：MOTOR START、SEQ_PLAY）。
     * 進行中的不再重送並立即回傳 null、還在排隊的直接回傳 null、傳送佇列裡還沒送出的同類幀丟掉。
     * 之後才發起的請求不受影響。（韌體不會對 MOTOR 去重，START 的重送會讓剛緊急停止的馬達又啟動。）
     */
    fun abortMotionRequests() {}
}

/** 會讓車子動起來的指令：MOTOR START、SEQ_PLAY。緊急停止 / 停止之後，先前發起的這類請求不得再送出。 */
fun isMotionCommand(type: Int, payload: ByteArray): Boolean =
    isMotionCommand(type, if (payload.isEmpty()) -1 else payload[0].toInt() and 0xFF)

/** [isMotionCommand]，以 payload 第一個 byte（沒有 = -1）判斷。 */
fun isMotionCommand(type: Int, firstPayloadByte: Int): Boolean =
    type == MsgType.SEQ_PLAY || (type == MsgType.MOTOR && firstPayloadByte == MotorAction.START.code)

/** 已組好的整幀（§2：LEN 在 [2]、TYPE 在 [3]、PAYLOAD 從 [5] 開始）是否為 [isMotionCommand]。 */
fun isMotionFrame(frame: ByteArray): Boolean {
    if (frame.size < 7) return false
    val len = frame[2].toInt() and 0xFF
    return isMotionCommand(frame[3].toInt() and 0xFF, if (len > 0 && frame.size > 5) frame[5].toInt() and 0xFF else -1)
}

/** CarLink 回報給 CarRepository 的事件（可能在任何執行緒呼叫，實作要快且不阻塞）。 */
interface LinkListener {
    /** 每個通過 CRC 的幀（含 PONG / HELLO_ACK）。在接收執行緒呼叫。 */
    fun onFrame(frame: Frame)

    /** App ⇄ 車子（STM32）來回延遲。 */
    fun onRtt(ms: Float)

    /** App ⇄ ESP 純 Wi-Fi 來回延遲。 */
    fun onEspRtt(ms: Float)

    /** 手機端看到的 Wi-Fi 訊號強度 dBm。 */
    fun onRssi(dbm: Int)

    /** 解析器 CRC 錯誤累計。 */
    fun onCrcErrors(total: Long) {}

    /**
     * 控制延遲樣本 ms（手機送出 CTRL → 車上真正套用，含那筆遙測的下行；見 [ControlTiming]）。
     * 每筆 v1.1 遙測（42 bytes）一個，在接收執行緒呼叫。
     */
    fun onControlLatency(ms: Float) {}

    /**
     * 上行（手機 → 主控板）樣本，約每 0.5 s：[sent] = 手機最近 1 秒送出的主控板幀數，
     * [received] = 主控板回報最近 1 秒收到的 App 幀數（TELEM.link_q）。
     */
    fun onUplinkSample(sent: Int, received: Int) {}

    /** 直連時手機同時連著另一個有網際網路的 Wi-Fi（STA + STA 併行，無線電要分時，延遲會變高）。 */
    fun onDualWifi(active: Boolean) {}

    /** 這段連線的路徑（連上時通知一次）。 */
    fun onLinkMode(mode: LinkMode?) {}

    /** 在手機網路上找到（或重新找到）這台車（DISCOVER_ACK）。repository 用來記住 lastIp / 熱點模式。 */
    fun onDiscovered(car: DiscoveredCar) {}
}
