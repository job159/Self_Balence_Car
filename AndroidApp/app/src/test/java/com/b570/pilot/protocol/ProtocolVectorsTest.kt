package com.b570.pilot.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL.md §2 的全部測試向量（與 tools/b570_proto.py vectors 逐位元組一致）。 */
class ProtocolVectorsTest {

    private fun assertHex(expected: String, actual: ByteArray) = assertEquals(expected, actual.toHex())

    @Test fun crc16_checkValue() {
        assertEquals(0x29B1, Crc16.compute("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test fun ctrl() {
        assertHex("A5 5A 05 01 07 2C 01 06 FF 00 D4 4D", Ctrl(300, -250, 0).toFrame(7))
    }

    @Test fun ping() {
        assertHex("A5 5A 04 02 01 78 56 34 12 E3 C1", Ping(0x12345678L).toFrame(1))
    }

    @Test fun motorStart() {
        assertHex("A5 5A 01 03 02 01 67 DD", Motor(MotorAction.START).toFrame(2))
    }

    @Test fun paramGetAll() {
        assertHex("A5 5A 01 10 03 FF B4 FA", ParamGet(Params.ALL_ID).toFrame(3))
    }

    @Test fun paramSetBalKp() {
        assertHex("A5 5A 05 11 04 00 00 B0 B3 46 98 6D", ParamSet(listOf(Params.BAL_KP to 23000.0f)).toFrame(4))
    }

    @Test fun seqLoadOneStep() {
        val frames = SeqLoad.chunk(listOf(SeqStep(200, 0, 1000, SeqMode.HOLD, 0)))
        assertEquals(1, frames.size)
        assertHex("A5 5A 0B 20 05 00 01 01 C8 00 00 00 E8 03 00 00 FF 25", frames[0].toFrame(5))
    }

    @Test fun hello() {
        assertHex("A5 5A 04 40 00 BE BA FE CA 65 78", Hello(0xCAFEBABEL).toFrame(0))
    }

    @Test fun credReq() {
        assertHex("A5 5A 00 52 09 68 35", FrameCodec.build(MsgType.CRED_REQ, 9))
    }

    @Test fun ackEncodeAndDecode() {
        val bytes = FrameCodec.build(MsgType.ACK, 10, Ack(MsgType.PARAM_SAVE, AckStatus.BUSY).encode())
        assertHex("A5 5A 02 91 0A 12 02 36 67", bytes)
        val f = FrameParser().feed(bytes).single()
        val ack = Incoming.decode(f) as Ack
        assertEquals(MsgType.PARAM_SAVE, ack.refType)
        assertEquals(AckStatus.BUSY, ack.status)
    }

    @Test fun telemetryFrameAndDecodedValues() {
        val hex = "A5 5A 28 81 2A D2 04 F5 FD 96 00 CC FC 21 00 78 00 76 00 DC 05 C8 05 B0 04 FA 00 32 00 78 00 00 00 84 04 03 00 FF 00 01 32 28 23 9C 01 4C 8F"
        val frames = FrameParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        val f = frames[0]
        assertEquals(MsgType.TELEM, f.type)
        assertEquals(0x2A, f.seq)
        val t = Incoming.decode(f) as Telemetry
        assertEquals(1234, t.tick)
        assertEquals(-5.23f, t.pitchDeg, 1e-4f)
        assertEquals(1.5f, t.rollDeg, 1e-4f)
        assertEquals(-820 / 16.4f, t.gyroBalDps, 1e-3f)
        assertEquals(-50f, t.gyroBalDps, 1e-3f)
        assertEquals(33 / 16.4f, t.gyroTurnDps, 1e-3f)
        assertEquals(120, t.velL)
        assertEquals(118, t.velR)
        assertEquals(1500, t.pwmL)
        assertEquals(1480, t.pwmR)
        assertEquals(1200, t.pwmBal)
        assertEquals(250, t.pwmVel)
        assertEquals(50, t.pwmTurn)
        assertEquals(120, t.targetSpeed)
        assertEquals(0, t.targetTurn)
        assertEquals(11.56f, t.batteryV, 1e-4f)
        assertEquals(0x03, t.flags)
        assertTrue(t.motorsOn)
        assertTrue(t.appLink)
        assertFalse(t.seqPlaying)
        assertFalse(t.lowBattery)
        assertFalse(t.upright)
        assertFalse(t.failsafe)
        assertFalse(t.paramsDirty)
        assertFalse(t.credOk)
        assertEquals(StopReason.RUNNING, t.stopReason)
        assertNull(t.seqStep)
        assertEquals(0, t.seqLoop)
        assertEquals(ControlSource.APP, t.source)
        assertEquals(50, t.linkQ)
        assertEquals(90.0f, t.headingDeg, 1e-4f)
        assertEquals(412, t.loopUs)
        assertEquals(119f, t.speedMmps, 1e-4f)
        // 重新編碼必須逐位元組相同
        assertHex(hex, FrameCodec.build(MsgType.TELEM, 0x2A, t.encode()))
    }

    @Test fun telemetrySeqStepNotNullWhenPlaying() {
        val p = hexToBytes("D2 04 F5 FD 96 00 CC FC 21 00 78 00 76 00 DC 05 C8 05 B0 04 FA 00 32 00 78 00 00 00 84 04 07 00 05 02 02 32 28 23 9C 01")
        val t = Telemetry.decode(p)
        assertEquals(5, t.seqStep)
        assertEquals(2, t.seqLoop)
        assertEquals(ControlSource.SEQUENCE, t.source)
        assertTrue(t.seqPlaying)
    }

    @Test(expected = ProtocolException::class)
    fun telemetryTooShortThrows() {
        Telemetry.decode(ByteArray(39))
    }

    @Test fun incomingDecodeReturnsNullOnMalformed() {
        assertNull(Incoming.decode(Frame(MsgType.TELEM, 0, ByteArray(10))))
        assertNull(Incoming.decode(Frame(MsgType.ACK, 0, ByteArray(1))))
        assertNull(Incoming.decode(Frame(0x7F, 0, ByteArray(0))))
    }

    @Test fun buildRejectsOversizedPayload() {
        FrameCodec.build(MsgType.PARAM_SET, 0, ByteArray(200)) // ok
        try {
            FrameCodec.build(MsgType.PARAM_SET, 0, ByteArray(201))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun ctrlRoundTripAndClamp() {
        val p = Ctrl(-800, 1500).encode()
        val back = Ctrl.decode(p)
        assertEquals(-800, back.speedMmps)
        assertEquals(1000, back.turnPermille)
    }

    @Test fun pingLargeTimestampIsUnsigned() {
        val p = Ping(0xFFFFFFF0L).encode()
        assertArrayEquals(byteArrayOf(0xF0.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), p)
        assertEquals(0xFFFFFFF0L, Pong.decode(p).t)
    }
}
