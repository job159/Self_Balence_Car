package com.b570.pilot.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.WifiTetheringError
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.Params
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.AnimatedNumber
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.ValueSlider
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** 一筆遙測幀：5 bytes 標頭 + 40 bytes 資料 + 2 bytes CRC（PROTOCOL §2、§4.1）。 */
private const val TELEM_FRAME_BYTES = 5 + Telemetry.SIZE + 2

/** IPv4 + UDP 標頭。 */
private const val UDP_IP_OVERHEAD = 28

/** ESP ⇄ STM32 序列埠：230400 bps、8N1（每 byte 10 bit）。 */
private const val UART_BPS = 230_400f

/**
 * 「遙測」：TELEM_HZ（10–100 Hz）。放開滑桿才送 PARAM_SET，並顯示車上實際套用值、
 * 估算的 Wi-Fi / 序列埠用量、實際收到的筆數，以及「尚未存入車上」提示。
 * 寬版（平板）：左 = 滑桿 / 說明 / 量表，右 = 用量數據 / 實際筆數 / 提示；窄版依直向版順序堆疊。
 */
@Composable
internal fun TelemetrySection(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val def = remember { Params.byId(Params.TELEM_HZ)!! }
    val params by vm.params.collectAsStateWithLifecycle()
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    val carHz = params[Params.TELEM_HZ] ?: def.default
    val known = params.containsKey(Params.TELEM_HZ)
    var local by remember(carHz) { mutableFloatStateOf(carHz) }
    var busy by remember { mutableStateOf(false) }
    val canEdit = state.isConnected && !busy

    fun commit() {
        val target = def.clamp(local)
        if (busy || target == carHz) {
            local = target
            return
        }
        busy = true
        scope.launch {
            val applied = vm.setParam(Params.TELEM_HZ, target)
            busy = false
            if (applied != null) {
                local = applied
                haptics.confirm()
            } else {
                local = carHz
                haptics.reject()
            }
        }
    }

    val hint = when {
        state.isConnected -> null
        state is ConnectionState.LinkLost -> "訊號中斷中，恢復連線後才能調整"
        else -> "連線到車子後才能調整"
    }
    val linkLost = state is ConnectionState.LinkLost

    Column(modifier) {
        PaneHeader(
            SettingsCategory.TELEMETRY,
            trailing = if (busy) ({ CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp) }) else null,
        )
        GlassCard(Modifier.fillMaxWidth()) {
            AdaptiveColumns(
                wide = wide,
                first = {
                    ValueSlider(
                        def = def,
                        value = local,
                        onValueChange = { local = it },
                        enabled = canEdit,
                        showDescription = false,
                        onValueChangeFinished = { commit() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "越高越平滑，但佔用更多 Wi-Fi。訊號不穩或延遲變高時，調低一點會更順。",
                        style = B570Theme.type.bodySmall,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    SmoothnessMeter(((local - def.min) / (def.max - def.min)).coerceIn(0f, 1f))
                },
                second = {
                    Spacer(Modifier.height(12.dp))
                    val hz = local.coerceAtLeast(1f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberTile("更新間隔", 1000f / hz, 0, "ms", Modifier.weight(1f))
                        NumberTile("Wi-Fi 用量", hz * (TELEM_FRAME_BYTES + UDP_IP_OVERHEAD) / 1024f, 1, "KB/s", Modifier.weight(1.15f))
                        NumberTile("序列埠佔用", hz * TELEM_FRAME_BYTES * 10f / UART_BPS * 100f, 0, "%", Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(10.dp))
                    LiveRateRow(vm, known)
                    UnsavedParamsHint(vm)

                    AnimatedVisibility(hint != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        HintRow(
                            if (linkLost) Icons.Rounded.WifiTetheringError else Icons.Rounded.LinkOff,
                            hint ?: "",
                            Modifier.padding(top = 8.dp),
                        )
                    }
                },
            )
        }
    }
}

/** 數值磚（數字平滑過渡）。 */
@Composable
private fun NumberTile(label: String, value: Float, decimals: Int, unit: String, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    Column(
        modifier
            .glass(RoundedCornerShape(B570Theme.radii.s), fill = c.glassHigh.copy(alpha = 0.45f), borderWidth = 0.75.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = B570Theme.type.caption, color = c.textMuted, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        AnimatedNumber(value, decimals = decimals, style = B570Theme.type.readoutS, unit = unit)
    }
}

/** 「省流量 ↔ 最平滑」量表：漸層軌道 + 彈簧移動的指標。 */
@Composable
private fun SmoothnessMeter(fraction: Float) {
    val c = B570Theme.colors
    val f = animateFloatAsState(fraction, spring(dampingRatio = 0.7f, stiffness = 320f), label = "meter")
    Column {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .drawWithCache {
                    val track = 6.dp.toPx()
                    val cy = size.height / 2f
                    val brush = Brush.horizontalGradient(listOf(c.ok, c.accent, c.accent2), startX = 0f, endX = size.width)
                    val cr = CornerRadius(track / 2f)
                    val knob = 7.dp.toPx()
                    onDrawBehind {
                        drawRoundRect(brush, Offset(0f, cy - track / 2f), Size(size.width, track), cr, alpha = 0.55f)
                        val x = knob + (size.width - 2f * knob) * f.value.coerceIn(0f, 1f)
                        drawCircle(c.accent.copy(alpha = 0.25f), knob * 1.7f, Offset(x, cy))
                        drawCircle(Color.White, knob, Offset(x, cy))
                    }
                },
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("省流量", style = B570Theme.type.caption, color = c.ok, modifier = Modifier.weight(1f))
            Text("最平滑", style = B570Theme.type.caption, color = c.accent2)
        }
    }
}

/** 實際收到的遙測筆數（leaf：只有這裡跟著 linkStats 約 2 Hz 重組）。 */
@Composable
private fun LiveRateRow(vm: AppViewModel, known: Boolean) {
    val c = B570Theme.colors
    val stats by vm.linkStats.collectAsStateWithLifecycle()
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val live = state.isConnected && stats.telemetryFps > 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        LiveDot(if (live) c.ok else c.textMuted, active = live)
        Spacer(Modifier.width(8.dp))
        val text = when {
            !state.isSession -> "未連線"
            !known -> "正在讀取車上的設定…"
            !live -> "等待遙測資料…"
            else -> "實際收到 ${stats.telemetryFps.roundToInt()} 筆/秒 · 遺失 ${String.format(Locale.US, "%.1f", stats.lossPercent)} %"
        }
        Text(text, style = B570Theme.type.caption, color = c.textSecondary)
    }
}

/**
 * 參數尚未寫入 Flash 時的提示 + 「儲存」。
 * 只從 10 Hz 節流遙測讀兩個布林值（derivedStateOf），不會跟著遙測重組。
 */
@Composable
private fun UnsavedParamsHint(vm: AppViewModel) {
    val c = B570Theme.colors
    val telem = vm.telemetrySlow.collectAsStateWithLifecycle()
    val dirty by remember { derivedStateOf { telem.value?.paramsDirty == true } }
    val motorsOn by remember { derivedStateOf { telem.value?.motorsOn == true } }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    AnimatedVisibility(dirty, enter = expandVertically(spring(stiffness = 400f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Row(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(B570Theme.radii.s), fill = c.warn.copy(alpha = 0.10f), borderWidth = 0.75.dp)
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Save, null, tint = c.warn, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("參數還沒存入車上", style = B570Theme.type.label, color = c.textPrimary)
                Text(
                    if (motorsOn) "馬達運轉中，請先停止再儲存" else "車子重開機後會恢復原本的設定",
                    style = B570Theme.type.caption,
                    color = c.textSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            NeonButton(
                "儲存",
                {
                    saving = true
                    scope.launch {
                        val st = vm.saveParams()
                        saving = false
                        if (st.isOk) haptics.confirm() else haptics.reject()
                    }
                },
                compact = true,
                enabled = !motorsOn,
                loading = saving,
            )
        }
    }
}
