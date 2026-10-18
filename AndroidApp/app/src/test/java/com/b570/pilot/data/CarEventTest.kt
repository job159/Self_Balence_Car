package com.b570.pilot.data

import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.ControlSource
import com.b570.pilot.protocol.Event
import com.b570.pilot.protocol.EventCode
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarEventTest {

    @Test fun stopReasonImuFromSpec() {
        // PROTOCOL.md §4.1：stop_reason 8 = IMU（姿態感測器中斷停止）
        assertEquals(StopReason.IMU, StopReason.of(8))
        val e = CarEvent.fromEvent(Event.of(EventCode.MOTORS_OFF, 8))
        assertNotNull(e)
        assertEquals(Severity.DANGER, e!!.severity)
        assertTrue(e.messageZh.contains("感測器"))
    }

    @Test fun protectionStopsUseTheirOwnEvent() {
        // 被拿起 / 傾倒 / 低電壓另有專屬事件，MOTORS_OFF 不重複提示
        for (r in listOf(StopReason.PICKUP, StopReason.FALL, StopReason.LOW_BATT)) {
            assertNull(CarEvent.fromEvent(Event.of(EventCode.MOTORS_OFF, r.code)))
        }
    }

    @Test fun zeroCalFailureTexts() {
        assertEquals("請先停止馬達再校正零點", CarEvent.ackFailureZh(MsgType.ZERO_CAL, AckStatus.BUSY))
        assertTrue(CarEvent.ackFailureZh(MsgType.ZERO_CAL, AckStatus.BAD_ARG).contains("10°"))
        assertEquals("馬達運轉中，請先停止再儲存", CarEvent.ackFailureZh(MsgType.PARAM_SAVE, AckStatus.BUSY))
    }

    private fun telem(batteryV: Float, flags: Int, reason: StopReason = StopReason.BOOT) = Telemetry(
        tick = 0, pitchDeg = 0f, rollDeg = 0f, gyroBalDps = 0f, gyroTurnDps = 0f, velL = 0, velR = 0,
        pwmL = 0, pwmR = 0, pwmBal = 0, pwmVel = 0, pwmTurn = 0, targetSpeed = 0, targetTurn = 0,
        batteryV = batteryV, flags = flags, stopReason = reason, seqStep = null, seqLoop = 0,
        source = ControlSource.NONE, linkQ = 50, headingDeg = 0f, loopUs = 400,
    )

    /** 韌體 can_start()：先檢查電壓（剛開機還沒讀數 / 低於 LOWBAT_V），才檢查傾角。提示要說出真正原因。 */
    @Test fun motorStartRefusalExplainsTheRealCause() {
        val upright = Telemetry.FLAG_UPRIGHT
        // 9.9 V < LOWBAT_V 10.0，車子是直的 → 不能叫使用者「扶正」
        val low = CarEvent.motorStartRefusedZh(telem(9.9f, upright or Telemetry.FLAG_LOW_BATT), 10f)
        assertTrue(low, low.contains("電量過低"))
        // 旗標還沒反映（或 LOWBAT_V 剛改高）：用電壓與參數判斷
        assertTrue(CarEvent.motorStartRefusedZh(telem(10.4f, upright), 10.5f).contains("電量過低"))
        // 開機約 1 秒內電壓還沒有讀數
        assertTrue(CarEvent.motorStartRefusedZh(telem(0f, upright), 10f).contains("剛開機"))
        // 電量正常但倒下 → 扶正
        assertTrue(CarEvent.motorStartRefusedZh(telem(12f, 0), 10f).contains("扶正"))
        // 感測器異常
        assertTrue(CarEvent.motorStartRefusedZh(telem(12f, upright, StopReason.IMU), 10f).contains("感測器"))
        // 都正常（瞬間條件）→ 不要亂猜成「扶正」
        val generic = CarEvent.motorStartRefusedZh(telem(12f, upright), 10f)
        assertTrue(generic, !generic.contains("電量過低") && !generic.contains("把車子扶正"))
        // 沒有遙測：同時提到兩個原因
        val none = CarEvent.motorStartRefusedZh(null, 10f)
        assertTrue(none.contains("電量") && none.contains("扶正"))
    }

    @Test fun cancelledIsLocalAndQuiet() {
        assertEquals(AckStatus.UNKNOWN, AckStatus.of(AckStatus.CANCELLED.code)) // 不會從線路上解出來
        assertEquals("已取消", CarEvent.ackFailureZh(MsgType.MOTOR, AckStatus.CANCELLED))
    }

    @Test fun gearFactorsMatchDriveSpec() {
        assertEquals(0.30f, Gear.SLOW.speedFactor, 0f)
        assertEquals(0.60f, Gear.NORMAL.speedFactor, 0f)
        assertEquals(1.00f, Gear.FAST.speedFactor, 0f)
        assertEquals(0.50f, Gear.SLOW.turnFactor, 0f)
        assertEquals(0.75f, Gear.NORMAL.turnFactor, 0f)
        assertEquals(1.00f, Gear.FAST.turnFactor, 0f)
    }
}
