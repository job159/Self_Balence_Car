package com.b570.pilot.ui.screens.drive

import android.hardware.SensorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ControlCamera
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.b570.pilot.data.Gear
import com.b570.pilot.data.JoystickMode
import com.b570.pilot.data.Severity
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.EventCode
import com.b570.pilot.protocol.MotorAction
import com.b570.pilot.protocol.Params
import com.b570.pilot.protocol.Telemetry
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.MainTab
import com.b570.pilot.ui.Route
import com.b570.pilot.ui.components.EStopStyle
import com.b570.pilot.ui.components.EmergencyStopButton
import com.b570.pilot.ui.components.LinkLostBadge
import com.b570.pilot.ui.components.SegmentedSelector
import com.b570.pilot.ui.components.rememberDisplayRotation
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.components.screenPadding
import com.b570.pilot.ui.theme.B570Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「駕駛」分頁：橫向全螢幕遙控器（像遙控車發射器 / 手把）。
 *
 * ```
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ [≡] 車名 · 連線 · 馬達 · 電量 · 訊號 · 延遲 · 品質   [搖桿在左] [編排]    │
 * │ ╭ 啟動平衡 / 停止 ╮    [ 360° 搖桿 | 雙搖桿 | 體感 ]     ╭  緊急停止  ╮ │
 * │    ╭───────╮        ┌ 姿態儀 ┐┌ 車速弧 ┐            (Y)      ┌升┐   │
 * │   (  360°   )       └────────┘└────────┘         (X)   (B)    │檔│   │
 * │    ╰───────╯        [ 向量儀 045° · 72% · 速度 / 轉向 ]   (A)      └降┘   │
 * └───────────────────────────────────────────────────────────────────────┘
 * ```
 * - 360° 搖桿（預設）：一支圓形類比搖桿往任何方向推（前後 = 速度、左右 = 轉向、斜推 = 弧線）；
 *   另一手 A/B/X/Y 菱形快捷動作（原地轉圈 / 搖擺 / 點頭 / 更多）+ 直立檔位翹板；「搖桿在左 / 在右」可對調（記住）。
 * - 雙搖桿：左手直立油門、右手橫向方向（兩根拇指可同時操作）；快捷動作在中控台下方。
 * - 體感：手機像方向盤；左右各一個「按住駕駛」（按住任一個才送指令），中控台多一個水平儀。
 *
 * 低延遲：搖桿 / 感測器回呼直接呼叫 setControl（同步、無 coroutine）。
 * 安全：放開、切換模式、搖桿換邊、離開畫面（含切分頁的退場動畫期間）、被掃描頁蓋住、螢幕翻轉、App 退到背景、
 * 訊號中斷、打開選單 → 控制量立即歸零，要重新按下；
 * 緊急停止一直在右上角（不做進場動畫、不會被提示蓋住；極小視窗整頁可捲動時另外固定一顆在視窗上）。
 * 效能：50 Hz 遙測只在 derivedStateOf 與繪製階段讀取；文字讀 telemetrySlow（≤ 10 Hz）。
 */
@Composable
fun DriveScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val hapticsNow by rememberUpdatedState(haptics)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val settings by vm.settings.collectAsStateWithLifecycle()
    val params by vm.params.collectAsStateWithLifecycle()
    val loaded = vm.loadedSequence.collectAsStateWithLifecycle()
    val connection = vm.connectionState.collectAsStateWithLifecycle()
    // 自動重連中（只用「有沒有在重連」：第幾次 / 倒數由 shell 的重連提示顯示）
    val reconnectState = vm.reconnect.collectAsStateWithLifecycle()
    // 50 Hz：不在組合中直接讀 .value（只在 derivedStateOf 與繪製階段讀）
    val telem = vm.telemetry.collectAsStateWithLifecycle()
    // ≤ 10 Hz：文字與提示
    val slow = vm.telemetrySlow.collectAsStateWithLifecycle()

    val maxSpeed = params[Params.MAX_SPEED]?.takeIf { it.isFinite() && it > 0f } ?: MAX_SPEED_DEFAULT
    val fallAngle = params[Params.FALL_ANGLE] ?: FALL_ANGLE_DEFAULT
    val zeroAngle = params[Params.ZERO_ANGLE] ?: 0f
    val gear = settings.defaultGear
    val mode = settings.joystickMode
    val caps = AxisCaps(
        speed = (maxSpeed * gear.driveSpeedFactor).roundToInt(),
        turn = (gear.driveTurnFactor * 100f).roundToInt(),
    )

    val controller = remember(vm) { DriveController(vm::setControl) }
    SideEffect { controller.configure(maxSpeed, gear) }

    val motorsOn by remember { derivedStateOf { telem.value?.motorsOn == true } }
    val seqPlaying by remember { derivedStateOf { slow.value?.seqPlaying == true } }
    val lostSince by remember { derivedStateOf { (connection.value as? ConnectionState.LinkLost)?.sinceMs } }
    val linkLost = lostSince != null
    val canCommand by remember { derivedStateOf { connection.value is ConnectionState.Connected && telem.value != null } }
    val notice by remember { derivedStateOf { noticeOf(slow.value) } }
    val block by remember { derivedStateOf { controlBlockOf(connection.value, telem.value, reconnectState.value != null) } }
    val startBlocked by remember { derivedStateOf { startBlockedOf(slow.value) } }
    val playingQuick by remember {
        derivedStateOf { if (seqPlaying) QuickActions.firstOrNull { it.steps == loaded.value } else null }
    }
    val quickProgress = rememberQuickProgress(playingQuick)

    // 駕駛畫面被蓋住或正在離開 → 搖桿 / 按住駕駛停用（結束手勢並送 0），回來後要重新按下：
    // 滑入式導覽（遮罩蓋住整個遙控器）、QR 掃描覆蓋層（例如桌面捷徑「掃描配對」，下層的駕駛畫面仍在組合中）、
    // 切到別的分頁（編排鈕 / Y 鍵；退場動畫約 260 ms 期間駕駛畫面還在、搖桿還追蹤著手指）、離開主畫面
    val navOpen = nav.isNavOverlayOpen
    val covered = navOpen || nav.overlay != null || nav.currentTab != MainTab.DRIVE || nav.base !is Route.Main
    // 緊急停止：短暫停用搖桿 / 按住駕駛 → 還按著的拇指一律結束手勢（送 0），要重新按下才會再送
    // （否則按下緊急停止的同一個觸控事件會讓仍按著的搖桿把舊值重送一次）
    var estopLatch by remember { mutableStateOf(false) }
    LaunchedEffect(estopLatch) {
        if (estopLatch) {
            delay(ESTOP_INPUT_LOCK_MS)
            estopLatch = false
        }
    }
    // 螢幕左右翻轉（90 ⇄ 270，不重建 Activity）：按著的手指座標整個鏡射 → 一樣短暫停用、要重新按下（體感另外由 TiltBinding 中止）
    val rotation by rememberDisplayRotation()
    val lastRotation = remember { intArrayOf(rotation) }
    var flipLatch by remember { mutableStateOf(false) }
    LaunchedEffect(rotation) {
        if (lastRotation[0] == rotation) return@LaunchedEffect
        lastRotation[0] = rotation
        controller.stop()
        flipLatch = true
        delay(ESTOP_INPUT_LOCK_MS)
        flipLatch = false
    }
    // 訊號中斷 / 未連線：搖桿與按住駕駛停用（立即歸零，恢復後要重新按下才會送出）
    val inputEnabled = !block.blocksInput && !covered && !estopLatch && !flipLatch

    // ---------------------------------------------------------------- 安全歸零
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE || e == Lifecycle.Event.ON_STOP) controller.stop()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            controller.stop()
        }
    }

    // 360° 搖桿在左 / 在右（駕駛頁自己的偏好，記住）
    val stickRightState = rememberStickRight()
    val stickRight = stickRightState.value

    // 訊號中斷 / 打開選單 / 被蓋住或離開：控制量立即歸零
    LaunchedEffect(linkLost) { if (linkLost) controller.stop() }
    LaunchedEffect(covered) { if (covered) controller.stop() }
    // 切換模式（含從設定頁改）/ 搖桿換邊：歸零（退場的搖桿也會停用並送 0）
    LaunchedEffect(mode, stickRight) { controller.stop() }

    // 車子的危險事件 → 觸覺（文字提示由 shell 顯示）
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { e ->
                when (e.code) {
                    EventCode.FELL, EventCode.PICKED_UP, EventCode.LOW_BATTERY -> hapticsNow.reject()
                    else -> Unit
                }
            }
        }
    }

    // ---------------------------------------------------------------- 體感
    val tracker = remember(context) { TiltTracker(context.getSystemService(SensorManager::class.java)) }
    val tilt = remember(controller) { TiltSession(controller) }
    SideEffect {
        tilt.expo = settings.expo
        tilt.sensitivity = settings.tiltSensitivity
        tilt.haptics = haptics
        tilt.onAbort = {
            hapticsNow.reject()
            vm.notice("傾斜過大，已停止。請放開後重新按住", Severity.WARNING)
        }
    }
    if (mode == JoystickMode.TILT) {
        TiltBinding(tracker, tilt) {
            hapticsNow.reject()
            vm.notice("畫面翻轉了，請重新按住駕駛", Severity.INFO)
        }
    }

    // ---------------------------------------------------------------- 動作
    var motorPending by remember { mutableStateOf(false) }
    // start 由 MotorToggle 依「按下時看到的標籤」決定（不是放開當下的遙測）
    val onMotorToggle: (Boolean) -> Unit = { start ->
        haptics.tick()
        motorPending = true
        scope.launch {
            val st = vm.motor(if (start) MotorAction.START else MotorAction.STOP)
            motorPending = false
            // CANCELLED = 被緊急停止取消（已有重震動回饋）
            if (!st.isOk && st != AckStatus.CANCELLED) hapticsNow.reject()
        }
    }

    val flash = remember { Animatable(0f) }
    val onEStop: () -> Unit = {
        controller.stop()
        vm.emergencyStop()
        haptics.heavy()
        estopLatch = true
        scope.launch {
            launch {
                delay(110)
                hapticsNow.heavy()
            }
            flash.snapTo(1f)
            flash.animateTo(0f, tween(750, easing = FastOutSlowInEasing))
        }
    }

    var quickBusy by remember { mutableStateOf<String?>(null) }
    val onQuick: (QuickAction) -> Unit = onQuick@{ a ->
        if (quickBusy != null) return@onQuick
        if (!canCommand) {
            haptics.reject()
            vm.notice(
                when {
                    linkLost -> "訊號中斷，請稍候再試"
                    block == ControlBlock.RECONNECTING -> "自動重連中，請稍候"
                    else -> "尚未連線"
                },
                Severity.WARNING,
            )
            return@onQuick
        }
        if (!motorsOn) {
            haptics.reject()
            vm.notice("請先按「啟動平衡」", Severity.WARNING)
            return@onQuick
        }
        haptics.click()
        quickBusy = a.id
        scope.launch {
            // 播放中換動作：repository 會先送 SEQ_STOP 再上傳（§3.2）
            QuickPlayMemory.mark(a.id)
            val st = vm.loadAndPlaySequence(a.steps, loops = 1)
            quickBusy = null
            if (st.isOk) hapticsNow.confirm() else hapticsNow.reject()
        }
    }

    var stopBusy by remember { mutableStateOf(false) }
    val onStopSequence: () -> Unit = {
        if (!stopBusy) {
            stopBusy = true
            scope.launch {
                vm.stopSequence()
                stopBusy = false
            }
        }
    }

    val onMore: () -> Unit = {
        haptics.click()
        // 另一根拇指可能還按著搖桿：先歸零（搖桿也會因 covered 停用）
        controller.stop()
        nav.selectTab(MainTab.CHOREO)
    }
    val onMenu: () -> Unit = {
        controller.stop()
        nav.openNavOverlay()
    }
    val onMode: (JoystickMode) -> Unit = { m ->
        controller.stop()
        vm.updateSettings { s -> s.copy(joystickMode = m) }
    }
    val onGear: (Gear) -> Unit = { g -> vm.updateSettings { s -> s.copy(defaultGear = g) } }
    val onSwapStick: () -> Unit = {
        controller.stop()
        val right = !stickRightState.value
        stickRightState.value = right
        DrivePrefs.setStickRight(context, right)
    }

    // ---------------------------------------------------------------- 版面
    Box(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().screenPadding()) {
            val m = rcMetricsOf(maxWidth, maxHeight)
            // 極小的分割畫面：維持最小版面、改成可捲動（控制不被壓扁）
            val tooShort = maxHeight < RcMinHeight
            val tooNarrow = maxWidth < RcMinWidth
            val layoutW = maxOf(maxWidth, RcMinWidth)
            val layoutH = maxOf(maxHeight, RcMinHeight)
            val vScroll = rememberScrollState()
            val hScroll = rememberScrollState()
            val leftKind = leftZoneOf(mode, stickRight)
            val rightKind = rightZoneOf(mode, stickRight)
            val leftW by animateDpAsState(m.zoneWidth(leftKind), spring(dampingRatio = 0.85f, stiffness = 380f), label = "leftW")
            val rightW by animateDpAsState(m.zoneWidth(rightKind), spring(dampingRatio = 0.85f, stiffness = 380f), label = "rightW")
            val single = mode == JoystickMode.SINGLE
            // 360°：檔位翹板在按鍵區（放得下時），上緣列不重複
            val gearInTopBar = !(single && m.gearInCluster)
            // 左右手區內容（AnimatedContent 退場中的舊內容一律停用：淡出時不會再送指令）
            val zone: @Composable (ZoneKind, Boolean, Boolean) -> Unit = { k, on, onRight ->
                val zm = Modifier.fillMaxSize()
                when (k) {
                    ZoneKind.STICK_360 -> Stick360Zone(controller, telem, settings.expo, caps, on, block, m.stick360, zm)
                    ZoneKind.CLUSTER -> GamepadCluster(
                        m = m,
                        onRight = onRight,
                        gear = gear,
                        onGear = onGear,
                        busyId = quickBusy,
                        playingId = playingQuick?.id,
                        dim = !motorsOn || !canCommand,
                        progress = quickProgress,
                        onPlay = onQuick,
                        onMore = onMore,
                        modifier = zm,
                    )
                    ZoneKind.THROTTLE -> ThrottleZone(controller, telem, settings.expo, caps, on, block, m.stickMax, zm)
                    ZoneKind.STEERING -> SteeringZone(controller, telem, settings.expo, caps, on, block, m.stickMax, zm)
                    ZoneKind.PAD_LEFT -> PadZone(PadSide.LEFT, tracker.available, on, tilt, controller, block, m, zm)
                    ZoneKind.PAD_RIGHT -> PadZone(PadSide.RIGHT, tracker.available, on, tilt, controller, block, m, zm)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (tooShort) Modifier.verticalScroll(vScroll) else Modifier)
                    .then(if (tooNarrow) Modifier.horizontalScroll(hScroll) else Modifier),
            ) {
                Column(
                    Modifier
                        .size(layoutW, layoutH)
                        .padding(start = m.edge, end = m.edge, top = m.top, bottom = m.bottom),
                ) {
                    // 上緣列
                    DriveTopBar(
                        vm = vm,
                        gear = gear,
                        onGear = onGear,
                        onMenu = onMenu,
                        onChoreo = onMore,
                        compact = m.compact,
                        detailed = m.expanded,
                        height = m.barH,
                        modifier = Modifier.fillMaxWidth().height(m.barH).staggerIn(0),
                        showGear = gearInTopBar,
                        stickRight = if (single) stickRight else null,
                        onSwapStick = onSwapStick,
                    )
                    Spacer(Modifier.height(m.barGap))

                    // 肩鍵列：左 = 啟動平衡 / 停止、右 = 緊急停止（安全控制不做進場動畫：一出現就看得到、按得到）
                    Row(Modifier.fillMaxWidth().height(m.shoulderH), verticalAlignment = Alignment.CenterVertically) {
                        MotorToggle(
                            running = motorsOn,
                            enabled = canCommand && (motorsOn || !startBlocked),
                            pending = motorPending,
                            onToggle = onMotorToggle,
                            modifier = Modifier.width(m.shoulderW).fillMaxHeight(),
                            onIgnored = { haptics.reject() },
                            attention = block == ControlBlock.MOTORS_OFF,
                        )
                        Box(
                            Modifier.weight(1f).fillMaxHeight().padding(horizontal = ShoulderInnerGap).staggerIn(1),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 「360° 搖桿」最長：每段 ≥ 100 dp 才加圖示（字級 1.3 倍也不會被切）
                            val showIcons = m.shoulderCenterWidth / 3 >= 100.dp
                            SegmentedSelector(
                                options = JoystickMode.entries,
                                selected = mode,
                                onSelect = onMode,
                                modifier = Modifier.widthIn(max = 380.dp).height(if (m.compact) 40.dp else 44.dp),
                                label = { it.labelZh },
                                icon = if (showIcons) { md -> modeIcon(md) } else null,
                            )
                        }
                        EmergencyStopButton(
                            onStop = onEStop,
                            modifier = Modifier.width(m.shoulderW).fillMaxHeight(),
                            style = EStopStyle.BAR,
                            haptic = false,
                        )
                    }
                    Spacer(Modifier.height(m.lowerGap))

                    // 下半部：左手區 · 中控台 · 右手區
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        AnimatedContent(
                            targetState = leftKind,
                            modifier = Modifier.width(leftW).fillMaxHeight(),
                            transitionSpec = { zoneTransition() },
                            contentAlignment = Alignment.BottomStart,
                            label = "leftZone",
                        ) { k ->
                            // 退場中的舊內容立刻停用（不會在淡出時還送指令）
                            zone(k, inputEnabled && k == leftKind, false)
                        }
                        Spacer(Modifier.width(m.colGap))
                        CenterConsole(
                            m = m,
                            mode = mode,
                            telem = telem,
                            slow = slow,
                            motorsOn = motorsOn,
                            maxSpeed = maxSpeed,
                            fallAngle = fallAngle,
                            zeroAngle = zeroAngle,
                            tilt = if (mode == JoystickMode.TILT) tilt else null,
                            linkLostSince = lostSince,
                            notice = notice,
                            loadedSize = loaded.value.size,
                            playingQuick = playingQuick,
                            quickProgress = quickProgress,
                            quickBusy = quickBusy,
                            quickDim = !motorsOn || !canCommand,
                            stopBusy = stopBusy,
                            controller = controller,
                            caps = caps,
                            onQuick = onQuick,
                            onMore = onMore,
                            onStopSequence = onStopSequence,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        Spacer(Modifier.width(m.colGap))
                        AnimatedContent(
                            targetState = rightKind,
                            modifier = Modifier.width(rightW).fillMaxHeight(),
                            transitionSpec = { zoneTransition() },
                            contentAlignment = Alignment.BottomEnd,
                            label = "rightZone",
                        ) { k ->
                            zone(k, inputEnabled && k == rightKind, true)
                        }
                    }
                }
            }
            // 極小的分割畫面（整頁可捲動）：肩鍵列的緊急停止可能在畫面外（寬 < 520 dp 時一開始就在右邊看不到的地方）
            // 或被捲走 → 固定一顆在視窗上（不跟著捲動）；沒捲動 / 捲到底時剛好疊在原本的位置
            if (tooShort || tooNarrow) {
                EmergencyStopButton(
                    onStop = onEStop,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = m.top + m.barH + m.barGap, end = m.edge)
                        .size(m.shoulderW, m.shoulderH),
                    style = EStopStyle.BAR,
                    haptic = false,
                )
            }
        }

        // 緊急停止：全螢幕紅色暈光一閃（不攔截觸控，只在繪製階段讀動畫值）
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val vignette = Brush.radialGradient(
                    listOf(Color.Transparent, c.danger.copy(alpha = 0.35f), c.danger.copy(alpha = 0.75f)),
                    radius = size.maxDimension * 0.75f,
                )
                onDrawBehind {
                    val a = flash.value
                    if (a > 0.001f) drawRect(vignette, alpha = a)
                }
            },
        )
    }
}

/** 緊急停止後搖桿 / 按住駕駛停用多久（與紅色閃光同時，之後要重新按下）。 */
private const val ESTOP_INPUT_LOCK_MS = 350L

/** 體感模式的「按住駕駛」區（平板上不要高到拇指按不到中間）。 */
@Composable
private fun PadZone(
    side: PadSide,
    available: Boolean,
    enabled: Boolean,
    tilt: TiltSession,
    controller: DriveController,
    block: ControlBlock,
    m: RcMetrics,
    modifier: Modifier,
) {
    val haptics = rememberHaptics()
    Box(modifier, contentAlignment = ZoneAlignCenter) {
        DeadManPad(
            side = side,
            available = available,
            enabled = enabled,
            session = tilt,
            controller = controller,
            haptics = haptics,
            block = block,
            compact = m.compact,
            modifier = Modifier.heightIn(max = m.stickMax + 60.dp).fillMaxWidth().fillMaxHeight(),
        )
    }
}

/** 中控台下方那一格要顯示什麼。 */
private sealed interface Slot {
    data class Lost(val sinceMs: Long) : Slot
    data class Notice(val notice: DriveNotice) : Slot
    data class Mode(val mode: JoystickMode) : Slot
}

/**
 * 中控台：（平板）大車速 ＋ 儀表（體感水平儀 / 姿態儀 / 車速弧 + 輪速）＋ 下方一格：
 * 訊號中斷 > 情境提示（緊急停止、被拿起、傾倒、低電量、感測器、失聯保護、編排播放中）> 快捷動作列（雙搖桿 / 體感）或指令讀數（單搖桿）。
 */
@Composable
private fun CenterConsole(
    m: RcMetrics,
    mode: JoystickMode,
    telem: State<Telemetry?>,
    slow: State<Telemetry?>,
    motorsOn: Boolean,
    maxSpeed: Float,
    fallAngle: Float,
    zeroAngle: Float,
    tilt: TiltSession?,
    linkLostSince: Long?,
    notice: DriveNotice,
    loadedSize: Int,
    playingQuick: QuickAction?,
    quickProgress: State<Float>,
    quickBusy: String?,
    quickDim: Boolean,
    stopBusy: Boolean,
    controller: DriveController,
    caps: AxisCaps,
    onQuick: (QuickAction) -> Unit,
    onMore: () -> Unit,
    onStopSequence: () -> Unit,
    modifier: Modifier,
) {
    val linkLost = linkLostSince != null
    BoxWithConstraints(modifier) {
        val gap = if (m.compact) 6.dp else 8.dp
        val bigSpeed = m.expanded && maxHeight >= 440.dp
        val showMore = maxWidth >= 340.dp
        // 平板：大車速 + 儀表 + 下方一格聚在一起置中；手機：儀表撐滿、下方一格貼底（與搖桿底部對齊）
        Column(Modifier.fillMaxSize(), verticalArrangement = if (m.expanded) Arrangement.Center else Arrangement.Top) {
            if (bigSpeed) {
                BigSpeedReadout(slow, Modifier.fillMaxWidth().staggerIn(2).dimmed(linkLost))
                Spacer(Modifier.height(12.dp))
            }
            DriveInstruments(
                telem, slow, motorsOn, maxSpeed, fallAngle, zeroAngle, tilt,
                modifier = Modifier.weight(1f, fill = !m.expanded).fillMaxWidth().staggerIn(2).dimmed(linkLost),
            )
            Spacer(Modifier.height(if (m.expanded) 16.dp else gap))
            val slot: Slot = when {
                linkLostSince != null -> Slot.Lost(linkLostSince)
                notice != DriveNotice.NONE -> Slot.Notice(notice)
                else -> Slot.Mode(mode)
            }
            AnimatedContent(
                targetState = slot,
                modifier = Modifier.fillMaxWidth().height(m.slotH).staggerIn(3),
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 40)) + slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { it / 2 })
                        .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 2 })
                },
                contentAlignment = Alignment.Center,
                label = "slot",
            ) { s ->
                when (s) {
                    is Slot.Lost -> LinkLostBadge(s.sinceMs, Modifier.fillMaxSize())
                    is Slot.Notice -> NoticeBanner(
                        notice = s.notice,
                        slow = slow,
                        seqTotal = loadedSize,
                        quick = playingQuick,
                        progress = quickProgress,
                        stopping = stopBusy,
                        onStopSequence = onStopSequence,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is Slot.Mode -> if (s.mode == JoystickMode.SINGLE) {
                        // 360°：快捷動作在按鍵區，這裡顯示跟搖桿同步的向量（方向 + 推桿量）與送出的速度 / 轉向
                        VectorStrip(controller, caps, Modifier.fillMaxSize())
                    } else {
                        QuickActionRow(
                            busyId = quickBusy,
                            playingId = playingQuick?.id,
                            dim = quickDim,
                            onPlay = onQuick,
                            onMore = onMore,
                            modifier = Modifier.fillMaxSize(),
                            showMore = showMore,
                        )
                    }
                }
            }
        }
    }
}

private fun zoneTransition() =
    (fadeIn(tween(220, delayMillis = 60)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 380f), initialScale = 0.92f))
        .togetherWith(fadeOut(tween(150)) + scaleOut(tween(200), targetScale = 1.04f))

private fun modeIcon(mode: JoystickMode): ImageVector = when (mode) {
    JoystickMode.SINGLE -> Icons.Rounded.ControlCamera
    JoystickMode.DUAL -> Icons.Rounded.SportsEsports
    JoystickMode.TILT -> Icons.Rounded.ScreenRotation
}

/** 進場：依序淡入 + 上浮（彈簧）。只動 graphicsLayer，不觸發重組。安全控制（肩鍵、搖桿）不使用。 */
@Composable
private fun Modifier.staggerIn(index: Int): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(40L + index * 55L)
        p.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 240f))
    }
    return this.graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 18.dp.toPx()
    }
}

/** 訊號中斷時讓資料變暗（顯示「這是舊資料」）。 */
@Composable
private fun Modifier.dimmed(dim: Boolean): Modifier {
    val a = animateFloatAsState(if (dim) 0.4f else 1f, tween(320), label = "dim")
    return this.graphicsLayer { alpha = a.value }
}
