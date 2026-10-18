package com.b570.pilot.ui.screens.tune

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.b570.pilot.data.CarEvent
import com.b570.pilot.data.Severity
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.MotorAction
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.ParamDef
import com.b570.pilot.protocol.ParamGroup
import com.b570.pilot.protocol.Params
import com.b570.pilot.ui.AppViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** 整定頁正在執行的長操作（按鈕顯示 loading，其餘按鈕暫停）。 */
internal enum class TuneOp { REFRESH, SAVE, RESET, APPLY, PROFILE, STOP }

/** 觸覺回饋請求（ViewModel 沒有 View，交給畫面執行）。 */
internal enum class TuneFeedback { TICK, CONFIRM, REJECT }

internal sealed interface ZeroCalState {
    data object Idle : ZeroCalState
    data object Running : ZeroCalState
    /** [savedOthers] = 校正前車上還有未儲存的參數修改（韌體校正後整份參數一起寫入 Flash）。 */
    data class Done(val before: Float?, val after: Float, val savedOthers: Boolean = false) : ZeroCalState
    data class Error(val messageZh: String) : ZeroCalState
}

internal sealed interface TuneDialog {
    data object SaveNeedsStop : TuneDialog
    data object SaveWithStaged : TuneDialog
    data object ConfirmReset : TuneDialog
    data object ConfirmDiscard : TuneDialog
    data object ZeroCal : TuneDialog
    data object NewProfile : TuneDialog
    data class ApplyProfile(val profile: TuneProfile) : TuneDialog
    data class DeleteProfile(val profile: TuneProfile) : TuneDialog
}

/** 套用設定檔時的一項變更。 */
internal data class ProfileChange(val def: ParamDef, val from: Float, val to: Float)

/**
 * 單一參數的 UI 狀態。每個欄位都是獨立的 snapshot state，
 * 拖曳某一列時只有那一列重組。
 */
@Stable
internal class ParamUi(val def: ParamDef) {
    /** 車上目前值（未知 = null，顯示預設）。 */
    var car by mutableStateOf<Float?>(null)
    /** 即時模式：拖曳中 / 傳送中的本地值。 */
    var override by mutableStateOf<Float?>(null)
    /** 暫存模式：尚未套用的值。 */
    var staged by mutableStateOf<Float?>(null)
    /** 車子夾限後的實際值（短暫提示）。 */
    var clampNote by mutableStateOf<Float?>(null)

    private val tolerance: Float = if (def.step > 0f) def.step * 0.49f else 1e-4f

    val base: Float get() = car ?: def.default
    val display: Float get() = staged ?: override ?: base
    val isModified: Boolean get() = !near(display, def.default)

    fun near(a: Float, b: Float): Boolean = abs(a - b) <= tolerance
}

/**
 * 整定頁的狀態與邏輯（Activity 範圍，切換分頁不會遺失暫存修改）。
 *
 * 即時套用：拖曳時以 ≤ 每 100 ms 一次節流送出 PARAM_SET（新值覆蓋舊值，前一筆回覆後才送下一筆），
 * 放開後一定送出最終值；顯示值跟隨車子回傳的實際（夾限後）值。
 */
internal class TuneController(private val vm: AppViewModel) : ViewModel() {

    private val store = TuneProfileStore(vm.getApplication<Application>())

    val params: List<ParamUi> = Params.ALL.map { ParamUi(it) }
    private val byId: Map<Int, ParamUi> = params.associateBy { it.def.id }
    val byGroup: Map<ParamGroup, List<ParamUi>> =
        ParamGroup.entries.associateWith { g -> params.filter { it.def.group == g } }

    fun param(id: Int): ParamUi = byId.getValue(id)

    val profiles: StateFlow<List<TuneProfile>> = store.profiles

    // ------------------------------------------------------------------ UI 狀態

    var liveApply by mutableStateOf(store.liveApply)
        private set
    var chartCollapsed by mutableStateOf(store.chartCollapsed)
        private set
    val expanded = mutableStateMapOf<ParamGroup, Boolean>().apply {
        val saved = store.expandedGroups
        ParamGroup.entries.forEach { g ->
            put(g, saved?.contains(g.name) ?: (g != ParamGroup.SAFETY && g != ParamGroup.SYSTEM))
        }
    }
    var busyOp by mutableStateOf<TuneOp?>(null)
        private set
    /** 進行中的即時 PARAM_SET 數。 */
    var inFlight by mutableIntStateOf(0)
        private set
    var dialog by mutableStateOf<TuneDialog?>(null)
    var zeroState by mutableStateOf<ZeroCalState>(ZeroCalState.Idle)
        private set
    /** 最近一次儲存成功的時間（uptime ms），按鈕短暫顯示「已儲存」。 */
    var savedAt by mutableLongStateOf(0L)
        private set

    val stagedCount: Int by derivedStateOf { params.count { it.staged != null } }
    val hasCarValues: Boolean by derivedStateOf { params.any { it.car != null } }
    val syncing: Boolean by derivedStateOf { inFlight > 0 || busyOp != null || zeroState == ZeroCalState.Running }

    private val _feedback = MutableSharedFlow<TuneFeedback>(extraBufferCapacity = 8)
    val feedback: SharedFlow<TuneFeedback> = _feedback.asSharedFlow()

    // ------------------------------------------------------------------ 即時傳送

    private val active = HashSet<Int>()
    private val pending = LinkedHashMap<Int, Float>()
    private val sending = HashSet<Int>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val canEdit: Boolean get() = vm.connectionState.value is ConnectionState.Connected

    init {
        viewModelScope.launch {
            vm.params.collect { m -> for (p in params) p.car = m[p.def.id] }
        }
        viewModelScope.launch { senderLoop() }
    }

    /** @param haptic false = 呼叫端（例如撥桿開關）已經自己做了觸覺 */
    fun setLive(on: Boolean, haptic: Boolean = true) {
        liveApply = on
        store.liveApply = on
        if (haptic) haptic(TuneFeedback.TICK)
    }

    fun toggleChart() {
        chartCollapsed = !chartCollapsed
        store.chartCollapsed = chartCollapsed
    }

    fun toggleGroup(g: ParamGroup) {
        expanded[g] = expanded[g] != true
        store.expandedGroups = expanded.filterValues { it }.keys.map { it.name }.toSet()
    }

    /** 群組快速跳轉：收合中的群組先展開。 */
    fun expandGroup(g: ParamGroup) {
        if (expanded[g] != true) toggleGroup(g)
    }

    /** 使用者改值（拖曳 / 微調 / 輸入 / 選項 / 單項重設）。 */
    fun change(p: ParamUi, value: Float) {
        if (!canEdit) return
        val v = p.def.clamp(value)
        val id = p.def.id
        p.clampNote = null
        if (liveApply) {
            p.staged = null
            p.override = v
            active += id
            pending[id] = v
            wake.trySend(Unit)
        } else {
            p.staged = if (p.near(v, p.base)) null else v
        }
    }

    /** 放開滑桿 / 微調結束 / 輸入確定。 */
    fun finish(p: ParamUi) {
        active -= p.def.id
        if (pending.isNotEmpty()) wake.trySend(Unit)
        maybeRelease(p)
    }

    private suspend fun senderLoop() {
        var lastAt = 0L
        while (true) {
            wake.receive()
            while (pending.isNotEmpty()) {
                val wait = lastAt + THROTTLE_MS - SystemClock.uptimeMillis()
                if (wait > 0) delay(wait)
                if (pending.isEmpty()) break
                val batch = LinkedHashMap(pending)
                pending.clear()
                sending += batch.keys
                lastAt = SystemClock.uptimeMillis()
                inFlight++
                val res = try {
                    vm.setParams(batch)
                } finally {
                    inFlight--
                    sending -= batch.keys
                }
                onLiveApplied(batch, res)
            }
        }
    }

    private fun onLiveApplied(batch: Map<Int, Float>, res: Map<Int, Float>?) {
        if (res == null) {
            // 失敗（提示已由 repository 顯示）：還原成車上值
            for (id in batch.keys) if (isIdle(id)) param(id).override = null
            haptic(TuneFeedback.REJECT)
            return
        }
        var clamped = false
        for ((id, req) in batch) {
            val p = param(id)
            val applied = res[id]
            if (applied != null && !p.near(applied, req)) {
                showClamp(p, applied)
                clamped = true
            }
            if (isIdle(id)) {
                if (applied != null) p.override = applied
                maybeRelease(p)
            }
        }
        if (clamped) haptic(TuneFeedback.REJECT)
    }

    private fun isIdle(id: Int) = id !in active && id !in pending && id !in sending

    /** 確認車上值已更新後，移除本地覆蓋值（避免閃回舊值）。 */
    private fun maybeRelease(p: ParamUi) {
        val id = p.def.id
        val o = p.override ?: return
        if (!isIdle(id)) return
        viewModelScope.launch {
            delay(RELEASE_DELAY_MS)
            if (isIdle(id) && p.override == o) p.override = null
        }
    }

    private fun releaseAll() {
        for (p in params) if (isIdle(p.def.id)) p.override = null
    }

    private fun showClamp(p: ParamUi, applied: Float) {
        p.clampNote = applied
        viewModelScope.launch {
            delay(CLAMP_NOTE_MS)
            if (p.clampNote == applied) p.clampNote = null
        }
    }

    /** 畫面離開：結束未完成的拖曳、關閉對話框。 */
    fun onScreenDisposed() {
        active.clear()
        dialog = null
        if (pending.isNotEmpty()) wake.trySend(Unit)
        params.forEach { maybeRelease(it) }
    }

    // ------------------------------------------------------------------ 暫存

    fun applyStaged() = runOp(TuneOp.APPLY) { applyStagedNow(notifySuccess = true) }

    private suspend fun applyStagedNow(notifySuccess: Boolean): Boolean {
        val batch = LinkedHashMap<Int, Float>()
        for (p in params) p.staged?.let { batch[p.def.id] = it }
        if (batch.isEmpty()) return true
        val res = vm.setParams(batch)
        if (res == null) {
            haptic(TuneFeedback.REJECT)
            return false
        }
        var clamped = 0
        for ((id, req) in batch) {
            val p = param(id)
            val applied = res[id] ?: continue
            if (p.staged == req) p.staged = null // 送出期間又被改過就保留
            if (!p.near(applied, req)) {
                showClamp(p, applied)
                clamped++
            }
            p.override = applied
            maybeRelease(p)
        }
        if (clamped > 0) {
            haptic(TuneFeedback.REJECT)
            vm.notice("已套用，其中 $clamped 項被車子調整", Severity.WARNING)
        } else if (notifySuccess) {
            haptic(TuneFeedback.CONFIRM)
            vm.notice("已套用 ${batch.size} 項變更", Severity.SUCCESS)
        }
        return true
    }

    fun discardStaged() {
        params.forEach { it.staged = null }
        haptic(TuneFeedback.TICK)
    }

    // ------------------------------------------------------------------ 指令

    fun refresh() = runOp(TuneOp.REFRESH) {
        val res = vm.refreshParams()
        if (res != null) {
            releaseAll()
            for (p in params) p.staged?.let { s -> if (p.near(s, p.base)) p.staged = null }
            haptic(TuneFeedback.CONFIRM)
            vm.notice("已讀取車上參數", Severity.SUCCESS)
        } else {
            haptic(TuneFeedback.REJECT)
        }
    }

    /** 「儲存到車上」：有暫存先問、馬達運轉先問，否則直接存。 */
    fun requestSave(motorsOn: Boolean) {
        when {
            stagedCount > 0 -> dialog = TuneDialog.SaveWithStaged
            motorsOn -> dialog = TuneDialog.SaveNeedsStop
            else -> save()
        }
    }

    fun save(applyStagedFirst: Boolean = false, stopFirst: Boolean = false) = runOp(TuneOp.SAVE) {
        if (applyStagedFirst && !applyStagedNow(notifySuccess = false)) return@runOp
        if (stopFirst) {
            val st = vm.motor(MotorAction.STOP)
            if (!st.isOk) {
                haptic(TuneFeedback.REJECT)
                return@runOp
            }
            withTimeoutOrNull(MOTOR_OFF_WAIT_MS) { vm.telemetry.first { it == null || !it.motorsOn } }
        }
        // 等即時傳送送完，確保存進 Flash 的是最新值
        withTimeoutOrNull(LIVE_IDLE_WAIT_MS) {
            while (pending.isNotEmpty() || inFlight > 0) delay(20)
        }
        val st = vm.saveParams(notify = false)
        when {
            st.isOk -> {
                savedAt = SystemClock.uptimeMillis()
                haptic(TuneFeedback.CONFIRM)
            }
            st == AckStatus.BUSY || st == AckStatus.NOT_READY -> {
                haptic(TuneFeedback.REJECT)
                dialog = TuneDialog.SaveNeedsStop
            }
            else -> {
                haptic(TuneFeedback.REJECT)
                vm.notice(
                    CarEvent.ackFailureZh(MsgType.PARAM_SAVE, st),
                    if (st == AckStatus.TIMEOUT || st == AckStatus.OFFLINE) Severity.WARNING else Severity.DANGER,
                )
            }
        }
    }

    fun resetDefaults() = runOp(TuneOp.RESET) {
        pending.clear()
        val res = vm.resetParams()
        if (res != null) {
            params.forEach { it.staged = null }
            releaseAll()
            haptic(TuneFeedback.CONFIRM)
            vm.notice("已恢復預設值，要保留請按「儲存到車上」", Severity.SUCCESS)
        } else {
            haptic(TuneFeedback.REJECT)
        }
    }

    fun stopMotors() = runOp(TuneOp.STOP) {
        val st = vm.motor(MotorAction.STOP)
        haptic(if (st.isOk) TuneFeedback.TICK else TuneFeedback.REJECT)
    }

    fun openZeroCal() {
        zeroState = ZeroCalState.Idle
        dialog = TuneDialog.ZeroCal
    }

    /** 車上有未儲存的參數修改（零點校正會連同它們一起寫入 Flash）。 */
    fun carHasUnsaved(): Boolean = vm.telemetrySlow.value?.paramsDirty == true || inFlight > 0 || pending.isNotEmpty()

    fun runZeroCal() {
        if (zeroState == ZeroCalState.Running || busyOp != null) return
        val zero = param(Params.ZERO_ANGLE)
        val before = zero.car
        val othersDirty = carHasUnsaved()
        zeroState = ZeroCalState.Running
        viewModelScope.launch {
            val st = vm.zeroCal(notify = false)
            zeroState = if (st.isOk) {
                pending.remove(Params.ZERO_ANGLE)
                zero.staged = null
                zero.override = null
                // ACK 之後車子會另送 PARAM_VALUES(id 6)，稍後還有 EVENT ZERO_CAL（repository 都會更新 params）
                val m = withTimeoutOrNull(ZERO_VALUE_WAIT_MS) {
                    vm.params.first { it[Params.ZERO_ANGLE] != null && it[Params.ZERO_ANGLE] != before }
                }
                // 兩個都遺失（或新零點剛好與原本相同）：直接讀車上的值，不要把舊零點當成新零點顯示
                val after = m?.get(Params.ZERO_ANGLE)
                    ?: vm.refreshParams(notify = false)?.get(Params.ZERO_ANGLE)
                    ?: zero.base
                haptic(TuneFeedback.CONFIRM)
                ZeroCalState.Done(before, after, savedOthers = othersDirty)
            } else {
                haptic(TuneFeedback.REJECT)
                ZeroCalState.Error(
                    when (st) {
                        AckStatus.BUSY, AckStatus.NOT_READY -> "馬達運轉中，請先停止再校正"
                        AckStatus.TIMEOUT -> "車子沒有回應，請再試一次"
                        AckStatus.OFFLINE -> "尚未連線"
                        else -> CarEvent.ackFailureZh(MsgType.ZERO_CAL, st)
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------ 設定檔

    /** 與目前顯示值不同的項目；馬達運轉中略過「需停止馬達」的參數。 */
    fun diff(profile: TuneProfile, motorsOn: Boolean): List<ProfileChange> =
        profile.values.mapNotNull { (id, v) ->
            val p = byId[id] ?: return@mapNotNull null
            val to = p.def.clamp(v)
            if (motorsOn && p.def.requiresMotorsOff) return@mapNotNull null
            if (p.near(p.display, to)) null else ProfileChange(p.def, p.display, to)
        }.sortedBy { it.def.id }

    /** 設定檔被略過的項目（馬達運轉中無法更改）。 */
    fun skippedWhileRunning(profile: TuneProfile): List<ParamDef> =
        profile.values.keys.mapNotNull { byId[it]?.def }.filter { d ->
            d.requiresMotorsOff && byId[d.id]?.let { !it.near(it.display, d.clamp(profile.values.getValue(d.id))) } == true
        }

    fun isCurrent(profile: TuneProfile): Boolean =
        profile.values.all { (id, v) -> byId[id]?.let { it.near(it.display, it.def.clamp(v)) } ?: true }

    fun applyProfile(profile: TuneProfile, motorsOn: Boolean) {
        val changes = diff(profile, motorsOn)
        if (changes.isEmpty()) {
            vm.notice("目前已是「${profile.name}」的設定")
            return
        }
        if (!liveApply) {
            for (ch in changes) {
                val p = param(ch.def.id)
                p.staged = if (p.near(ch.to, p.base)) null else ch.to
            }
            haptic(TuneFeedback.CONFIRM)
            vm.notice("已暫存「${profile.name}」的 ${changes.size} 項變更")
            return
        }
        runOp(TuneOp.PROFILE) {
            val batch = changes.associate { it.def.id to it.to }
            val res = vm.setParams(batch)
            if (res == null) {
                haptic(TuneFeedback.REJECT)
                return@runOp
            }
            var clamped = false
            for ((id, req) in batch) {
                val p = param(id)
                val applied = res[id] ?: continue
                p.staged = null
                if (!p.near(applied, req)) {
                    showClamp(p, applied)
                    clamped = true
                }
                p.override = applied
                maybeRelease(p)
            }
            haptic(if (clamped) TuneFeedback.REJECT else TuneFeedback.CONFIRM)
            vm.notice("已套用「${profile.name}」", if (clamped) Severity.WARNING else Severity.SUCCESS)
        }
    }

    fun profileNameTaken(name: String): Boolean = store.findByName(name) != null

    fun saveProfile(name: String) {
        val values = params.filter { it.def.id !in TunePresets.EXCLUDED }.associate { it.def.id to it.display }
        val p = store.save(name, values)
        haptic(TuneFeedback.CONFIRM)
        vm.notice("已儲存設定檔「${p.name}」", Severity.SUCCESS)
    }

    fun deleteProfile(profile: TuneProfile) {
        store.delete(profile.id)
        haptic(TuneFeedback.TICK)
        vm.notice("已刪除「${profile.name}」")
    }

    // ------------------------------------------------------------------ 共用

    private fun runOp(op: TuneOp, block: suspend () -> Unit) {
        if (busyOp != null) return
        busyOp = op
        viewModelScope.launch {
            try {
                block()
            } finally {
                busyOp = null
            }
        }
    }

    private fun haptic(f: TuneFeedback) {
        _feedback.tryEmit(f)
    }

    private companion object {
        const val THROTTLE_MS = 100L
        const val RELEASE_DELAY_MS = 180L
        const val CLAMP_NOTE_MS = 2800L
        const val MOTOR_OFF_WAIT_MS = 800L
        const val LIVE_IDLE_WAIT_MS = 2500L
        const val ZERO_VALUE_WAIT_MS = 700L
    }
}
