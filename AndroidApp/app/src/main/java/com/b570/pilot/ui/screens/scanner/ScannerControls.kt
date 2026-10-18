package com.b570.pilot.ui.screens.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.cockpitBackground
import com.b570.pilot.ui.theme.glass
import com.b570.pilot.ui.theme.neonGlow
import java.util.Locale

// ====================================================================== 圓形玻璃鍵

@Composable
internal fun RoundGlassButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: Dp = 44.dp,
) {
    val c = B570Theme.colors
    val bg by animateColorAsState(if (active) c.accent.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.4f), label = "roundBg")
    var m = modifier.size(size)
    if (active) m = m.neonGlow(c.accent, CircleShape, 14.dp)
    Box(
        m.clip(CircleShape)
            .background(bg)
            .border(1.dp, if (active) c.accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.16f), CircleShape)
            .noRippleClickable(onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (active) c.accent else Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** 圓鍵 + 下方小字（右側控制列用）。 */
@Composable
internal fun RailButton(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    Column(modifier.width(SCAN_RAIL_W.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RoundGlassButton(icon, description, onClick, active = active)
        Text(
            label,
            style = B570Theme.type.caption,
            color = if (active) B570Theme.colors.accent else Color.White.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

// ====================================================================== 左上：關閉 + 標題

@Composable
internal fun ScanCloseBar(onClose: () -> Unit, showTitle: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.height(SCAN_CLOSE_BAR_H.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundGlassButton(Icons.Rounded.Close, "關閉", onClose)
        if (showTitle) {
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text("掃描配對", style = B570Theme.type.titleS, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "自動讀取車子的 Wi-Fi",
                    style = B570Theme.type.caption, color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ====================================================================== 左側：說明 + 手動輸入

private val GUIDE_TIPS = listOf(
    "打開車子電源，OLED 會顯示 QR Code",
    "把 QR Code 放進框內，可以靠近一點",
    "太暗可開手電筒；點畫面或按「對焦」",
)

/**
 * 左側說明欄（關閉列下方）：三步驟說明卡 + 「改用手動輸入」。
 * 內容比欄高時可捲動（字級 1.3 倍、640 × 320 也不會被切掉）。
 * @param compact 欄太窄：不放說明卡，「改用手動輸入」改成圖示鍵
 */
@Composable
internal fun ScanGuidePanel(
    canManual: Boolean,
    onManual: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (!compact) {
                GuideCard(Modifier.widthIn(max = 360.dp).fillMaxWidth())
            } else {
                Spacer(Modifier.height(1.dp))
            }
            if (canManual) {
                Spacer(Modifier.height(10.dp))
                if (compact) {
                    RailButton(Icons.Rounded.Keyboard, "手動", "改用手動輸入", onManual)
                } else {
                    NeonButton(
                        "改用手動輸入", onManual,
                        style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.Keyboard, compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideCard(modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    Column(
        modifier
            .glass(RoundedCornerShape(B570Theme.radii.m), fill = Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GUIDE_TIPS.forEachIndexed { i, tip ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.padding(top = 1.dp).size(18.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", style = B570Theme.type.caption, color = c.accent, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Text(tip, style = B570Theme.type.bodySmall, color = Color.White.copy(alpha = 0.86f))
            }
        }
    }
}

// ====================================================================== 掃描框下方提示

private enum class HintKind { STARTING, SCANNING, SUCCESS }

@Composable
internal fun HintPill(scan: ScannerController, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val kind = when {
        scan.success != null -> HintKind.SUCCESS
        scan.status == CamStatus.STARTING -> HintKind.STARTING
        else -> HintKind.SCANNING
    }
    if (scan.status == CamStatus.ERROR || scan.status == CamStatus.NO_CAMERA) return
    AnimatedContent(
        targetState = kind,
        modifier = modifier,
        transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.92f)).togetherWith(fadeOut(tween(120))) },
        contentAlignment = Alignment.Center,
        label = "hint",
    ) { k ->
        val (icon, text, tint) = when (k) {
            HintKind.STARTING -> Triple(Icons.Rounded.PhotoCamera, "正在開啟相機…", Color.White)
            HintKind.SCANNING -> Triple(Icons.Rounded.QrCode2, "對準 OLED 螢幕上的 QR Code", Color.White)
            HintKind.SUCCESS -> Triple(Icons.Rounded.CheckCircle, "配對成功，正在連線…", c.ok)
        }
        Row(
            Modifier
                .glass(RoundedCornerShape(50), fill = Color.Black.copy(alpha = 0.45f), glow = if (k == HintKind.SUCCESS) c.ok.copy(alpha = 0.6f) else null)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = B570Theme.type.bodySmall, color = tint, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

// ====================================================================== 右側：縮放

/**
 * 直立縮放列：倍率讀數、（夠高時）放大 / 縮小鍵、直立滑桿（上 = 放大）。
 * 右手拇指就能操作，跟遙控器的微調桿一樣每 25 % 一格輕震。
 */
@Composable
internal fun ZoomRail(scan: ScannerController, modifier: Modifier = Modifier, showSteps: Boolean = true) {
    val haptics = rememberHaptics()
    Column(
        modifier
            .width(SCAN_RAIL_W.dp)
            .glass(RoundedCornerShape(50), fill = Color.Black.copy(alpha = 0.45f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            String.format(Locale.US, "%.1f×", scan.zoomRatio),
            style = B570Theme.type.readoutS.copy(fontSize = 12.sp), color = Color.White,
            maxLines = 1, textAlign = TextAlign.Center,
        )
        if (showSteps) {
            ZoomStep(Icons.Rounded.ZoomIn, "放大") {
                haptics.tick()
                scan.setLinearZoom(scan.zoomLinear + 0.1f)
            }
        } else {
            Spacer(Modifier.height(6.dp))
        }
        VerticalZoomSlider(
            value = { scan.zoomLinear },
            onValueChange = { scan.setLinearZoom(it) },
            modifier = Modifier.weight(1f).width(44.dp),
        )
        if (showSteps) {
            ZoomStep(Icons.Rounded.ZoomOut, "縮小") {
                haptics.tick()
                scan.setLinearZoom(scan.zoomLinear - 0.1f)
            }
        } else {
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ZoomStep(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).noRippleClickable(onClick = onClick).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
    }
}

/**
 * 直立滑桿 0..1（上 = 1）。按下即跳到該位置、可拖曳；跨過 25 % 刻度輕震。
 * 值只在繪製階段讀取（[value] lambda），筆刷在 drawWithCache 建立。
 */
@Composable
private fun VerticalZoomSlider(value: () -> Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val hapticsNow by rememberUpdatedState(haptics)
    val change by rememberUpdatedState(onValueChange)
    val read by rememberUpdatedState(value)
    var dragging by remember { mutableStateOf(false) }
    val active by animateFloatAsState(if (dragging) 1f else 0f, label = "zoomDrag")
    val current = value()
    Spacer(
        modifier
            // 貼右緣：拖曳時不要被系統返回手勢搶走
            .systemGestureExclusion()
            .semantics {
                contentDescription = "縮放"
                stateDescription = "${(current * 100).toInt()}%"
                progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..1f)
                setProgress { v -> change(v.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                val pad = THUMB_R.dp.toPx()
                fun fromY(y: Float): Float = 1f - ((y - pad) / (size.height - 2f * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    var lastStep = (read() * 4f).toInt()
                    fun emit(v: Float) {
                        val step = (v * 4f).toInt()
                        if (step != lastStep) hapticsNow.tick()
                        lastStep = step
                        change(v)
                    }
                    try {
                        emit(fromY(down.position.y))
                        verticalDrag(down.id) { ch ->
                            ch.consume()
                            emit(fromY(ch.position.y))
                        }
                    } finally {
                        dragging = false
                    }
                }
            }
            .drawWithCache {
                val pad = THUMB_R.dp.toPx()
                val trackW = 4.dp.toPx()
                val cx = size.width / 2f
                val top = pad
                val bottom = size.height - pad
                val fill = Brush.verticalGradient(listOf(c.accent2, c.accent), startY = bottom, endY = top)
                val tickW = 7.dp.toPx()
                val tickStroke = 1.dp.toPx()
                val thumbR = THUMB_R.dp.toPx()
                val ring = Stroke(2.dp.toPx())
                val halo = 18.dp.toPx()
                onDrawBehind {
                    val v = read().coerceIn(0f, 1f)
                    val y = bottom - (bottom - top) * v
                    drawRoundRect(Color.White, Offset(cx - trackW / 2f, top), Size(trackW, bottom - top), CornerRadius(trackW), alpha = 0.18f)
                    drawRoundRect(fill, Offset(cx - trackW / 2f, y), Size(trackW, bottom - y), CornerRadius(trackW))
                    for (k in 0..4) {
                        val ty = bottom - (bottom - top) * k / 4f
                        drawLine(Color.White, Offset(cx - trackW - tickW, ty), Offset(cx - trackW, ty), tickStroke, StrokeCap.Round, alpha = 0.35f)
                    }
                    if (active > 0.01f) drawCircle(c.accent, halo, Offset(cx, y), alpha = 0.25f * active)
                    drawCircle(Color.White, thumbR, Offset(cx, y))
                    drawCircle(c.accent, thumbR, Offset(cx, y), style = ring)
                }
            },
    )
}

private const val THUMB_R = 10f

// ====================================================================== 置中說明（權限 / 相機錯誤）

/**
 * 橫向置中說明卡：左邊大圖示，右邊標題 / 說明 / 按鈕。內容比畫面高時可捲動。
 * 左右各留 72 dp，不會蓋到左上角的關閉鍵。
 */
@Composable
internal fun CenterMessage(
    icon: ImageVector,
    title: String,
    message: String,
    primaryText: String?,
    primaryIcon: ImageVector?,
    onPrimary: () -> Unit,
    canManual: Boolean,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
    background: Boolean = false,
) {
    val c = B570Theme.colors
    var m = modifier.fillMaxSize()
    m = if (background) m.cockpitBackground() else m.background(Color.Black.copy(alpha = 0.7f))
    BoxWithConstraints(m, contentAlignment = Alignment.Center) {
        val tall = maxHeight >= 520.dp
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 72.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(Modifier.widthIn(max = 600.dp), verticalAlignment = Alignment.CenterVertically) {
                val iconBox = if (tall) 96.dp else 72.dp
                Box(
                    Modifier.size(iconBox).glass(CircleShape, glow = c.accent.copy(alpha = 0.6f), glowElevation = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = c.accent, modifier = Modifier.size(iconBox * 0.45f))
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(title, style = B570Theme.type.titleL, color = c.textPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(message, style = B570Theme.type.bodySmall, color = c.textSecondary, modifier = Modifier.widthIn(max = 380.dp))
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (primaryText != null) {
                            NeonButton(primaryText, onPrimary, Modifier.widthIn(min = 150.dp), icon = primaryIcon)
                        }
                        if (canManual) {
                            NeonButton("改用手動輸入", onManual, style = NeonButtonStyle.GHOST, icon = Icons.Rounded.Keyboard)
                        }
                    }
                }
            }
        }
    }
}
