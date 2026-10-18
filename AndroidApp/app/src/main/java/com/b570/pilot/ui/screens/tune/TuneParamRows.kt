package com.b570.pilot.ui.screens.tune

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.b570.pilot.protocol.ParamDef
import com.b570.pilot.protocol.ParamGroup
import com.b570.pilot.protocol.Params
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.NeonSlider
import com.b570.pilot.ui.components.NumberInputDialog
import com.b570.pilot.ui.components.RepeatStepButton
import com.b570.pilot.ui.components.SegmentedSelector
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** PID 增益所在的分組（馬達運轉中調整要提醒）。 */
internal val GAIN_GROUPS: Set<ParamGroup> = setOf(ParamGroup.BALANCE, ParamGroup.VELOCITY, ParamGroup.TURN)

internal fun groupIcon(g: ParamGroup): ImageVector = when (g) {
    ParamGroup.BALANCE -> Icons.Rounded.Balance
    ParamGroup.VELOCITY -> Icons.Rounded.Speed
    ParamGroup.TURN -> Icons.Rounded.TurnRight
    ParamGroup.MOTION -> Icons.AutoMirrored.Rounded.TrendingUp
    ParamGroup.SAFETY -> Icons.Rounded.Shield
    ParamGroup.SYSTEM -> Icons.Rounded.Memory
}

/** 對齊步進並依顯示位數四捨五入（與共用 ValueSlider 相同規則）。 */
internal fun snapValue(def: ParamDef, v: Float): Float {
    var s = v.coerceIn(def.min, def.max)
    if (def.step > 0f) s = (def.min + ((s - def.min) / def.step).roundToLong() * def.step).coerceIn(def.min, def.max)
    val k = 10f.pow(def.decimals)
    return (s * k).roundToLong() / k
}

internal fun formatNumber(def: ParamDef, v: Float): String = String.format(Locale.US, "%.${def.decimals}f", v)

/**
 * 可收合的參數分組卡片。
 * @param editable 已連線（Connected）才可修改；否則唯讀顯示最後讀到的值
 * @param columns 卡片內參數排幾欄（寬螢幕 2 欄）
 * @param compactRows 參數列窄：說明文字放到標題列下方
 */
@Composable
internal fun ParamGroupCard(
    group: ParamGroup,
    items: List<ParamUi>,
    ctl: TuneController,
    expanded: Boolean,
    editable: Boolean,
    motorsOn: Boolean,
    modifier: Modifier = Modifier,
    columns: Int = 1,
    compactRows: Boolean = false,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val modifiedCount by remember(items) { derivedStateOf { items.count { it.isModified } } }
    val stagedCount by remember(items) { derivedStateOf { items.count { it.staged != null } } }
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "chevron",
    )
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        glow = if (stagedCount > 0) c.warn.copy(alpha = 0.5f) else null,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .noRippleClickable { haptics.tick(); ctl.toggleGroup(group) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.24f), c.accent2.copy(alpha = 0.18f))))
                    .border(1.dp, c.accent.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(groupIcon(group), null, tint = c.accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.labelZh, style = B570Theme.type.titleS, color = c.textPrimary)
                Text(
                    if (modifiedCount > 0) "${items.size} 項參數 · 已調整 $modifiedCount 項" else "${items.size} 項參數 · 皆為預設",
                    style = B570Theme.type.caption,
                    color = if (modifiedCount > 0) c.accent.copy(alpha = 0.85f) else c.textSecondary,
                )
            }
            AnimatedVisibility(
                visible = stagedCount > 0,
                enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f)) + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                MiniBadge("$stagedCount 項待套用", c.warn, Modifier.padding(end = 8.dp))
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收合" else "展開",
                tint = c.textSecondary,
                modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(dampingRatio = 0.86f, stiffness = 360f), expandFrom = Alignment.Top) +
                fadeIn(tween(240, delayMillis = 50)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 480f), shrinkTowards = Alignment.Top) +
                fadeOut(tween(140)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = TUNE_CARD_PAD.dp, end = TUNE_CARD_PAD.dp, bottom = 18.dp)) {
                HorizontalDivider(color = c.divider)
                Spacer(Modifier.height(14.dp))
                if (group in GAIN_GROUPS) {
                    AnimatedVisibility(
                        visible = motorsOn && editable,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        HintRow(Icons.Rounded.WarningAmber, "調整中請注意車身穩定", c.warn, Modifier.padding(bottom = 14.dp), filled = true)
                    }
                }
                if (columns <= 1) {
                    items.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 14.dp), color = c.divider.copy(alpha = 0.6f))
                        key(p.def.id) { ParamRow(p, ctl, editable, motorsOn, compactRows) }
                    }
                } else {
                    // 寬螢幕：兩欄（同一列頂端對齊；最後一列不足補空白）
                    val rows = remember(items, columns) { items.chunked(columns) }
                    rows.forEachIndexed { r, row ->
                        if (r > 0) HorizontalDivider(Modifier.padding(vertical = 14.dp), color = c.divider.copy(alpha = 0.6f))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(TUNE_COL_GAP.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            row.forEach { p ->
                                key(p.def.id) {
                                    Box(Modifier.weight(1f)) { ParamRow(p, ctl, editable, motorsOn, compactRows) }
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamRow(p: ParamUi, ctl: TuneController, editable: Boolean, motorsOn: Boolean, compact: Boolean) {
    if (p.def.options != null) ParamOptionRow(p, ctl, editable, motorsOn, compact)
    else ParamSliderRow(p, ctl, editable, compact)
}

/** 數值參數：標題 + 說明 + 數值膠囊（點擊輸入）+ −/＋ 長按連續 + 滑軌（含預設刻度、車上值標記）。 */
@Composable
private fun ParamSliderRow(p: ParamUi, ctl: TuneController, editable: Boolean, compact: Boolean) {
    val def = p.def
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val range = remember(def) { def.min..def.max }
    var interacting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val display = p.display
    val staged = p.staged != null
    val springSpec = remember { spring<Float>(dampingRatio = 0.82f, stiffness = 520f) }
    val snapSpec = remember { snap<Float>() }
    val shown by animateFloatAsState(display, if (interacting) snapSpec else springSpec, label = "param")
    val accent = if (staged) c.warn else c.accent

    fun update(v: Float) {
        val prev = p.display
        val nv = snapValue(def, v)
        if (nv == prev) return
        // 經過預設值時給一下「刻度」觸感
        if ((prev - def.default) * (nv - def.default) < 0f || nv == def.default) haptics.tick()
        interacting = true
        ctl.change(p, nv)
    }

    fun nudge(dir: Int) {
        val cur = p.display
        val nv = snapValue(def, cur + dir * def.step)
        if (nv != cur) {
            haptics.tick()
            interacting = true
            ctl.change(p, nv)
        } else {
            haptics.reject()
        }
    }

    val done: () -> Unit = {
        interacting = false
        ctl.finish(p)
    }

    Column(Modifier.fillMaxWidth()) {
        ParamHeader(
            p = p,
            editable = editable,
            compact = compact,
            onReset = {
                haptics.click()
                interacting = false
                ctl.change(p, def.default)
                ctl.finish(p)
            },
        ) {
            ValuePill(
                text = formatNumber(def, display),
                unit = def.unit,
                staged = staged,
                modified = p.isModified,
                enabled = editable,
                onClick = { haptics.click(); editing = true },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.graphicsLayer { alpha = if (editable) 1f else 0.4f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RepeatStepButton(Icons.Rounded.Remove, editable, onStep = { nudge(-1) }, onDone = done)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .drawWithCache {
                        val pad = 9.dp.toPx() // 與 NeonSlider 的拇指半徑相同
                        val w = size.width - 2 * pad
                        val span = (def.max - def.min).takeIf { it > 0f } ?: 1f
                        val cy = size.height / 2f
                        val tickHalf = 8.dp.toPx()
                        val tickW = 2.dp.toPx()
                        val dx = pad + w * ((def.default - def.min) / span).coerceIn(0f, 1f)
                        val ring = Stroke(1.6.dp.toPx())
                        val ringR = 6.5.dp.toPx()
                        val tickColor = c.textSecondary.copy(alpha = 0.55f)
                        onDrawBehind {
                            drawLine(tickColor, Offset(dx, cy - tickHalf), Offset(dx, cy + tickHalf), tickW, StrokeCap.Round)
                            if (p.staged != null) {
                                // 暫存中：標出車上目前的位置
                                val cx = pad + w * ((p.base - def.min) / span).coerceIn(0f, 1f)
                                drawCircle(c.warn.copy(alpha = 0.8f), ringR, Offset(cx, cy), style = ring)
                            }
                        }
                    },
            ) {
                NeonSlider(
                    // 拖曳中直接用目前值（零延遲），放開後才用彈簧動畫
                    value = if (interacting) display else shown,
                    onValueChange = { update(it) },
                    range = range,
                    enabled = editable,
                    onValueChangeFinished = done,
                    accent = accent,
                )
            }
            Spacer(Modifier.width(10.dp))
            RepeatStepButton(Icons.Rounded.Add, editable, onStep = { nudge(1) }, onDone = done)
        }
        ParamFooter(p)
    }

    if (editing) {
        NumberInputDialog(
            title = def.labelZh,
            initial = display,
            range = range,
            decimals = def.decimals,
            unit = def.unit,
            onDismiss = { editing = false },
            onConfirm = { v ->
                editing = false
                update(v)
                done()
            },
        )
    }
}

/** 列舉參數（姿態演算法、拿起停機）：分段選擇。 */
@Composable
private fun ParamOptionRow(p: ParamUi, ctl: TuneController, editable: Boolean, motorsOn: Boolean, compact: Boolean) {
    val def = p.def
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val options = remember(def) { def.options.orEmpty() }
    if (options.isEmpty()) return
    val display = p.display
    val locked = def.requiresMotorsOff && motorsOn
    val selected = options.firstOrNull { abs(it.first - display) < 0.01f } ?: options.first()
    Column(Modifier.fillMaxWidth()) {
        ParamHeader(
            p = p,
            editable = editable && !locked,
            compact = compact,
            onReset = {
                haptics.click()
                ctl.change(p, def.default)
                ctl.finish(p)
            },
        )
        Spacer(Modifier.height(10.dp))
        SegmentedSelector(
            options = options,
            selected = selected,
            onSelect = { o ->
                ctl.change(p, o.first)
                ctl.finish(p)
            },
            label = { it.second },
            enabled = editable && !locked,
        )
        ParamFooter(p)
        AnimatedVisibility(visible = locked && editable, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            HintRow(Icons.Rounded.Lock, "馬達運轉中無法更改，請先停止馬達", c.warn, Modifier.padding(top = 8.dp))
        }
        if (def.id == Params.ANGLE_ALGO && editable) {
            HintRow(Icons.Rounded.WarningAmber, "設定後按「儲存到車上」，車子重新開機才會切換", c.warn, Modifier.padding(top = 8.dp))
        }
        if (def.id == Params.PICKUP_EN) {
            AnimatedVisibility(visible = display < 0.5f, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                HintRow(Icons.Rounded.WarningAmber, "關閉後拿起車子時輪子仍會轉，請小心", c.warn, Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * 標題列：修改指示條 + 名稱 / 說明 + 單項重設 + 右側內容（數值膠囊）。
 * @param compact 窄欄：說明改放在標題列下方整行，名稱旁邊才放得下數值膠囊
 */
@Composable
private fun ParamHeader(
    p: ParamUi,
    editable: Boolean,
    onReset: () -> Unit,
    compact: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = B570Theme.colors
    val def = p.def
    val modified = p.isModified
    val staged = p.staged != null
    val barAlpha by animateFloatAsState(if (modified || staged) 1f else 0f, spring(stiffness = 400f), label = "bar")
    val barTop = if (staged) c.warn else c.accent
    val barBottom = if (staged) c.warn.copy(alpha = 0.5f) else c.accent2
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .graphicsLayer { alpha = barAlpha; scaleY = 0.4f + 0.6f * barAlpha }
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(barTop, barBottom))),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(def.labelZh, style = B570Theme.type.titleS, color = c.textPrimary)
            if (!compact) Text(def.descZh, style = B570Theme.type.caption, color = c.textSecondary)
        }
        AnimatedVisibility(
            visible = modified && editable,
            enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = 500f)) + fadeIn(),
            exit = scaleOut(tween(150)) + fadeOut(tween(150)),
        ) {
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(32.dp)
                    .glass(CircleShape)
                    .noRippleClickable(onClick = onReset),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = "恢復預設", tint = c.textSecondary, modifier = Modifier.size(17.dp))
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
    if (compact) {
        Text(
            def.descZh,
            style = B570Theme.type.caption,
            color = c.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(start = 13.dp, top = 2.dp),
        )
    }
}

/** 數值膠囊：已調整 = 強調色，待套用 = 橘色。 */
@Composable
private fun ValuePill(
    text: String,
    unit: String,
    staged: Boolean,
    modified: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = B570Theme.colors
    val target = when {
        staged -> c.warn
        modified -> c.accent
        else -> c.textPrimary
    }
    val color by animateColorAsState(target, label = "pillText")
    val border by animateColorAsState(
        when {
            staged -> c.warn.copy(alpha = 0.55f)
            modified -> c.accent.copy(alpha = 0.45f)
            else -> c.glassBorderDim
        },
        label = "pillBorder",
    )
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (modified || staged) target.copy(alpha = 0.08f) else c.glassHigh)
            .border(1.dp, border, shape)
            .noRippleClickable(enabled, onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text, style = B570Theme.type.readoutS, color = color, maxLines = 1)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Text(unit, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
        }
    }
}

/** 下方說明列：「預設 xxx」+ 待套用 / 夾限提示。 */
@Composable
private fun ParamFooter(p: ParamUi) {
    val c = B570Theme.colors
    val def = p.def
    val note = p.clampNote
    val staged = p.staged
    val trailing: String? = when {
        note != null -> "車子實際套用 ${def.format(note)}"
        staged != null -> "車上 ${def.format(p.base)} · 待套用"
        else -> null
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("預設 ${def.format(def.default)}", style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        AnimatedContent(
            targetState = trailing,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "footer",
        ) { t ->
            if (t != null) Text(t, style = B570Theme.type.caption, color = c.warn, maxLines = 1)
        }
    }
}

/** 提示列（圖示 + 一句話）。 */
@Composable
internal fun HintRow(icon: ImageVector, text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    var m = modifier.fillMaxWidth()
    if (filled) {
        m = m.clip(shape).background(color.copy(alpha = 0.08f)).border(1.dp, color.copy(alpha = 0.25f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    }
    Row(m, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = B570Theme.type.bodySmall, color = color)
    }
}

/** 小徽章（例：「2 項待套用」「使用中」）。 */
@Composable
internal fun MiniBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = B570Theme.type.caption,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
