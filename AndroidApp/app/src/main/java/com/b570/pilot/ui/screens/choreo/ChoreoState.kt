package com.b570.pilot.ui.screens.choreo

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.b570.pilot.data.SavedSequence
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.MotorAction
import com.b570.pilot.protocol.SeqStep
import com.b570.pilot.ui.AppViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 一次播放請求。[sourceId] = 範例 id / 編排 id / 編輯中草稿。 */
@Immutable
internal data class PlayRequest(val sourceId: String, val name: String, val steps: List<SeqStep>, val loops: Int)

/** 編排結束（車子 EVENT SEQ_DONE / SEQ_ABORTED）。 */
@Immutable
internal data class Outcome(val done: Boolean, val arg: Int, val id: Long)

/** 播放請求結果（給畫面做觸覺回饋）。 */
@Immutable
internal data class Feedback(val ok: Boolean, val id: Long, val atMs: Long = SystemClock.uptimeMillis())

/** 編輯器裡的一個步驟（key 讓清單動畫穩定）。 */
@Immutable
internal data class StepItem(val key: Long, val step: SeqStep)

/**
 * 編輯中的編排（存在 [ChoreoState]，切換分頁不會遺失）。
 * [id] 空白 = 尚未存過的新編排。
 */
@Stable
internal class EditorDraft(id: String, name: String, steps: List<SeqStep>, loops: Int) {
    private var nextKey = 1L

    var id by mutableStateOf(id)
        private set
    var name by mutableStateOf(name)
    var items by mutableStateOf(steps.map { StepItem(nextKey++, it) })
        private set
    var loops by mutableIntStateOf(loops)
    var expandedKey by mutableStateOf<Long?>(null)

    private var savedName by mutableStateOf(name)
    private var savedSteps by mutableStateOf(steps)
    private var savedLoops by mutableIntStateOf(loops)

    val steps: List<SeqStep> get() = items.map { it.step }
    val isFull: Boolean get() = items.size >= SeqStep.MAX_STEPS
    val dirty: Boolean get() = name != savedName || loops != savedLoops || steps != savedSteps

    fun markSaved(saved: SavedSequence) {
        id = saved.id
        name = saved.name
        savedName = saved.name
        savedSteps = saved.steps
        savedLoops = saved.loops
    }

    fun indexOf(key: Long): Int = items.indexOfFirst { it.key == key }

    fun update(key: Long, step: SeqStep) {
        items = items.map { if (it.key == key) it.copy(step = step) else it }
    }

    fun move(key: Long, delta: Int) {
        val i = indexOf(key)
        val j = i + delta
        if (i < 0 || j !in items.indices) return
        items = items.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** 插入到 [afterKey] 後面（null = 最後），回傳新步驟的 key（已滿回傳 null）。 */
    fun insertAfter(afterKey: Long?, step: SeqStep): Long? {
        if (isFull) return null
        val item = StepItem(nextKey++, step)
        val i = if (afterKey == null) -1 else indexOf(afterKey)
        items = items.toMutableList().apply { if (i < 0) add(item) else add(i + 1, item) }
        return item.key
    }

    fun duplicate(key: Long): Long? {
        val src = items.firstOrNull { it.key == key } ?: return null
        return insertAfter(key, src.step)
    }

    fun delete(key: Long) {
        items = items.filter { it.key != key }
        if (expandedKey == key) expandedKey = null
    }
}

/**
 * 編排頁的狀態（Activity 範圍 ViewModel）：編輯草稿、播放流程、結果提示。
 * 指令一律走 [AppViewModel]（main-safe suspend），這裡只負責流程與 UI 狀態。
 */
internal class ChoreoState : ViewModel() {
    var draft by mutableStateOf<EditorDraft?>(null)
        private set

    /** 正在上傳 / 啟動馬達。 */
    var busy by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PlayRequest?>(null)
        private set

    /** 最後一次成功開始播放的編排。 */
    var nowPlaying by mutableStateOf<PlayRequest?>(null)
        private set
    var stopping by mutableStateOf(false)
        private set
    var startingMotors by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<Outcome?>(null)
    var feedback by mutableStateOf<Feedback?>(null)
        private set

    /** 等待使用者確認「先啟動平衡」的請求。 */
    var askStart by mutableStateOf<PlayRequest?>(null)

    private var nextId = 0L
    private var playStartedAt = 0L

    // ------------------------------------------------------------------ 編輯器

    fun openEditor(d: EditorDraft) {
        draft = d
    }

    fun closeEditor() {
        draft = null
    }

    // ------------------------------------------------------------------ 播放

    /**
     * 上傳並播放。[startMotors] = 先啟動馬達（使用者已確認）。
     * 播放中要換編排：CarRepository.loadAndPlaySequence 會先 SEQ_STOP（§3.2 播放中 SEQ_LOAD 會回 BUSY）。
     */
    fun play(vm: AppViewModel, req: PlayRequest, startMotors: Boolean) {
        if (busy) return
        busy = true
        pending = req
        outcome = null
        viewModelScope.launch {
            var st = AckStatus.OK
            try {
                if (startMotors) {
                    st = vm.motor(MotorAction.START)
                    if (st.isOk) withTimeoutOrNull(1500) { vm.telemetry.first { it?.motorsOn == true } }
                }
                if (st.isOk) {
                    // 播放中換編排：repository 會先送 SEQ_STOP 再上傳（§3.2）
                    // 搖桿歸零，避免被判定為手動接管
                    vm.stopControl()
                    st = vm.loadAndPlaySequence(req.steps, req.loops)
                    if (st.isOk) {
                        nowPlaying = req
                        playStartedAt = SystemClock.uptimeMillis()
                    }
                }
            } finally {
                busy = false
                pending = null
                feedback = Feedback(st.isOk, ++nextId)
            }
        }
    }

    fun stop(vm: AppViewModel) {
        if (stopping) return
        stopping = true
        viewModelScope.launch {
            try {
                vm.stopSequence()
            } finally {
                stopping = false
            }
        }
    }

    fun startMotors(vm: AppViewModel) {
        if (startingMotors) return
        startingMotors = true
        viewModelScope.launch {
            try {
                val st = vm.motor(MotorAction.START)
                feedback = Feedback(st.isOk, ++nextId)
            } finally {
                startingMotors = false
            }
        }
    }

    /** 車子回報編排結束。 */
    fun onSequenceEnded(done: Boolean, arg: Int) {
        // 換編排時我們自己送的 SEQ_STOP 也會觸發「已停止」事件：上傳中或剛開始播放時忽略
        if (busy) return
        if (!done && arg == 4 && SystemClock.uptimeMillis() - playStartedAt < 800) return
        outcome = Outcome(done, arg, ++nextId)
    }

    fun clearOutcome(id: Long) {
        if (outcome?.id == id) outcome = null
    }

    /** 連線中斷：清掉播放中的狀態（草稿保留）。 */
    fun onSessionEnded() {
        nowPlaying = null
        outcome = null
        askStart = null
    }
}
