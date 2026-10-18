package com.b570.pilot.ui.screens.connect

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.b570.pilot.BuildConfig
import com.b570.pilot.data.SavedCar
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.SignalBars
import com.b570.pilot.ui.components.StatusPill
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay

// ====================================================================== 進場動畫

/** 首次進入時依序浮現；同一次組合中捲出再捲回的項目直接顯示（不重播）。 */
@Composable
internal fun Modifier.entrance(order: Int, introStartMs: Long): Modifier {
    val anim = remember { Animatable(if (SystemClock.uptimeMillis() - introStartMs > INTRO_WINDOW_MS) 1f else 0f) }
    LaunchedEffect(anim) {
        if (anim.value < 1f) {
            delay(70L * order)
            anim.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 260f))
        }
    }
    return this.graphicsLayer {
        val v = anim.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 32.dp.toPx()
        val s = 0.97f + 0.03f * v
        scaleX = s
        scaleY = s
    }
}

private const val INTRO_WINDOW_MS = 1400L

// ====================================================================== 頂部 + 品牌

@Composable
internal fun ConnectTopRow(state: ConnectionState, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val color = when (state) {
        ConnectionState.Idle -> c.textMuted
        is ConnectionState.Discovering, is ConnectionState.RequestingWifi, ConnectionState.Handshaking -> c.accent
        is ConnectionState.Connected -> c.ok
        is ConnectionState.LinkLost -> c.warn
        is ConnectionState.Failed -> c.danger
    }
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusPill(state.labelZh, color, pulsing = state.isConnecting || state is ConnectionState.LinkLost)
        Spacer(Modifier.weight(1f))
        Text("v${BuildConfig.VERSION_NAME}", style = B570Theme.type.mono, color = c.textMuted)
    }
}

/**
 * 品牌標題「B570 PILOT」：漸層字 + 霓虹柔光。
 * @param maxWidth 可用寬度；放不下時等比例縮小（含字距），字級 1.3 倍也不會被切掉
 * @param baseSize 放得下時的字級
 */
@Composable
internal fun BrandTitle(modifier: Modifier = Modifier, maxWidth: Dp = Dp.Unspecified, baseSize: TextUnit = 34.sp) {
    val c = B570Theme.colors
    val style = remember(c.accent, c.accent2, c.textPrimary, baseSize) {
        TextStyle(
            brush = Brush.linearGradient(listOf(c.textPrimary, c.accent, c.accent2)),
            fontSize = baseSize,
            lineHeight = baseSize * (40f / 34f),
            letterSpacing = baseSize * (6f / 34f),
            shadow = Shadow(c.accent.copy(alpha = 0.55f), blurRadius = 28f),
        )
    }
    val text = remember {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("B570") }
            withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append(" PILOT") }
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = 2)
    val density = LocalDensity.current
    val fitted = remember(style, maxWidth, density) {
        if (!maxWidth.isSpecified) {
            style
        } else {
            val avail = with(density) { (maxWidth - 8.dp).toPx() }
            val w = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
            if (w <= avail || w <= 0) {
                style
            } else {
                val k = (avail / w) * 0.98f
                style.copy(fontSize = style.fontSize * k, lineHeight = style.lineHeight * k, letterSpacing = style.letterSpacing * k)
            }
        }
    }
    // 字距會加在最後一個字後面，左邊補同樣寬度讓視覺置中
    Text(
        text, style = fitted, maxLines = 1, softWrap = false,
        modifier = modifier.padding(start = with(density) { fitted.letterSpacing.toDp() }),
    )
}

// ====================================================================== 未連線：主要動作

/**
 * 未連線：主要動作（操作台最上方），依 [hero]：
 * - [IdleHero.FIRST_PAIR]：還沒有記住的車 → 配對主視覺（三步驟 + 大顆「掃描 QR 配對」）。
 * - [IdleHero.QUICK_CONNECT]：剛好一台記住的車在附近 → 大顆「連線 <名稱>」，掃描 / 手動輸入變次要。
 * - [IdleHero.SCAN]：大顆「掃描 QR 配對」+ 並排「手動輸入 / 展示模式」。
 */
@Composable
internal fun IdleActions(
    hero: IdleHero,
    quickCar: SavedCar?,
    quickDbm: Int?,
    onQuickConnect: () -> Unit,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onDemo: () -> Unit,
    sheenPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    if (hero == IdleHero.FIRST_PAIR) {
        FirstPairHero(onScan = onScan, onManual = onManual, onDemo = onDemo, sheenPaused = sheenPaused, modifier = modifier)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hero == IdleHero.QUICK_CONNECT && quickCar != null) {
            NeonButton(
                "連線 ${quickCar.displayName}", onQuickConnect,
                Modifier.fillMaxWidth().height(60.dp).ctaSheen(sheenPaused),
                icon = Icons.Rounded.Bolt,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("掃描配對", onScan, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.QrCodeScanner)
                NeonButton("手動輸入", onManual, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.Keyboard)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (quickDbm != null) {
                    SignalBars(signalLevelOf(quickDbm), color = c.ok)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "「${quickCar.displayName}」就在附近，點一下就能開始駕駛",
                    style = B570Theme.type.caption, color = c.textSecondary, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            ScanCta(onScan, sheenPaused, Modifier.fillMaxWidth().height(56.dp))
            SecondaryActionsRow(onManual = onManual, onDemo = onDemo)
            Text(
                "點下方記住的車就能連線；新車請掃描 OLED 上的 QR\u00A0Code",
                style = B570Theme.type.caption, color = c.textMuted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

/** 大顆「掃描 QR 配對」（主按鈕 + 光澤）。 */
@Composable
internal fun ScanCta(onScan: () -> Unit, sheenPaused: Boolean, modifier: Modifier = Modifier) {
    NeonButton("掃描 QR 配對", onScan, modifier.ctaSheen(sheenPaused), icon = Icons.Rounded.QrCodeScanner)
}

/** 並排「手動輸入 / 展示模式」。 */
@Composable
internal fun SecondaryActionsRow(onManual: () -> Unit, onDemo: () -> Unit, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NeonButton("手動輸入", onManual, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.Keyboard)
        NeonButton(
            "展示模式", onDemo, Modifier.weight(1f),
            style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.PlayCircleOutline, color = c.accent2,
        )
    }
}

/** 主按鈕上緩緩掃過的光澤（筆刷 / 路徑快取，每幀只平移）。 */
@Composable
private fun Modifier.ctaSheen(paused: Boolean): Modifier {
    val still = rememberReduceMotion()
    if (paused || still) return this
    val t = rememberInfiniteTransition(label = "sheen")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "sheenP")
    return this.drawWithCache {
        val band = size.width * 0.32f
        val brush = Brush.horizontalGradient(
            0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.28f), 1f to Color.Transparent,
            startX = 0f, endX = band,
        )
        val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(16.dp.toPx()))) }
        onDrawWithContent {
            drawContent()
            // 前 45 % 時間掃過，其餘停住（不會一直閃）
            val f = (p.value / 0.45f).coerceAtMost(1f)
            if (f < 1f) {
                clipPath(clip) {
                    translate(left = -band + (size.width + band) * f) {
                        drawRect(brush, size = Size(band, size.height))
                    }
                }
            }
        }
    }
}

// ====================================================================== 連線中：步驟

private enum class StepStatus { PENDING, ACTIVE, DONE }

/** 這次連線走哪條路（步驟條的標題 / 說明不同）。 */
internal enum class ConnectPath { DIRECT, HOTSPOT }

private val STEP_TITLES = listOf("請求 Wi-Fi", "等待系統確認", "握手", "完成")
private val HOTSPOT_STEP_TITLES = listOf("在熱點找車", "握手", "完成")

/** 步驟標題：直連 4 步（請求 Wi-Fi → 等待系統確認 → 握手 → 完成）；熱點 3 步（在熱點找車 → 握手 → 完成）。 */
internal fun stepTitlesOf(path: ConnectPath): List<String> = if (path == ConnectPath.HOTSPOT) HOTSPOT_STEP_TITLES else STEP_TITLES

/**
 * 目前在第幾步（= 已完成幾步；連上 = 步數）。其他狀態（例如退場中的失敗）沿用 [last]。
 * @param awaitingSystem 直連：請求 Wi-Fi 一陣子了（系統對話框可能已跳出）
 */
internal fun connectStepOf(state: ConnectionState, path: ConnectPath, awaitingSystem: Boolean, last: Int): Int = when (path) {
    ConnectPath.DIRECT -> when (state) {
        is ConnectionState.RequestingWifi -> if (awaitingSystem) 1 else 0
        ConnectionState.Handshaking -> 2
        is ConnectionState.Connected, is ConnectionState.LinkLost -> STEP_TITLES.size
        else -> last
    }
    ConnectPath.HOTSPOT -> when (state) {
        is ConnectionState.Discovering -> 0
        ConnectionState.Handshaking -> 1
        is ConnectionState.Connected, is ConnectionState.LinkLost -> HOTSPOT_STEP_TITLES.size
        else -> last
    }
}

/**
 * 連線中 / 剛連上：橫向步驟條（橫向畫面高度有限，步驟排一列）+ 目前步驟說明 + 取消（右上，永遠看得到）。
 * 熱點模式（[ConnectionState.Discovering]）：「在熱點網路尋找車子…」，找不到改直連時步驟條換成直連的 4 步。
 * @param phoneHotspotOn 手機熱點看起來開著
 * @param hotspotCar 這台車設定為熱點模式（熱點沒開時提示「請開啟手機熱點」）
 * @param onOpenHotspot 打開系統熱點設定
 */
@Composable
internal fun ConnectStepper(
    state: ConnectionState,
    carName: String,
    ssid: String?,
    isDemo: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    autoLabel: String? = null,
    phoneHotspotOn: Boolean = false,
    hotspotCar: Boolean = false,
    onOpenHotspot: () -> Unit = {},
) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val requesting = state as? ConnectionState.RequestingWifi
    val discovering = state as? ConnectionState.Discovering
    var awaitingSystem by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    var searchingLong by remember { mutableStateOf(false) }
    var searchingSlow by remember { mutableStateOf(false) }
    LaunchedEffect(requesting) {
        awaitingSystem = false
        slow = false
        if (requesting != null) {
            delay(900)
            awaitingSystem = true
            delay(11_000)
            slow = true
        }
    }
    LaunchedEffect(discovering) {
        searchingLong = false
        searchingSlow = false
        if (discovering != null) {
            delay(1_000)
            searchingLong = true
            delay(7_000)
            searchingSlow = true
        }
    }
    // 這次連線走哪條路：看過 Discovering = 熱點；之後改 RequestingWifi = 找不到改直連（非 state 暫存，組合期間寫入安全）
    val pathRef = remember { arrayOf(if (state is ConnectionState.Discovering) ConnectPath.HOTSPOT else ConnectPath.DIRECT) }
    when (state) {
        is ConnectionState.Discovering -> pathRef[0] = ConnectPath.HOTSPOT
        is ConnectionState.RequestingWifi -> pathRef[0] = ConnectPath.DIRECT
        else -> Unit
    }
    val path = pathRef[0]
    val titles = stepTitlesOf(path)
    // 退場動畫期間狀態可能已變（例如失敗），保留最後的步驟
    val last = remember { mutableIntStateOf(0) }
    val target = connectStepOf(state, path, awaitingSystem, last.intValue).coerceAtMost(titles.size)
    LaunchedEffect(target) {
        if (target > last.intValue) haptics.tick()
        last.intValue = target
    }
    val done = target >= titles.size
    val hints = if (path == ConnectPath.HOTSPOT) {
        listOf("在熱點網路尋找車子…", "和車子交換連線資訊", "準備開始駕駛")
    } else {
        listOf(
            if (ssid != null && !isDemo) "向系統請求連上「$ssid」" else "準備連線",
            if (isDemo) "展示模式不需要確認" else "請在彈窗中按「連線」",
            "和車子交換連線資訊",
            "準備開始駕駛",
        )
    }
    val glow by animateColorAsState(if (done) c.ok else if (path == ConnectPath.HOTSPOT) c.accent2 else c.accent, label = "stepGlow")

    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.l), glow = glow.copy(alpha = 0.55f))
            .padding(B570Theme.spacing.l),
    ) {
        val name = carName.ifBlank { ssid ?: "車子" }
        if (autoLabel != null) {
            // 開啟 App 自動連線 / 桌面捷徑：大標題「正在連線 <名稱>…」，取消鈕放大
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(autoLabel, glow, pulsing = !done, icon = Icons.Rounded.Bolt)
                if (path == ConnectPath.HOTSPOT) {
                    Spacer(Modifier.width(6.dp))
                    StatusPill("手機熱點", c.accent2, icon = Icons.Rounded.WifiTethering)
                }
                Spacer(Modifier.weight(1f))
                NeonButton(
                    "取消", onCancel,
                    style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.Close, enabled = !done,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotBadge(active = true, color = glow)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (done) "已連線 $name" else "正在連線 $name…",
                        style = B570Theme.type.titleL, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (ssid != null && !isDemo && carName != ssid) {
                        Text(ssid, style = B570Theme.type.mono, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotBadge(active = true, color = glow)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            done -> "已連線"
                            path == ConnectPath.HOTSPOT -> "正在連線 · 手機熱點"
                            else -> "正在連線"
                        },
                        style = B570Theme.type.label, color = glow, maxLines = 1,
                    )
                    Text(name, style = B570Theme.type.titleM, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ssid != null && !isDemo && carName != ssid) {
                        Text(ssid, style = B570Theme.type.mono, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                NeonButton(
                    "取消", onCancel,
                    style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.Close, compact = true, enabled = !done,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        key(path) { StepTrack(target, titles, glow) }
        Spacer(Modifier.height(10.dp))
        val hintIndex = target.coerceAtMost(hints.lastIndex)
        AnimatedContent(
            targetState = hints[hintIndex],
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(spring(stiffness = 400f)) { it / 2 })
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "stepHint",
        ) { hint ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).drawBehind { drawCircle(if (done) c.ok else glow) })
                Spacer(Modifier.width(8.dp))
                Text(hint, style = B570Theme.type.bodySmall, color = if (done) c.ok else glow)
            }
        }
        AnimatedVisibility(
            visible = slow && target == 1 && path == ConnectPath.DIRECT,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                "沒看到彈窗？確認車子已開機，手機靠近一點再試。",
                style = B570Theme.type.bodySmall, color = c.warn,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // 熱點模式的車、手機熱點沒開：提示開啟（開了之後車子會自動連上熱點）
        AnimatedVisibility(
            visible = discovering != null && searchingLong && hotspotCar && !phoneHotspotOn && !isDemo,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "手機熱點沒開？開啟後車子會自動連上",
                    style = B570Theme.type.bodySmall, color = c.warn, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                NeonButton("開啟熱點", onOpenHotspot, compact = true, color = c.accent2, icon = Icons.Rounded.WifiTethering)
            }
        }
        AnimatedVisibility(
            visible = discovering != null && searchingSlow && (phoneHotspotOn || !hotspotCar),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                "還在找…確認手機熱點是 2.4\u00A0GHz、車子已開機",
                style = B570Theme.type.bodySmall, color = c.warn,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * 步驟排成一列：圓形指示 + 連接線（完成時由左往右填滿）+ 下方標題（最多 2 行）。
 * 連接線的填色進度只在繪製階段讀取。步數改變（熱點 → 直連）時呼叫端用 key 重建。
 */
@Composable
private fun StepTrack(target: Int, titles: List<String>, accent: Color, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val fillSpec = spring<Float>(stiffness = 220f)
    // 第 i 段 = 步驟 i → i+1 之間的線
    val segs = (0 until titles.size - 1).map { i ->
        animateFloatAsState(if (target > i) 1f else 0f, fillSpec, label = "seg$i")
    }
    val muted = c.textMuted.copy(alpha = 0.25f)
    Row(modifier.fillMaxWidth()) {
        titles.forEachIndexed { i, title ->
            val status = when {
                i < target -> StepStatus.DONE
                i == target -> StepStatus.ACTIVE
                else -> StepStatus.PENDING
            }
            val titleColor by animateColorAsState(
                when (status) {
                    StepStatus.ACTIVE -> c.textPrimary
                    StepStatus.DONE -> c.textSecondary
                    StepStatus.PENDING -> c.textMuted
                },
                label = "stepTitle",
            )
            val left = segs.getOrNull(i - 1)
            val right = segs.getOrNull(i)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .drawBehind {
                            val cy = size.height / 2f
                            val cx = size.width / 2f
                            val clear = 18.dp.toPx() // 圓半徑 14 dp + 4 dp 空隙
                            val th = 2.dp.toPx()
                            // 左半：上一段的後半（前半段填滿後才開始填）
                            if (left != null && cx - clear > 0f) {
                                val x1 = cx - clear
                                drawLine(muted, Offset(0f, cy), Offset(x1, cy), th)
                                val f = (left.value * 2f - 1f).coerceIn(0f, 1f)
                                if (f > 0f) drawLine(accent, Offset(0f, cy), Offset(x1 * f, cy), th)
                            }
                            // 右半：這一段的前半
                            if (right != null && cx + clear < size.width) {
                                val x0 = cx + clear
                                drawLine(muted, Offset(x0, cy), Offset(size.width, cy), th)
                                val f = (right.value * 2f).coerceIn(0f, 1f)
                                if (f > 0f) drawLine(accent, Offset(x0, cy), Offset(x0 + (size.width - x0) * f, cy), th)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    StepIndicator(i + 1, status)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    title,
                    style = B570Theme.type.label, color = titleColor, textAlign = TextAlign.Center, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(number: Int, status: StepStatus) {
    val c = B570Theme.colors
    val doneScale by animateFloatAsState(
        if (status == StepStatus.DONE) 1f else 0f,
        spring(dampingRatio = 0.5f, stiffness = 500f), label = "stepDone",
    )
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        // 外環（待辦 / 進行中）
        val ringColor = if (status == StepStatus.PENDING) c.textMuted.copy(alpha = 0.5f) else c.accent
        Canvas(Modifier.size(28.dp)) {
            drawCircle(ringColor, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
        if (status == StepStatus.ACTIVE) SpinningArc()
        if (status == StepStatus.PENDING) {
            Text("$number", style = B570Theme.type.caption, color = c.textMuted)
        }
        if (status == StepStatus.ACTIVE) {
            Box(Modifier.size(8.dp).clip(CircleShape).drawBehind { drawCircle(c.accent) })
        }
        // 完成：彈出的實心勾
        Box(
            Modifier
                .size(28.dp)
                .graphicsLayer {
                    scaleX = doneScale
                    scaleY = doneScale
                    alpha = doneScale.coerceIn(0f, 1f)
                }
                .clip(CircleShape)
                .drawBehind { drawCircle(Brush.linearGradient(listOf(c.accent, c.ok))) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SpinningArc() {
    val c = B570Theme.colors
    val t = rememberInfiniteTransition(label = "arc")
    val rot = t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "arcRot")
    Canvas(Modifier.size(28.dp).graphicsLayer { rotationZ = rot.value }) {
        val sw = 2.5.dp.toPx()
        drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, c.accent)), 0f, 300f, false,
            topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
    }
}

// ====================================================================== 失敗

/**
 * 連線失敗卡：原因 + 關閉 / 主要動作（開啟熱點 / 開啟 Wi-Fi / 重試 / 重新掃描）。
 * [ConnectionState.Failed.hotspotHint]（熱點模式找不到車）：主要動作是「開啟熱點」，下面一列再放「重試」（+「開啟 Wi-Fi」）；
 * [waitingHotspot] = 已打開熱點設定，手機熱點一開就自動重試。
 */
@Composable
internal fun FailedCard(
    failed: ConnectionState.Failed,
    onRetry: () -> Unit,
    onOpenWifi: () -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoRetries: Int? = null,
    onOpenHotspot: (() -> Unit)? = null,
    waitingHotspot: Boolean = false,
    phoneHotspotOn: Boolean = false,
) {
    val c = B570Theme.colors
    val shake = remember { Animatable(0f) }
    LaunchedEffect(failed) {
        shake.snapTo(0f)
        shake.animateTo(0f, spring(dampingRatio = 0.22f, stiffness = 900f), initialVelocity = 900f)
    }
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shake.value }
            .glass(RoundedCornerShape(B570Theme.radii.l), glow = c.danger.copy(alpha = 0.5f))
            .padding(B570Theme.spacing.l),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).glass(CircleShape, fill = c.danger.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (failed.wifiDisabled) Icons.Rounded.WifiOff else Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (autoRetries != null) "自動重連失敗" else "連線失敗", style = B570Theme.type.titleS, color = c.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(failed.reasonZh, style = B570Theme.type.bodySmall, color = c.textSecondary)
                if (autoRetries != null) {
                    Text(
                        if (failed.wifiDisabled || !failed.retryable) "已自動重試 $autoRetries 次"
                        else "已自動重試 $autoRetries 次。確認車子已開機、靠近一點再按「重試」",
                        style = B570Theme.type.caption, color = c.warn,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        val hotspot = failed.hotspotHint && onOpenHotspot != null
        // 熱點已經開著：主要動作改成「重試」（不必再開熱點設定）
        val openHotspotFirst = hotspot && !phoneHotspotOn
        AnimatedVisibility(hotspot && waitingHotspot, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                "開啟手機熱點後會自動重試…",
                style = B570Theme.type.caption, color = c.accent2,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton("關閉", onDismiss, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY)
            when {
                openHotspotFirst -> NeonButton("開啟熱點", { onOpenHotspot?.invoke() }, Modifier.weight(1.4f), icon = Icons.Rounded.WifiTethering, color = c.accent2)
                hotspot && failed.retryable -> NeonButton("重試", onRetry, Modifier.weight(1.4f), icon = Icons.Rounded.Refresh)
                failed.wifiDisabled -> NeonButton("開啟 Wi-Fi", onOpenWifi, Modifier.weight(1.4f), icon = Icons.Rounded.Wifi)
                failed.retryable -> NeonButton("重試", onRetry, Modifier.weight(1.4f), icon = Icons.Rounded.Refresh)
                else -> NeonButton("重新掃描", onRescan, Modifier.weight(1.4f), icon = Icons.Rounded.QrCodeScanner)
            }
        }
        val retryBelow = openHotspotFirst && failed.retryable
        // 主要動作已經是「開啟 Wi-Fi」時不重複
        val wifiBelow = hotspot && failed.wifiDisabled && (openHotspotFirst || failed.retryable)
        if (retryBelow || wifiBelow) {
            // 熱點模式：也可以直接重試（熱點其實已開），或開 Wi-Fi 改用直連
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (retryBelow) {
                    NeonButton("重試", onRetry, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.Refresh, compact = true)
                }
                if (wifiBelow) {
                    NeonButton("開啟 Wi-Fi", onOpenWifi, Modifier.weight(1f), style = NeonButtonStyle.SECONDARY, icon = Icons.Rounded.Wifi, compact = true)
                }
            }
        }
    }
}

// ====================================================================== Wi-Fi 關閉

/**
 * Wi-Fi 關閉卡的說明（只要打開開關、不用連網路）。[pendingName] = 開啟後會自動連上的車（null = 沒有在等）；
 * 沒有在等時提醒熱點模式不必開 Wi-Fi。
 */
internal fun wifiOffHintZh(pendingName: String?): String =
    if (pendingName != null) "打開開關就好，不用連網路；開啟後自動連上「$pendingName」"
    else "連車子只要打開 Wi-Fi 開關，不用連網路；改用熱點模式就不必開"

/**
 * 要不要顯示「Wi-Fi 已關閉」卡（純函式，`ConnectWifiGateTest`）：Wi-Fi 關著、失敗卡沒在說 Wi-Fi，而且
 * 正在等 Wi-Fi 直連（[waitingWifi]），或是直連的使用者（手機熱點沒開、最近的車不是熱點模式）。
 * 熱點模式的使用者不提示開 Wi-Fi（很多手機開 Wi-Fi 會關掉熱點），車子卡片會請他開啟手機熱點。
 * @param hotspotUser 最近連線的那台車是熱點模式
 */
internal fun showWifiOffCardOf(
    wifiEnabled: Boolean,
    failedWifiDisabled: Boolean,
    waitingWifi: Boolean,
    phoneHotspotOn: Boolean,
    hotspotUser: Boolean,
): Boolean = !wifiEnabled && !failedWifiDisabled && (waitingWifi || (!phoneHotspotOn && !hotspotUser))

@Composable
internal fun WifiOffCard(
    pendingName: String?,
    onOpen: () -> Unit,
    onCancelPending: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.l), glow = c.warn.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).glass(CircleShape, fill = c.warn.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.WifiOff, null, tint = c.warn, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Wi-Fi 已關閉", style = B570Theme.type.titleS, color = c.textPrimary)
            Text(wifiOffHintZh(pendingName), style = B570Theme.type.bodySmall, color = c.textSecondary)
            if (pendingName != null) {
                Text(
                    "不連了",
                    style = B570Theme.type.label, color = c.textMuted,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .noRippleClick(onCancelPending)
                        .padding(vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        NeonButton("開啟 Wi-Fi", onOpen, compact = true, color = c.warn)
    }
}

// ====================================================================== 頁尾

/**
 * 操作台最下方：展示模式入口（連線中 / 失敗時主要動作被步驟條或失敗卡取代，這裡保留入口）+ 小提醒。
 * @param showDemo false = 主要動作已有「展示模式」鍵，這裡只放說明
 */
@Composable
internal fun ConnectFooter(onDemo: () -> Unit, showDemo: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    Column(modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showDemo) {
            NeonButton("展示模式", onDemo, style = NeonButtonStyle.GHOST, icon = Icons.Rounded.PlayCircleOutline)
        }
        Text("沒有車子也能用展示模式體驗所有功能", style = B570Theme.type.caption, color = c.textMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SignalCellularAlt, null, tint = c.textMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("連線時手機行動數據不會中斷", style = B570Theme.type.caption, color = c.textMuted)
        }
    }
}

// ====================================================================== 共用小元件

/** 兩輪自平衡車的正面小圖示（玻璃圓底）。 */
@Composable
internal fun BotBadge(active: Boolean, modifier: Modifier = Modifier, color: Color = B570Theme.colors.accent) {
    val c = B570Theme.colors
    val tint = if (active) color else c.textSecondary
    Box(
        modifier.size(44.dp).glass(CircleShape, fill = tint.copy(alpha = if (active) 0.14f else 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val wheelR = CornerRadius(w * 0.08f)
            // 左右輪
            drawRoundRect(tint, topLeft = Offset(0f, h * 0.38f), size = Size(w * 0.2f, h * 0.62f), cornerRadius = wheelR)
            drawRoundRect(tint, topLeft = Offset(w * 0.8f, h * 0.38f), size = Size(w * 0.2f, h * 0.62f), cornerRadius = wheelR)
            // 車身
            drawRoundRect(tint, topLeft = Offset(w * 0.27f, h * 0.04f), size = Size(w * 0.46f, h * 0.84f), cornerRadius = CornerRadius(w * 0.1f), alpha = 0.3f)
            drawRoundRect(tint, topLeft = Offset(w * 0.27f, h * 0.04f), size = Size(w * 0.46f, h * 0.84f), cornerRadius = CornerRadius(w * 0.1f), style = Stroke(1.3.dp.toPx()))
            // OLED
            drawRoundRect(tint, topLeft = Offset(w * 0.35f, h * 0.18f), size = Size(w * 0.30f, h * 0.2f), cornerRadius = CornerRadius(w * 0.03f))
        }
    }
}

/** 無水波紋點擊（小文字連結用）。 */
@Composable
internal fun Modifier.noRippleClick(onClick: () -> Unit): Modifier {
    val haptics = rememberHaptics()
    return this.noRippleClickable { haptics.click(); onClick() }
}
