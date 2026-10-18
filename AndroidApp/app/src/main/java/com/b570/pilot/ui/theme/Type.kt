package com.b570.pilot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 等寬數字（tabular numerals），讀數跳動時不會左右晃。 */
private const val TNUM = "tnum"

/**
 * 字體 token。中文用系統字體（Noto Sans CJK），數字讀數一律 tabular。
 * - readout*：儀表數字（AnimatedNumber、StatTile）
 * - title / body / label / caption：一般文字
 */
@Immutable
data class B570Typography(
    val readoutXL: TextStyle = TextStyle(fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Light, fontFeatureSettings = TNUM, letterSpacing = (-1).sp),
    val readoutL: TextStyle = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TNUM),
    val readoutM: TextStyle = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM),
    val readoutS: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM),
    /** 等寬小字（ID、十六進位、log）。 */
    val mono: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace),
    val titleL: TextStyle = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    val titleM: TextStyle = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    val titleS: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    val bodySmall: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    /** 小標籤（卡片標題、單位、圖例）。 */
    val label: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, fontFeatureSettings = TNUM),
    val caption: TextStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp, fontFeatureSettings = TNUM),
) {
    /** 對應的 Material3 Typography（讓 M3 元件風格一致）。 */
    val material: Typography = Typography(
        displayLarge = readoutXL,
        displayMedium = readoutL,
        displaySmall = readoutM,
        headlineLarge = titleL,
        headlineMedium = titleL.copy(fontSize = 22.sp),
        headlineSmall = titleM,
        titleLarge = titleM,
        titleMedium = titleS,
        titleSmall = titleS.copy(fontSize = 14.sp),
        bodyLarge = body,
        bodyMedium = bodySmall.copy(fontSize = 14.sp),
        bodySmall = bodySmall,
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = label,
        labelSmall = caption,
    )
}
