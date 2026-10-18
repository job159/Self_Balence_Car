package com.b570.pilot.data

import com.b570.pilot.link.CarLink
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.HotspotConfig
import com.b570.pilot.link.LinkListener
import com.b570.pilot.link.LinkMode
import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.protocol.Ack
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.AngleAlgo
import com.b570.pilot.protocol.ByteReader
import com.b570.pilot.protocol.ControlSource
import com.b570.pilot.protocol.Discover
import com.b570.pilot.protocol.DiscoverAck
import com.b570.pilot.protocol.EspAck
import com.b570.pilot.protocol.Event
import com.b570.pilot.protocol.EventCode
import com.b570.pilot.protocol.Frame
import com.b570.pilot.protocol.HelloAck
import com.b570.pilot.protocol.Info
import com.b570.pilot.protocol.Le
import com.b570.pilot.protocol.MotorAction
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.ParamValues
import com.b570.pilot.protocol.Params
import com.b570.pilot.protocol.ProtocolException
import com.b570.pilot.protocol.SeqLoad
import com.b570.pilot.protocol.SeqMode
import com.b570.pilot.protocol.SeqStep
import com.b570.pilot.protocol.StaInfo
import com.b570.pilot.protocol.StaSet
import com.b570.pilot.protocol.StaState
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.protocol.WifiInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * 展示模式：一台「會說協定」的模擬車子。
 * 產生真實格式的 TELEM / ACK / PARAM_VALUES / EVENT 幀，走與真車完全相同的解碼路徑，
 * 所以整個 UI 不需要車子也能完整操作。
 * v1.1：遙測帶 ctrl_seq / ctrl_age（42 bytes），並回報模擬的控制延遲（約 15–30 ms）與上行遺失；
 * 熱點模式：STA_SET / STA_GET（STA_INFO）/ DISCOVER（DISCOVER_ACK）都會回覆，連線路徑跟著熱點設定切換。
 */
class DemoTelemetrySource(private val listener: LinkListener) : CarLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var job: Job? = null
    private val control = AtomicInteger(0)
    private val outSeq = AtomicInteger(0)
    /** 模擬器本體（internal：單元測試可直接逐步推進、不靠真實時間）。 */
    internal val sim = Sim()

    override fun start(credentials: WifiCredentials) {
        stop()
        job = scope.launch {
            _state.value = ConnectionState.RequestingWifi(credentials.ssid)
            delay(500)
            _state.value = ConnectionState.Handshaking
            delay(350)
            synchronized(sim) { sim.reset(credentials) }
            emit(MsgType.HELLO_ACK, DEMO_HELLO.encode())
            delay(15)
            emit(MsgType.INFO, DEMO_INFO.encode())
            emit(MsgType.PARAM_VALUES, ParamValues(synchronized(sim) { LinkedHashMap(sim.params) }).encode())
            listener.onLinkMode(currentMode())
            _state.value = ConnectionState.Connected(DEMO_INFO, System.currentTimeMillis())
            runLoop()
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        control.set(0)
        _state.value = ConnectionState.Idle
    }

    override fun setControl(speedMmps: Int, turnPermille: Int) {
        control.set((speedMmps.coerceIn(-32768, 32767) shl 16) or (turnPermille.coerceIn(-1000, 1000) and 0xFFFF))
    }

    override fun send(type: Int, payload: ByteArray): Boolean {
        if (_state.value !is ConnectionState.Connected) return false
        scope.launch { delay(4); handle(type, payload).forEach { listener.onFrame(it) } }
        return true
    }

    override suspend fun request(type: Int, payload: ByteArray, timeoutMs: Long, retries: Int, matcher: (Frame) -> Boolean): Frame? {
        if (_state.value !is ConnectionState.Connected) return null
        delay(Random.nextLong(8, 18))
        val replies = handle(type, payload)
        replies.forEach { listener.onFrame(it) }
        // 熱點設定變了：展示車不重開機，直接切換「連線路徑」讓 UI 看得到
        if (type == MsgType.STA_SET) listener.onLinkMode(currentMode())
        return replies.firstOrNull(matcher)
    }

    private fun currentMode(): LinkMode = if (synchronized(sim) { sim.staEnabled }) LinkMode.HOTSPOT else LinkMode.DIRECT

    private fun emit(type: Int, payload: ByteArray) = listener.onFrame(frame(type, payload))
    private fun frame(type: Int, payload: ByteArray) = Frame(type, outSeq.getAndIncrement() and 0xFF, payload)

    // ------------------------------------------------------------------ loop

    private suspend fun kotlinx.coroutines.CoroutineScope.runLoop() {
        var next = System.nanoTime()
        var n = 0L
        val events = ArrayList<Event>(4)
        var ctrlSeq = 0
        var sentCtrl = 0.0
        while (isActive) {
            val hz = synchronized(sim) { (sim.params[Params.TELEM_HZ] ?: 50f).coerceIn(10f, 100f) }
            val periodNs = (1e9 / hz).toLong()
            val c = control.get()
            events.clear()
            // 模擬手機 50 Hz 的 CTRL（另有 PING 等幀共用 SEQ）：這筆遙測回報最後收到的 CTRL SEQ 與經過的控制週期
            sentCtrl += 50.0 / hz
            while (sentCtrl >= 1.0) {
                sentCtrl -= 1.0
                ctrlSeq = (ctrlSeq + if (Random.nextInt(25) == 0) 2 else 1) and 0xFF
            }
            // 與韌體相同：收到後的下一個控制週期套用，該週期送出的遙測 ctrl_age = 1（50 Hz 遙測時為 1 或 2）
            val ctrlAge = Random.nextInt(1, 3)
            val telem = synchronized(sim) { sim.step(1f / hz, c shr 16, (c and 0xFFFF).toShort().toInt(), events, ctrlSeq, ctrlAge) }
            emit(MsgType.TELEM, telem.encode())
            events.forEach { emit(MsgType.EVENT, it.encode()) }
            // 控制延遲（手機 → 車上套用）：一般 15–27 ms，偶爾 Wi-Fi 抖動多 5–13 ms
            listener.onControlLatency(15f + Random.nextFloat() * 12f + if (Random.nextInt(40) == 0) 5f + Random.nextFloat() * 8f else 0f)
            if (n % (hz / 2).toLong().coerceAtLeast(1) == 0L) listener.onUplinkSample(52, 52 - if (Random.nextInt(8) == 0) 1 else 0)

            val ms = (n * 1000 / hz).toLong()
            if (n % (hz / 2).toLong().coerceAtLeast(1) == 0L) listener.onRtt(6f + Random.nextFloat() * 6f)
            if (n % (hz * 2).toLong() == 0L) listener.onEspRtt(2.5f + Random.nextFloat() * 2.5f)
            if (n % (hz * 3).toLong() == 0L) {
                listener.onRssi(-47 - Random.nextInt(0, 7))
                emit(MsgType.WIFI_INFO, synchronized(sim) { sim.wifiInfo(ms) }.encode())
            }
            n++
            next += periodNs
            val wait = next - System.nanoTime()
            if (wait > 0) delay(wait / 1_000_000L) else if (wait < -periodNs * 5) next = System.nanoTime()
        }
    }

    // ------------------------------------------------------------------ commands

    private fun handle(type: Int, p: ByteArray): List<Frame> = try {
        synchronized(sim) { sim.command(type, p) }.map { (t, payload) -> frame(t, payload) }
    } catch (e: ProtocolException) {
        listOf(frame(MsgType.ACK, Ack(type, AckStatus.BAD_ARG).encode()))
    } catch (e: IllegalArgumentException) {
        listOf(frame(MsgType.ACK, Ack(type, AckStatus.BAD_ARG).encode()))
    }

    /** 簡化的平衡車模型（只求「看起來真實」）。所有存取都在 synchronized(sim) 內。 */
    internal class Sim {
        val params = LinkedHashMap<Int, Float>()
        var creds = DEMO_CREDS
        var t = 0.0
        var tick = 0
        var motorsOn = false
        var stopReason = StopReason.BOOT
        var dirty = false
        var tgtSpeed = 0f
        var tgtTurn = 0f
        var v = 0f
        var pitch = 0f
        var heading = 0f
        var battery = 12.32f
        /** 最近一次遙測的電壓（含負載壓降），比照韌體 can_start() 用。 */
        var vbat = 12.32f
        var lowBattFired = false
        var lastInputT = 0.0
        var nextRandomT = 70.0
        var pickupUntil = -1.0
        var autoStartAt = 1.2
        // 編排
        val loaded = arrayOfNulls<SeqStep>(SeqStep.MAX_STEPS)
        var loadedTotal = 0
        var loadedCount = 0
        var playing = false
        var seqIdx = 0
        var seqLoop = 0
        var seqLoops = 0
        var stepStartT = 0.0
        var fromSpeed = 0f
        var fromTurn = 0f
        var source = ControlSource.NONE
        // 熱點模式設定（像 ESP 的 Flash：重新連線也保留）
        var staEnabled = false
        var staSsid = ""
        var staPw = ""

        fun reset(c: WifiCredentials) {
            creds = c
            params.clear(); params.putAll(Params.defaults())
            t = 0.0; tick = 0; motorsOn = false; stopReason = StopReason.BOOT; dirty = false
            tgtSpeed = 0f; tgtTurn = 0f; v = 0f; pitch = 0.6f; heading = 0f
            battery = 12.32f; vbat = battery; lowBattFired = false; lastInputT = 0.0; nextRandomT = 70.0
            pickupUntil = -1.0; autoStartAt = 1.2
            loadedTotal = 0; loadedCount = 0; playing = false
        }

        private fun pv(id: Int) = params[id] ?: Params.byId(id)!!.default

        fun step(dt: Float, cmdSpeed: Int, cmdTurn: Int, events: MutableList<Event>, ctrlSeq: Int? = null, ctrlAge: Int? = null): Telemetry {
            t += dt
            tick = (tick + (dt * 100f).roundToInt()) and 0xFFFF
            if (cmdSpeed != 0 || cmdTurn != 0) lastInputT = t

            // 開機後自動「放下啟動」；被拿起後 3 秒自動放回（比照韌體：電壓 / 傾角不允許就不啟動）
            if (!motorsOn && autoStartAt in 0.0..t) {
                autoStartAt = -1.0
                if (canStart()) { startMotors(); events += Event.of(EventCode.MOTORS_ON, 3) }
            }
            if (pickupUntil in 0.0..t) {
                pickupUntil = -1.0
                if (canStart()) { startMotors(); events += Event.of(EventCode.MOTORS_ON, 3) }
            }
            // 偶發事件：閒置一段時間後模擬被拿起
            if (motorsOn && !playing && t > nextRandomT && t - lastInputT > 25.0) {
                nextRandomT = t + 90.0 + Random.nextDouble() * 60.0
                stopMotors(StopReason.PICKUP)
                events += Event.of(EventCode.PICKED_UP, 0)
                pickupUntil = t + 3.0
            }

            // 目標：編排或搖桿
            var wantSpeed = 0f
            var wantTurn = 0f
            source = ControlSource.NONE
            if (motorsOn) {
                if (playing) {
                    if (abs(cmdSpeed) > 30 || abs(cmdTurn) > 50) {
                        playing = false
                        events += Event.of(EventCode.SEQ_ABORTED, 1)
                        wantSpeed = cmdSpeed.toFloat(); wantTurn = cmdTurn.toFloat(); source = ControlSource.APP
                    } else {
                        val out = seqTarget(events)
                        wantSpeed = out.first; wantTurn = out.second
                        source = if (playing) ControlSource.SEQUENCE else ControlSource.NONE
                    }
                } else {
                    wantSpeed = cmdSpeed.toFloat()
                    wantTurn = cmdTurn.toFloat()
                    if (cmdSpeed != 0 || cmdTurn != 0) source = ControlSource.APP
                }
                // 比照韌體 Motion_Tick：不論來源（搖桿或編排）都夾限在 ±MAX_SPEED、±1000‰
                wantSpeed = wantSpeed.coerceIn(-pv(Params.MAX_SPEED), pv(Params.MAX_SPEED))
                wantTurn = wantTurn.coerceIn(-1000f, 1000f)
            }
            if (motorsOn) {
                tgtSpeed = approach(tgtSpeed, wantSpeed, pv(Params.ACCEL) * dt)
                tgtTurn = approach(tgtTurn, wantTurn, pv(Params.TURN_ACCEL) * dt)
            } else {
                tgtSpeed = 0f; tgtTurn = 0f
            }

            // 動態
            val vPrev = v
            v += (tgtSpeed - v) * (dt / 0.28f) + gauss() * (if (motorsOn) 6f else 0f)
            if (!motorsOn) v *= 0.8f
            val accel = (v - vPrev) / dt
            val zero = pv(Params.ZERO_ANGLE)
            val pitchPrev = pitch
            pitch = if (motorsOn) {
                val lean = Math.toDegrees(atan((accel / 9810f).toDouble())).toFloat() * 0.9f
                val ts = t.toFloat()
                zero + lean + 0.55f * sin(2f * PI.toFloat() * 1.3f * ts) + 0.22f * sin(2f * PI.toFloat() * 3.1f * ts + 1f) + gauss() * 0.08f
            } else if (pickupUntil > 0) {
                pitch + (25f - pitch) * 0.08f + gauss() * 0.6f
            } else {
                pitch + (zero + 0.4f - pitch) * 0.05f + gauss() * 0.05f
            }
            val gyroBal = (pitch - pitchPrev) / dt
            val turnRate = if (motorsOn) tgtTurn / 1000f * pv(Params.MAX_TURN) * 4.5f + gauss() * 1.2f else gauss() * 0.3f
            heading += turnRate * dt
            if (heading > 180f) heading -= 360f
            if (heading < -180f) heading += 360f
            val diff = Math.toRadians(turnRate.toDouble()).toFloat() * 80f
            val velL = if (motorsOn) v + diff else 0f
            val velR = if (motorsOn) v - diff else 0f

            val pwmBal = if (motorsOn) (pv(Params.BAL_KP) / 22500f * 180f * (pitch - zero) + pv(Params.BAL_KD) / 108f * 6f * gyroBal) else 0f
            val pwmVel = if (motorsOn) (pv(Params.VEL_KP) / 16000f * 1.4f * (v - tgtSpeed) + accel * 0.04f) else 0f
            val pwmTurn = if (motorsOn) pv(Params.TURN_KP) / 4200f * 0.9f * tgtTurn else 0f
            val pwmL = (pwmBal + pwmVel + pwmTurn).coerceIn(-6900f, 6900f)
            val pwmR = (pwmBal + pwmVel - pwmTurn).coerceIn(-6900f, 6900f)

            // 電池：緩慢放電 + 負載壓降
            battery -= dt * (if (motorsOn) 0.0006f else 0.00015f)
            val sag = (abs(pwmL) + abs(pwmR)) / 6900f * 0.22f
            vbat = battery - sag + gauss() * 0.008f
            val lowBat = vbat < pv(Params.LOWBAT_V)
            if (lowBat && motorsOn && !lowBattFired) {
                lowBattFired = true
                events += Event.of(EventCode.LOW_BATTERY, (vbat * 100).roundToInt())
                stopMotors(StopReason.LOW_BATT)
            }

            var flags = Telemetry.FLAG_APP_LINK or Telemetry.FLAG_CRED_OK
            if (motorsOn) flags = flags or Telemetry.FLAG_MOTORS_ON
            if (playing) flags = flags or Telemetry.FLAG_SEQ_PLAYING
            if (lowBat) flags = flags or Telemetry.FLAG_LOW_BATT
            if (abs(pitch - zero) < pv(Params.FALL_ANGLE)) flags = flags or Telemetry.FLAG_UPRIGHT
            if (dirty) flags = flags or Telemetry.FLAG_PARAMS_DIRTY

            return Telemetry(
                tick = tick,
                pitchDeg = pitch.coerceIn(-90f, 90f),
                rollDeg = 0.4f + gauss() * 0.15f + tgtTurn / 1000f * 2.5f,
                gyroBalDps = gyroBal.coerceIn(-1990f, 1990f),
                gyroTurnDps = turnRate,
                velL = velL.roundToInt(),
                velR = velR.roundToInt(),
                pwmL = pwmL.roundToInt(),
                pwmR = pwmR.roundToInt(),
                pwmBal = pwmBal.roundToInt().coerceIn(-6900, 6900),
                pwmVel = pwmVel.roundToInt().coerceIn(-6900, 6900),
                pwmTurn = pwmTurn.roundToInt().coerceIn(-6900, 6900),
                targetSpeed = tgtSpeed.roundToInt(),
                targetTurn = tgtTurn.roundToInt(),
                batteryV = vbat,
                flags = flags,
                stopReason = if (motorsOn) StopReason.RUNNING else stopReason,
                seqStep = if (playing) seqIdx else null,
                seqLoop = seqLoop,
                source = source,
                linkQ = 50 - (if (Random.nextInt(100) < 3) 1 else 0),
                headingDeg = heading,
                loopUs = 380 + Random.nextInt(0, 70),
                ctrlSeq = ctrlSeq,
                ctrlAge = ctrlAge,
            )
        }

        private fun seqTarget(events: MutableList<Event>): Pair<Float, Float> {
            while (true) {
                val st = loaded[seqIdx] ?: run { playing = false; return 0f to 0f }
                val el = (t - stepStartT).toFloat()
                val dur = st.durMs / 1000f
                if (el >= dur) {
                    val end = stepValue(st, dur)
                    fromSpeed = end.first; fromTurn = end.second
                    seqIdx++
                    stepStartT += dur
                    if (seqIdx >= loadedTotal) {
                        seqLoop++
                        if (seqLoops != 0 && seqLoop >= seqLoops) {
                            playing = false
                            events += Event.of(EventCode.SEQ_DONE, 0)
                            return 0f to 0f
                        }
                        seqIdx = 0
                    }
                    continue
                }
                return stepValue(st, el)
            }
        }

        private fun stepValue(st: SeqStep, el: Float): Pair<Float, Float> {
            val w = 2f * PI.toFloat() * el / (st.periodMs / 1000f)
            return when (st.mode) {
                SeqMode.HOLD -> st.speed.toFloat() to st.turn.toFloat()
                SeqMode.SINE_TURN -> st.speed.toFloat() to st.turn * sin(w)
                SeqMode.SINE_SPEED -> st.speed * sin(w) to st.turn.toFloat()
                SeqMode.RAMP -> {
                    val f = (el / (st.durMs / 1000f)).coerceIn(0f, 1f)
                    (fromSpeed + (st.speed - fromSpeed) * f) to (fromTurn + (st.turn - fromTurn) * f)
                }
            }
        }

        /** 比照韌體 can_start()：電壓低於 LOWBAT_V 或 |pitch − ZERO_ANGLE| > FALL_ANGLE 不能啟動。 */
        private fun canStart(): Boolean =
            vbat >= pv(Params.LOWBAT_V) && abs(pitch - pv(Params.ZERO_ANGLE)) <= pv(Params.FALL_ANGLE)

        private fun startMotors() {
            motorsOn = true; stopReason = StopReason.RUNNING
            lowBattFired = false // 下次電壓過低時要再停一次
        }

        private fun stopMotors(reason: StopReason) {
            motorsOn = false; stopReason = reason; playing = false; tgtSpeed = 0f; tgtTurn = 0f
        }

        fun command(type: Int, p: ByteArray): List<Pair<Int, ByteArray>> {
            val out = ArrayList<Pair<Int, ByteArray>>(3)
            fun ack(s: AckStatus) { out += MsgType.ACK to Ack(type, s).encode() }
            fun event(c: EventCode, a: Int) { out += MsgType.EVENT to Event.of(c, a).encode() }
            when (type) {
                MsgType.MOTOR -> when (MotorAction.entries.firstOrNull { it.code == Le.u8(p, 0) }) {
                    MotorAction.START -> {
                        when {
                            motorsOn -> ack(AckStatus.OK)
                            pickupUntil > 0 || !canStart() -> ack(AckStatus.NOT_READY)
                            else -> { startMotors(); ack(AckStatus.OK); event(EventCode.MOTORS_ON, 2) }
                        }
                        autoStartAt = -1.0
                    }
                    MotorAction.STOP, MotorAction.ESTOP -> {
                        val estop = Le.u8(p, 0) == MotorAction.ESTOP.code
                        val wasPlaying = playing
                        val wasOn = motorsOn
                        stopMotors(if (estop) StopReason.ESTOP else StopReason.APP)
                        autoStartAt = -1.0
                        ack(AckStatus.OK)
                        if (wasPlaying) event(EventCode.SEQ_ABORTED, 4)
                        if (wasOn || estop) event(EventCode.MOTORS_OFF, stopReason.code)
                    }
                    null -> ack(AckStatus.BAD_ARG)
                }
                // 比照韌體：運轉中 BUSY、傾斜超過 ±10° BAD_ARG；成功 → ACK、PARAM_VALUES(6)、EVENT ZERO_CAL，並自動存檔
                MsgType.ZERO_CAL -> if (motorsOn) ack(AckStatus.BUSY) else if (abs(pitch) > 10f) ack(AckStatus.BAD_ARG) else {
                    val z = Params.clamp(Params.ZERO_ANGLE, pitch)
                    params[Params.ZERO_ANGLE] = z
                    dirty = false
                    ack(AckStatus.OK)
                    out += MsgType.PARAM_VALUES to ParamValues(mapOf(Params.ZERO_ANGLE to z)).encode()
                    event(EventCode.ZERO_CAL, (z * 100).roundToInt())
                    event(EventCode.PARAMS_SAVED, AckStatus.OK.code)
                }
                MsgType.PARAM_GET -> {
                    val id = Le.u8(p, 0)
                    val m = if (id == Params.ALL_ID) LinkedHashMap(params) else mapOf(id to pv(id))
                    out += MsgType.PARAM_VALUES to ParamValues(m).encode()
                }
                MsgType.PARAM_SET -> {
                    val n = p.size / 5
                    val applied = LinkedHashMap<Int, Float>()
                    for (i in 0 until n) {
                        val id = Le.u8(p, i * 5)
                        val v = Le.f32(p, i * 5 + 1)
                        val def = Params.byId(id) ?: continue
                        if (def.requiresMotorsOff && motorsOn) { applied[id] = pv(id); continue }
                        val c = def.clamp(v)
                        params[id] = c
                        applied[id] = c
                        dirty = true
                    }
                    out += MsgType.PARAM_VALUES to ParamValues(applied).encode()
                }
                MsgType.PARAM_SAVE -> if (motorsOn) ack(AckStatus.BUSY) else {
                    dirty = false
                    ack(AckStatus.OK)
                    event(EventCode.PARAMS_SAVED, 0)
                }
                MsgType.PARAM_DEFAULTS -> {
                    params.clear(); params.putAll(Params.defaults())
                    dirty = true
                    out += MsgType.PARAM_VALUES to ParamValues(LinkedHashMap(params)).encode()
                }
                MsgType.SEQ_LOAD -> {
                    val sl = SeqLoad.decode(p)
                    when {
                        playing -> ack(AckStatus.BUSY)
                        sl.total !in 1..SeqStep.MAX_STEPS || sl.offset + sl.steps.size > sl.total -> ack(AckStatus.BAD_ARG)
                        else -> {
                            if (sl.offset == 0) { loaded.fill(null); loadedCount = 0 }
                            sl.steps.forEachIndexed { i, s -> loaded[sl.offset + i] = s }
                            loadedTotal = sl.total
                            loadedCount = sl.offset + sl.steps.size
                            ack(AckStatus.OK)
                        }
                    }
                }
                MsgType.SEQ_PLAY -> if (!motorsOn || loadedTotal == 0 || loadedCount < loadedTotal) ack(AckStatus.NOT_READY) else {
                    seqLoops = Le.u8(p, 0)
                    seqIdx = 0; seqLoop = 0; stepStartT = t; fromSpeed = tgtSpeed; fromTurn = tgtTurn
                    playing = true
                    ack(AckStatus.OK)
                }
                MsgType.SEQ_STOP -> {
                    val was = playing
                    playing = false
                    ack(AckStatus.OK)
                    if (was) event(EventCode.SEQ_ABORTED, 4)
                }
                MsgType.INFO_GET -> out += MsgType.INFO to DEMO_INFO.encode()
                MsgType.HELLO -> out += MsgType.HELLO_ACK to DEMO_HELLO.encode()
                MsgType.WIFI_GET -> out += MsgType.WIFI_INFO to wifiInfo((t * 1000).toLong()).encode()
                MsgType.WIFI_SET -> {
                    // 模擬車記住新的 SSID / 密碼（WIFI_INFO 會回報新值）
                    val r = ByteReader(p)
                    val ssid = r.lenString()
                    val pw = r.lenString()
                    if (WifiCredentials.validateSsid(ssid) != null || WifiCredentials.validatePassword(pw) != null) {
                        out += MsgType.ESP_ACK to EspAck(type, AckStatus.BAD_ARG).encode()
                    } else {
                        creds = WifiCredentials(ssid, pw)
                        out += MsgType.ESP_ACK to EspAck(type, AckStatus.OK).encode()
                    }
                }
                MsgType.WIFI_FACTORY -> out += MsgType.ESP_ACK to EspAck(type, AckStatus.OK).encode()
                MsgType.STA_GET -> out += MsgType.STA_INFO to staInfo().encode()
                MsgType.STA_SET -> {
                    val st = try { StaSet.decode(p) } catch (e: ProtocolException) { null }
                    val ok = st != null && if (st.enable) {
                        HotspotConfig.validateSsid(st.ssid) == null && HotspotConfig.validatePassword(st.password) == null
                    } else {
                        st.ssid.isEmpty() || HotspotConfig.validateSsid(st.ssid) == null
                    }
                    if (st == null || !ok) {
                        out += MsgType.ESP_ACK to EspAck(type, AckStatus.BAD_ARG).encode()
                    } else {
                        staEnabled = st.enable
                        if (st.ssid.isNotEmpty()) { staSsid = st.ssid; staPw = st.password }
                        out += MsgType.ESP_ACK to EspAck(type, AckStatus.OK).encode()
                    }
                }
                MsgType.DISCOVER -> {
                    val nonce = Discover.decode(p).nonce
                    out += MsgType.DISCOVER_ACK to DiscoverAck(
                        nonce, DEMO_HELLO.chipId, if (staEnabled) DiscoverAck.MODE_HOTSPOT else DiscoverAck.MODE_DIRECT,
                        if (staEnabled) DEMO_HOTSPOT_IP else "192.168.4.1", false, creds.ssid,
                    ).encode()
                }
                MsgType.CTRL, MsgType.PING, MsgType.ESP_PING, MsgType.BYE -> Unit
                else -> ack(AckStatus.UNKNOWN_TYPE)
            }
            return out
        }

        fun staInfo(): StaInfo = StaInfo(
            enabled = staEnabled,
            state = if (staEnabled) StaState.CONNECTED else StaState.OFF,
            ip = if (staEnabled) DEMO_HOTSPOT_IP else "0.0.0.0",
            rssiDbm = if (staEnabled) -48 - Random.nextInt(0, 8) else null,
            ssid = staSsid,
            password = staPw,
        )

        fun wifiInfo(ms: Long): WifiInfo {
            val pk = (t * 50).toLong()
            return WifiInfo(6, 1, 17, 120 + ms / 1000, 38_400 - Random.nextLong(0, 600), pk * 2, 0, pk * 3, pk * 2, creds.ssid, creds.password)
        }

        private fun approach(cur: Float, target: Float, maxDelta: Float): Float =
            if (abs(target - cur) <= maxDelta) target else cur + sign(target - cur) * maxDelta

        private fun gauss(): Float = (Random.nextFloat() + Random.nextFloat() + Random.nextFloat() - 1.5f) * 1.15f
    }

    companion object {
        val DEMO_CREDS = WifiCredentials("B570-DEMO", "demo1234")
        val DEMO_INFO = Info(1, 1, 0, 0, Params.COUNT, SeqStep.MAX_STEPS, AngleAlgo.KALMAN, "B570")
        val DEMO_HELLO = HelloAck(0, 1, 1, 0, 0, 6, 8888, 0x00C0FFEEL)
        /** 展示車在「手機熱點」網段的位址。 */
        const val DEMO_HOTSPOT_IP = "192.168.43.57"
    }
}
