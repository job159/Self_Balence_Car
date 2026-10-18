package com.b570.pilot.protocol

// =====================================================================
//  App 送出的訊息（App -> STM32 / App -> ESP）
// =====================================================================

/** App 送出的訊息：[type] + [encode] 產生 payload。用 [toFrame] 組幀。 */
sealed interface Outgoing {
    val type: Int
    fun encode(): ByteArray
    fun toFrame(seq: Int): ByteArray = FrameCodec.build(type, seq, encode())
}

/** CTRL：目標車速 mm/s（正 = 前進）、轉向 ‰（-1000..1000，正 = 右轉）。同時是心跳。 */
data class Ctrl(val speedMmps: Int, val turnPermille: Int, val flags: Int = 0) : Outgoing {
    override val type get() = MsgType.CTRL
    override fun encode(): ByteArray = ByteArray(5).also {
        Le.putI16(it, 0, speedMmps.coerceIn(-32768, 32767))
        Le.putI16(it, 2, turnPermille.coerceIn(-1000, 1000))
        it[4] = flags.toByte()
    }

    companion object {
        const val SIZE = 5
        fun decode(p: ByteArray) = ByteReader(p).let { Ctrl(it.i16(), it.i16(), it.u8()) }

        /** 不配置版本：把 payload 寫進 [out]（從 [off] 開始 5 bytes），夾限規則與 [encode] 相同。 */
        fun writePayload(out: ByteArray, off: Int, speedMmps: Int, turnPermille: Int, flags: Int = 0) {
            Le.putI16(out, off, speedMmps.coerceIn(-32768, 32767))
            Le.putI16(out, off + 2, turnPermille.coerceIn(-1000, 1000))
            out[off + 4] = flags.toByte()
        }
    }
}

/** PING：t = App 自訂時間戳（ms，u32）。車子以 PONG 原樣回傳。 */
data class Ping(val t: Long) : Outgoing {
    override val type get() = MsgType.PING
    override fun encode(): ByteArray = ByteWriter(4).u32(t).toByteArray()
    companion object { fun decode(p: ByteArray) = Ping(ByteReader(p).u32()) }
}

data class Motor(val action: MotorAction) : Outgoing {
    override val type get() = MsgType.MOTOR
    override fun encode(): ByteArray = byteArrayOf(action.code.toByte())
}

/** 以當前傾角作為機械零點（馬達停止時才允許）。 */
data object ZeroCal : Outgoing {
    override val type get() = MsgType.ZERO_CAL
    override fun encode() = FrameCodec.EMPTY
}

/** PARAM_GET：id = 0xFF 讀全部。 */
data class ParamGet(val id: Int = Params.ALL_ID) : Outgoing {
    override val type get() = MsgType.PARAM_GET
    override fun encode(): ByteArray = byteArrayOf(id.toByte())
}

/** PARAM_SET：1..20 組 {id, f32}。 */
data class ParamSet(val values: List<Pair<Int, Float>>) : Outgoing {
    init { require(values.size in 1..MAX_PER_FRAME) { "PARAM_SET needs 1..$MAX_PER_FRAME values" } }
    override val type get() = MsgType.PARAM_SET
    override fun encode(): ByteArray {
        val w = ByteWriter(values.size * 5)
        for ((id, v) in values) w.u8(id).f32(v)
        return w.toByteArray()
    }

    companion object {
        const val MAX_PER_FRAME = 20

        /** 把任意多組數值切成多個 PARAM_SET。 */
        fun chunked(values: Map<Int, Float>): List<ParamSet> =
            values.entries.map { it.key to it.value }.chunked(MAX_PER_FRAME).map { ParamSet(it) }
    }
}

data object ParamSave : Outgoing {
    override val type get() = MsgType.PARAM_SAVE
    override fun encode() = FrameCodec.EMPTY
}

data object ParamDefaults : Outgoing {
    override val type get() = MsgType.PARAM_DEFAULTS
    override fun encode() = FrameCodec.EMPTY
}

/** SEQ_LOAD：一批最多 24 步。整段編排用 [chunk] 切批。 */
data class SeqLoad(val offset: Int, val total: Int, val steps: List<SeqStep>) : Outgoing {
    init {
        require(steps.size <= SeqStep.MAX_PER_FRAME) { "max ${SeqStep.MAX_PER_FRAME} steps per frame" }
        require(total in 0..255 && offset in 0..255) { "offset/total out of range" }
    }

    override val type get() = MsgType.SEQ_LOAD
    override fun encode(): ByteArray {
        val w = ByteWriter(3 + steps.size * SeqStep.SIZE).u8(offset).u8(total).u8(steps.size)
        steps.forEach { it.encodeTo(w) }
        return w.toByteArray()
    }

    companion object {
        /** 1..48 步 → 依序的 SEQ_LOAD 批次（每批 ≤ 24 步）。 */
        fun chunk(steps: List<SeqStep>): List<SeqLoad> {
            require(steps.isNotEmpty()) { "sequence is empty" }
            require(steps.size <= SeqStep.MAX_STEPS) { "max ${SeqStep.MAX_STEPS} steps" }
            return steps.chunked(SeqStep.MAX_PER_FRAME).mapIndexed { i, part ->
                SeqLoad(i * SeqStep.MAX_PER_FRAME, steps.size, part)
            }
        }

        fun decode(p: ByteArray): SeqLoad {
            val r = ByteReader(p)
            val off = r.u8(); val total = r.u8(); val n = r.u8()
            return SeqLoad(off, total, List(n) { SeqStep.decode(r) })
        }
    }
}

/** SEQ_PLAY：loops = 0 無限循環。 */
data class SeqPlay(val loops: Int) : Outgoing {
    override val type get() = MsgType.SEQ_PLAY
    override fun encode(): ByteArray = byteArrayOf(loops.coerceIn(0, 255).toByte())
}

data object SeqStop : Outgoing {
    override val type get() = MsgType.SEQ_STOP
    override fun encode() = FrameCodec.EMPTY
}

data object InfoGet : Outgoing {
    override val type get() = MsgType.INFO_GET
    override fun encode() = FrameCodec.EMPTY
}

/** HELLO：u32 session_id（App 隨機）。 */
data class Hello(val sessionId: Long) : Outgoing {
    override val type get() = MsgType.HELLO
    override fun encode(): ByteArray = ByteWriter(4).u32(sessionId).toByteArray()
}

data object WifiGet : Outgoing {
    override val type get() = MsgType.WIFI_GET
    override fun encode() = FrameCodec.EMPTY
}

/** WIFI_SET：ESP 回 ESP_ACK 後 500 ms 重開機。channel 0 = 自動。 */
data class WifiSet(val ssid: String, val password: String, val channel: Int = 0) : Outgoing {
    override val type get() = MsgType.WIFI_SET
    override fun encode(): ByteArray =
        ByteWriter(3 + ssid.length + password.length).lenString(ssid).lenString(password).u8(channel.coerceIn(0, 13)).toByteArray()
}

data class EspPing(val t: Long) : Outgoing {
    override val type get() = MsgType.ESP_PING
    override fun encode(): ByteArray = ByteWriter(4).u32(t).toByteArray()
}

data object Bye : Outgoing {
    override val type get() = MsgType.BYE
    override fun encode() = FrameCodec.EMPTY
}

data object WifiFactory : Outgoing {
    override val type get() = MsgType.WIFI_FACTORY
    override fun encode() = FrameCodec.EMPTY
}

/**
 * STA_SET（§3.4 / §3.6）：熱點模式設定。[ssid] 是**手機熱點**的名稱（UTF-8，1..32 bytes），
 * [password] 空字串 = 開放熱點，否則 8..63 個可列印 ASCII。enable = false 且 ssid 空白 = 保留原本存的熱點帳密。
 * ESP 回 ESP_ACK 後 500 ms 重新開機。檢查請用 link.HotspotConfig。
 */
data class StaSet(val enable: Boolean, val ssid: String, val password: String) : Outgoing {
    override val type get() = MsgType.STA_SET
    override fun encode(): ByteArray =
        ByteWriter(3 + ssid.length * 3 + password.length).u8(if (enable) 1 else 0).lenStringUtf8(ssid).lenString(password).toByteArray()

    override fun toString(): String = "StaSet(enable=$enable, ssid=$ssid, password=***)"

    companion object {
        fun decode(p: ByteArray): StaSet = ByteReader(p).let { r -> StaSet(r.u8() != 0, r.lenStringUtf8(), r.lenString()) }
    }
}

/** STA_GET：ESP 回 [StaInfo]。 */
data object StaGet : Outgoing {
    override val type get() = MsgType.STA_GET
    override fun encode() = FrameCodec.EMPTY
}

/** DISCOVER：u32 nonce。任何人、任何時候都可送（不需 session），可用廣播；ESP 單播回 [DiscoverAck]。 */
data class Discover(val nonce: Long) : Outgoing {
    override val type get() = MsgType.DISCOVER
    override fun encode(): ByteArray = ByteWriter(4).u32(nonce).toByteArray()
    companion object { fun decode(p: ByteArray) = Discover(ByteReader(p).u32()) }
}

// =====================================================================
//  App 收到的訊息（STM32 -> App / ESP -> App）
// =====================================================================

/** App 收到的訊息。用 [Incoming.decode] 由 [Frame] 解出。 */
sealed interface Incoming {
    companion object {
        /** 解碼；未知 TYPE 或 payload 格式錯誤回傳 null（不丟例外）。 */
        fun decode(f: Frame): Incoming? = try {
            when (f.type) {
                MsgType.TELEM -> Telemetry.decode(f.payload)
                MsgType.PONG -> Pong.decode(f.payload)
                MsgType.PARAM_VALUES -> ParamValues.decode(f.payload)
                MsgType.ACK -> Ack.decode(f.payload)
                MsgType.INFO -> Info.decode(f.payload)
                MsgType.EVENT -> Event.decode(f.payload)
                MsgType.HELLO_ACK -> HelloAck.decode(f.payload)
                MsgType.WIFI_INFO -> WifiInfo.decode(f.payload)
                MsgType.ESP_PONG -> EspPong.decode(f.payload)
                MsgType.ESP_ACK -> EspAck.decode(f.payload)
                MsgType.STA_INFO -> StaInfo.decode(f.payload)
                MsgType.DISCOVER_ACK -> DiscoverAck.decode(f.payload)
                else -> null
            }
        } catch (e: ProtocolException) {
            null
        }
    }
}

data class Pong(val t: Long) : Incoming {
    fun encode(): ByteArray = ByteWriter(4).u32(t).toByteArray()
    companion object { fun decode(p: ByteArray) = Pong(ByteReader(p).u32()) }
}

data class EspPong(val t: Long) : Incoming {
    fun encode(): ByteArray = ByteWriter(4).u32(t).toByteArray()
    companion object { fun decode(p: ByteArray) = EspPong(ByteReader(p).u32()) }
}

/** PARAM_VALUES：id → 實際套用後的值（保持線上順序）。 */
data class ParamValues(val values: Map<Int, Float>) : Incoming {
    fun encode(): ByteArray {
        val w = ByteWriter(1 + values.size * 5).u8(values.size)
        for ((id, v) in values) w.u8(id).f32(v)
        return w.toByteArray()
    }

    companion object {
        fun decode(p: ByteArray): ParamValues {
            val r = ByteReader(p)
            val n = r.u8()
            val m = LinkedHashMap<Int, Float>(n * 2)
            repeat(n) { val id = r.u8(); m[id] = r.f32() }
            return ParamValues(m)
        }
    }
}

/** ACK：回覆哪個 TYPE（[refType]）與狀態。 */
data class Ack(val refType: Int, val status: AckStatus, val rawStatus: Int = status.code) : Incoming {
    fun encode(): ByteArray = byteArrayOf(refType.toByte(), rawStatus.toByte())
    companion object {
        fun decode(p: ByteArray): Ack = ByteReader(p).let { r ->
            val ref = r.u8(); val s = r.u8(); Ack(ref, AckStatus.of(s), s)
        }
    }
}

/** ESP_ACK：WIFI_SET / WIFI_FACTORY 的回覆。 */
data class EspAck(val refType: Int, val status: AckStatus, val rawStatus: Int = status.code) : Incoming {
    fun encode(): ByteArray = byteArrayOf(refType.toByte(), rawStatus.toByte())
    companion object {
        fun decode(p: ByteArray): EspAck = ByteReader(p).let { r ->
            val ref = r.u8(); val s = r.u8(); EspAck(ref, AckStatus.of(s), s)
        }
    }
}

/** INFO（20 bytes）：韌體版本與能力。 */
data class Info(
    val protoVer: Int,
    val fwMajor: Int,
    val fwMinor: Int,
    val fwPatch: Int,
    val paramCount: Int,
    val maxSteps: Int,
    val angleAlgo: AngleAlgo,
    val model: String,
) : Incoming {
    val fwVersion: String get() = "$fwMajor.$fwMinor.$fwPatch"

    fun encode(): ByteArray {
        val w = ByteWriter(SIZE).u8(protoVer).u8(fwMajor).u8(fwMinor).u8(fwPatch).u8(paramCount).u8(maxSteps)
            .u8(angleAlgo.code.coerceAtLeast(0)).u8(0)
        val m = model.toByteArray(Charsets.US_ASCII).copyOf(12)
        return w.bytes(m).toByteArray()
    }

    companion object {
        const val SIZE = 20
        fun decode(p: ByteArray): Info {
            val r = ByteReader(p)
            val i = Info(r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), AngleAlgo.of(r.u8()), "")
            r.u8() // reserved
            val raw = r.bytes(minOf(12, r.remaining))
            val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
            return i.copy(model = String(raw, 0, end, Charsets.US_ASCII))
        }
    }
}

/** EVENT：[code] + 16-bit 參數（意義見 §4.3）。 */
data class Event(val code: EventCode, val rawCode: Int, val arg: Int) : Incoming {
    fun encode(): ByteArray = ByteWriter(3).u8(rawCode).i16(arg).toByteArray()
    companion object {
        fun of(code: EventCode, arg: Int = 0) = Event(code, code.code, arg)
        fun decode(p: ByteArray): Event = ByteReader(p).let { r ->
            val c = r.u8(); Event(EventCode.of(c), c, r.i16())
        }
    }
}

/** HELLO_ACK（12 bytes）。status 0 OK、1 BUSY（已有其他手機在控制）。 */
data class HelloAck(
    val status: Int,
    val protoVer: Int,
    val espMajor: Int,
    val espMinor: Int,
    val espPatch: Int,
    val channel: Int,
    val udpPort: Int,
    val chipId: Long,
) : Incoming {
    val isOk: Boolean get() = status == 0
    val isBusy: Boolean get() = status == 1
    val espVersion: String get() = "$espMajor.$espMinor.$espPatch"
    val chipIdHex: String get() = "%08X".format(chipId)

    fun encode(): ByteArray = ByteWriter(SIZE).u8(status).u8(protoVer).u8(espMajor).u8(espMinor).u8(espPatch)
        .u8(channel).u16(udpPort).u32(chipId).toByteArray()

    companion object {
        const val SIZE = 12
        fun decode(p: ByteArray): HelloAck = ByteReader(p).let { r ->
            HelloAck(r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u16(), r.u32())
        }
    }
}

/** WIFI_INFO：ESP-01 狀態與目前的 SSID / 密碼。 */
data class WifiInfo(
    val channel: Int,
    val stations: Int,
    val txPowerDbm: Int,
    val uptimeS: Long,
    val freeHeap: Long,
    val uartRxOk: Long,
    val uartRxErr: Long,
    val udpRx: Long,
    val udpTx: Long,
    val ssid: String,
    val password: String,
    /** 設定的頻道：0 = 自動選頻（channel 則是實際運作頻道）。 */
    val configuredChannel: Int = 0,
) : Incoming {
    fun encode(): ByteArray = ByteWriter(40).u8(channel).u8(stations).u8(txPowerDbm).u8(configuredChannel)
        .u32(uptimeS).u32(freeHeap).u32(uartRxOk).u32(uartRxErr).u32(udpRx).u32(udpTx)
        .lenString(ssid).lenString(password).toByteArray()

    companion object {
        fun decode(p: ByteArray): WifiInfo {
            val r = ByteReader(p)
            val ch = r.u8(); val st = r.u8(); val tx = r.u8(); val cfg = r.u8()
            return WifiInfo(ch, st, tx, r.u32(), r.u32(), r.u32(), r.u32(), r.u32(), r.u32(), r.lenString(), r.lenString(), cfg)
        }
    }
}

/**
 * STA_INFO（0xC4）：熱點模式狀態。
 * @param enabled 已設定熱點模式
 * @param ip 車子在熱點網段的位址（[state] = CONNECTED 才有效，其他為 "0.0.0.0"）
 * @param rssiDbm 車子收到熱點的訊號強度（0 = 無 → null）
 * @param ssid 熱點名稱（UTF-8）
 * @param password 熱點密碼（只回給 session 擁有者，其他人空字串）
 */
data class StaInfo(
    val enabled: Boolean,
    val state: StaState,
    val ip: String,
    val rssiDbm: Int?,
    val ssid: String,
    val password: String,
    val rawState: Int = state.code,
) : Incoming {
    /** 車子在熱點網段的位址（已連上熱點才有）。 */
    val carIp: String? get() = if (state == StaState.CONNECTED && !Ipv4.isZero(ip)) ip else null

    fun encode(): ByteArray = ByteWriter(10 + ssid.length * 3 + password.length)
        .u8(if (enabled) 1 else 0).u8(rawState).ipv4(ip).u8((rssiDbm ?: 0) and 0xFF)
        .lenStringUtf8(ssid).lenString(password).toByteArray()

    override fun toString(): String =
        "StaInfo(enabled=$enabled, state=$state, ip=$ip, rssiDbm=$rssiDbm, ssid=$ssid, password=${if (password.isEmpty()) "" else "***"})"

    companion object {
        fun decode(p: ByteArray): StaInfo {
            val r = ByteReader(p)
            val en = r.u8() != 0
            val st = r.u8()
            val ip = r.ipv4()
            val rssi = r.i8()
            return StaInfo(en, StaState.of(st), ip, rssi.takeIf { it != 0 }, r.lenStringUtf8(), r.lenString(), st)
        }
    }
}

/**
 * DISCOVER_ACK（0xC5）：ESP 單播回覆。
 * @param nonce DISCOVER 的 nonce（原樣回傳，App 用來確認是這次的搜尋）
 * @param mode 0 = 直連 AP、1 = 熱點 STA
 * @param ip 車子的位址（直連 = 192.168.4.1；熱點 = 熱點網段位址）
 * @param busy 已有其他手機在控制
 * @param ssid 車名（車子 AP 的 SSID，例如 B570-A1B2；App 用它比對記住的車）
 */
data class DiscoverAck(
    val nonce: Long,
    val chipId: Long,
    val mode: Int,
    val ip: String,
    val busy: Boolean,
    val ssid: String,
) : Incoming {
    val isHotspot: Boolean get() = mode == MODE_HOTSPOT
    val chipIdHex: String get() = "%08X".format(chipId)

    fun encode(): ByteArray = ByteWriter(16 + ssid.length).u32(nonce).u32(chipId).u8(mode).ipv4(ip)
        .u8(if (busy) 1 else 0).lenStringUtf8(ssid).toByteArray()

    companion object {
        const val MODE_DIRECT = 0
        const val MODE_HOTSPOT = 1
        fun decode(p: ByteArray): DiscoverAck {
            val r = ByteReader(p)
            return DiscoverAck(r.u32(), r.u32(), r.u8(), r.ipv4(), r.u8() != 0, r.lenStringUtf8())
        }
    }
}

/**
 * NET（0x53，ESP → STM32，**只在 UART**，不會出現在 UDP）：OLED 顯示用的網路狀態。
 * App 不會收到；放在這裡給測試向量與工具使用。mode 0 直連、1 搜尋熱點中、2 熱點已連上、3 找不到熱點已改回直連。
 */
data class NetStatus(val mode: Int, val ip: String, val ssid: String) {
    fun encode(): ByteArray = ByteWriter(6 + ssid.length * 3).u8(mode).ipv4(ip).lenStringUtf8(ssid).toByteArray()
    companion object {
        fun decode(p: ByteArray): NetStatus = ByteReader(p).let { r -> NetStatus(r.u8(), r.ipv4(), r.lenStringUtf8()) }
    }
}
