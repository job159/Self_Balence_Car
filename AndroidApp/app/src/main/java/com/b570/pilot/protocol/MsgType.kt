package com.b570.pilot.protocol

/** 協定 §3 所有 TYPE。 */
object MsgType {
    // App -> STM32（ESP 透傳）
    const val CTRL = 0x01
    const val PING = 0x02
    const val MOTOR = 0x03
    const val ZERO_CAL = 0x04
    const val PARAM_GET = 0x10
    const val PARAM_SET = 0x11
    const val PARAM_SAVE = 0x12
    const val PARAM_DEFAULTS = 0x13
    const val SEQ_LOAD = 0x20
    const val SEQ_PLAY = 0x21
    const val SEQ_STOP = 0x22
    const val INFO_GET = 0x30

    // App -> ESP（ESP 自行處理，不轉送）
    const val HELLO = 0x40
    const val WIFI_GET = 0x41
    const val WIFI_SET = 0x42
    const val ESP_PING = 0x43
    const val BYE = 0x44
    const val WIFI_FACTORY = 0x45
    /** 熱點模式設定（§3.4 / §3.6）。 */
    const val STA_SET = 0x46
    const val STA_GET = 0x47
    /** 任何人、任何時候都可送（不需 session），可用廣播。 */
    const val DISCOVER = 0x48

    // ESP <-> STM32（只在 UART，不會出現在 UDP）
    const val LINK = 0x50
    const val CRED = 0x51
    const val CRED_REQ = 0x52
    const val NET = 0x53

    // STM32 -> App
    const val TELEM = 0x81
    const val PONG = 0x82
    const val PARAM_VALUES = 0x90
    const val ACK = 0x91
    const val INFO = 0xA0
    const val EVENT = 0xA1

    // ESP -> App
    const val HELLO_ACK = 0xC0
    const val WIFI_INFO = 0xC1
    const val ESP_PONG = 0xC2
    const val ESP_ACK = 0xC3
    const val STA_INFO = 0xC4
    const val DISCOVER_ACK = 0xC5

    /** 0x80–0xBF：由 STM32 產生（判斷主控板是否還活著）。 */
    fun isFromCar(type: Int): Boolean = type in 0x80..0xBF

    /** 0xC0–0xCF：由 ESP 產生。 */
    fun isFromEsp(type: Int): Boolean = type in 0xC0..0xCF

    fun name(type: Int): String = when (type) {
        CTRL -> "CTRL"; PING -> "PING"; MOTOR -> "MOTOR"; ZERO_CAL -> "ZERO_CAL"
        PARAM_GET -> "PARAM_GET"; PARAM_SET -> "PARAM_SET"; PARAM_SAVE -> "PARAM_SAVE"; PARAM_DEFAULTS -> "PARAM_DEFAULTS"
        SEQ_LOAD -> "SEQ_LOAD"; SEQ_PLAY -> "SEQ_PLAY"; SEQ_STOP -> "SEQ_STOP"; INFO_GET -> "INFO_GET"
        HELLO -> "HELLO"; WIFI_GET -> "WIFI_GET"; WIFI_SET -> "WIFI_SET"; ESP_PING -> "ESP_PING"; BYE -> "BYE"
        WIFI_FACTORY -> "WIFI_FACTORY"; STA_SET -> "STA_SET"; STA_GET -> "STA_GET"; DISCOVER -> "DISCOVER"
        LINK -> "LINK"; CRED -> "CRED"; CRED_REQ -> "CRED_REQ"; NET -> "NET"
        TELEM -> "TELEM"; PONG -> "PONG"; PARAM_VALUES -> "PARAM_VALUES"; ACK -> "ACK"; INFO -> "INFO"; EVENT -> "EVENT"
        HELLO_ACK -> "HELLO_ACK"; WIFI_INFO -> "WIFI_INFO"; ESP_PONG -> "ESP_PONG"; ESP_ACK -> "ESP_ACK"
        STA_INFO -> "STA_INFO"; DISCOVER_ACK -> "DISCOVER_ACK"
        else -> "0x%02X".format(type)
    }
}
