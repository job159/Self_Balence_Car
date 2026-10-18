package com.b570.pilot.ui.screens.choreo

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.b570.pilot.data.SavedSequence
import com.b570.pilot.data.Severity
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.EventCode
import com.b570.pilot.protocol.SeqMode
import com.b570.pilot.protocol.SeqStep
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.ConfirmDialog
import com.b570.pilot.ui.components.LocalScreenPadding
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.SectionHeader
import com.b570.pilot.ui.components.StatusPill
import com.b570.pilot.ui.components.lerpColor
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.sin

/**
 * 「編排」分頁（橫向）：
 * - 上緣：標題 + 車況橫幅（寬時並排）；
 * - 動作庫：自適應格狀（依寬度 3–6 欄），每張卡有循環播放的路徑縮圖；
 * - 我的編排：平板放右側側欄（獨立捲動），手機放在動作庫下方（寬時一列兩張）；
 * - 播放中：下緣固定播放列（縮圖、目前步驟、進度、停止 / 再播一次）；
 * - 編輯器：全區覆蓋（左 = 步驟時間軸，右 = 路徑預覽 + 循環 + 上傳並播放）。
 * 內容畫在左側導覽軌與頂部狀態條之下（LocalScreenPadding）。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ChoreoScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier) {
    val cs: ChoreoState = viewModel { ChoreoState() }
    val haptics = rememberHaptics()
    val currentHaptics by rememberUpdatedState(haptics)
    val conn by vm.connectionState.collectAsStateWithLifecycle()
    val statusFlow = remember(vm) { vm.telemetrySlow.map { t -> t?.let { CarStatus.of(it) } }.distinctUntilChanged() }
    val status by statusFlow.collectAsStateWithLifecycle(vm.telemetrySlow.value?.let { CarStatus.of(it) })
    val params by vm.params.collectAsStateWithLifecycle()
    val limits = remember(params) { SimLimits.from(params) }
    val readiness = Readiness.of(conn, status)

    // 車子事件：完成放煙火、中止抖動（文字提示由 shell 的 toast 顯示）
    LaunchedEffect(cs, vm) {
        vm.events.collect { e ->
            when (e.code) {
                EventCode.SEQ_DONE -> {
                    cs.onSequenceEnded(true, e.arg)
                    currentHaptics.confirm()
                }
                EventCode.SEQ_ABORTED -> cs.onSequenceEnded(false, e.arg)
                else -> Unit
            }
        }
    }
    // 結果幾秒後自動收起
    val outcome = cs.outcome
    LaunchedEffect(outcome?.id) {
        val o = outcome ?: return@LaunchedEffect
        delay(if (o.done) 4500 else 3500)
        cs.clearOutcome(o.id)
    }
    // 播放開始成功 → 確認觸覺（失敗時 toast 已有觸覺）
    val fb = cs.feedback
    LaunchedEffect(fb?.id) {
        if (fb != null && fb.ok && SystemClock.uptimeMillis() - fb.atMs < 1500) haptics.confirm()
    }
    LaunchedEffect(conn.isSession) { if (!conn.isSession) cs.onSessionEnded() }

    // 播放一律經過這裡：需已連線 + 馬達運轉中（馬達未啟動 → 先確認「啟動並播放」；感測器異常 / 電量過低 / 倒下 → 拒絕 + 提示）
    fun requestPlay(req: PlayRequest) {
        when {
            req.steps.isEmpty() -> vm.notice("這段編排還沒有步驟", Severity.WARNING)
            cs.busy -> haptics.reject()
            !readiness.canPlay -> vm.notice(readiness.blockedZh, Severity.WARNING)
            readiness == Readiness.MOTORS_OFF -> cs.askStart = req
            else -> cs.play(vm, req, startMotors = false)
        }
    }

    val gridState = rememberLazyGridState()
    val sideState = rememberLazyListState()
    Box(modifier) {
        AnimatedContent(
            targetState = cs.draft,
            contentKey = { it != null },
            transitionSpec = {
                if (targetState != null) {
                    (slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it / 6 } + fadeIn(tween(240)) + scaleIn(initialScale = 0.98f))
                        .togetherWith(fadeOut(tween(160)) + scaleOut(targetScale = 0.96f))
                } else {
                    (fadeIn(tween(260, delayMillis = 60)) + scaleIn(initialScale = 0.96f))
                        .togetherWith(slideOutVertically(tween(260)) { it / 6 } + fadeOut(tween(200)))
                }
            },
            label = "choreo",
        ) { d ->
            if (d != null) {
                ChoreoEditor(vm, cs, d, readiness, status, limits, { requestPlay(it) }, Modifier.fillMaxSize())
            } else {
                ChoreoHome(vm, cs, gridState, sideState, conn, readiness, status, limits, { requestPlay(it) }, Modifier.fillMaxSize())
            }
        }
    }

    cs.askStart?.let { req ->
        ConfirmDialog(
            title = "先啟動平衡？",
            message = "播放「${req.name}」需要先啟動平衡。請確認車子已扶正、周圍淨空，再開始播放。",
            confirmText = "啟動並播放",
            icon = Icons.Rounded.PowerSettingsNew,
            onConfirm = {
                cs.askStart = null
                cs.play(vm, req, startMotors = true)
            },
            onDismiss = { cs.askStart = null },
        )
    }
}

// ================================================================== 首頁

@Composable
private fun ChoreoHome(
    vm: AppViewModel,
    cs: ChoreoState,
    gridState: LazyGridState,
    sideState: LazyListState,
    conn: ConnectionState,
    readiness: Readiness,
    status: CarStatus?,
    limits: SimLimits,
    onPlay: (PlayRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val sequences by vm.sequences.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val presetTrajs = remember(limits) { ChoreoPresets.ALL.map { simulate(it.steps, it.loops, limits, maxPoints = 320) } }

    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(2f, tween(2000, easing = LinearEasing)) }
    val enter = { entrance.value }

    var renameTarget by remember { mutableStateOf<SavedSequence?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedSequence?>(null) }

    val seqPlaying = status?.seqPlaying == true
    val playingId = if (seqPlaying) cs.nowPlaying?.sourceId else null
    val pendingId = cs.pending?.sourceId
    val showDock = cs.busy || seqPlaying || cs.outcome != null

    fun names() = sequences.map { it.name }

    fun copyPreset(p: ChoreoPreset) {
        val saved = vm.saveSequence(SavedSequence("", uniqueName(p.name, names()), p.steps, p.loops))
        vm.notice("已複製到「我的編排」，可以自由修改", Severity.SUCCESS)
        cs.openEditor(EditorDraft(saved.id, saved.name, saved.steps, saved.loops))
    }

    fun newSequence() {
        val existing = names()
        var k = sequences.size + 1
        while ("我的編排 $k" in existing) k++
        cs.openEditor(EditorDraft("", "我的編排 $k", STARTER_STEPS, 1))
    }

    fun duplicate(s: SavedSequence) {
        vm.saveSequence(SavedSequence("", uniqueName("${s.name} 副本", names()), s.steps, s.loops))
        vm.notice("已複製「${s.name}」", Severity.SUCCESS)
    }

    @Composable
    fun SeqItem(s: SavedSequence, i: Int, rowModifier: Modifier) {
        SequenceRow(
            seq = s,
            limits = limits,
            playing = playingId == s.id,
            loading = pendingId == s.id,
            onOpen = { cs.openEditor(EditorDraft(s.id, s.name, s.steps, s.loops)) },
            onPlay = { onPlay(PlayRequest(s.id, s.name, s.steps, s.loops)) },
            onStop = { cs.stop(vm) },
            onRename = { renameTarget = s },
            onDuplicate = { duplicate(s) },
            onDelete = { deleteTarget = s },
            modifier = rowModifier.staggerIn(enter, 15 + i),
        )
    }

    val pad = LocalScreenPadding.current
    val ld = LocalLayoutDirection.current
    val density = LocalDensity.current
    var dockH by remember { mutableStateOf(0.dp) }

    BoxWithConstraints(modifier) {
        val startPad = pad.calculateStartPadding(ld) + 16.dp
        val endPad = pad.calculateEndPadding(ld) + 16.dp
        val topPad = pad.calculateTopPadding()
        val bottomPad = pad.calculateBottomPadding()
        val lay = homeLayoutFor(maxWidth - startPad - endPad)
        val side = lay.side
        val cols = lay.cols
        val seqPerLine = lay.seqPerLine
        val listBottom = bottomPad + if (showDock) dockH + CardGap else 0.dp
        val large = maxHeight >= 560.dp

        Row(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(lay.units),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = startPad, top = topPad + 6.dp, end = if (side) PaneGap / 2 else endPad, bottom = listBottom),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                item(key = "top", span = { GridItemSpan(maxLineSpan) }, contentType = "top") {
                    HomeTop(demo, readiness, status, cs.startingMotors, { cs.startMotors(vm) }, lay.headerInline, enter)
                }
                item(key = "lib-h", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    SectionHeader("動作庫", subtitle = "點一下立即播放，右上角可複製來改", modifier = Modifier.staggerIn(enter, 2))
                }
                itemsIndexed(
                    ChoreoPresets.ALL,
                    key = { _, p -> p.id },
                    span = { _, _ -> GridItemSpan(seqPerLine) },
                    contentType = { _, _ -> "preset" },
                ) { i, p ->
                    PresetCard(
                        preset = p,
                        traj = presetTrajs[i],
                        color = c.series[p.colorIndex % c.series.size],
                        previewHeight = lay.previewH,
                        playing = playingId == p.id,
                        loading = pendingId == p.id,
                        blocked = !readiness.canPlay,
                        onPlay = { if (playingId == p.id) cs.stop(vm) else onPlay(p.toRequest()) },
                        onCopy = { copyPreset(p) },
                        modifier = Modifier.staggerIn(enter, 3 + i),
                    )
                }
                if (!side) {
                    item(key = "mine-h", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        MineHeader(sequences.size, ::newSequence, Modifier.padding(top = 4.dp).staggerIn(enter, 14))
                    }
                    if (sequences.isEmpty()) {
                        item(key = "mine-empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                            MineEmptyCard(::newSequence, Modifier.animateItem().staggerIn(enter, 15))
                        }
                    } else {
                        itemsIndexed(
                            sequences,
                            key = { _, s -> s.id },
                            span = { _, _ -> GridItemSpan(cols) },
                            contentType = { _, _ -> "seq" },
                        ) { i, s -> SeqItem(s, i, Modifier.animateItem()) }
                    }
                }
            }
            if (side) {
                LazyColumn(
                    state = sideState,
                    modifier = Modifier.width(lay.sideW + PaneGap / 2 + endPad).fillMaxHeight(),
                    contentPadding = PaddingValues(start = PaneGap / 2, top = topPad + 6.dp, end = endPad, bottom = listBottom),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "mine-h", contentType = "header") {
                        MineHeader(sequences.size, ::newSequence, Modifier.staggerIn(enter, 1))
                    }
                    if (sequences.isEmpty()) {
                        item(key = "mine-empty", contentType = "empty") {
                            MineEmptyCard(::newSequence, Modifier.animateItem().staggerIn(enter, 2))
                        }
                    } else {
                        itemsIndexed(sequences, key = { _, s -> s.id }, contentType = { _, _ -> "seq" }) { i, s ->
                            SeqItem(s, i, Modifier.animateItem())
                        }
                    }
                }
            }
        }

        // 播放列：固定在下緣（播放中 / 上傳中 / 剛結束），停止鈕一直按得到
        AnimatedVisibility(
            visible = showDock,
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = startPad, end = endPad, bottom = bottomPad)
                .fillMaxWidth(),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                PlaybackDock(
                    vm, cs, conn, limits, onPlay,
                    modifier = Modifier
                        .widthIn(max = 980.dp)
                        .onSizeChanged { dockH = with(density) { it.height.toDp() } },
                    large = large,
                )
            }
        }
    }

    renameTarget?.let { s ->
        NameDialog(
            title = "重新命名",
            initial = s.name,
            onConfirm = { name ->
                vm.saveSequence(s.copy(name = name))
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { s ->
        ConfirmDialog(
            title = "刪除「${s.name}」？",
            message = "刪除後無法復原。",
            confirmText = "刪除",
            danger = true,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = {
                vm.deleteSequence(s.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 新編排的起手式：起步、直走、停下。 */
private val STARTER_STEPS = listOf(
    SeqStep(200, 0, 500, SeqMode.RAMP),
    SeqStep(200, 0, 1500),
    SeqStep(0, 0, 500, SeqMode.RAMP),
)

/** 上緣：標題 + 車況橫幅（[inline] = 並排，窄時上下）。 */
@Composable
private fun HomeTop(
    demo: Boolean,
    readiness: Readiness,
    status: CarStatus?,
    startingMotors: Boolean,
    onStartMotors: () -> Unit,
    inline: Boolean,
    enter: () -> Float,
) {
    if (inline) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChoreoHeader(demo, Modifier.weight(1f).staggerIn(enter, 0))
            Spacer(Modifier.width(16.dp))
            ReadinessCard(readiness, status, startingMotors, onStartMotors, Modifier.weight(1.5f).staggerIn(enter, 1))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoreoHeader(demo, Modifier.staggerIn(enter, 0))
            ReadinessCard(readiness, status, startingMotors, onStartMotors, Modifier.staggerIn(enter, 1))
        }
    }
}

@Composable
private fun ChoreoHeader(demo: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("編排", style = B570Theme.type.titleL, color = c.textPrimary, maxLines = 1)
            Text("挑一段動作，讓車子跳起舞來", style = B570Theme.type.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (demo) {
            Spacer(Modifier.width(8.dp))
            StatusPill("展示模式", c.info, icon = Icons.Rounded.AutoAwesome)
        }
    }
}

// ================================================================== 車況橫幅

@Composable
private fun ReadinessCard(
    readiness: Readiness,
    status: CarStatus?,
    startingMotors: Boolean,
    onStartMotors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val (icon, tone) = when (readiness) {
        Readiness.OFFLINE -> Icons.Rounded.LinkOff to c.textMuted
        Readiness.LINK_LOST -> Icons.Rounded.SignalWifiOff to c.warn
        Readiness.NO_DATA -> Icons.Rounded.HourglassTop to c.info
        Readiness.SENSOR -> Icons.Rounded.Warning to c.danger
        Readiness.LOW_BATT -> Icons.Rounded.BatteryAlert to c.danger
        Readiness.NOT_UPRIGHT -> Icons.Rounded.Warning to c.danger
        Readiness.MOTORS_OFF -> Icons.Rounded.PowerSettingsNew to c.warn
        Readiness.READY -> Icons.Rounded.CheckCircle to c.ok
    }
    val col by animateColorAsState(tone, label = "ready")
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.l), glow = col.copy(alpha = 0.35f), glowElevation = 14.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).glass(CircleShape, fill = col.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = icon,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)).togetherWith(fadeOut() + scaleOut(targetScale = 0.6f)) },
                    label = "readyIcon",
                ) { ic -> Icon(ic, null, tint = col, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(readiness.titleZh, style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1)
                    if (readiness == Readiness.MOTORS_OFF && status != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "（${status.stopReason.labelZh}）", style = B570Theme.type.caption, color = c.textMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(readiness.reasonZh, style = B570Theme.type.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (readiness == Readiness.MOTORS_OFF) {
                Spacer(Modifier.width(8.dp))
                NeonButton("啟動", onStartMotors, compact = true, icon = Icons.Rounded.PowerSettingsNew, loading = startingMotors)
            }
        }
        if (status?.lowBattery == true && readiness != Readiness.LOW_BATT) {
            Spacer(Modifier.height(8.dp))
            StatusPill("電量偏低，動作可能不穩", c.warn, icon = Icons.Rounded.BatteryAlert)
        }
    }
}

// ================================================================== 動作卡

@Composable
private fun PresetCard(
    preset: ChoreoPreset,
    traj: SeqTrajectory,
    color: Color,
    previewHeight: Dp,
    playing: Boolean,
    loading: Boolean,
    blocked: Boolean,
    onPlay: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(B570Theme.radii.l)
    Column(
        modifier
            .pressScale(src)
            .graphicsLayer { alpha = if (blocked && !playing) 0.72f else 1f }
            .glass(shape, fill = if (playing) c.glassHigh else c.glass, glow = if (playing) color else null)
            .clip(shape)
            .clickable(src, indication = null, role = Role.Button, onClickLabel = if (playing) "停止" else "播放") { haptics.click(); onPlay() }
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.08f), c.background.copy(alpha = 0.35f)))),
        ) {
            MiniPathPreview(traj, color, Modifier.matchParentSize())
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(26.dp).glass(CircleShape, fill = color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(preset.icon, null, tint = color, modifier = Modifier.size(15.dp))
            }
            RoundIconButton(
                Icons.Rounded.ContentCopy, "複製到我的編排", onCopy,
                Modifier.align(Alignment.TopEnd).padding(4.dp), size = 32.dp, tint = c.textSecondary,
            )
            // 時長徽章（像影片縮圖）
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.background.copy(alpha = 0.72f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Timer, null, tint = c.textSecondary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text(fmtTotal(preset.totalMs), style = B570Theme.type.caption, color = c.textPrimary, maxLines = 1)
                if (preset.loops > 1) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Repeat, null, tint = c.textSecondary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("×${preset.loops}", style = B570Theme.type.caption, color = c.textPrimary, maxLines = 1)
                }
            }
            if (playing || loading) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
                    else EqualizerBadge(color)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            preset.name, style = B570Theme.type.titleS, color = c.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            if (playing) "播放中 · 點一下停止" else preset.descZh,
            style = B570Theme.type.caption,
            color = if (playing) color else c.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** 播放中的小等化器（只在繪製階段讀動畫值）。 */
@Composable
private fun EqualizerBadge(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "eqP")
    Spacer(
        modifier.size(16.dp).drawBehind {
            val w = size.width / 5f
            val r = CornerRadius(w / 2f)
            for (i in 0 until 3) {
                val ph = p * 2f * PI.toFloat() + i * 2.1f
                val h = size.height * (0.3f + 0.7f * (0.5f + 0.5f * sin(ph)))
                drawRoundRect(color, Offset(i * 2f * w, size.height - h), Size(w, h), r)
            }
        },
    )
}

// ================================================================== 我的編排

@Composable
private fun MineHeader(count: Int, onNew: () -> Unit, modifier: Modifier = Modifier) {
    SectionHeader(
        "我的編排",
        subtitle = if (count == 0) "自己排一段專屬動作" else "共 $count 段",
        modifier = modifier,
        trailing = {
            NeonButton("新增", onNew, style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.Add, compact = true)
        },
    )
}

/** 還沒有自己的編排（橫向：一列，省高度）。 */
@Composable
private fun MineEmptyCard(onNew: () -> Unit, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.l))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).glass(CircleShape, glow = c.accent.copy(alpha = 0.5f), glowElevation = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.LibraryMusic, null, tint = c.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("還沒有自己的編排", style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "從零開始排步驟，或在動作庫按複製，改成你喜歡的樣子。",
                style = B570Theme.type.caption, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        NeonButton("新增編排", onNew, style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.Add, compact = true)
    }
}

@Composable
private fun SequenceRow(
    seq: SavedSequence,
    limits: SimLimits,
    playing: Boolean,
    loading: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val color = c.series[Math.floorMod(seq.id.hashCode(), c.series.size)]
    val traj = remember(seq.steps, seq.loops, limits) { simulate(seq.steps, seq.loops, limits, maxPoints = 240) }
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(B570Theme.radii.l)
    var menu by remember { mutableStateOf(false) }
    val loopsText = when {
        seq.loops == 0 -> " · 無限循環"
        seq.loops > 1 -> " · ×${seq.loops}"
        else -> ""
    }
    val meta = "${seq.steps.size} 步 · ${fmtTotal(seq.totalDurationMs)}$loopsText"
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(src, 0.98f)
            .glass(shape, fill = if (playing) c.glassHigh else c.glass, glow = if (playing) color else null)
            .clip(shape)
            .clickable(src, indication = null, role = Role.Button, onClickLabel = "編輯") { haptics.click(); onOpen() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.1f), c.background.copy(alpha = 0.35f)))),
        ) {
            MiniPathPreview(traj, color, Modifier.matchParentSize(), running = playing, minExtentMm = 400f)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(seq.name, style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(meta, style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (playing) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EqualizerBadge(color, Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("播放中", style = B570Theme.type.caption, color = color, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        PlayStopButton(playing, loading, color, if (playing) onStop else onPlay)
        Spacer(Modifier.width(4.dp))
        RoundIconButton(Icons.Rounded.MoreVert, "更多", { menu = true }, size = 40.dp, fill = Color.Transparent, tint = c.textSecondary)
    }

    if (menu) {
        ActionSheetDialog(
            title = seq.name,
            subtitle = meta.removePrefix(" · "),
            actions = listOf(
                SheetAction("編輯", Icons.Rounded.Edit, onClick = onOpen),
                SheetAction("重新命名", Icons.Rounded.DriveFileRenameOutline, onClick = onRename),
                SheetAction("複製", Icons.Rounded.ContentCopy, onClick = onDuplicate),
                SheetAction("刪除", Icons.Rounded.DeleteOutline, danger = true, onClick = onDelete),
            ),
            onDismiss = { menu = false },
        )
    }
}

/** 圓形播放 / 停止鈕。 */
@Composable
private fun PlayStopButton(playing: Boolean, loading: Boolean, color: Color, onClick: () -> Unit) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (playing) c.danger else color, label = "playBg")
    Box(
        Modifier
            .size(44.dp)
            .pressScale(src, 0.88f)
            .glass(CircleShape, fill = bg.copy(alpha = 0.9f), glow = bg, glowElevation = 12.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(bg, lerpColor(bg, c.accent2, 0.35f))))
            .clickable(src, indication = null, enabled = !loading, role = Role.Button) { haptics.click(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(18.dp), color = c.onAccent, strokeWidth = 2.dp)
            playing -> Icon(Icons.Rounded.Stop, "停止", tint = Color.White, modifier = Modifier.size(22.dp))
            else -> Icon(Icons.Rounded.PlayArrow, "播放", tint = c.onAccent, modifier = Modifier.size(24.dp))
        }
    }
}
