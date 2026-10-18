package com.b570.pilot.link

/**
 * 車子 SoftAP 的連線資訊。純 Kotlin（可在 JVM 單元測試）。
 * SSID / 密碼限制見 PROTOCOL §3.4。
 */
data class WifiCredentials(val ssid: String, val password: String) {

    /** 符合 §3.4 限制（App 與 ESP 都會檢查）。 */
    val isValid: Boolean get() = validateSsid(ssid) == null && validatePassword(password) == null

    /** 產生標準 Wi-Fi QR 字串（合法的 SSID/密碼不需跳脫）。 */
    fun toQrPayload(): String = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"

    /** 隱藏密碼的字串（log 用）。 */
    override fun toString(): String = "WifiCredentials(ssid=$ssid, password=***)"

    companion object {
        const val SSID_MIN = 1
        const val SSID_MAX = 12
        const val PW_MIN = 8
        const val PW_MAX = 16
        private const val PW_FORBIDDEN = ";,:\\\""

        /** SSID 錯誤訊息（中文），合法回傳 null。規則：1..12 字元，[A-Za-z0-9_-]。 */
        fun validateSsid(ssid: String): String? = when {
            ssid.isEmpty() -> "請輸入車子名稱（SSID）"
            ssid.length > SSID_MAX -> "名稱最多 $SSID_MAX 個字元"
            !ssid.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' } ->
                "名稱只能用英文、數字、底線或減號"
            else -> null
        }

        /** 密碼錯誤訊息（中文），合法回傳 null。規則：8..16 個可列印 ASCII，不含 ; , : \ "。 */
        fun validatePassword(pw: String): String? = when {
            pw.length < PW_MIN -> "密碼至少 $PW_MIN 碼"
            pw.length > PW_MAX -> "密碼最多 $PW_MAX 碼"
            !pw.all { it.code in 0x21..0x7E } -> "密碼只能用英文、數字與一般符號（不可有空白或中文）"
            pw.any { it in PW_FORBIDDEN } -> "密碼不可包含 ; , : \\ \" 這些符號"
            else -> null
        }

        /**
         * 解析 Wi-Fi QR：`WIFI:T:WPA;S:<ssid>;P:<pw>;;`
         * - 欄位順序不限，T: / H: 可省略，結尾 `;;` 可省略
         * - 支援反斜線跳脫（`\;` `\,` `\:` `\\` `\"`），以及用雙引號包住的值
         * - 非 WIFI: 開頭、或缺少 S: 時回傳 null
         * 只做解析不做 §3.4 驗證；要一併驗證請用 [parseCarQr]。
         */
        fun parseQr(raw: String): WifiCredentials? {
            val text = raw.trim()
            if (!text.startsWith("WIFI:", ignoreCase = true)) return null
            val fields = splitFields(text.substring(5))
            var ssid: String? = null
            var pw = ""
            for ((key, value) in fields) {
                when (key.uppercase()) {
                    "S" -> ssid = value
                    "P" -> pw = value
                    // T:（WPA/WEP/nopass）、H:（隱藏網路）等其他欄位忽略
                }
            }
            if (ssid.isNullOrEmpty()) return null
            return WifiCredentials(ssid, pw)
        }

        /** 掃描結果：連同 §3.4 驗證與中文錯誤訊息。 */
        fun parseCarQr(raw: String): QrResult {
            val c = parseQr(raw) ?: return if (raw.trim().startsWith("WIFI:", ignoreCase = true))
                QrResult.Invalid("QR Code 裡沒有 Wi-Fi 名稱")
            else QrResult.NotWifi
            validateSsid(c.ssid)?.let { return QrResult.Invalid("這不是 B570 的 QR Code（$it）") }
            validatePassword(c.password)?.let { return QrResult.Invalid("密碼格式不符：$it") }
            return QrResult.Ok(c)
        }

        private fun splitFields(body: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>(4)
            val cur = StringBuilder()
            var i = 0
            fun flush() {
                val s = cur.toString()
                cur.setLength(0)
                if (s.isEmpty()) return
                val colon = findUnescapedColon(s)
                if (colon <= 0) return
                out += s.substring(0, colon) to unescape(s.substring(colon + 1))
            }
            while (i < body.length) {
                val ch = body[i]
                when {
                    ch == '\\' && i + 1 < body.length -> { cur.append(ch).append(body[i + 1]); i += 2; continue }
                    ch == ';' -> flush()
                    else -> cur.append(ch)
                }
                i++
            }
            flush()
            return out
        }

        private fun findUnescapedColon(s: String): Int {
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\') { i += 2; continue }
                if (s[i] == ':') return i
                i++
            }
            return -1
        }

        private fun unescape(v: String): String {
            // 雙引號包住且內部沒有跳脫的引號 → 去掉引號
            val s = if (v.length >= 2 && v.first() == '"' && v.last() == '"' && !v.endsWith("\\\"")) v.substring(1, v.length - 1) else v
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 } else { sb.append(ch); i++ }
            }
            return sb.toString()
        }

        private fun escape(v: String): String {
            val sb = StringBuilder(v.length)
            for (ch in v) { if (ch in "\\;,:\"") sb.append('\\'); sb.append(ch) }
            return sb.toString()
        }
    }
}

/** [WifiCredentials.parseCarQr] 的結果。 */
sealed interface QrResult {
    data class Ok(val credentials: WifiCredentials) : QrResult
    /** 不是 Wi-Fi QR（例如網址）。掃描頁應忽略並繼續掃描。 */
    data object NotWifi : QrResult
    /** 是 Wi-Fi QR 但不符合 B570 規格。 */
    data class Invalid(val reasonZh: String) : QrResult
}
