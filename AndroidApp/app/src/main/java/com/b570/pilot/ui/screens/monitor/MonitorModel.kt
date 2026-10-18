package com.b570.pilot.ui.screens.monitor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.b570.pilot.data.CarEvent
import com.b570.pilot.data.CarRepository
import com.b570.pilot.data.TelemetryHistory.Channel
import com.b570.pilot.protocol.Params
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// ====================================================================== 圖表定義

/** 監測頁的五張圖（也當 LazyColumn key 與展開狀態 id）。 */
internal enum class ChartKind { ATTITUDE, WHEELS, PWM, GYRO, BATTERY }

/** 圖上的一條曲線（顏色由主題決定，見 rememberSeriesColors）。 */
internal class SeriesDef(
    val channel: Channel,
    val label: String,
    val defaultVisible: Boolean = true,
)

/**
 * 一張圖的靜態定義。
 * @param symmetricLevels 以 0 為中心的縱軸候選範圍（保證看得到零線）；null = 交給 LineChart 自動縮放
 * @param motorHint 馬達停止時顯示「輸出維持 0」提示
 * @param tunable 放大檢視提供「整定」捷徑
 */
internal class ChartDef(
    val kind: ChartKind,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val unit: String,
    val decimals: Int,
    val minSpan: Float,
    val series: List<SeriesDef>,
    val symmetricLevels: FloatArray? = null,
    val motorHint: Boolean = false,
    val tunable: Boolean = true,
) {
    /** 隱藏狀態的 key（預先建好，組合時零配置）。 */
    val keys: Array<String> = Array(series.size) { "${kind.name}:${series[it].channel.name}" }
}

internal object ChartDefs {
    val ALL: List<ChartDef> = listOf(
        ChartDef(
            ChartKind.ATTITUDE, "姿態", "傾角與橫滾，中線為 0°", Icons.Rounded.Balance, "°", 1, 2f,
            listOf(SeriesDef(Channel.PITCH, "傾角"), SeriesDef(Channel.ROLL, "橫滾", defaultVisible = false)),
            symmetricLevels = floatArrayOf(2f, 5f, 10f, 20f, 30f, 45f, 60f, 90f),
        ),
        ChartDef(
            ChartKind.WHEELS, "輪速", "左右輪實際速度與目標速度", Icons.Rounded.Speed, "mm/s", 0, 40f,
            listOf(SeriesDef(Channel.VEL_L, "左輪"), SeriesDef(Channel.VEL_R, "右輪"), SeriesDef(Channel.TGT_SPEED, "目標")),
            motorHint = true,
        ),
        ChartDef(
            ChartKind.PWM, "PWM 分量", "直立環、速度環、轉向環輸出", Icons.Rounded.ElectricBolt, "", 0, 200f,
            listOf(
                SeriesDef(Channel.PWM_BAL, "直立環"),
                SeriesDef(Channel.PWM_VEL, "速度環"),
                SeriesDef(Channel.PWM_TURN, "轉向環"),
                SeriesDef(Channel.PWM_L, "左馬達", defaultVisible = false),
                SeriesDef(Channel.PWM_R, "右馬達", defaultVisible = false),
            ),
            motorHint = true,
        ),
        ChartDef(
            ChartKind.GYRO, "角速度", "俯仰與偏航角速度", Icons.Rounded.Autorenew, "°/s", 1, 10f,
            listOf(SeriesDef(Channel.GYRO_BAL, "俯仰"), SeriesDef(Channel.GYRO_TURN, "偏航")),
            symmetricLevels = floatArrayOf(10f, 25f, 50f, 100f, 250f, 500f, 1000f, 2000f),
        ),
        ChartDef(
            ChartKind.BATTERY, "電池", "電壓變化，負載大時會短暫下降", Icons.Rounded.BatteryChargingFull, "V", 2, 0.3f,
            listOf(SeriesDef(Channel.BATTERY, "電壓")),
            tunable = false,
        ),
    )

    fun of(kind: ChartKind): ChartDef = ALL[kind.ordinal]
}

/** 時間範圍選項（秒）。 */
internal val WINDOW_OPTIONS = listOf(5, 10, 30, 60)

// ====================================================================== 監測頁狀態（Activity 範圍）

/**
 * 監測頁自己的小 ViewModel（Activity 範圍，切換分頁不會遺失）：
 * - 事件紀錄：CarRepository.recentEvents（從 App 啟動起保留最近 50 則，含連線變化）
 * - 時間範圍、各圖曲線顯示 / 隱藏
 * - CSV 匯出（在 viewModelScope 執行，切換分頁也會完成）
 */
internal class MonitorViewModel(
    /** 最新在前。 */
    val log: StateFlow<List<CarEvent>>,
    private val onClearLog: () -> Unit,
) : ViewModel() {

    var windowSec by mutableIntStateOf(10)

    /** 圖表暫停（放在 ViewModel：切到其他分頁再回來仍保持暫停）。 */
    var paused by mutableStateOf(false)

    private val hidden = mutableStateMapOf<String, Boolean>()

    var exporting by mutableStateOf(false)
        private set

    fun isHidden(def: ChartDef, index: Int): Boolean = hidden[def.keys[index]] ?: !def.series[index].defaultVisible

    /** bit i = 第 i 條曲線隱藏。 */
    fun hiddenMask(def: ChartDef): Int {
        var m = 0
        for (i in def.series.indices) if (isHidden(def, i)) m = m or (1 shl i)
        return m
    }

    /** 切換顯示；最後一條可見曲線不能隱藏（回傳 false）。 */
    fun toggle(def: ChartDef, index: Int): Boolean {
        val mask = hiddenMask(def)
        if (!canToggle(def.series.size, mask, index)) return false
        hidden[def.keys[index]] = mask and (1 shl index) == 0
        return true
    }

    fun clearLog() = onClearLog()

    fun export(write: suspend () -> Boolean, onDone: (Boolean) -> Unit) {
        if (exporting) return
        exporting = true
        viewModelScope.launch {
            val ok = try { write() } finally { exporting = false }
            onDone(ok)
        }
    }

    companion object {
        const val MAX_LOG = CarRepository.EVENT_LOG_SIZE
    }
}

/** 最後一條可見的曲線不能隱藏；隱藏中的隨時可以打開。 */
internal fun canToggle(seriesCount: Int, hiddenMask: Int, index: Int): Boolean {
    if (index !in 0 until seriesCount) return false
    val isHidden = hiddenMask and (1 shl index) != 0
    return isHidden || seriesCount - Integer.bitCount(hiddenMask) > 1
}

// ====================================================================== 格式化

private val NUM_FMT = arrayOf("%.0f", "%.1f", "%.2f", "%.3f")
private val HALF_ULP = floatArrayOf(0.5f, 0.05f, 0.005f, 0.0005f)

/** 數值 → 文字（NaN / null 顯示「--」，避免「-0.0」）。 */
internal fun fmtNum(v: Float?, decimals: Int): String {
    if (v == null || v.isNaN() || v.isInfinite()) return "--"
    val d = decimals.coerceIn(0, 3)
    val x = if (abs(v) < HALF_ULP[d]) 0f else v
    return String.format(Locale.US, NUM_FMT[d], x)
}

/** 秒數（整數不帶小數）。 */
internal fun fmtSec(s: Float): String =
    if (abs(s - Math.round(s)) < 0.05f) Math.round(s).toString() else String.format(Locale.US, "%.1f", s)

/** 運行時間：1:02:03 或 2 天 03:04。 */
internal fun fmtUptime(totalS: Long): String {
    val s = totalS.coerceAtLeast(0)
    val d = s / 86_400
    val h = (s % 86_400) / 3_600
    val m = (s % 3_600) / 60
    val sec = s % 60
    return if (d > 0) String.format(Locale.US, "%d 天 %02d:%02d", d, h, m)
    else String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
}

internal fun fmtKb(bytes: Long): String = String.format(Locale.US, "%.1f KB", bytes / 1024f)

/** 目前遙測頻率（參數未知時用預設）。 */
internal fun telemHz(params: Map<Int, Float>): Float =
    (params[Params.TELEM_HZ] ?: Params.byId(Params.TELEM_HZ)?.default ?: 50f).coerceIn(1f, 200f)

private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

internal fun csvFileName(): String = "B570_遙測_${LocalDateTime.now().format(FILE_TIME)}.csv"

internal const val CSV_MIME = "text/csv"

internal fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t
