package com.b570.pilot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析器：雜訊、黏包、拆包、CRC 重新同步、LEN > 200（語意與 tools/b570_proto.py 相同）。 */
class FrameParserTest {

    private val ctrl = Ctrl(300, -250).toFrame(7)
    private val ping = Ping(0x12345678L).toFrame(1)

    /** b570_proto.py 的 parser self-test 原樣搬過來。 */
    @Test fun pythonSelfTest() {
        val pr = FrameParser()
        val stream = byteArrayOf(0x00, 0xA5.toByte(), 0xFF.toByte()) + ctrl + ping.copyOfRange(0, 5)
        val got = ArrayList<Frame>()
        got += pr.feed(stream)
        got += pr.feed(ping.copyOfRange(5, ping.size) + byteArrayOf(0xA5.toByte()))
        got += pr.feed(byteArrayOf(0x5A, 0x00)) // 不完整
        assertEquals(listOf(MsgType.CTRL, MsgType.PING), got.map { it.type })
        assertEquals(0L, pr.crcErrors)
    }

    @Test fun leadingNoiseLikeEspBootGarbage() {
        val noise = ByteArray(300) { ((it * 37 + 11) and 0xFF).toByte() }.map { if (it == 0xA5.toByte()) 0x00 else it }.toByteArray()
        val frames = FrameParser().feed(noise + ctrl)
        assertEquals(1, frames.size)
        assertEquals(Ctrl(300, -250), Ctrl.decode(frames[0].payload))
    }

    @Test fun multipleFramesInOneDatagram() {
        val frames = FrameParser().feed(ctrl + ping + ctrl)
        assertEquals(listOf(MsgType.CTRL, MsgType.PING, MsgType.CTRL), frames.map { it.type })
    }

    @Test fun frameSplitAtEveryPosition() {
        val all = ctrl + ping
        for (cut in 1 until all.size) {
            val pr = FrameParser()
            val out = pr.feed(all.copyOfRange(0, cut)) + pr.feed(all.copyOfRange(cut, all.size))
            assertEquals("cut=$cut", listOf(MsgType.CTRL, MsgType.PING), out.map { it.type })
        }
    }

    @Test fun byteByByte() {
        val pr = FrameParser()
        val out = ArrayList<Frame>()
        for (b in ctrl + ping) out += pr.feed(byteArrayOf(b))
        assertEquals(listOf(MsgType.CTRL, MsgType.PING), out.map { it.type })
    }

    @Test fun crcErrorResyncsToNextFrame() {
        val bad = ctrl.copyOf().also { it[7] = (it[7] + 1).toByte() } // 改壞 payload
        val pr = FrameParser()
        val out = pr.feed(bad + ping)
        assertEquals(listOf(MsgType.PING), out.map { it.type })
        assertEquals(1L, pr.crcErrors)
    }

    @Test fun crcErrorWithEmbeddedFrame() {
        // 一個「假 SOF + 長度」吞掉了後面真正的幀的開頭：丟 1 byte 後必須還找得到真幀
        val fake = byteArrayOf(0xA5.toByte(), 0x5A, 0x08, 0x01, 0x00)
        val pr = FrameParser()
        val out = pr.feed(fake + ctrl + ping)
        assertEquals(listOf(MsgType.CTRL, MsgType.PING), out.map { it.type })
        assertTrue(pr.crcErrors >= 1)
    }

    @Test fun lenOver200DropsOneByteAndResyncs() {
        val junk = byteArrayOf(0xA5.toByte(), 0x5A, 0xC9.toByte(), 0x01, 0x02) // LEN = 201
        val pr = FrameParser()
        val out = pr.feed(junk + ctrl)
        assertEquals(listOf(MsgType.CTRL), out.map { it.type })
        assertEquals(1L, pr.lenErrors)
        assertEquals(0L, pr.crcErrors)
    }

    @Test fun lenExactly200IsAccepted() {
        val big = FrameCodec.build(MsgType.PARAM_VALUES, 3, ByteArray(200) { it.toByte() })
        val out = FrameParser().feed(big)
        assertEquals(1, out.size)
        assertEquals(200, out[0].payload.size)
    }

    @Test fun trailingSof0IsKeptAcrossFeeds() {
        val pr = FrameParser()
        assertTrue(pr.feed(byteArrayOf(0x11, 0x22, 0xA5.toByte())).isEmpty())
        assertEquals(1, pr.buffered)
        val out = pr.feed(ctrl.copyOfRange(1, ctrl.size))
        assertEquals(listOf(MsgType.CTRL), out.map { it.type })
    }

    @Test fun garbageWithoutSofIsDiscarded() {
        val pr = FrameParser()
        pr.feed(ByteArray(5000) { 0x33 })
        assertEquals(0, pr.buffered)
    }

    @Test fun largeStreamOfManyFrames() {
        val pr = FrameParser()
        var n = 0
        repeat(1000) { i ->
            val f = Ctrl(i, -i % 1000).toFrame(i)
            pr.feed(f, 0, f.size) { n++ }
        }
        assertEquals(1000, n)
        assertEquals(1000L, pr.framesOk)
    }

    @Test fun emptyPayloadFrame() {
        val f = FrameCodec.build(MsgType.SEQ_STOP, 200)
        val out = FrameParser().feed(f).single()
        assertEquals(MsgType.SEQ_STOP, out.type)
        assertEquals(200, out.seq)
        assertEquals(0, out.payload.size)
    }
}
