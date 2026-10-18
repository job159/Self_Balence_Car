package com.b570.pilot.data

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.b570.pilot.MainActivity
import com.b570.pilot.R

/** 從桌面捷徑（或其他 intent）帶進 App 的請求，由 [com.b570.pilot.ui.AppViewModel.handleLaunchRequest] 處理。 */
sealed interface LaunchRequest {
    /** 連線到記住的車 [ssid]（連線頁會先檢查權限與 Wi-Fi）。 */
    data class ConnectCar(val ssid: String) : LaunchRequest

    /** 開啟 QR 掃描，配對新車。 */
    data object ScanPair : LaunchRequest
}

/** 一個要發佈的桌面捷徑（純資料，方便單元測試）。[rank] 0 = 最靠近 App 圖示。 */
data class ShortcutSpec(
    val id: String,
    val shortLabel: String,
    val longLabel: String,
    val rank: Int,
    val request: LaunchRequest,
)

/**
 * 桌面捷徑（長按 App 圖示）：最近連線的 3 台車「連線 <名稱>」+「掃描配對」。
 * 記住的車改變時由 AppViewModel 重新發佈；被忘記的車若已釘選在桌面，捷徑會停用並顯示原因。
 * 只用 androidx.core 的 ShortcutManagerCompat（API 25+ 都有）。
 */
object CarShortcuts {
    const val ACTION_CONNECT = "com.b570.pilot.action.CONNECT_CAR"
    const val ACTION_SCAN = "com.b570.pilot.action.SCAN_PAIR"
    const val EXTRA_SSID = "com.b570.pilot.extra.SSID"

    const val ID_SCAN = "scan"
    private const val CAR_PREFIX = "car:"

    /** 最多幾台車的捷徑（再加「掃描配對」= 4 個，大部分桌面長按選單剛好放得下）。 */
    const val MAX_CARS = 3

    /** 系統建議：短標籤 ≤ 10 字、長標籤 ≤ 25 字。 */
    private const val SHORT_MAX = 10
    private const val LONG_MAX = 25

    fun carId(ssid: String): String = CAR_PREFIX + ssid

    /** 捷徑 id → SSID（不是車子捷徑回傳 null）。 */
    fun ssidOf(id: String): String? = id.takeIf { it.startsWith(CAR_PREFIX) }?.removePrefix(CAR_PREFIX)?.takeIf { it.isNotEmpty() }

    /** intent 的 action + SSID extra → 請求；不是捷徑的 intent（一般從桌面開啟）回傳 null。 */
    fun parse(action: String?, ssid: String?): LaunchRequest? = when (action) {
        ACTION_CONNECT -> ssid?.trim()?.takeIf { it.isNotEmpty() }?.let { LaunchRequest.ConnectCar(it) }
        ACTION_SCAN -> LaunchRequest.ScanPair
        else -> null
    }

    /**
     * 要發佈的動態捷徑：最近連線的車（[cars] 已是最近在前）排前面，「掃描配對」最後。
     * @param maxTotal 系統允許的數量（getMaxShortcutCountPerActivity）；不夠時先少放車，「掃描配對」一定保留
     */
    fun plan(cars: List<SavedCar>, maxTotal: Int = MAX_CARS + 1): List<ShortcutSpec> {
        if (maxTotal <= 0) return emptyList()
        val carSlots = minOf(MAX_CARS, maxTotal - 1).coerceAtLeast(0)
        val recent = cars.distinctBy { it.ssid }.filter { it.ssid.isNotBlank() }.take(carSlots)
        val out = ArrayList<ShortcutSpec>(recent.size + 1)
        recent.forEachIndexed { i, car ->
            val name = car.displayName
            val full = "連線 $name"
            out += ShortcutSpec(
                id = carId(car.ssid),
                // 桌面上的捷徑只有一小格：放不下「連線 …」時只放車名
                shortLabel = if (full.length <= SHORT_MAX) full else clip(name, SHORT_MAX),
                longLabel = clip(full, LONG_MAX),
                rank = i,
                request = LaunchRequest.ConnectCar(car.ssid),
            )
        }
        out += ShortcutSpec(ID_SCAN, "掃描配對", "掃描 QR 配對新車", out.size, LaunchRequest.ScanPair)
        return out
    }

    /** 太長的字串截斷加「…」（總長 = [max]）。 */
    internal fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take((max - 1).coerceAtLeast(0)) + "…"

    /** 開啟 App 並執行 [request] 的 intent（明確指向 MainActivity；singleTop 時走 onNewIntent）。 */
    fun intentFor(context: Context, request: LaunchRequest): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                when (request) {
                    is LaunchRequest.ConnectCar -> {
                        action = ACTION_CONNECT
                        putExtra(EXTRA_SSID, request.ssid)
                    }
                    LaunchRequest.ScanPair -> action = ACTION_SCAN
                }
            }

    /**
     * 依記住的車重新發佈動態捷徑（取代全部）。被忘記的車若被釘選在桌面就停用（點了會顯示原因），
     * 重新配對後再啟用。任何系統錯誤都忽略（捷徑只是方便用，不能影響 App）。非主執行緒呼叫即可。
     */
    fun publish(context: Context, cars: List<SavedCar>) {
        val app = context.applicationContext
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app).let { if (it <= 0) MAX_CARS + 1 else it }
            val specs = plan(cars, max)
            val infos = specs.map { s ->
                ShortcutInfoCompat.Builder(app, s.id)
                    .setShortLabel(s.shortLabel)
                    .setLongLabel(s.longLabel)
                    .setRank(s.rank)
                    .setIcon(
                        IconCompat.createWithResource(
                            app,
                            if (s.request is LaunchRequest.ScanPair) R.drawable.ic_shortcut_scan else R.drawable.ic_shortcut_car,
                        ),
                    )
                    .setIntent(intentFor(app, s.request))
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(app, infos)

            // 釘選在桌面的車子捷徑：車子被忘記 → 停用；又記住了 → 啟用
            val saved = cars.mapTo(HashSet()) { it.ssid }
            val pinned = ShortcutManagerCompat.getShortcuts(app, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            val gone = pinned.filter { p -> ssidOf(p.id)?.let { it !in saved } == true }.map { it.id }
            val back = pinned.filter { p -> !p.isEnabled && ssidOf(p.id)?.let { it in saved } == true }.map { it.id }
            if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(app, gone, "這台車已被忘記，請重新掃描 QR Code 配對")
            if (back.isNotEmpty()) ShortcutManagerCompat.enableShortcuts(app, pinned.filter { it.id in back })
        }
    }

    /** 連上某台車：告訴桌面這個捷徑剛被用過（桌面的建議排序會用到）。 */
    fun reportUsed(context: Context, ssid: String) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context.applicationContext, carId(ssid)) }
    }
}
