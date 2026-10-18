package com.b570.pilot.ui.screens.choreo

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.SavedSequence
import com.b570.pilot.data.Severity
import com.b570.pilot.protocol.SeqStep
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.LocalScreenPadding
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.NeonSlider
import com.b570.pilot.ui.components.NumberInputDialog
import com.b570.pilot.ui.components.RepeatStepButton
import com.b570.pilot.ui.components.SectionHeader
import com.b570.pilot.ui.components.StatusPill
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 時間軸 LazyColumn 中步驟之前的項目數（標題）。 */
private const val HEAD_ITEMS = 1

/** 預估文字說明（俯視方向 + 轉向換算）。 */
private const val PREVIEW_NOTE = "俯視圖、起點朝上。路線以轉向 100 % ≈ 每秒 180° 估算，實際會依車況與地面略有不同。"

/**
 * 編排編輯器（橫向，覆蓋整個編排分頁）：
 * - 左欄：名稱 / 返回 / 儲存列（固定）+ 步驟時間軸（展開編輯：動作種類、滑桿、上移 / 下移 / 複製 / 插入 / 刪除）+ 新增步驟；
 * - 右欄：大的路徑預覽（移動中的小車、選取 / 播放中步驟高亮）、步驟進度條（點一下選步驟）、總計、循環、上傳並播放 / 停止。
 * 車上正在播放這段時，時間軸與預覽都會跟著遙測標示目前步驟（不在畫面內會自動捲過去）。
 * 返回鍵：有未儲存修改時先詢問。
 */
@Composable
internal fun ChoreoEditor(
    vm: AppViewModel,
    cs: ChoreoState,
    draft: EditorDraft,
    readiness: Readiness,
    status: CarStatus?,
    limits: SimLimits,
    onPlay: (PlayRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val pad = LocalScreenPadding.current
    val ld = LocalLayoutDirection.current
    val density = LocalDensity.current

    val items = draft.items
    val steps = remember(items) { items.map { it.step } }
    val traj = remember(steps, draft.loops, limits) { simulate(steps, draft.loops, limits) }
    val colors = remember(steps, c) { steps.map { it.kind.color(c) } }
    val maxSpeed = limits.maxSpeed.roundToInt()
    val expandedIndex = draft.expandedKey?.let { k -> items.indexOfFirst { it.key == k } } ?: -1

    // 車上正在播放的就是這段 → 標示執行中的步驟（只在步驟改變時重組）
    val loaded by vm.loadedSequence.collectAsStateWithLifecycle()
    val onCar = remember(loaded, steps) { loaded.isNotEmpty() && loaded == steps.map { it.clamped() } }
    val tickFlow = remember(vm) { vm.telemetry.map { PlayTick.of(it) }.distinctUntilChanged() }
    val tick by tickFlow.collectAsStateWithLifecycle(PlayTick.IDLE)
    val seqPlaying = status?.seqPlaying == true || tick.playing
    val executing = if (onCar && tick.playing) tick.step.coerceAtMost(steps.size - 1) else -1
    val stepClock = rememberStepClock(if (onCar) tick else PlayTick.IDLE)

    var askSave by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var headerH by remember { mutableStateOf(64.dp) }

    fun save(): Boolean {
        if (draft.items.isEmpty()) {
            vm.notice("至少要有一個步驟才能儲存", Severity.WARNING)
            return false
        }
        val saved = vm.saveSequence(SavedSequence(draft.id, draft.name.trim().ifEmpty { "未命名編排" }, draft.steps, draft.loops))
        draft.markSaved(saved)
        haptics.confirm()
        vm.notice("已儲存「${saved.name}」", Severity.SUCCESS)
        return true
    }

    fun requestClose() {
        if (draft.dirty && draft.items.isNotEmpty()) askSave = true else cs.closeEditor()
    }

    fun addStep(kind: MoveKind) {
        val key = draft.insertAfter(null, defaultStep(kind)) ?: run {
            vm.notice("最多 ${SeqStep.MAX_STEPS} 步", Severity.WARNING)
            return
        }
        draft.expandedKey = key
        val idx = draft.indexOf(key)
        scope.launch { listState.animateScrollToItem(HEAD_ITEMS + idx) }
    }

    fun focusStep(i: Int) {
        val item = draft.items.getOrNull(i) ?: return
        haptics.tick()
        draft.expandedKey = item.key
        scope.launch { listState.animateScrollToItem(HEAD_ITEMS + i) }
    }

    fun scrollToAdd() {
        scope.launch { listState.animateScrollToItem(HEAD_ITEMS + draft.items.size) }
    }

    BackHandler { requestClose() }

    val clock = rememberLoopClock(traj.totalSec, 15f, 1f, running = steps.isNotEmpty() && executing < 0)

    // 播放中的步驟不在畫面內 → 自動捲過去（使用者正在捲動時不搶）
    LaunchedEffect(executing) {
        if (executing < 0) return@LaunchedEffect
        val target = HEAD_ITEMS + executing
        val info = listState.layoutInfo
        val vis = info.visibleItemsInfo.firstOrNull { it.index == target }
        val head = with(density) { 56.dp.toPx() }.toInt()
        val shown = vis != null && vis.offset >= 0 && vis.offset + min(vis.size, head) <= info.viewportEndOffset - info.afterContentPadding
        if (!shown && !listState.isScrollInProgress) listState.animateScrollToItem(target)
    }

    val playhead: () -> Float = { if (executing >= 0) stepClock.playhead(traj, false) else clock.floatValue }
    val stripProgress: () -> Float = {
        if (executing >= 0) {
            stepClock.progressInLoop(traj, false)
        } else {
            val t = clock.floatValue
            val loop = traj.loopSec
            when {
                steps.isEmpty() || loop <= 0f -> -1f
                t >= traj.totalSec - 1e-4f -> loop
                else -> t - floor(t / loop) * loop
            }
        }
    }

    val hint = when {
        steps.isEmpty() -> "先新增至少一個步驟"
        readiness == Readiness.LINK_LOST || readiness == Readiness.OFFLINE -> readiness.blockedZh
        seqPlaying && onCar -> "正在播放這段編排"
        seqPlaying -> "車上正在播放其他編排，先停止再上傳"
        !readiness.canPlay -> readiness.blockedZh
        readiness == Readiness.MOTORS_OFF -> "馬達未啟動，按下後會先詢問是否啟動"
        else -> null
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val startPad = pad.calculateStartPadding(ld) + 16.dp
        val endPad = pad.calculateEndPadding(ld) + 16.dp
        val topPad = pad.calculateTopPadding()
        val bottomPad = pad.calculateBottomPadding()
        val lay = editorLayoutFor(maxWidth - startPad - endPad)
        val leftW = lay.leftW
        val wideCard = lay.wideCard

        Row(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------- 左：步驟時間軸
            Box(Modifier.width(startPad + leftW + EditorGap / 2).fillMaxHeight()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = startPad,
                        top = topPad + 6.dp + headerH + 10.dp,
                        end = EditorGap / 2,
                        bottom = bottomPad + 8.dp,
                    ),
                ) {
                    item(key = "steps-h", contentType = "header") {
                        SectionHeader(
                            "步驟",
                            subtitle = if (steps.isEmpty()) "從下方挑一個動作開始" else "點步驟可以展開編輯",
                            modifier = Modifier.padding(bottom = 6.dp),
                            trailing = {
                                Text(
                                    "${steps.size} / ${SeqStep.MAX_STEPS}",
                                    style = B570Theme.type.label,
                                    color = if (draft.isFull) c.warn else c.textSecondary,
                                )
                            },
                        )
                    }
                    itemsIndexed(items, key = { _, it -> it.key }, contentType = { _, _ -> "step" }) { i, item ->
                        StepRow(
                            index = i,
                            count = items.size,
                            item = item,
                            expanded = item.key == draft.expandedKey,
                            executing = i == executing,
                            limits = limits,
                            maxSpeed = maxSpeed,
                            canGrow = !draft.isFull,
                            wide = wideCard,
                            onToggle = { draft.expandedKey = if (draft.expandedKey == item.key) null else item.key },
                            onChange = { draft.update(item.key, it) },
                            onMove = { d -> draft.move(item.key, d) },
                            onDuplicate = { draft.duplicate(item.key)?.let { k -> draft.expandedKey = k } },
                            onInsert = { draft.insertAfter(item.key, defaultStep(MoveKind.PAUSE))?.let { k -> draft.expandedKey = k } },
                            onDelete = { draft.delete(item.key) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    item(key = "add", contentType = "add") {
                        AddStepCard(
                            full = draft.isFull, count = steps.size, onAdd = ::addStep, wide = wideCard,
                            modifier = Modifier.animateItem().padding(top = 6.dp),
                        )
                    }
                }

                EditorTopBar(
                    name = draft.name,
                    dirty = draft.dirty,
                    isNew = draft.id.isEmpty(),
                    canSave = (draft.dirty || draft.id.isEmpty()) && steps.isNotEmpty(),
                    showAdd = leftW >= 320.dp,
                    canAdd = !draft.isFull,
                    onBack = ::requestClose,
                    onRename = { renaming = true },
                    onAdd = ::scrollToAdd,
                    onSave = { save() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = startPad, top = topPad + 6.dp)
                        .width(leftW)
                        .onSizeChanged { headerH = with(density) { it.height.toDp() } },
                )
            }
            Spacer(Modifier.width(EditorGap / 2))

            // ---------------------------------------------------------- 右：預覽 + 播放
            EditorSidePanel(
                traj = traj,
                colors = colors,
                steps = steps,
                playhead = playhead,
                stripProgress = stripProgress,
                selectedStep = if (executing >= 0) executing else expandedIndex,
                executing = executing,
                loops = draft.loops,
                onLoops = { draft.loops = it },
                onSelectStep = ::focusStep,
                onInfo = { vm.notice(PREVIEW_NOTE) },
                hint = hint,
                hintWarn = steps.isEmpty() || !readiness.canPlay,
                playing = seqPlaying,
                busy = cs.busy,
                stopping = cs.stopping,
                canStop = readiness != Readiness.LINK_LOST && readiness != Readiness.OFFLINE,
                canPlay = readiness.canPlay && steps.isNotEmpty(),
                motorsOff = readiness == Readiness.MOTORS_OFF,
                onPlay = { onPlay(PlayRequest(draft.id.ifEmpty { "draft" }, draft.name, draft.steps, draft.loops)) },
                onStop = { cs.stop(vm) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = topPad + 6.dp, bottom = bottomPad),
            )
            Spacer(Modifier.width(endPad))
        }
    }

    if (renaming) {
        NameDialog(
            title = "重新命名",
            initial = draft.name,
            onConfirm = { draft.name = it; renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (askSave) {
        SaveChangesDialog(
            name = draft.name,
            onSave = { askSave = false; if (save()) cs.closeEditor() },
            onDiscard = { askSave = false; cs.closeEditor() },
            onDismiss = { askSave = false },
        )
    }
}

// ================================================================== 右欄：預覽 + 播放

/**
 * 右欄。高度夠（平板，≥ 480 dp）：預覽 + 進度條 + 三格總計 + 循環 + 說明，提示在按鈕上方；
 * 矮（手機橫放）：總計併進預覽角落與循環說明、提示疊在預覽左上，讓預覽保持夠大；真的放不下時中段可捲動。
 * 上傳並播放 / 停止固定在下緣。
 */
@Composable
private fun EditorSidePanel(
    traj: SeqTrajectory,
    colors: List<Color>,
    steps: List<SeqStep>,
    playhead: () -> Float,
    stripProgress: () -> Float,
    selectedStep: Int,
    executing: Int,
    loops: Int,
    onLoops: (Int) -> Unit,
    onSelectStep: (Int) -> Unit,
    onInfo: () -> Unit,
    hint: String?,
    hintWarn: Boolean,
    playing: Boolean,
    busy: Boolean,
    stopping: Boolean,
    canStop: Boolean,
    canPlay: Boolean,
    motorsOff: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val fs = max(1f, LocalDensity.current.fontScale)
    val stepCount = steps.size
    val loopMs = SeqStep.totalDurationMs(steps)
    val distance = if (stepCount > 0) fmtDistance(traj.loopDistanceMm) else "--"
    BoxWithConstraints(modifier) {
        val tall = maxHeight >= 480.dp && maxWidth >= 340.dp
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // 預覽以外的高度（估算，含字級）：進度條、總計、循環、說明
                val below = if (tall) 30.dp + 4.dp + 56.dp * fs + 12.dp + 44.dp * fs + 8.dp + 32.dp * fs else 30.dp + 4.dp + 46.dp * fs
                val fits = maxHeight - below >= 96.dp
                Column(if (fits) Modifier.fillMaxSize() else Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PreviewBox(
                        traj = traj,
                        colors = colors,
                        steps = steps,
                        playhead = playhead,
                        selectedStep = selectedStep,
                        executing = executing,
                        hint = if (tall) null else hint,
                        hintWarn = hintWarn,
                        distance = if (tall) null else distance,
                        showInfo = !tall,
                        onInfo = onInfo,
                        modifier = Modifier.fillMaxWidth().then(if (fits) Modifier.weight(1f) else Modifier.height(150.dp)),
                    )
                    // 步驟進度條：預覽 / 車上播放到哪一步；點一下選那一步
                    val stepCountNow by rememberUpdatedState(stepCount)
                    val startSec by rememberUpdatedState(traj.stepStartSec)
                    val select by rememberUpdatedState(onSelectStep)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .pointerInput(Unit) {
                                detectTapGestures { o ->
                                    val i = stepIndexAtFraction(o.x / size.width.coerceAtLeast(1), startSec, stepCountNow)
                                    if (i >= 0) select(i)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        StepProgressStrip(traj.stepStartSec, colors, stripProgress, Modifier.fillMaxWidth().height(8.dp), selectedStep = selectedStep)
                    }
                    Spacer(Modifier.height(4.dp))
                    if (tall) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MiniStat("步數", "$stepCount / ${SeqStep.MAX_STEPS}", Modifier.weight(1f), accent = if (stepCount >= SeqStep.MAX_STEPS) c.warn else null)
                            MiniStat("單趟", fmtTotal(loopMs), Modifier.weight(1f))
                            MiniStat("路程", distance, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    LoopSelector(loops, loopMs, onLoops, compact = !tall)
                    if (tall) {
                        Spacer(Modifier.height(8.dp))
                        Text(PREVIEW_NOTE, style = B570Theme.type.caption, color = c.textMuted)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            PlayBar(if (tall) hint else null, hintWarn, playing, busy, stopping, canStop, canPlay, motorsOff, onPlay, onStop)
        }
    }
}

/**
 * 大的路徑預覽（俯視）：格線、依步驟上色的路線、移動中的小車。
 * 左上依序顯示：播放中的步驟 > 警告提示 > 選取的步驟 > 一般提示 > 方向說明；左下比例尺（+ 路程）；右下說明鈕。
 */
@Composable
private fun PreviewBox(
    traj: SeqTrajectory,
    colors: List<Color>,
    steps: List<SeqStep>,
    playhead: () -> Float,
    selectedStep: Int,
    executing: Int,
    hint: String?,
    hintWarn: Boolean,
    distance: String?,
    showInfo: Boolean,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val stepCount = steps.size
    val shape = RoundedCornerShape(B570Theme.radii.l)
    Box(
        modifier
            .glass(shape, fill = c.glass)
            .clip(shape)
            .background(c.background.copy(alpha = 0.4f)),
    ) {
        if (stepCount > 0) {
            PathPreview(traj, colors, playhead, Modifier.matchParentSize(), selectedStep = selectedStep)
            val sel = selectedStep.takeIf { it in steps.indices }
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                when {
                    executing in steps.indices -> {
                        val k = steps[executing].kind
                        StatusPill("播放中 · 第 ${executing + 1} / $stepCount 步", k.color(c), pulsing = true)
                    }
                    hint != null && hintWarn -> HintPill(hint, true)
                    sel != null -> {
                        val k = steps[sel].kind
                        StatusPill("第 ${sel + 1} 步 · ${k.labelZh}", k.color(c), icon = k.icon)
                    }
                    hint != null -> HintPill(hint, false)
                    else -> Text("俯視 · 起點朝上", style = B570Theme.type.caption, color = c.textMuted, modifier = Modifier.padding(2.dp))
                }
            }
            val g = gridSpacingMm(traj, 600f)
            val gridText = "每格 ${if (g < 1000f) "${(g / 10f).roundToInt()} cm" else "${(g / 1000f).roundToInt()} m"}"
            Text(
                if (distance != null) "$gridText · 路程 $distance" else gridText,
                style = B570Theme.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp, end = if (showInfo) 44.dp else 10.dp),
            )
            if (showInfo) {
                RoundIconButton(
                    Icons.Rounded.Info, "路線怎麼估算", onInfo,
                    Modifier.align(Alignment.BottomEnd).padding(6.dp), size = 32.dp, tint = c.textMuted, fill = Color.Transparent,
                )
            }
        } else {
            Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "新增步驟後，這裡會畫出車子的路線",
                    style = B570Theme.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center,
                )
                if (hint != null) {
                    Spacer(Modifier.height(8.dp))
                    HintPill(hint, hintWarn)
                }
            }
        }
    }
}

/** 疊在預覽上的提示膠囊（最多兩行）。 */
@Composable
private fun HintPill(text: String, warn: Boolean) {
    val c = B570Theme.colors
    Row(
        Modifier
            .glass(RoundedCornerShape(12.dp), fill = c.surface.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, null, tint = if (warn) c.warn else c.info, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = B570Theme.type.caption, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 循環：1–10 次或無限（+ 超過 10 → 無限）。[compact] = 不畫左側圖示，說明加上單趟時間（矮的右欄沒有總計格）。 */
@Composable
private fun LoopSelector(loops: Int, loopMs: Long, onChange: (Int) -> Unit, compact: Boolean = false) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val cur by rememberUpdatedState(loops)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!compact) {
            Box(Modifier.size(36.dp).glass(CircleShape, fill = c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Repeat, null, tint = c.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("循環", style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1)
            Text(
                when {
                    compact && loops == 0 -> "單趟 ${fmtTotal(loopMs)} · 一直重複"
                    compact -> "單趟 ${fmtTotal(loopMs)} · 總長 ${fmtTotal(loopMs * loops)}"
                    loops == 0 -> "一直重複，直到按停止"
                    else -> "總長 ${fmtTotal(loopMs * loops)}"
                },
                style = B570Theme.type.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        RepeatStepButton(Icons.Rounded.Remove, enabled = loops != 1, onStep = {
            val v = cur
            val nv = if (v == 0) 10 else (v - 1).coerceAtLeast(1)
            if (nv != v) { onChange(nv); haptics.tick() } else haptics.reject()
        })
        AnimatedContent(
            targetState = loops,
            transitionSpec = { (fadeIn(tween(160)) + scaleIn(initialScale = 0.7f)).togetherWith(fadeOut(tween(100)) + scaleOut(targetScale = 1.2f)) },
            label = "loops",
            modifier = Modifier.width(if (compact) 56.dp else 64.dp),
        ) { v ->
            Text(
                if (v == 0) "∞" else "$v 次",
                style = B570Theme.type.readoutM,
                color = if (v == 0) c.accent else c.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        RepeatStepButton(Icons.Rounded.Add, enabled = loops != 0, onStep = {
            val v = cur
            val nv = when (v) { 0 -> 0; 10 -> 0; else -> v + 1 }
            if (nv != v) { onChange(nv); haptics.tick() } else haptics.reject()
        })
    }
}

/** 右欄下緣：提示 + 上傳並播放 / 停止（固定、一直看得到）。 */
@Composable
private fun PlayBar(
    hint: String?,
    hintWarn: Boolean,
    playing: Boolean,
    busy: Boolean,
    stopping: Boolean,
    canStop: Boolean,
    canPlay: Boolean,
    motorsOff: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    val c = B570Theme.colors
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = hint != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(
                Modifier
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(12.dp), fill = c.surface.copy(alpha = 0.92f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Info, null, tint = if (hintWarn) c.warn else c.info, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(hint.orEmpty(), style = B570Theme.type.caption, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedContent(
            targetState = playing,
            transitionSpec = { (fadeIn(tween(200)) + scaleIn(initialScale = 0.94f)).togetherWith(fadeOut(tween(120))) },
            label = "playStop",
        ) { isPlaying ->
            if (isPlaying) {
                NeonButton(
                    "停止", onStop, Modifier.fillMaxWidth().height(52.dp),
                    style = NeonButtonStyle.DANGER, icon = Icons.Rounded.Stop, loading = stopping, enabled = canStop,
                )
            } else {
                NeonButton(
                    if (motorsOff) "啟動並播放" else "上傳並播放", onPlay, Modifier.fillMaxWidth().height(52.dp),
                    icon = Icons.Rounded.PlayArrow, enabled = canPlay, loading = busy,
                )
            }
        }
    }
}

// ================================================================== 左欄上緣

@Composable
private fun EditorTopBar(
    name: String,
    dirty: Boolean,
    isNew: Boolean,
    canSave: Boolean,
    showAdd: Boolean,
    canAdd: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onAdd: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.l), fill = c.surface.copy(alpha = 0.92f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .noRippleClickable(onClick = onRename)
                .padding(vertical = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.Edit, "重新命名", tint = c.textMuted, modifier = Modifier.size(14.dp))
            }
            val (sub, col) = when {
                dirty -> "尚未儲存" to c.warn
                isNew -> "新編排" to c.textMuted
                else -> "已儲存" to c.ok
            }
            Text(sub, style = B570Theme.type.caption, color = col, maxLines = 1)
        }
        if (showAdd) {
            Spacer(Modifier.width(6.dp))
            RoundIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "新增步驟", onAdd, size = 38.dp, tint = c.accent, enabled = canAdd)
        }
        Spacer(Modifier.width(8.dp))
        NeonButton("儲存", onSave, compact = true, icon = Icons.Rounded.Save, enabled = canSave)
    }
}

// ================================================================== 步驟

@Composable
private fun StepRow(
    index: Int,
    count: Int,
    item: StepItem,
    expanded: Boolean,
    executing: Boolean,
    limits: SimLimits,
    maxSpeed: Int,
    canGrow: Boolean,
    wide: Boolean,
    onToggle: () -> Unit,
    onChange: (SeqStep) -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onInsert: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val st = item.step
    val kind = st.kind
    val kc = kind.color(c)
    val rail = c.divider
    val isFirst = index == 0
    val isLast = index == count - 1
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 17.dp.toPx()
                val nodeY = 32.dp.toPx()
                val top = if (isFirst) nodeY else 0f
                val bottom = if (isLast) nodeY else size.height
                if (bottom > top) drawLine(rail, Offset(x, top), Offset(x, bottom), 2.dp.toPx())
            }
            .padding(bottom = 10.dp),
    ) {
        Box(Modifier.width(34.dp).padding(top = 18.dp), contentAlignment = Alignment.TopCenter) {
            StepNode(index + 1, kc, executing)
        }
        Spacer(Modifier.width(6.dp))
        val shape = RoundedCornerShape(B570Theme.radii.m)
        val edge by animateColorAsState(
            when {
                executing -> kc
                expanded -> kc.copy(alpha = 0.55f)
                else -> Color.Transparent
            },
            label = "stepEdge",
        )
        val src = remember { MutableInteractionSource() }
        Column(
            Modifier
                .weight(1f)
                .glass(shape, fill = if (expanded) c.glassHigh else c.glass, glow = if (executing) kc else null, glowElevation = 14.dp)
                .border(1.dp, edge, shape)
                .clip(shape),
        ) {
            val arrow by animateFloatAsState(if (expanded) 180f else 0f, spring(stiffness = 500f), label = "arrow")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(src, indication = null, role = Role.Button) { haptics.tick(); onToggle() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).glass(CircleShape, fill = kc.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(kind.icon, null, tint = kc, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(kind.labelZh, style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1)
                    Text(st.summaryZh(), style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Text(fmtDur(st.durMs), style = B570Theme.type.readoutS, color = if (executing) kc else c.textPrimary, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Rounded.KeyboardArrowDown, if (expanded) "收合" else "展開", tint = c.textMuted,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = arrow },
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) + fadeIn(tween(220, delayMillis = 60)),
                exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)) + fadeOut(tween(120)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 500f))
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                    Spacer(Modifier.height(12.dp))
                    KindGrid(selected = kind, onSelect = { k -> onChange(st.convertTo(k, maxSpeed)) }, perRow = if (wide) 5 else 3)
                    Spacer(Modifier.height(10.dp))
                    StepSliders(st, kc, maxSpeed, limits, onChange, columns = if (wide) 2 else 1)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        val w = Modifier.weight(1f)
                        StepAction(Icons.Rounded.KeyboardArrowUp, "上移", w, enabled = index > 0) { onMove(-1) }
                        StepAction(Icons.Rounded.KeyboardArrowDown, "下移", w, enabled = index < count - 1) { onMove(1) }
                        StepAction(Icons.Rounded.ContentCopy, "複製", w, enabled = canGrow, onClick = onDuplicate)
                        StepAction(Icons.Rounded.AddCircleOutline, "插入", w, enabled = canGrow, onClick = onInsert)
                        StepAction(Icons.Rounded.DeleteOutline, "刪除", w, tint = c.danger, onClick = onDelete)
                    }
                }
            }
        }
    }
}

/** 時間軸節點：編號圓圈；執行中會有脈動光環。 */
@Composable
private fun StepNode(number: Int, color: Color, executing: Boolean) {
    val c = B570Theme.colors
    Box(contentAlignment = Alignment.Center) {
        if (executing) PulseRing(color)
        Box(
            Modifier
                .size(28.dp)
                .background(if (executing) color else c.background, CircleShape)
                .border(1.5.dp, color.copy(alpha = if (executing) 1f else 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = B570Theme.type.caption,
                color = if (executing) c.background else c.textPrimary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PulseRing(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Spacer(
        Modifier.size(28.dp).drawBehind {
            val r = size.minDimension / 2f
            drawCircle(color.copy(alpha = (1f - p) * 0.55f), r * (1f + p * 0.9f), style = Stroke(2.dp.toPx()))
        },
    )
}

@Composable
private fun StepAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = B570Theme.colors.textPrimary,
    onClick: () -> Unit,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(src, 0.9f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.3f }
            .clip(RoundedCornerShape(12.dp))
            .clickable(src, indication = null, enabled = enabled, role = Role.Button) { haptics.click(); onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = B570Theme.type.caption, color = if (tint == c.textPrimary) c.textSecondary else tint, maxLines = 1)
    }
}

// ================================================================== 滑桿

/** 滑桿範圍與步進；[log] = 對數刻度（時間用，短時間比較好調）。 */
@Immutable
private class SliderSpec(val min: Float, val max: Float, val step: Float, val log: Boolean = false, val decimals: Int = 0) {
    fun stepAt(v: Float): Float = if (!log) step else when {
        v < 1f -> 0.01f
        v < 5f -> 0.05f
        else -> 0.1f
    }

    fun snap(v: Float): Float {
        val s = stepAt(v)
        return ((v / s).roundToInt() * s).coerceIn(min, max)
    }

    fun toFrac(v: Float): Float =
        if (log) (ln(v.coerceIn(min, max) / min) / ln(max / min)) else ((v - min) / (max - min)).coerceIn(0f, 1f)

    fun fromFrac(f: Float): Float = snap(if (log) min * (max / min).pow(f) else min + f * (max - min))
}

private val DUR_SPEC = SliderSpec(SeqStep.MIN_DUR_MS / 1000f, SeqStep.MAX_DUR_MS / 1000f, 0.01f, log = true, decimals = 2)
private val TURN_MAG_SPEC = SliderSpec(5f, 100f, 1f)
private val TURN_SIGNED_SPEC = SliderSpec(-100f, 100f, 1f)
private val ANGLE_SPEC = SliderSpec(5f, 1080f, 5f)
private val PERIOD_SPEC = SliderSpec(0.2f, 5.1f, 0.02f, decimals = 2)

/**
 * 一列編輯滑桿：標題 + 說明 + 數值（點擊可輸入）＋ −/＋ 微調與霓虹滑軌。拖曳時輕觸覺（節流）。
 */
@Composable
private fun EditorSlider(
    label: String,
    value: Float,
    spec: SliderSpec,
    valueText: String,
    accent: Color,
    onValue: (Float) -> Unit,
    caption: String? = null,
    captionWarn: Boolean = false,
    unit: String = "",
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val cur by rememberUpdatedState(value)
    val set by rememberUpdatedState(onValue)
    val lastTick = remember { longArrayOf(0L) }
    var editing by remember { mutableStateOf(false) }

    fun nudge(dir: Int) {
        val v = cur
        val nv = spec.snap(v + dir * spec.stepAt(if (dir < 0) v - spec.stepAt(v) * 0.5f else v))
        if (nv != v) { set(nv); haptics.tick() } else haptics.reject()
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = B570Theme.type.label, color = c.textPrimary, maxLines = 1)
                if (caption != null) {
                    Text(caption, style = B570Theme.type.caption, color = if (captionWarn) c.warn else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                valueText,
                style = B570Theme.type.readoutS,
                color = accent,
                maxLines = 1,
                modifier = Modifier
                    .glass(RoundedCornerShape(10.dp), fill = c.glassHigh)
                    .noRippleClickable { haptics.click(); editing = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatStepButton(Icons.Rounded.Remove, enabled = true, onStep = { nudge(-1) })
            Spacer(Modifier.width(8.dp))
            NeonSlider(
                value = spec.toFrac(value),
                onValueChange = { f ->
                    val nv = spec.fromFrac(f)
                    if (nv != cur) {
                        set(nv)
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTick[0] > 45) { lastTick[0] = now; haptics.tick() }
                    }
                },
                range = 0f..1f,
                modifier = Modifier.weight(1f),
                accent = accent,
            )
            Spacer(Modifier.width(8.dp))
            RepeatStepButton(Icons.Rounded.Add, enabled = true, onStep = { nudge(1) })
        }
    }

    if (editing) {
        NumberInputDialog(
            title = label,
            initial = value,
            range = spec.min..spec.max,
            onConfirm = { v -> editing = false; set(spec.snap(v)) },
            onDismiss = { editing = false },
            decimals = spec.decimals,
            unit = unit,
        )
    }
}

@Composable
private fun DurSlider(st: SeqStep, kc: Color, onChange: (SeqStep) -> Unit, label: String, caption: String?) {
    EditorSlider(
        label, st.durMs / 1000f, DUR_SPEC, fmtDur(st.durMs), kc,
        { v -> onChange(st.copy(durMs = (v * 1000f).roundToInt().coerceIn(SeqStep.MIN_DUR_MS, SeqStep.MAX_DUR_MS))) },
        caption = caption, unit = "秒",
    )
}

/** 依動作種類顯示需要的滑桿；[columns] = 2 時兩個一列（寬的步驟卡）。 */
@Composable
private fun StepSliders(st: SeqStep, kc: Color, maxSpeed: Int, limits: SimLimits, onChange: (SeqStep) -> Unit, columns: Int = 1) {
    val speedMag = remember(maxSpeed) { SliderSpec(50f, maxSpeed.toFloat().coerceAtLeast(60f), 10f) }
    val speedSigned = remember(maxSpeed) { SliderSpec(-maxSpeed.toFloat(), maxSpeed.toFloat(), 10f) }
    val parts: List<@Composable () -> Unit> = when (st.kind) {
        MoveKind.FORWARD, MoveKind.BACKWARD -> {
            val sign = if (st.speed >= 0) 1 else -1
            listOf(
                {
                    EditorSlider(
                        "速度", abs(st.speed).toFloat(), speedMag, "${abs(st.speed)} mm/s", kc,
                        { v -> onChange(st.copy(speed = sign * v.roundToInt())) },
                        caption = "大約走 ${fmtDistance(abs(st.speed) * st.durMs / 1000f)}", unit = "mm/s",
                    )
                },
                { DurSlider(st, kc, onChange, "時間", null) },
            )
        }
        MoveKind.LEFT, MoveKind.RIGHT -> {
            val sign = if (st.turn >= 0) 1 else -1
            val rate = abs(st.turn) / 1000f * YAW_DPS_FULL
            val angle = st.turnAngleDeg()
            listOf(
                {
                    EditorSlider(
                        "轉速", abs(st.turn) / 10f, TURN_MAG_SPEC, fmtPct(st.turn), kc,
                        { v ->
                            val t = sign * (v * 10f).roundToInt()
                            onChange(st.copy(turn = t, durMs = durForAngle(angle, t)))
                        },
                        caption = "約每秒 ${rate.roundToInt()}°", unit = "%",
                    )
                },
                {
                    EditorSlider(
                        "角度", angle, ANGLE_SPEC, "${angle.roundToInt()}°", kc,
                        { v -> onChange(st.copy(durMs = durForAngle(v, st.turn))) },
                        caption = "需時 ${fmtDur(st.durMs)}", unit = "°",
                    )
                },
            )
        }
        MoveKind.ARC -> {
            val r = st.arcRadiusMm()
            listOf(
                {
                    EditorSlider(
                        "速度", st.speed.toFloat(), speedSigned, fmtSpeedSigned(st.speed), kc,
                        { v ->
                            // 弧線不允許 0（否則會變成原地轉）
                            var s = v.roundToInt()
                            if (abs(s) < 10) s = if (st.speed >= 0) 10 else -10
                            onChange(st.copy(speed = s))
                        },
                        caption = "正值前進、負值後退", unit = "mm/s",
                    )
                },
                {
                    EditorSlider(
                        "轉向", st.turn / 10f, TURN_SIGNED_SPEC, fmtTurnSigned(st.turn), kc,
                        { v ->
                            var t = (v * 10f).roundToInt()
                            if (abs(t) < 10) t = if (st.turn >= 0) 10 else -10
                            onChange(st.copy(turn = t))
                        },
                        caption = r?.let { "轉彎半徑約 ${fmtDistance(it)}" }, unit = "%",
                    )
                },
                { DurSlider(st, kc, onChange, "時間", "約轉 ${st.turnAngleDeg().roundToInt()}°") },
            )
        }
        MoveKind.WIGGLE -> {
            val sign = if (st.turn >= 0) 1 else -1
            val tooFast = abs(st.turn) * 2f * PI.toFloat() / (st.periodMs / 1000f) > limits.turnAccel
            listOf(
                {
                    EditorSlider(
                        "擺幅", abs(st.turn) / 10f, TURN_MAG_SPEC, fmtPct(st.turn), kc,
                        { v -> onChange(st.copy(turn = sign * (v * 10f).roundToInt())) },
                        caption = if (tooFast) "太快了，車子會自動放慢" else "單邊約 ${st.wiggleAmpDeg().roundToInt()}°",
                        captionWarn = tooFast, unit = "%",
                    )
                },
                { PeriodSlider(st, kc, onChange) },
                {
                    EditorSlider(
                        "行進速度", st.speed.toFloat(), speedSigned, fmtSpeedSigned(st.speed), kc,
                        { v -> onChange(st.copy(speed = v.roundToInt())) },
                        caption = "0 = 原地搖擺", unit = "mm/s",
                    )
                },
                { DurSlider(st, kc, onChange, "時間", "約擺 ${cycles(st)} 下") },
            )
        }
        MoveKind.NOD -> {
            val tooFast = abs(st.speed) * 2f * PI.toFloat() / (st.periodMs / 1000f) > limits.accel
            listOf(
                {
                    EditorSlider(
                        "擺幅", abs(st.speed).toFloat(), speedMag, "${abs(st.speed)} mm/s", kc,
                        { v -> onChange(st.copy(speed = v.roundToInt())) },
                        caption = if (tooFast) "太快了，車子會自動放慢" else "前後約 ±${fmtDistance(st.nodAmpMm())}",
                        captionWarn = tooFast, unit = "mm/s",
                    )
                },
                { PeriodSlider(st, kc, onChange) },
                { DurSlider(st, kc, onChange, "時間", "約點 ${cycles(st)} 下") },
            )
        }
        MoveKind.PAUSE -> listOf({ DurSlider(st, kc, onChange, "時間", "原地保持平衡") })
        MoveKind.RAMP -> listOf(
            {
                EditorSlider(
                    "目標速度", st.speed.toFloat(), speedSigned, fmtSpeedSigned(st.speed), kc,
                    { v -> onChange(st.copy(speed = v.roundToInt())) }, unit = "mm/s",
                )
            },
            {
                EditorSlider(
                    "目標轉向", st.turn / 10f, TURN_SIGNED_SPEC, fmtTurnSigned(st.turn), kc,
                    { v -> onChange(st.copy(turn = (v * 10f).roundToInt())) }, unit = "%",
                )
            },
            { DurSlider(st, kc, onChange, "漸變時間", null) },
        )
    }
    Column(Modifier.fillMaxWidth()) {
        if (st.kind == MoveKind.RAMP) {
            Text(
                "從上一步結束時的速度與轉向，平滑變化到這裡設定的值。",
                style = B570Theme.type.caption, color = B570Theme.colors.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (columns <= 1) {
            for (p in parts) p()
        } else {
            for (row in parts.chunked(columns)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (p in row) Box(Modifier.weight(1f)) { p() }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PeriodSlider(st: SeqStep, kc: Color, onChange: (SeqStep) -> Unit) {
    EditorSlider(
        "週期", st.periodMs / 1000f, PERIOD_SPEC, fmtDur(st.periodMs), kc,
        { v -> onChange(st.copy(period20ms = (v / 0.02f).roundToInt().coerceIn(10, 255))) },
        caption = "擺一個來回的時間", unit = "秒",
    )
}

private fun cycles(st: SeqStep): String {
    val n = st.durMs.toFloat() / st.periodMs.coerceAtLeast(20)
    return if (abs(n - n.roundToInt()) < 0.05f) "${n.roundToInt()}" else String.format(Locale.US, "%.1f", n)
}

// ================================================================== 新增步驟

@Composable
private fun AddStepCard(full: Boolean, count: Int, onAdd: (MoveKind) -> Unit, wide: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    GlassCard(modifier.fillMaxWidth().padding(start = 40.dp), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("新增步驟", style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                if (full) "已達 ${SeqStep.MAX_STEPS} 步上限" else "還可以加 ${SeqStep.MAX_STEPS - count} 步",
                style = B570Theme.type.caption,
                color = if (full) c.warn else c.textMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(12.dp))
        KindGrid(selected = null, onSelect = onAdd, enabled = !full, perRow = if (wide) 5 else 3)
    }
}
