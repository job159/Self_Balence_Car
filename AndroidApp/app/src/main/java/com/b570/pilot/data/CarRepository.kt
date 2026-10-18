package com.b570.pilot.data

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.b570.pilot.B570Application
import com.b570.pilot.link.CarConnection
import com.b570.pilot.link.CarLink
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.DiscoveredCar
import com.b570.pilot.link.HotspotConfig
import com.b570.pilot.link.HotspotNet
import com.b570.pilot.link.LinkListener
import com.b570.pilot.link.LinkMode
import com.b570.pilot.link.LinkRequest
import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.link.isMotionCommand
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.Discover
import com.b570.pilot.protocol.DiscoverAck
import com.b570.pilot.protocol.EspAck
import com.b570.pilot.protocol.Event
import com.b570.pilot.protocol.EventCode
import com.b570.pilot.protocol.Frame
import com.b570.pilot.protocol.HelloAck
import com.b570.pilot.protocol.Info
import com.b570.pilot.protocol.InfoGet
import com.b570.pilot.protocol.Motor
import com.b570.pilot.protocol.MotorAction
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.ParamDefaults
import com.b570.pilot.protocol.ParamGet
import com.b570.pilot.protocol.ParamSave
import com.b570.pilot.protocol.ParamSet
import com.b570.pilot.protocol.ParamValues
import com.b570.pilot.protocol.Params
import com.b570.pilot.protocol.ProtocolException
import com.b570.pilot.protocol.SeqLoad
import com.b570.pilot.protocol.SeqPlay
import com.b570.pilot.protocol.SeqStep
import com.b570.pilot.protocol.SeqStop
import com.b570.pilot.protocol.StaGet
import com.b570.pilot.protocol.StaInfo
import com.b570.pilot.protocol.StaSet
import com.b570.pilot.protocol.StopReason
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.protocol.WifiFactory
import com.b570.pilot.protocol.WifiInfo
import com.b570.pilot.protocol.WifiSet
import com.b570.pilot.protocol.ZeroCal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * App 唯一的資料中心（Application 範圍單例，見 [B570Application]）。
 * 所有畫面透過 AppViewModel 使用；StateFlow 可在任何執行緒安全讀取。
 */
class CarRepository(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------ stores

    private val carsStore = SavedCarsStore(app)
    private val settingsStore = SettingsStore(app)
    private val sequencesStore = SequencesStore(app)
    private val hotspotPromptStore = HotspotPromptStore(app)

    /** 記住的車子（最近連線在前）。成功連上真車時自動更新。 */
    val savedCars: StateFlow<List<SavedCar>> = carsStore.cars
    /** 已回答過「帶出去玩：改用熱點模式」邀請的車（SSID）。 */
    val hotspotPromptAnswered: StateFlow<Set<String>> = hotspotPromptStore.answered
    val settings: StateFlow<AppSettings> = settingsStore.settings
    /** 使用者儲存的編排（最近修改在前）。內建範例見 [SequencePresets]。 */
    val sequences: StateFlow<List<SavedSequence>> = sequencesStore.sequences

    // ------------------------------------------------------------------ live state

    /** 遙測歷史（圖表用，零配置讀取）。 */
    val history = TelemetryHistory()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    /** 最新遙測（每筆都更新，約 50 Hz）。只在「小的葉節點」composable 收集。 */
    val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()

    private val _telemetrySlow = MutableStateFlow<Telemetry?>(null)
    /** 節流版遙測（最多 10 Hz），適合文字讀數與一般 UI。 */
    val telemetrySlow: StateFlow<Telemetry?> = _telemetrySlow.asStateFlow()

    private val _linkStats = MutableStateFlow(LinkStats())
    val linkStats: StateFlow<LinkStats> = _linkStats.asStateFlow()

    private val _carInfo = MutableStateFlow<Info?>(null)
    val carInfo: StateFlow<Info?> = _carInfo.asStateFlow()

    private val _espInfo = MutableStateFlow(EspInfo())
    val espInfo: StateFlow<EspInfo> = _espInfo.asStateFlow()

    private val _params = MutableStateFlow<Map<Int, Float>>(emptyMap())
    /** 車上目前的參數值（id → 值）。未連線或尚未讀到時為空 map，UI 以 ParamDef.default 顯示。 */
    val params: StateFlow<Map<Int, Float>> = _params.asStateFlow()

    private val _currentCar = MutableStateFlow<WifiCredentials?>(null)
    /** 目前（或最後一次）連線的車子。 */
    val currentCar: StateFlow<WifiCredentials?> = _currentCar.asStateFlow()

    private val _isDemo = MutableStateFlow(false)
    /** 目前是否為展示模式。 */
    val isDemo: StateFlow<Boolean> = _isDemo.asStateFlow()

    private val _loadedSequence = MutableStateFlow<List<SeqStep>>(emptyList())
    /** 最後一次成功載入到車上的編排步驟（對照 Telemetry.seqStep 顯示進度）。 */
    val loadedSequence: StateFlow<List<SeqStep>> = _loadedSequence.asStateFlow()

    private val _events = MutableSharedFlow<CarEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** 提示事件（車子 EVENT + 本地通知），由 shell 的 toast host 顯示；畫面也可自行收集。 */
    val events: SharedFlow<CarEvent> = _events.asSharedFlow()

    private val _recentEvents = MutableStateFlow<List<CarEvent>>(emptyList())
    /**
     * 最近 [EVENT_LOG_SIZE] 則事件（最新在前），從 App 啟動起保留（監測頁的事件紀錄）。
     * 除了 [events] 的提示，也記錄 shell 狀態條 / 連線頁顯示、不另跳提示的連線變化（訊號中斷、連線失敗、已中斷連線）。
     */
    val recentEvents: StateFlow<List<CarEvent>> = _recentEvents.asStateFlow()

    // ------------------------------------------------------------------ hotspot mode

    private val _linkMode = MutableStateFlow<LinkMode?>(null)
    /** 目前連線的路徑（直連 / 手機熱點）；沒有連線 = null。 */
    val linkMode: StateFlow<LinkMode?> = _linkMode.asStateFlow()

    private val _staInfo = MutableStateFlow<StaInfo?>(null)
    /** 車子的熱點模式狀態（STA_INFO）：連上時讀一次，熱點模式中每 3 s 更新；[requestStaInfo] 立即讀取。 */
    val staInfo: StateFlow<StaInfo?> = _staInfo.asStateFlow()

    private val _hotspotSupported = MutableStateFlow<Boolean?>(null)
    /** 車子的 Wi-Fi 模組支援熱點模式：null = 還不知道、false = ESP 韌體太舊（回 UNKNOWN_TYPE）。 */
    val hotspotSupported: StateFlow<Boolean?> = _hotspotSupported.asStateFlow()

    private val _hotspotSwitch = MutableStateFlow<HotspotSwitchStatus?>(null)
    /**
     * 切換到熱點模式的進度（[setHotspot] enable = true 之後）：重新開機中 → 請開啟手機熱點 / 在熱點上找車 → 連上（變回 null）
     * 或 FAILED（留著直到 [dismissHotspotSwitch] / 下一次連線）。null = 沒有在切換。
     */
    val hotspotSwitch: StateFlow<HotspotSwitchStatus?> = _hotspotSwitch.asStateFlow()
    @Volatile private var hotspotJob: Job? = null

    /**
     * 切換熱點模式的進度對話框正在畫面上：它自己會顯示每一步與完成說明，
     * 切換過程的提示（完成 / 已連線）就不再另外跳出（否則同一句話在對話框後面重複一次）。
     */
    @Volatile var switchProgressOnScreen: Boolean = false

    // ------------------------------------------------------------------ links

    private val listener = RepoListener()
    private val realLink: CarLink = CarConnection(app, listener)
    private val demoLink: CarLink = DemoTelemetrySource(listener)
    private val activeLink = MutableStateFlow(realLink)
    private val link: CarLink get() = activeLink.value
    private var reconnectJob: Job? = null

    // ------------------------------------------------------------------ quick connect

    private val nearbyCars = NearbyCars(app) { savedCars.value.mapTo(HashSet()) { it.ssid } }
    /** 附近的車：SSID → 訊號 dBm（記住的車 + 尚未配對的 `B570-` 熱點）。連線頁顯示期間呼叫 [startNearbyScan]。 */
    val nearby: StateFlow<Map<String, Int>> = nearbyCars.nearby

    private val _reconnect = MutableStateFlow<ReconnectStatus?>(null)
    /** 斷線後自動重連的進度；null = 沒有在重連。 */
    val reconnect: StateFlow<ReconnectStatus?> = _reconnect.asStateFlow()
    private var autoJob: Job? = null
    private val autoLaunchTried = AtomicBoolean(false)
    /** 目前這台車在這次連線流程中成功連上過（系統已記住同意，可安靜地自動重連）。 */
    @Volatile private var sessionWasUp = false

    // ================================================================== connection

    /**
     * 連線到真車（會中斷目前連線、取消自動重連）。**智慧連線**（[ConnectPlanner]）：記住的車是熱點模式、
     * 或手機熱點開著 → 先在手機熱點上找車（≤ 4 s），找不到再直連車子的 AP（有密碼時）。
     */
    fun connect(credentials: WifiCredentials) {
        cancelReconnect()
        stopHotspotSwitch()
        sessionWasUp = false
        startReal(requestFor(credentials))
    }

    /** 一律直連車子的 AP（剛掃 QR 時：OLED 顯示 QR = 車子正在直連模式）。 */
    fun connectDirect(credentials: WifiCredentials) {
        cancelReconnect()
        stopHotspotSwitch()
        sessionWasUp = false
        startReal(LinkRequest(credentials))
    }

    /** 連線到記住的車（= [connect]，依熱點模式 / 手機熱點決定先找車還是直連）。 */
    fun smartConnect(car: SavedCar) = connect(car.credentials)

    /** 這組帳密現在會怎麼連（[ConnectPlanner.plan]）。 */
    fun planFor(credentials: WifiCredentials): ConnectPlan =
        ConnectPlanner.plan(carsStore.find(credentials.ssid), credentials, isPhoneHotspotLikelyOn())

    private fun requestFor(credentials: WifiCredentials): LinkRequest =
        planFor(credentials).toRequest(credentials, carsStore.find(credentials.ssid)?.lastIp)

    /** 手機個人熱點看起來開著（網路介面判斷，約 1–5 ms）。 */
    fun isPhoneHotspotLikelyOn(): Boolean = runCatching { HotspotNet.isPhoneHotspotLikelyOn(app) }.getOrDefault(false)

    /**
     * 開啟 App 時自動連上最近的車（設定「開啟 App 自動連線」）。每個 App 行程只會嘗試一次；
     * 由連線頁在 Wi-Fi 權限已就緒時呼叫。回傳正在連線的車，沒有嘗試則回傳 null。
     */
    fun autoConnectOnLaunch(): SavedCar? {
        // 第一次呼叫就用掉：之後使用者主動中斷回到連線頁，不會又被自動連回去
        if (!autoLaunchTried.compareAndSet(false, true)) return null
        val s = settings.value
        if (!s.autoConnect || s.demoMode) return null
        if (_connectionState.value != ConnectionState.Idle) return null
        val car = savedCars.value.firstOrNull() ?: return null
        smartConnect(car)
        return car
    }

    /**
     * 這次啟動改由桌面捷徑 / 外部請求決定要做什麼（連某台車、掃描配對新車）：
     * 不再自動連最近的車（[autoConnectOnLaunch] 之後回傳 null）。
     */
    fun skipAutoConnectOnLaunch() {
        autoLaunchTried.set(true)
    }

    /** 停止自動重連（使用者在連線頁按「取消」時呼叫；[disconnect] 也會呼叫）。 */
    fun cancelReconnect() {
        autoJob?.cancel()
        autoJob = null
        reconnectNeedsApproval = false
        _reconnect.value = null
    }

    /**
     * 這一次自動重連嘗試期間 App 失去過視窗焦點：多半是系統「連線到裝置」同意視窗跳出來了（系統沒有記住同意）。
     * 這次沒連上就停止自動重連（[ReconnectPolicy.shouldRetry]），不要每次重試都再跳一次視窗。
     */
    @Volatile private var reconnectNeedsApproval = false

    /** MainActivity：視窗失去焦點。 */
    fun onWindowFocusLost() {
        if (_reconnect.value != null && _connectionState.value is ConnectionState.RequestingWifi) reconnectNeedsApproval = true
    }
    /** 開始偵測附近的車（連線頁顯示時）。 */
    fun startNearbyScan() = nearbyCars.start()

    /** 停止偵測（離開連線頁）。 */
    fun stopNearbyScan() = nearbyCars.stop()

    /** 重新掃描附近的車（下拉更新 / 重新整理按鈕）。 */
    fun refreshNearby() = nearbyCars.refresh()

    private fun startReal(request: LinkRequest) {
        reconnectJob?.cancel()
        // 先停掉舊連線再清狀態：舊連線收尾期間的遙測不會寫回剛清空的畫面
        if (link !== realLink) demoLink.stop() else realLink.stop()
        resetSession()
        _isDemo.value = false
        _currentCar.value = request.credentials
        activeLink.value = realLink
        realLink.start(request)
    }

    /** 進入展示模式（模擬車子，所有功能可操作）。 */
    fun connectDemo() {
        cancelReconnect()
        stopHotspotSwitch()
        reconnectJob?.cancel()
        realLink.stop()
        resetSession()
        _isDemo.value = true
        _currentCar.value = DemoTelemetrySource.DEMO_CREDS
        activeLink.value = demoLink
        demoLink.start(DemoTelemetrySource.DEMO_CREDS)
    }

    /** 以目前的車子重試連線（Failed 畫面的「重試」）。 */
    fun retry() {
        val c = _currentCar.value ?: return
        if (_isDemo.value) connectDemo() else connect(c)
    }

    /** 中斷連線（送 BYE、釋放 Wi-Fi），狀態回到 Idle。 */
    fun disconnect() {
        cancelReconnect()
        stopHotspotSwitch()
        sessionWasUp = false
        reconnectJob?.cancel()
        estopJob?.cancel()
        link.setControl(0, 0)
        link.stop()
    }

    // ================================================================== control

    /** 設定 50 Hz CTRL：車速 mm/s（正 = 前進）、轉向 ‰（-1000..1000，正 = 右轉）。不阻塞，可每幀呼叫。 */
    fun setControl(speedMmps: Int, turnPermille: Int) = link.setControl(speedMmps, turnPermille)

    /**
     * 以正規化輸入設定 CTRL：forward / turn ∈ -1..1。
     * speed = forward × gear.speedFactor × MAX_SPEED 參數；turn = turn × gear.turnFactor × 1000‰。
     */
    fun setControlNormalized(forward: Float, turn: Float, gear: Gear) {
        val maxSpeed = _params.value[Params.MAX_SPEED] ?: Params.byId(Params.MAX_SPEED)!!.default
        setControl(
            (forward.coerceIn(-1f, 1f) * gear.speedFactor * maxSpeed).roundToInt(),
            (turn.coerceIn(-1f, 1f) * gear.turnFactor * 1000f).roundToInt(),
        )
    }

    /**
     * 緊急停止：立刻歸零控制、中止還在重送 / 排隊的 START 與 SEQ_PLAY，並直接送出 ESTOP（不經請求佇列）。
     * 之後持續補送 ESTOP，直到遙測確認馬達已停、編排已停（訊號中斷期間也會持續，恢復後補送）；
     * 一直無法確認就跳危險提示。沒有連線（自動重連中）或確認前就斷線 → 記下來，連回同一台車時立刻補送。
     * 可在主執行緒呼叫。
     */
    fun emergencyStop() {
        setControl(0, 0)
        abortMotion()
        val l = link
        l.send(MsgType.MOTOR, Motor(MotorAction.ESTOP).encode())
        estopJob?.cancel()
        estopJob = null
        // 一律記下，遙測確認馬達已停才清掉：還沒確認就斷線（自動重連中、訊號中斷後失敗）→ 連回這台車時立刻補送
        _currentCar.value?.let { pendingEstop = PendingEstop(it.ssid, SystemClock.elapsedRealtime()) }
        if (!_connectionState.value.isSession) {
            // 斷線 / 自動重連中按的：現在送不到（按鈕照樣看得到、按得到），不能讓使用者以為車子已經停了
            notice("車子目前沒有連線，緊急停止會在連回時立刻送出；必要時請直接關閉車子電源", Severity.DANGER)
            return
        }
        estopJob = scope.launch { confirmEstop(l) }
    }

    @Volatile private var estopJob: Job? = null

    /** 還沒被遙測確認的緊急停止（哪台車、何時按的）。 */
    private class PendingEstop(val ssid: String, val atMs: Long)
    @Volatile private var pendingEstop: PendingEstop? = null

    /**
     * 連上（新的連線或訊號恢復）時補送還沒確認的緊急停止：同一台車；自動重連 / 訊號恢復不限時間，
     * 其他（使用者手動重新連線）在按下後 [PENDING_ESTOP_MS] 內。
     */
    private fun deliverPendingEstop(viaReconnect: Boolean) {
        val p = pendingEstop ?: return
        if (p.ssid != _currentCar.value?.ssid) return
        if (estopJob?.isActive == true) return
        if (!viaReconnect && SystemClock.elapsedRealtime() - p.atMs > PENDING_ESTOP_MS) {
            pendingEstop = null
            return
        }
        abortMotion()
        val l = link
        l.send(MsgType.MOTOR, Motor(MotorAction.ESTOP).encode())
        estopJob = scope.launch { confirmEstop(l) }
        notice("已連回，補送緊急停止", Severity.WARNING)
    }

    /** 遙測確認目前這台車已停：清掉它的待補送緊急停止。 */
    private fun clearPendingEstopForCurrentCar() {
        val p = pendingEstop ?: return
        if (p.ssid == _currentCar.value?.ssid) pendingEstop = null
    }

    /** 每次 [abortMotion] +1：在這之前發起的 START / SEQ_PLAY 不再送出（見 [ackRequest]）。 */
    @Volatile private var motionAbortEpoch = 0

    /** 停止類指令：讓先前發起、可能還在重送或排隊的 START / SEQ_PLAY 全部失效。 */
    private fun abortMotion() {
        motionAbortEpoch++
        link.abortMotionRequests()
    }

    private suspend fun confirmEstop(l: CarLink) {
        val payload = Motor(MotorAction.ESTOP).encode()
        // 連發幾個：單一 datagram 遺失時不必等下一輪補送
        repeat(ESTOP_BURST - 1) {
            delay(ESTOP_BURST_GAP_MS)
            l.send(MsgType.MOTOR, payload)
        }
        val start = SystemClock.elapsedRealtime()
        val fromFrame = telemCount
        var stoppedSince = -1L
        var warned = false
        while (true) {
            if (activeLink.value !== l || !_connectionState.value.isSession) return
            val now = SystemClock.elapsedRealtime()
            val t = _telemetry.value
            // 只採信送出 ESTOP 之後才收到的遙測
            if (telemCount > fromFrame && t != null && !t.motorsOn && !t.seqPlaying) {
                if (stoppedSince < 0) stoppedSince = now
                // 多看一下：確認沒有在途的舊 START 又把馬達打開
                if (now - stoppedSince >= ESTOP_HOLD_MS) {
                    clearPendingEstopForCurrentCar()
                    return
                }
            } else {
                stoppedSince = -1L
                if (now - start >= ESTOP_GIVE_UP_MS) return
                if (!warned && now - start >= ESTOP_WARN_MS) {
                    warned = true
                    notice("緊急停止還沒確認，必要時請直接關閉車子電源", Severity.DANGER)
                }
                l.send(MsgType.MOTOR, payload)
            }
            delay(ESTOP_RESEND_MS)
        }
    }

    // ================================================================== commands

    /**
     * 馬達 啟動 / 停止 / 緊急停止。notify = 失敗時自動顯示中文提示。
     * 停止 / 緊急停止會先讓還在重送或排隊的 START / SEQ_PLAY 失效；
     * 使用者主動按啟動則結束緊急停止的補送。被停止取消的 START 回傳 [AckStatus.CANCELLED]（不提示）。
     */
    suspend fun motor(action: MotorAction, notify: Boolean = true): AckStatus {
        if (action == MotorAction.START) {
            // 使用者主動按啟動：結束緊急停止的補送（含斷線前還沒確認、等著連回補送的那一個）
            estopJob?.cancel()
            clearPendingEstopForCurrentCar()
        } else {
            abortMotion()
        }
        return ackRequest(MsgType.MOTOR, Motor(action).encode(), notify)
    }

    /** 以目前傾角校正機械零點（馬達需停止）。成功後 params[ZERO_ANGLE] 會更新。 */
    suspend fun zeroCal(notify: Boolean = true): AckStatus = ackRequest(MsgType.ZERO_CAL, ZeroCal.encode(), notify)

    /**
     * 設定參數（自動分批）。回傳車子**實際套用**（夾限後）的值，並更新 [params]。
     * 失敗（逾時 / 未連線）回傳 null。
     */
    suspend fun setParams(values: Map<Int, Float>, notify: Boolean = true): Map<Int, Float>? {
        if (values.isEmpty()) return emptyMap()
        if (!canCommand()) return failNull(notify, AckStatus.OFFLINE)
        val applied = LinkedHashMap<Int, Float>()
        for (chunk in ParamSet.chunked(values)) {
            val ids = chunk.values.map { it.first }.toSet()
            val f = link.request(MsgType.PARAM_SET, chunk.encode()) { fr ->
                fr.type == MsgType.PARAM_VALUES && decodeIds(fr.payload).containsAll(ids)
            } ?: return failNull(notify, AckStatus.TIMEOUT)
            val pv = decodeParams(f) ?: return failNull(notify, AckStatus.TIMEOUT)
            ids.forEach { id -> pv[id]?.let { applied[id] = it } }
        }
        return applied
    }

    /** 設定單一參數，回傳實際套用值（失敗 null）。 */
    suspend fun setParam(id: Int, value: Float, notify: Boolean = true): Float? = setParams(mapOf(id to value), notify)?.get(id)

    /** 寫入 Flash（馬達需停止）。成功時車子會另送「參數已儲存」事件。 */
    suspend fun saveParams(notify: Boolean = true): AckStatus = ackRequest(MsgType.PARAM_SAVE, ParamSave.encode(), notify)

    /** RAM 參數恢復預設（不自動存檔），回傳全部參數。 */
    suspend fun resetParams(notify: Boolean = true): Map<Int, Float>? {
        if (!canCommand()) return failNull(notify, AckStatus.OFFLINE)
        val f = link.request(MsgType.PARAM_DEFAULTS, ParamDefaults.encode()) { isAllParams(it) }
            ?: return failNull(notify, AckStatus.TIMEOUT)
        return decodeParams(f)
    }

    /** 重新讀取全部參數。 */
    suspend fun refreshParams(notify: Boolean = true): Map<Int, Float>? {
        if (!canCommand()) return failNull(notify, AckStatus.OFFLINE)
        val f = link.request(MsgType.PARAM_GET, ParamGet().encode()) { isAllParams(it) }
            ?: return failNull(notify, AckStatus.TIMEOUT)
        return decodeParams(f)
    }

    /**
     * 載入編排（自動切成 ≤ 24 步一批，每批等 ACK）後播放。loops = 0 無限循環。
     * 需馬達已啟動，否則回 NOT_READY。
     * 播放中換編排：§3.2 播放中 SEQ_LOAD 會回 BUSY，所以先送 SEQ_STOP（畫面不用自己停）；
     * 這個自己造成的「編排已停止」事件不提示。
     */
    suspend fun loadAndPlaySequence(steps: List<SeqStep>, loops: Int, notify: Boolean = true): AckStatus {
        if (steps.isEmpty() || steps.size > SeqStep.MAX_STEPS) {
            if (notify) notice(if (steps.isEmpty()) "編排沒有任何步驟" else "編排最多 ${SeqStep.MAX_STEPS} 步", Severity.WARNING)
            return AckStatus.BAD_ARG
        }
        val clamped = steps.map { it.clamped() }
        val epoch = motionAbortEpoch
        if (_telemetry.value?.seqPlaying == true) stopForReplace()
        for ((i, chunk) in SeqLoad.chunk(clamped).withIndex()) {
            var st = ackRequest(MsgType.SEQ_LOAD, chunk.encode(), notify = false)
            if (st == AckStatus.BUSY && i == 0) {
                // 遙測還沒反映「播放中」（剛開始播放）：停止後重試一次
                stopForReplace()
                st = ackRequest(MsgType.SEQ_LOAD, chunk.encode(), notify = false)
            }
            if (!st.isOk) {
                if (notify) notifyAckFailure(MsgType.SEQ_LOAD, st)
                return st
            }
        }
        _loadedSequence.value = clamped
        // 上傳期間按了停止 / 緊急停止：不要再送 PLAY
        return ackRequest(MsgType.SEQ_PLAY, SeqPlay(loops).encode(), notify, motionEpoch = epoch)
    }

    /** 換編排前停止目前播放；車子因此回的那一個 SEQ_ABORTED(4) 不提示（只吞一次，最多等 1 秒）。 */
    private suspend fun stopForReplace() {
        quietSeqAbortUntil = SystemClock.elapsedRealtime() + QUIET_SEQ_ABORT_MS
        ackRequest(MsgType.SEQ_STOP, SeqStop.encode(), notify = false)
    }

    @Volatile private var quietSeqAbortUntil = 0L

    suspend fun stopSequence(notify: Boolean = true): AckStatus = ackRequest(MsgType.SEQ_STOP, SeqStop.encode(), notify)

    /**
     * 修改車子 Wi-Fi（ESP 回覆後約 0.5 s 重開機）。成功時更新記住的車子，並在數秒後自動用新設定重新連線。
     * channel：0 = 自動，1..13。
     */
    suspend fun setWifi(ssid: String, password: String, channel: Int = 0, notify: Boolean = true): AckStatus {
        val err = WifiCredentials.validateSsid(ssid) ?: WifiCredentials.validatePassword(password)
        if (err != null) {
            if (notify) notice(err, Severity.WARNING)
            return AckStatus.BAD_ARG
        }
        val st = ackRequest(MsgType.WIFI_SET, WifiSet(ssid, password, channel).encode(), notify, ackType = MsgType.ESP_ACK)
        if (!st.isOk) return st
        val newCreds = WifiCredentials(ssid, password)
        if (_isDemo.value) {
            _currentCar.value = newCreds
            notice("Wi-Fi 設定已更新", Severity.SUCCESS)
            return st
        }
        val old = _currentCar.value
        if (old != null) carsStore.replaceSsid(old.ssid, ssid, password) else carsStore.upsert(ssid, password)
        notice("Wi-Fi 已更新，車子重新開機中…", Severity.SUCCESS)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(600)
            link.stop()
            delay(3500)
            connect(newCreds)
        }
        return st
    }

    /** ESP 重新產生 SSID / 密碼並重開機。之後需重新掃描車上的 QR Code。 */
    suspend fun factoryWifi(notify: Boolean = true): AckStatus {
        val st = ackRequest(MsgType.WIFI_FACTORY, WifiFactory.encode(), notify, ackType = MsgType.ESP_ACK)
        if (st.isOk && !_isDemo.value) {
            _currentCar.value?.let { carsStore.forget(it.ssid) }
            disconnect()
            notice("Wi-Fi 已重設，請重新掃描車上的 QR Code", Severity.WARNING)
        }
        return st
    }

    // ================================================================== hotspot mode

    /**
     * 讀取車子的熱點模式狀態（STA_GET → STA_INFO），同時更新 [staInfo]。
     * 舊 ESP 韌體（回 UNKNOWN_TYPE）→ [hotspotSupported] = false、回傳 null。逾時 / 未連線回傳 null。
     */
    suspend fun requestStaInfo(notify: Boolean = false): StaInfo? {
        if (!canCommand()) {
            if (notify) notice(CarEvent.ackFailureZh(MsgType.STA_GET, AckStatus.OFFLINE), Severity.WARNING)
            return null
        }
        val f = link.request(MsgType.STA_GET, StaGet.encode()) { fr ->
            fr.type == MsgType.STA_INFO ||
                (fr.type == MsgType.ESP_ACK && fr.payload.size >= 2 && (fr.payload[0].toInt() and 0xFF) == MsgType.STA_GET)
        }
        if (f == null) {
            if (notify) notice(CarEvent.ackFailureZh(MsgType.STA_GET, AckStatus.TIMEOUT), Severity.WARNING)
            return null
        }
        if (f.type == MsgType.ESP_ACK) {
            _hotspotSupported.value = false
            if (notify) notice(HOTSPOT_UNSUPPORTED_ZH, Severity.WARNING)
            return null
        }
        return try { StaInfo.decode(f.payload) } catch (e: ProtocolException) { null }
    }

    /**
     * STA_INFO（回覆或熱點模式中每 3 s）：更新 [staInfo]，並把熱點設定同步到記住的車。
     * 車子回報熱點模式（例如從「熱點上的車」配對、或這版之前就設定好的車）→ 「改用熱點模式」邀請記成已回答，
     * 之後「改回直連」不會又被邀請（已記過不會再寫檔）。
     */
    private fun onStaInfo(info: StaInfo) {
        _staInfo.value = info
        _hotspotSupported.value = true
        if (_isDemo.value) return
        val car = _currentCar.value ?: return
        if (info.enabled) hotspotPromptStore.markAnswered(car.ssid)
        carsStore.updateHotspot(
            car.ssid,
            hotspotMode = info.enabled,
            hotspotSsid = info.ssid.takeIf { it.isNotEmpty() },
            lastIp = info.carIp,
        )
    }

    /**
     * 熱點模式開 / 關（§3.4 STA_SET → ESP_ACK，ESP 約 0.5 s 後重新開機）。
     * - 開啟：[ssid] / [password] = **手機個人熱點**的名稱 / 密碼（先用 [HotspotConfig] 檢查；開放熱點密碼留空）。
     *   成功後記住的車改成熱點模式，並開始切換流程（[hotspotSwitch]）：車子重開機 → 等使用者打開手機熱點 →
     *   在熱點上找車（最多 60 s）→ 連上。手機熱點必須是 2.4 GHz、WPA2（§3.6）。
     * - 關閉（enable = false，ssid 留空 = 車上保留熱點帳密）：車子重開機回到直連 AP，約 4 s 後自動直連
     *   （這台車沒有 AP 密碼時改提示掃描 QR）。手機熱點開著時部分手機無法同時連車子的 Wi-Fi。
     * 失敗會自動提示（notify），回傳 ESP_ACK 狀態（舊 ESP 韌體 = UNKNOWN_TYPE）。
     */
    suspend fun setHotspot(enable: Boolean, ssid: String = "", password: String = "", notify: Boolean = true): AckStatus {
        if (enable) {
            val err = HotspotConfig.validateSsid(ssid) ?: HotspotConfig.validatePassword(password)
            if (err != null) {
                if (notify) notice(err, Severity.WARNING)
                return AckStatus.BAD_ARG
            }
        } else if (ssid.isNotEmpty()) {
            HotspotConfig.validateSsid(ssid)?.let { if (notify) notice(it, Severity.WARNING); return AckStatus.BAD_ARG }
        }
        val payload = StaSet(enable, ssid, if (enable || ssid.isNotEmpty()) password else "").encode()
        val st = ackRequest(MsgType.STA_SET, payload, notify, ackType = MsgType.ESP_ACK)
        if (st == AckStatus.UNKNOWN_TYPE) _hotspotSupported.value = false
        if (!st.isOk) return st
        val car = _currentCar.value ?: return st
        if (_isDemo.value) {
            runDemoHotspotSwitch(enable, ssid)
            return st
        }
        carsStore.updateHotspot(car.ssid, hotspotMode = enable, hotspotSsid = ssid.takeIf { it.isNotEmpty() })
        // 使用者自己選過連線方式（開或關都算）：shell 不再邀請「改用熱點模式」
        hotspotPromptStore.markAnswered(car.ssid)
        _staInfo.value = null
        reconnectJob?.cancel()
        stopHotspotSwitch()
        if (enable) {
            // notify = false：呼叫端（設定熱點的對話框）自己顯示進度
            if (notify) notice("車子重新開機中，接著會加入手機熱點「$ssid」", Severity.SUCCESS)
            hotspotJob = scope.launch { runHotspotSwitch(car, ssid) }
        } else {
            // 車子回到直連 AP：用 AP 密碼直連（從熱點上配對、沒掃過 QR 的車沒有密碼 → 請使用者掃 QR）
            val direct = car.takeIf { it.isValid } ?: carsStore.find(car.ssid)?.credentials?.takeIf { it.isValid }
            notice(if (direct != null) "已關閉熱點模式，車子重新開機中…" else "已關閉熱點模式，請掃描車上的 QR Code 重新連線", Severity.SUCCESS)
            reconnectJob = scope.launch {
                delay(600)
                if (direct == null) {
                    disconnect()
                    return@launch
                }
                link.stop()
                delay(3500)
                connectDirect(direct)
            }
        }
        return st
    }

    /**
     * 切換到熱點模式：車子重開機（0.5 s 後）→ 加入手機熱點（最多 12 s；熱點沒開就等使用者打開）→
     * 在熱點上持續找車（一次長的 Discovering，不會反覆失敗 / 跳提示）→ 連上就清掉 [hotspotSwitch]。
     */
    private suspend fun runHotspotSwitch(creds: WifiCredentials, hotspotSsid: String) {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + HOTSPOT_SWITCH_MS
        _hotspotSwitch.value = HotspotSwitchStatus(hotspotSsid, HotspotSwitchPhase.RESTARTING, isPhoneHotspotLikelyOn(), start, deadline)
        delay(600) // ESP 回 ACK 後 500 ms 重新開機
        cancelReconnect()
        sessionWasUp = false
        realLink.stop()
        startReal(
            LinkRequest(
                credentials = creds,
                discover = true,
                discoveryTimeoutMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(LinkRequest.DEFAULT_DISCOVERY_MS),
                direct = false,
                lastIp = null,
                hotspotExpected = true,
            ),
        )
        while (true) {
            delay(500)
            val st = realLink.state.value
            if (activeLink.value !== realLink) return
            when {
                st is ConnectionState.Connected -> {
                    _hotspotSwitch.value = null
                    // 完成：順便說以後帶出去怎麼連（先開熱點、再開車子；手機 Wi-Fi 可以關掉）
                    if (!switchProgressOnScreen) notice(HOTSPOT_SWITCH_DONE_ZH, Severity.SUCCESS)
                    return
                }
                st is ConnectionState.Failed -> {
                    val on = withContext(Dispatchers.IO) { isPhoneHotspotLikelyOn() }
                    _hotspotSwitch.update { it?.copy(phase = HotspotSwitchPhase.FAILED, phoneHotspotOn = on) }
                    return
                }
                st == ConnectionState.Idle -> {
                    _hotspotSwitch.value = null
                    return
                }
            }
            val on = withContext(Dispatchers.IO) { isPhoneHotspotLikelyOn() }
            val now = SystemClock.elapsedRealtime()
            val phase = when {
                now - start < HOTSPOT_RESTART_MS -> HotspotSwitchPhase.RESTARTING
                !on -> HotspotSwitchPhase.WAITING_HOTSPOT
                else -> HotspotSwitchPhase.SEARCHING
            }
            _hotspotSwitch.update { it?.copy(phase = phase, phoneHotspotOn = on) }
        }
    }

    /** 展示模式：不重開機，只模擬切換提示（重新開機中 → 尋找中 → 連上）。 */
    private fun runDemoHotspotSwitch(enable: Boolean, ssid: String) {
        stopHotspotSwitch()
        scope.launch { requestStaInfo(notify = false) }
        if (!enable) {
            notice("已關閉熱點模式（展示）", Severity.SUCCESS)
            return
        }
        hotspotJob = scope.launch {
            val start = SystemClock.elapsedRealtime()
            _hotspotSwitch.value = HotspotSwitchStatus(ssid, HotspotSwitchPhase.RESTARTING, true, start, start + HOTSPOT_SWITCH_MS)
            delay(DEMO_SWITCH_STEP_MS)
            _hotspotSwitch.update { it?.copy(phase = HotspotSwitchPhase.SEARCHING) }
            delay(DEMO_SWITCH_STEP_MS)
            _hotspotSwitch.value = null
            if (!switchProgressOnScreen) notice("$HOTSPOT_SWITCH_DONE_ZH（展示）", Severity.SUCCESS)
        }
    }

    private fun stopHotspotSwitch() {
        hotspotJob?.cancel()
        hotspotJob = null
        _hotspotSwitch.value = null
    }

    /** 取消切換到熱點模式（停止找車並中斷 → Idle）。車子仍是熱點模式，之後連線會先在熱點上找。 */
    fun cancelHotspotSwitch() {
        if (_hotspotSwitch.value == null && hotspotJob == null) return
        disconnect()
    }

    /** 關掉 FAILED 的切換提示（不影響連線）。 */
    fun dismissHotspotSwitch() {
        if (_hotspotSwitch.value?.phase == HotspotSwitchPhase.FAILED) _hotspotSwitch.value = null
    }

    /**
     * 掃描手機網路（熱點）上的車（DISCOVER 廣播 [timeoutMs]，不影響目前連線）。用來配對「已經是熱點模式」的車
     * （OLED 顯示熱點資訊、沒有 QR）。展示模式回傳展示車。
     */
    suspend fun discoverHotspotCars(timeoutMs: Long = 2_000L): List<DiscoveredCar> {
        // 只有展示車還在跑時才問展示車：離開展示模式後 isDemo 仍為 true（「重試」要能回到展示），但連線頁要找的是真車
        if (isDemoRunning()) {
            delay(minOf(timeoutMs, 600L))
            val f = demoLink.request(MsgType.DISCOVER, Discover(1L).encode()) { it.type == MsgType.DISCOVER_ACK } ?: return emptyList()
            val a = try { DiscoverAck.decode(f.payload) } catch (e: ProtocolException) { return emptyList() }
            return listOf(DiscoveredCar(a.ssid, a.ip, a.chipIdHex, a.isHotspot, a.busy))
        }
        val ips = savedCars.value.mapNotNull { it.lastIp }
        return withContext(Dispatchers.IO) { HotspotNet.scanCars(app, timeoutMs, ips) }
    }

    /**
     * 連線到 [discoverHotspotCars] 找到的車：還沒記住就記下來（沒有 AP 密碼 → 只能用熱點連，之後掃 QR 會補上密碼），
     * 然後在熱點上連線（有密碼時找不到才直連）。
     */
    fun connectDiscovered(car: DiscoveredCar) {
        val saved = carsStore.find(car.ssid)
        val password = saved?.password.orEmpty()
        carsStore.updateHotspot(car.ssid, hotspotMode = if (car.viaHotspot) true else null, lastIp = car.ip, password = password)
        val creds = WifiCredentials(car.ssid, password)
        cancelReconnect()
        stopHotspotSwitch()
        sessionWasUp = false
        startReal(
            LinkRequest(
                credentials = creds,
                discover = true,
                discoveryTimeoutMs = ConnectPlanner.HOTSPOT_DISCOVERY_MS,
                direct = creds.isValid,
                lastIp = car.ip,
                hotspotExpected = true,
            ),
        )
    }

    /** 清除遙測歷史（圖表歸零）。 */
    fun clearHistory() = history.clear()

    /** 清除事件紀錄（[recentEvents]）。 */
    fun clearEventLog() {
        _recentEvents.value = emptyList()
    }

    // ================================================================== stores API

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    /** 新增 / 更新記住的車子（例如從 QR 或手動輸入）。 */
    fun saveCar(credentials: WifiCredentials, nickname: String? = null) =
        carsStore.upsert(credentials.ssid, credentials.password, nickname)

    fun renameCar(ssid: String, nickname: String) = carsStore.rename(ssid, nickname)
    fun forgetCar(ssid: String) {
        carsStore.forget(ssid)
        hotspotPromptStore.forget(ssid)
    }

    /** 記下這台車已回答「改用熱點模式」邀請（之後不再問）。 */
    fun markHotspotPromptAnswered(ssid: String) = hotspotPromptStore.markAnswered(ssid)

    /** 儲存編排（id 空白時自動產生），回傳存入的版本。 */
    fun saveSequence(seq: SavedSequence): SavedSequence =
        sequencesStore.save(if (seq.id.isBlank()) seq.copy(id = sequencesStore.newId()) else seq)

    fun deleteSequence(id: String) = sequencesStore.delete(id)
    fun newSequenceId(): String = sequencesStore.newId()

    /** 顯示一則本地提示（toast）。 */
    fun notice(messageZh: String, severity: Severity = Severity.INFO) = emitEvent(CarEvent.notice(messageZh, severity))

    // ================================================================== internals

    private fun canCommand(): Boolean = _connectionState.value.isSession

    /** 展示車正在跑（展示模式的連線還沒中斷）。[isDemo] 在中斷後仍保留 true，不能單獨用來判斷。 */
    private fun isDemoRunning(): Boolean =
        _isDemo.value && activeLink.value === demoLink && demoLink.state.value != ConnectionState.Idle

    /**
     * @param motionEpoch 呼叫端發起操作當下的 [motionAbortEpoch]：START / SEQ_PLAY 若在那之後被停止取消，
     *   就不送出並回傳 [AckStatus.CANCELLED]。
     */
    private suspend fun ackRequest(
        type: Int,
        payload: ByteArray,
        notify: Boolean,
        ackType: Int = MsgType.ACK,
        motionEpoch: Int = motionAbortEpoch,
    ): AckStatus {
        if (!canCommand()) {
            if (notify) notice(CarEvent.ackFailureZh(type, AckStatus.OFFLINE), Severity.WARNING)
            return AckStatus.OFFLINE
        }
        val motion = isMotionCommand(type, payload)
        if (motion && motionAbortEpoch != motionEpoch) return AckStatus.CANCELLED
        val f = link.request(type, payload) { fr ->
            fr.type == ackType && fr.payload.size >= 2 && (fr.payload[0].toInt() and 0xFF) == type
        }
        val st = when {
            f != null -> AckStatus.of(f.payload[1].toInt() and 0xFF)
            motion && motionAbortEpoch != motionEpoch -> AckStatus.CANCELLED
            else -> AckStatus.TIMEOUT
        }
        if (notify && !st.isOk && st != AckStatus.CANCELLED) {
            if (type == MsgType.MOTOR && (st == AckStatus.NOT_READY || st == AckStatus.BUSY)) {
                // 比照韌體 can_start()：電壓未就緒 / 低電壓 / 傾斜，用最新遙測說明真正原因
                val lowBatV = _params.value[Params.LOWBAT_V] ?: Params.byId(Params.LOWBAT_V)!!.default
                notice(CarEvent.motorStartRefusedZh(_telemetry.value, lowBatV), Severity.DANGER)
            } else {
                notifyAckFailure(type, st)
            }
        }
        return st
    }

    private fun notifyAckFailure(type: Int, st: AckStatus) =
        notice(CarEvent.ackFailureZh(type, st), if (st == AckStatus.TIMEOUT) Severity.WARNING else Severity.DANGER)

    private fun failNull(notify: Boolean, st: AckStatus): Nothing? {
        if (notify) notice(CarEvent.ackFailureZh(0, st), Severity.WARNING)
        return null
    }

    private fun isAllParams(f: Frame) =
        f.type == MsgType.PARAM_VALUES && f.payload.isNotEmpty() && (f.payload[0].toInt() and 0xFF) > 1

    private fun decodeIds(p: ByteArray): Set<Int> {
        if (p.isEmpty()) return emptySet()
        val n = p[0].toInt() and 0xFF
        val s = HashSet<Int>(n * 2)
        for (i in 0 until n) { val o = 1 + i * 5; if (o < p.size) s += p[o].toInt() and 0xFF }
        return s
    }

    private fun decodeParams(f: Frame): Map<Int, Float>? =
        try { ParamValues.decode(f.payload).values } catch (e: ProtocolException) { null }

    private fun resetSession() {
        estopJob?.cancel()
        resyncJob?.cancel()
        lastStopReason = null
        _telemetry.value = null
        _telemetrySlow.value = null
        _carInfo.value = null
        _espInfo.value = EspInfo()
        _params.value = emptyMap()
        _loadedSequence.value = emptyList()
        _staInfo.value = null
        _hotspotSupported.value = null
        _linkMode.value = null
        history.clear()
        synchronized(statsLock) {
            lastTick = -1; winReceived = 0; winExpected = 0; prevReceived = 0; prevExpected = 0
            rttEma = null; espRttEma = null; rssi = null; crcErrors = 0; rxFrames = 0
            latency.reset(); uplink.reset(); dualWifi = false
        }
        _linkStats.value = LinkStats()
    }

    private fun onLinkState(st: ConnectionState) {
        val prev = _connectionState.value
        _connectionState.value = st
        when (st) {
            is ConnectionState.Connected -> {
                st.info?.let { _carInfo.value = it }
                if (!_isDemo.value) sessionWasUp = true
                // 斷線前 / 重連期間按的緊急停止還沒被確認 → 先補送（在任何其他指令之前）
                if (prev !is ConnectionState.Connected) {
                    deliverPendingEstop(viaReconnect = _reconnect.value != null || prev is ConnectionState.LinkLost)
                }
                if (_reconnect.value != null) {
                    cancelReconnect()
                    notice("已自動重新連線", Severity.SUCCESS)
                }
                if (prev is ConnectionState.LinkLost) {
                    notice("訊號已恢復", Severity.SUCCESS)
                    // 失聯期間主控板可能重開機（參數回到 Flash 的值、演算法切換、編排清空）：重新讀取
                    requestResync(rebooted = false)
                } else if (prev !is ConnectionState.Connected) {
                    val c = _currentCar.value
                    if (c != null && !_isDemo.value) carsStore.upsert(c.ssid, c.password, lastConnected = System.currentTimeMillis())
                    // 熱點模式設定（設定頁顯示、記住的車同步）：讀一次；舊 ESP 韌體回 UNKNOWN_TYPE → hotspotSupported = false
                    val l = link
                    scope.launch {
                        delay(STA_INFO_AFTER_CONNECT_MS)
                        if (activeLink.value === l && canCommand()) requestStaInfo(notify = false)
                    }
                    val name = c?.let { carsStore.find(it.ssid)?.displayName ?: it.ssid } ?: "車子"
                    // 熱點切換的進度對話框會顯示「已透過手機熱點連上車子」
                    if (!switchProgressOnScreen) notice(if (_isDemo.value) "展示模式：已連上模擬車" else "已連線 $name", Severity.SUCCESS)
                    if (st.info == null && !_isDemo.value) notice("主控板沒有回應，部分功能無法使用", Severity.WARNING)
                }
            }
            is ConnectionState.Failed, ConnectionState.Idle -> {
                _telemetry.value = null
                _telemetrySlow.value = null
                _linkMode.value = null
                synchronized(statsLock) { dualWifi = false }
                if (st is ConnectionState.Failed) scheduleReconnect(st)
            }
            else -> Unit
        }
        // 只記錄、不跳提示（shell 狀態條會顯示訊號中斷，連線頁會顯示失敗原因）
        when {
            st is ConnectionState.LinkLost && prev !is ConnectionState.LinkLost ->
                logEvent(CarEvent.notice("訊號中斷，正在重新連線", Severity.WARNING))
            st is ConnectionState.Failed && prev !is ConnectionState.Failed ->
                logEvent(CarEvent.notice(st.reasonZh, Severity.DANGER))
            st is ConnectionState.Idle && prev.isSession ->
                logEvent(CarEvent.notice("已中斷連線", Severity.INFO))
        }
    }

    /**
     * 連上過的車子意外斷線（關機、走遠、ESP 重開）→ 依 [ReconnectPolicy] 安靜地自動重連。
     * 使用者主動中斷（Idle）、Wi-Fi 關閉、不可重試的錯誤、展示模式都不重連。
     */
    private fun scheduleReconnect(failed: ConnectionState.Failed) {
        val creds = _currentCar.value ?: return
        if (_isDemo.value || !sessionWasUp || !settings.value.autoReconnect) return
        if (hotspotJob?.isActive == true) return // 切換熱點模式中：由切換流程自己處理
        val approvalShown = reconnectNeedsApproval && _reconnect.value != null
        if (!ReconnectPolicy.shouldRetry(failed, approvalShown)) {
            cancelReconnect()
            if (approvalShown) notice("自動重連需要在系統視窗按「連線」，已停止自動重連，請按「重試」", Severity.WARNING)
            return
        }
        val attempt = (_reconnect.value?.attempt ?: 0) + 1
        val wait = ReconnectPolicy.delayBefore(attempt)
        if (wait == null) {
            cancelReconnect()
            logEvent(CarEvent.notice("自動重連失敗，請確認車子已開機後再試", Severity.WARNING))
            return
        }
        _reconnect.value = ReconnectStatus(creds.ssid, attempt, ReconnectPolicy.maxAttempts, SystemClock.elapsedRealtime() + wait)
        autoJob?.cancel()
        autoJob = scope.launch {
            delay(wait)
            if (_connectionState.value !is ConnectionState.Failed || _currentCar.value != creds) return@launch
            _reconnect.value = _reconnect.value?.copy(nextAtMs = 0L)
            reconnectNeedsApproval = false
            // 熱點模式的車：重新在熱點上找（車子 IP 可能變了），找不到再直連
            startReal(requestFor(creds))
        }
    }

    private fun logEvent(e: CarEvent) {
        _recentEvents.update { old ->
            buildList(minOf(old.size + 1, EVENT_LOG_SIZE)) {
                add(e)
                for (x in old) { if (size >= EVENT_LOG_SIZE) break; add(x) }
            }
        }
    }

    private fun emitEvent(e: CarEvent) {
        val now = SystemClock.elapsedRealtime()
        synchronized(eventLock) {
            // 同一句話 1.5 秒內只顯示一次（例如 ACK 失敗 + 車子事件）
            if (e.messageZh == lastEventText && now - lastEventAt < 1500) return
            lastEventText = e.messageZh
            lastEventAt = now
        }
        logEvent(e)
        _events.tryEmit(e)
    }

    private val eventLock = Any()
    private var lastEventText = ""
    private var lastEventAt = 0L

    // ------------------------------------------------------------------ resync

    @Volatile private var resyncJob: Job? = null
    @Volatile private var lastStopReason: StopReason? = null
    /** 收到的遙測筆數（緊急停止只採信送出之後的遙測）。只在接收執行緒寫入。 */
    @Volatile private var telemCount = 0L

    /**
     * 主控板重開機（EVENT BOOT / 遙測 stop_reason 回到 BOOT）或訊號恢復：重新讀 INFO 與全部參數，
     * 取代 App 手上的舊值（未存檔的即時調整已隨重開機消失、ANGLE_ALGO 存檔重開後才生效、MAX_SPEED 影響搖桿比例）。
     * [rebooted] 時車上的編排也已清空。
     */
    private fun requestResync(rebooted: Boolean) {
        if (rebooted) _loadedSequence.value = emptyList()
        if (resyncJob?.isActive == true) return
        val l = link
        // 回覆由 RepoListener 寫入 carInfo / params（全部 17 項 = 整份取代）；連線層也會記下新的 INFO
        resyncJob = scope.launch {
            if (!canCommand()) return@launch
            l.request(MsgType.INFO_GET, InfoGet.encode()) { it.type == MsgType.INFO }
            l.request(MsgType.PARAM_GET, ParamGet().encode()) { isAllParams(it) }
        }
    }

    /**
     * EVENT ZERO_CAL（arg = 新零點 × 100，韌體截斷）：車上按鍵校正時車子不會送 PARAM_VALUES，
     * App 校正時 PARAM_VALUES(6) 也可能遺失 → 與手上的值不一致就先更新，再讀一次精確值。
     */
    private fun onZeroCalEvent(zero: Float) {
        val cur = _params.value[Params.ZERO_ANGLE]
        if (cur != null && abs(cur - zero) < 0.01f) return
        _params.update { it + (Params.ZERO_ANGLE to zero) }
        val l = link
        scope.launch {
            l.request(MsgType.PARAM_GET, ParamGet(Params.ZERO_ANGLE).encode()) { fr ->
                fr.type == MsgType.PARAM_VALUES && Params.ZERO_ANGLE in decodeIds(fr.payload)
            }
        }
    }

    // ------------------------------------------------------------------ stats

    private val statsLock = Any()
    private var lastTick = -1
    private var winReceived = 0
    private var winExpected = 0
    private var prevReceived = 0
    private var prevExpected = 0
    private var rttEma: Float? = null
    private var espRttEma: Float? = null
    private var rssi: Int? = null
    private var crcErrors = 0L
    private var rxFrames = 0L
    private var lastSlowMs = 0L
    private val latency = LatencyWindow()
    private val uplink = UplinkLossEstimator()
    private var dualWifi = false

    private suspend fun statsLoop() {
        var lastAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(500)
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastAt).coerceAtLeast(1)
            lastAt = now
            val s = synchronized(statsLock) {
                val rec = winReceived + prevReceived
                val exp = winExpected + prevExpected
                val fps = winReceived * 1000f / dt
                val loss = if (exp > 0) ((exp - rec).coerceAtLeast(0) * 100f / exp) else 0f
                prevReceived = winReceived; prevExpected = winExpected
                winReceived = 0; winExpected = 0
                LinkStats(
                    rttEma, espRttEma, fps, loss, rssi, crcErrors, rxFrames,
                    controlLatencyMs = latency.ema(now),
                    controlLatencyP95Ms = latency.p95(now),
                    uplinkLossPercent = uplink.percent(),
                    dualWifi = dualWifi,
                )
            }
            if (_connectionState.value.isSession || s != _linkStats.value) _linkStats.value = s
        }
    }

    private fun onTelemetry(t: Telemetry) {
        val nowMs = System.currentTimeMillis()
        history.add(t, nowMs)
        _telemetry.value = t
        telemCount++
        // stop_reason 只有開機時是 BOOT：從別的狀態回到 BOOT = 主控板重開機（EVENT BOOT 遺失時的備援）
        val prevReason = lastStopReason
        lastStopReason = t.stopReason
        if (prevReason != null && prevReason != StopReason.BOOT && t.stopReason == StopReason.BOOT) requestResync(rebooted = true)
        if (nowMs - lastSlowMs >= 100) {
            lastSlowMs = nowMs
            _telemetrySlow.value = t
        }
        val hz = (_params.value[Params.TELEM_HZ] ?: 50f).coerceIn(10f, 100f)
        val ticksPerFrame = 100f / hz
        synchronized(statsLock) {
            if (lastTick >= 0) {
                val d = (t.tick - lastTick) and 0xFFFF
                winExpected += if (d in 1..500) (d / ticksPerFrame).roundToInt().coerceAtLeast(1) else 1
            } else {
                winExpected += 1
            }
            winReceived++
            lastTick = t.tick
        }
    }

    private inner class RepoListener : LinkListener {
        override fun onFrame(frame: Frame) {
            synchronized(statsLock) { rxFrames++ }
            try {
                when (frame.type) {
                    MsgType.TELEM -> onTelemetry(Telemetry.decode(frame.payload))
                    MsgType.PARAM_VALUES -> {
                        val v = ParamValues.decode(frame.payload).values
                        if (v.isNotEmpty()) _params.update { it + v }
                    }
                    MsgType.INFO -> _carInfo.value = Info.decode(frame.payload)
                    MsgType.EVENT -> {
                        val ev = Event.decode(frame.payload)
                        when (ev.code) {
                            EventCode.BOOT -> requestResync(rebooted = true)
                            EventCode.ZERO_CAL -> onZeroCalEvent(ev.arg / 100f)
                            else -> Unit
                        }
                        val selfStop = ev.code == EventCode.SEQ_ABORTED && ev.arg == 4 &&
                            SystemClock.elapsedRealtime() < quietSeqAbortUntil
                        if (selfStop) quietSeqAbortUntil = 0L else CarEvent.fromEvent(ev)?.let { emitEvent(it) }
                    }
                    MsgType.HELLO_ACK -> {
                        val h = HelloAck.decode(frame.payload)
                        _espInfo.update { it.copy(hello = h) }
                    }
                    MsgType.WIFI_INFO -> {
                        val w = WifiInfo.decode(frame.payload)
                        _espInfo.update { it.copy(wifi = w) }
                    }
                    MsgType.STA_INFO -> onStaInfo(StaInfo.decode(frame.payload))
                    MsgType.ESP_ACK -> {
                        val a = EspAck.decode(frame.payload)
                        if ((a.refType == MsgType.STA_GET || a.refType == MsgType.STA_SET) && a.status == AckStatus.UNKNOWN_TYPE) {
                            _hotspotSupported.value = false
                        }
                    }
                }
            } catch (e: ProtocolException) {
                // 格式錯誤的 payload：忽略
            }
        }

        override fun onRtt(ms: Float) {
            synchronized(statsLock) { rttEma = rttEma?.let { it * 0.7f + ms * 0.3f } ?: ms }
        }

        override fun onEspRtt(ms: Float) {
            synchronized(statsLock) { espRttEma = espRttEma?.let { it * 0.7f + ms * 0.3f } ?: ms }
        }

        override fun onRssi(dbm: Int) {
            synchronized(statsLock) { rssi = dbm }
        }

        override fun onCrcErrors(total: Long) {
            synchronized(statsLock) { crcErrors = total }
        }

        override fun onControlLatency(ms: Float) {
            val now = SystemClock.elapsedRealtime()
            synchronized(statsLock) { latency.add(ms, now) }
        }

        override fun onUplinkSample(sent: Int, received: Int) {
            synchronized(statsLock) { uplink.add(sent, received) }
        }

        override fun onDualWifi(active: Boolean) {
            synchronized(statsLock) { dualWifi = active }
        }

        override fun onLinkMode(mode: LinkMode?) {
            _linkMode.value = mode
        }

        override fun onDiscovered(car: DiscoveredCar) {
            if (_isDemo.value) return
            carsStore.updateHotspot(car.ssid, hotspotMode = if (car.viaHotspot) true else null, lastIp = car.ip)
        }
    }

    // 必須放在所有欄位宣告之後（Kotlin 依宣告順序初始化）
    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        scope.launch { activeLink.flatMapLatest { it.state }.collect { onLinkState(it) } }
        scope.launch { statsLoop() }
        if (settings.value.demoMode) connectDemo()
    }

    companion object {
        /** [recentEvents] 保留筆數。 */
        const val EVENT_LOG_SIZE = 50
        /** 連上後多久讀一次 STA_INFO（讓 INFO / 參數先讀完）。 */
        private const val STA_INFO_AFTER_CONNECT_MS = 300L
        /** 切換到熱點模式：最多等這麼久（車子重開機 + 最多 12 s 找熱點 + 使用者開熱點的時間）。 */
        private const val HOTSPOT_SWITCH_MS = 60_000L
        /** 切換到熱點模式：前這麼久顯示「車子重新開機中」。 */
        private const val HOTSPOT_RESTART_MS = 3_500L
        /** 展示模式切換熱點的模擬時間。 */
        private const val DEMO_SWITCH_STEP_MS = 1_200L
        private const val HOTSPOT_UNSUPPORTED_ZH = "車子的 Wi-Fi 模組韌體太舊，不支援熱點模式，請先更新 ESP-01"
        private const val QUIET_SEQ_ABORT_MS = 1000L

        /** 緊急停止：一開始連發幾個、間隔；之後每 [ESTOP_RESEND_MS] 補送直到遙測確認。 */
        private const val ESTOP_BURST = 3
        private const val ESTOP_BURST_GAP_MS = 25L
        private const val ESTOP_RESEND_MS = 100L
        /** 遙測連續顯示「已停止」這麼久才算確認。 */
        private const val ESTOP_HOLD_MS = 400L
        /** 這麼久還沒確認 → 危險提示。 */
        private const val ESTOP_WARN_MS = 1500L
        /** 最多補送這麼久（訊號中斷期間也持續）。 */
        private const val ESTOP_GIVE_UP_MS = 30_000L
        /** 還沒確認的緊急停止：使用者手動重新連上同一台車時，按下後這麼久內仍會補送（自動重連 / 訊號恢復不限）。 */
        private const val PENDING_ESTOP_MS = 60_000L

        /** 從任何 Context 取得單例。 */
        fun get(context: Context): CarRepository = (context.applicationContext as B570Application).repository
    }
}
