package com.b570.pilot.ui.screens.connect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.CAR_SSID_PREFIX
import com.b570.pilot.data.SavedCar
import com.b570.pilot.link.DiscoveredCar
import com.b570.pilot.link.LinkPermissions
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.SignalBars
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ====================================================================== 純邏輯（單元測試：ConnectQuickTest）

/** 記住的車在不在附近（車子卡片上的標記）。 */
internal sealed interface NearbyBadge {
    /** 沒權限 / Wi-Fi 關閉 / 還沒有掃描結果：不顯示任何標記。 */
    data object Unknown : NearbyBadge

    /** 在附近，訊號 [dbm]。 */
    data class InRange(val dbm: Int) : NearbyBadge

    /** 掃描結果可用，但沒看到這台車（關機或太遠）。 */
    data object OutOfRange : NearbyBadge

    /** 在手機熱點（或同一個網路）上找到這台車（DISCOVER）。 */
    data object OnHotspot : NearbyBadge
}

/**
 * @param onHotspot 在手機熱點上找到的車（車名）
 * @param hotspotMode 這台車設定為熱點模式：它不開自己的 Wi-Fi，Wi-Fi 掃描看不到不代表不在附近（不標「不在範圍內」）
 */
internal fun nearbyBadgeOf(
    ssid: String,
    nearby: Map<String, Int>,
    scanUsable: Boolean,
    onHotspot: Set<String> = emptySet(),
    hotspotMode: Boolean = false,
): NearbyBadge {
    val dbm = nearby[ssid]
    return when {
        ssid in onHotspot -> NearbyBadge.OnHotspot
        dbm != null -> NearbyBadge.InRange(dbm)
        hotspotMode -> NearbyBadge.Unknown
        scanUsable -> NearbyBadge.OutOfRange
        else -> NearbyBadge.Unknown
    }
}

/** 在手機熱點上找到、但還沒記住的車（不用掃 QR，點一下就能連），依車名排序。 */
internal fun hotspotNewCarsOf(found: List<DiscoveredCar>, saved: List<SavedCar>): List<DiscoveredCar> {
    if (found.isEmpty()) return emptyList()
    val known = saved.mapTo(HashSet()) { it.ssid }
    return found.filter { it.ssid.isNotBlank() && it.ssid !in known }.distinctBy { it.ssid }.sortedBy { it.ssid }
}

/** 剛好一台記住的車「找得到」（Wi-Fi 掃描看到它的 AP，或在手機熱點上找到）→ 那台；否則 null。 */
internal fun soleReachableCarOf(saved: List<SavedCar>, nearby: Map<String, Int>, onHotspot: Set<String>): SavedCar? {
    if (nearby.isEmpty() && onHotspot.isEmpty()) return null
    return saved.filter { it.ssid in nearby || it.ssid in onHotspot }.singleOrNull()
}

/** 要不要在連線頁定期掃描手機熱點上的車：手機熱點開著，或有記住的車是熱點模式（也可能在同一個 Wi-Fi 上）。 */
internal fun shouldDiscoverHotspot(phoneHotspotOn: Boolean, saved: List<SavedCar>): Boolean =
    phoneHotspotOn || saved.any { it.hotspotMode }

/** 記住的車卡片要不要提示「請開啟手機熱點」：熱點模式的車、手機熱點沒開、而且現在也找不到它（Wi-Fi 掃描 / 熱點都沒看到）。 */
internal fun needsHotspotHint(car: SavedCar, phoneHotspotOn: Boolean, badge: NearbyBadge): Boolean =
    car.hotspotMode && !phoneHotspotOn && badge != NearbyBadge.OnHotspot && badge !is NearbyBadge.InRange

/** 附近還沒配對的車（`B570-` 開頭、不在記住的車裡），訊號強的在前，最多 [max] 台。 */
internal fun unpairedNearbyOf(nearby: Map<String, Int>, saved: List<SavedCar>, max: Int = 3): List<Pair<String, Int>> {
    if (nearby.isEmpty()) return emptyList()
    val known = saved.mapTo(HashSet()) { it.ssid }
    return nearby.entries
        .filter { (ssid, _) -> ssid.startsWith(CAR_SSID_PREFIX) && ssid !in known }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(max)
        .map { it.key to it.value }
}

/** 剛好只有一台記住的車在附近 → 那台（連線頁主按鈕改成「連線 <名稱>」）；0 台或好幾台 → null。 */
internal fun soleNearbyCarOf(saved: List<SavedCar>, nearby: Map<String, Int>): SavedCar? {
    if (nearby.isEmpty()) return null
    val inRange = saved.filter { it.ssid in nearby }
    return inRange.singleOrNull()
}

/** dBm → 訊號 0..4 格（與 LinkStats.signalLevel 同門檻）。 */
internal fun signalLevelOf(dbm: Int): Int = when {
    dbm >= -55 -> 4
    dbm >= -65 -> 3
    dbm >= -75 -> 2
    dbm >= -85 -> 1
    else -> 0
}

/** 這台車從沒連上過：Android 第一次會跳出「連線到裝置」確認視窗（之後系統會記住）。 */
internal fun isFirstConnection(ssid: String, saved: List<SavedCar>): Boolean =
    saved.none { it.ssid == ssid && it.lastConnected > 0L }

/** 未連線時操作台最上面的主要動作。 */
internal enum class IdleHero {
    /** 還沒有記住的車：「掃描 QR 配對」放大成主視覺（含三步驟說明）。 */
    FIRST_PAIR,

    /** 剛好一台記住的車在附近：主按鈕「連線 <名稱>」。 */
    QUICK_CONNECT,

    /** 其他：主按鈕「掃描 QR 配對」，記住的車在下面點一下連線。 */
    SCAN,
}

/**
 * 主要動作的選擇。開啟 App 自動連線時，啟動當下是「正在連線」卡片（不會走到這裡）；
 * 使用者取消、中斷或自動連線失敗回到這裡時，附近只有一台記住的車一樣給一鍵連線。
 */
internal fun idleHeroOf(saved: List<SavedCar>, soleNearby: SavedCar?): IdleHero = when {
    saved.isEmpty() -> IdleHero.FIRST_PAIR
    soleNearby != null -> IdleHero.QUICK_CONNECT
    else -> IdleHero.SCAN
}

// ====================================================================== 附近的車（掃描）

/** 連線頁看到的附近車子資料。[usable] = 掃描結果可信（沒看到的車可以標「不在範圍內」）。 */
@Stable
internal class NearbyUi(val nearby: Map<String, Int>, val usable: Boolean, val active: Boolean)

/**
 * 跟著連線頁生命週期掃描附近的車：畫面在前景、有權限、Wi-Fi 開著、不在連線中才掃
 * （連線中額外的掃描會拖慢系統連線）。每 30 秒補掃一次（系統前景限制 2 分鐘 4 次，被節流就用快取）。
 * 沒權限時不掃、不顯示任何標記。
 */
@Composable
internal fun rememberNearbyUi(vm: AppViewModel, permitted: Boolean, wifiEnabled: Boolean, connecting: Boolean): NearbyUi {
    val context = LocalContext.current
    val raw by vm.nearby.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // 不能用 collectAsStateWithLifecycle：它在背景時停止收集，就看不到「已離開前景」
    val lifeState by lifecycle.currentStateFlow.collectAsState()
    val visible = lifeState.isAtLeast(Lifecycle.State.STARTED)
    val run = visible && permitted && wifiEnabled && !connecting

    DisposableEffect(vm, run) {
        if (run) vm.startNearbyScan()
        onDispose { if (run) vm.stopNearbyScan() }
    }
    LaunchedEffect(run) {
        if (!run) return@LaunchedEffect
        while (true) {
            delay(NEARBY_REFRESH_MS)
            vm.refreshNearby()
        }
    }
    // 系統有沒有任何掃描結果（家裡的 Wi-Fi 也算）：有 → 掃描正常，沒出現的車才標「不在範圍內」
    var systemHasResults by remember { mutableStateOf(false) }
    LaunchedEffect(run) {
        if (!run) return@LaunchedEffect
        while (true) {
            systemHasResults = withContext(Dispatchers.Default) { hasAnyScanResults(context) }
            delay(if (systemHasResults) 10_000L else 2_500L)
        }
    }
    val ok = permitted && wifiEnabled
    val shown = if (ok) raw else emptyMap()
    return remember(shown, ok, systemHasResults, run) {
        NearbyUi(shown, usable = ok && (shown.isNotEmpty() || systemHasResults), active = run)
    }
}

private const val NEARBY_REFRESH_MS = 30_000L

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun hasAnyScanResults(context: Context): Boolean = try {
    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    wm?.scanResults?.isNotEmpty() == true
} catch (e: SecurityException) {
    false
} catch (e: RuntimeException) {
    false
}

/** 連線權限是否已全部取得（回到前景時重新檢查，例如剛從權限對話框 / 系統設定回來）。 */
@Composable
internal fun rememberConnectPermitted(): Boolean {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(LinkPermissions.missingForConnect(context).isEmpty()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permitted = LinkPermissions.missingForConnect(context).isEmpty() }
    return permitted
}

/**
 * 連線頁顯示期間定期掃描手機熱點上的車（DISCOVER 廣播，約 1.2 秒）：[active] 時每 [periodMs] 一輪，
 * 畫面不在前景時停止；停止後保留最後的結果（連線中不更新，避免干擾系統連線）。
 */
@Composable
internal fun rememberHotspotCars(vm: AppViewModel, active: Boolean, periodMs: Long = HOTSPOT_SCAN_PERIOD_MS): State<List<DiscoveredCar>> {
    val found = remember { mutableStateOf<List<DiscoveredCar>>(emptyList()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateFlow.collectAsState()
    val run = active && lifeState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(run) {
        if (!run) return@LaunchedEffect
        while (true) {
            found.value = runCatching { vm.discoverHotspotCars(HOTSPOT_SCAN_TIMEOUT_MS) }.getOrDefault(emptyList())
            delay(periodMs)
        }
    }
    return found
}

private const val HOTSPOT_SCAN_PERIOD_MS = 5_000L
private const val HOTSPOT_SCAN_TIMEOUT_MS = 1_200L

// ====================================================================== 畫面元件

/**
 * 手機熱點上找到、還沒記住的車：「熱點上的新車：B570-XXXX」—「已在你的手機熱點上，不用掃 QR，點一下連線」。
 * 這種車是別支手機設定成熱點模式的（OLED 顯示熱點資訊、沒有 QR）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HotspotNewCarCard(car: DiscoveredCar, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    GlassCard(
        modifier.fillMaxWidth(),
        glow = c.accent2.copy(alpha = 0.40f),
        onClick = onConnect,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotBadge(active = true, color = c.accent2)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                FlowRow {
                    Text("熱點上的車：", style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, softWrap = false)
                    Text(car.ssid, style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (car.busy) "另一支手機正在控制，連線可能會被拒絕" else "已在你的手機熱點上，不用掃\u00A0QR，點一下連線",
                    style = B570Theme.type.caption, color = if (car.busy) c.warn else c.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.WifiTethering, contentDescription = null, tint = c.accent2, modifier = Modifier.size(20.dp))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * 附近還沒配對的車：「附近的新車：B570-XXXX」—「掃描車上 OLED 的 QR 即可配對」，點一下開掃描。
 * 窄欄 / 大字級時車名整段換到下一行（不會只剩「B570-7F…」，也不會從「-」切開）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NearbyNewCarCard(ssid: String, dbm: Int, onPair: () -> Unit, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    GlassCard(
        modifier.fillMaxWidth(),
        glow = c.accent2.copy(alpha = 0.35f),
        onClick = onPair,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotBadge(active = true, color = c.accent2)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                FlowRow {
                    Text("附近的新車：", style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, softWrap = false)
                    Text(ssid, style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "掃描車上 OLED 的 QR 即可配對",
                    style = B570Theme.type.caption, color = c.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            SignalBars(signalLevelOf(dbm), color = c.accent2)
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(24.dp))
        }
    }
}

/** 記住的車卡片上的「在附近 ▂▄▆」/「不在範圍內」（[NearbyBadge.Unknown] 不畫）。 */
@Composable
internal fun NearbyTag(badge: NearbyBadge, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    when (badge) {
        is NearbyBadge.InRange -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            SignalBars(signalLevelOf(badge.dbm), color = c.ok)
            Spacer(Modifier.width(4.dp))
            Text("在附近", style = B570Theme.type.caption, color = c.ok, maxLines = 1)
        }
        NearbyBadge.OutOfRange -> Text("不在範圍內", style = B570Theme.type.caption, color = c.textMuted, maxLines = 1, modifier = modifier)
        NearbyBadge.OnHotspot -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WifiTethering, null, tint = c.ok, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("在熱點上", style = B570Theme.type.caption, color = c.ok, maxLines = 1)
        }
        NearbyBadge.Unknown -> Unit
    }
}

/**
 * 第一次連某台車時的提示（Android 會跳出系統「連線到裝置」視窗）：
 * 「請在系統視窗按「連線」，之後就會自動連線」。不攔觸控；位置由呼叫端決定（不要蓋住連線卡的「取消」）。
 * @param fromTop true = 由上緣滑入，false = 由下緣滑入
 */
@Composable
internal fun ApprovalHint(visible: Boolean, modifier: Modifier = Modifier, fromTop: Boolean = true) {
    val c = B570Theme.colors
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(220)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { if (fromTop) -it else it },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(200)) { if (fromTop) -it / 2 else it / 2 },
    ) {
        Row(
            Modifier
                .widthIn(max = 560.dp)
                .glass(RoundedCornerShape(B570Theme.radii.l), fill = c.surface.copy(alpha = 0.95f), glow = c.accent.copy(alpha = 0.6f))
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).glass(CircleShape, fill = c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text("請在系統視窗按「連線」，之後就會自動連線", style = B570Theme.type.titleS, color = c.textPrimary)
                Text("只有第一次要確認，行動數據不會中斷", style = B570Theme.type.caption, color = c.textSecondary)
            }
        }
    }
}

/**
 * 還沒有記住的車：配對主視覺卡。三步驟 + 大顆「掃描 QR 配對」+ 手動輸入 / 展示模式。
 */
@Composable
internal fun FirstPairHero(
    onScan: () -> Unit,
    onManual: () -> Unit,
    onDemo: () -> Unit,
    sheenPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.l), glow = c.accent.copy(alpha = 0.45f))
            .padding(B570Theme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text("配對你的平衡車", style = B570Theme.type.titleM, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                // 兩句分行（不會只剩一個「線」掉到下一行）；QR 與 Code 之間是不換行空白
                "只要第一次：掃描 OLED 上的 QR\u00A0Code，\n之後打開 App 就會自動連線",
                style = B570Theme.type.bodySmall, color = c.textSecondary,
            )
        }
        PairStepsRow(Modifier.fillMaxWidth().padding(vertical = 2.dp))
        ScanCta(onScan, sheenPaused, Modifier.fillMaxWidth().height(60.dp))
        SecondaryActionsRow(onManual = onManual, onDemo = onDemo)
    }
}
