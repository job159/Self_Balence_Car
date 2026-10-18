package com.b570.pilot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 其他訊息的編解碼、分批與參數表。 */
class MessagesTest {

    @Test fun paramValuesRoundTrip() {
        val pv = ParamValues(linkedMapOf(0 to 22500f, 6 to -1.25f, 16 to 40f))
        val back = ParamValues.decode(pv.encode())
        assertEquals(pv.values, back.values)
        assertEquals(listOf(0, 6, 16), back.values.keys.toList())
    }

    @Test fun paramSetChunkingSplitsAt20() {
        val m = (0 until 17).associateWith { it.toFloat() } + (100 until 110).associateWith { 1f }
        val chunks = ParamSet.chunked(m)
        assertEquals(2, chunks.size)
        assertEquals(20, chunks[0].values.size)
        assertEquals(7, chunks[1].values.size)
        assertTrue(chunks.all { it.encode().size == it.values.size * 5 })
    }

    @Test fun seqLoadChunking48Steps() {
        val steps = List(48) { SeqStep(it * 10, -it, 100 + it, SeqMode.entries[it % 4], it) }
        val chunks = SeqLoad.chunk(steps)
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].offset)
        assertEquals(24, chunks[1].offset)
        assertTrue(chunks.all { it.total == 48 && it.steps.size == 24 })
        val p = chunks[1].encode()
        assertEquals(3 + 24 * 8, p.size)
        assertTrue(p.size <= FrameCodec.MAX_PAYLOAD)
        val back = SeqLoad.decode(p)
        assertEquals(steps.subList(24, 48), back.steps)
    }

    @Test fun seqLoadChunking25Steps() {
        val chunks = SeqLoad.chunk(List(25) { SeqStep(0, 0, 20) })
        assertEquals(listOf(24, 1), chunks.map { it.steps.size })
        assertEquals(listOf(0, 24), chunks.map { it.offset })
    }

    @Test(expected = IllegalArgumentException::class)
    fun seqLoadRejectsTooManySteps() {
        SeqLoad.chunk(List(49) { SeqStep(0, 0, 20) })
    }

    @Test fun seqStepPeriodAndClamp() {
        assertEquals(1000, SeqStep(0, 0, 100, SeqMode.SINE_TURN, 0).periodMs)
        assertEquals(40, SeqStep(0, 0, 100, SeqMode.SINE_TURN, 2).periodMs)
        val c = SeqStep(5000, -3000, 5, SeqMode.HOLD, 999).clamped()
        assertEquals(SeqStep(1000, -1000, 20, SeqMode.HOLD, 255), c)
    }

    @Test fun infoRoundTrip() {
        val i = Info(1, 2, 3, 4, 17, 48, AngleAlgo.KALMAN, "B570")
        val p = i.encode()
        assertEquals(Info.SIZE, p.size)
        assertEquals(i, Info.decode(p))
        assertEquals("2.3.4", i.fwVersion)
    }

    @Test fun helloAckRoundTrip() {
        val h = HelloAck(1, 1, 1, 2, 3, 11, 8888, 0xDEADBEEFL)
        val p = h.encode()
        assertEquals(HelloAck.SIZE, p.size)
        val back = HelloAck.decode(p)
        assertEquals(h, back)
        assertTrue(back.isBusy)
        assertEquals("DEADBEEF", back.chipIdHex)
    }

    @Test fun wifiInfoRoundTrip() {
        val w = WifiInfo(6, 1, 17, 1234, 40000, 10, 2, 300, 400, "B570-1A2B", "abcd1234")
        assertEquals(w, WifiInfo.decode(w.encode()))
        val fixed = w.copy(channel = 11, configuredChannel = 11)
        val back = WifiInfo.decode(fixed.encode())
        assertEquals(11, back.configuredChannel)
        assertEquals(0, WifiInfo.decode(w.encode()).configuredChannel)   // byte 3 = 0 → 自動選頻
    }

    @Test fun telemHzQuantizedLikeFirmware() {
        val d = Params.byId(Params.TELEM_HZ)!!
        assertEquals(100f, d.clamp(70f))
        assertEquals(33f, d.clamp(30f))
        assertEquals(50f, d.clamp(50f))
        assertEquals(10f, d.clamp(5f))
        assertEquals(100f, d.clamp(1000f))
    }

    @Test fun wifiSetEncoding() {
        val p = WifiSet("B570-AB", "pass1234", 6).encode()
        assertEquals(7, p[0].toInt())
        assertEquals("B570-AB", String(p, 1, 7, Charsets.US_ASCII))
        assertEquals(8, p[8].toInt())
        assertEquals("pass1234", String(p, 9, 8, Charsets.US_ASCII))
        assertEquals(6, p[17].toInt())
        assertEquals(18, p.size)
    }

    @Test fun eventDecodeSignedArg() {
        val e = Event.decode(Event.of(EventCode.FELL, -4210).encode())
        assertEquals(EventCode.FELL, e.code)
        assertEquals(-4210, e.arg)
        assertEquals(EventCode.UNKNOWN, Event.decode(byteArrayOf(99, 0, 0)).code)
    }

    @Test fun ackStatusMapping() {
        assertEquals(AckStatus.NOT_READY, AckStatus.of(4))
        assertEquals(AckStatus.UNKNOWN, AckStatus.of(42))
        assertEquals(AckStatus.UNKNOWN, AckStatus.of(-1)) // 本地狀態不會從線上解出
    }

    @Test fun paramRegistryMatchesSpec() {
        assertEquals(17, Params.ALL.size)
        assertEquals((0 until 17).toList(), Params.ALL.map { it.id })
        val expected = listOf(
            Triple("BAL_KP", 22500f, 0f..60000f), Triple("BAL_KD", 108f, 0f..600f), Triple("VEL_KP", 16000f, 0f..40000f),
            Triple("VEL_KI", 80f, 0f..400f), Triple("TURN_KP", 4200f, 0f..12000f), Triple("TURN_KD", 0f, 0f..300f),
            Triple("ZERO_ANGLE", 0f, -10f..10f), Triple("MAX_SPEED", 500f, 100f..1000f), Triple("MAX_TURN", 40f, 5f..80f),
            Triple("ACCEL", 2000f, 100f..6000f), Triple("TURN_ACCEL", 12000f, 500f..20000f), Triple("FAILSAFE_MS", 400f, 150f..2000f),
            Triple("TELEM_HZ", 50f, 10f..100f), Triple("ANGLE_ALGO", 2f, 1f..3f), Triple("PICKUP_EN", 1f, 0f..1f),
            Triple("LOWBAT_V", 10f, 9f..12f), Triple("FALL_ANGLE", 40f, 20f..60f),
        )
        expected.forEachIndexed { id, (key, def, range) ->
            val d = assertNotNullAndGet(Params.byId(id))
            assertEquals(key, d.key)
            assertEquals(def, d.default, 0f)
            assertEquals(range.start, d.min, 0f)
            assertEquals(range.endInclusive, d.max, 0f)
            assertTrue("$key default in range", d.default in d.min..d.max)
            assertTrue(d.labelZh.isNotBlank() && d.descZh.isNotBlank())
        }
        val ints = Params.ALL.filter { it.isInteger }.map { it.key }.toSet()
        assertEquals(setOf("ANGLE_ALGO", "PICKUP_EN", "FAILSAFE_MS", "TELEM_HZ"), ints)
        assertEquals(Params.ZERO_ANGLE, Params.byKey("zero_angle")?.id)
    }

    @Test fun paramClampAndFormat() {
        assertEquals(2000f, Params.clamp(Params.FAILSAFE_MS, 5000f), 0f)
        assertEquals(401f, Params.clamp(Params.FAILSAFE_MS, 400.6f), 0f)
        assertEquals(-10f, Params.clamp(Params.ZERO_ANGLE, -99f), 0f)
        val za = Params.byId(Params.ZERO_ANGLE)!!
        assertEquals(2, za.decimals)
        assertEquals("1.25 °", za.format(1.25f))
        assertEquals("卡爾曼", Params.byId(Params.ANGLE_ALGO)!!.format(2f))
        assertEquals(1, Params.byId(Params.LOWBAT_V)!!.decimals)
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
