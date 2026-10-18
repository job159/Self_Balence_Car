package com.b570.pilot.ui.screens.choreo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.StatusPill
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val PARTICLES = 18

/**
 * 車上播放進度的本地時鐘：車子只回報「目前第幾步 / 第幾輪」，步驟內的經過時間用影格時鐘估算。
 * [step] / [loop] 是一般欄位（組合期間寫入、繪製期間讀取），[frame] / [stepStart] 只在繪製階段讀取 → 不觸發重組。
 * 停止後凍結在最後的步驟位置。
 */
@Stable
internal class StepClock {
    var step = -1
    var loop = 0

    /** 影格時鐘最後看到的步驟（與 step 不同 = 剛換步驟，經過時間視為 0）。 */
    var clockStep = Int.MIN_VALUE
    var clockLoop = Int.MIN_VALUE
    val frame = mutableLongStateOf(0L)
    val stepStart = mutableLongStateOf(0L)

    /** 單趟內的目前時間（秒）；< 0 = 還沒開始。只在繪製階段呼叫。 */
    fun progressInLoop(traj: SeqTrajectory, done: Boolean): Float {
        // 一律先讀影格時鐘：繪製一定訂閱到它，換步驟後的第一個影格也會重畫
        val now = frame.longValue
        return when {
            done -> traj.loopSec
            step < 0 || traj.stepCount == 0 -> -1f
            else -> {
                val s = step.coerceIn(0, traj.stepCount - 1)
                val a = traj.stepStartSec[s]
                val dur = traj.stepStartSec[s + 1] - a
                val fresh = step == clockStep && loop == clockLoop
                if (fresh) a + ((now - stepStart.longValue) / 1e9f).coerceIn(0f, dur) else a
            }
        }
    }

    /** 整段軌跡上的目前時間（秒，含循環）。只在繪製階段呼叫。 */
    fun playhead(traj: SeqTrajectory, done: Boolean): Float {
        if (done) return traj.totalSec
        val loopIdx = if (traj.simLoops > 0) loop % traj.simLoops else 0
        return loopIdx * traj.loopSec + progressInLoop(traj, false).coerceAtLeast(0f)
    }
}

/**
 * 依車上的 [tick] 推進 [StepClock]；[reset] = 上傳中（回到起點）。
 * 影格迴圈只在車上播放中執行。
 */
@Composable
internal fun rememberStepClock(tick: PlayTick, reset: Boolean = false): StepClock {
    val clock = remember { StepClock() }
    if (tick.playing && tick.step >= 0) {
        clock.step = tick.step
        clock.loop = tick.loop
    }
    if (reset) {
        clock.step = -1
        clock.loop = 0
    }
    LaunchedEffect(clock, tick.playing) {
        if (tick.playing) {
            while (true) {
                val now = withFrameNanos { it }
                if (clock.step != clock.clockStep || clock.loop != clock.clockLoop) {
                    clock.clockStep = clock.step
                    clock.clockLoop = clock.loop
                    clock.stepStart.longValue = now
                }
                clock.frame.longValue = now
            }
        }
    }
    return clock
}

/**
 * 橫向播放列（固定在編排首頁下緣，像音樂播放器的「正在播放」）：
 * 即時軌跡縮圖、狀態、名稱、目前步驟、循環、步驟進度條、大大的紅色停止鈕。
 * 完成會放煙火、被中止會抖一下；完成 / 中止後顯示「再播一次 / 關閉」。
 * 高頻遙測只在這裡收集，而且只在步驟改變時重組。
 *
 * @param large 平板：縮圖較大
 */
@Composable
internal fun PlaybackDock(
    vm: AppViewModel,
    cs: ChoreoState,
    conn: ConnectionState,
    limits: SimLimits,
    onReplay: (PlayRequest) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val c = B570Theme.colors
    val tickFlow = remember(vm) { vm.telemetry.map { PlayTick.of(it) }.distinctUntilChanged() }
    val tick by tickFlow.collectAsStateWithLifecycle(PlayTick.of(vm.telemetry.value))
    val loaded by vm.loadedSequence.collectAsStateWithLifecycle()
    // 有編排正在播放時，舊的結果不再顯示
    val outcome = cs.outcome.takeIf { !tick.playing }
    val uploading = cs.busy
    val linkLost = conn is ConnectionState.LinkLost
    val req = if (uploading) cs.pending else cs.nowPlaying
    // 車上實際的步驟以 loadedSequence 為準；名稱與循環取自最後一次播放
    val steps = if (uploading) req?.steps.orEmpty() else loaded.ifEmpty { req?.steps.orEmpty() }
    val loops = req?.loops ?: 1
    val name = req?.name ?: "編排"
    val traj = remember(steps, loops, limits) { simulate(steps, loops, limits, 600) }
    val colors = remember(steps, c) { steps.map { it.kind.color(c) } }
    val n = steps.size

    val clock = rememberStepClock(tick, reset = uploading)
    val done = outcome?.done == true
    val progressInLoop: () -> Float = { clock.progressInLoop(traj, done) }
    val playhead: () -> Float = { clock.playhead(traj, done) }

    val (stateText, stateColor) = when {
        uploading -> "上傳中…" to c.info
        outcome != null && done -> "完成！" to c.ok
        outcome != null -> when (outcome.arg) {
            1 -> "手動接管" to c.warn
            2 -> "連線中斷" to c.warn
            3 -> "馬達停止" to c.warn
            else -> "已停止" to c.info
        }
        linkLost -> "訊號中斷" to c.warn
        else -> "播放中" to c.accent
    }
    val glow by animateColorAsState(stateColor, label = "dockGlow")

    // 完成：煙火；中止：抖動
    val burst = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(outcome?.id) {
        val o = outcome ?: return@LaunchedEffect
        if (o.done) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(1500, easing = LinearOutSlowInEasing))
        } else if (o.arg in 1..3) {
            shake.animateTo(0f, keyframes {
                durationMillis = 460
                -14f at 50
                12f at 120
                -9f at 190
                6f at 260
                -3f at 330
            })
        }
    }
    val particles = remember {
        FloatArray(PARTICLES * 2) { k ->
            val i = k / 2
            if (k % 2 == 0) (i.toFloat() / PARTICLES) * 2f * PI.toFloat() + ((i * 7) % 5) * 0.09f
            else 0.55f + ((i * 37) % 11) / 11f * 0.55f
        }
    }
    val series = c.series
    val ringColor = c.ok
    val previewSize: Dp = if (large) 84.dp else 64.dp

    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shake.value }
            .glass(RoundedCornerShape(B570Theme.radii.l), fill = c.surface.copy(alpha = 0.96f), glow = glow.copy(alpha = 0.6f), glowElevation = 20.dp)
            // 擋住下方的動作卡（點到播放列空白處不會誤觸卡片）
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .drawWithContent {
                drawContent()
                val p = burst.value
                if (p > 0f && p < 1f) {
                    val cx = 12.dp.toPx() + previewSize.toPx() / 2f
                    val cy = size.height / 2f
                    val maxD = size.width * 0.3f
                    val ease = 1f - (1f - p) * (1f - p)
                    drawCircle(ringColor.copy(alpha = 0.35f * (1f - p)), maxD * ease * 0.6f, Offset(cx, cy), style = Stroke(2.dp.toPx()))
                    val r = 3.5.dp.toPx() * (1f - p * 0.5f)
                    val fall = p * p * 24.dp.toPx()
                    for (i in 0 until PARTICLES) {
                        val ang = particles[i * 2]
                        val d = maxD * particles[i * 2 + 1] * ease
                        drawCircle(
                            series[i % series.size].copy(alpha = 1f - p), r,
                            Offset(cx + cos(ang) * d, cy + sin(ang) * d * 0.35f + fall),
                        )
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(previewSize)
                .clip(RoundedCornerShape(B570Theme.radii.s))
                .background(c.background.copy(alpha = 0.5f)),
        ) {
            PathPreview(traj, colors, playhead, Modifier.matchParentSize(), minExtentMm = 500f, showGrid = true, small = true)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(stateText, stateColor, pulsing = !uploading && outcome == null && !linkLost)
                Spacer(Modifier.width(8.dp))
                Text(
                    name, style = B570Theme.type.titleS, color = c.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                val loopText = when {
                    uploading || n == 0 -> "$n 步"
                    loops == 0 -> "第 ${clock.loop.coerceAtLeast(0) + 1} 輪 · 無限"
                    loops > 1 -> "第 ${(clock.loop + 1).coerceIn(1, loops)} / $loops 輪"
                    else -> "共 ${fmtTotal((traj.totalSec * 1000f).toLong())}"
                }
                Spacer(Modifier.width(8.dp))
                if (loops != 1 && !uploading) {
                    Icon(Icons.Rounded.Repeat, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(loopText, style = B570Theme.type.label, color = c.textSecondary, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            val shownStep = when {
                done -> -2
                linkLost && outcome == null && !uploading -> -3
                else -> clock.step
            }
            AnimatedContent(
                targetState = shownStep,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn(tween(200))).togetherWith(slideOutVertically { -it / 2 } + fadeOut(tween(150)))
                },
                label = "step",
            ) { s ->
                when {
                    s == -3 -> CurrentStepLine("車子收不到訊號時會自動煞停並中止編排", null, c.warn, null, Icons.Rounded.SignalWifiOff)
                    s == -2 -> CurrentStepLine("全部 $n 步都完成了", null, c.ok, null)
                    s in steps.indices -> {
                        val st = steps[s]
                        val k = st.kind
                        CurrentStepLine("第 ${s + 1} / $n 步 · ${k.labelZh}", st.summaryZh(), k.color(c), k)
                    }
                    uploading -> CurrentStepLine("正在把 $n 個步驟傳到車上…", null, c.info, null)
                    else -> CurrentStepLine("準備開始…", null, c.textSecondary, null)
                }
            }
            Spacer(Modifier.height(8.dp))
            StepProgressStrip(traj.stepStartSec, colors, progressInLoop, Modifier.fillMaxWidth().height(6.dp))
        }
        Spacer(Modifier.width(12.dp))
        if (outcome != null && !tick.playing && !uploading) {
            NeonButton(
                "再播一次", { req?.let(onReplay) }, Modifier.height(48.dp),
                icon = Icons.Rounded.Replay, enabled = req != null,
            )
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Rounded.Close, "關閉", { cs.clearOutcome(outcome.id) }, size = 44.dp, tint = c.textSecondary)
        } else {
            NeonButton(
                text = "停止",
                onClick = { cs.stop(vm) },
                modifier = Modifier.widthIn(min = 112.dp).height(52.dp),
                style = NeonButtonStyle.DANGER,
                icon = Icons.Rounded.Stop,
                enabled = !uploading && !linkLost,
                loading = cs.stopping,
            )
        }
    }
}

/** 一行的目前步驟：圖示 + 標題 + 細節（過長省略）。 */
@Composable
private fun CurrentStepLine(
    title: String,
    detail: String?,
    color: Color,
    kind: MoveKind?,
    icon: ImageVector? = kind?.icon,
) {
    val c = B570Theme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title, style = B570Theme.type.label, color = if (kind != null) c.textPrimary else color,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (detail == null) Modifier.weight(1f) else Modifier,
        )
        if (detail != null) {
            Text(
                " · $detail", style = B570Theme.type.caption, color = c.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
    }
}
