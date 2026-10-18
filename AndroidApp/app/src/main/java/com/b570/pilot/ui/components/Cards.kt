package com.b570.pilot.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass

/**
 * 玻璃卡片：半透明表面 + 1 dp 漸層邊框 + 可選霓虹柔光。
 * @param glow 柔光顏色（例如選取中的卡片傳 accent），null = 無
 * @param onClick 非 null 時整張卡片可點（含觸覺回饋）
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glow: Color? = null,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(B570Theme.radii.l),
    contentPadding: PaddingValues = PaddingValues(B570Theme.spacing.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = rememberHaptics()
    var m = modifier.glass(shape = shape, glow = glow).clip(shape)
    if (onClick != null) m = m.clickable { haptics.click(); onClick() }
    Column(m.padding(contentPadding), content = content)
}

/**
 * 區塊標題：主標 + 可選副標 + 右側動作。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = B570Theme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(2.dp))
                .drawBehind { drawRect(Brush.verticalGradient(listOf(c.accent, c.accent2))) },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = B570Theme.type.titleS, color = c.textPrimary)
            if (subtitle != null) Text(subtitle, style = B570Theme.type.caption, color = c.textSecondary)
        }
        trailing?.invoke()
    }
}

/**
 * 空狀態：圖示 + 標題 + 說明 + 可選按鈕。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = B570Theme.colors
    Column(
        modifier.padding(B570Theme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).glass(CircleShape, glow = c.accent.copy(alpha = 0.6f), glowElevation = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = B570Theme.type.titleM, color = c.textPrimary, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(message, style = B570Theme.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            NeonButton(actionText, onAction, style = NeonButtonStyle.SECONDARY)
        }
    }
}

/**
 * 「施工中」佔位畫面（各畫面工程師完成後移除）。
 */
@Composable
fun ConstructionPlaceholder(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val pulse = rememberInfiniteTransition(label = "construction")
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.padding(B570Theme.spacing.xl).widthIn(max = 420.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(84.dp).drawBehind {
                        drawCircle(c.accent.copy(alpha = 0.12f * a), radius = size.minDimension / 2)
                        drawCircle(c.accent.copy(alpha = 0.5f * a), radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = c.accent, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text(title, style = B570Theme.type.titleL, color = c.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(description, style = B570Theme.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Construction, null, tint = c.warn, modifier = Modifier.size(16.dp).graphicsLayer { alpha = a })
                    Spacer(Modifier.width(6.dp))
                    Text("施工中", style = B570Theme.type.label, color = c.warn)
                }
            }
        }
    }
}

/** 無水波紋的點擊（自訂按壓效果的元件用）。 */
@Composable
fun Modifier.noRippleClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.clickable(src, indication = null, enabled = enabled, onClick = onClick)
}
