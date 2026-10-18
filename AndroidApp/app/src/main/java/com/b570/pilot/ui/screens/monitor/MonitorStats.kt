package com.b570.pilot.ui.screens.monitor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Battery0Bar
import androidx.compose.material.icons.rounded.Battery1Bar
import androidx.compose.material.icons.rounded.Battery2Bar
import androidx.compose.material.icons.rounded.Battery3Bar
import androidx.compose.material.icons.rounded.Battery4Bar
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.Battery6Bar
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkWifi1Bar
import androidx.compose.material.icons.rounded.NetworkWifi2Bar
import androidx.compose.material.icons.rounded.NetworkWifi3Bar
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SignalWifi0Bar
import androidx.compose.material.icons.rounded.SignalWifi4Bar
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.protocol.Params
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.batteryPercent
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import com.b570.pilot.ui.theme.neonGlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** 數據磚列數（每列兩格）。 */
internal const val STAT_ROWS = 6

/** 控制週期 10 ms（PROTOCOL §5：控制 100 Hz）。 */
private const val CONTROL_PERIOD_US = 10_000f

/**
 * 第 [row] 列數據磚（兩格）。每格都是自己收集資料的小葉節點（≤ 10 Hz），整列不會跟著重組。
 * @param dense 窄欄（每格 < 130 dp）：數值字級較小、留白較少
 */
@Composable
internal fun StatRow(row: Int, vm: AppViewModel, modifier: Modifier = Modifier, dense: Boolean = false) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(TileGap),
    ) {
        val m = Modifier.weight(1f).fillMaxHeight()
        CompositionLocalProvider(LocalDenseTiles provides dense) {
            StatRowTiles(row, vm, m)
        }
    }
}

/** 數據磚是否緊湊（由 [StatRow] 提供，避免每個磚都多一個參數）。 */
private val LocalDenseTiles = staticCompositionLocalOf { false }

@Composable
private fun StatRowTiles(row: Int, vm: AppViewModel, m: Modifier) {
    when (row) {
        0 -> { PitchTile(vm, m); GyroTile(vm, m) }
        1 -> { WheelTile(vm, left = true, m); WheelTile(vm, left = false, m) }
        2 -> { BatteryTile(vm, m); LoopTile(vm, m) }
        3 -> { RttTile(vm, m); EspRttTile(vm, m) }
        4 -> { FpsTile(vm, m); LossTile(vm, m) }
        else -> { RssiTile(vm, m); EspTile(vm, m) }
    }
}

/** 數據磚語意色。 */
private enum class Tone { ACCENT, OK, WARN, DANGER, MUTED }

/** 數據磚 + 狀態色（圖示顏色平滑過渡；危險時加紅色柔光）。 */
@Composable
private fun Tile(
    label: String,
    value: String,
    unit: String?,
    icon: ImageVector,
    tone: Tone,
    caption: String,
    modifier: Modifier,
) {
    val c = B570Theme.colors
    val target = when (tone) {
        Tone.ACCENT -> c.accent
        Tone.OK -> c.ok
        Tone.WARN -> c.warn
        Tone.DANGER -> c.danger
        Tone.MUTED -> c.textMuted
    }
    val accent by animateColorAsState(target, tween(450), label = "tileTone")
    val m = if (tone == Tone.DANGER) {
        modifier.neonGlow(c.danger.copy(alpha = 0.55f), RoundedCornerShape(B570Theme.radii.m), 10.dp)
    } else {
        modifier
    }
    MonitorTile(label, value, m, unit = unit, icon = icon, accent = accent, caption = caption, dense = LocalDenseTiles.current)
}

/**
 * 監測頁的數據磚：外觀同共用 StatTile（玻璃、圖示 + 標籤、等寬數值、說明），
 * 但每行都是單行 + 省略號（窄欄 / 字級 1.3 倍不切字），[dense] 時數值改用較小字級。
 */
@Composable
private fun MonitorTile(
    label: String,
    value: String,
    modifier: Modifier,
    unit: String?,
    icon: ImageVector,
    accent: Color,
    caption: String,
    dense: Boolean,
) {
    val c = B570Theme.colors
    Column(
        modifier
            .glass(RoundedCornerShape(B570Theme.radii.m))
            .padding(horizontal = if (dense) 10.dp else 14.dp, vertical = if (dense) 9.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(if (dense) 5.dp else 6.dp))
            Text(label, style = B570Theme.type.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(if (dense) 2.dp else 4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                modifier = Modifier.weight(1f, fill = false),
                style = if (dense) B570Theme.type.readoutS else B570Theme.type.readoutM,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!unit.isNullOrEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    style = B570Theme.type.caption,
                    color = c.textMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = if (dense) 1.dp else 3.dp),
                )
            }
        }
        Text(caption, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PitchTile(vm: AppViewModel, modifier: Modifier) {
    val t by vm.telemetrySlow.collectAsStateWithLifecycle()
    val params by vm.params.collectAsStateWithLifecycle()
    val fall = params[Params.FALL_ANGLE] ?: Params.byId(Params.FALL_ANGLE)?.default ?: 40f
    val zero = params[Params.ZERO_ANGLE] ?: 0f
    val tt = t
    // 韌體以扣掉零點後的傾角判斷傾倒（遙測 pitch 是原始值）
    val tilt = tt?.let { abs(it.pitchDeg - zero) }
    val tone = when {
        tilt == null -> Tone.MUTED
        tilt > fall * 0.75f -> Tone.DANGER
        tilt > fall * 0.45f -> Tone.WARN
        else -> Tone.ACCENT
    }
    Tile("傾角", fmtNum(tt?.pitchDeg, 1), "°", Icons.Rounded.SquareFoot, tone, "橫滾 ${fmtNum(tt?.rollDeg, 1)}°", modifier)
}

@Composable
private fun GyroTile(vm: AppViewModel, modifier: Modifier) {
    val t by vm.telemetrySlow.collectAsStateWithLifecycle()
    val tt = t
    val tone = when {
        tt == null -> Tone.MUTED
        abs(tt.gyroBalDps) > 300f -> Tone.WARN
        else -> Tone.ACCENT
    }
    Tile("角速度", fmtNum(tt?.gyroBalDps, 1), "°/s", Icons.Rounded.Autorenew, tone, "偏航 ${fmtNum(tt?.gyroTurnDps, 1)} °/s", modifier)
}

@Composable
private fun WheelTile(vm: AppViewModel, left: Boolean, modifier: Modifier) {
    val t by vm.telemetrySlow.collectAsStateWithLifecycle()
    val tt = t
    val v = tt?.let { if (left) it.velL else it.velR }
    val caption = if (left) "目標 ${tt?.targetSpeed ?: "--"} mm/s" else "平均 ${fmtNum(tt?.speedMmps, 0)} mm/s"
    Tile(
        if (left) "左輪速" else "右輪速",
        v?.toString() ?: "--",
        "mm/s",
        Icons.Rounded.Speed,
        if (tt == null) Tone.MUTED else Tone.ACCENT,
        caption,
        modifier,
    )
}

@Composable
private fun BatteryTile(vm: AppViewModel, modifier: Modifier) {
    val t by vm.telemetrySlow.collectAsStateWithLifecycle()
    val tt = t
    val pct = tt?.let { batteryPercent(it.batteryV) }
    val low = tt?.lowBattery == true
    val tone = when {
        pct == null -> Tone.MUTED
        low || pct <= 15 -> Tone.DANGER
        pct <= 30 -> Tone.WARN
        else -> Tone.OK
    }
    val caption = when {
        pct == null -> "電量 --"
        low -> "電量過低，請充電"
        else -> "電量 $pct %"
    }
    Tile("電池", fmtNum(tt?.batteryV, 2), "V", batteryIcon(pct), tone, caption, modifier)
}

private fun batteryIcon(pct: Int?): ImageVector = when {
    pct == null -> Icons.Rounded.Battery0Bar
    pct >= 95 -> Icons.Rounded.BatteryFull
    pct >= 80 -> Icons.Rounded.Battery6Bar
    pct >= 65 -> Icons.Rounded.Battery5Bar
    pct >= 50 -> Icons.Rounded.Battery4Bar
    pct >= 35 -> Icons.Rounded.Battery3Bar
    pct >= 20 -> Icons.Rounded.Battery2Bar
    pct >= 10 -> Icons.Rounded.Battery1Bar
    else -> Icons.Rounded.BatteryAlert
}

@Composable
private fun LoopTile(vm: AppViewModel, modifier: Modifier) {
    val t by vm.telemetrySlow.collectAsStateWithLifecycle()
    val us = t?.loopUs
    val load = us?.let { it / CONTROL_PERIOD_US * 100f }
    val tone = when {
        load == null -> Tone.MUTED
        load >= 85f -> Tone.DANGER
        load >= 60f -> Tone.WARN
        else -> Tone.ACCENT
    }
    Tile("控制迴圈", us?.toString() ?: "--", "µs", Icons.Rounded.Memory, tone, "負載 ${fmtNum(load, 0)} %", modifier)
}

@Composable
private fun RttTile(vm: AppViewModel, modifier: Modifier) {
    val s by vm.linkStats.collectAsStateWithLifecycle()
    val rtt = s.rttMs
    val tone = when {
        rtt == null -> Tone.MUTED
        rtt > 60f -> Tone.DANGER
        rtt > 25f -> Tone.WARN
        else -> Tone.ACCENT
    }
    Tile("端到端延遲", fmtNum(rtt, 1), "ms", Icons.Rounded.SyncAlt, tone, "手機 ⇄ 主控板", modifier)
}

@Composable
private fun EspRttTile(vm: AppViewModel, modifier: Modifier) {
    val s by vm.linkStats.collectAsStateWithLifecycle()
    val rtt = s.espRttMs
    val tone = when {
        rtt == null -> Tone.MUTED
        rtt > 40f -> Tone.DANGER
        rtt > 15f -> Tone.WARN
        else -> Tone.ACCENT
    }
    Tile("Wi-Fi 延遲", fmtNum(rtt, 1), "ms", Icons.Rounded.WifiTethering, tone, "手機 ⇄ Wi-Fi 模組", modifier)
}

@Composable
private fun FpsTile(vm: AppViewModel, modifier: Modifier) {
    val s by vm.linkStats.collectAsStateWithLifecycle()
    val st by vm.connectionState.collectAsStateWithLifecycle()
    val params by vm.params.collectAsStateWithLifecycle()
    val hz = telemHz(params)
    val fps = s.telemetryFps
    val tone = when {
        !st.isSession -> Tone.MUTED
        fps < hz * 0.5f -> Tone.DANGER
        fps < hz * 0.85f -> Tone.WARN
        else -> Tone.ACCENT
    }
    Tile(
        "遙測幀率",
        if (st.isSession) fmtNum(fps, 0) else "--",
        "Hz",
        Icons.Rounded.GraphicEq,
        tone,
        "設定 ${hz.roundToInt()} Hz",
        modifier,
    )
}

@Composable
private fun LossTile(vm: AppViewModel, modifier: Modifier) {
    val s by vm.linkStats.collectAsStateWithLifecycle()
    val st by vm.connectionState.collectAsStateWithLifecycle()
    val loss = s.lossPercent
    val tone = when {
        !st.isSession -> Tone.MUTED
        loss >= 10f -> Tone.DANGER
        loss >= 2f -> Tone.WARN
        else -> Tone.ACCENT
    }
    Tile(
        "掉包率",
        if (st.isSession) fmtNum(loss, 1) else "--",
        "%",
        Icons.Rounded.Grain,
        tone,
        "CRC 錯誤 ${s.crcErrors}",
        modifier,
    )
}

@Composable
private fun RssiTile(vm: AppViewModel, modifier: Modifier) {
    val s by vm.linkStats.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val rssi = s.wifiRssi
    val level = s.signalLevel
    val tone = when {
        rssi == null -> Tone.MUTED
        level <= 1 -> Tone.DANGER
        level == 2 -> Tone.WARN
        else -> Tone.ACCENT
    }
    val caption = when {
        rssi == null -> if (demo) "展示模式沒有訊號資料" else "等待量測…"
        level >= 4 -> "訊號極佳"
        level == 3 -> "訊號良好"
        level == 2 -> "訊號普通"
        level == 1 -> "訊號微弱"
        else -> "訊號很差"
    }
    val icon = when {
        rssi == null -> Icons.Rounded.SignalWifi0Bar
        level >= 4 -> Icons.Rounded.SignalWifi4Bar
        level == 3 -> Icons.Rounded.NetworkWifi3Bar
        level == 2 -> Icons.Rounded.NetworkWifi2Bar
        level == 1 -> Icons.Rounded.NetworkWifi1Bar
        else -> Icons.Rounded.SignalWifi0Bar
    }
    Tile("訊號強度", rssi?.toString() ?: "--", "dBm", icon, tone, caption, modifier)
}

@Composable
private fun EspTile(vm: AppViewModel, modifier: Modifier) {
    val esp by vm.espInfo.collectAsStateWithLifecycle()
    val w = esp.wifi
    val tone = when {
        w == null -> Tone.MUTED
        w.freeHeap < 8_192 -> Tone.WARN
        else -> Tone.ACCENT
    }
    Tile(
        "ESP 運行時間",
        w?.let { fmtUptime(it.uptimeS) } ?: "--",
        null,
        Icons.Rounded.Router,
        tone,
        w?.let { "剩餘記憶體 ${fmtKb(it.freeHeap)}" } ?: "等待 Wi-Fi 模組資料",
        modifier,
    )
}
