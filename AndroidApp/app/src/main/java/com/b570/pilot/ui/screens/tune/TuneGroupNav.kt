package com.b570.pilot.ui.screens.tune

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.b570.pilot.protocol.ParamGroup
import com.b570.pilot.ui.components.horizontalEdgeFade
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass

/** 參數群組（依韌體分組順序）。 */
internal val TUNE_GROUPS: List<ParamGroup> = ParamGroup.entries.toList()

/**
 * 群組快速跳轉列（像遙控器的選單分頁）：直立環 / 速度環 / 轉向環 / 運動限制 / 安全保護 / 系統。
 * 目前捲到的群組會亮起；有待套用（橘點）或已調整（青點）的群組有小圓點。點一下 → 展開並捲到該群組。
 */
@Composable
internal fun GroupJumpBar(
    ctl: TuneController,
    active: ParamGroup?,
    onJump: (ParamGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = B570Theme.colors
    val rowState = rememberLazyListState()
    // 目前群組被擠出可見範圍時，把它捲回來
    LaunchedEffect(active) {
        val idx = TUNE_GROUPS.indexOf(active)
        if (idx < 0) return@LaunchedEffect
        val info = rowState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == idx }
        if (item == null || item.offset < 0 || item.offset + item.size > info.viewportEndOffset) {
            rowState.animateScrollToItem((idx - 1).coerceAtLeast(0))
        }
    }
    Box(
        modifier
            .glass(RoundedCornerShape(50), fill = c.surface.copy(alpha = 0.92f))
            .clip(RoundedCornerShape(50)),
    ) {
        LazyRow(
            state = rowState,
            modifier = Modifier.horizontalEdgeFade(rowState, 28.dp).selectableGroup(),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(TUNE_GROUPS, key = { it.name }) { g ->
                GroupChip(g, ctl.byGroup.getValue(g), selected = g == active, onClick = { onJump(g) })
            }
        }
    }
}

@Composable
private fun GroupChip(group: ParamGroup, items: List<ParamUi>, selected: Boolean, onClick: () -> Unit) {
    val c = B570Theme.colors
    val modified by remember(items) { derivedStateOf { items.any { it.isModified } } }
    val staged by remember(items) { derivedStateOf { items.any { it.staged != null } } }
    val fill by animateColorAsState(if (selected) c.accent.copy(alpha = 0.18f) else c.glass.copy(alpha = 0f), label = "chipFill")
    val border by animateColorAsState(if (selected) c.accent.copy(alpha = 0.6f) else c.glassBorderDim.copy(alpha = 0f), label = "chipBorder")
    val tint by animateColorAsState(if (selected) c.accent else c.textSecondary, label = "chipTint")
    val textColor by animateColorAsState(if (selected) c.textPrimary else c.textSecondary, label = "chipText")
    val lift by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "chipLift")
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .graphicsLayer { scaleX = 0.96f + 0.04f * lift; scaleY = 0.96f + 0.04f * lift }
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(groupIcon(group), null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(group.labelZh, style = B570Theme.type.label, color = textColor, maxLines = 1)
        if (staged || modified) {
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (staged) c.warn else c.accent),
            )
        }
    }
}
