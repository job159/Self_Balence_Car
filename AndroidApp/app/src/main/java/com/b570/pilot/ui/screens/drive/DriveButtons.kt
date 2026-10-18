package com.b570.pilot.ui.screens.drive

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.components.lerpColor
import com.b570.pilot.ui.theme.B570Theme

/** 肩鍵外側上角（大圓角）與其他角。 */
private val CornerBig = 22.dp
private val CornerSmall = 10.dp

/** 立體唇邊高度（按下時鍵面下沉）。 */
private val Lip = 4.dp

/** 標籤剛翻轉（例如車子自動停機）後這段時間內不接受「啟動」，避免手指落下時看到的是「停止」。 */
private const val FLIP_GUARD_MS = 700L

/** [MotorToggle] 依「使用者按下時看到的狀態」決定動作（非 state，只在主執行緒存取）。 */
private class ToggleGuard(var shown: Boolean) {
    var flippedAt = 0L
    var shownAtPress: Boolean? = null
}

/**
 * 「啟動平衡 / 停止」左肩鍵（遙控器 L1 位置）：狀態綁定遙測 MOTORS_ON；運轉中綠色實心 + 呼吸光環；
 * 立體唇邊、按下時鍵面下沉。
 * 動作以「按下時畫面上顯示的狀態」為準：按住期間標籤翻轉（例如車子被拿起而自動停機），放開時不做任何事；
 * 標籤剛翻轉的 [FLIP_GUARD_MS] 內也不接受「啟動」——絕不會因為原本想按「停止」而把拿在手上的車啟動。
 * @param pending 指令等待回覆中（顯示轉圈、暫停點擊）
 * @param onToggle true = 啟動、false = 停止
 * @param onIgnored 這次點擊因上述保護被忽略
 * @param attention 引導使用者按這裡（已連線、馬達未啟動）：外框呼吸光暈
 * @param rightSide true = 右肩外形（外側上角在右）
 */
@Composable
internal fun MotorToggle(
    running: Boolean,
    enabled: Boolean,
    pending: Boolean,
    onToggle: (start: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onIgnored: () -> Unit = {},
    attention: Boolean = false,
    rightSide: Boolean = false,
) {
    val c = B570Theme.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val guard = remember { ToggleGuard(running) }
    SideEffect {
        if (guard.shown != running) {
            guard.shown = running
            guard.flippedAt = SystemClock.uptimeMillis()
        }
    }
    // clickable 在放開時先同步呼叫 onClick，Release / Cancel 之後才送到這裡 → 收到時清掉，不會留給下一次點擊
    // （快速點擊時 Press 也可能在 onClick 之後才補送，隨後的 Release 一樣會清掉）
    LaunchedEffect(src) {
        src.interactions.collect {
            when (it) {
                is PressInteraction.Press -> guard.shownAtPress = guard.shown
                is PressInteraction.Release, is PressInteraction.Cancel -> guard.shownAtPress = null
            }
        }
    }
    val toggle by rememberUpdatedState(onToggle)
    val ignored by rememberUpdatedState(onIgnored)
    val onClick: () -> Unit = {
        val atPress = guard.shownAtPress ?: guard.shown
        guard.shownAtPress = null
        val start = !atPress
        val flippedUnderFinger = atPress != guard.shown
        val justFlipped = SystemClock.uptimeMillis() - guard.flippedAt < FLIP_GUARD_MS
        if (flippedUnderFinger || (start && justFlipped)) ignored() else toggle(start)
    }
    val active = enabled && !pending
    val runColor = c.ok
    val keyColor = if (running) runColor else c.accent
    val depth by animateDpAsState(if (pressed && active) Lip - 1.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh), label = "motorDepth")
    val fill by animateColorAsState(if (running) runColor.copy(alpha = 0.92f) else c.surface, tween(320), label = "motorFill")
    val border by animateColorAsState(if (running) runColor else c.accent.copy(alpha = 0.65f), tween(320), label = "motorBorder")
    val glowColor by animateColorAsState(keyColor, tween(320), label = "motorGlow")
    val glow by animateDpAsState(if (running) 18.dp else if (attention && active) 12.dp else 6.dp, label = "motorGlowDp")
    val lipColor by animateColorAsState(lerpColor(keyColor, Color.Black, 0.7f).copy(alpha = 0.92f), tween(320), label = "motorLip")
    val onRun = Color(0xFF03150E)
    val shape = if (rightSide) {
        RoundedCornerShape(topStart = CornerSmall, topEnd = CornerBig, bottomEnd = CornerSmall, bottomStart = CornerSmall)
    } else {
        RoundedCornerShape(topStart = CornerBig, topEnd = CornerSmall, bottomEnd = CornerSmall, bottomStart = CornerSmall)
    }

    // 運轉中：向外擴散的光環；未啟動但該按這裡：外框呼吸（動畫值只在繪製階段讀取）
    val pulse: State<Float>? = if (running || (attention && active)) {
        val tr = rememberInfiniteTransition(label = "motorPulse")
        tr.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(if (running) 1600 else 1300, easing = LinearOutSlowInEasing), RepeatMode.Restart),
            label = "p",
        )
    } else {
        null
    }

    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .drawWithCache {
                val ring = Stroke(2.dp.toPx())
                val corner = ((CornerBig + CornerSmall) / 2f).toPx()
                val spread = 9.dp.toPx()
                val faceH = size.height - Lip.toPx()
                onDrawBehind {
                    val p = pulse?.value ?: return@onDrawBehind
                    val g = p * spread
                    drawRoundRect(
                        keyColor.copy(alpha = 0.55f * (1f - p)),
                        topLeft = Offset(-g, -g),
                        size = Size(size.width + g * 2f, faceH + g * 2f),
                        cornerRadius = CornerRadius(corner + g),
                        style = ring,
                    )
                }
            }
            .clickable(src, indication = null, enabled = active, role = Role.Button, onClick = onClick),
    ) {
        // 立體唇邊（底座）
        Box(
            Modifier
                .matchParentSize()
                .padding(top = Lip)
                .background(lipColor, shape),
        )
        // 鍵面
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = Lip)
                .offset { IntOffset(0, depth.roundToPx()) }
                .shadow(glow, shape, clip = false, ambientColor = glowColor, spotColor = glowColor)
                .clip(shape)
                .background(fill)
                .drawWithCache {
                    // 上半部光澤（Brush 只在尺寸 / 狀態改變時建立）
                    val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = if (running) 0.18f else 0.10f), 0.55f to Color.Transparent)
                    val down = pressed && active
                    onDrawBehind {
                        drawRect(sheen)
                        if (down) drawRect(Color.Black.copy(alpha = 0.18f))
                    }
                }
                .border(1.5.dp, border, shape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = running,
                transitionSpec = {
                    (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) { it } + fadeIn(tween(180)))
                        .togetherWith(slideOutVertically(tween(180)) { -it } + fadeOut(tween(140)))
                },
                label = "motorLabel",
            ) { on ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        if (pending) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = if (on) onRun else c.accent, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                if (on) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                tint = if (on) onRun else c.accent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (on) "停止" else "啟動平衡",
                        style = B570Theme.type.titleS.copy(fontWeight = FontWeight.SemiBold),
                        color = if (on) onRun else c.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (on) {
                        Spacer(Modifier.width(10.dp))
                        BalancingTag(onRun)
                    }
                }
            }
        }
    }
}

@Composable
private fun BalancingTag(color: Color) {
    val tr = rememberInfiniteTransition(label = "tag")
    val a = tr.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "tagA")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(7.dp).drawBehind { drawCircle(color.copy(alpha = a.value)) },
        )
        Spacer(Modifier.width(4.dp))
        Text("平衡中", style = B570Theme.type.caption, color = color.copy(alpha = 0.8f), maxLines = 1, softWrap = false)
    }
}
