package com.b570.pilot.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** 是否啟用觸覺回饋（shell 依 AppSettings.hapticsEnabled 提供）。 */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * 觸覺回饋（自動遵守使用者設定）。用 [rememberHaptics] 取得。
 * - [tick]：很輕（滑桿步進、搖桿離開死區）
 * - [click]：一般按鈕
 * - [confirm]：成功 / 確認
 * - [heavy]：重要動作（緊急停止、搖桿推到底）
 * - [reject]：失敗 / 不允許
 */
@Stable
class Haptics internal constructor(private val view: View, private val enabled: Boolean) {
    fun tick() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun click() = perform(HapticFeedbackConstants.VIRTUAL_KEY)
    fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun heavy() = perform(HapticFeedbackConstants.LONG_PRESS)
    fun reject() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    private fun perform(c: Int) {
        if (enabled) view.performHapticFeedback(c)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}
