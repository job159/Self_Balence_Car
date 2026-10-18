package com.b570.pilot.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.b570.pilot.data.AppSettings
import com.b570.pilot.data.CarEvent
import com.b570.pilot.data.CarRepository
import com.b570.pilot.data.CarShortcuts
import com.b570.pilot.data.ConnectPlan
import com.b570.pilot.data.EspInfo
import com.b570.pilot.data.Gear
import com.b570.pilot.data.HotspotSwitchStatus
import com.b570.pilot.data.LaunchRequest
import com.b570.pilot.data.LinkStats
import com.b570.pilot.data.ReconnectStatus
import com.b570.pilot.data.SavedCar
import com.b570.pilot.data.SavedSequence
import com.b570.pilot.data.Severity
import com.b570.pilot.data.TelemetryHistory
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.DiscoveredCar
import com.b570.pilot.link.LinkMode
import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.Info
import com.b570.pilot.protocol.MotorAction
import com.b570.pilot.protocol.SeqStep
import com.b570.pilot.protocol.StaInfo
import com.b570.pilot.protocol.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 所有畫面共用的 ViewModel：把 [CarRepository] 的資料流與動作轉給 UI。
 * 畫面取得方式：shell 直接傳入（`fun XxxScreen(vm: AppViewModel, nav: AppNavigator, modifier)`）。
 *
 * - 資料流：用 `collectAsStateWithLifecycle()` 收集。
 * - suspend 指令：在 `rememberCoroutineScope().launch { }` 內呼叫，或用 [launch]（離開畫面也會完成）。
 * - 失敗時 repository 會自動顯示中文提示（notify = false 可關閉）。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo: CarRepository = CarRepository.get(app)

    /** 導覽狀態放在 ViewModel：Activity 重建（深色模式、字體大小、分割畫面…）時分頁與覆蓋層不會重置。 */
    val nav = AppNavigator()

    // ------------------------------------------------------------------ 資料流

    val connectionState: StateFlow<ConnectionState> = repo.connectionState
    /** 最新遙測（約 50 Hz）。只在小的葉節點收集，或在繪製階段讀取。 */
    val telemetry: StateFlow<Telemetry?> = repo.telemetry
    /** 節流遙測（≤ 10 Hz），一般文字 / 狀態用這個。 */
    val telemetrySlow: StateFlow<Telemetry?> = repo.telemetrySlow
    val linkStats: StateFlow<LinkStats> = repo.linkStats
    val carInfo: StateFlow<Info?> = repo.carInfo
    val espInfo: StateFlow<EspInfo> = repo.espInfo
    /** 參數 id → 車上目前值（未知時為空 map，請以 ParamDef.default 顯示）。 */
    val params: StateFlow<Map<Int, Float>> = repo.params
    val currentCar: StateFlow<WifiCredentials?> = repo.currentCar
    val isDemo: StateFlow<Boolean> = repo.isDemo
    val loadedSequence: StateFlow<List<SeqStep>> = repo.loadedSequence
    val events: SharedFlow<CarEvent> = repo.events
    /** 最近 50 則事件（最新在前，含連線變化），從 App 啟動起保留。 */
    val recentEvents: StateFlow<List<CarEvent>> = repo.recentEvents
    val savedCars: StateFlow<List<SavedCar>> = repo.savedCars
    val settings: StateFlow<AppSettings> = repo.settings
    val sequences: StateFlow<List<SavedSequence>> = repo.sequences
    /** 遙測歷史（LineChart 直接讀）。 */
    val history: TelemetryHistory get() = repo.history

    /** 目前車子的顯示名稱（暱稱優先，否則 SSID；未連線為空字串）。 */
    val currentCarName: StateFlow<String> = combine(repo.currentCar, repo.savedCars, repo.isDemo) { car, cars, demo ->
        when {
            car == null -> ""
            demo -> "展示車"
            else -> cars.firstOrNull { it.ssid == car.ssid }?.displayName ?: car.ssid
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ------------------------------------------------------------------ 快速連線

    /**
     * 斷線後自動重連的進度（null = 沒有在重連）。重連期間連線狀態是 Failed / 連線中，
     * shell 會停在原本的分頁（駕駛畫面照樣顯示、輸入被封鎖），並在狀態條 / 駕駛上緣顯示「自動重連中（第 n/6 次）」。
     */
    val reconnect: StateFlow<ReconnectStatus?> = repo.reconnect

    private val _reconnectGaveUp = MutableStateFlow<ReconnectStatus?>(null)

    /**
     * 自動重連放棄了（次數用完，或遇到不能重試的錯誤）時最後一次的進度；連線頁的失敗卡用來說明「已自動重試 n 次」。
     * 連線狀態離開 Failed 就清掉。
     */
    val reconnectGaveUp: StateFlow<ReconnectStatus?> = _reconnectGaveUp.asStateFlow()

    /** 附近的車：SSID → 訊號 dBm（記住的車 + 尚未配對的 `B570-` 熱點）。連線頁顯示期間才會更新。 */
    val nearby: StateFlow<Map<String, Int>> = repo.nearby

    private val _pendingConnect = MutableStateFlow<WifiCredentials?>(null)

    /** 桌面捷徑要連的車：連線頁收下後（[consumePendingConnect]）先檢查權限與 Wi-Fi 再連線。 */
    val pendingConnect: StateFlow<WifiCredentials?> = _pendingConnect.asStateFlow()

    /** 開啟 App 自動連最近的車（每個 App 行程最多一次；連線頁在 Wi-Fi 權限就緒時呼叫）。回傳正在連線的車。 */
    fun autoConnectOnLaunch(): SavedCar? = repo.autoConnectOnLaunch()

    /** 停止自動重連並中斷（重連提示的「取消」）：狀態回到 Idle，shell 會切到連線頁。 */
    fun cancelReconnect() = repo.disconnect()

    fun startNearbyScan() = repo.startNearbyScan()
    fun stopNearbyScan() = repo.stopNearbyScan()
    fun refreshNearby() = repo.refreshNearby()

    fun consumePendingConnect(credentials: WifiCredentials) {
        _pendingConnect.compareAndSet(credentials, null)
    }

    /**
     * 處理桌面捷徑（MainActivity 的 onCreate / onNewIntent）。這次啟動就不再自動連最近的車。
     * 安全：先把搖桿指令歸零（分割畫面時 App 可能還被按著）；掃描頁蓋住駕駛畫面時駕駛畫面自己停用搖桿。
     * - 掃描配對：直接開 QR 掃描（掃描頁自己處理相機權限，連線中也能用）。
     * - 連線某台車：已經連著 / 正在連 / 正在自動重連這台就不動；正連著另一台真車而且馬達在運轉 → 不切換（提示先停止），
     *   否則交給連線頁（權限、Wi-Fi 檢查），目前在主畫面（連著別台車或展示模式）時先中斷，shell 會切回連線頁。
     */
    fun handleLaunchRequest(request: LaunchRequest) {
        repo.skipAutoConnectOnLaunch()
        stopControl()
        when (request) {
            LaunchRequest.ScanPair -> nav.openScanner()
            is LaunchRequest.ConnectCar -> {
                val car = savedCars.value.firstOrNull { it.ssid == request.ssid }
                if (car == null) {
                    nav.openScanner()
                    // 冷啟動時提示列還沒開始收集（events 不重播）：等畫面出來再提示
                    viewModelScope.launch {
                        delay(NOTICE_AFTER_LAUNCH_MS)
                        notice("找不到這台車，請重新掃描車上的 QR Code 配對", Severity.WARNING)
                    }
                    return
                }
                val st = connectionState.value
                val same = !isDemo.value && currentCar.value?.ssid == car.ssid
                if (same && (st.isSession || st.isConnecting || reconnect.value != null)) return
                // 正在駕駛另一台車（已連線、馬達運轉中）：不要直接丟下它去連別台 —— 中斷後那台車會一直原地平衡，
                // 手機就再也停不了它（緊急停止送不到）。請使用者先按「停止」再用捷徑切換。
                if (!isDemo.value && st is ConnectionState.Connected && telemetry.value?.motorsOn == true) {
                    nav.closeOverlay()
                    notice("「${currentCarName.value}」還在運轉，請先按「停止」再切換到「${car.displayName}」", Severity.WARNING)
                    return
                }
                nav.closeOverlay()
                _pendingConnect.value = WifiCredentials(car.ssid, car.password)
                if (nav.base is Route.Main || st.isConnecting || reconnect.value != null) disconnect()
            }
        }
    }

    init {
        // 自動重連放棄 → 記下最後的進度給連線頁；狀態離開 Failed 就清掉
        viewModelScope.launch {
            var last: ReconnectStatus? = null
            combine(repo.reconnect, repo.connectionState) { r, st -> r to st }.collect { (r, st) ->
                when {
                    r != null -> {
                        last = r
                        _reconnectGaveUp.value = null
                    }
                    st is ConnectionState.Failed -> {
                        if (last != null) _reconnectGaveUp.value = last
                        last = null
                    }
                    else -> {
                        last = null
                        _reconnectGaveUp.value = null
                    }
                }
            }
        }
        // 桌面捷徑跟著記住的車更新（只有影響捷徑的內容變了才重新發佈）
        viewModelScope.launch(Dispatchers.Default) {
            repo.savedCars
                .map { cars -> cars to (CarShortcuts.plan(cars) to cars.map { it.ssid }.toSet()) }
                .distinctUntilChanged { a, b -> a.second == b.second }
                .collect { (cars, _) -> CarShortcuts.publish(app, cars) }
        }
        // 連上真車：回報捷徑使用（桌面的建議排序）
        viewModelScope.launch(Dispatchers.Default) {
            combine(repo.connectionState, repo.currentCar, repo.isDemo) { st, car, demo ->
                if (st is ConnectionState.Connected && !demo) car?.ssid else null
            }.distinctUntilChanged().collect { ssid -> if (ssid != null) CarShortcuts.reportUsed(app, ssid) }
        }
    }

    // ------------------------------------------------------------------ 熱點模式（手機個人熱點 + 行動網路）

    /** 目前連線的路徑（直連 / 手機熱點）；沒有連線 = null。 */
    val linkMode: StateFlow<LinkMode?> = repo.linkMode
    /** 車子的熱點模式狀態（STA_INFO）；還沒讀到 = null。 */
    val staInfo: StateFlow<StaInfo?> = repo.staInfo
    /** 車子的 Wi-Fi 模組支援熱點模式：null = 還不知道、false = ESP 韌體太舊。 */
    val hotspotSupported: StateFlow<Boolean?> = repo.hotspotSupported
    /** 切換到熱點模式的進度（重新開機中 / 請開啟手機熱點 / 尋找中 / 沒找到）；null = 沒有在切換。 */
    val hotspotSwitch: StateFlow<HotspotSwitchStatus?> = repo.hotspotSwitch

    /** 開 / 關熱點模式（ssid / password = 手機個人熱點；先用 HotspotConfig 檢查）。成功後車子重開機，連線層自動切換。 */
    suspend fun setHotspot(enable: Boolean, ssid: String = "", password: String = "", notify: Boolean = true): AckStatus =
        repo.setHotspot(enable, ssid, password, notify)

    /** 立即讀取車子的熱點模式狀態（也會更新 [staInfo]）。 */
    suspend fun requestStaInfo(notify: Boolean = false): StaInfo? = repo.requestStaInfo(notify)

    /** 取消切換到熱點模式（停止找車並中斷）。 */
    fun cancelHotspotSwitch() = repo.cancelHotspotSwitch()

    /** 關掉「沒有找到車子」的切換提示。 */
    fun dismissHotspotSwitch() = repo.dismissHotspotSwitch()

    /** 熱點切換的進度對話框顯示中（切換過程的提示改由對話框說明，不重複跳出）。 */
    fun setSwitchProgressOnScreen(onScreen: Boolean) {
        repo.switchProgressOnScreen = onScreen
    }

    /** 手機個人熱點看起來開著（約 1–5 ms；需要時輪詢，例如每 1–2 s）。 */
    fun isPhoneHotspotLikelyOn(): Boolean = repo.isPhoneHotspotLikelyOn()

    /** 已回答過「帶出去玩：改用熱點模式」邀請的車（SSID；shell 的 HotspotOnboardingHost 每台車只問一次）。 */
    val hotspotPromptAnswered: StateFlow<Set<String>> = repo.hotspotPromptAnswered

    /** 記下這台車已回答「改用熱點模式」邀請（按了「先不用」、或沒套用就關掉熱點表單；套用成功時 repository 自己記）。 */
    fun markHotspotPromptAnswered(ssid: String) = repo.markHotspotPromptAnswered(ssid)

    /** 掃描手機熱點上的車（配對已經是熱點模式、OLED 沒有 QR 的車）。 */
    suspend fun discoverHotspotCars(timeoutMs: Long = 2_000L): List<DiscoveredCar> = repo.discoverHotspotCars(timeoutMs)

    /** 連線到掃描到的車（還沒記住會自動記住）。 */
    fun connectDiscovered(car: DiscoveredCar) = repo.connectDiscovered(car)

    /** 這組帳密現在會怎麼連（先找車 / 直連），連線頁決定要不要先檢查 Wi-Fi 與權限：`plan.discover` = 不一定需要 Wi-Fi。 */
    fun connectPlanFor(credentials: WifiCredentials): ConnectPlan = repo.planFor(credentials)

    // ------------------------------------------------------------------ 連線

    /** 智慧連線：熱點模式的車 / 手機熱點開著 → 先在熱點上找車（≤ 4 s），找不到再直連。 */
    fun connect(credentials: WifiCredentials) = repo.connect(credentials)

    /** 連線到記住的車（= [connect]）。 */
    fun smartConnect(car: SavedCar) = repo.smartConnect(car)

    /** 一律直連車子的 AP。 */
    fun connectDirect(credentials: WifiCredentials) = repo.connectDirect(credentials)
    fun connectDemo() = repo.connectDemo()
    fun retry() = repo.retry()
    fun disconnect() = repo.disconnect()

    /** QR 掃描成功：記住這台車，並（預設）立即直連（OLED 顯示 QR = 車子正在直連 AP 模式）。 */
    fun onQrScanned(credentials: WifiCredentials, connectNow: Boolean = true) {
        repo.saveCar(credentials)
        if (connectNow) repo.connectDirect(credentials)
    }

    // ------------------------------------------------------------------ 控制（不阻塞，可每幀呼叫）

    /** 車速 mm/s（正 = 前進）、轉向 ‰（-1000..1000，正 = 右轉）。 */
    fun setControl(speedMmps: Int, turnPermille: Int) = repo.setControl(speedMmps, turnPermille)

    /** forward / turn ∈ -1..1，依檔位與 MAX_SPEED 換算。 */
    fun setControlNormalized(forward: Float, turn: Float, gear: Gear) = repo.setControlNormalized(forward, turn, gear)

    /** 放開搖桿 / 離開畫面時呼叫。 */
    fun stopControl() = repo.setControl(0, 0)

    /** MainActivity：視窗失去焦點（系統「連線到裝置」視窗、通知欄…）。自動重連用來判斷有沒有跳出系統同意視窗。 */
    fun onWindowFocusLost() = repo.onWindowFocusLost()

    /** 緊急停止（立即送出，不需 coroutine）。 */
    fun emergencyStop() = repo.emergencyStop()

    // ------------------------------------------------------------------ 指令（suspend）

    suspend fun motor(action: MotorAction, notify: Boolean = true): AckStatus = repo.motor(action, notify)
    suspend fun zeroCal(notify: Boolean = true): AckStatus = repo.zeroCal(notify)
    suspend fun setParams(values: Map<Int, Float>, notify: Boolean = true): Map<Int, Float>? = repo.setParams(values, notify)
    suspend fun setParam(id: Int, value: Float, notify: Boolean = true): Float? = repo.setParam(id, value, notify)
    suspend fun saveParams(notify: Boolean = true): AckStatus = repo.saveParams(notify)
    suspend fun resetParams(notify: Boolean = true): Map<Int, Float>? = repo.resetParams(notify)
    suspend fun refreshParams(notify: Boolean = true): Map<Int, Float>? = repo.refreshParams(notify)
    suspend fun loadAndPlaySequence(steps: List<SeqStep>, loops: Int, notify: Boolean = true): AckStatus =
        repo.loadAndPlaySequence(steps, loops, notify)
    suspend fun stopSequence(notify: Boolean = true): AckStatus = repo.stopSequence(notify)
    suspend fun setWifi(ssid: String, password: String, channel: Int = 0, notify: Boolean = true): AckStatus =
        repo.setWifi(ssid, password, channel, notify)
    suspend fun factoryWifi(notify: Boolean = true): AckStatus = repo.factoryWifi(notify)

    /** 在 ViewModel 範圍執行（離開畫面也會跑完），例如 `vm.launch { saveParams() }`。 */
    fun launch(block: suspend CarRepository.() -> Unit): Job = viewModelScope.launch { repo.block() }

    // ------------------------------------------------------------------ 本地資料

    fun updateSettings(transform: (AppSettings) -> AppSettings) = repo.updateSettings(transform)
    fun saveCar(credentials: WifiCredentials, nickname: String? = null) = repo.saveCar(credentials, nickname)
    fun renameCar(ssid: String, nickname: String) = repo.renameCar(ssid, nickname)
    fun forgetCar(ssid: String) = repo.forgetCar(ssid)
    fun saveSequence(seq: SavedSequence): SavedSequence = repo.saveSequence(seq)
    fun deleteSequence(id: String) = repo.deleteSequence(id)
    fun newSequenceId(): String = repo.newSequenceId()
    fun clearHistory() = repo.clearHistory()
    fun clearEventLog() = repo.clearEventLog()

    /** 顯示一則提示（toast）。 */
    fun notice(messageZh: String, severity: Severity = Severity.INFO) = repo.notice(messageZh, severity)

    /**
     * 把遙測歷史匯出成 CSV 到 [uri]（例如 ActivityResultContracts.CreateDocument("text/csv") 的結果）。
     * @return 成功與否（失敗會自動提示）
     */
    suspend fun exportHistoryCsv(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { repo.history.exportCsv(it) }
                ?: error("no stream")
            repo.notice("已匯出遙測資料", Severity.SUCCESS)
            true
        } catch (e: Exception) {
            repo.notice("匯出失敗，請再試一次", Severity.DANGER)
            false
        }
    }

    private companion object {
        /** 冷啟動（桌面捷徑）時等畫面組合、提示列開始收集後才跳提示。 */
        const val NOTICE_AFTER_LAUNCH_MS = 800L
    }
}
