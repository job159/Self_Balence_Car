package com.b570.pilot.ui.screens.drive

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext

/**
 * 駕駛頁自己的小偏好（`AppSettings` 沒有對應欄位，所以不動 data 層，存在駕駛頁自己的 SharedPreferences）。
 * 目前只有：360° 搖桿在左 / 在右。
 */
internal object DrivePrefs {
    private const val FILE = "drive_prefs"
    private const val KEY_STICK_RIGHT = "stick_right_360"

    /** 360° 搖桿在右手（按鍵區在左手）；預設在左（像手把：左類比搖桿移動、右手 A/B/X/Y）。 */
    fun stickRight(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_STICK_RIGHT, false) }.getOrDefault(false)

    fun setStickRight(context: Context, right: Boolean) {
        runCatching { prefs(context).edit().putBoolean(KEY_STICK_RIGHT, right).apply() }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/**
 * 360° 搖桿的位置：rememberSaveable（Activity 重建 / 切分頁回來不跳）+ SharedPreferences（下次開 App 也記得）。
 * 改值請用 [DrivePrefs.setStickRight] 一起存檔。
 */
@Composable
internal fun rememberStickRight(): MutableState<Boolean> {
    val context = LocalContext.current
    return rememberSaveable { mutableStateOf(DrivePrefs.stickRight(context)) }
}
