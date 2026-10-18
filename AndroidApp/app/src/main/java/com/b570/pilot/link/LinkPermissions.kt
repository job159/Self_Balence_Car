package com.b570.pilot.link

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 連線前需要的執行期權限與 Wi-Fi 狀態工具。 */
object LinkPermissions {

    /**
     * 這個 API level 連線前要請求的執行期權限（一次全部請求）。
     * API 33+：NEARBY_WIFI_DEVICES；29..32：ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION（API 31+ 規定兩者要一起請求）。
     */
    fun requiredForConnect(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
        if (sdkInt >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** 尚未授予的連線權限（空陣列 = 可以連線）。 */
    fun missingForConnect(context: Context): Array<String> =
        requiredForConnect().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()

    /** 掃描 QR 用的相機權限。 */
    const val CAMERA: String = Manifest.permission.CAMERA

    fun hasCamera(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, CAMERA) == PackageManager.PERMISSION_GRANTED

    /** 權限用途說明（請求前顯示）。 */
    val rationaleZh: String
        get() = if (Build.VERSION.SDK_INT >= 33) "需要「附近裝置」權限才能直接連上車子的 Wi-Fi，不會讀取你的位置。"
        else "Android 系統要求「位置」權限才能連上車子的 Wi-Fi，App 不會記錄你的位置。"

    fun isWifiEnabled(context: Context): Boolean =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    /** 開啟系統 Wi-Fi 快速面板（在 App 內開關 Wi-Fi）。 */
    fun wifiPanelIntent(): Intent = Intent(Settings.Panel.ACTION_WIFI)

    /**
     * 熱點模式：開啟系統的「個人熱點 / 網路共用」設定頁。依序嘗試各家設定頁，最後退回「無線與網路」設定。
     * @return 有成功開啟某個設定頁
     */
    fun openHotspotSettings(context: Context): Boolean {
        for (intent in hotspotSettingsIntents()) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // ActivityNotFoundException / SecurityException：換下一個
            }
        }
        return false
    }

    /** 熱點設定頁候選（Wi-Fi 熱點頁 → 網路共用頁 → 通用網路設定）。 */
    fun hotspotSettingsIntents(): List<Intent> = listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$WifiTetherSettingsActivity"),
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
        Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )

    /** 開啟本 App 的系統設定頁（權限被永久拒絕時引導用）。 */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
