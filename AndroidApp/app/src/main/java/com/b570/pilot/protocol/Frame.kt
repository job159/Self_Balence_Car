package com.b570.pilot.protocol

/** 一個完整的幀（已通過 CRC）。type / seq 為 0..255。 */
class Frame(val type: Int, val seq: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && other.type == type && other.seq == seq && other.payload.contentEquals(payload)

    override fun hashCode(): Int = (type * 31 + seq) * 31 + payload.contentHashCode()
    override fun toString(): String = "Frame(${MsgType.name(type)} seq=$seq [${payload.toHex()}])"
    fun encode(): ByteArray = FrameCodec.build(type, seq, payload)
}

/** 幀組裝：A5 5A LEN TYPE SEQ PAYLOAD CRC16（LE，範圍 = LEN..PAYLOAD）。 */
object FrameCodec {
    const val SOF0 = 0xA5
    const val SOF1 = 0x5A
    const val MAX_PAYLOAD = 200
    const val OVERHEAD = 7
    const val MAX_FRAME = MAX_PAYLOAD + OVERHEAD
    val EMPTY = ByteArray(0)

    fun build(type: Int, seq: Int, payload: ByteArray = EMPTY): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too long: ${payload.size}" }
        val out = ByteArray(payload.size + OVERHEAD)
        out[0] = SOF0.toByte(); out[1] = SOF1.toByte()
        out[2] = payload.size.toByte(); out[3] = type.toByte(); out[4] = seq.toByte()
        payload.copyInto(out, 5)
        Le.putU16(out, 5 + payload.size, Crc16.compute(out, 2, 3 + payload.size))
        return out
    }

    /**
     * 不配置版本（傳送執行緒每 20 ms 送 CTRL 用）：payload 已經寫在 `out[5 until 5 + payloadLen]`，
     * 這裡補上表頭與 CRC，回傳整幀長度。結果與 [build] 逐位元組相同。
     */
    fun finishInto(out: ByteArray, type: Int, seq: Int, payloadLen: Int): Int {
        require(payloadLen in 0..MAX_PAYLOAD && out.size >= payloadLen + OVERHEAD) { "bad frame buffer" }
        out[0] = SOF0.toByte(); out[1] = SOF1.toByte()
        out[2] = payloadLen.toByte(); out[3] = type.toByte(); out[4] = seq.toByte()
        Le.putU16(out, 5 + payloadLen, Crc16.compute(out, 2, 3 + payloadLen))
        return payloadLen + OVERHEAD
    }
}

/**
 * 串流解析器（UART 與 UDP 共用），行為與 tools/b570_proto.py 的 Parser 完全一致：
 * - 找 A5 5A；找不到時只保留最後一個 A5（可能是被切開的 SOF0）
 * - LEN > 200：丟 1 byte 重新同步（另計入 [lenErrors]；Python 版不計數）
 * - CRC 錯：[crcErrors]++，丟 1 byte 重新同步
 * 非執行緒安全：每個接收執行緒各用一個。
 */
class FrameParser(initialCapacity: Int = 4096) {
    private var buf = ByteArray(initialCapacity)
    private var start = 0
    private var end = 0

    @Volatile var crcErrors = 0L
        private set
    @Volatile var lenErrors = 0L
        private set
    @Volatile var framesOk = 0L
        private set

    /** 目前暫存、尚未成幀的位元組數。 */
    val buffered: Int get() = end - start

    fun reset() { start = 0; end = 0 }

    /** 餵入資料並回傳解出的幀（依序）。 */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Frame> {
        val out = ArrayList<Frame>(2)
        feed(data, offset, length) { out.add(it) }
        return out
    }

    /** 不配置 List 的版本：每解出一幀呼叫一次 [onFrame]。 */
    inline fun feed(data: ByteArray, offset: Int, length: Int, onFrame: (Frame) -> Unit) {
        append(data, offset, length)
        while (true) {
            val f = next() ?: break
            onFrame(f)
        }
        compact()
    }

    @PublishedApi
    internal fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (end + length > buf.size) {
            compact()
            if (end + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, end + length))
        }
        System.arraycopy(data, offset, buf, end, length)
        end += length
    }

    @PublishedApi
    internal fun compact() {
        if (start == 0) return
        val n = end - start
        if (n > 0) System.arraycopy(buf, start, buf, 0, n)
        start = 0; end = n
    }

    /** 從暫存取出下一幀；資料不足回傳 null。 */
    @PublishedApi
    internal fun next(): Frame? {
        while (true) {
            var i = start
            var found = -1
            while (i + 1 < end) {
                if (buf[i] == SOF0B && buf[i + 1] == SOF1B) { found = i; break }
                i++
            }
            if (found < 0) {
                start = if (end > start && buf[end - 1] == SOF0B) end - 1 else end
                return null
            }
            start = found
            if (end - start < 3) return null
            val len = buf[start + 2].toInt() and 0xFF
            if (len > FrameCodec.MAX_PAYLOAD) { lenErrors++; start += 1; continue }
            val total = len + FrameCodec.OVERHEAD
            if (end - start < total) return null
            val crc = Crc16.compute(buf, start + 2, 3 + len)
            if (crc == Le.u16(buf, start + 5 + len)) {
                val f = Frame(
                    type = buf[start + 3].toInt() and 0xFF,
                    seq = buf[start + 4].toInt() and 0xFF,
                    payload = buf.copyOfRange(start + 5, start + 5 + len),
                )
                start += total
                framesOk++
                return f
            }
            crcErrors++
            start += 1
        }
    }

    private companion object {
        const val SOF0B = 0xA5.toByte()
        const val SOF1B = 0x5A.toByte()
    }
}
