package com.b570.pilot.link

/**
 * 目前這段連線走哪條路。
 * - [DIRECT]：手機連上車子的 SoftAP（WifiNetworkSpecifier，本地網路，行動數據照常可用）
 * - [HOTSPOT]：車子加入**手機的個人熱點**，App 用 DISCOVER 找到車子 IP 後在熱點網段 UDP 單播（手機照用 4G/5G）
 */
enum class LinkMode(val labelZh: String) {
    DIRECT("直連"),
    HOTSPOT("手機熱點"),
}

/**
 * 一次連線要怎麼做（[CarLink.start]）。
 * @param credentials 車名（車子 AP 的 SSID，也用來比對 DISCOVER_ACK.ssid）+ 車子 AP 密碼（直連用；熱點模式用不到）
 * @param discover 先在手機的網路（熱點網段等）上送 DISCOVER 找車子
 * @param discoveryTimeoutMs 找多久（每 300 ms 一輪）
 * @param direct 找不到（或沒有找）時改用 WifiNetworkSpecifier 直連車子的 AP
 * @param lastIp 車子上次在熱點網段的位址（先單播給它，再廣播）
 * @param hotspotExpected 這台車設定為熱點模式：失敗訊息提示開啟手機熱點（[ConnectionState.Failed.hotspotHint]）
 */
data class LinkRequest(
    val credentials: WifiCredentials,
    val discover: Boolean = false,
    val discoveryTimeoutMs: Long = DEFAULT_DISCOVERY_MS,
    val direct: Boolean = true,
    val lastIp: String? = null,
    val hotspotExpected: Boolean = false,
) {
    companion object {
        /** §3.6：每 300 ms，最多 4 s。 */
        const val DEFAULT_DISCOVERY_MS = 4_000L
        const val DISCOVERY_PERIOD_MS = 300L
    }
}

/**
 * 在手機網路（熱點）上找到的車（DISCOVER_ACK）。
 * @param ssid 車名（車子 AP 的 SSID，例如 B570-A1B2）
 * @param ip 車子在熱點網段的位址
 * @param viaHotspot 車子是以熱點模式（STA）連上的
 * @param busy 已有其他手機在控制
 */
data class DiscoveredCar(
    val ssid: String,
    val ip: String,
    val chipIdHex: String,
    val viaHotspot: Boolean,
    val busy: Boolean,
)

/**
 * 熱點模式的帳密檢查（§3.4 STA_SET；App 與 ESP 都會檢查）。
 * - 熱點名稱：1..32 bytes（UTF-8，中文一個字 3 bytes）
 * - 熱點密碼：空白 = 開放熱點；否則 8..63 個可列印 ASCII（0x20..0x7E，可以有空白）
 */
object HotspotConfig {
    const val SSID_MAX_BYTES = 32
    const val PW_MIN = 8
    const val PW_MAX = 63

    /** 熱點名稱錯誤訊息（中文），合法回傳 null。 */
    fun validateSsid(ssid: String): String? {
        val n = ssid.toByteArray(Charsets.UTF_8).size
        return when {
            ssid.isEmpty() -> "請輸入手機熱點的名稱"
            n > SSID_MAX_BYTES -> "熱點名稱太長（最多 $SSID_MAX_BYTES 個位元組，中文約 10 個字）"
            ssid.any { it.isISOControl() } -> "熱點名稱不能有換行或控制字元"
            else -> null
        }
    }

    /** 熱點密碼錯誤訊息（中文），合法回傳 null。空字串 = 開放熱點（合法）。 */
    fun validatePassword(pw: String): String? = when {
        pw.isEmpty() -> null
        !pw.all { it.code in 0x20..0x7E } -> "熱點密碼只能用英文、數字、空白與一般符號"
        pw.length < PW_MIN -> "熱點密碼至少 $PW_MIN 碼（開放熱點請留空）"
        pw.length > PW_MAX -> "熱點密碼最多 $PW_MAX 碼"
        else -> null
    }
}
