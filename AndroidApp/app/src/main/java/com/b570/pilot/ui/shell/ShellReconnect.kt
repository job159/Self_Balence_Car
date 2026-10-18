package com.b570.pilot.ui.shell

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.b570.pilot.data.ReconnectStatus
import com.b570.pilot.ui.components.HudChip
import com.b570.pilot.ui.components.HudIconButton
import com.b570.pilot.ui.components.lerpColor
import com.b570.pilot.ui.theme.B570Theme
import kotlinx.coroutines.delay

/**
 * 自動重連提示：「車子斷線，自動重連中（第 2/6 次）」+ 倒數「3 秒後重試」/「正在連線…」+「取消」。
 * 不透明的警告色膠囊（與訊號中斷的 LinkLostBadge 同款），本體不攔觸控，只有「取消」可以按。
 * 倒數只在最裡面的文字重組（≤ 4 次 / 秒），外層不跟著重組。
 *
 * 駕駛畫面把它放在上緣列的車況膠囊那一格（DriveTopBar），所以寬度永遠是那一列實際剩下的空間。
 *
 * @param compact true = 單行（狀態條 / 駕駛上緣）；false = 兩行（滑入式導覽的車況面板）
 */
@Composable
fun ReconnectBadge(
    status: ReconnectStatus,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
) {
    val c = B570Theme.colors
    val shape = RoundedCornerShape(12.dp)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        // 放不下完整句子時改用短句 / 極短句（字級放大時門檻跟著放大，不切字）
        val widthDp = maxWidth.value / fontScale.coerceAtLeast(1f)
        val length = reconnectTitleLengthOf(widthDp, compact)
        // 窄的兩行版（滑入式導覽的車況面板約 210 dp）：「取消」改成 ✕ 圖示鈕，主文字才放得下一行
        val iconCancel = length == ReconnectTitle.TINY || (!compact && widthDp < 300f)
        Row(
            Modifier
                .clip(shape)
                .background(lerpColor(c.surface, c.warn, 0.16f))
                .border(1.dp, c.warn.copy(alpha = 0.55f), shape)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpinningRenew(c.warn, if (compact) 16.dp else 18.dp)
            Spacer(Modifier.width(8.dp))
            if (compact) {
                Text(
                    reconnectTitleZh(status, length),
                    style = B570Theme.type.label, color = c.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(" · ", style = B570Theme.type.label, color = c.textMuted, maxLines = 1)
                CountdownText(status, B570Theme.type.label, c.warn)
            } else {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        reconnectTitleZh(status, length),
                        style = B570Theme.type.bodySmall, color = c.textPrimary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    CountdownText(status, B570Theme.type.caption, c.warn)
                }
            }
            Spacer(Modifier.width(6.dp))
            if (iconCancel) {
                HudIconButton(Icons.Rounded.Close, "取消自動重連", onCancel, size = 30.dp, color = c.textSecondary)
            } else {
                HudChip("取消", icon = Icons.Rounded.Close, color = c.textSecondary, onClick = onCancel)
            }
        }
    }
}

/** 倒數文字（自己的計時器；只有秒數變了才重組這一個 Text）。 */
@Composable
private fun CountdownText(status: ReconnectStatus, style: TextStyle, color: Color) {
    val now = produceState(SystemClock.elapsedRealtime(), status.nextAtMs) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            if (status.nextAtMs <= 0L || value >= status.nextAtMs) break
            delay(250)
        }
    }
    val text by remember(status) { derivedStateOf { reconnectDetailZh(status, now.value) } }
    Text(text, style = style, color = color, maxLines = 1)
}

/** 旋轉的重連圖示（只在繪製階段轉）。 */
@Composable
private fun SpinningRenew(color: Color, size: Dp) {
    val t = rememberInfiniteTransition(label = "renew")
    val rot = t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "renewRot")
    Icon(
        Icons.Rounded.Autorenew, contentDescription = null, tint = color,
        modifier = Modifier.size(size).graphicsLayer { rotationZ = rot.value },
    )
}
