package com.b570.pilot.protocol

/** CRC-16/CCITT-FALSE：poly 0x1021、init 0xFFFF、不反射、xorout 0。"123456789" → 0x29B1。 */
object Crc16 {
    private val TABLE = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
            t[i] = c and 0xFFFF
        }
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, init: Int = 0xFFFF): Int {
        var crc = init and 0xFFFF
        for (i in offset until offset + length) {
            crc = ((crc shl 8) xor TABLE[((crc ushr 8) xor (data[i].toInt() and 0xFF)) and 0xFF]) and 0xFFFF
        }
        return crc
    }
}
