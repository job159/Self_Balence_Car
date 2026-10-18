package com.b570.pilot.data

import com.b570.pilot.protocol.ControlSource
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class TelemetryHistoryTest {

    private fun t(i: Int) = Telemetry(
        tick = i * 2, pitchDeg = i.toFloat(), rollDeg = 0f, gyroBalDps = 0f, gyroTurnDps = 0f,
        velL = i, velR = -i, pwmL = 0, pwmR = 0, pwmBal = 0, pwmVel = 0, pwmTurn = 0,
        targetSpeed = 0, targetTurn = 0, batteryV = 12f, flags = 0, stopReason = StopReason.RUNNING,
        seqStep = null, seqLoop = 0, source = ControlSource.NONE, linkQ = 50, headingDeg = 0f, loopUs = 400,
    )

    @Test fun copyLastOldestFirstAndWraps() {
        val h = TelemetryHistory(capacity = 10)
        repeat(25) { h.add(t(it), it * 20L) }
        assertEquals(10, h.size)
        assertEquals(25L, h.version)
        val out = FloatArray(4)
        assertEquals(4, h.copyLast(TelemetryHistory.Channel.PITCH, 4, out))
        assertEquals(listOf(21f, 22f, 23f, 24f), out.toList())
        val all = FloatArray(20)
        assertEquals(10, h.copyLast(TelemetryHistory.Channel.VEL_R, 20, all))
        assertEquals((15..24).map { -it.toFloat() }, all.take(10))
        val ts = LongArray(3)
        assertEquals(3, h.copyLastTimes(3, ts))
        assertEquals(listOf(440L, 460L, 480L), ts.toList())
        assertEquals(24f, h.latest(TelemetryHistory.Channel.PITCH), 0f)
        val mm = FloatArray(2)
        h.minMax(TelemetryHistory.Channel.PITCH, 5, mm)
        assertEquals(20f, mm[0], 0f)
        assertEquals(24f, mm[1], 0f)
    }

    @Test fun clearResets() {
        val h = TelemetryHistory(capacity = 5)
        repeat(3) { h.add(t(it), 0) }
        h.clear()
        assertEquals(0, h.size)
        assertEquals(0, h.copyLast(TelemetryHistory.Channel.PITCH, 5, FloatArray(5)))
        assertTrue(h.latest(TelemetryHistory.Channel.PITCH).isNaN())
    }

    @Test fun csvExport() {
        val h = TelemetryHistory(capacity = 100)
        repeat(3) { h.add(t(it), 1000L + it * 20) }
        val w = StringWriter()
        h.exportCsv(w)
        val lines = w.toString().trim().lines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("time_s,pitch,roll"))
        assertTrue(lines[2].startsWith("0.020,1.000,"))
        assertEquals(1 + TelemetryHistory.Channel.entries.size, lines[1].split(',').size)
    }
}
