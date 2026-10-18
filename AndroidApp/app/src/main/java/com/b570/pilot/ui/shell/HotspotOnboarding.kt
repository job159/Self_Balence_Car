package com.b570.pilot.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.data.SavedCar
import com.b570.pilot.data.Severity
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.LinkMode
import com.b570.pilot.ui.AppNavigator
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.MainTab
import com.b570.pilot.ui.Route
import com.b570.pilot.ui.components.ConfirmDialog
import com.b570.pilot.ui.components.openPhoneHotspotSettings
import com.b570.pilot.ui.screens.settings.HotspotEditDialog
import kotlinx.coroutines.delay

/** 條件成立後等這麼久才邀請（避開連線頁 → 主畫面的轉場，也讓剛連上的提示先跳完）。 */
internal const val HOTSPOT_ONBOARDING_DELAY_MS = 1_200L

/** 按了「先不用」（或開始設定後沒有套用就關掉）：之後去哪裡改。 */
internal const val HOTSPOT_ONBOARDING_LATER_ZH = "之後可以在 設定 › 連線方式 改用熱點模式"

/**
 * 第一次直連成功後，要不要邀請改用熱點模式（純函式，`HotspotOnboardingTest`）。全部成立才邀請：
 * @param state 已連線（HELLO 完成）
 * @param demo 不是展示模式
 * @param linkMode 這段連線是直連（已經走手機熱點就不用問）
 * @param hotspotSupported 車上的 ESP-01 支援熱點模式（STA_GET 之後才知道：null = 還不知道 → 先等；false = 韌體太舊 → 不問）
 * @param car 記住的這台車（還沒記住 = null → 不問）；已經是熱點模式、或設定過熱點（hotspotSsid 有值：例如從設定頁
 *   改用過熱點模式、之後又「改回直連」）就不問
 * @param carReportsHotspot 車子回報已設定熱點模式（STA_INFO.enabled；找不到熱點才改回直連的車）
 * @param answered 已回答過的車（SSID）
 * @param motorsOn 馬達運轉中（正在開車不要打擾）
 * @param switching 正在切換熱點模式（vm.hotspotSwitch != null）
 * @param overlayOpen 覆蓋層（QR 掃描）或 shell 的其他對話框開著
 * @param onMain 在主畫面
 */
internal fun hotspotOnboardingDue(
    state: ConnectionState,
    demo: Boolean,
    linkMode: LinkMode?,
    hotspotSupported: Boolean?,
    car: SavedCar?,
    carReportsHotspot: Boolean,
    answered: Set<String>,
    motorsOn: Boolean,
    switching: Boolean,
    overlayOpen: Boolean,
    onMain: Boolean,
): Boolean =
    state is ConnectionState.Connected &&
        !demo &&
        linkMode == LinkMode.DIRECT &&
        hotspotSupported == true &&
        car != null &&
        !car.hotspotMode &&
        car.hotspotSsid.isBlank() &&
        !carReportsHotspot &&
        car.ssid !in answered &&
        !motorsOn &&
        !switching &&
        !overlayOpen &&
        onMain

/** 表單預先填入的手機熱點名稱：記住的車裡最近連線、有填熱點名稱的那台（都沒有 = 空字串）。 */
internal fun onboardingHotspotSsidOf(cars: List<SavedCar>): String =
    cars.sortedByDescending { it.lastConnected }.firstOrNull { it.hotspotSsid.isNotBlank() }?.hotspotSsid.orEmpty()

/**
 * 「帶出去玩：改用熱點模式」：第一次直連成功（[hotspotOnboardingDue]）後等 [HOTSPOT_ONBOARDING_DELAY_MS] 問一次，
 * 每台車只問一次（記在 vm.hotspotPromptAnswered）。
 * - 「設定熱點模式」→ 打開與設定頁相同的 [HotspotEditDialog]（表單 → 確認 → 進度），**這時還不記錄已回答**：
 *   - 套用成功（STA_SET 回 OK）→ repository 記下已回答（`CarRepository.setHotspot`）。車子重新開機時連線狀態是
 *     Discovering，shell 留在主畫面（rootRouteOf = STAY），這個對話框繼續顯示進度（請開啟手機熱點 → 尋找 → 連上）；
 *     從這裡開始的進度不能「在背景繼續」也不能點外面關掉（canBackground = false：駕駛分頁沒有別的地方顯示進度），
 *     只能「取消」或等完成 / 失敗。連上後顯示「完成！以後帶出去…」；沒找到 → 連線狀態 Failed，shell 切到連線頁，
 *     由連線頁的 HotspotSwitchPanel 顯示（重試 / 關閉）。
 *   - 沒套用就關掉（取消 / 返回）→ 記下已回答，提示之後去 設定 › 連線方式。
 *   - 表單開著時這裡被移出組合（直連中斷切到連線頁、Activity 重建、行程被系統收掉）→ 不記錄，下次直連會再問
 *     （開著的表單用 rememberSaveable 記住，Activity 重建後直接再打開）。
 * - 「先不用」（或返回 / 點外面）→ 記下已回答，提示之後去 設定 › 連線方式。
 * - 還沒回答前條件不再成立（馬達啟動、斷線、打開掃描…）→ 先收起來，不記錄；之後條件再成立會再問。
 * 從設定頁切換過熱點模式（開或關）、或車子回報熱點模式的車，repository 也會記成已回答（不會在「改回直連」後又被邀請）。
 *
 * shell 只在主畫面時組合這個元件（[B570Root]）；[otherDialogOpen] = shell 自己的對話框（中斷連線確認）開著。
 */
@Composable
internal fun HotspotOnboardingHost(vm: AppViewModel, nav: AppNavigator, otherDialogOpen: Boolean) {
    val context = LocalContext.current
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    val mode by vm.linkMode.collectAsStateWithLifecycle()
    val supported by vm.hotspotSupported.collectAsStateWithLifecycle()
    val sta by vm.staInfo.collectAsStateWithLifecycle()
    val current by vm.currentCar.collectAsStateWithLifecycle()
    val cars by vm.savedCars.collectAsStateWithLifecycle()
    val answered by vm.hotspotPromptAnswered.collectAsStateWithLifecycle()
    val switch by vm.hotspotSwitch.collectAsStateWithLifecycle()
    // 只取「馬達運轉中」一個布林值（不跟著遙測重組）；還沒有遙測 = 沒在運轉
    val telem = vm.telemetrySlow.collectAsStateWithLifecycle()
    val motorsOn by remember { derivedStateOf { telem.value?.motorsOn == true } }

    val saved = current?.let { cur -> cars.firstOrNull { it.ssid == cur.ssid } }
    val due = hotspotOnboardingDue(
        state = state,
        demo = demo,
        linkMode = mode,
        hotspotSupported = supported,
        car = saved,
        carReportsHotspot = sta?.enabled == true,
        answered = answered,
        motorsOn = motorsOn,
        switching = switch != null,
        overlayOpen = nav.overlay != null || otherDialogOpen,
        onMain = nav.base is Route.Main,
    )

    var prompt by remember { mutableStateOf(false) }
    // 正在設定的車（從邀請打開的熱點表單；null = 沒開）。Activity 重建（深色模式、字級…）後表單再打開
    var editingSsid by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(due) {
        prompt = false
        if (due) {
            delay(HOTSPOT_ONBOARDING_DELAY_MS)
            prompt = true
        }
    }

    val ssid = saved?.ssid
    if (prompt && due && ssid != null && editingSsid == null) {
        ConfirmDialog(
            title = "帶出去玩：改用熱點模式",
            message = "以後只要打開手機的個人熱點，車子會自己連上你的手機。不需要任何 Wi-Fi 網路，手機 Wi-Fi 可以關著，照常用行動網路上網。只要設定這一次。",
            confirmText = "設定熱點模式",
            dismissText = "先不用",
            icon = Icons.Rounded.WifiTethering,
            onConfirm = {
                // 還不記錄已回答：套用成功時 repository 會記；沒套用就關掉時下面的 onDismiss 記
                prompt = false
                editingSsid = ssid
            },
            onDismiss = {
                vm.markHotspotPromptAnswered(ssid)
                prompt = false
                vm.notice(HOTSPOT_ONBOARDING_LATER_ZH, Severity.INFO)
            },
        )
    }

    editingSsid?.let { target ->
        HotspotEditDialog(
            vm = vm,
            initialSsid = remember(target) { onboardingHotspotSsidOf(cars) },
            initialPassword = "",
            alreadyHotspot = false,
            demo = false,
            motorsOn = motorsOn,
            // 駕駛分頁沒有別的地方顯示切換進度：進度中不能「在背景繼續」或點外面關掉
            canBackground = false,
            onOpenHotspot = {
                if (!openPhoneHotspotSettings(context)) vm.notice("請到手機設定開啟「個人熱點」", Severity.WARNING)
            },
            onDismiss = {
                vm.markHotspotPromptAnswered(target)
                prompt = false
                editingSsid = null
                val switchNow = vm.hotspotSwitch.value
                if (switchNow?.isActive == true) {
                    // 理論上不會發生（進度中不能關）：帶到看得到進度的地方
                    nav.selectTab(MainTab.SETTINGS)
                    vm.notice("切換進度在 設定 › 連線方式", Severity.INFO)
                    return@HotspotEditDialog
                }
                // 沒有套用就關掉（取消 / 返回）：說一下之後去哪裡改。套用成功時記住的車已改成熱點模式
                val applied = vm.savedCars.value.firstOrNull { it.ssid == target }?.hotspotMode == true
                if (!applied && switchNow == null) vm.notice(HOTSPOT_ONBOARDING_LATER_ZH, Severity.INFO)
            },
        )
    }
}
