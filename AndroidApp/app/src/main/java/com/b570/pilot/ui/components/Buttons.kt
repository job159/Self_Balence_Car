package com.b570.pilot.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.theme.B570Theme

/** NeonButton 樣式。 */
enum class NeonButtonStyle { PRIMARY, SECONDARY, DANGER, GHOST }

/**
 * 霓虹按鈕：按下縮放 + 光暈、自動觸覺回饋。
 * @param style PRIMARY 實心漸層 / SECONDARY 玻璃描邊 / DANGER 紅色實心 / GHOST 純文字
 * @param loading 顯示轉圈並停用點擊
 * @param compact 較矮的版本（36 dp）
 */
@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NeonButtonStyle = NeonButtonStyle.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
    color: Color? = null,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val active = enabled && !loading
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "scale")
    val glowDp by animateDpAsState(if (pressed) 22.dp else 12.dp, label = "glow")
    val shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
    val base = color ?: when (style) {
        NeonButtonStyle.DANGER -> c.danger
        else -> c.accent
    }
    val fg = when (style) {
        NeonButtonStyle.PRIMARY -> if (color == null) c.onAccent else Color.Black
        NeonButtonStyle.DANGER -> Color.White
        NeonButtonStyle.SECONDARY, NeonButtonStyle.GHOST -> base
    }
    var m = modifier
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (active || loading) 1f else 0.4f }
    if (active && (style == NeonButtonStyle.PRIMARY || style == NeonButtonStyle.DANGER)) {
        m = m.shadow(glowDp, shape, clip = false, ambientColor = base, spotColor = base)
    }
    m = m.clip(shape)
    m = when (style) {
        NeonButtonStyle.PRIMARY -> m.background(
            if (color == null) Brush.horizontalGradient(listOf(c.accent, lerpColor(c.accent, c.accent2, 0.55f)))
            else Brush.horizontalGradient(listOf(base, base)),
        )
        NeonButtonStyle.DANGER -> m.background(Brush.verticalGradient(listOf(c.danger, lerpColor(c.danger, Color.Black, 0.25f))))
        NeonButtonStyle.SECONDARY -> m.background(if (pressed) c.glassHigh else c.glass).border(1.dp, base.copy(alpha = 0.55f), shape)
        NeonButtonStyle.GHOST -> m.background(if (pressed) base.copy(alpha = 0.10f) else Color.Transparent)
    }
    m = m.clickable(src, indication = null, enabled = active, role = Role.Button) { haptics.click(); onClick() }
        .defaultMinSize(minHeight = if (compact) 36.dp else 48.dp)
        .padding(if (compact) PaddingValues(horizontal = 14.dp) else PaddingValues(horizontal = 20.dp))
    Row(m, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(if (compact) 18.dp else 20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, style = if (compact) B570Theme.type.label else B570Theme.type.titleS, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 分段選擇器：玻璃底 + 滑動的發光指示塊。
 * @param label 選項顯示文字
 * @param icon 選項圖示（可選）
 */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    icon: ((T) -> ImageVector?)? = null,
    enabled: Boolean = true,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(14.dp)
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(c.glass)
            .border(1.dp, c.glassBorderDim, shape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(4.dp),
    ) {
        val segW = maxWidth / options.size.coerceAtLeast(1)
        val offset by animateDpAsState(segW * index, spring(dampingRatio = 0.75f, stiffness = 500f), label = "seg")
        Box(
            Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .width(segW)
                .fillMaxHeight()
                .shadow(10.dp, RoundedCornerShape(11.dp), clip = false, ambientColor = c.accent, spotColor = c.accent)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.32f), c.accent2.copy(alpha = 0.26f))))
                .border(1.dp, c.accent.copy(alpha = 0.6f), RoundedCornerShape(11.dp)),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
            options.forEachIndexed { i, opt ->
                val sel = i == index
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(11.dp))
                        // 無障礙：單選按鈕角色 + 選取狀態（TalkBack 會念出目前選的是哪一個）
                        .selectable(
                            selected = sel,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = enabled,
                            role = Role.RadioButton,
                        ) {
                            if (!sel) { haptics.tick(); onSelect(opt) }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ic = icon?.invoke(opt)
                    val col = if (sel) c.textPrimary else c.textSecondary
                    if (ic != null) {
                        Icon(ic, null, tint = if (sel) c.accent else col, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(label(opt), style = B570Theme.type.label, color = col, maxLines = 1)
                }
            }
        }
    }
}

fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

