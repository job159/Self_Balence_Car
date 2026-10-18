package com.b570.pilot.ui.screens.tune

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.protocol.ParamGroup
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.LocalScreenPadding
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「整定」分頁（橫向）：依分組調整 17 個參數，即時套用或暫存後一次套用，儲存到車上、恢復預設、零點校正、設定檔。
 *
 * 版面（[tuneLayoutOf] 依實際可用區域決定）：
 * - **左欄（約 38 %）**＝控制台：上緣固定「示波器」卡（馬達 / 未儲存 / 同步中 + 最近 5 秒曲線），
 *   下面可捲動：即時套用撥桿、儲存到車上、讀取 / 零點校正 / 恢復預設圓鍵、設定檔。
 * - **右欄**＝參數群組（可捲動、可收合），上緣群組快速跳轉列；寬螢幕每張卡片內兩欄。
 *   底部浮動：唯讀提示（未連線 / 訊號中斷）+「套用 N 項變更」（暫存模式）。
 * - 太窄（分割畫面）時退回單欄捲動。
 * 未連線（或訊號中斷）時唯讀顯示最後讀到的值。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun TuneScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier) {
    val ctl: TuneController = viewModel(key = "b570.tune") { TuneController(vm) }
    val conn by vm.connectionState.collectAsStateWithLifecycle()
    val isDemo by vm.isDemo.collectAsStateWithLifecycle()
    val slow = vm.telemetrySlow.collectAsStateWithLifecycle()
    val motorsOn by remember { derivedStateOf { slow.value?.motorsOn == true } }
    val dirty by remember { derivedStateOf { slow.value?.paramsDirty == true } }
    val editable = conn is ConnectionState.Connected

    // 觸覺回饋（ViewModel 發出、畫面執行；自動遵守設定）
    val haptics by rememberUpdatedState(rememberHaptics())
    LaunchedEffect(ctl) {
        ctl.feedback.collect { f ->
            when (f) {
                TuneFeedback.TICK -> haptics.tick()
                TuneFeedback.CONFIRM -> haptics.confirm()
                TuneFeedback.REJECT -> haptics.reject()
            }
        }
    }
    DisposableEffect(ctl) { onDispose { ctl.onScreenDisposed() } }

    val pad = LocalScreenPadding.current
    val ld = LocalLayoutDirection.current
    val stagger = remember { Stagger(HashSet(), SystemClock.uptimeMillis()) }

    BoxWithConstraints(modifier) {
        val ins = TuneInsets(
            start = pad.calculateStartPadding(ld) + EDGE,
            end = pad.calculateEndPadding(ld) + EDGE,
            top = pad.calculateTopPadding(),
            bottom = pad.calculateBottomPadding(),
        )
        val usableW = (maxWidth - ins.start - ins.end).value
        val usableH = (maxHeight - ins.top - ins.bottom).value
        val layout = remember(usableW, usableH) { tuneLayoutOf(usableW, usableH) }
        val env = TuneEnv(vm, ctl, conn, editable, isDemo, motorsOn, dirty, layout, ins, stagger)
        if (layout.twoPane) TwoPaneTune(env) else SinglePaneTune(env)
    }

    TuneDialogs(vm, ctl, editable, motorsOn)
}

/** 導覽軌 / 安全區之外再留的左右邊距。 */
private val EDGE = 12.dp

private class TuneInsets(val start: Dp, val end: Dp, val top: Dp, val bottom: Dp)

/** 進場動畫的記錄（同一次造訪已出現過的不再播放）。 */
private class Stagger(val seen: MutableSet<Any>, val enteredAt: Long)

/** 畫面共用的狀態（只在組合期間傳遞，不保存）。 */
private class TuneEnv(
    val vm: AppViewModel,
    val ctl: TuneController,
    val conn: ConnectionState,
    val editable: Boolean,
    val isDemo: Boolean,
    val motorsOn: Boolean,
    val dirty: Boolean,
    val layout: TuneLayout,
    val ins: TuneInsets,
    val stagger: Stagger,
)

// ====================================================================== 左右分欄

@Composable
private fun TwoPaneTune(env: TuneEnv) {
    val ins = env.ins
    val layout = env.layout
    val ctl = env.ctl
    Row(Modifier.fillMaxSize()) {
        // 左欄：示波器（固定）+ 控制台（可捲動）
        Column(
            Modifier
                .padding(start = ins.start)
                .width(layout.sideWidth.dp)
                .fillMaxHeight(),
        ) {
            Spacer(Modifier.height(ins.top + 4.dp))
            TuneScopeCard(
                vm = env.vm,
                ctl = ctl,
                conn = env.conn,
                motorsOn = env.motorsOn,
                dirty = env.dirty,
                chartHeight = layout.chartHeight.dp,
                modifier = Modifier.enterStagger("scope", 0, env.stagger),
            )
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = ins.bottom),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TuneActionsCard(
                    ctl, env.editable, env.isDemo, env.motorsOn,
                    Modifier.enterStagger("actions", 1, env.stagger),
                    narrow = layout.narrowSide,
                )
                TuneProfilesCard(
                    ctl, env.editable,
                    Modifier.enterStagger("profiles", 2, env.stagger),
                    narrow = layout.narrowSide,
                )
            }
        }
        Spacer(Modifier.width(TUNE_PANE_GAP.dp))
        // 右欄：參數群組
        ParamPane(
            env = env,
            startPad = 0.dp,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            leadingCount = 0,
            leading = {},
            trailing = {},
        )
    }
}

// ====================================================================== 單欄（分割畫面等太窄時）

@Composable
private fun SinglePaneTune(env: TuneEnv) {
    val ctl = env.ctl
    ParamPane(
        env = env,
        startPad = env.ins.start,
        modifier = Modifier.fillMaxSize(),
        leadingCount = 2,
        leading = {
            item(key = "scope", contentType = "scope") {
                TuneScopeCard(
                    env.vm, ctl, env.conn, env.motorsOn, env.dirty, env.layout.chartHeight.dp,
                    Modifier.enterStagger("scope", 0, env.stagger),
                )
            }
            item(key = "actions", contentType = "actions") {
                TuneActionsCard(
                    ctl, env.editable, env.isDemo, env.motorsOn,
                    Modifier.enterStagger("actions", 1, env.stagger),
                    narrow = env.layout.narrowSide,
                )
            }
        },
        trailing = {
            item(key = "profiles", contentType = "profiles") {
                TuneProfilesCard(
                    ctl, env.editable,
                    Modifier.enterStagger("profiles", TUNE_GROUPS.size + 2, env.stagger),
                    narrow = env.layout.narrowSide,
                )
            }
        },
    )
}

// ====================================================================== 參數區

/**
 * 參數群組清單 + 群組跳轉列 + 底部浮動列。
 * @param leadingCount [leading] 放了幾個清單項目（計算群組的捲動位置）
 */
@Composable
private fun ParamPane(
    env: TuneEnv,
    startPad: Dp,
    modifier: Modifier,
    leadingCount: Int,
    leading: LazyListScope.() -> Unit,
    trailing: LazyListScope.() -> Unit,
) {
    val ctl = env.ctl
    val ins = env.ins
    val layout = env.layout
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val sticky = layout.stickyGroupNav
    var navPx by remember { mutableIntStateOf(0) }
    var dockPx by remember { mutableIntStateOf(0) }
    val navDp = with(density) { navPx.toDp() }
    val dockDp = with(density) { dockPx.toDp() }

    // 目前捲到的群組：上緣下方第一個露出 32 dp 以上的群組卡片；點跳轉列後固定顯示，直到使用者自己拖動清單
    val groupByKey = remember { TUNE_GROUPS.associateBy { it.name } }
    val thresholdPx = with(density) { 32.dp.toPx() }
    val scrolledGroup by remember(listState, thresholdPx) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
                val g = (info.key as? String)?.let(groupByKey::get)
                if (g != null && info.offset + info.size > thresholdPx) g else null
            }
        }
    }
    var pinned by remember { mutableStateOf<ParamGroup?>(null) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) pinned = null }
    val active = pinned ?: scrolledGroup ?: TUNE_GROUPS.first()
    val firstGroupIndex = leadingCount + if (sticky) 0 else 1
    val jump: (ParamGroup) -> Unit = { g ->
        haptics.tick()
        pinned = g
        ctl.expandGroup(g)
        scope.launch { listState.animateScrollToItem(firstGroupIndex + TUNE_GROUPS.indexOf(g)) }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = startPad,
                end = ins.end,
                top = ins.top + if (sticky) navDp + 10.dp else 4.dp,
                bottom = ins.bottom + dockDp + if (dockPx > 0) 10.dp else 0.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading()
            if (!sticky) {
                item(key = "groupNav", contentType = "groupNav") {
                    GroupJumpBar(ctl, active, jump, Modifier.fillMaxWidth())
                }
            }
            itemsIndexed(TUNE_GROUPS, key = { _, g -> g.name }, contentType = { _, _ -> "group" }) { i, g ->
                ParamGroupCard(
                    group = g,
                    items = ctl.byGroup.getValue(g),
                    ctl = ctl,
                    expanded = ctl.expanded[g] == true,
                    editable = env.editable,
                    motorsOn = env.motorsOn,
                    modifier = Modifier.enterStagger(g, i + 1, env.stagger),
                    columns = layout.paramColumns,
                    compactRows = layout.compactRows,
                )
            }
            trailing()
            item(key = "footer", contentType = "footer") { FooterNote() }
        }

        if (sticky) {
            GroupJumpBar(
                ctl, active, jump,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = startPad, end = ins.end, top = ins.top + 4.dp)
                    .fillMaxWidth()
                    .onSizeChanged { navPx = it.height },
            )
        }

        BottomDock(
            ctl = ctl,
            conn = env.conn,
            editable = env.editable,
            compact = layout.compactDock,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = startPad + 8.dp, end = ins.end + 8.dp, bottom = (ins.bottom - 4.dp).coerceAtLeast(8.dp))
                .onSizeChanged { dockPx = it.height },
        )
    }
}

/**
 * 進場動畫：首次出現時由下往上淡入，依序錯開。
 * 進入畫面 0.5 s 後才捲進來的項目不延遲；同一次造訪已出現過的不再播放。
 */
@Composable
private fun Modifier.enterStagger(key: Any, index: Int, stagger: Stagger): Modifier {
    val anim = remember { Animatable(if (key in stagger.seen) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (anim.value < 1f) {
            stagger.seen += key
            val late = SystemClock.uptimeMillis() - stagger.enteredAt > 500
            if (!late) delay(45L * index.coerceAtMost(6))
            anim.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 260f))
        }
    }
    return this.graphicsLayer {
        val p = anim.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 28.dp.toPx()
    }
}

/** 底部浮動區：唯讀提示（未連線 / 訊號中斷）+「套用 N 項變更」。 */
@Composable
private fun BottomDock(ctl: TuneController, conn: ConnectionState, editable: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    val count = ctl.stagedCount
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(
            visible = !editable,
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(160)),
        ) {
            OfflinePill(conn, compact)
        }
        AnimatedVisibility(
            visible = count > 0,
            enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = 420f)) { it } + fadeIn() + scaleIn(initialScale = 0.9f),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(160)),
        ) {
            StagedBar(ctl, count, editable, compact)
        }
    }
}

@Composable
private fun StagedBar(ctl: TuneController, count: Int, editable: Boolean, compact: Boolean) {
    val c = B570Theme.colors
    // 收起動畫期間 count 會變 0，保留最後的數字
    val last = remember { IntArray(1) }
    if (count > 0) last[0] = count
    Row(
        Modifier
            .glass(RoundedCornerShape(B570Theme.radii.xl), fill = c.surface.copy(alpha = 0.96f), glow = c.warn.copy(alpha = 0.55f), glowElevation = 20.dp)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonButton(
            "捨棄",
            onClick = { ctl.dialog = TuneDialog.ConfirmDiscard },
            style = NeonButtonStyle.GHOST,
            compact = true,
            color = c.textSecondary,
            enabled = ctl.busyOp == null,
        )
        Spacer(Modifier.width(6.dp))
        NeonButton(
            "套用 ${last[0]} 項變更",
            onClick = ctl::applyStaged,
            icon = Icons.Rounded.Upload,
            enabled = editable && ctl.busyOp == null,
            loading = ctl.busyOp == TuneOp.APPLY,
            compact = compact,
        )
    }
}

/** 唯讀提示：未連線 / 訊號中斷時顯示最後讀到的值。 */
@Composable
private fun OfflinePill(conn: ConnectionState, compact: Boolean) {
    val c = B570Theme.colors
    val lost = conn is ConnectionState.LinkLost
    val tint = if (lost) c.warn else c.danger
    Row(
        Modifier
            .widthIn(max = 420.dp)
            .glass(RoundedCornerShape(50), fill = c.surface.copy(alpha = 0.96f), glow = tint.copy(alpha = 0.45f), glowElevation = 16.dp)
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.LinkOff, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                if (lost) "訊號中斷 · 唯讀" else "未連線 · 唯讀",
                style = B570Theme.type.titleS,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (lost) "顯示最後讀到的數值，恢復連線後即可調整" else "顯示最後讀到的數值，僅供檢視",
                style = B570Theme.type.caption,
                color = c.textSecondary,
            )
        }
    }
}

@Composable
private fun FooterNote() {
    val c = B570Theme.colors
    Text(
        "調整會立刻作用在車上，按「儲存到車上」寫入 Flash 後，重開機才會保留。\n參數沿用原廠直立環、速度環、轉向環的數值尺度。",
        style = B570Theme.type.caption,
        color = c.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
