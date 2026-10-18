package com.b570.pilot.link

import com.b570.pilot.protocol.FrameCodec
import com.b570.pilot.protocol.Motor
import com.b570.pilot.protocol.MotorAction
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.ParamSet
import com.b570.pilot.protocol.SeqPlay
import com.b570.pilot.protocol.SeqStop
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 緊急停止 / 停止要取消的「會讓車子動起來」的指令：只有 MOTOR START 與 SEQ_PLAY。 */
class MotionCommandTest {

    @Test fun onlyStartAndPlayAreMotion() {
        assertTrue(isMotionCommand(MsgType.MOTOR, Motor(MotorAction.START).encode()))
        assertTrue(isMotionCommand(MsgType.SEQ_PLAY, SeqPlay(1).encode()))
        assertFalse(isMotionCommand(MsgType.MOTOR, Motor(MotorAction.STOP).encode()))
        assertFalse(isMotionCommand(MsgType.MOTOR, Motor(MotorAction.ESTOP).encode()))
        assertFalse(isMotionCommand(MsgType.SEQ_STOP, SeqStop.encode()))
        assertFalse(isMotionCommand(MsgType.PARAM_SET, ParamSet(listOf(0 to 1f)).encode()))
        assertFalse(isMotionCommand(MsgType.MOTOR, ByteArray(0)))
    }

    @Test fun builtFramesAreRecognized() {
        // 傳送佇列裡是整幀位元組：清除時要能認出 START / SEQ_PLAY，ESTOP 絕不能被清掉
        assertTrue(isMotionFrame(FrameCodec.build(MsgType.MOTOR, 7, Motor(MotorAction.START).encode())))
        assertTrue(isMotionFrame(FrameCodec.build(MsgType.SEQ_PLAY, 255, SeqPlay(0).encode())))
        assertFalse(isMotionFrame(FrameCodec.build(MsgType.MOTOR, 8, Motor(MotorAction.ESTOP).encode())))
        assertFalse(isMotionFrame(FrameCodec.build(MsgType.MOTOR, 9, Motor(MotorAction.STOP).encode())))
        assertFalse(isMotionFrame(FrameCodec.build(MsgType.SEQ_STOP, 1, SeqStop.encode())))
        assertFalse(isMotionFrame(ByteArray(3)))
    }
}
