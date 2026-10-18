package com.b570.pilot.data

import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.Event
import com.b570.pilot.protocol.EventCode
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.protocol.Telemetry
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 提示等級（決定顏色與圖示）。 */
enum class Severity { INFO, SUCCESS, WARNING, DANGER }

/**
 * 給 UI 的提示事件（CarRepository.events）：車子送來的 EVENT 解碼結果，或 App 本地通知。
 * [messageZh] 已是可直接顯示的中文短句。
 */
data class CarEvent(
    val messageZh: String,
    val severity: Severity,
    /** 車子事件代碼；本地通知為 null。 */
    val code: EventCode? = null,
    val arg: Int = 0,
    val timeMs: Long = System.currentTimeMillis(),
    val id: Long = NEXT_ID.incrementAndGet(),
) {
    val isLocal: Boolean get() = code == null

    companion object {
        private val NEXT_ID = AtomicLong(0)

        fun notice(messageZh: String, severity: Severity = Severity.INFO) = CarEvent(messageZh, severity)

        /** 車子 EVENT → 中文提示；不需要提示的事件回傳 null。 */
        fun fromEvent(e: Event): CarEvent? {
            val a = e.arg
            return when (e.code) {
                EventCode.MOTORS_ON -> CarEvent(
                    when (a) { 1 -> "馬達已啟動（按鍵）"; 3 -> "已放下，馬達自動啟動"; else -> "馬達已啟動" },
                    Severity.SUCCESS, e.code, a,
                )
                EventCode.MOTORS_OFF -> when (StopReason.of(a)) {
                    // 這些原因另有專屬事件，避免重複提示
                    StopReason.PICKUP, StopReason.FALL, StopReason.LOW_BATT -> null
                    StopReason.ESTOP -> CarEvent("已緊急停止", Severity.DANGER, e.code, a)
                    StopReason.IMU -> CarEvent("姿態感測器異常，馬達已停止。請檢查後重新開機", Severity.DANGER, e.code, a)
                    StopReason.KEY -> CarEvent("馬達已停止（按鍵）", Severity.INFO, e.code, a)
                    else -> CarEvent("馬達已停止", Severity.INFO, e.code, a)
                }
                EventCode.PICKED_UP -> CarEvent("已被拿起，馬達自動停止", Severity.WARNING, e.code, a)
                EventCode.FELL -> CarEvent(String.format(Locale.US, "傾倒保護啟動（%.0f°）", a / 100f), Severity.DANGER, e.code, a)
                EventCode.LOW_BATTERY -> CarEvent(String.format(Locale.US, "電量過低（%.2f V），請充電", a / 100f), Severity.DANGER, e.code, a)
                EventCode.SEQ_DONE -> CarEvent("編排完成", Severity.SUCCESS, e.code, a)
                EventCode.SEQ_ABORTED -> CarEvent(
                    when (a) {
                        1 -> "手動接管，已停止編排"
                        2 -> "連線中斷，編排已停止"
                        3 -> "馬達停止，編排已中止"
                        else -> "編排已停止"
                    },
                    if (a == 4) Severity.INFO else Severity.WARNING, e.code, a,
                )
                EventCode.ZERO_CAL -> CarEvent(String.format(Locale.US, "零點已校正為 %.2f°", a / 100f), Severity.SUCCESS, e.code, a)
                EventCode.PARAMS_SAVED -> {
                    val st = AckStatus.of(a)
                    if (st.isOk) CarEvent("參數已儲存", Severity.SUCCESS, e.code, a)
                    else CarEvent(ackFailureZh(MsgType.PARAM_SAVE, st), Severity.DANGER, e.code, a)
                }
                EventCode.BOOT -> CarEvent("車子已重新開機（韌體 v${(a shr 8) and 0xFF}.${a and 0xFF}）", Severity.INFO, e.code, a)
                EventCode.LINK_LOST -> CarEvent("連線中斷，已自動煞停", Severity.WARNING, e.code, a)
                EventCode.UNKNOWN -> null
            }
        }

        /** 指令失敗的中文說明（依指令種類給出可行動的提示）。 */
        fun ackFailureZh(refType: Int, status: AckStatus): String = when (status) {
            AckStatus.TIMEOUT -> "車子沒有回應，請再試一次"
            AckStatus.OFFLINE -> "尚未連線"
            AckStatus.CANCELLED -> "已取消"
            else -> when (refType) {
                MsgType.PARAM_SAVE -> when (status) {
                    AckStatus.BUSY -> "馬達運轉中，請先停止再儲存"
                    else -> "參數儲存失敗"
                }
                MsgType.ZERO_CAL -> when (status) {
                    AckStatus.BUSY, AckStatus.NOT_READY -> "請先停止馬達再校正零點"
                    AckStatus.BAD_ARG -> "車身傾斜超過 10°，請扶正後再校正"
                    else -> "零點校正失敗"
                }
                MsgType.MOTOR -> when (status) {
                    AckStatus.NOT_READY, AckStatus.BUSY -> "現在無法啟動：請扶正車身並確認電量"
                    else -> "馬達指令失敗（${status.labelZh}）"
                }
                MsgType.SEQ_LOAD -> when (status) {
                    AckStatus.BUSY -> "編排播放中，請先停止"
                    AckStatus.BAD_ARG -> "編排內容有誤，請檢查步驟"
                    else -> "編排載入失敗"
                }
                MsgType.SEQ_PLAY -> when (status) {
                    AckStatus.NOT_READY -> "請先按「啟動平衡」再播放編排"
                    else -> "編排播放失敗（${status.labelZh}）"
                }
                MsgType.WIFI_SET, MsgType.WIFI_FACTORY -> "Wi-Fi 設定失敗（${status.labelZh}）"
                MsgType.STA_SET, MsgType.STA_GET -> when (status) {
                    AckStatus.UNKNOWN_TYPE -> "車子的 Wi-Fi 模組韌體太舊，不支援熱點模式，請先更新 ESP-01"
                    AckStatus.BUSY -> "另一支手機正在控制這台車，無法變更熱點設定"
                    AckStatus.BAD_ARG -> "熱點名稱或密碼格式不正確"
                    AckStatus.FAIL -> "熱點設定寫入失敗，請再試一次"
                    else -> "熱點設定失敗（${status.labelZh}）"
                }
                else -> "指令失敗（${status.labelZh}）"
            }
        }

        /**
         * MOTOR START 被拒（NOT_READY）的真正原因。比照韌體 can_start()：先看電壓（開機約 1 秒內還沒有讀數、
         * 低於 LOWBAT_V），再看傾角（|pitch − ZERO_ANGLE| > FALL_ANGLE）。[t] = 最新遙測，[lowBatV] = LOWBAT_V 參數。
         */
        fun motorStartRefusedZh(t: Telemetry?, lowBatV: Float): String = when {
            t == null -> "現在無法啟動：請扶正車身並確認電量"
            t.stopReason == StopReason.IMU -> "姿態感測器異常，請檢查後重新開機"
            t.batteryV <= 0.05f -> "車子剛開機，還在量測電壓，請稍候再試"
            t.lowBattery || t.batteryV < lowBatV ->
                String.format(Locale.US, "電量過低（%.2f V），請先充電", t.batteryV)
            !t.upright -> "現在無法啟動：請把車子扶正再試"
            else -> "車子還沒準備好：請扶穩車身、確認電量後再試"
        }
    }
}
