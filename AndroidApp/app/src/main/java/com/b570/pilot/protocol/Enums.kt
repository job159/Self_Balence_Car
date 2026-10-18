package com.b570.pilot.protocol

/** ACK / ESP_ACK 狀態。負值 code 是 App 本地狀態（不會出現在線路上）。 */
enum class AckStatus(val code: Int, val labelZh: String) {
    OK(0, "成功"),
    BAD_ARG(1, "參數錯誤"),
    BUSY(2, "忙碌中"),
    FAIL(3, "執行失敗"),
    NOT_READY(4, "尚未就緒"),
    UNKNOWN_TYPE(5, "不支援的指令"),

    /** 本地：重送 3 次仍沒有回覆。 */
    TIMEOUT(-1, "車子沒有回應"),

    /** 本地：目前沒有連線，指令沒有送出。 */
    OFFLINE(-2, "尚未連線"),

    /** 本地：收到無法辨識的狀態碼。 */
    UNKNOWN(-3, "未知狀態"),

    /** 本地：送出前或重送中被停止 / 緊急停止取消（只會出現在 MOTOR START、SEQ_PLAY；不提示）。 */
    CANCELLED(-4, "已取消");

    val isOk: Boolean get() = this == OK

    companion object {
        fun of(code: Int): AckStatus = entries.firstOrNull { it.code == code && it.code >= 0 } ?: UNKNOWN
    }
}

/** MOTOR 指令。 */
enum class MotorAction(val code: Int, val labelZh: String) {
    STOP(0, "停止"),
    START(1, "啟動"),
    ESTOP(2, "緊急停止"),
}

/** TELEM.stop_reason。 */
enum class StopReason(val code: Int, val labelZh: String) {
    RUNNING(0, "運轉中"),
    BOOT(1, "剛開機"),
    KEY(2, "按鍵停止"),
    APP(3, "手機停止"),
    PICKUP(4, "被拿起"),
    FALL(5, "傾倒"),
    LOW_BATT(6, "電量過低"),
    ESTOP(7, "緊急停止"),
    /** 姿態感測器（MPU6050）中斷停止，需檢查後重新開機。 */
    IMU(8, "感測器異常"),
    UNKNOWN(-1, "未知");

    companion object {
        fun of(code: Int): StopReason = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** TELEM.src：目前控制來源。 */
enum class ControlSource(val code: Int, val labelZh: String) {
    NONE(0, "無"),
    APP(1, "手機"),
    SEQUENCE(2, "編排"),
    UNKNOWN(-1, "未知");

    companion object {
        fun of(code: Int): ControlSource = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** EVENT code（§4.3）。 */
enum class EventCode(val code: Int) {
    MOTORS_ON(1),
    MOTORS_OFF(2),
    PICKED_UP(3),
    FELL(4),
    LOW_BATTERY(5),
    SEQ_DONE(6),
    SEQ_ABORTED(7),
    ZERO_CAL(8),
    PARAMS_SAVED(9),
    BOOT(10),
    LINK_LOST(11),
    UNKNOWN(-1);

    companion object {
        fun of(code: Int): EventCode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** INFO.angle_algo / 參數 ANGLE_ALGO。 */
enum class AngleAlgo(val code: Int, val labelZh: String) {
    DMP(1, "DMP"),
    KALMAN(2, "卡爾曼"),
    COMPLEMENTARY(3, "互補"),
    UNKNOWN(-1, "未知");

    companion object {
        fun of(code: Int): AngleAlgo = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** STA_INFO.state：熱點模式狀態（§3.4）。 */
enum class StaState(val code: Int, val labelZh: String) {
    OFF(0, "未啟用"),
    SEARCHING(1, "搜尋熱點中"),
    CONNECTED(2, "已連上熱點"),
    /**
     * 找不到熱點（或熱點斷線超過 15 s）→ 已改回直連 AP；沒有手機連上 AP 時自動再試熱點
     * （改開 AP 或手機離開 AP 後的快速期內約每 8 s 一次，之後每 30 s；§3.6）。
     */
    FALLBACK(3, "找不到熱點，已改回直連"),
    UNKNOWN(-1, "未知");

    companion object {
        fun of(code: Int): StaState = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
