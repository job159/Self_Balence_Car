package com.b570.pilot.ui.screens.drive

import com.b570.pilot.data.Gear
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.ControlSource
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class DriveModelsTest {

    private fun telem(flags: Int, reason: StopReason, batteryV: Float = 12f) = Telemetry(
        tick = 0, pitchDeg = 0f, rollDeg = 0f, gyroBalDps = 0f, gyroTurnDps = 0f, velL = 0, velR = 0,
        pwmL = 0, pwmR = 0, pwmBal = 0, pwmVel = 0, pwmTurn = 0, targetSpeed = 0, targetTurn = 0,
        batteryV = batteryV, flags = flags, stopReason = reason, seqStep = null, seqLoop = 0,
        source = ControlSource.NONE, linkQ = 50, headingDeg = 0f, loopUs = 400,
    )

    private val connected = ConnectionState.Connected(null, 0L)
    private val upright = Telemetry.FLAG_UPRIGHT

    @Test fun linkLostBlocksInput() {
        // 訊號中斷：搖桿 / 按住駕駛停用並歸零，恢復後要重新按下才會送出
        val b = controlBlockOf(ConnectionState.LinkLost(0L), telem(Telemetry.FLAG_MOTORS_ON or upright, StopReason.RUNNING))
        assertEquals(ControlBlock.LINK_LOST, b)
        assertTrue(b.blocksInput)
        assertTrue(controlBlockOf(ConnectionState.Idle, null).blocksInput)
        assertTrue(controlBlockOf(ConnectionState.Handshaking, null).blocksInput)
        // 正常運轉 / 馬達停止（車子會忽略目標）不擋
        assertFalse(controlBlockOf(connected, telem(Telemetry.FLAG_MOTORS_ON or upright, StopReason.RUNNING)).blocksInput)
        assertFalse(controlBlockOf(connected, telem(upright, StopReason.APP)).blocksInput)
        assertFalse(ControlBlock.LINK_LOST.bodyZh.contains("已自動煞停"))
    }

    @Test fun reconnectingBlocksInputUntilConnected() {
        // 自動重連中：Failed / Idle / 連線中都顯示「自動重連中…」並封鎖輸入
        val failed = ConnectionState.Failed("連不上車子")
        assertEquals(ControlBlock.RECONNECTING, controlBlockOf(failed, null, reconnecting = true))
        assertEquals(ControlBlock.RECONNECTING, controlBlockOf(ConnectionState.Idle, null, reconnecting = true))
        assertEquals(ControlBlock.RECONNECTING, controlBlockOf(ConnectionState.RequestingWifi("B570-A1B2"), null, reconnecting = true))
        assertTrue(ControlBlock.RECONNECTING.blocksInput)
        // 沒在重連：維持原本的規則
        assertEquals(ControlBlock.OFFLINE, controlBlockOf(failed, null))
        assertEquals(ControlBlock.CONNECTING, controlBlockOf(ConnectionState.Handshaking, null))
        // 訊號中斷優先；連上後（重連狀態還沒清掉的瞬間）照一般規則
        assertEquals(ControlBlock.LINK_LOST, controlBlockOf(ConnectionState.LinkLost(0L), null, reconnecting = true))
        assertEquals(ControlBlock.NONE, controlBlockOf(connected, telem(Telemetry.FLAG_MOTORS_ON or upright, StopReason.RUNNING), reconnecting = true))
    }

    @Test fun imuAndLowBatteryAreNotJustMotorsOff() {
        assertEquals(ControlBlock.SENSOR, controlBlockOf(connected, telem(upright, StopReason.IMU)))
        // 開機就低電壓：stop_reason 仍是 BOOT，但車子不會接受啟動
        assertEquals(ControlBlock.LOW_BATT, controlBlockOf(connected, telem(upright or Telemetry.FLAG_LOW_BATT, StopReason.BOOT, 9.8f)))
        assertEquals(ControlBlock.MOTORS_OFF, controlBlockOf(connected, telem(upright, StopReason.APP)))
        assertEquals(ControlBlock.NONE, controlBlockOf(connected, telem(Telemetry.FLAG_MOTORS_ON or upright, StopReason.RUNNING)))
    }

    @Test fun startDisabledOnlyForSensorFault() {
        assertTrue(startBlockedOf(telem(upright, StopReason.IMU)))
        assertFalse(startBlockedOf(telem(upright, StopReason.ESTOP)))
        assertFalse(startBlockedOf(telem(upright or Telemetry.FLAG_LOW_BATT, StopReason.LOW_BATT, 9.8f)))
        assertFalse(startBlockedOf(null))
    }

    @Test fun controllerNeverThrowsOrSendsGarbageOnNaN() {
        // NaN.coerceIn 仍是 NaN → roundToInt 會在觸控事件裡丟例外：一律當 0
        var last = 1 to 1
        val c = DriveController { s, t -> last = s to t }
        c.set(Float.NaN, Float.NaN)
        assertEquals(0 to 0, last)
        c.set(0.5f, Float.NaN)
        assertEquals(0, last.second)
        // 車上回報的 MAX_SPEED 異常 → 用預設值
        c.configure(Float.NaN, Gear.FAST)
        c.set(1f, 0f)
        assertEquals(MAX_SPEED_DEFAULT.roundToInt() to 0, last)
        c.configure(-5f, Gear.FAST)
        c.set(1f, 0f)
        assertEquals(MAX_SPEED_DEFAULT.roundToInt() to 0, last)
    }
}
