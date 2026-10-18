package com.b570.pilot.ui.screens.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.WifiTetheringError
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.BuildConfig
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.GlassDialog
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.SectionHeader
import com.b570.pilot.ui.components.StatusPill
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay

/** 本 App 實作的通訊協定版本（docs/PROTOCOL.md v1）。 */
private const val APP_PROTO_VER = 1

/**
 * 「關於」：App / 主控板 / Wi-Fi 模組版本、使用說明、致謝。
 * 寬版（平板）：左 = App 與版本資訊，右 = 使用說明 + 致謝；窄版依直向版順序堆疊。
 */
@Composable
internal fun AboutSection(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val info by vm.carInfo.collectAsStateWithLifecycle()
    val esp by vm.espInfo.collectAsStateWithLifecycle()
    val demo by vm.isDemo.collectAsStateWithLifecycle()
    var help by rememberSaveable { mutableStateOf(false) }

    val sim = if (demo) "（模擬）" else ""
    val proto = info?.protoVer ?: esp.hello?.protoVer
    val protoMismatch = proto != null && proto != APP_PROTO_VER

    Column(modifier) {
        PaneHeader(SettingsCategory.ABOUT)
        GlassCard(Modifier.fillMaxWidth()) {
            AdaptiveColumns(
                wide = wide,
                firstWeight = 1.15f,
                first = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppMark()
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("B570 Pilot", style = B570Theme.type.titleM, color = c.textPrimary, maxLines = 1)
                            Text("版本 ${BuildConfig.VERSION_NAME}", style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1)
                        }
                        if (BuildConfig.DEBUG) StatusPill("測試版", c.warn)
                    }
                    Spacer(Modifier.height(10.dp))
                    SettingDivider()
                    InfoRow(
                        "主控板韌體",
                        info?.let { "v${it.fwVersion}$sim" } ?: "--",
                        icon = Icons.Rounded.Memory,
                    )
                    InfoRow(
                        "車型",
                        info?.model?.takeIf { it.isNotBlank() } ?: "--",
                        icon = Icons.Rounded.SmartToy,
                    )
                    InfoRow(
                        "通訊協定",
                        proto?.let { if (protoMismatch) "v$it（App 為 v$APP_PROTO_VER）" else "v$it" } ?: "--",
                        icon = Icons.Rounded.SwapHoriz,
                        valueColor = if (protoMismatch) c.warn else c.textPrimary,
                    )
                    InfoRow(
                        "Wi-Fi 模組韌體",
                        esp.hello?.let { "v${it.espVersion}$sim" } ?: "--",
                        icon = Icons.Rounded.Router,
                    )
                    InfoRow(
                        "姿態演算法",
                        info?.angleAlgo?.labelZh ?: "--",
                        icon = Icons.Rounded.Sensors,
                    )
                    if (protoMismatch) {
                        Spacer(Modifier.height(4.dp))
                        HintRow(Icons.Rounded.Tag, "版本不一致，部分功能可能無法使用，建議更新韌體")
                    }
                },
                second = {
                    Spacer(Modifier.height(12.dp))
                    if (wide) {
                        FieldLabel("第一次使用？", icon = Icons.AutoMirrored.Rounded.MenuBook)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "五個步驟就能開始駕駛；遙控時手機照常上網，熱點模式延遲最穩。",
                            style = B570Theme.type.bodySmall,
                            color = c.textSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    NeonButton(
                        "使用說明",
                        { help = true },
                        Modifier.fillMaxWidth(),
                        style = NeonButtonStyle.SECONDARY,
                        icon = Icons.AutoMirrored.Rounded.MenuBook,
                    )
                    Spacer(Modifier.height(14.dp))
                    Credits()
                },
            )
        }
    }

    if (help) HelpDialog(onDismiss = { help = false })
}

/** App 標誌：漸層圓角方塊 + 「B5」。 */
@Composable
private fun AppMark() {
    val c = B570Theme.colors
    Box(
        Modifier
            .size(52.dp)
            .glass(RoundedCornerShape(16.dp), fill = Color.Transparent, glow = c.accent, glowElevation = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawWithCache {
                val brush = Brush.linearGradient(listOf(c.accent, c.accent2))
                onDrawBehind { drawRect(brush) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("B5", style = B570Theme.type.titleM.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp), color = c.onAccent)
    }
}

@Composable
private fun Credits() {
    val c = B570Theme.colors
    Column(Modifier.fillMaxWidth()) {
        Text("致謝", style = B570Theme.type.label, color = c.textMuted)
        Spacer(Modifier.height(4.dp))
        Text(
            "平衡控制沿用 WHEELTEC 原廠串級架構（直立環、速度環、轉向環），感謝原廠扎實的控制設計。",
            style = B570Theme.type.caption,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "介面使用 Jetpack Compose，QR 掃描使用 CameraX 與 ML Kit。",
            style = B570Theme.type.caption,
            color = c.textSecondary,
        )
    }
}

// ------------------------------------------------------------------ 使用說明

private data class HelpStep(val title: String, val desc: String)

private val QUICK_START = listOf(
    HelpStep("開機", "打開車子電源，等幾秒，OLED 會顯示 QR Code。"),
    HelpStep("掃描 OLED QR（只要第一次）", "在連線頁按「掃描 QR 配對」對準 OLED；Wi-Fi 開關要打開（不用連網路），系統跳出視窗時按「連線」。行動數據不會中斷。"),
    HelpStep("之後打開 App 會自動連線", "開機後打開 App 就會自動連上；斷線也會自動重連。長按桌面圖示可以直接選車。"),
    HelpStep("啟動平衡", "把車子扶正放在地上，按「啟動平衡」。"),
    HelpStep("橫向握持駕駛", "拇指推 360° 搖桿，往哪推就往哪開；放開搖桿就會停下。"),
)

/** 熱點模式：車子加入手機個人熱點，手機照用行動網路。 */
private val HOTSPOT_STEPS = listOf(
    HelpStep("先直連車子一次", "掃 QR 連上車子後，App 會問要不要改用熱點模式（也可以到「設定 › 連線方式」選「熱點模式」）。"),
    HelpStep("輸入手機熱點名稱與密碼", "熱點請設 2.4\u00A0GHz（iPhone：最大化相容性）、安全性 WPA2。"),
    HelpStep("打開手機熱點", "車子重新開機後會加入熱點，App 會自動在熱點上找到車子。"),
    HelpStep("之後：先開熱點，再開車子", "App 會自動連上，手機 Wi-Fi 可以關著、照常用 4G / 5G。車子先開了也沒關係：OLED 暫時顯示 QR，打開熱點後約 20 秒內就會連上，不用掃。"),
)

private data class HelpTip(val icon: ImageVector, val text: String)

private val SAFETY_TIPS = listOf(
    HelpTip(Icons.Rounded.Stop, "駕駛頁與左側導覽軌都有紅色「緊急停止」，一碰立即停車。"),
    HelpTip(Icons.Rounded.PanTool, "拿起車子會自動停止馬達。"),
    HelpTip(Icons.Rounded.BatteryAlert, "電壓太低會自動停機，請記得充電。"),
    HelpTip(Icons.Rounded.WifiTetheringError, "訊號中斷時，車子會自動煞停並保持平衡。"),
)

private val LATENCY_TIPS = listOf(
    HelpTip(Icons.Rounded.NetworkCheck, "上方「控制 xx ms」是手機送出指令到車子真正執行的時間：綠 < 40、黃 < 100、紅 ≥ 100。"),
    HelpTip(Icons.Rounded.WifiTethering, "熱點模式延遲通常最低；直連時請中斷手機其他 Wi-Fi（同時連兩個 Wi-Fi 會變慢）。"),
    HelpTip(Icons.Rounded.Insights, "「監測 › 延遲診斷」會說慢在哪：Wi-Fi 延遲低、控制延遲卻高 → 請更新 STM32 韌體。"),
    HelpTip(Icons.Rounded.Router, "讓車子在手機附近，避開微波爐、藍牙喇叭等 2.4\u00A0GHz 干擾。"),
)

private val FAQ = listOf(
    "每次都要掃描 QR Code 嗎？" to "不用。配對一次後，打開 App 會自動連線；在「設定 › 記住的車子」可以關掉自動連線。",
    "為什麼掃描後要開 Wi-Fi？" to "車上的 ESP-01 只有 Wi-Fi。第一次配對要打開 Wi-Fi 開關（不用連網路，行動網路照常），連上後改用熱點模式，以後帶出去只開手機熱點，Wi-Fi 可以關著。",
    "可以用行動網路控制嗎？" to "可以：改用熱點模式（設定 › 連線方式），車子連手機熱點，手機照常用 4G / 5G。",
    "帶到戶外怎麼連？" to "熱點模式：先打開手機熱點，再打開車子電源，App 會自動找到車子；不需要任何 Wi-Fi 網路。",
    "可以邊遙控邊看 YouTube 嗎？" to "可以，兩種連線方式手機都照常上網。同一支手機請用分割畫面或 YouTube 小視窗（子母畫面）；整個切到別的 App 時，車子會自動煞停保持平衡。邊看影片邊遙控建議用熱點模式，延遲最穩。",
    "連不上車子？" to "直連：確認車子已開機、手機 Wi-Fi 已開啟，靠近一點再按「重試」。熱點模式：確認手機熱點已開、是 2.4\u00A0GHz。",
    "控制感覺慢半拍？" to "看上方「控制 xx ms」與「監測 › 延遲診斷」的建議；熱點模式通常最快。",
    "車子一直往前或往後跑？" to "到「整定」頁校正機械零點。",
    "想換駕駛方式？" to "在駕駛頁或「設定 › 操控」切換 360° 搖桿、雙搖桿或體感。",
    "忘記密碼了？" to "車上的 OLED 會顯示目前的 QR Code，重新掃描就好。",
)

/**
 * 使用說明（橫向對話框，取代直向版的底部抽屜：矮螢幕放不下抽屜，而且對話框維持沉浸式）。
 * 手機橫放：左 = 快速上手時間軸（交錯進場），右 = 安全提醒 + 常見問題；平板 / 窄視窗單欄。
 * 整個對話框可捲動，右上角 ✕ 與最下面「知道了」都能關閉。
 */
@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val c = B570Theme.colors
    val conf = LocalConfiguration.current
    val twoCol = conf.screenWidthDp >= 600
    GlassDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        maxWidth = if (twoCol) 760.dp else 460.dp,
        glow = c.accent.copy(alpha = 0.4f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("快速上手", style = B570Theme.type.titleL, color = c.textPrimary, maxLines = 1)
                Text("五個步驟，馬上開始駕駛；也能用手機熱點連線", style = B570Theme.type.bodySmall, color = c.textSecondary)
            }
            GlassIconButton(Icons.Rounded.Close, "關閉使用說明", onDismiss, size = 36.dp)
        }
        Spacer(Modifier.height(14.dp))
        if (twoCol) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    QuickStartTimeline()
                    Spacer(Modifier.height(12.dp))
                    HotspotTimeline()
                }
                Column(Modifier.weight(1f)) {
                    SafetyTips()
                    Spacer(Modifier.height(12.dp))
                    LatencyTips()
                    Spacer(Modifier.height(12.dp))
                    FaqList()
                }
            }
        } else {
            QuickStartTimeline()
            Spacer(Modifier.height(10.dp))
            HotspotTimeline()
            Spacer(Modifier.height(10.dp))
            SafetyTips()
            Spacer(Modifier.height(12.dp))
            LatencyTips()
            Spacer(Modifier.height(16.dp))
            FaqList()
        }

        Spacer(Modifier.height(16.dp))
        NeonButton("知道了", onDismiss, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(
            "B570 Pilot · 為 WHEELTEC B570 平衡車打造",
            style = B570Theme.type.caption,
            color = c.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QuickStartTimeline() {
    QUICK_START.forEachIndexed { i, step ->
        TimelineStep(i, step, last = i == QUICK_START.lastIndex)
    }
}

/** 熱點模式四步驟（接在快速上手後面，紫色編號）。 */
@Composable
private fun HotspotTimeline() {
    val c = B570Theme.colors
    SectionHeader("熱點模式（照用行動網路）")
    Spacer(Modifier.height(6.dp))
    HOTSPOT_STEPS.forEachIndexed { i, step ->
        TimelineStep(QUICK_START.size + i, step, last = i == HOTSPOT_STEPS.lastIndex, number = i + 1, tint = c.accent2)
    }
}

/** 延遲小技巧。 */
@Composable
private fun LatencyTips() {
    val c = B570Theme.colors
    SectionHeader("延遲小技巧")
    Spacer(Modifier.height(4.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.m), fill = c.glass)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        LATENCY_TIPS.forEach { tip ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(tip.icon, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(tip.text, style = B570Theme.type.bodySmall, color = c.textPrimary)
            }
        }
    }
}

@Composable
private fun SafetyTips() {
    val c = B570Theme.colors
    SectionHeader("安全提醒")
    Spacer(Modifier.height(4.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(B570Theme.radii.m), fill = c.glass)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        SAFETY_TIPS.forEach { tip ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(tip.icon, null, tint = c.warn, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(tip.text, style = B570Theme.type.bodySmall, color = c.textPrimary)
            }
        }
    }
}

@Composable
private fun FaqList() {
    val c = B570Theme.colors
    SectionHeader("常見問題")
    Spacer(Modifier.height(4.dp))
    FAQ.forEach { (q, a) ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(q, style = B570Theme.type.titleS, color = c.textPrimary)
            Text(a, style = B570Theme.type.bodySmall, color = c.textSecondary)
        }
    }
}

/**
 * 時間軸上的一步：發光編號 + 連接線 + 標題說明；依序彈入。
 * @param index 進場順序（錯開動畫）；[number] 顯示的編號；[tint] 編號顏色
 */
@Composable
private fun TimelineStep(index: Int, step: HelpStep, last: Boolean, number: Int = index + 1, tint: Color = B570Theme.colors.accent) {
    val c = B570Theme.colors
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120L + index * 80L)
        p.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 260f))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .graphicsLayer {
                val v = p.value
                alpha = v.coerceIn(0f, 1f)
                translationX = (1f - v) * 28.dp.toPx()
            },
    ) {
        Column(Modifier.width(36.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(30.dp)
                    .glass(CircleShape, fill = tint.copy(alpha = 0.16f), glow = tint, glowElevation = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", style = B570Theme.type.label.copy(fontWeight = FontWeight.Bold), color = tint)
            }
            if (!last) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.6f), c.accent2.copy(alpha = 0.15f)))),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 4.dp else 16.dp, top = 4.dp)) {
            Text(step.title, style = B570Theme.type.titleS, color = c.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(step.desc, style = B570Theme.type.bodySmall, color = c.textSecondary)
        }
    }
}
