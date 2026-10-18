package com.b570.pilot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.b570.pilot.protocol.ParamDef
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 數值滑桿：標題 + 數值（點擊可輸入精確值）+ 霓虹滑軌 + −/＋ 微調鈕（長按連續）。
 * @param step 步進（0 = 連續）；滑動與微調都會對齊步進
 * @param decimals 顯示小數位數（null = 依 step 推算）
 * @param onValueChangeFinished 放開滑桿 / 微調結束 / 輸入確定時呼叫（適合在這裡送 PARAM_SET）
 */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    unit: String = "",
    decimals: Int? = null,
    description: String? = null,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color = B570Theme.colors.accent,
) {
    val c = B570Theme.colors
    val dec = decimals ?: decimalsFor(step)
    val haptics = rememberHaptics()
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    var editing by remember { mutableStateOf(false) }

    fun snap(v: Float): Float {
        var s = v.coerceIn(range.start, range.endInclusive)
        if (step > 0f) s = (range.start + ((s - range.start) / step).roundToLong() * step).coerceIn(range.start, range.endInclusive)
        val k = 10f.pow(dec)
        return (s * k).roundToLong() / k
    }

    fun nudge(dir: Int) {
        val st = if (step > 0f) step else (range.endInclusive - range.start) / 100f
        val nv = snap(current + dir * st)
        if (nv != current) { change(nv); haptics.tick() } else haptics.reject()
    }

    Column(modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = B570Theme.type.titleS, color = c.textPrimary)
                if (description != null) Text(description, style = B570Theme.type.caption, color = c.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .glass(RoundedCornerShape(10.dp), fill = c.glassHigh)
                    .noRippleClickable(enabled) { haptics.click(); editing = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(String.format(Locale.US, "%.${dec}f", value), style = B570Theme.type.readoutS, color = accent)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(unit, style = B570Theme.type.caption, color = c.textMuted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatStepButton(Icons.Rounded.Remove, enabled, onStep = { nudge(-1) }, onDone = { finished?.invoke() })
            Spacer(Modifier.width(10.dp))
            NeonSlider(
                value = value,
                onValueChange = { v -> change(snap(v)) },
                range = range,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onValueChangeFinished = { finished?.invoke() },
                accent = accent,
            )
            Spacer(Modifier.width(10.dp))
            RepeatStepButton(Icons.Rounded.Add, enabled, onStep = { nudge(1) }, onDone = { finished?.invoke() })
        }
    }

    if (editing) {
        NumberInputDialog(
            title = label,
            initial = value,
            range = range,
            decimals = dec,
            unit = unit,
            onDismiss = { editing = false },
            onConfirm = { v ->
                editing = false
                change(snap(v))
                finished?.invoke()
            },
        )
    }
}

/** 以 [ParamDef] 建立滑桿（標題、範圍、步進、單位、說明全部取自參數表）。 */
@Composable
fun ValueSlider(
    def: ParamDef,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDescription: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) = ValueSlider(
    label = def.labelZh,
    value = value,
    onValueChange = onValueChange,
    range = def.min..def.max,
    modifier = modifier,
    step = def.step,
    unit = def.unit,
    decimals = def.decimals,
    description = if (showDescription) def.descZh else null,
    enabled = enabled,
    onValueChangeFinished = onValueChangeFinished,
)

/**
 * 只有滑軌的霓虹滑桿（不含標題）。水平拖曳才會攔截，垂直捲動不受影響。
 */
@Composable
fun NeonSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color = B570Theme.colors.accent,
) {
    val c = B570Theme.colors
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val currentValue by rememberUpdatedState(value)
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    var dragging by remember { mutableStateOf(false) }
    val thumbR = 9.dp
    Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(enabled, range) {
                if (!enabled) return@pointerInput
                val pad = thumbR.toPx()
                fun fromX(x: Float) = range.start + ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * span
                detectTapGestures(onTap = { change(fromX(it.x)); finished?.invoke() })
            }
            .pointerInput(enabled, range) {
                if (!enabled) return@pointerInput
                val pad = thumbR.toPx()
                fun fromX(x: Float) = range.start + ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * span
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; change(fromX(it.x)) },
                    onDragEnd = { dragging = false; finished?.invoke() },
                    onDragCancel = { dragging = false; finished?.invoke() },
                ) { ch, _ ->
                    ch.consume()
                    change(fromX(ch.position.x))
                }
            },
    ) {
        val pad = thumbR.toPx()
        val cy = size.height / 2
        val w = size.width - 2 * pad
        val f = ((currentValue - range.start) / span).coerceIn(0f, 1f)
        val x = pad + w * f
        val track = 4.dp.toPx()
        drawLine(c.textMuted.copy(alpha = 0.35f), Offset(pad, cy), Offset(pad + w, cy), track, StrokeCap.Round)
        drawLine(
            Brush.horizontalGradient(listOf(c.accent2, accent), startX = pad, endX = x.coerceAtLeast(pad + 1f)),
            Offset(pad, cy), Offset(x, cy), track, StrokeCap.Round,
        )
        val glowR = if (dragging) thumbR.toPx() * 2.2f else thumbR.toPx() * 1.6f
        drawCircle(accent.copy(alpha = if (dragging) 0.28f else 0.16f), glowR, Offset(x, cy))
        drawCircle(Color.White, thumbR.toPx() * 0.72f, Offset(x, cy))
        drawCircle(accent, thumbR.toPx() * 0.72f, Offset(x, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

/** 圓形微調鈕：點一下 = 一步；長按 0.4 s 後連續、越來越快。 */
@Composable
fun RepeatStepButton(
    icon: ImageVector,
    enabled: Boolean,
    onStep: () -> Unit,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
) {
    val c = B570Theme.colors
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    val done by rememberUpdatedState(onDone)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(36.dp)
            .graphicsLayer { val s = if (pressed) 0.9f else 1f; scaleX = s; scaleY = s }
            .glass(CircleShape, fill = if (pressed) c.glassHigh else c.glass, glow = if (pressed) c.accent else null, glowElevation = 8.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    step()
                    val job = scope.launch {
                        delay(400)
                        var d = 120L
                        while (true) {
                            step()
                            delay(d)
                            d = (d * 0.88f).toLong().coerceAtLeast(30L)
                        }
                    }
                    waitForUpOrCancellation()
                    job.cancel()
                    pressed = false
                    done?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) c.textPrimary else c.textMuted, modifier = Modifier.size(18.dp))
    }
}

/** 依步進推算小數位數。 */
fun decimalsFor(step: Float): Int =
    if (step <= 0f) 2 else if (step >= 1f && abs(step - Math.round(step)) < 1e-4f) 0 else (-log10(step.toDouble()) + 0.999).toInt().coerceIn(0, 4)
