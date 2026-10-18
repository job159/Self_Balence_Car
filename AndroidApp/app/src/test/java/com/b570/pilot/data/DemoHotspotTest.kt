package com.b570.pilot.data

import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.LinkListener
import com.b570.pilot.link.LinkMode
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.Discover
import com.b570.pilot.protocol.DiscoverAck
import com.b570.pilot.protocol.EspAck
import com.b570.pilot.protocol.Event
import com.b570.pilot.protocol.Frame
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.StaGet
import com.b570.pilot.protocol.StaInfo
import com.b570.pilot.protocol.StaSet
import com.b570.pilot.protocol.StaState
import com.b570.pilot.protocol.Telemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 展示模式也要能展示新功能：v1.1 遙測（ctrl_seq / ctrl_age）、控制延遲、熱點模式指令。 */
class DemoHotspotTest {

    private class Rec : LinkListener {
        val frames = CopyOnWriteArrayList<Frame>()
        val latencies = CopyOnWriteArrayList<Float>()
        val uplink = CopyOnWriteArrayList<Pair<Int, Int>>()
        @Volatile var mode: LinkMode? = null
        override fun onFrame(frame: Frame) { frames += frame }
        override fun onRtt(ms: Float) {}
        override fun onEspRtt(ms: Float) {}
        override fun onRssi(dbm: Int) {}
        override fun onControlLatency(ms: Float) { latencies += ms }
        override fun onUplinkSample(sent: Int, received: Int) { uplink += sent to received }
        override fun onLinkMode(mode: LinkMode?) { this.mode = mode }
    }

    private suspend fun connected(): Pair<Rec, DemoTelemetrySource> {
        val rec = Rec()
        val demo = DemoTelemetrySource(rec)
        demo.start(DemoTelemetrySource.DEMO_CREDS)
        withTimeout(3000) { demo.state.first { it is ConnectionState.Connected } }
        return rec to demo
    }

    @Test fun demoSendsV11TelemetryAndControlLatency() = runBlocking {
        val (rec, demo) = connected()
        delay(1200)
        val telem = rec.frames.filter { it.type == MsgType.TELEM }
        assertTrue("telemetry ${telem.size}", telem.size >= 20)
        assertTrue(telem.all { it.payload.size == Telemetry.SIZE_V11 })
        val decoded = telem.map { Telemetry.decode(it.payload) }
        assertTrue(decoded.all { it.ctrlSeq != null && it.ctrlAge in 1..2 })
        // CTRL SEQ 往前走（50 Hz）
        assertTrue(decoded.map { it.ctrlSeq }.distinct().size > 10)
        assertTrue("latency ${rec.latencies.size}", rec.latencies.size >= 20)
        assertTrue(rec.latencies.all { it in 15f..40f })
        assertTrue(rec.uplink.isNotEmpty() && rec.uplink.all { (s, r) -> s >= r })
        assertEquals(LinkMode.DIRECT, rec.mode)
        demo.stop()
    }

    @Test fun demoAnswersHotspotCommands() = runBlocking {
        val (rec, demo) = connected()
        suspend fun sta(): StaInfo = StaInfo.decode(demo.request(MsgType.STA_GET, StaGet.encode()) { it.type == MsgType.STA_INFO }!!.payload)
        suspend fun staSet(s: StaSet): AckStatus =
            EspAck.decode(demo.request(MsgType.STA_SET, s.encode()) { it.type == MsgType.ESP_ACK }!!.payload).status

        assertFalse(sta().enabled)
        assertEquals(StaState.OFF, sta().state)

        // 不合法（啟用但沒有熱點名稱 / 密碼太短）→ BAD_ARG
        assertEquals(AckStatus.BAD_ARG, staSet(StaSet(true, "", "")))
        assertEquals(AckStatus.BAD_ARG, staSet(StaSet(true, "MyPhone", "short")))

        assertEquals(AckStatus.OK, staSet(StaSet(true, "MyPhone", "pass1234")))
        assertEquals(LinkMode.HOTSPOT, rec.mode)
        val on = sta()
        assertTrue(on.enabled)
        assertEquals(StaState.CONNECTED, on.state)
        assertEquals(DemoTelemetrySource.DEMO_HOTSPOT_IP, on.carIp)
        assertEquals("MyPhone", on.ssid)
        assertNotNull(on.rssiDbm)

        val ackFrame = demo.request(MsgType.DISCOVER, Discover(0xCAFEL).encode()) { it.type == MsgType.DISCOVER_ACK }!!
        val ack = DiscoverAck.decode(ackFrame.payload)
        assertEquals(0xCAFEL, ack.nonce)
        assertTrue(ack.isHotspot)
        assertEquals(DemoTelemetrySource.DEMO_CREDS.ssid, ack.ssid)
        assertEquals(DemoTelemetrySource.DEMO_HOTSPOT_IP, ack.ip)

        // 關閉、保留熱點帳密
        assertEquals(AckStatus.OK, staSet(StaSet(false, "", "")))
        assertEquals(LinkMode.DIRECT, rec.mode)
        val off = sta()
        assertFalse(off.enabled)
        assertNull(off.carIp)
        assertEquals("MyPhone", off.ssid)
        demo.stop()
    }

    @Test fun simTelemetryHasCtrlFieldsOnlyWhenGiven() {
        val sim = DemoTelemetrySource.Sim().apply { reset(DemoTelemetrySource.DEMO_CREDS) }
        val events = ArrayList<Event>()
        assertEquals(Telemetry.SIZE, sim.step(0.02f, 0, 0, events).encode().size)
        val t = sim.step(0.02f, 0, 0, events, ctrlSeq = 77, ctrlAge = 1)
        assertEquals(Telemetry.SIZE_V11, t.encode().size)
        assertEquals(77, Telemetry.decode(t.encode()).ctrlSeq)
    }
}
