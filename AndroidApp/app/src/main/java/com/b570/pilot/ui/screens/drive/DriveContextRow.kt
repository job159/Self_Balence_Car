package com.b570.pilot.ui.screens.drive

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Cyclone
import androidx.compose.material.icons.rounded.Dangerous
import androidx.compose.material.icons.rounded.FrontHand
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.WifiTetheringError
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.protocol.SeqMode
import com.b570.pilot.protocol.SeqStep
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.ui.components.GamepadButton
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass

// ------------------------------------------------------------------ 內建快捷動作

/** 駕駛頁快捷動作：短編排（|speed| ≤ 300 mm/s），一鍵載入並播放一次。 */
internal data class QuickAction(
    val id: String,
    val labelZh: String,
    /** 窄中控台用的短標籤（2 字）。 */
    val shortZh: String,
    val icon: ImageVector,
    val steps: List<SeqStep>,
) {
    val totalMs: Long get() = SeqStep.totalDurationMs(steps)
}

internal val QuickActions: List<QuickAction> = listOf(
    // 原地轉圈：0.5 s 加速 → 2.6 s 定速自轉 → 0.5 s 收尾
    QuickAction(
        "spin", "原地轉圈", "轉圈", Icons.Rounded.Cyclone,
        listOf(
            SeqStep(speed = 0, turn = 750, durMs = 500, mode = SeqMode.RAMP),
            SeqStep(speed = 0, turn = 750, durMs = 2600, mode = SeqMode.HOLD),
            SeqStep(speed = 0, turn = 0, durMs = 500, mode = SeqMode.RAMP),
        ),
    ),
    // 搖擺：原地左右扭動，0.8 s 一個週期 × 5（結束時剛好回到 0）
    QuickAction(
        "sway", "搖擺", "搖擺", Icons.Rounded.Waves,
        listOf(
            SeqStep(speed = 0, turn = 650, durMs = 4000, mode = SeqMode.SINE_TURN, period20ms = 40),
        ),
    ),
    // 點頭：前後小幅來回，0.9 s 一個週期 × 4，車身跟著前後點頭
    QuickAction(
        "nod", "點頭", "點頭", Icons.Rounded.SwapVert,
        listOf(
            SeqStep(speed = 160, turn = 0, durMs = 3600, mode = SeqMode.SINE_SPEED, period20ms = 45),
            SeqStep(speed = 0, turn = 0, durMs = 300, mode = SeqMode.RAMP),
        ),
    ),
)

/**
 * 快捷動作播放進度 0..1（以開始時間推算，離開再回來也能接續）；不是駕駛頁開始的編排 → 0。
 * 回傳的 State 只在繪製階段讀取。
 */
@Composable
internal fun rememberQuickProgress(playing: QuickAction?): State<Float> {
    val progress = remember { Animatable(0f) }
    val id = playing?.id
    LaunchedEffect(id) {
        val a = playing
        if (a == null || QuickPlayMemory.id != a.id) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        val total = a.totalMs.coerceAtLeast(1L)
        val elapsed = (SystemClock.elapsedRealtime() - QuickPlayMemory.startMs).coerceIn(0L, total)
        progress.snapTo(elapsed.toFloat() / total)
        progress.animateTo(1f, tween((total - elapsed).toInt(), easing = LinearEasing))
    }
    return progress.asState()
}

// ------------------------------------------------------------------ 中控台快捷列（雙搖桿 / 體感）

/** 快捷動作列放不下完整標籤時依序退讓：短標籤 → 緊湊 → 只剩圖示（不會被切掉一半）。 */
private enum class ChipFit { FULL, SHORT, SHORT_COMPACT, ICON }

/**
 * 中控台的快捷動作列：原地轉圈 / 搖擺 / 點頭 / 更多。依實際寬度量字決定標籤長短，整列一定完整顯示。
 * @param dim 目前無法播放（例如馬達未啟動）→ 半透明，點了會提示原因
 */
@Composable
internal fun QuickActionRow(
    busyId: String?,
    playingId: String?,
    dim: Boolean,
    onPlay: (QuickAction) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    showMore: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val style = B570Theme.type.label
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val avail = constraints.maxWidth
        val fit = remember(avail, style, density, showMore) {
            val more = if (showMore) listOf("更多") else emptyList()
            fun total(labels: List<String>, compact: Boolean): Int = with(density) {
                val chrome = (if (compact) 7.dp * 2 + 14.dp + 3.dp else 10.dp * 2 + 16.dp + 5.dp).roundToPx()
                val gap = (if (compact) 4.dp else 6.dp).roundToPx()
                labels.sumOf { chrome + measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } + gap * (labels.size - 1)
            }
            when {
                total(QuickActions.map { it.labelZh } + more, compact = false) <= avail -> ChipFit.FULL
                total(QuickActions.map { it.shortZh } + more, compact = false) <= avail -> ChipFit.SHORT
                total(QuickActions.map { it.shortZh } + more, compact = true) <= avail -> ChipFit.SHORT_COMPACT
                else -> ChipFit.ICON
            }
        }
        val compact = fit == ChipFit.SHORT_COMPACT || fit == ChipFit.ICON
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickActions.forEach { a ->
                key(a.id) {
                    ActionChip(
                        label = when (fit) {
                            ChipFit.FULL -> a.labelZh
                            ChipFit.ICON -> null
                            else -> a.shortZh
                        },
                        description = a.labelZh,
                        icon = a.icon,
                        onClick = { onPlay(a) },
                        dim = dim,
                        loading = busyId == a.id,
                        active = playingId == a.id,
                        enabled = busyId == null,
                        compact = compact,
                    )
                }
            }
            // 窄中控台：「更多」省略（上緣列已有「編排」捷徑，同一個動作）
            if (showMore) {
                ActionChip(
                    label = if (fit == ChipFit.ICON) null else "更多",
                    description = "更多動作",
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = onMore,
                    dim = false,
                    loading = false,
                    active = false,
                    enabled = true,
                    subtle = true,
                    compact = compact,
                )
            }
        }
    }
}

/** @param label null = 只顯示圖示（[description] 給 TalkBack） */
@Composable
private fun ActionChip(
    label: String?,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    dim: Boolean,
    loading: Boolean,
    active: Boolean,
    enabled: Boolean,
    subtle: Boolean = false,
    compact: Boolean = false,
) {
    val c = B570Theme.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.93f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "chip",
    )
    val shape = RoundedCornerShape(50)
    val iconSize = if (compact) 14.dp else 16.dp
    Row(
        Modifier
            .height(36.dp)
            .then(if (label == null) Modifier.width(40.dp) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (dim) 0.5f else 1f }
            .clip(shape)
            .background(if (pressed || active) c.glassHigh else c.glass)
            .border(1.dp, if (loading || active) c.accent else c.glassBorder, shape)
            .clickable(src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (label == null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = if (label == null) 0.dp else if (compact) 7.dp else 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(iconSize - 2.dp), color = c.accent, strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = null, tint = if (subtle) c.textSecondary else c.accent, modifier = Modifier.size(iconSize))
            }
        }
        if (label != null) {
            Spacer(Modifier.width(if (compact) 3.dp else 5.dp))
            Text(label, style = B570Theme.type.label, color = if (subtle) c.textSecondary else c.textPrimary, maxLines = 1, softWrap = false)
        }
    }
}

// ------------------------------------------------------------------ ABXY 動作鍵（單搖桿右手區）

/** ABXY 菱形的尺寸（單元測試 / 版面共用）：直徑 [d] 的圓鍵 + 18 dp 標籤。 */
internal object ActionPadGeometry {
    const val GAP_FACTOR = 0.15f
    const val ROW_FACTOR = 0.78f
    val LabelH = 18.dp

    fun width(d: Dp): Dp = d * (3f + 2f * GAP_FACTOR)
    fun height(d: Dp): Dp = d * (2f * ROW_FACTOR) + d + LabelH

    /** 在 [w] × [h] 內放得下的最大直徑（夾在 [min]..[max]）。 */
    fun diameterFor(w: Dp, h: Dp, min: Dp = 40.dp, max: Dp = 78.dp): Dp =
        minOf(w / (3f + 2f * GAP_FACTOR), (h - LabelH) / (1f + 2f * ROW_FACTOR)).coerceIn(min, max)
}

/**
 * 單搖桿模式的右手動作鍵（像手把的 A / B / X / Y 菱形）：
 * A（下）原地轉圈、B（右）搖擺、X（左）點頭、Y（上）更多 → 編排。
 * 播放中的動作外環顯示進度；馬達未啟動 / 未連線時變暗（仍可點，點了跳提示說明原因）。
 */
@Composable
internal fun QuickActionPad(
    busyId: String?,
    playingId: String?,
    dim: Boolean,
    progress: State<Float>,
    onPlay: (QuickAction) -> Unit,
    onMore: () -> Unit,
    diameter: Dp,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val d = diameter
    val gap = d * ActionPadGeometry.GAP_FACTOR
    val row = d * ActionPadGeometry.ROW_FACTOR
    val colors = listOf(c.ok, c.accent2, c.info)
    val glyphs = listOf("A", "B", "X")
    // (x, y) 左上角：A 下、B 右、X 左、Y 上
    val pos = listOf(
        (d + gap) to row * 2f,
        (d + gap) * 2f to row,
        0.dp to row,
    )
    Box(modifier.size(ActionPadGeometry.width(d), ActionPadGeometry.height(d))) {
        QuickActions.forEachIndexed { i, a ->
            val (x, y) = pos[i]
            val playing = playingId == a.id
            GamepadButton(
                icon = a.icon,
                label = a.labelZh,
                onClick = { onPlay(a) },
                modifier = Modifier.offset(x = x, y = y).width(d).wrapContentWidth(unbounded = true),
                diameter = d,
                color = colors[i],
                enabled = busyId == null,
                active = playing,
                loading = busyId == a.id,
                dimmed = dim,
                glyph = glyphs[i],
                progress = if (playing) ({ progress.value }) else null,
            )
        }
        GamepadButton(
            icon = Icons.Rounded.AutoAwesome,
            label = "更多",
            onClick = onMore,
            modifier = Modifier.offset(x = d + gap, y = 0.dp).width(d).wrapContentWidth(unbounded = true),
            diameter = d,
            color = c.warn,
            glyph = "Y",
        )
    }
}

// ------------------------------------------------------------------ 情境提示

/**
 * 中控台下方的情境提示（緊急停止、被拿起、傾倒、低電量、失聯保護、編排播放中）。
 * 編排播放中：顯示步驟 / 循環、「停止」鈕；若是快捷動作，底色顯示播放進度。
 */
@Composable
internal fun NoticeBanner(
    notice: DriveNotice,
    slow: State<Telemetry?>,
    seqTotal: Int,
    quick: QuickAction?,
    progress: State<Float>,
    stopping: Boolean,
    onStopSequence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val col = when (notice) {
        DriveNotice.ESTOP, DriveNotice.FALL, DriveNotice.LOW_BATT, DriveNotice.IMU -> c.danger
        DriveNotice.PICKUP, DriveNotice.FAILSAFE -> c.warn
        DriveNotice.SEQUENCE, DriveNotice.NONE -> c.accent
    }
    val icon = when (notice) {
        DriveNotice.ESTOP -> Icons.Rounded.Dangerous
        DriveNotice.PICKUP -> Icons.Rounded.FrontHand
        DriveNotice.FALL, DriveNotice.IMU -> Icons.Rounded.ReportProblem
        DriveNotice.LOW_BATT -> Icons.Rounded.BatteryAlert
        DriveNotice.FAILSAFE -> Icons.Rounded.WifiTetheringError
        DriveNotice.SEQUENCE, DriveNotice.NONE -> quick?.icon ?: Icons.Rounded.AutoAwesome
    }
    val showProgress = notice == DriveNotice.SEQUENCE && quick != null
    val shape = RoundedCornerShape(14.dp)
    val fill = col.copy(alpha = 0.16f)
    Row(
        modifier
            .fillMaxSize()
            .glass(shape, fill = col.copy(alpha = 0.12f), glow = col.copy(alpha = 0.5f), glowElevation = 10.dp)
            .clip(shape)
            .drawBehind {
                if (!showProgress) return@drawBehind
                val p = progress.value
                if (p > 0f) drawRect(fill, size = Size(size.width * p.coerceAtMost(1f), size.height))
            }
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = col, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (notice == DriveNotice.SEQUENCE) "編排播放中" + (quick?.let { " · ${it.labelZh}" } ?: "") else notice.titleZh,
                style = B570Theme.type.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (notice == DriveNotice.SEQUENCE) {
                SequenceProgressText(slow, seqTotal)
            } else {
                Text(notice.bodyZh, style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (notice == DriveNotice.SEQUENCE) {
            Spacer(Modifier.width(8.dp))
            NeonButton("停止", onStopSequence, style = NeonButtonStyle.SECONDARY, compact = true, loading = stopping)
        }
    }
}

@Composable
private fun SequenceProgressText(slow: State<Telemetry?>, total: Int) {
    val t = slow.value
    val step = t?.seqStep
    val loop = (t?.seqLoop ?: 0) + 1
    val progress = when {
        step == null -> "準備中…"
        total > 0 -> "第 ${step + 1} / $total 步 · 第 $loop 輪"
        else -> "第 ${step + 1} 步"
    }
    // 接管提示放前面（窄中控台被省略的是步數）
    Text(
        "推動搖桿即可接管 · $progress",
        style = B570Theme.type.caption,
        color = B570Theme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
