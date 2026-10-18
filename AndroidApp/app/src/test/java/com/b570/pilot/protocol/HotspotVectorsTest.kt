package com.b570.pilot.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROTOCOL.md §2 新增的測試向量（熱點模式 STA_SET / DISCOVER / DISCOVER_ACK / NET）與 §4.1 TELEM 42 bytes，
 * 逐位元組與 tools/b570_proto.py 一致（另外幾個向量也由 b570_proto.py 的 build_frame 產生）。
 */
class HotspotVectorsTest {

    private fun assertHex(expected: String, actual: ByteArray) = assertEquals(expected, actual.toHex())
    private fun single(hex: String): Frame = FrameParser().feed(hexToBytes(hex)).single()

    // ------------------------------------------------------------------ spec §2 vectors

    @Test fun staSetSpecVector() {
        val hex = "A5 5A 12 46 0B 01 07 4D 79 50 68 6F 6E 65 08 70 61 73 73 31 32 33 34 F8 9F"
        assertHex(hex, StaSet(true, "MyPhone", "pass1234").toFrame(11))
        val f = single(hex)
        assertEquals(MsgType.STA_SET, f.type)
        assertEquals(StaSet(true, "MyPhone", "pass1234"), StaSet.decode(f.payload))
    }

    @Test fun discoverSpecVector() {
        val hex = "A5 5A 04 48 0C 04 03 02 01 93 39"
        assertHex(hex, Discover(0x01020304L).toFrame(12))
        assertEquals(0x01020304L, Discover.decode(single(hex).payload).nonce)
    }

    @Test fun discoverAckSpecVector() {
        val hex = "A5 5A 18 C5 0C 04 03 02 01 C3 B2 A1 00 01 AC 14 0A 05 00 09 42 35 37 30 2D 41 31 42 32 59 56"
        val ack = DiscoverAck(0x01020304L, 0x00A1B2C3L, DiscoverAck.MODE_HOTSPOT, "172.20.10.5", false, "B570-A1B2")
        assertHex(hex, FrameCodec.build(MsgType.DISCOVER_ACK, 12, ack.encode()))
        val f = single(hex)
        assertEquals(12, f.seq) // 回覆 SEQ = DISCOVER 的 SEQ
        val d = Incoming.decode(f) as DiscoverAck
        assertEquals(ack, d)
        assertTrue(d.isHotspot)
        assertEquals("00A1B2C3", d.chipIdHex)
    }

    @Test fun netSpecVector() {
        val hex = "A5 5A 0D 53 0D 02 AC 14 0A 05 07 4D 79 50 68 6F 6E 65 63 91"
        val n = NetStatus(2, "172.20.10.5", "MyPhone")
        assertHex(hex, FrameCodec.build(MsgType.NET, 13, n.encode()))
        assertEquals(n, NetStatus.decode(single(hex).payload))
    }

    // ------------------------------------------------------------------ more vectors (b570_proto.py build_frame)

    @Test fun staGetVector() {
        assertHex("A5 5A 00 47 0F 28 A9", StaGet.toFrame(15))
    }

    @Test fun staSetDisableKeepCredentials() {
        // enable = 0、ssid_len = 0 = 保留原本存的熱點帳密
        assertHex("A5 5A 03 46 10 00 00 00 BA 07", StaSet(false, "", "").toFrame(16))
    }

    @Test fun staSetUtf8SsidOpenHotspot() {
        val hex = "A5 5A 0F 46 11 01 0C E6 88 91 E7 9A 84 E6 89 8B E6 A9 9F 00 80 B4"
        assertHex(hex, StaSet(true, "我的手機", "").toFrame(17))
        assertEquals(StaSet(true, "我的手機", ""), StaSet.decode(single(hex).payload))
    }

    @Test fun staInfoConnectedVector() {
        val hex = "A5 5A 18 C4 0E 01 02 AC 14 0A 05 CC 07 4D 79 50 68 6F 6E 65 08 70 61 73 73 31 32 33 34 A2 EC"
        val info = Incoming.decode(single(hex)) as StaInfo
        assertTrue(info.enabled)
        assertEquals(StaState.CONNECTED, info.state)
        assertEquals("172.20.10.5", info.ip)
        assertEquals("172.20.10.5", info.carIp)
        assertEquals(-52, info.rssiDbm)
        assertEquals("MyPhone", info.ssid)
        assertEquals("pass1234", info.password)
        assertHex(hex, FrameCodec.build(MsgType.STA_INFO, 14, info.encode()))
        assertFalse("password not logged", info.toString().contains("pass1234"))
        assertFalse(StaSet(true, "MyPhone", "pass1234").toString().contains("pass1234"))
    }

    @Test fun staInfoSearchingUtf8NoRssi() {
        val hex = "A5 5A 15 C4 03 01 01 00 00 00 00 00 0C E6 88 91 E7 9A 84 E6 89 8B E6 A9 9F 00 B4 0B"
        val info = Incoming.decode(single(hex)) as StaInfo
        assertEquals(StaState.SEARCHING, info.state)
        assertNull(info.rssiDbm) // 0 = 無
        assertNull(info.carIp) // 還沒連上熱點：ip 無效
        assertEquals("我的手機", info.ssid)
        assertEquals("", info.password)
        assertHex(hex, FrameCodec.build(MsgType.STA_INFO, 3, info.encode()))
    }

    @Test fun discoverAckDirectBusyVector() {
        val hex = "A5 5A 18 C5 C8 EF BE AD DE CD AB 34 12 00 C0 A8 04 01 01 09 42 35 37 30 2D 37 46 33 41 4E 92"
        val d = Incoming.decode(single(hex)) as DiscoverAck
        assertEquals(DiscoverAck(0xDEADBEEFL, 0x1234ABCDL, DiscoverAck.MODE_DIRECT, "192.168.4.1", true, "B570-7F3A"), d)
        assertFalse(d.isHotspot)
        assertHex(hex, FrameCodec.build(MsgType.DISCOVER_ACK, 200, d.encode()))
    }

    @Test fun netDirectModeVector() {
        assertHex(
            "A5 5A 0F 53 01 00 00 00 00 00 09 42 35 37 30 2D 41 31 42 32 4D D8",
            FrameCodec.build(MsgType.NET, 1, NetStatus(0, "0.0.0.0", "B570-A1B2").encode()),
        )
    }

    @Test fun staStateCodes() {
        assertEquals(listOf(0, 1, 2, 3), listOf(StaState.OFF, StaState.SEARCHING, StaState.CONNECTED, StaState.FALLBACK).map { it.code })
        assertEquals(StaState.UNKNOWN, StaState.of(9))
    }

    @Test fun malformedHotspotPayloadsDecodeToNull() {
        assertNull(Incoming.decode(Frame(MsgType.STA_INFO, 0, ByteArray(5))))
        assertNull(Incoming.decode(Frame(MsgType.DISCOVER_ACK, 0, ByteArray(13))))
        // ssid_len 超出 payload
        assertNull(Incoming.decode(Frame(MsgType.DISCOVER_ACK, 0, hexToBytes("04 03 02 01 C3 B2 A1 00 01 AC 14 0A 05 00 09 42"))))
    }

    @Test fun messageNames() {
        assertEquals("STA_SET", MsgType.name(0x46))
        assertEquals("STA_GET", MsgType.name(0x47))
        assertEquals("DISCOVER", MsgType.name(0x48))
        assertEquals("NET", MsgType.name(0x53))
        assertEquals("STA_INFO", MsgType.name(0xC4))
        assertEquals("DISCOVER_ACK", MsgType.name(0xC5))
        assertTrue(MsgType.isFromEsp(MsgType.DISCOVER_ACK))
        assertFalse(MsgType.isFromCar(MsgType.STA_INFO))
    }

    // ------------------------------------------------------------------ TELEM 40 vs 42 bytes (§4.1)

    private val telem40 = "D2 04 F5 FD 96 00 CC FC 21 00 78 00 76 00 DC 05 C8 05 B0 04 FA 00 32 00 78 00 00 00 84 04 03 00 FF 00 01 32 28 23 9C 01"

    @Test fun telemetry40BytesHasNoCtrlFields() {
        val t = Telemetry.decode(hexToBytes(telem40))
        assertNull(t.ctrlSeq)
        assertNull(t.ctrlAge)
        assertEquals(Telemetry.SIZE, t.encode().size)
    }

    @Test fun telemetry42BytesFrameVector() {
        val hex = "A5 5A 2A 81 2A D2 04 F5 FD 96 00 CC FC 21 00 78 00 76 00 DC 05 C8 05 B0 04 FA 00 32 00 78 00 00 00 84 04 03 00 FF 00 01 32 28 23 9C 01 2A 03 C7 E3"
        val f = single(hex)
        val t = Incoming.decode(f) as Telemetry
        assertEquals(0x2A, t.ctrlSeq)
        assertEquals(3, t.ctrlAge)
        // 前 40 bytes 的欄位與 v1 完全相同
        assertEquals(Telemetry.decode(hexToBytes(telem40)).copy(ctrlSeq = 0x2A, ctrlAge = 3), t)
        assertEquals(1234, t.tick)
        assertEquals(412, t.loopUs)
        assertHex(hex, FrameCodec.build(MsgType.TELEM, 0x2A, t.encode()))
    }

    @Test fun telemetryDecoderAcceptsAtLeast40Bytes() {
        // 41 bytes：多出來的不足一組 → 視為舊格式
        val t41 = Telemetry.decode(hexToBytes("$telem40 07"))
        assertNull(t41.ctrlSeq)
        // 更長（未來的欄位）：照樣解出 ctrl_seq / ctrl_age
        val t44 = Telemetry.decode(hexToBytes("$telem40 FF 00 AA BB"))
        assertEquals(255, t44.ctrlSeq)
        assertEquals(0, t44.ctrlAge)
    }

    @Test fun telemetryCtrlAgeEncodeClamps() {
        val t = Telemetry.decode(hexToBytes(telem40)).copy(ctrlSeq = 300, ctrlAge = 999)
        val p = t.encode()
        assertEquals(Telemetry.SIZE_V11, p.size)
        assertEquals(300 and 0xFF, p[40].toInt() and 0xFF)
        assertEquals(255, p[41].toInt() and 0xFF)
    }

    // ------------------------------------------------------------------ zero-allocation CTRL path

    @Test fun finishIntoMatchesBuildForCtrlVector() {
        val buf = ByteArray(FrameCodec.OVERHEAD + Ctrl.SIZE)
        Ctrl.writePayload(buf, 5, 300, -250)
        val n = FrameCodec.finishInto(buf, MsgType.CTRL, 7, Ctrl.SIZE)
        assertEquals(12, n)
        assertHex("A5 5A 05 01 07 2C 01 06 FF 00 D4 4D", buf)
        // 夾限與 encode() 一致
        Ctrl.writePayload(buf, 5, -800, 1500)
        FrameCodec.finishInto(buf, MsgType.CTRL, 9, Ctrl.SIZE)
        assertArrayEquals(Ctrl(-800, 1500).toFrame(9), buf)
    }

    @Test fun finishIntoSmallFrames() {
        val buf = ByteArray(FrameCodec.OVERHEAD + 4)
        Le.putU32(buf, 5, 0x12345678L)
        val n = FrameCodec.finishInto(buf, MsgType.PING, 1, 4)
        assertHex("A5 5A 04 02 01 78 56 34 12 E3 C1", buf.copyOf(n))
        val m = FrameCodec.finishInto(buf, MsgType.STA_GET, 15, 0)
        assertHex("A5 5A 00 47 0F 28 A9", buf.copyOf(m))
    }

    // ------------------------------------------------------------------ IPv4

    @Test fun ipv4Helpers() {
        assertEquals("172.20.10.5", Ipv4.format(hexToBytes("AC 14 0A 05")))
        assertArrayEquals(hexToBytes("C0 A8 2B FF"), Ipv4.parse("192.168.43.255"))
        assertNull(Ipv4.parse("192.168.43"))
        assertNull(Ipv4.parse("192.168.43.256"))
        assertNull(Ipv4.parse("a.b.c.d"))
        assertNull(Ipv4.parse(null))
        assertEquals("10.0.0.1", Ipv4.formatInt(Ipv4.toInt(Ipv4.parse("10.0.0.1")!!)))
        assertTrue(Ipv4.isZero("0.0.0.0"))
        assertFalse(Ipv4.isZero("1.2.3.4"))
    }
}
