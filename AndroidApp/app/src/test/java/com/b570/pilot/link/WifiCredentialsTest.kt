package com.b570.pilot.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WIFI: QR 解析與 §3.4 驗證。 */
class WifiCredentialsTest {

    @Test fun standardOledQr() {
        assertEquals(WifiCredentials("B570-1A2B", "k7Pq9xZa"), WifiCredentials.parseQr("WIFI:T:WPA;S:B570-1A2B;P:k7Pq9xZa;;"))
    }

    @Test fun anyFieldOrderAndOptionalFields() {
        val want = WifiCredentials("B570-1A2B", "k7Pq9xZa")
        assertEquals(want, WifiCredentials.parseQr("WIFI:S:B570-1A2B;P:k7Pq9xZa;T:WPA;;"))
        assertEquals(want, WifiCredentials.parseQr("WIFI:P:k7Pq9xZa;S:B570-1A2B;;"))
        assertEquals(want, WifiCredentials.parseQr("WIFI:T:WPA;P:k7Pq9xZa;H:false;S:B570-1A2B;;"))
        assertEquals(want, WifiCredentials.parseQr("WIFI:S:B570-1A2B;T:WPA;P:k7Pq9xZa"))   // 缺結尾 ;;
        assertEquals(want, WifiCredentials.parseQr("  wifi:t:WPA;s:B570-1A2B;p:k7Pq9xZa;;\n")) // 大小寫、空白
    }

    @Test fun backslashEscapes() {
        val c = WifiCredentials.parseQr("WIFI:T:WPA;S:My\\;Net\\:1;P:a\\\\b\\,c\\\"d;;")
        assertEquals("My;Net:1", c?.ssid)
        assertEquals("a\\b,c\"d", c?.password)
    }

    @Test fun quotedValues() {
        assertEquals(WifiCredentials("ABCDEF", "12345678"), WifiCredentials.parseQr("WIFI:S:\"ABCDEF\";P:\"12345678\";;"))
    }

    @Test fun rejectsNonWifiPayloads() {
        assertNull(WifiCredentials.parseQr("https://example.com"))
        assertNull(WifiCredentials.parseQr("B570-1A2B"))
        assertNull(WifiCredentials.parseQr(""))
        assertNull(WifiCredentials.parseQr("WIFI:T:WPA;P:k7Pq9xZa;;")) // 沒有 SSID
        assertNull(WifiCredentials.parseQr("WIFI:S:;P:k7Pq9xZa;;"))
    }

    @Test fun roundTripThroughQrPayload() {
        val c = WifiCredentials("B570_x-9", "a!b#c\$d%e")
        assertEquals(c, WifiCredentials.parseQr(c.toQrPayload()))
        val weird = WifiCredentials("S;:,\\", "p;w,d:x\\y\"z")
        assertEquals(weird, WifiCredentials.parseQr(weird.toQrPayload()))
    }

    @Test fun carQrValidation() {
        assertTrue(WifiCredentials.parseCarQr("WIFI:T:WPA;S:B570-1A2B;P:k7Pq9xZa;;") is QrResult.Ok)
        assertEquals(QrResult.NotWifi, WifiCredentials.parseCarQr("hello"))
        assertTrue(WifiCredentials.parseCarQr("WIFI:T:WPA;P:k7Pq9xZa;;") is QrResult.Invalid)
        assertTrue(WifiCredentials.parseCarQr("WIFI:T:WPA;S:Home WiFi;P:k7Pq9xZa;;") is QrResult.Invalid) // 空白
        assertTrue(WifiCredentials.parseCarQr("WIFI:T:WPA;S:B570-1A2B;P:short;;") is QrResult.Invalid)
        assertTrue(WifiCredentials.parseCarQr("WIFI:T:nopass;S:B570-1A2B;;") is QrResult.Invalid)
    }

    @Test fun ssidRules() {
        assertNull(WifiCredentials.validateSsid("B570-1A2B"))
        assertNull(WifiCredentials.validateSsid("a"))
        assertNull(WifiCredentials.validateSsid("ABCDEFGHIJ_-"))
        assertNotNull(WifiCredentials.validateSsid(""))
        assertNotNull(WifiCredentials.validateSsid("ABCDEFGHIJKLM")) // 13
        assertNotNull(WifiCredentials.validateSsid("B570 1A2B"))
        assertNotNull(WifiCredentials.validateSsid("車子"))
    }

    @Test fun passwordRules() {
        assertNull(WifiCredentials.validatePassword("12345678"))
        assertNull(WifiCredentials.validatePassword("!#\$%&'()*+-./<=>"))
        assertNotNull(WifiCredentials.validatePassword("1234567"))
        assertNotNull(WifiCredentials.validatePassword("12345678901234567"))
        assertNotNull(WifiCredentials.validatePassword("1234 5678"))
        for (bad in listOf(";", ",", ":", "\\", "\"")) assertNotNull(bad, WifiCredentials.validatePassword("abcd123$bad"))
        assertNotNull(WifiCredentials.validatePassword("密碼密碼密碼密碼"))
    }

    @Test fun toStringHidesPassword() {
        assertTrue(!WifiCredentials("B570-1A2B", "secret99").toString().contains("secret99"))
    }
}
