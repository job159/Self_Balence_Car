package com.b570.pilot.protocol

/** Little-endian 讀寫工具（協定所有多位元組數值皆為 LE）。 */
object Le {
    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
    fun i8(b: ByteArray, off: Int): Int = b[off].toInt()
    fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    fun i16(b: ByteArray, off: Int): Int = u16(b, off).toShort().toInt()
    fun i32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
    fun u32(b: ByteArray, off: Int): Long = i32(b, off).toLong() and 0xFFFF_FFFFL
    fun f32(b: ByteArray, off: Int): Float = Float.fromBits(i32(b, off))

    fun putU8(b: ByteArray, off: Int, v: Int) { b[off] = v.toByte() }
    fun putU16(b: ByteArray, off: Int, v: Int) { b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte() }
    fun putI16(b: ByteArray, off: Int, v: Int) = putU16(b, off, v)
    fun putU32(b: ByteArray, off: Int, v: Long) = putI32(b, off, v.toInt())
    fun putI32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte()
        b[off + 2] = (v shr 16).toByte(); b[off + 3] = (v shr 24).toByte()
    }
    fun putF32(b: ByteArray, off: Int, v: Float) = putI32(b, off, v.toRawBits())
}

/** 依序寫入 payload。i16/u16 會先夾限，避免溢位翻號。 */
class ByteWriter(capacity: Int = 32) {
    private var buf = ByteArray(capacity)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }
    fun u8(v: Int) = apply { ensure(1); buf[size++] = v.toByte() }
    fun i16(v: Int) = apply { ensure(2); Le.putI16(buf, size, v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())); size += 2 }
    fun u16(v: Int) = apply { ensure(2); Le.putU16(buf, size, v.coerceIn(0, 0xFFFF)); size += 2 }
    fun u32(v: Long) = apply { ensure(4); Le.putU32(buf, size, v); size += 4 }
    fun f32(v: Float) = apply { ensure(4); Le.putF32(buf, size, v); size += 4 }
    fun bytes(v: ByteArray) = apply { ensure(v.size); v.copyInto(buf, size); size += v.size }
    /** u8 長度 + ASCII 內容。 */
    fun lenString(s: String) = apply { val b = s.toByteArray(Charsets.US_ASCII); u8(b.size); bytes(b) }
    /** u8 長度 + UTF-8 內容（熱點 SSID 可以有中文，§3.4 STA_SET）。超過 255 bytes 丟例外。 */
    fun lenStringUtf8(s: String) = apply {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 255) { "string too long: ${b.size} bytes" }
        u8(b.size); bytes(b)
    }
    /** IPv4 位址 4 bytes（網路順序，"172.20.10.5" → AC 14 0A 05）；格式錯誤丟例外。 */
    fun ipv4(ip: String) = apply { bytes(requireNotNull(Ipv4.parse(ip)) { "bad IPv4: $ip" }) }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** 依序讀取 payload；長度不足時丟 [ProtocolException]。 */
class ByteReader(private val b: ByteArray, var pos: Int = 0) {
    val remaining: Int get() = b.size - pos
    private fun need(n: Int) { if (pos + n > b.size) throw ProtocolException("payload too short") }
    fun u8(): Int { need(1); return Le.u8(b, pos).also { pos += 1 } }
    fun i16(): Int { need(2); return Le.i16(b, pos).also { pos += 2 } }
    fun u16(): Int { need(2); return Le.u16(b, pos).also { pos += 2 } }
    fun i32(): Int { need(4); return Le.i32(b, pos).also { pos += 4 } }
    fun u32(): Long { need(4); return Le.u32(b, pos).also { pos += 4 } }
    fun f32(): Float { need(4); return Le.f32(b, pos).also { pos += 4 } }
    fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }
    fun lenString(): String { val n = u8(); return String(bytes(n), Charsets.US_ASCII) }
    fun lenStringUtf8(): String { val n = u8(); return String(bytes(n), Charsets.UTF_8) }
    fun i8(): Int { need(1); return Le.i8(b, pos).also { pos += 1 } }
    /** 4 bytes IPv4 → "a.b.c.d"。 */
    fun ipv4(): String { need(4); return Ipv4.format(b, pos).also { pos += 4 } }
}

/** IPv4 位址工具（協定的 ip[4] 是網路順序：第一個 byte = 第一段）。 */
object Ipv4 {
    fun format(b: ByteArray, off: Int = 0): String =
        "${b[off].toInt() and 0xFF}.${b[off + 1].toInt() and 0xFF}.${b[off + 2].toInt() and 0xFF}.${b[off + 3].toInt() and 0xFF}"

    /** "a.b.c.d"（每段 0..255、十進位）→ 4 bytes；格式錯誤回傳 null。 */
    fun parse(s: String?): ByteArray? {
        if (s == null) return null
        val parts = s.trim().split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v.toByte()
        }
        return out
    }

    /** 4 bytes → 整數（大端，第一段在最高位）。 */
    fun toInt(b: ByteArray, off: Int = 0): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    fun fromInt(v: Int): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    fun formatInt(v: Int): String = format(fromInt(v))

    /** 0.0.0.0（STA_INFO / NET 在非「已連上熱點」狀態時的 ip）。 */
    fun isZero(ip: String?): Boolean = ip == null || ip == "0.0.0.0"
}

class ProtocolException(message: String) : RuntimeException(message)

/** 位元組陣列轉成 "A5 5A 05 ..." 大寫十六進位字串（除錯／測試用）。 */
fun ByteArray.toHex(sep: String = " "): String {
    val sb = StringBuilder(size * 3)
    for (i in indices) {
        if (i > 0) sb.append(sep)
        val v = this[i].toInt() and 0xFF
        sb.append(HEX_CHARS[v shr 4]).append(HEX_CHARS[v and 0xF])
    }
    return sb.toString()
}

/** "A5 5A 05" 或 "a55a05" → ByteArray。 */
fun hexToBytes(hex: String): ByteArray {
    val clean = hex.filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
