package com.b570.pilot.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dangerous
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import com.b570.pilot.ui.theme.neonGlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ================================================================================================ 肩鍵

/** 肩鍵外形：LEFT / RIGHT = 外側上角較圓（像手把 L1 / R1），CENTER = 對稱膠囊。 */
enum class ShoulderSide { LEFT, RIGHT, CENTER }

/**
 * 肩鍵（遙控器上緣左右角的寬扳機鍵）：立體按壓（按下時鍵面下沉、底座唇邊變薄）、霓虹光暈、觸覺。
 *
 * - 一般模式（[holdToConfirmMs] = 0）：放開時在按鍵範圍內 → [onClick]。
 * - 長按確認（[holdToConfirmMs] > 0）：按住期間鍵面由左往右填滿，填滿才觸發 [onClick]（確認震動）；
 *   提早放開或滑出 → 取消（拒絕震動）。TalkBack 的雙擊視為確認（直接觸發）。
 * - [onPressedChange]：按下 / 放開（含取消）時同步呼叫，可做「按住才動作」的瞬時鍵。
 *
 * @param active 亮燈狀態（例如功能開啟中）：填滿強調色漸層 + 光暈
 * @param badge 外側上角的小字（例如「L1」「R1」）
 * @param height 鍵高（含 4 dp 立體唇邊），寬度由 Modifier 決定（建議 ≥ 120 dp）
 */
@Composable
fun ShoulderButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    side: ShoulderSide = ShoulderSide.CENTER,
    color: Color = B570Theme.colors.accent,
    enabled: Boolean = true,
    active: Boolean = false,
    loading: Boolean = false,
    holdToConfirmMs: Int = 0,
    badge: String? = null,
    height: Dp = 48.dp,
    onPressedChange: ((Boolean) -> Unit)? = null,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val hapticsNow by rememberUpdatedState(haptics)
    val click by rememberUpdatedState(onClick)
    val pressedCb by rememberUpdatedState(onPressedChange)
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val usable = enabled && !loading
    val lip = 4.dp
    val depth by animateDpAsState(if (pressed) lip - 1.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh), label = "depth")
    val glow by animateFloatAsState(if (active) 1f else if (pressed) 0.7f else 0f, label = "glow")
    val big = 22.dp
    val small = 10.dp
    val shape: Shape = when (side) {
        ShoulderSide.LEFT -> RoundedCornerShape(topStart = big, topEnd = small, bottomEnd = small, bottomStart = small)
        ShoulderSide.RIGHT -> RoundedCornerShape(topStart = small, topEnd = big, bottomEnd = small, bottomStart = small)
        ShoulderSide.CENTER -> RoundedCornerShape(50)
    }
    val fg = if (active) c.onAccent else if (usable) c.textPrimary else c.textMuted

    Box(
        modifier
            .height(height)
            .graphicsLayer { alpha = if (usable || loading) 1f else 0.45f }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = text
                if (!usable) disabled()
                onClick(label = text) {
                    if (usable) { click(); true } else false
                }
            }
            .pointerInput(usable, holdToConfirmMs) {
                if (!usable) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pressed = true
                    pressedCb?.invoke(true)
                    hapticsNow.tick()
                    var fired = false
                    var holdJob: Job? = null
                    try {
                        if (holdToConfirmMs > 0) {
                            holdJob = scope.launch {
                                progress.snapTo(0f)
                                progress.animateTo(1f, tween(holdToConfirmMs, easing = LinearEasing))
                                fired = true
                                hapticsNow.confirm()
                                click()
                            }
                        }
                        val up = waitForUpOrCancellation()
                        if (holdToConfirmMs > 0) {
                            if (!fired) {
                                holdJob?.cancel()
                                hapticsNow.reject()
                            }
                        } else if (up != null) {
                            up.consume()
                            hapticsNow.click()
                            click()
                        }
                    } finally {
                        pressed = false
                        pressedCb?.invoke(false)
                        if (holdToConfirmMs > 0) {
                            holdJob?.cancel()
                            scope.launch { progress.animateTo(0f, tween(220)) }
                        }
                    }
                }
            },
    ) {
        // 立體唇邊（底座）
        Box(
            Modifier
                .matchParentSize()
                .padding(top = lip)
                .background(lerpColor(color, Color.Black, 0.72f).copy(alpha = 0.9f), shape),
        )
        // 鍵面
        Box(
            Modifier
                .fillMaxWidth()
                .height(height - lip)
                .offset { IntOffset(0, depth.roundToPx()) }
                .neonGlowIf(glow > 0.01f, color.copy(alpha = 0.6f * glow), shape)
                .clip(shape)
                .drawWithCache {
                    val face = Brush.verticalGradient(listOf(c.glassHigh, c.surface))
                    val lit = Brush.horizontalGradient(listOf(color, lerpColor(color, c.accent2, 0.5f)))
                    val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.5f to Color.Transparent)
                    val hold = Brush.horizontalGradient(listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0.55f)))
                    onDrawBehind {
                        drawRect(face)
                        if (glow > 0.01f) drawRect(lit, alpha = if (active) 1f else 0.25f * glow)
                        val p = progress.value
                        if (p > 0f) drawRect(hold, size = Size(size.width * p, size.height))
                        drawRect(sheen)
                        if (pressed) drawRect(Color.Black.copy(alpha = 0.18f))
                    }
                }
                .border(1.dp, if (active) color.copy(alpha = 0.9f) else color.copy(alpha = 0.45f), shape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = fg, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                } else if (icon != null) {
                    Icon(icon, null, tint = if (active) fg else color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, style = B570Theme.type.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (badge != null) {
                Text(
                    badge,
                    style = B570Theme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = (if (active) fg else color).copy(alpha = 0.75f),
                    maxLines = 1,
                    modifier = Modifier
                        .align(if (side == ShoulderSide.RIGHT) Alignment.TopEnd else Alignment.TopStart)
                        .padding(top = 2.dp),
                )
            }
        }
    }
}

// ================================================================================================ 圓形動作鍵

/**
 * 圓形動作鍵（像手把的 A / B / X / Y）：圓形玻璃鍵 + 圖示，下方小標籤；按下縮放 + 觸覺。
 *
 * @param label 鍵下方的小字（1 行，過長會省略）；null = 不顯示
 * @param diameter 圓鍵直徑（建議 52–72 dp）；標籤在圓鍵下方，總高 = 直徑 + 約 18 dp
 * @param active 播放中 / 開啟中：旋轉的發光環 + 呼吸光暈
 * @param dimmed 看得到但較暗（例如「需先啟動平衡」）；仍可點，讓畫面能跳提示說明原因
 * @param glyph 圓鍵右上角的小字母（"A" / "B" / "X" / "Y"）
 * @param progress 外環進度 0..1（**在繪製階段呼叫**，可讀高頻 State，例如編排播放進度）；null = 不畫
 * @param onLongClick 長按（重震動）；null = 無長按
 */
@Composable
fun GamepadButton(
    icon: ImageVector,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 60.dp,
    color: Color = B570Theme.colors.accent,
    enabled: Boolean = true,
    active: Boolean = false,
    loading: Boolean = false,
    dimmed: Boolean = false,
    glyph: String? = null,
    progress: (() -> Float)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val hapticsNow by rememberUpdatedState(haptics)
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val onLongPress: ((Offset) -> Unit)? =
        if (onLongClick != null) ({ _: Offset -> hapticsNow.heavy(); longClick?.invoke() }) else null
    var pressed by remember { mutableStateOf(false) }
    val usable = enabled && !loading
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "scale")
    val contentAlpha = when {
        !enabled -> 0.35f
        dimmed -> 0.55f
        else -> 1f
    }
    Column(
        modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (label != null) contentDescription = label
                if (active) stateDescription = "進行中"
                if (!usable) disabled()
                onClick(label = label) {
                    if (usable) { click(); true } else false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(diameter)
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = contentAlpha }
                .pointerInput(usable, onLongClick != null) {
                    if (!usable) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            hapticsNow.tick()
                            try { tryAwaitRelease() } finally { pressed = false }
                        },
                        onLongPress = onLongPress,
                        onTap = { hapticsNow.click(); click() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (active) ActiveRing(color, Modifier.matchParentSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(diameter * 0.06f)
                    .neonGlowIf(active || pressed, color.copy(alpha = 0.7f), CircleShape)
                    .clip(CircleShape)
                    .drawWithCache {
                        val fill = Brush.radialGradient(
                            listOf(lerpColor(c.glassHigh, color, if (active) 0.45f else 0.16f), c.surface, lerpColor(c.surface, Color.Black, 0.4f)),
                            Offset(size.width * 0.38f, size.height * 0.32f),
                            size.minDimension * 0.75f,
                        )
                        val rim = Stroke(1.5.dp.toPx())
                        val inner = Stroke(1.dp.toPx())
                        onDrawBehind {
                            drawCircle(fill)
                            drawCircle(color.copy(alpha = if (active) 0.95f else 0.55f), radius = size.minDimension / 2f - rim.width / 2f, style = rim)
                            drawCircle(Color.White.copy(alpha = 0.10f), radius = size.minDimension / 2f - 4.dp.toPx(), style = inner)
                            if (pressed) drawCircle(Color.Black.copy(alpha = 0.22f))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(diameter * 0.38f), color = color, strokeWidth = 2.dp)
                } else {
                    Icon(icon, null, tint = if (active) Color.White else color, modifier = Modifier.size(diameter * 0.42f))
                }
            }
            if (progress != null) {
                Box(
                    Modifier.matchParentSize().drawWithCache {
                        val st = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                        val inset = st.width / 2f
                        val arcSize = Size(size.width - st.width, size.height - st.width)
                        onDrawBehind {
                            val p = progress().coerceIn(0f, 1f)
                            drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = st)
                            if (p > 0f) drawArc(color, -90f, 360f * p, false, Offset(inset, inset), arcSize, style = st)
                        }
                    },
                )
            }
            if (glyph != null) {
                Text(
                    glyph,
                    style = B570Theme.type.caption.copy(fontWeight = FontWeight.Bold),
                    color = color,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(c.background.copy(alpha = 0.85f))
                        .border(1.dp, color.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 5.dp),
                )
            }
        }
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = B570Theme.type.caption,
                color = if (active) color else c.textSecondary.copy(alpha = if (enabled) 1f else 0.5f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = diameter * 1.7f),
            )
        }
    }
}

/** 旋轉的發光環（只在 active 時組合；動畫值只在繪製階段讀）。 */
@Composable
private fun ActiveRing(color: Color, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "ring")
    val rot = t.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "rot")
    val pulse = t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Box(
        modifier.drawWithCache {
            val sweep = Brush.sweepGradient(listOf(Color.Transparent, color.copy(alpha = 0.15f), color, Color.Transparent))
            val st = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
                drawCircle(color.copy(alpha = 0.16f * pulse.value), radius = size.minDimension / 2f)
                rotate(rot.value) {
                    drawCircle(sweep, radius = size.minDimension / 2f - st.width / 2f, style = st)
                }
            }
        },
    )
}

// ================================================================================================ 檔位搖桿開關

/**
 * 檔位翹板開關（升檔 / 降檔）：中間顯示目前檔位（大字 + 位置點），兩端按鍵切換；按下時整個翹板往該側傾斜。
 * 到頂 / 到底再按 → 拒絕震動。
 *
 * @param options 由低到高（例如 Gear.entries：慢 → 中 → 快）
 * @param label 中央顯示文字（建議 1–2 字，例如「慢 / 中 / 快」）
 * @param title 中央小標（例如「檔位」）；null = 不顯示
 * @param vertical true = 直立（上 = 升檔，預設 64 × 148 dp）；false = 橫向（右 = 升檔，預設 176 × 56 dp）
 * @param colorOf 各檔位的顏色（例如快檔用 warn）；預設全部用強調色
 */
@Composable
fun <T> RockerSwitch(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    title: String? = "檔位",
    increaseText: String = "升檔",
    decreaseText: String = "降檔",
    vertical: Boolean = true,
    enabled: Boolean = true,
    colorOf: ((T) -> Color)? = null,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val idx = options.indexOf(selected).coerceAtLeast(0)
    val color = colorOf?.invoke(selected) ?: c.accent
    var tilt by remember { mutableIntStateOf(0) } // -1 降檔側按下、+1 升檔側按下
    val tiltAnim by animateFloatAsState(tilt.toFloat(), spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "tilt")
    val shape = RoundedCornerShape(if (vertical) 22.dp else 18.dp)

    fun step(dir: Int) {
        val n = idx + dir
        if (n in options.indices) {
            haptics.click()
            onSelect(options[n])
        } else {
            haptics.reject()
        }
    }

    val base = modifier
        .then(if (vertical) Modifier.size(width = 64.dp, height = 148.dp) else Modifier.size(width = 176.dp, height = 56.dp))
        .graphicsLayer {
            alpha = if (enabled) 1f else 0.45f
            if (vertical) rotationX = -7f * tiltAnim else rotationY = 7f * tiltAnim
            cameraDistance = 12f * density
        }
        .glass(shape, fill = c.surface.copy(alpha = 0.85f), glow = color.copy(alpha = 0.35f), glowElevation = 10.dp)
        .clip(shape)

    @Composable
    fun Half(dir: Int, text: String, icon: ImageVector, m: Modifier) {
        val can = enabled && (idx + dir) in options.indices
        Box(
            m
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = text
                    if (!enabled) disabled()
                    onClick(label = text) { if (enabled) { step(dir); true } else false }
                }
                .pointerInput(enabled, idx) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            tilt = dir
                            try { tryAwaitRelease() } finally { tilt = 0 }
                        },
                        onTap = { step(dir) },
                    )
                }
                .drawBehind { if (tilt == dir) drawRect(color.copy(alpha = 0.16f)) },
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (can) color else c.textMuted
            if (vertical) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (dir > 0) Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                    Text(text, style = B570Theme.type.caption, color = if (can) c.textSecondary else c.textMuted, maxLines = 1)
                    if (dir < 0) Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dir < 0) Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                    Text(text, style = B570Theme.type.caption, color = if (can) c.textSecondary else c.textMuted, maxLines = 1)
                    if (dir > 0) Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    @Composable
    fun Display(m: Modifier) {
        Column(
            m
                .background(c.background.copy(alpha = 0.55f))
                .semantics(mergeDescendants = true) { stateDescription = (title ?: "") + label(selected) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (title != null && vertical) Text(title, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
            AnimatedContent(
                targetState = idx,
                transitionSpec = {
                    val d = if (targetState > initialState) 1 else -1
                    if (vertical) {
                        (slideInVertically { h -> d * h } + fadeIn()).togetherWith(slideOutVertically { h -> -d * h } + fadeOut())
                    } else {
                        (slideInHorizontally { w -> d * w } + fadeIn()).togetherWith(slideOutHorizontally { w -> -d * w } + fadeOut())
                    }
                },
                label = "gear",
            ) { i ->
                Text(
                    label(options[i]),
                    style = B570Theme.type.readoutM,
                    color = colorOf?.invoke(options[i]) ?: c.accent,
                    maxLines = 1,
                )
            }
            // 位置點
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 2.dp)) {
                options.indices.forEach { i ->
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (i <= idx) color else c.textMuted.copy(alpha = 0.4f)))
                }
            }
        }
    }

    if (vertical) {
        Column(base) {
            Half(+1, increaseText, Icons.Rounded.KeyboardArrowUp, Modifier.weight(1f).fillMaxWidth())
            Display(Modifier.fillMaxWidth().height(62.dp))
            Half(-1, decreaseText, Icons.Rounded.KeyboardArrowDown, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(base, verticalAlignment = Alignment.CenterVertically) {
            Half(-1, decreaseText, Icons.AutoMirrored.Rounded.KeyboardArrowLeft, Modifier.weight(1f).fillMaxHeight())
            Display(Modifier.width(64.dp).fillMaxHeight())
            Half(+1, increaseText, Icons.AutoMirrored.Rounded.KeyboardArrowRight, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

// ================================================================================================ 撥桿開關

/**
 * 撥桿開關（遙控器上的金屬撥桿）：上 = 開、下 = 關，開啟時發光；觸覺回饋、TalkBack 為「切換開關」。
 * 預設 40 × 64 dp（含下方標籤時再加標籤高度）。
 */
@Composable
fun LeverSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    color: Color = B570Theme.colors.accent,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val pos by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "lever")
    Column(
        modifier
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                if (label != null) contentDescription = label
                stateDescription = if (checked) "開" else "關"
                if (!enabled) disabled()
                onClick { if (enabled) { onCheckedChange(!checked); true } else false }
            }
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 64.dp)
                .pointerInput(enabled, checked) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { haptics.click(); onCheckedChange(!checked) }
                }
                .drawWithCache {
                    val slotW = size.width * 0.32f
                    val slotTop = size.height * 0.12f
                    val slotH = size.height * 0.76f
                    val plate = Brush.verticalGradient(listOf(c.glassHigh, c.surface))
                    val r = size.width * 0.3f
                    val ballFill = Brush.radialGradient(listOf(Color.White, lerpColor(c.textSecondary, color, 0.3f), c.textMuted), Offset(-r * 0.3f, -r * 0.3f), r * 1.6f)
                    val stroke = Stroke(1.dp.toPx())
                    onDrawBehind {
                        val p = pos
                        drawRoundRect(plate, cornerRadius = CornerRadius(10.dp.toPx()))
                        drawRoundRect(lerpColor(c.glassBorder, color, p * 0.7f), cornerRadius = CornerRadius(10.dp.toPx()), style = stroke)
                        // 滑槽
                        drawRoundRect(
                            c.background.copy(alpha = 0.8f),
                            topLeft = Offset((size.width - slotW) / 2f, slotTop),
                            size = Size(slotW, slotH),
                            cornerRadius = CornerRadius(slotW / 2f),
                        )
                        if (p > 0.01f) drawCircle(color.copy(alpha = 0.35f * p), r * 1.6f, Offset(size.width / 2f, slotTop + r * 0.6f))
                        // 撥桿：樞軸在中心，桿頭在上（開）/ 下（關）
                        val pivot = Offset(size.width / 2f, size.height / 2f)
                        val headY = slotTop + r * 0.6f + (1f - p) * (slotH - r * 1.2f)
                        val head = Offset(size.width / 2f, headY)
                        drawLine(c.textSecondary, pivot, head, 4.dp.toPx(), StrokeCap.Round)
                        drawCircle(c.textMuted, 4.dp.toPx(), pivot)
                        translate(head.x, head.y) { drawCircle(ballFill, r, Offset.Zero) }
                    }
                },
        )
        if (label != null) {
            Spacer(Modifier.height(3.dp))
            Text(label, style = B570Theme.type.caption, color = if (checked) color else c.textSecondary, maxLines = 1)
        }
    }
}

/** 條件式霓虹柔光（關閉時不加 shadow 圖層）。 */
private fun Modifier.neonGlowIf(on: Boolean, color: Color, shape: Shape, elevation: Dp = 14.dp): Modifier =
    if (on) this.neonGlow(color, shape, elevation) else this

// ================================================================================================ 緊急停止

/** [EmergencyStopButton] 外觀。 */
enum class EStopStyle {
    /** 圓角長條：圖示 + 「緊急停止」橫排（預設，適合放在控制台中央）。 */
    BAR,

    /** 圓形蘑菇頭（工業急停鈕：紅色圓鈕 + 黃色外環），字在鈕內。 */
    ROUND,

    /** 直排：圖示在上、字在下（窄欄位，例如導覽軌底部）。 */
    STACKED,
}

/**
 * 緊急停止鈕：**手指觸碰瞬間**就觸發（不等放開、滑出也算），紅色漸層 + 強光暈；TalkBack 雙擊也會觸發。
 * 大字體時「緊急停止」字級最多放大 1.15 倍，保證完整顯示。不做進場動畫——一出現就看得到、按得到。
 *
 * 呼叫端在 [onStop] 內做實際動作（駕駛：`controller.stop(); vm.emergencyStop()`）。
 * @param haptic true = 按下時重震動（呼叫端自己做觸覺時傳 false）
 */
@Composable
fun EmergencyStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    style: EStopStyle = EStopStyle.BAR,
    haptic: Boolean = true,
    label: String = "緊急停止",
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val hapticsNow by rememberUpdatedState(haptics)
    val hapticOn by rememberUpdatedState(haptic)
    val fire by rememberUpdatedState(onStop)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "estop")
    val glow by animateDpAsState(if (pressed) 30.dp else 14.dp, label = "estopGlow")
    val shape: Shape = when (style) {
        EStopStyle.ROUND -> CircleShape
        EStopStyle.STACKED -> RoundedCornerShape(16.dp)
        EStopStyle.BAR -> RoundedCornerShape(16.dp)
    }
    val fontScale = LocalDensity.current.fontScale
    val baseStyle = when (style) {
        EStopStyle.STACKED -> B570Theme.type.caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
        EStopStyle.ROUND -> B570Theme.type.label.copy(fontWeight = FontWeight.Bold)
        EStopStyle.BAR -> B570Theme.type.titleS.copy(fontWeight = FontWeight.Bold)
    }
    val capped = if (fontScale > ESTOP_MAX_FONT_SCALE) baseStyle.copy(fontSize = baseStyle.fontSize * (ESTOP_MAX_FONT_SCALE / fontScale)) else baseStyle

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .neonGlow(c.danger, shape, glow)
            .clip(shape)
            .drawWithCache {
                val body = Brush.verticalGradient(listOf(lerpColor(c.danger, Color.White, 0.10f), lerpColor(c.danger, Color.Black, 0.30f)))
                val ringW = size.minDimension * 0.07f
                val ring = Stroke(ringW)
                val dome = Brush.radialGradient(
                    listOf(lerpColor(c.danger, Color.White, 0.35f), c.danger, lerpColor(c.danger, Color.Black, 0.35f)),
                    Offset(size.width * 0.4f, size.height * 0.35f), size.minDimension * 0.6f,
                )
                onDrawBehind {
                    if (style == EStopStyle.ROUND) {
                        // 黃色外環 + 紅色蘑菇頭
                        drawCircle(c.warn.copy(alpha = 0.95f), radius = size.minDimension / 2f)
                        drawCircle(Color.Black.copy(alpha = 0.35f), radius = size.minDimension / 2f - ringW, style = ring)
                        drawCircle(dome, radius = size.minDimension / 2f - ringW * 1.5f)
                        if (pressed) drawCircle(Color.Black.copy(alpha = 0.2f), radius = size.minDimension / 2f - ringW * 1.5f)
                    } else {
                        drawRect(body)
                        if (pressed) drawRect(Color.Black.copy(alpha = 0.15f))
                    }
                }
            }
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    if (hapticOn) hapticsNow.heavy()
                    fire()
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label = label) { if (hapticOn) hapticsNow.heavy(); fire(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (style) {
            EStopStyle.BAR -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Dangerous, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(label, style = capped, color = Color.White, maxLines = 1, softWrap = false)
            }

            EStopStyle.ROUND -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Dangerous, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Text(label, style = capped, color = Color.White, maxLines = 1, softWrap = false)
            }

            EStopStyle.STACKED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Dangerous, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(2.dp))
                Text(label, style = capped, color = Color.White, maxLines = 1, softWrap = false)
            }
        }
    }
}

private const val ESTOP_MAX_FONT_SCALE = 1.15f
