package com.b570.pilot.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.BuildConfig
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.LinkMode
import com.b570.pilot.protocol.StaState
import com.b570.pilot.protocol.Params
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 設定的分類（左側清單 → 右側詳情）。順序 = 清單順序 = 詳情切換方向（往下的分類由下往上進場）。
 * [titleZh] 清單與詳情標題共用；[subtitleZh] 詳情標題下方的說明。
 */
internal enum class SettingsCategory(val titleZh: String, val subtitleZh: String, val icon: ImageVector) {
    WIFI("車輛 Wi-Fi", "車子自己的 Wi-Fi（直連用）名稱、密碼與無線模組狀態", Icons.Rounded.Wifi),
    LINK("連線方式", "直連車子 Wi-Fi，或讓車子連手機熱點（手機照用行動網路）", Icons.Rounded.WifiTethering),
    CONTROL("操控", "搖桿手感與駕駛偏好", Icons.Rounded.SportsEsports),
    APPEARANCE("外觀", "選一個你喜歡的霓虹色，立即套用", Icons.Rounded.Palette),
    TELEMETRY("遙測", "車子每秒回傳姿態與速度資料的次數", Icons.Rounded.Timeline),
    DEMO("展示模式", "沒有車子，也能體驗所有功能", Icons.Rounded.Science),
    CARS("記住的車子", "連線過的車與自動連線", Icons.Rounded.SmartToy),
    ABOUT("關於", "版本資訊、使用說明與致謝", Icons.Rounded.Info),
    ;

    companion object {
        /** rememberSaveable 存的是名稱；找不到（改版後）回到第一個。 */
        fun fromName(name: String?): SettingsCategory = entries.firstOrNull { it.name == name } ?: WIFI
    }
}

/** 清單每一列的摘要與狀態燈。 */
private data class CategoryStatus(val summary: String, val led: Color?, val ledPulse: Boolean = false)

/**
 * 左側分類面板（固定玻璃面板，內容太長時面板內捲動）：標題、分類清單（滑動發光指示塊 + 摘要 + 狀態燈）、
 * 最下面「中斷連線」。清單只收集低頻資料（設定、連線狀態、參數、記住的車），摘要用 derivedStateOf，
 * ESP 每 3 秒的更新不會讓整個面板重組。
 */
@Composable
internal fun CategoryPanel(
    vm: AppViewModel,
    nav: AppNavigator,
    selected: SettingsCategory?,
    onSelect: (SettingsCategory) -> Unit,
    tracker: EntranceTracker,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val statuses = rememberCategoryStatuses(vm)

    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .glass(shape, fill = c.surface.copy(alpha = 0.62f), glow = c.accent.copy(alpha = 0.16f), glowElevation = 14.dp)
            .clip(shape),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, bottom = 8.dp)
                    .staggeredEntrance(tracker, "panel-title", 0),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("設定", style = B570Theme.type.titleM, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                Text(
                    "${SettingsCategory.entries.size} 項",
                    style = B570Theme.type.caption.copy(letterSpacing = 0.5.sp),
                    color = c.textMuted,
                    maxLines = 1,
                )
            }

            CategoryList(
                selected = selected,
                statuses = statuses,
                tracker = tracker,
                onSelect = { cat ->
                    if (cat != selected) haptics.tick()
                    onSelect(cat)
                },
            )

            Spacer(Modifier.height(10.dp))
            SettingDivider()
            Spacer(Modifier.height(6.dp))
            DisconnectSection(
                vm,
                nav,
                Modifier.padding(horizontal = 4.dp).staggeredEntrance(tracker, "panel-disconnect", SettingsCategory.entries.size + 1),
                compact = true,
            )
        }
    }
}

/** 分類清單：各列量出自己的位置，發光指示塊用彈簧滑到選取列（字級放大、列高不同也對得準）。 */
@Composable
private fun CategoryList(
    selected: SettingsCategory?,
    statuses: Map<SettingsCategory, State<CategoryStatus>>,
    tracker: EntranceTracker,
    onSelect: (SettingsCategory) -> Unit,
) {
    val c = B570Theme.colors
    val tops = remember { mutableStateMapOf<SettingsCategory, Float>() }
    val heights = remember { mutableStateMapOf<SettingsCategory, Float>() }
    val y = remember { Animatable(0f) }
    val h = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val targetTop = selected?.let { tops[it] }
    val targetH = selected?.let { heights[it] }
    LaunchedEffect(targetTop, targetH) {
        if (targetTop == null || targetH == null) return@LaunchedEffect
        if (!placed) {
            y.snapTo(targetTop)
            h.snapTo(targetH)
            placed = true
        } else {
            launch { h.animateTo(targetH, spring(dampingRatio = 0.75f, stiffness = 420f)) }
            y.animateTo(targetTop, spring(dampingRatio = 0.72f, stiffness = 420f))
        }
    }
    val indicatorShape = RoundedCornerShape(16.dp)

    Box(Modifier.fillMaxWidth()) {
        if (selected != null && placed) {
            // 發光指示塊（位置 / 高度只在版面階段讀取動畫值，不重組）
            Box(
                Modifier
                    .offset { IntOffset(0, y.value.roundToInt()) }
                    .layout { measurable, constraints ->
                        val hh = h.value.roundToInt().coerceAtLeast(0)
                        val p = measurable.measure(Constraints.fixed(constraints.maxWidth, hh))
                        layout(p.width, p.height) { p.place(0, 0) }
                    }
                    // 第一次進入時跟選取列一起進場
                    .staggeredEntrance(tracker, "cat-indicator", selected.ordinal + 1)
                    .shadow(10.dp, indicatorShape, clip = false, ambientColor = c.accent, spotColor = c.accent)
                    .clip(indicatorShape)
                    .background(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.24f), c.accent2.copy(alpha = 0.10f))))
                    .border(1.dp, c.accent.copy(alpha = 0.5f), indicatorShape),
            ) {
                // 左側光條（像遙控器選單的游標）
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 3.dp)
                        .size(width = 3.dp, height = 22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c.accent),
                )
            }
        }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            SettingsCategory.entries.forEachIndexed { i, cat ->
                val status by statuses.getValue(cat)
                CategoryItem(
                    category = cat,
                    selected = cat == selected,
                    status = status,
                    onClick = { onSelect(cat) },
                    modifier = Modifier
                        .onPlaced { coords ->
                            tops[cat] = coords.positionInParent().y
                            heights[cat] = coords.size.height.toFloat()
                        }
                        .staggeredEntrance(tracker, "cat:${cat.name}", i + 1),
                )
            }
        }
    }
}

/** 一列分類：圖示、名稱、摘要（單行省略）、狀態燈。 */
@Composable
private fun CategoryItem(
    category: SettingsCategory,
    selected: Boolean,
    status: CategoryStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val tint by animateColorAsState(if (selected) c.accent else c.textSecondary, label = "catTint")
    val lift by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 420f), label = "catLift")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, interactionSource = null, indication = null, role = Role.Tab, onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .graphicsLayer {
                    val s = 1f + 0.06f * lift
                    scaleX = s
                    scaleY = s
                }
                .glass(RoundedCornerShape(10.dp), fill = tint.copy(alpha = 0.12f), borderWidth = 0.75.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(category.icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                category.titleZh,
                style = B570Theme.type.titleS,
                color = if (selected) c.textPrimary else c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                status.summary,
                style = B570Theme.type.caption,
                color = if (selected) c.textSecondary else c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (status.led != null) {
            Spacer(Modifier.width(6.dp))
            if (status.ledPulse) {
                LiveDot(status.led, Modifier.size(7.dp))
            } else {
                val led = status.led
                Box(
                    Modifier.size(7.dp).drawBehind {
                        drawCircle(led.copy(alpha = 0.28f), size.minDimension)
                        drawCircle(led, size.minDimension / 2f)
                    },
                )
            }
        }
    }
}

/** 計算每個分類的摘要與狀態燈（各自 derivedStateOf：只有該列的文字變了才重組那一列）。 */
@Composable
private fun rememberCategoryStatuses(vm: AppViewModel): Map<SettingsCategory, State<CategoryStatus>> {
    val c = B570Theme.colors
    val settings = vm.settings.collectAsStateWithLifecycle()
    val state = vm.connectionState.collectAsStateWithLifecycle()
    val demo = vm.isDemo.collectAsStateWithLifecycle()
    val car = vm.currentCar.collectAsStateWithLifecycle()
    val esp = vm.espInfo.collectAsStateWithLifecycle()
    val params = vm.params.collectAsStateWithLifecycle()
    val cars = vm.savedCars.collectAsStateWithLifecycle()
    val telem = vm.telemetrySlow.collectAsStateWithLifecycle()
    val sta = vm.staInfo.collectAsStateWithLifecycle()
    val linkMode = vm.linkMode.collectAsStateWithLifecycle()
    val hotspotSwitch = vm.hotspotSwitch.collectAsStateWithLifecycle()
    val hotspotSupported = vm.hotspotSupported.collectAsStateWithLifecycle()
    val ok = c.ok
    val warn = c.warn
    val accent = c.accent
    val accent2 = c.accent2
    return remember(ok, warn, accent, accent2) {
        mapOf(
            SettingsCategory.WIFI to derivedStateOf {
                val st = state.value
                val ssid = esp.value.wifi?.ssid?.takeIf { it.isNotEmpty() } ?: car.value?.ssid
                when {
                    demo.value -> CategoryStatus(car.value?.ssid ?: "展示車", accent2)
                    st is ConnectionState.Connected -> CategoryStatus(ssid ?: "已連線", ok)
                    st is ConnectionState.LinkLost -> CategoryStatus("訊號中斷", warn, ledPulse = true)
                    else -> CategoryStatus("未連線", null)
                }
            },
            SettingsCategory.LINK to derivedStateOf {
                val sw = hotspotSwitch.value
                val st = state.value
                val info = sta.value
                val saved = car.value?.let { cur -> cars.value.firstOrNull { it.ssid == cur.ssid } }
                when {
                    sw != null && sw.isActive -> CategoryStatus("切換到熱點模式中…", accent2, ledPulse = true)
                    sw != null -> CategoryStatus("熱點上沒有找到車子", warn)
                    st.isSession && linkMode.value == LinkMode.HOTSPOT -> CategoryStatus("手機熱點 · 照用行動網路", ok)
                    st.isSession && info?.enabled == true && info.state == StaState.FALLBACK -> CategoryStatus("找不到熱點 · 已改回直連", warn)
                    st.isSession && hotspotSupported.value == false -> CategoryStatus("直連 · 不支援熱點模式", null)
                    st.isSession -> CategoryStatus("直連", null)
                    saved?.hotspotMode == true -> CategoryStatus("熱點模式", null)
                    else -> CategoryStatus("直連", null)
                }
            },
            SettingsCategory.CONTROL to derivedStateOf {
                val s = settings.value
                CategoryStatus("${s.joystickMode.labelZh} · ${s.defaultGear.labelZh}", null)
            },
            SettingsCategory.APPEARANCE to derivedStateOf {
                CategoryStatus(settings.value.accent.labelZh, accent)
            },
            SettingsCategory.TELEMETRY to derivedStateOf {
                val hz = params.value[Params.TELEM_HZ]
                val dirty = telem.value?.paramsDirty == true
                when {
                    hz != null -> CategoryStatus("${hz.roundToInt()} Hz${if (dirty) " · 未儲存" else ""}", if (dirty) warn else null)
                    state.value.isSession -> CategoryStatus("讀取中…", null)
                    else -> CategoryStatus("連線後可調整", null)
                }
            },
            SettingsCategory.DEMO to derivedStateOf {
                when {
                    demo.value -> CategoryStatus("展示中", accent2, ledPulse = true)
                    settings.value.demoMode -> CategoryStatus("開啟 App 時自動進入", null)
                    else -> CategoryStatus("未開啟", null)
                }
            },
            SettingsCategory.CARS to derivedStateOf {
                val n = cars.value.size
                val s = settings.value
                val mode = when {
                    s.autoConnect && s.autoReconnect -> "自動連線"
                    s.autoConnect -> "自動連線 · 不重連"
                    s.autoReconnect -> "斷線自動重連"
                    else -> "手動連線"
                }
                CategoryStatus(if (n == 0) "還沒有記住的車 · $mode" else "$n 台 · $mode", null)
            },
            SettingsCategory.ABOUT to derivedStateOf {
                CategoryStatus("版本 ${BuildConfig.VERSION_NAME}", null)
            },
        )
    }
}
