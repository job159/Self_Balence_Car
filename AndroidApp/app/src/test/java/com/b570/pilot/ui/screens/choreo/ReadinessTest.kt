package com.b570.pilot.ui.screens.choreo

import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {

    private val connected = ConnectionState.Connected(null, 0L)

    private fun status(motorsOn: Boolean = false, upright: Boolean = true, lowBattery: Boolean = false, reason: StopReason = StopReason.APP) =
        CarStatus(motorsOn = motorsOn, upright = upright, seqPlaying = false, lowBattery = lowBattery, stopReason = reason)

    @Test fun doesNotOfferToStartWhenTheCarCannotStart() {
        // 感測器異常：韌體控制迴圈已停，「先啟動平衡再播放」不會有任何作用
        val imu = Readiness.of(connected, status(reason = StopReason.IMU))
        assertEquals(Readiness.SENSOR, imu)
        assertFalse(imu.canPlay)
        // 電壓低於 LOWBAT_V：韌體 can_start() 拒絕
        val low = Readiness.of(connected, status(lowBattery = true, reason = StopReason.BOOT))
        assertEquals(Readiness.LOW_BATT, low)
        assertFalse(low.canPlay)
        assertTrue(low.blockedZh.isNotEmpty())
    }

    @Test fun normalStates() {
        assertEquals(Readiness.MOTORS_OFF, Readiness.of(connected, status()))
        assertEquals(Readiness.NOT_UPRIGHT, Readiness.of(connected, status(upright = false)))
        assertEquals(Readiness.READY, Readiness.of(connected, status(motorsOn = true)))
        assertEquals(Readiness.LINK_LOST, Readiness.of(ConnectionState.LinkLost(0L), status(motorsOn = true)))
    }
}
