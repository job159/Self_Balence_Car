package com.b570.pilot.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.AccentChoice
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.AccentPalette
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlin.math.sin

/**
 * 「外觀」：強調色色票（選取中有旋轉光環、彈簧放大、打勾），點選立即套用整個 App。
 * 下方預覽條與遙控器預覽用動畫色在新舊顏色之間平滑過渡。
 * 寬版（平板）：左 = 色票 + 介面預覽，右 = 遙控器預覽；窄版上下堆疊。
 */
@Composable
internal fun AppearanceSection(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val spin = rememberInfiniteTransition(label = "swatchSpin")
    val angle = spin.animateFloat(0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "angle")

    Column(modifier) {
        PaneHeader(SettingsCategory.APPEARANCE)
        GlassCard(Modifier.fillMaxWidth()) {
            AdaptiveColumns(
                wide = wide,
                firstWeight = 1.1f,
                first = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        AccentChoice.entries.forEach { choice ->
                            val selected = choice == settings.accent
                            AccentSwatch(
                                choice = choice,
                                selected = selected,
                                angle = angle,
                                onClick = {
                                    if (!selected) {
                                        haptics.tick()
                                        vm.updateSettings { it.copy(accent = choice) }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    AccentPreview()
                },
                second = {
                    if (!wide) Spacer(Modifier.height(14.dp))
                    TransmitterPreview()
                },
            )
        }
    }
}

@Composable
private fun AccentSwatch(
    choice: AccentChoice,
    selected: Boolean,
    angle: State<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val palette = AccentPalette.of(choice)
    val sel = animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 360f), label = "swatchSel")
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press = animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "swatchPress")
    val labelColor by animateColorAsState(if (selected) c.textPrimary else c.textSecondary, label = "swatchLabel")

    Column(
        modifier.selectable(selected = selected, interactionSource = src, indication = null, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = press.value
                    scaleY = press.value
                }
                .drawWithCache {
                    val r = size.minDimension / 2f
                    val ring = Brush.sweepGradient(listOf(palette.primary, palette.secondary, Color.Transparent, palette.primary))
                    val core = Brush.linearGradient(listOf(palette.primary, palette.secondary), start = Offset.Zero, end = Offset(size.width, size.height))
                    val ringStroke = Stroke(2.5.dp.toPx())
                    val idleStroke = Stroke(1.dp.toPx())
                    onDrawBehind {
                        val s = sel.value
                        val a = s.coerceIn(0f, 1f)
                        if (a > 0.01f) {
                            drawCircle(palette.primary.copy(alpha = 0.20f * a), radius = r)
                            rotate(angle.value) {
                                drawCircle(ring, radius = r - ringStroke.width, style = ringStroke, alpha = a)
                            }
                        } else {
                            drawCircle(c.glassBorder, radius = r * 0.66f + 3.dp.toPx(), style = idleStroke)
                        }
                        drawCircle(core, radius = r * (0.56f + 0.10f * s))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier.size(22.dp).graphicsLayer {
                    val s = sel.value
                    scaleX = s
                    scaleY = s
                    alpha = s.coerceIn(0f, 1f)
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(choice.labelZh, style = B570Theme.type.label, color = labelColor, maxLines = 1)
    }
}

/** 迷你介面預覽：漸層按鈕、滑軌、訊號格，顏色以動畫追上新的強調色。 */
@Composable
private fun AccentPreview() {
    val c = B570Theme.colors
    val a1 = animateColorAsState(c.accent, tween(520), label = "pa1")
    val a2 = animateColorAsState(c.accent2, tween(520), label = "pa2")
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.s), fill = c.background.copy(alpha = 0.5f), borderWidth = 0.75.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("預覽", style = B570Theme.type.caption, color = c.textMuted)
        Spacer(Modifier.width(12.dp))
        // 漸層按鈕
        Box(
            Modifier
                .size(width = 76.dp, height = 28.dp)
                .drawWithCache {
                    // 只在換色動畫期間重建（讀取動畫色會讓快取失效），平時零配置
                    val cr = CornerRadius(size.height / 2f)
                    val brush = Brush.horizontalGradient(listOf(a1.value, a2.value))
                    onDrawBehind { drawRoundRect(brush, cornerRadius = cr) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("啟動平衡", style = B570Theme.type.caption, color = c.onAccent)
        }
        Spacer(Modifier.width(12.dp))
        // 滑軌
        Box(
            Modifier
                .weight(1f)
                .height(16.dp)
                .drawWithCache {
                    val cy = size.height / 2f
                    val track = 4.dp.toPx()
                    val knob = 6.dp.toPx()
                    onDrawBehind {
                        drawRoundRect(c.textMuted.copy(alpha = 0.3f), Offset(0f, cy - track / 2f), Size(size.width, track), CornerRadius(track / 2f))
                        val x = size.width * 0.62f
                        drawRoundRect(a1.value, Offset(0f, cy - track / 2f), Size(x, track), CornerRadius(track / 2f))
                        drawCircle(a1.value.copy(alpha = 0.25f), knob * 1.8f, Offset(x, cy))
                        drawCircle(Color.White, knob, Offset(x, cy))
                    }
                },
        )
        Spacer(Modifier.width(12.dp))
        // 訊號格
        Box(
            Modifier
                .size(width = 22.dp, height = 16.dp)
                .drawWithCache {
                    val gap = 2.dp.toPx()
                    val bw = (size.width - gap * 3) / 4f
                    val cr = CornerRadius(1.5.dp.toPx())
                    onDrawBehind {
                        for (i in 0 until 4) {
                            val h = size.height * (0.35f + 0.65f * (i + 1) / 4f)
                            drawRoundRect(
                                if (i < 3) a1.value else c.textMuted.copy(alpha = 0.4f),
                                topLeft = Offset(i * (bw + gap), size.height - h),
                                size = Size(bw, h),
                                cornerRadius = cr,
                            )
                        }
                    }
                },
        )
    }
}

/**
 * 遙控器預覽：用目前的強調色畫一台迷你發射器（肩鍵、兩支搖桿、中控螢幕、緊急停止），
 * 左搖桿上下擺（油門）、右搖桿左右擺（方向）。換色時刷子只在動畫期間重建；擺動只在繪製階段讀取，每幀零配置。
 */
@Composable
private fun TransmitterPreview(modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val a1 = animateColorAsState(c.accent, tween(520), label = "tx1")
    val a2 = animateColorAsState(c.accent2, tween(520), label = "tx2")
    val sway = rememberInfiniteTransition(label = "txSway")
    val phase = sway.animateFloat(0f, 1f, infiniteRepeatable(tween(4800, easing = LinearEasing)), label = "txPhase")
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .aspectRatio(2.5f)
                .semantics { contentDescription = "遙控器預覽：左手油門、右手方向" }
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val pad = 6.dp.toPx()
                    val left = pad
                    val bodyW = w - pad * 2f
                    val bodyH = h * 0.80f
                    val top = h - bodyH - 2.dp.toPx()
                    val corner = CornerRadius(bodyH * 0.40f)
                    val col1 = a1.value
                    val col2 = a2.value
                    val bodyBrush = Brush.verticalGradient(listOf(c.glassHigh, c.surface), startY = top, endY = top + bodyH)
                    val rimBrush = Brush.linearGradient(
                        listOf(col1.copy(alpha = 0.85f), col2.copy(alpha = 0.6f)),
                        start = Offset(left, top),
                        end = Offset(left + bodyW, top + bodyH),
                    )
                    val rim = Stroke(1.5.dp.toPx())
                    val hair = Stroke(1.dp.toPx())
                    val haloPad = 5.dp.toPx()
                    val stickR = bodyH * 0.30f
                    val knobR = stickR * 0.42f
                    val travel = stickR - knobR - 2.dp.toPx()
                    val lc = Offset(left + bodyW * 0.20f, top + bodyH * 0.54f)
                    val rc = Offset(left + bodyW * 0.80f, top + bodyH * 0.54f)
                    val shoulderW = bodyW * 0.20f
                    val shoulderH = (top * 0.9f).coerceAtLeast(4.dp.toPx())
                    val shoulderY = top - shoulderH * 0.55f
                    val shoulderCr = CornerRadius(shoulderH / 2f)
                    val screenW = bodyW * 0.20f
                    val screenH = bodyH * 0.24f
                    val screenTl = Offset(left + (bodyW - screenW) / 2f, top + bodyH * 0.16f)
                    val screenCr = CornerRadius(4.dp.toPx())
                    val barGap = screenW * 0.06f
                    val barW = (screenW * 0.5f - barGap * 3f) / 4f
                    val estop = Offset(left + bodyW / 2f, top + bodyH * 0.72f)
                    val estopR = bodyH * 0.10f
                    val hl = knobR * 0.28f
                    onDrawBehind {
                        // 光暈 + 肩鍵
                        drawRoundRect(col1.copy(alpha = 0.10f), Offset(left - haloPad, top - haloPad), Size(bodyW + haloPad * 2f, bodyH + haloPad * 2f), CornerRadius(corner.x + haloPad))
                        drawRoundRect(col1.copy(alpha = 0.55f), Offset(left + bodyW * 0.07f, shoulderY), Size(shoulderW, shoulderH), shoulderCr)
                        drawRoundRect(col2.copy(alpha = 0.55f), Offset(left + bodyW * 0.93f - shoulderW, shoulderY), Size(shoulderW, shoulderH), shoulderCr)
                        // 機身
                        drawRoundRect(bodyBrush, Offset(left, top), Size(bodyW, bodyH), corner)
                        drawRoundRect(rimBrush, Offset(left, top), Size(bodyW, bodyH), corner, style = rim)
                        // 搖桿座
                        drawCircle(Color.Black.copy(alpha = 0.35f), stickR, lc)
                        drawCircle(Color.Black.copy(alpha = 0.35f), stickR, rc)
                        drawCircle(col1.copy(alpha = 0.5f), stickR, lc, style = hair)
                        drawCircle(col2.copy(alpha = 0.5f), stickR, rc, style = hair)
                        drawLine(c.grid, Offset(lc.x, lc.y - stickR), Offset(lc.x, lc.y + stickR), hair.width)
                        drawLine(c.grid, Offset(rc.x - stickR, rc.y), Offset(rc.x + stickR, rc.y), hair.width)
                        // 搖桿頭：左 = 油門（上下）、右 = 方向（左右）
                        val p = phase.value * TWO_PI
                        val lk = Offset(lc.x, lc.y - sin(p) * travel)
                        val rk = Offset(rc.x + sin(p * 2f + 0.8f) * travel, rc.y)
                        drawCircle(col1.copy(alpha = 0.22f), knobR * 1.8f, lk)
                        drawCircle(col1, knobR, lk)
                        drawCircle(Color.White.copy(alpha = 0.8f), hl, Offset(lk.x - hl, lk.y - hl))
                        drawCircle(col2.copy(alpha = 0.22f), knobR * 1.8f, rk)
                        drawCircle(col2, knobR, rk)
                        drawCircle(Color.White.copy(alpha = 0.8f), hl, Offset(rk.x - hl, rk.y - hl))
                        // 中控螢幕 + 訊號格
                        drawRoundRect(c.background.copy(alpha = 0.85f), screenTl, Size(screenW, screenH), screenCr)
                        drawRoundRect(col1.copy(alpha = 0.5f), screenTl, Size(screenW, screenH), screenCr, style = hair)
                        val bx0 = screenTl.x + (screenW - (barW * 4f + barGap * 3f)) / 2f
                        val by1 = screenTl.y + screenH * 0.78f
                        for (i in 0 until 4) {
                            val bh = screenH * (0.18f + 0.13f * i)
                            drawRect(if (i < 3) col1 else c.textMuted.copy(alpha = 0.5f), Offset(bx0 + i * (barW + barGap), by1 - bh), Size(barW, bh))
                        }
                        // 緊急停止（黃環 + 紅鈕）
                        drawCircle(c.warn.copy(alpha = 0.85f), estopR * 1.3f, estop)
                        drawCircle(c.danger, estopR, estop)
                    }
                },
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.widthIn(max = 340.dp).fillMaxWidth()) {
            val style = B570Theme.type.caption
            Text("左手油門", style = style, color = c.textSecondary, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("緊急停止", style = style, color = c.danger, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("右手方向", style = style, color = c.textSecondary, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
    }
}

private const val TWO_PI = (2.0 * Math.PI).toFloat()
