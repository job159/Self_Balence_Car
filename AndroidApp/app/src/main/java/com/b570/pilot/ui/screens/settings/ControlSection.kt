package com.b570.pilot.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ControlCamera
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.Gear
import com.b570.pilot.data.JoystickMode
import com.b570.pilot.protocol.Params
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.SegmentedSelector
import com.b570.pilot.ui.components.ValueSlider
import com.b570.pilot.ui.components.joystickCurve
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlin.math.roundToInt

/** 曲線取樣點數（預覽用）。 */
private const val CURVE_SAMPLES = 48

/**
 * 「操控」：觸覺回饋、搖桿模式、搖桿曲線（即時預覽）、預設檔位、體感靈敏度、螢幕常亮。
 * 滑桿拖曳中只改本地值，放開才寫入設定（避免每幀寫 SharedPreferences）。
 * 寬版（平板）：左 = 開關 / 模式 / 檔位，右 = 搖桿曲線 + 體感靈敏度；窄版依直向版順序堆疊。
 */
@Composable
internal fun ControlSection(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val view = LocalView.current

    val haptics: @Composable () -> Unit = {
        SettingSwitchRow(
            icon = Icons.Rounded.Vibration,
            title = "觸覺回饋",
            subtitle = "按鈕、滑桿與搖桿的震動",
            checked = settings.hapticsEnabled,
            onCheckedChange = { on ->
                vm.updateSettings { it.copy(hapticsEnabled = on) }
                if (on) view.confirmBuzz() // 這一下設定還沒生效，直接震給使用者感受
            },
        )
    }
    val mode: @Composable () -> Unit = {
        ModeEditor(settings.joystickMode) { m -> vm.updateSettings { it.copy(joystickMode = m) } }
    }
    val expo: @Composable () -> Unit = {
        ExpoEditor(committed = settings.expo, onCommit = { v -> vm.updateSettings { it.copy(expo = v) } })
    }
    val gear: @Composable () -> Unit = {
        GearEditor(vm, settings.defaultGear) { g -> vm.updateSettings { it.copy(defaultGear = g) } }
    }
    val tilt: @Composable () -> Unit = {
        TiltEditor(settings.tiltSensitivity, settings.joystickMode == JoystickMode.TILT) { v ->
            vm.updateSettings { it.copy(tiltSensitivity = v) }
        }
    }
    val keepOn: @Composable () -> Unit = {
        SettingSwitchRow(
            icon = Icons.Rounded.LightMode,
            title = "保持螢幕常亮",
            subtitle = "連線期間螢幕不會自動關閉",
            checked = settings.keepScreenOn,
            onCheckedChange = { on -> vm.updateSettings { it.copy(keepScreenOn = on) } },
        )
    }

    Column(modifier) {
        PaneHeader(SettingsCategory.CONTROL)
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (wide) {
                AdaptiveColumns(
                    wide = true,
                    first = {
                        haptics()
                        SettingDivider()
                        Spacer(Modifier.height(8.dp))
                        mode()
                        Spacer(Modifier.height(10.dp))
                        SettingDivider()
                        Spacer(Modifier.height(8.dp))
                        gear()
                        Spacer(Modifier.height(10.dp))
                        SettingDivider()
                        keepOn()
                    },
                    second = {
                        Spacer(Modifier.height(8.dp))
                        expo()
                        Spacer(Modifier.height(10.dp))
                        SettingDivider()
                        Spacer(Modifier.height(8.dp))
                        tilt()
                        Spacer(Modifier.height(8.dp))
                    },
                )
            } else {
                haptics()
                SettingDivider()
                Spacer(Modifier.height(8.dp))
                mode()
                Spacer(Modifier.height(10.dp))
                SettingDivider()
                Spacer(Modifier.height(8.dp))
                expo()
                Spacer(Modifier.height(10.dp))
                SettingDivider()
                Spacer(Modifier.height(8.dp))
                gear()
                Spacer(Modifier.height(10.dp))
                SettingDivider()
                Spacer(Modifier.height(8.dp))
                tilt()
                Spacer(Modifier.height(10.dp))
                SettingDivider()
                keepOn()
            }
        }
    }
}

/** 搖桿模式：分段選擇 + 說明（橫向握持：左手油門、右手方向）。 */
@Composable
private fun ModeEditor(mode: JoystickMode, onSelect: (JoystickMode) -> Unit) {
    FieldLabel("搖桿模式", icon = Icons.Rounded.SportsEsports, trailing = "駕駛頁也能切換")
    Spacer(Modifier.height(8.dp))
    SegmentedSelector(
        options = JoystickMode.entries,
        selected = mode,
        onSelect = onSelect,
        label = { it.labelZh },
        icon = { m ->
            when (m) {
                JoystickMode.SINGLE -> Icons.Rounded.ControlCamera
                JoystickMode.DUAL -> Icons.Rounded.SportsEsports
                JoystickMode.TILT -> Icons.Rounded.ScreenRotation
            }
        },
    )
    Spacer(Modifier.height(6.dp))
    AnimatedCaption(
        when (mode) {
            JoystickMode.SINGLE -> "一支 360° 搖桿往任何方向推：前後＝速度、左右＝轉向"
            JoystickMode.DUAL -> "橫向握持：左手油門（前後）、右手方向（轉向）"
            JoystickMode.TILT -> "按住駕駛、傾斜手機來控制，可調整體感靈敏度"
        },
    )
}

/** 體感靈敏度（放開滑桿才寫入設定）。 */
@Composable
private fun TiltEditor(committed: Float, active: Boolean, onCommit: (Float) -> Unit) {
    var tilt by remember(committed) { mutableFloatStateOf(committed * 100f) }
    ValueSlider(
        label = "體感靈敏度",
        value = tilt,
        onValueChange = { tilt = it },
        range = 0f..100f,
        step = 5f,
        unit = "%",
        decimals = 0,
        description = if (active) "傾斜手機時的反應程度" else "切換到「體感」模式時使用",
        onValueChangeFinished = {
            val v = (tilt / 100f).coerceIn(0f, 1f)
            if (v != committed) onCommit(v)
        },
    )
}

/** 文字切換時上下滑動淡入。 */
@Composable
private fun AnimatedCaption(text: String) {
    val c = B570Theme.colors
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) { it / 2 } + fadeIn(tween(200)))
                .togetherWith(slideOutVertically(tween(160)) { -it / 2 } + fadeOut(tween(120)))
        },
        label = "caption",
    ) { t ->
        Text(t, style = B570Theme.type.caption, color = c.textSecondary, modifier = Modifier.fillMaxWidth())
    }
}

/** 搖桿曲線：滑桿 + 即時曲線預覽（曲線形變用彈簧追上，探針沿曲線來回掃動）。 */
@Composable
private fun ExpoEditor(committed: Float, onCommit: (Float) -> Unit) {
    val c = B570Theme.colors
    var local by remember(committed) { mutableFloatStateOf(committed) }
    val animated = animateFloatAsState(local, spring(dampingRatio = 0.7f, stiffness = 260f), label = "expo")

    ValueSlider(
        label = "搖桿曲線",
        value = local,
        onValueChange = { local = it },
        range = 0f..1f,
        step = 0.05f,
        decimals = 2,
        description = "越大中心越細膩，推到底一樣快",
        onValueChangeFinished = { if (local != committed) onCommit(local) },
    )
    Spacer(Modifier.height(10.dp))
    ExpoCurvePreview(expo = { animated.value })
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        val half = (joystickCurve(0.5f, local) * 100f).roundToInt()
        Text("搖桿推一半 → 輸出 $half %", style = B570Theme.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text(
            when {
                local < 0.15f -> "線性"
                local < 0.5f -> "柔和"
                local < 0.8f -> "細膩"
                else -> "極細膩"
            },
            style = B570Theme.type.label,
            color = c.accent,
        )
    }
}

/**
 * 曲線預覽畫布：格線、線性參考（虛線）、漸層填色曲線、掃動探針。
 * Brush / Stroke / PathEffect 都在 drawWithCache 建立；Path 重複使用（reset），每幀零配置。
 */
@Composable
private fun ExpoCurvePreview(expo: () -> Float, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val sweep = rememberInfiniteTransition(label = "probe")
    val probe = sweep.animateFloat(
        initialValue = 0.04f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "probe",
    )
    val curve = remember { Path() }
    val fill = remember { Path() }
    Spacer(
        modifier
            .fillMaxWidth()
            .height(148.dp)
            .glass(RoundedCornerShape(B570Theme.radii.s), fill = c.background.copy(alpha = 0.55f), borderWidth = 0.75.dp)
            .drawWithCache {
                val pad = 14.dp.toPx()
                val left = pad
                val top = pad * 0.8f
                val w = size.width - pad * 2f
                val h = size.height - pad * 1.6f
                val fillBrush = Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.30f), c.accent.copy(alpha = 0f)), startY = top, endY = top + h)
                val lineBrush = Brush.horizontalGradient(listOf(c.accent2, c.accent), startX = left, endX = left + w)
                val curveStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                val guide = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
                val hair = 1.dp.toPx()
                val probeOuter = 10.dp.toPx()
                val probeInner = 4.dp.toPx()
                onDrawBehind {
                    // 格線
                    for (i in 0..4) {
                        val x = left + w * i / 4f
                        val y = top + h * i / 4f
                        drawLine(c.grid, Offset(x, top), Offset(x, top + h), hair)
                        drawLine(c.grid, Offset(left, y), Offset(left + w, y), hair)
                    }
                    // 線性參考
                    drawLine(c.textMuted.copy(alpha = 0.7f), Offset(left, top + h), Offset(left + w, top), 1.5f * hair, pathEffect = dash)

                    // 曲線
                    val e = expo()
                    curve.reset()
                    fill.reset()
                    fill.moveTo(left, top + h)
                    for (i in 0..CURVE_SAMPLES) {
                        val x = i / CURVE_SAMPLES.toFloat()
                        val px = left + w * x
                        val py = top + h * (1f - joystickCurve(x, e))
                        if (i == 0) curve.moveTo(px, py) else curve.lineTo(px, py)
                        fill.lineTo(px, py)
                    }
                    fill.lineTo(left + w, top + h)
                    fill.close()
                    drawPath(fill, fillBrush)
                    drawPath(curve, lineBrush, style = curveStroke)

                    // 探針
                    val px = probe.value
                    val py = joystickCurve(px, e)
                    val cx = left + w * px
                    val cy = top + h * (1f - py)
                    drawLine(c.accent.copy(alpha = 0.45f), Offset(cx, top + h), Offset(cx, cy), hair, pathEffect = guide)
                    drawLine(c.accent.copy(alpha = 0.45f), Offset(left, cy), Offset(cx, cy), hair, pathEffect = guide)
                    drawCircle(c.accent.copy(alpha = 0.22f), probeOuter, Offset(cx, cy))
                    drawCircle(Color.White, probeInner, Offset(cx, cy))
                }
            },
    )
}

/** 預設檔位：分段選擇 + 動態速度條（顯示推到底的車速 / 轉向比例）。 */
@Composable
private fun GearEditor(vm: AppViewModel, gear: Gear, onSelect: (Gear) -> Unit) {
    val params by vm.params.collectAsStateWithLifecycle()
    val maxSpeed = params[Params.MAX_SPEED] ?: Params.byId(Params.MAX_SPEED)!!.default
    val speed = animateFloatAsState(gear.speedFactor, spring(dampingRatio = 0.62f, stiffness = 300f), label = "gearSpeed")
    val turn = animateFloatAsState(gear.turnFactor, spring(dampingRatio = 0.62f, stiffness = 300f), label = "gearTurn")

    FieldLabel("駕駛檔位", icon = Icons.Rounded.Speed, trailing = "駕駛頁也能隨時切換")
    Spacer(Modifier.height(8.dp))
    SegmentedSelector(
        options = Gear.entries,
        selected = gear,
        onSelect = onSelect,
        label = { it.labelZh },
    )
    Spacer(Modifier.height(10.dp))
    GearBar("車速", { speed.value }, "${(maxSpeed * gear.speedFactor).roundToInt()} mm/s")
    Spacer(Modifier.height(6.dp))
    GearBar("轉向", { turn.value }, "${(gear.turnFactor * 100).roundToInt()} %")
}

/** 單條比例條（填滿量在繪製階段讀取）。 */
@Composable
private fun GearBar(label: String, fraction: () -> Float, valueText: String) {
    val c = B570Theme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = B570Theme.type.caption, color = c.textMuted, modifier = Modifier.width(34.dp))
        Spacer(
            Modifier
                .weight(1f)
                .height(8.dp)
                .drawWithCache {
                    val r = CornerRadius(size.height / 2f)
                    val brush = Brush.horizontalGradient(listOf(c.accent2, c.accent), startX = 0f, endX = size.width)
                    onDrawBehind {
                        drawRoundRect(c.textMuted.copy(alpha = 0.22f), cornerRadius = r)
                        val f = fraction().coerceIn(0f, 1f)
                        drawRoundRect(brush = brush, size = Size(size.width * f, size.height), cornerRadius = r)
                    }
                },
        )
        Spacer(Modifier.width(10.dp))
        Text(valueText, style = B570Theme.type.label, color = c.textPrimary, modifier = Modifier.width(72.dp).padding(start = 2.dp))
    }
}
