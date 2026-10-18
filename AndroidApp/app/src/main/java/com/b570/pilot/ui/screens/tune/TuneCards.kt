package com.b570.pilot.ui.screens.tune

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Factory
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.b570.pilot.ui.components.GamepadButton
import com.b570.pilot.ui.components.GlassCard
import com.b570.pilot.ui.components.LeverSwitch
import com.b570.pilot.ui.components.NeonButton
import com.b570.pilot.ui.components.NeonButtonStyle
import com.b570.pilot.ui.components.SectionHeader
import com.b570.pilot.ui.components.noRippleClickable
import com.b570.pilot.ui.components.rememberHaptics
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 控制台動作卡：即時套用撥桿、儲存到車上、讀取 / 零點校正 / 恢復預設（遙控器圓鍵）。
 * @param narrow 側欄窄（約 230 dp）：較小的內距與圓鍵
 */
@Composable
internal fun TuneActionsCard(
    ctl: TuneController,
    editable: Boolean,
    isDemo: Boolean,
    motorsOn: Boolean,
    modifier: Modifier = Modifier,
    narrow: Boolean = false,
) {
    val c = B570Theme.colors
    val busy = ctl.busyOp
    val idle = editable && busy == null
    GlassCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(if (narrow) 12.dp else 16.dp)) {
        LiveApplyRow(live = ctl.liveApply, onChange = ctl::setLive)
        if (isDemo) {
            Spacer(Modifier.height(10.dp))
            HintRow(Icons.Rounded.Info, "展示模式：只會改到模擬車", c.info, filled = true)
        }
        AnimatedVisibility(
            visible = editable && !ctl.hasCarValues,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            HintRow(Icons.Rounded.WarningAmber, "還沒讀到車上的參數，目前顯示預設值，請按「讀取」", c.warn, Modifier.padding(top = 10.dp), filled = true)
        }
        Spacer(Modifier.height(14.dp))
        SaveButton(ctl, enabled = idle || busy == TuneOp.SAVE, motorsOn = motorsOn, editable = editable)
        Spacer(Modifier.height(14.dp))
        val d = if (narrow) 50.dp else 58.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            GamepadButton(
                icon = Icons.Rounded.Download,
                label = "讀取",
                onClick = ctl::refresh,
                diameter = d,
                enabled = idle,
                loading = busy == TuneOp.REFRESH,
            )
            GamepadButton(
                icon = Icons.Rounded.CenterFocusStrong,
                label = "零點校正",
                onClick = ctl::openZeroCal,
                diameter = d,
                enabled = idle,
            )
            GamepadButton(
                icon = Icons.Rounded.RestartAlt,
                label = "恢復預設",
                onClick = { ctl.dialog = TuneDialog.ConfirmReset },
                diameter = d,
                color = c.warn,
                enabled = idle,
                loading = busy == TuneOp.RESET,
            )
        }
    }
}

/** 即時套用：遙控器撥桿（上 = 開）+ 說明；點文字也能切換。 */
@Composable
private fun LiveApplyRow(live: Boolean, onChange: (Boolean, Boolean) -> Unit) {
    val c = B570Theme.colors
    val iconTint by animateColorAsState(if (live) c.accent else c.textMuted, label = "liveTint")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 撥桿自己有觸覺回饋 → 不再加
        LeverSwitch(
            checked = live,
            onCheckedChange = { onChange(it, false) },
            modifier = Modifier.semantics { contentDescription = "即時套用" },
        )
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .noRippleClickable { onChange(!live, true) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, tint = iconTint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("即時套用", style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AnimatedContent(
                targetState = live,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "liveDesc",
            ) { on ->
                Text(
                    if (on) "拖動時直接送到車上，馬上看效果" else "先暫存，確認後一次套用",
                    style = B570Theme.type.caption,
                    color = c.textSecondary,
                )
            }
        }
    }
}

/** 儲存到車上：成功後短暫變成綠色「已儲存」。 */
@Composable
private fun SaveButton(ctl: TuneController, enabled: Boolean, motorsOn: Boolean, editable: Boolean) {
    val c = B570Theme.colors
    var justSaved by remember { mutableStateOf(false) }
    val savedAt = ctl.savedAt
    LaunchedEffect(savedAt) {
        if (savedAt > 0L && SystemClock.uptimeMillis() - savedAt < 1500L) {
            justSaved = true
            delay(1800)
            justSaved = false
        }
    }
    NeonButton(
        text = if (justSaved) "已儲存" else "儲存到車上",
        onClick = { ctl.requestSave(motorsOn) },
        modifier = Modifier.fillMaxWidth(),
        icon = if (justSaved) Icons.Rounded.CheckCircle else Icons.Rounded.Save,
        enabled = enabled && !justSaved,
        loading = ctl.busyOp == TuneOp.SAVE,
        color = if (justSaved) c.ok else null,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        when {
            !editable -> "連線後才能儲存"
            motorsOn -> "儲存前需先停止馬達"
            else -> "寫入車上的 Flash，重開機後仍保留"
        },
        style = B570Theme.type.caption,
        color = if (motorsOn && editable) c.warn else c.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ====================================================================== 設定檔

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.TAIWAN)

/**
 * 設定檔卡：內建（原廠 / 穩定 / 運動）+ 我的設定檔（套用 / 刪除）+ 另存設定。
 * @param narrow 側欄窄：內建設定檔改成直列，「另存設定」移到卡片下方
 */
@Composable
internal fun TuneProfilesCard(ctl: TuneController, editable: Boolean, modifier: Modifier = Modifier, narrow: Boolean = false) {
    val c = B570Theme.colors
    val profiles by ctl.profiles.collectAsStateWithLifecycle()
    val saveAs: @Composable () -> Unit = {
        NeonButton(
            "另存設定",
            onClick = { ctl.dialog = TuneDialog.NewProfile },
            style = if (narrow) NeonButtonStyle.SECONDARY else NeonButtonStyle.GHOST,
            icon = Icons.Rounded.BookmarkAdd,
            compact = true,
            enabled = ctl.hasCarValues,
            modifier = if (narrow) Modifier.fillMaxWidth() else Modifier,
        )
    }
    GlassCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(if (narrow) 12.dp else 16.dp)) {
        SectionHeader(
            title = "設定檔",
            subtitle = "內建設定只調運動限制，不會動到 PID",
            trailing = if (narrow) null else saveAs,
        )
        Spacer(Modifier.height(10.dp))
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TunePresets.ALL.forEach { preset ->
                    key(preset.id) { PresetRow(preset, ctl, editable) }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TunePresets.ALL.forEach { preset ->
                    key(preset.id) { PresetTile(preset, ctl, editable, Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("我的設定檔", style = B570Theme.type.label, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        if (profiles.isEmpty()) {
            Text(
                "還沒有自己的設定檔。調好參數後按「另存設定」，之後一鍵套用。",
                style = B570Theme.type.bodySmall,
                color = c.textMuted,
            )
        } else {
            profiles.forEachIndexed { i, pr ->
                key(pr.id) {
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = c.divider.copy(alpha = 0.6f))
                    UserProfileRow(pr, ctl, editable, narrow)
                }
            }
        }
        if (narrow) {
            Spacer(Modifier.height(12.dp))
            saveAs()
        }
    }
}

private fun presetTint(kind: ProfileKind, accent: Color, info: Color, ok: Color, hot: Color): Color = when (kind) {
    ProfileKind.FACTORY -> info
    ProfileKind.STABLE -> ok
    ProfileKind.SPORT -> hot
    ProfileKind.USER -> accent
}

private fun presetIcon(kind: ProfileKind): ImageVector = when (kind) {
    ProfileKind.FACTORY -> Icons.Rounded.Factory
    ProfileKind.STABLE -> Icons.Rounded.Spa
    ProfileKind.SPORT -> Icons.Rounded.LocalFireDepartment
    ProfileKind.USER -> Icons.Rounded.Bookmark
}

/** 內建設定檔共用的按壓外觀（縮放 + 使用中發光）。 */
@Composable
private fun Modifier.presetSurface(
    profile: TuneProfile,
    ctl: TuneController,
    editable: Boolean,
    current: Boolean,
    tint: Color,
): Modifier {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.95f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "preset",
    )
    val shape = RoundedCornerShape(B570Theme.radii.m)
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (editable) 1f else 0.5f }
        .glass(
            shape,
            fill = if (current) tint.copy(alpha = 0.12f) else if (pressed) c.glassHigh else c.glass,
            glow = if (current) tint.copy(alpha = 0.55f) else null,
            glowElevation = 10.dp,
        )
        .clip(shape)
        .clickable(src, indication = null, enabled = editable && ctl.busyOp == null, role = Role.Button) {
            haptics.click()
            ctl.dialog = TuneDialog.ApplyProfile(profile)
        }
}

@Composable
private fun PresetTile(profile: TuneProfile, ctl: TuneController, editable: Boolean, modifier: Modifier = Modifier) {
    val c = B570Theme.colors
    val current by remember(profile) { derivedStateOf { ctl.isCurrent(profile) } }
    val tint = presetTint(profile.kind, c.accent, c.info, c.ok, c.danger)
    Column(
        modifier
            .presetSurface(profile, ctl, editable, current, tint)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(presetIcon(profile.kind), null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(profile.name, style = B570Theme.type.titleS, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (current) "使用中" else profile.captionZh,
            style = B570Theme.type.caption,
            color = if (current) tint else c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 窄側欄用：圖示 + 名稱 + 說明橫排（像遙控器的「模型記憶」槽位）。 */
@Composable
private fun PresetRow(profile: TuneProfile, ctl: TuneController, editable: Boolean) {
    val c = B570Theme.colors
    val current by remember(profile) { derivedStateOf { ctl.isCurrent(profile) } }
    val tint = presetTint(profile.kind, c.accent, c.info, c.ok, c.danger)
    Row(
        Modifier
            .fillMaxWidth()
            .presetSurface(profile, ctl, editable, current, tint)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(presetIcon(profile.kind), null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            profile.name,
            style = B570Theme.type.titleS,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        if (current) {
            MiniBadge("使用中", tint)
        } else {
            Text(profile.captionZh, style = B570Theme.type.caption, color = c.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun UserProfileRow(profile: TuneProfile, ctl: TuneController, editable: Boolean, narrow: Boolean) {
    val c = B570Theme.colors
    val haptics = rememberHaptics()
    val current by remember(profile) { derivedStateOf { ctl.isCurrent(profile) } }
    val date = remember(profile.updatedMs) {
        Instant.ofEpochMilli(profile.updatedMs).atZone(ZoneId.systemDefault()).format(DATE_FMT)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!narrow) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(c.accent2.copy(alpha = 0.16f))
                    .border(1.dp, c.accent2.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Bookmark, null, tint = c.accent2, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    style = B570Theme.type.titleS,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (current && !narrow) {
                    Spacer(Modifier.width(6.dp))
                    MiniBadge("使用中", c.ok)
                }
            }
            Text(
                if (current && narrow) "使用中 · ${profile.values.size} 項" else "${profile.values.size} 項 · $date",
                style = B570Theme.type.caption,
                color = if (current && narrow) c.ok else c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        NeonButton(
            "套用",
            onClick = { ctl.dialog = TuneDialog.ApplyProfile(profile) },
            style = NeonButtonStyle.SECONDARY,
            compact = true,
            enabled = editable && ctl.busyOp == null,
        )
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) {
                    haptics.click()
                    ctl.dialog = TuneDialog.DeleteProfile(profile)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "刪除", tint = c.textSecondary, modifier = Modifier.size(19.dp))
        }
    }
}
