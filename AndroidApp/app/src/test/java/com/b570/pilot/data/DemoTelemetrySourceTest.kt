package com.b570.pilot.data

import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.LinkListener
import com.b570.pilot.protocol.Ack
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.EspAck
import com.b570.pilot.protocol.Event
import com.b570.pilot.protocol.EventCode
import com.b570.pilot.protocol.Frame
import com.b570.pilot.protocol.Motor
import com.b570.pilot.protocol.MotorAction
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.ParamSet
import com.b570.pilot.protocol.ParamValues
import com.b570.pilot.protocol.Params
import com.b570.pilot.protocol.SeqLoad
import com.b570.pilot.protocol.SeqMode
import com.b570.pilot.protocol.SeqPlay
import com.b570.pilot.protocol.SeqStep
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.protocol.WifiGet
import com.b570.pilot.protocol.WifiInfo
import com.b570.pilot.protocol.WifiSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 展示模式要像真車一樣說協定（畫面工程師都靠它開發）。 */
class DemoTelemetrySourceTest {

    private class Recorder : LinkListener {
        val frames = CopyOnWriteArrayList<Frame>()
        override fun onFrame(frame: Frame) { frames += frame }
        override fun onRtt(ms: Float) {}
        override fun onEspRtt(ms: Float) {}
        override fun onRssi(dbm: Int) {}
        fun telems() = frames.filter { it.type == MsgType.TELEM }.map { Telemetry.decode(it.payload) }
        fun events() = frames.filter { it.type == MsgType.EVENT }.map { Event.decode(it.payload) }
    }

    private suspend fun DemoTelemetrySource.ack(type: Int, payload: ByteArray): AckStatus {
        val f = request(type, payload) { it.type == MsgType.ACK && (it.payload[0].toInt() and 0xFF) == type }
        assertNotNull("no ACK for ${MsgType.name(type)}", f)
        return Ack.decode(f!!.payload).status
    }

    @Test fun demoSpeaksTheProtocol() = runBlocking {
        val rec = Recorder()
        val demo = DemoTelemetrySource(rec)
        demo.start(DemoTelemetrySource.DEMO_CREDS)
        withTimeout(3000) { demo.state.first { it is ConnectionState.Connected } }
        assertTrue(rec.frames.any { it.type == MsgType.HELLO_ACK })
        assertTrue(rec.frames.any { it.type == MsgType.INFO })
        assertTrue(rec.frames.any { it.type == MsgType.PARAM_VALUES })

        // 自動啟動（放下啟動）後持續送遙測，tick 每筆 +2（50 Hz）
        delay(1600)
        val t = rec.telems()
        assertTrue("telemetry count ${t.size}", t.size >= 40)
        assertEquals(2, (t[t.size - 1].tick - t[t.size - 2].tick) and 0xFFFF)
        assertTrue(t.last().motorsOn)
        assertTrue(rec.events().any { it.code == EventCode.MOTORS_ON })

        // PARAM_SET 夾限
        val pv = demo.request(MsgType.PARAM_SET, ParamSet(listOf(Params.MAX_SPEED to 5000f)).encode()) { it.type == MsgType.PARAM_VALUES }
        assertEquals(1000f, ParamValues.decode(pv!!.payload).values[Params.MAX_SPEED]!!, 0f)

        // 馬達運轉中不能存檔 / 校正零點
        assertEquals(AckStatus.BUSY, demo.ack(MsgType.PARAM_SAVE, ByteArray(0)))
        assertEquals(AckStatus.BUSY, demo.ack(MsgType.ZERO_CAL, ByteArray(0)))

        // 編排：載入 + 播放 → seqStep 出現
        val steps = listOf(SeqStep(100, 0, 300, SeqMode.RAMP), SeqStep(100, 400, 2000, SeqMode.SINE_TURN, 25))
        for (chunk in SeqLoad.chunk(steps)) assertEquals(AckStatus.OK, demo.ack(MsgType.SEQ_LOAD, chunk.encode()))
        assertEquals(AckStatus.OK, demo.ack(MsgType.SEQ_PLAY, SeqPlay(1).encode()))
        delay(200)
        assertTrue(rec.telems().last().seqPlaying)
        assertNotNull(rec.telems().last().seqStep)

        // 手動接管 → SEQ_ABORTED arg 1
        demo.setControl(300, 0)
        delay(200)
        assertTrue(rec.events().any { it.code == EventCode.SEQ_ABORTED && it.arg == 1 })
        demo.setControl(0, 0)

        // 停止馬達後可以存檔
        assertEquals(AckStatus.OK, demo.ack(MsgType.MOTOR, Motor(MotorAction.STOP).encode()))
        assertEquals(AckStatus.OK, demo.ack(MsgType.PARAM_SAVE, ByteArray(0)))
        assertTrue(rec.events().any { it.code == EventCode.PARAMS_SAVED && it.arg == 0 })

        demo.stop()
        assertEquals(ConnectionState.Idle, demo.state.value)
    }

    // ------------------------------------------------------------------ 直接推進模擬器（不靠真實時間）

    private class SimDriver {
        val demo = DemoTelemetrySource(Recorder())
        val sim = demo.sim
        val events = ArrayList<Event>()
        var last: Telemetry? = null

        init { sim.reset(DemoTelemetrySource.DEMO_CREDS) }

        fun run(seconds: Float, speed: Int = 0, turn: Int = 0): List<Telemetry> {
            val out = ArrayList<Telemetry>()
            repeat((seconds / DT).toInt()) {
                val t = sim.step(DT, speed, turn, events)
                out += t
                last = t
            }
            return out
        }

        fun ack(type: Int, payload: ByteArray): AckStatus =
            sim.command(type, payload).first { it.first == MsgType.ACK }.second.let { Ack.decode(it).status }

        companion object { const val DT = 0.02f }
    }

    /** 比照韌體 Motion_Tick：編排的目標速度也要夾限在 ±MAX_SPEED（App 的編排預覽也是這樣算）。 */
    @Test fun demoClampsSequenceSpeedToMaxSpeed() {
        val d = SimDriver()
        d.run(1.6f)
        assertTrue("auto start", d.last!!.motorsOn)
        d.sim.command(MsgType.PARAM_SET, ParamSet(listOf(Params.MAX_SPEED to 100f)).encode())
        for (chunk in SeqLoad.chunk(listOf(SeqStep(300, 1500, 3000)))) assertEquals(AckStatus.OK, d.ack(MsgType.SEQ_LOAD, chunk.encode()))
        assertEquals(AckStatus.OK, d.ack(MsgType.SEQ_PLAY, SeqPlay(1).encode()))
        val t = d.run(1.5f)
        assertTrue(t.last().seqPlaying)
        assertEquals(100, t.maxOf { it.targetSpeed })
        assertTrue(t.all { it.targetTurn <= 1000 })
    }

    /** 比照韌體 can_start()：電壓低於 LOWBAT_V 拒絕啟動；充電後啟動，下次低電壓要再停一次。 */
    @Test fun demoRefusesStartBelowLowBattery() {
        val d = SimDriver()
        d.run(1.6f)
        assertTrue(d.last!!.motorsOn)

        d.sim.battery = 9.5f
        d.run(0.1f)
        assertFalse(d.last!!.motorsOn)
        assertEquals(StopReason.LOW_BATT, d.last!!.stopReason)
        assertTrue(d.events.any { it.code == EventCode.LOW_BATTERY })
        assertEquals(AckStatus.NOT_READY, d.ack(MsgType.MOTOR, Motor(MotorAction.START).encode()))

        d.sim.battery = 12.3f
        d.run(0.1f)
        assertEquals(AckStatus.OK, d.ack(MsgType.MOTOR, Motor(MotorAction.START).encode()))
        d.run(0.2f)
        assertTrue(d.last!!.motorsOn)

        d.events.clear()
        d.sim.battery = 9.5f
        d.run(0.1f)
        assertFalse("low battery stop must re-arm after a restart", d.last!!.motorsOn)
        assertTrue(d.events.any { it.code == EventCode.LOW_BATTERY })
    }

    /** 設定頁在展示模式改 Wi-Fi 後，WIFI_INFO 要回報新值；零點校正比照韌體（ACK → PARAM_VALUES(6) → 事件 + 自動存檔）。 */
    @Test fun demoWifiSetAndZeroCal() = runBlocking {
        val rec = Recorder()
        val demo = DemoTelemetrySource(rec)
        demo.start(DemoTelemetrySource.DEMO_CREDS)
        withTimeout(3000) { demo.state.first { it is ConnectionState.Connected } }

        val ok = demo.request(MsgType.WIFI_SET, WifiSet("B570-NEW1", "newpass99", 6).encode()) { it.type == MsgType.ESP_ACK }
        assertEquals(AckStatus.OK, EspAck.decode(ok!!.payload).status)
        val wi = demo.request(MsgType.WIFI_GET, WifiGet.encode()) { it.type == MsgType.WIFI_INFO }
        val info = WifiInfo.decode(wi!!.payload)
        assertEquals("B570-NEW1", info.ssid)
        assertEquals("newpass99", info.password)
        val bad = demo.request(MsgType.WIFI_SET, WifiSet("bad ssid!", "x", 0).encode()) { it.type == MsgType.ESP_ACK }
        assertEquals(AckStatus.BAD_ARG, EspAck.decode(bad!!.payload).status)

        assertEquals(AckStatus.OK, demo.ack(MsgType.MOTOR, Motor(MotorAction.STOP).encode()))
        delay(100)
        assertEquals(AckStatus.OK, demo.ack(MsgType.ZERO_CAL, ByteArray(0)))
        delay(50)
        assertTrue(rec.frames.any { it.type == MsgType.PARAM_VALUES && ParamValues.decode(it.payload).values.keys == setOf(Params.ZERO_ANGLE) })
        assertTrue(rec.events().any { it.code == EventCode.ZERO_CAL })
        assertTrue(rec.events().any { it.code == EventCode.PARAMS_SAVED && it.arg == 0 })
        demo.stop()
    }
}
