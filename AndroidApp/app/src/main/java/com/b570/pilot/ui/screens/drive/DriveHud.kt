package com.b570.pilot.ui.screens.drive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.Gear
import com.b570.pilot.data.ReconnectStatus
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.ControlSource
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.BatteryChip
import com.b570.pilot.ui.components.ConnectionChip
import com.b570.pilot.ui.components.ControlLatencyChip
import com.b570.pilot.ui.components.HudChip
import com.b570.pilot.ui.components.HudIconButton
import com.b570.pilot.ui.components.LinkLostBadge
import com.b570.pilot.ui.components.LinkModeChip
import com.b570.pilot.ui.components.LinkQualityChip
import com.b570.pilot.ui.components.MotorChip
import com.b570.pilot.ui.components.RockerSwitch
import com.b570.pilot.ui.components.SignalChip
import com.b570.pilot.ui.shell.ReconnectBadge
import com.b570.pilot.ui.theme.B570Theme
import kotlinx.coroutines.delay

/** 檔位翹板（橫向）的尺寸：「降檔 / 升檔」在字級 1.3 倍也放得下。 */
private val GearRockerWidth = 176.dp

/**
 * 遙控器上緣列：≡ 選單（滑入式導覽）· 車況膠囊（車名、連線 / 失聯、馬達、電量、訊號、連線方式（熱點 / 直連）、
 * 控制延遲「控制 23 ms」（點一下展開 P95 / 來回 / Wi-Fi 細節，8 秒後收回）、連線品質；
 * 放不下時依重要度收起；**自動重連中**換成重連提示「自動重連中（第 n/6 次）· 倒數 · 取消」）·
 *（360° 模式）搖桿換邊 · 編排捷徑 · 檔位翹板（降檔 / 升檔，顯示 慢 / 中 / 快）。
 * 重連提示放在車況膠囊那一格（不是 shell 疊上去的浮層）：寬度跟著這一列實際剩下的空間走，任何模式 / 字級都不會蓋到右側按鈕，
 * 也不會往下蓋到肩鍵列的緊急停止。
 * 膠囊自己收集 telemetrySlow / linkStats（≤ 10 Hz），只有那一段會重組。
 * @param showGear false = 檔位翹板已在 360° 按鍵區（這裡不重複）
 * @param stickRight 非 null = 顯示「搖桿在左 / 在右」換邊膠囊（360° 模式）
 */
@Composable
internal fun DriveTopBar(
    vm: AppViewModel,
    gear: Gear,
    onGear: (Gear) -> Unit,
    onMenu: () -> Unit,
    onChoreo: () -> Unit,
    compact: Boolean,
    detailed: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    showGear: Boolean = true,
    stickRight: Boolean? = null,
    onSwapStick: () -> Unit = {},
) {
    val c = B570Theme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        HudIconButton(Icons.Rounded.Menu, "選單", onMenu, size = 40.dp)
        Spacer(Modifier.width(8.dp))
        StatusOrReconnect(vm, compact, detailed, Modifier.weight(1f).fillMaxHeight())
        if (stickRight != null) {
            Spacer(Modifier.width(8.dp))
            StickSideChip(stickRight, onSwapStick)
        }
        Spacer(Modifier.width(8.dp))
        if (compact) {
            HudIconButton(Icons.Rounded.AutoAwesome, "編排", onChoreo, size = 36.dp, color = c.accent2)
        } else {
            HudChip("編排", icon = Icons.Rounded.AutoAwesome, color = c.accent2, onClick = onChoreo)
        }
        if (showGear) {
            Spacer(Modifier.width(8.dp))
            RockerSwitch(
                options = Gear.entries,
                selected = gear,
                onSelect = onGear,
                modifier = Modifier.size(GearRockerWidth, height),
                label = { it.driveLabel },
                title = "檔位",
                vertical = false,
                colorOf = { g ->
                    when (g) {
                        Gear.SLOW -> c.ok
                        Gear.NORMAL -> c.accent
                        Gear.FAST -> c.warn
                    }
                },
            )
        }
    }
}

/** 360° 搖桿換邊（搖桿在左 ⇄ 在右）：顯示目前位置，點一下對調。 */
@Composable
private fun StickSideChip(stickRight: Boolean, onSwap: () -> Unit) {
    val c = B570Theme.colors
    HudChip(
        color = c.accent,
        onClick = onSwap,
        contentDescription = if (stickRight) "搖桿在右，點一下換到左邊" else "搖桿在左，點一下換到右邊",
    ) {
        Icon(Icons.Rounded.SwapHoriz, null, tint = c.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            if (stickRight) "搖桿在右" else "搖桿在左",
            style = B570Theme.type.label,
            color = c.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * 車況膠囊，或（自動重連中）重連提示 + 取消。只有這一格收集重連進度（第幾次改變時重組；倒數在提示內部自己更新）。
 * 重連期間搖桿已被 ControlBlock.RECONNECTING 封鎖。
 */
@Composable
private fun StatusOrReconnect(vm: AppViewModel, compact: Boolean, detailed: Boolean, modifier: Modifier) {
    val reconnect by vm.reconnect.collectAsStateWithLifecycle()
    // 退場動畫期間 reconnect 已是 null：沿用最後一筆（非 state 暫存，組合期間寫入安全）
    val last = remember { arrayOfNulls<ReconnectStatus>(1) }
    reconnect?.let { last[0] = it }
    AnimatedContent(
        targetState = reconnect != null,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(160))) },
        contentAlignment = Alignment.CenterStart,
        label = "statusOrReconnect",
    ) { on ->
        val s = last[0]
        if (on && s != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                ReconnectBadge(s, onCancel = { vm.cancelReconnect() }, Modifier.widthIn(max = 560.dp), compact = true)
            }
        } else {
            StatusChips(vm, compact, detailed, Modifier.fillMaxSize())
        }
    }
}

/** 車況膠囊（依重要度收起放不下的）。 */
@Composable
private fun StatusChips(vm: AppViewModel, compact: Boolean, detailed: Boolean, modifier: Modifier) {
    val c = B570Theme.colors
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val name by vm.currentCarName.collectAsStateWithLifecycle()
    val t by vm.telemetrySlow.collectAsStateWithLifecycle()
    val stats by vm.linkStats.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val mode by vm.linkMode.collectAsStateWithLifecycle()
    // 點延遲膠囊：展開一行細節（控制 · P95 · 來回 · Wi-Fi），一段時間後自動收回（行內展開，不是浮層：不會蓋到肩鍵列的緊急停止）
    var latencyOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(latencyOpen) {
        if (latencyOpen) {
            delay(LATENCY_DETAIL_MS)
            latencyOpen = false
        }
    }
    val tel = t
    val motorDetail = when {
        tel == null -> null
        tel.motorsOn -> when (tel.source) {
            ControlSource.APP -> "手機控制"
            ControlSource.SEQUENCE -> "編排控制"
            else -> null
        }
        else -> tel.stopReason.labelZh
    }
    // 子項順序 = 顯示順序；數字 = 重要度（小 = 最後才收起）。展開延遲細節時延遲膠囊最優先。
    PriorityRow(if (latencyOpen) ChipPrioritiesLatencyOpen else ChipPriorities, modifier, spacing = 6.dp) {
        Text(
            name.ifEmpty { "B570" },
            style = B570Theme.type.titleS,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = if (compact) 110.dp else 160.dp),
        )
        val lost = state as? ConnectionState.LinkLost
        if (lost != null) LinkLostBadge(lost.sinceMs, compact = true) else ConnectionChip(state, demo = demo)
        MotorChip(tel?.motorsOn, detail = if (detailed) motorDetail else null)
        BatteryChip(tel?.batteryV, showVoltage = !compact)
        SignalChip(stats.signalLevel)
        // 沒有連線方式（還沒連上 / 展示模式）時是 0 寬，PriorityRow 不留間距
        val m = mode
        if (m != null) LinkModeChip(m) else Box(Modifier)
        ControlLatencyChip(stats, expanded = latencyOpen, onClick = { latencyOpen = !latencyOpen })
        LinkQualityChip(stats.linkQualityPercent)
    }
}

/** 延遲細節展開多久後自動收回。 */
private const val LATENCY_DETAIL_MS = 8_000L

/**
 * 名稱、連線、馬達、電量、訊號、連線方式、控制延遲、品質。收起順序：品質 → 名稱 → 馬達 → 連線方式 → 訊號 → 延遲 → 電量 → 連線。
 * 馬達狀態左肩鍵（啟動平衡 / 停止 · 平衡中）已經看得到，所以比連線方式（熱點 / 直連，小膠囊）、訊號、延遲先收起。
 */
private val ChipPriorities = intArrayOf(6, 0, 5, 1, 3, 4, 2, 7)

/** 延遲細節展開時：延遲膠囊最後才收起（其他膠囊暫時讓位）。 */
private val ChipPrioritiesLatencyOpen = intArrayOf(6, 0, 5, 1, 3, 4, -1, 7)

/**
 * 一列放不下時，依 [priorities]（與子項同順序；數字大的先收起）隱藏子項，其餘由左到右排、垂直置中。
 * 不會裁切文字：要嘛整個顯示、要嘛整個收起。0 寬的子項視為不存在（不佔間距）。
 */
@Composable
private fun PriorityRow(
    priorities: IntArray,
    modifier: Modifier = Modifier,
    spacing: Dp = 6.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val maxW = constraints.maxWidth
        val child = Constraints(maxWidth = maxW, maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.measure(child) }
        val n = placeables.size
        val visible = BooleanArray(n) { true }
        // 0 寬的子項（例如沒有資料時的空位）不佔間距
        for (i in 0 until n) if (placeables[i].width == 0) visible[i] = false
        fun total(): Int {
            var sum = 0
            var count = 0
            for (i in 0 until n) if (visible[i]) {
                sum += placeables[i].width
                count++
            }
            return sum + gap * (count - 1).coerceAtLeast(0)
        }
        while (maxW != Constraints.Infinity && total() > maxW) {
            var drop = -1
            var worst = Int.MIN_VALUE
            for (i in 0 until n) if (visible[i]) {
                val p = if (i < priorities.size) priorities[i] else Int.MAX_VALUE
                if (p >= worst) {
                    worst = p
                    drop = i
                }
            }
            if (drop < 0) break
            visible[drop] = false
        }
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight else placeables.maxOfOrNull { it.height } ?: 0
        val w = total().coerceIn(constraints.minWidth, maxW)
        layout(w, h) {
            var x = 0
            for (i in 0 until n) if (visible[i]) {
                val p = placeables[i]
                p.placeRelative(x, (h - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}
