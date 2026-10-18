package com.b570.pilot.ui.screens.drive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SensorsOff
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WifiTetheringError
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.b570.pilot.data.Gear
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.ui.components.AnalogText
import com.b570.pilot.ui.components.Joystick
import com.b570.pilot.ui.components.JoystickAxes
import com.b570.pilot.ui.components.JoystickStyle
import com.b570.pilot.ui.components.RockerSwitch
import com.b570.pilot.ui.components.analogBearing
import com.b570.pilot.ui.components.Haptics
import com.b570.pilot.ui.components.rememberDisplayRotation
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 搖桿在左右手區的垂直位置：手機上搖桿幾乎填滿高度（沒差）；平板上放在中線與下緣之間（拇指自然落點）。
 */
internal val ZoneAlignStart = BiasAlignment(-1f, 0.45f)
internal val ZoneAlignCenter = BiasAlignment(0f, 0.45f)
internal val ZoneAlignEnd = BiasAlignment(1f, 0.45f)

/** UI 測試用的 testTag（只加在 semantics，不影響行為）。 */
internal object DriveTestTags {
    const val THROTTLE = "drive.throttle"
    const val STEERING = "drive.steering"
    const val STICK = "drive.stick"
}

/** 目前檔位推到底的上限：車速 mm/s、轉向 %。 */
@Immutable
internal data class AxisCaps(val speed: Int, val turn: Int)

/** 車子實際的目標速度 / 轉向（相對目前檔位上限，-1..1），給搖桿的回授標記在繪製階段讀。 */
private fun feedbackOf(t: Telemetry?, caps: AxisCaps): Offset {
    if (t == null) return Offset.Zero
    val s = t.targetSpeed / caps.speed.coerceAtLeast(1).toFloat()
    val r = t.targetTurn / (caps.turn.coerceAtLeast(1) * 10f)
    return Offset(r.coerceIn(-1f, 1f), s.coerceIn(-1f, 1f))
}

// ------------------------------------------------------------------ 搖桿區（左右手）

/**
 * 雙搖桿左手：直立油門搖桿（外側）+ 送出車速讀數（內側）。
 * 搖桿事件直接同步呼叫 [DriveController]（無 coroutine、無 debounce）；放開只歸零自己的軸。
 */
@Composable
internal fun ThrottleZone(
    controller: DriveController,
    telem: State<Telemetry?>,
    expo: Float,
    caps: AxisCaps,
    enabled: Boolean,
    block: ControlBlock,
    stickMax: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val h = minOf(maxHeight, stickMax)
        val w = minOf(h * 0.42f, maxWidth * 0.6f)
        val readoutStyle = readoutStyleFor(maxWidth - w - 8.dp, h)
        Row(
            Modifier.align(ZoneAlignStart).fillMaxWidth().height(h),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(w, h).testTag(DriveTestTags.THROTTLE)) {
                Joystick(
                    onMove = { _, y -> controller.setForward(y) },
                    modifier = Modifier.fillMaxSize(),
                    axes = JoystickAxes.VERTICAL,
                    expo = expo,
                    enabled = enabled,
                    style = JoystickStyle.THROTTLE,
                    showReadout = true,
                    ghost = true,
                    feedback = { Offset(0f, feedbackOf(telem.value, caps).y) },
                )
            }
            Spacer(Modifier.width(8.dp))
            AxisReadout(AxisKind.SPEED, controller, caps, readoutStyle, Modifier.weight(1f))
        }
        // 蓋整個左手區（油門搖桿本身太窄放不下說明）；不攔截觸控
        ZoneBlockOverlay(block, Modifier.matchParentSize())
    }
}

/** 雙搖桿右手：橫向轉向搖桿（下方，靠右手拇指）+ 送出轉向讀數（上方）。 */
@Composable
internal fun SteeringZone(
    controller: DriveController,
    telem: State<Telemetry?>,
    expo: Float,
    caps: AxisCaps,
    enabled: Boolean,
    block: ControlBlock,
    stickMax: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val w = minOf(maxWidth, stickMax * 1.35f)
        val h = minOf(w / 2.2f, maxHeight * 0.62f)
        // 平板：讀數 + 方向搖桿不要拉滿整個高度，跟左手搖桿一樣放在偏下方
        val colH = minOf(maxHeight, h + 8.dp + 140.dp)
        val readoutStyle = readoutStyleFor(maxWidth, colH - h - 8.dp)
        Column(
            Modifier.align(ZoneAlignEnd).fillMaxWidth().height(colH),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AxisReadout(AxisKind.TURN, controller, caps, readoutStyle, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(w, h).testTag(DriveTestTags.STEERING)) {
                Joystick(
                    onMove = { x, _ -> controller.setTurn(x) },
                    modifier = Modifier.fillMaxSize(),
                    axes = JoystickAxes.HORIZONTAL,
                    expo = expo,
                    enabled = enabled,
                    style = JoystickStyle.STEERING,
                    showReadout = true,
                    ghost = true,
                    feedback = { Offset(feedbackOf(telem.value, caps).x, 0f) },
                )
            }
        }
        // 蓋整個右手區（方向搖桿很矮）；不攔截觸控
        ZoneBlockOverlay(block, Modifier.matchParentSize())
    }
}

/**
 * 360° 搖桿區：一支大的圓形類比搖桿（像手把的類比搖桿，往任何方向推）。
 * Arcade 換算：搖桿輸出 (x, y) 已是圓形夾限 + 圓形死區 + expo（只套推桿量、方向不變）+ 正方向吸附 →
 * `controller.set(forward = y, turn = x)` → 車速 = y × MAX_SPEED × 檔位速度比、轉向 = x × 1000‰ × 檔位轉向比。
 * 純前推 x = 0（轉向 0）、純橫推 y = 0（原地旋轉）、斜推 = 弧線。
 * 搖桿事件直接同步呼叫 [DriveController]（無 coroutine、無 debounce）；放開 / 取消 / 停用 → (0, 0)。
 */
@Composable
internal fun Stick360Zone(
    controller: DriveController,
    telem: State<Telemetry?>,
    expo: Float,
    caps: AxisCaps,
    enabled: Boolean,
    block: ControlBlock,
    stickMax: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val s = minOf(maxWidth, maxHeight, stickMax)
        Box(
            Modifier
                .align(ZoneAlignCenter)
                .size(s)
                .testTag(DriveTestTags.STICK)
                .semantics { contentDescription = "360° 搖桿" },
        ) {
            Joystick(
                onMove = { x, y -> controller.set(y, x) },
                modifier = Modifier.fillMaxSize(),
                axes = JoystickAxes.BOTH,
                style = JoystickStyle.ANALOG_360,
                expo = expo,
                enabled = enabled,
                showReadout = true,
                ghost = true,
                feedback = { feedbackOf(telem.value, caps) },
            )
            ZoneBlockOverlay(block, Modifier.matchParentSize().clip(CircleShape))
        }
    }
}

/**
 * 360° 模式另一隻手的「手把按鍵區」：A/B/X/Y 菱形快捷動作（A 原地轉圈 / B 搖擺 / X 點頭 / Y 更多 → 編排）
 * + 直立檔位翹板（放在靠中控台那側；[RcMetrics.gearInCluster] = false 時檔位留在上緣列）。
 * @param onRight 按鍵區在右手（搖桿在左）
 */
@Composable
internal fun GamepadCluster(
    m: RcMetrics,
    onRight: Boolean,
    gear: Gear,
    onGear: (Gear) -> Unit,
    busyId: String?,
    playingId: String?,
    dim: Boolean,
    progress: State<Float>,
    onPlay: (QuickAction) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    BoxWithConstraints(modifier, contentAlignment = ZoneAlignCenter) {
        val zoneH = minOf(maxHeight, m.stickMax + 40.dp)
        val showGear = m.gearInCluster
        val padW = if (showGear) maxWidth - m.rockerW - m.rockerGap else maxWidth
        val d = ActionPadGeometry.diameterFor(padW, zoneH)
        val rockerH = minOf(zoneH, 156.dp)
        val rocker: @Composable () -> Unit = {
            RockerSwitch(
                options = Gear.entries,
                selected = gear,
                onSelect = onGear,
                modifier = Modifier.size(m.rockerW, rockerH),
                label = { it.driveLabel },
                title = "檔位",
                vertical = true,
                colorOf = { g ->
                    when (g) {
                        Gear.SLOW -> c.ok
                        Gear.NORMAL -> c.accent
                        Gear.FAST -> c.warn
                    }
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showGear && onRight) {
                rocker()
                Spacer(Modifier.width(m.rockerGap))
            }
            QuickActionPad(
                busyId = busyId,
                playingId = playingId,
                dim = dim,
                progress = progress,
                onPlay = onPlay,
                onMore = onMore,
                diameter = d,
            )
            if (showGear && !onRight) {
                Spacer(Modifier.width(m.rockerGap))
                rocker()
            }
        }
    }
}

internal enum class AxisKind { SPEED, TURN }

/** FULL = 大數字 + 上限；NARROW = 小數字（窄欄）；ROW = 單行（矮欄 / 中控台讀數列）。 */
internal enum class AxisStyle { FULL, NARROW, ROW }

private fun readoutStyleFor(w: Dp, h: Dp): AxisStyle = when {
    h < 92.dp -> AxisStyle.ROW
    w < 88.dp -> AxisStyle.NARROW
    else -> AxisStyle.FULL
}

/** 送出指令讀數（小葉節點：只有它跟著搖桿重組）。 */
@Composable
internal fun AxisReadout(kind: AxisKind, controller: DriveController, caps: AxisCaps, style: AxisStyle, modifier: Modifier) {
    val c = B570Theme.colors
    val type = B570Theme.type
    val speed = kind == AxisKind.SPEED
    val v = if (speed) controller.cmdSpeed.intValue else controller.cmdTurn.intValue
    val frac = if (speed) v / caps.speed.coerceAtLeast(1).toFloat() else v / (caps.turn.coerceAtLeast(1) * 10f)
    val barColor by animateColorAsState(
        when {
            v == 0 -> c.textMuted
            speed && v < 0 -> c.warn
            else -> c.accent
        },
        label = "axisColor",
    )
    val valueText = when {
        speed -> if (v == 0) "0" else String.format(Locale.US, "%+d", v)
        v > 0 -> "右 ${(v + 5) / 10}"
        v < 0 -> "左 ${(-v + 5) / 10}"
        else -> "0"
    }
    val label = if (speed) "速度" else "轉向"
    val unit = if (speed) "mm/s" else "%"
    val capText = if (speed) "上限 ${caps.speed}" else "上限 ${caps.turn} %"
    val valueColor = if (v == 0) c.textSecondary else c.textPrimary
    val barWidth = if (style == AxisStyle.NARROW) 44.dp else 56.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (style == AxisStyle.ROW) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = type.caption, color = c.textSecondary, maxLines = 1)
                Spacer(Modifier.width(5.dp))
                Text(valueText, style = type.readoutS, color = valueColor, maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Text(unit, style = type.caption, color = c.textMuted, maxLines = 1)
            }
        } else {
            Text(label, style = type.caption, color = c.textSecondary, maxLines = 1)
            Text(valueText, style = if (style == AxisStyle.NARROW) type.readoutS else type.readoutM, color = valueColor, maxLines = 1)
            Text(unit, style = type.caption, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.height(if (style == AxisStyle.ROW) 4.dp else 6.dp))
        Box(
            Modifier
                .width(barWidth)
                .height(4.dp)
                .drawBehind {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(c.textMuted.copy(alpha = 0.22f), cornerRadius = r)
                    val f = frac.coerceIn(-1f, 1f)
                    val half = size.width / 2f
                    val w = abs(f) * half
                    if (w > 0.5f) {
                        val x = if (f >= 0f) half else half - w
                        drawRoundRect(barColor, topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = r)
                    }
                    drawLine(c.textSecondary.copy(alpha = 0.6f), Offset(half, -2f), Offset(half, size.height + 2f), 1f)
                },
        )
        if (style != AxisStyle.ROW) {
            Spacer(Modifier.height(4.dp))
            Text(capText, style = type.caption, color = c.textMuted, maxLines = 1)
        }
    }
}

/**
 * 中控台向量讀數列（360° 模式沒有提示時）：小向量儀（跟搖桿同步：方向 + 推桿量）+ 角度° / 推桿 % + 送出的速度 / 轉向。
 * 依寬度收起：≥ 360 dp 全部（速度 / 轉向含長條）；≥ 210 dp 向量儀 + 角度 / % + 兩行指令；更窄只剩向量儀 + 兩行指令
 * （搖桿本身也有角度 / % 讀數）。全部讀 [DriveController] 的整數指令（小葉節點 / 繪製階段）。
 */
@Composable
internal fun VectorStrip(controller: DriveController, caps: AxisCaps, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(modifier.glass(shape)) {
        val w = maxWidth
        val scopeD = minOf(maxHeight - 6.dp, 40.dp)
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VectorScope(controller, caps, Modifier.size(scopeD))
            Spacer(Modifier.width(8.dp))
            if (w >= 210.dp) {
                VectorText(controller, caps)
                Spacer(Modifier.width(10.dp))
            }
            if (w >= 360.dp) {
                AxisReadout(AxisKind.SPEED, controller, caps, AxisStyle.ROW, Modifier.weight(1f))
                AxisReadout(AxisKind.TURN, controller, caps, AxisStyle.ROW, Modifier.weight(1f))
            } else {
                CommandLines(controller, Modifier.weight(1f))
            }
        }
    }
}

/** 送出的 (x 轉向, y 速度) 相對目前檔位上限 -1..1。 */
private fun commandVector(controller: DriveController, caps: AxisCaps, out: FloatArray) {
    out[0] = (controller.cmdTurn.intValue / (caps.turn.coerceAtLeast(1) * 10f)).coerceIn(-1f, 1f)
    out[1] = (controller.cmdSpeed.intValue / caps.speed.coerceAtLeast(1).toFloat()).coerceIn(-1f, 1f)
}

/** 小向量儀：圓框 + 8 方位點 + 指向送出方向的發光弧與箭頭（只在繪製階段讀指令，零配置）。 */
@Composable
private fun VectorScope(controller: DriveController, caps: AxisCaps, modifier: Modifier) {
    val c = B570Theme.colors
    Box(
        modifier.drawWithCache {
            val ctr = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 2.dp.toPx()
            val thin = Stroke(1.dp.toPx())
            val arc = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
            val arcTL = Offset(ctr.x - r, ctr.y - r)
            val arcSize = Size(r * 2f, r * 2f)
            val vecW = 2.dp.toPx()
            val dotR = 2.5.dp.toPx()
            val tickR = 1.2.dp.toPx()
            val tickPts = FloatArray(16)
            for (i in 0 until 8) {
                val rad = Math.toRadians((i * 45 - 90).toDouble())
                tickPts[i * 2] = ctr.x + cos(rad).toFloat() * r * 0.78f
                tickPts[i * 2 + 1] = ctr.y + sin(rad).toFloat() * r * 0.78f
            }
            val v = FloatArray(2)
            onDrawBehind {
                commandVector(controller, caps, v)
                var vx = v[0]
                var vy = v[1]
                var len = hypot(vx, vy)
                if (len > 1f) {
                    vx /= len
                    vy /= len
                    len = 1f
                }
                drawCircle(c.background.copy(alpha = 0.55f), r, ctr)
                drawCircle(c.glassBorder, r, ctr, style = thin)
                drawCircle(c.textMuted.copy(alpha = 0.3f), r * 0.45f, ctr, style = thin)
                for (i in 0 until 8) {
                    drawCircle(
                        if (i % 2 == 0) c.textSecondary.copy(alpha = 0.7f) else c.textMuted.copy(alpha = 0.5f),
                        tickR, Offset(tickPts[i * 2], tickPts[i * 2 + 1]),
                    )
                }
                if (len > 0f) {
                    val b = analogBearing(vx, vy)
                    val sw = 14f + 76f * len
                    drawArc(c.accent.copy(alpha = 0.45f + 0.55f * len), b - 90f - sw / 2f, sw, false, arcTL, arcSize, style = arc)
                    val tip = Offset(ctr.x + vx * r * 0.8f, ctr.y - vy * r * 0.8f)
                    drawLine(c.accent, ctr, tip, vecW, StrokeCap.Round)
                    drawCircle(c.accent, dotR, tip)
                } else {
                    drawCircle(c.textMuted, dotR * 0.8f, ctr)
                }
            }
        },
    )
}

/** 角度° + 推桿 %（小葉節點；字串預建）。 */
@Composable
private fun VectorText(controller: DriveController, caps: AxisCaps) {
    val c = B570Theme.colors
    val type = B570Theme.type
    val v = remember { FloatArray(2) }
    commandVector(controller, caps, v)
    val len = minOf(hypot(v[0], v[1]), 1f)
    val moving = len > 0f
    Column(verticalArrangement = Arrangement.Center) {
        Text(
            if (moving) AnalogText.angle(analogBearing(v[0], v[1]).roundToInt()) else "---°",
            style = type.label.copy(fontWeight = FontWeight.SemiBold),
            color = if (moving) c.textPrimary else c.textMuted,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            "推桿 " + AnalogText.percent((len * 100f).roundToInt()),
            style = type.caption,
            color = if (moving) c.accent else c.textMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 兩行指令：車速 mm/s / 轉向 %（窄中控台）。 */
@Composable
private fun CommandLines(controller: DriveController, modifier: Modifier) {
    val c = B570Theme.colors
    val type = B570Theme.type
    val s = controller.cmdSpeed.intValue
    val t = controller.cmdTurn.intValue
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(
            if (s == 0) "速度 0 mm/s" else String.format(Locale.US, "速度 %+d mm/s", s),
            style = type.caption,
            color = if (s == 0) c.textSecondary else if (s < 0) c.warn else c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                t > 0 -> "轉向 右 ${(t + 5) / 10} %"
                t < 0 -> "轉向 左 ${(-t + 5) / 10} %"
                else -> "轉向 0"
            },
            style = type.caption,
            color = if (t == 0) c.textSecondary else c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ 體感模式

/**
 * 體感感測器的生命週期：只在體感模式組合。
 * ON_RESUME 開始、ON_PAUSE 停止並中止駕駛；螢幕左右翻轉（90 ⇄ 270，不重建 Activity）→ 立即改用新座標，
 * 正在駕駛就中止並要求重新按下（零點與軸向都失效）。
 */
@Composable
internal fun TiltBinding(tracker: TiltTracker, session: TiltSession, onRotationAbort: () -> Unit) {
    val rotation by rememberDisplayRotation()
    val rotationNow by rememberUpdatedState(rotation)
    val abortCb by rememberUpdatedState(onRotationAbort)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(tracker, session, lifecycleOwner) {
        tracker.onSample = session::onSample
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> tracker.start(rotationNow)
                Lifecycle.Event.ON_PAUSE -> {
                    tracker.stop()
                    session.abort()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            tracker.stop()
            tracker.onSample = null
            session.abort()
        }
    }
    val lastRotation = remember { intArrayOf(rotation) }
    SideEffect {
        if (lastRotation[0] != rotation) {
            lastRotation[0] = rotation
            tracker.setRotation(rotation)
            if (session.held.value) {
                session.abort()
                abortCb()
            }
        }
    }
}

/** 體感模式左右兩個「按住駕駛」鈕。 */
internal enum class PadSide { LEFT, RIGHT }

/**
 * 「按住駕駛」安全開關（左右各一，拇指放上去）：至少按住一個才依手機傾斜送指令，全部放開即停。
 * 左鈕顯示油門（前後傾）、右鈕顯示轉向（像方向盤轉）。
 * `enabled` 變成 false（訊號中斷、選單打開…）或 [TiltSession.resetGen] 改變 → 結束進行中的手勢（放開、歸零），要重新按下。
 */
@Composable
internal fun DeadManPad(
    side: PadSide,
    available: Boolean,
    enabled: Boolean,
    session: TiltSession,
    controller: DriveController,
    haptics: Haptics,
    block: ControlBlock,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val type = B570Theme.type
    val idx = side.ordinal
    val hapticsNow by rememberUpdatedState(haptics)
    val held = session.held.value
    var touching by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (touching) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "deadScale")
    val fill by animateColorAsState(if (held) c.accent.copy(alpha = if (touching) 0.22f else 0.12f) else c.glass, label = "deadFill")
    val border by animateColorAsState(if (held) c.accent else c.glassBorder, label = "deadBorder")
    val glow by animateDpAsState(if (touching) 22.dp else if (held) 10.dp else 0.dp, label = "deadGlow")
    val heldAnim = animateFloatAsState(if (held) 1f else 0f, tween(200), label = "deadHeld")
    val shape = RoundedCornerShape(B570Theme.radii.xl)
    val resetGen = session.resetGen.intValue
    val usable = available && enabled
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (usable) 1f else 0.6f }
            .shadow(glow, shape, clip = false, ambientColor = c.accent, spotColor = c.accent)
            .clip(shape)
            .background(fill)
            .border(1.5.dp, border, shape)
            .systemGestureExclusion()
            // 指示條：左 = 油門（直條），右 = 轉向（橫條）；只在繪製階段讀傾斜量
            .drawWithCache {
                val r = CornerRadius(3.dp.toPx())
                val thick = 6.dp.toPx()
                val m = 14.dp.toPx()
                val track = c.textMuted.copy(alpha = 0.18f)
                onDrawBehind {
                    val h = heldAnim.value
                    if (h <= 0.01f) return@onDrawBehind
                    val t = session.tilt.value
                    if (side == PadSide.LEFT) {
                        // 內側直條：中線 = 0，往上 = 前進
                        val x = size.width - m - thick
                        val top = m
                        val len = size.height - m * 2f
                        val mid = top + len / 2f
                        drawRoundRect(track.copy(alpha = track.alpha * h), Offset(x, top), Size(thick, len), r)
                        val f = t.y.coerceIn(-1f, 1f)
                        val l = abs(f) * len / 2f
                        if (l > 0.5f) {
                            val col = if (f >= 0f) c.accent else c.warn
                            drawRoundRect(col.copy(alpha = h), Offset(x, if (f >= 0f) mid - l else mid), Size(thick, l), r)
                        }
                        drawLine(c.textSecondary.copy(alpha = 0.6f * h), Offset(x - 3f, mid), Offset(x + thick + 3f, mid), 1.5f)
                    } else {
                        // 下緣橫條：中線 = 0，往右 = 右轉
                        val y = size.height - m - thick
                        val left = m
                        val len = size.width - m * 2f
                        val mid = left + len / 2f
                        drawRoundRect(track.copy(alpha = track.alpha * h), Offset(left, y), Size(len, thick), r)
                        val f = t.x.coerceIn(-1f, 1f)
                        val l = abs(f) * len / 2f
                        if (l > 0.5f) drawRoundRect(c.accent.copy(alpha = h), Offset(if (f >= 0f) mid else mid - l, y), Size(l, thick), r)
                        drawLine(c.textSecondary.copy(alpha = 0.6f * h), Offset(mid, y - 3f), Offset(mid, y + thick + 3f), 1.5f)
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "按住駕駛"
                stateDescription = if (held) "駕駛中" else "未按住"
            }
            // enabled / resetGen 改變會取消進行中的手勢 → finally 放開（歸零）；恢復後要重新按下
            .pointerInput(usable, resetGen) {
                if (!usable) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    touching = true
                    if (session.pressPad(idx)) hapticsNow.confirm() else hapticsNow.tick()
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            ch.consume()
                        }
                    } finally {
                        touching = false
                        if (session.releasePad(idx)) hapticsNow.tick()
                    }
                }
            }
            .padding(if (compact) 8.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!available) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.SensorsOff, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(6.dp))
                Text("這支手機不支援體感", style = type.titleS, color = c.textPrimary, textAlign = TextAlign.Center)
                Text("請改用搖桿駕駛", style = type.caption, color = c.textSecondary, textAlign = TextAlign.Center)
            }
        } else {
            AnimatedContent(
                targetState = held,
                transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(120))) },
                contentAlignment = Alignment.Center,
                label = "deadContent",
            ) { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (h) {
                        Text("駕駛中", style = if (compact) type.titleS else type.titleM, color = c.accent, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        TiltCommandText(controller, side)
                        Spacer(Modifier.height(2.dp))
                        Text("放開即停車", style = type.caption, color = c.textMuted, maxLines = 1)
                    } else {
                        Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = c.accent, modifier = Modifier.size(if (compact) 24.dp else 30.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("按住駕駛", style = if (compact) type.titleS else type.titleM, color = c.textPrimary, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (side == PadSide.LEFT) "前後傾＝油門" else "方向盤轉＝轉向",
                            style = type.caption, color = c.textSecondary, textAlign = TextAlign.Center,
                        )
                        Text("按下即歸零", style = type.caption, color = c.textMuted, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        ZoneBlockOverlay(block, Modifier.matchParentSize())
    }
}

@Composable
private fun TiltCommandText(controller: DriveController, side: PadSide) {
    val text = if (side == PadSide.LEFT) {
        val s = controller.cmdSpeed.intValue
        if (s == 0) "0 mm/s" else String.format(Locale.US, "%+d mm/s", s)
    } else {
        val t = controller.cmdTurn.intValue
        when {
            t > 0 -> "右 ${(t + 5) / 10} %"
            t < 0 -> "左 ${(-t + 5) / 10} %"
            else -> "直行"
        }
    }
    Text(text, style = B570Theme.type.readoutS, color = B570Theme.colors.textPrimary, maxLines = 1)
}

// ------------------------------------------------------------------ 狀態覆蓋層

/**
 * 蓋在搖桿 / 按住駕駛上的狀態（未連線 / 連線中 / 自動重連中 / 等待資料 / 訊號中斷 / 感測器異常 / 低電量 / 先按「啟動平衡」）。
 * **不攔截觸控**（沒有 pointerInput）；需要擋的狀態由搖桿 `enabled = false` 處理。
 */
@Composable
internal fun ZoneBlockOverlay(block: ControlBlock, modifier: Modifier) {
    val c = B570Theme.colors
    val type = B570Theme.type
    AnimatedContent(
        targetState = block,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(240)).togetherWith(fadeOut(tween(200))) },
        contentAlignment = Alignment.Center,
        label = "block",
    ) { b ->
        if (b == ControlBlock.NONE) {
            Box(Modifier.fillMaxSize())
        } else {
            val subtle = b == ControlBlock.MOTORS_OFF
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(B570Theme.radii.l))
                    .background(c.background.copy(alpha = if (subtle) 0.30f else 0.60f)),
                contentAlignment = Alignment.Center,
            ) {
                val tint = when (b) {
                    ControlBlock.LINK_LOST, ControlBlock.RECONNECTING -> c.warn
                    ControlBlock.SENSOR, ControlBlock.LOW_BATT -> c.danger
                    ControlBlock.OFFLINE -> c.textSecondary
                    else -> c.accent
                }
                val wide = maxWidth >= 230.dp
                // 窄區域：「先按『啟動平衡』」標題就夠了，不要用大卡片蓋住讀數
                val showBody = if (wide) maxHeight >= 72.dp else !subtle && maxHeight >= 120.dp
                val icon: @Composable () -> Unit = {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        when (b) {
                            ControlBlock.CONNECTING, ControlBlock.WAITING, ControlBlock.RECONNECTING ->
                                CircularProgressIndicator(Modifier.size(15.dp), color = tint, strokeWidth = 2.dp)
                            else -> Icon(
                                when (b) {
                                    ControlBlock.LINK_LOST -> Icons.Rounded.WifiTetheringError
                                    ControlBlock.SENSOR -> Icons.Rounded.SensorsOff
                                    ControlBlock.LOW_BATT -> Icons.Rounded.BatteryAlert
                                    ControlBlock.OFFLINE -> Icons.Rounded.LinkOff
                                    else -> Icons.Rounded.PowerSettingsNew
                                },
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                if (wide) {
                    // 寬：膠囊（圖示 + 標題 / 說明各一行）
                    Row(
                        Modifier
                            .padding(6.dp)
                            .widthIn(max = 300.dp)
                            .heightIn(min = 36.dp)
                            .glass(RoundedCornerShape(50), fill = c.surface.copy(alpha = 0.88f), glow = tint.copy(alpha = 0.4f), glowElevation = 10.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        icon()
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                b.titleZh,
                                style = type.label.copy(fontWeight = FontWeight.SemiBold),
                                color = c.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (showBody) {
                                Text(b.bodyZh, style = type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    // 窄（小手機、體感按鈕）：直排卡片，文字可換行，不會被切掉
                    Column(
                        Modifier
                            .padding(6.dp)
                            .glass(RoundedCornerShape(16.dp), fill = c.surface.copy(alpha = 0.88f), glow = tint.copy(alpha = 0.4f), glowElevation = 10.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        icon()
                        Spacer(Modifier.height(4.dp))
                        Text(
                            b.titleZh,
                            style = type.label.copy(fontWeight = FontWeight.SemiBold),
                            color = c.textPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (showBody) {
                            Spacer(Modifier.height(2.dp))
                            Text(b.bodyZh, style = type.caption, color = c.textSecondary, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
