package com.b570.pilot.link

import com.b570.pilot.protocol.Ipv4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 熱點模式：手機熱點判斷、DISCOVER 目標位址（純函式，用介面快照資料測）。 */
class HotspotNetTest {

    private fun iface(name: String, vararg cidrs: String, up: Boolean = true, loopback: Boolean = false, bcast: String? = null) =
        IfaceInfo(name, up, loopback, cidrs.map { IfaceAddr.of(it, bcast) })

    private val lo = iface("lo", "127.0.0.1/8", loopback = true)
    private val cell = iface("rmnet_data0", "10.123.45.67/30")
    private val clat = iface("v4-rmnet_data0", "192.0.0.4/29")
    private val homeWifi = iface("wlan0", "192.168.1.23/24")
    private val samsungAp = iface("swlan0", "192.168.43.1/24", bcast = "192.168.43.255")
    private val pixelAp = iface("wlan1", "10.97.40.1/24")
    private val vpn = iface("tun0", "10.8.0.2/24")

    // ------------------------------------------------------------------ IfaceAddr

    @Test fun directedBroadcastFromPrefix() {
        assertEquals("192.168.43.255", Ipv4.formatInt(IfaceAddr.of("192.168.43.1/24").directedBroadcast!!))
        assertEquals("172.20.10.15", Ipv4.formatInt(IfaceAddr.of("172.20.10.1/28").directedBroadcast!!))
        assertEquals("10.255.255.255", Ipv4.formatInt(IfaceAddr.of("10.1.2.3/8").directedBroadcast!!))
        // 介面給的廣播位址優先
        assertEquals("192.168.43.255", Ipv4.formatInt(IfaceAddr.of("192.168.43.1/24", "192.168.43.255").directedBroadcast!!))
        // /31、/32 沒有廣播位址
        assertNull(IfaceAddr.of("10.0.0.1/31").directedBroadcast)
        assertNull(IfaceAddr.of("10.0.0.1/32").directedBroadcast)
    }

    @Test fun subnetContains() {
        val a = IfaceAddr.of("192.168.43.1/24")
        assertTrue(a.contains(Ipv4.toInt(Ipv4.parse("192.168.43.57")!!)))
        assertFalse(a.contains(Ipv4.toInt(Ipv4.parse("192.168.44.57")!!)))
        assertTrue(IfaceAddr.of("10.0.0.5/32").contains(Ipv4.toInt(Ipv4.parse("10.0.0.5")!!)))
    }

    // ------------------------------------------------------------------ hotspot detection

    @Test fun apNamedInterfacesAreHotspot() {
        for (n in listOf("ap0", "ap1", "swlan0", "softap0", "wlan_ap0")) {
            assertTrue(n, HotspotNet.isHotspotIface(iface(n, "192.168.43.1/24"), emptySet()))
        }
        assertFalse(HotspotNet.isHotspotIface(iface("swlan0", "192.168.43.1/24", up = false), emptySet()))
        assertFalse("no IPv4", HotspotNet.isHotspotIface(IfaceInfo("swlan0", true, false, emptyList()), emptySet()))
    }

    @Test fun wlanIsHotspotOnlyWhenNotAConnectivityNetwork() {
        val nets = setOf("wlan0", "rmnet_data0")
        assertTrue("wlan1 tether", HotspotNet.isHotspotIface(pixelAp, nets))
        assertFalse("wlan0 = home Wi-Fi", HotspotNet.isHotspotIface(homeWifi, nets))
        // STA + STA：我們自己的直連網路（wlan1）是 Network → 不是熱點
        assertFalse(HotspotNet.isHotspotIface(pixelAp, setOf("wlan0", "wlan1")))
        // Wi-Fi 關掉、直接用 wlan0 開熱點的機型
        assertTrue(HotspotNet.isHotspotIface(iface("wlan0", "192.168.43.1/24"), setOf("rmnet_data0")))
        // 拿不到 Network 清單：wlan1 以後當熱點，wlan0 不算
        assertTrue(HotspotNet.isHotspotIface(pixelAp, null))
        assertFalse(HotspotNet.isHotspotIface(homeWifi, null))
    }

    @Test fun wifiDirectGroupOwnerIsNotHotspot() {
        val nets = setOf("wlan0", "rmnet_data0")
        // 部分機型的 Wi-Fi Direct 介面叫 wlan1（不是 Network），位址固定 192.168.49.1/24
        val p2pWlan = iface("wlan1", "192.168.49.1/24")
        assertFalse(HotspotNet.isHotspotIface(p2pWlan, nets))
        assertFalse(HotspotNet.isHotspotIface(p2pWlan, null))
        assertFalse(HotspotNet.hotspotLikelyOn(listOf(lo, cell, homeWifi, p2pWlan), nets))
        // 名稱一看就是熱點的介面照樣算；熱點同時有別的網段也算
        assertTrue(HotspotNet.isHotspotIface(iface("ap0", "192.168.49.1/24"), nets))
        assertTrue(HotspotNet.isHotspotIface(IfaceInfo("wlan1", true, false, listOf(IfaceAddr.of("192.168.49.1/24"), IfaceAddr.of("10.97.40.1/24"))), nets))
        // 192.168.4x 其他網段（舊版 Android 熱點 192.168.43.1）不受影響
        assertTrue(HotspotNet.isHotspotIface(iface("wlan1", "192.168.43.1/24"), nets))
    }

    @Test fun cellularAndVpnAreNeverHotspot() {
        for (i in listOf(lo, cell, clat, vpn)) assertFalse(i.name, HotspotNet.isHotspotIface(i, emptySet()))
        assertTrue(HotspotNet.isExcluded("rmnet_data3"))
        assertTrue(HotspotNet.isExcluded("ccmni1"))
        assertFalse(HotspotNet.isExcluded("wlan0"))
    }

    @Test fun hotspotLikelyOn() {
        val nets = setOf("wlan0", "rmnet_data0")
        assertTrue(HotspotNet.hotspotLikelyOn(listOf(lo, cell, samsungAp), nets))
        assertTrue(HotspotNet.hotspotLikelyOn(listOf(lo, cell, homeWifi, pixelAp), nets))
        assertFalse(HotspotNet.hotspotLikelyOn(listOf(lo, cell, homeWifi), nets))
        assertFalse(HotspotNet.hotspotLikelyOn(emptyList(), nets))
    }

    // ------------------------------------------------------------------ discovery targets

    @Test fun targetsLastIpFirstThenHotspotThenOthers() {
        val nets = setOf("wlan0", "rmnet_data0")
        val t = HotspotNet.discoveryTargets(listOf(lo, cell, clat, homeWifi, samsungAp, vpn), nets, "192.168.43.57")
        assertEquals(listOf("192.168.43.57", "192.168.43.255", "192.168.1.255"), t)
    }

    @Test fun targetsWithoutLastIpAndDeduplicated() {
        val twoAddrs = IfaceInfo("ap0", true, false, listOf(IfaceAddr.of("192.168.43.1/24"), IfaceAddr.of("192.168.43.9/24")))
        assertEquals(listOf("192.168.43.255"), HotspotNet.discoveryTargets(listOf(twoAddrs), emptySet(), null))
        // lastIp 剛好是廣播位址也不重複
        assertEquals(listOf("192.168.43.255"), HotspotNet.discoveryTargets(listOf(twoAddrs), emptySet(), "192.168.43.255"))
    }

    @Test fun invalidLastIpIgnored() {
        val t = listOf(samsungAp)
        for (bad in listOf("0.0.0.0", "", "abc", "300.1.1.1", "127.0.0.1", "255.255.255.255")) {
            assertEquals(bad, listOf("192.168.43.255"), HotspotNet.discoveryTargets(t, emptySet(), bad))
        }
    }

    @Test fun downAndPointToPointInterfacesSkipped() {
        val down = iface("ap0", "192.168.50.1/24", up = false)
        val p2p = iface("rndis0", "10.0.0.1/32")
        assertEquals(emptyList<String>(), HotspotNet.discoveryTargets(listOf(lo, cell, down, p2p), emptySet(), null))
    }

    @Test fun ifaceForCarAddress() {
        val all = listOf(lo, cell, homeWifi, samsungAp)
        assertEquals("swlan0", HotspotNet.ifaceForAddress(all, Ipv4.toInt(Ipv4.parse("192.168.43.57")!!))?.name)
        assertEquals("wlan0", HotspotNet.ifaceForAddress(all, Ipv4.toInt(Ipv4.parse("192.168.1.80")!!))?.name)
        // 熱點關掉了：車子的位址不屬於任何介面
        assertNull(HotspotNet.ifaceForAddress(listOf(lo, cell, homeWifi), Ipv4.toInt(Ipv4.parse("192.168.43.57")!!)))
    }

    // ------------------------------------------------------------------ HotspotConfig (STA_SET 限制)

    @Test fun hotspotSsidValidation() {
        assertNull(HotspotConfig.validateSsid("MyPhone"))
        assertNull(HotspotConfig.validateSsid("我的手機"))
        assertNull(HotspotConfig.validateSsid("a".repeat(32)))
        assertNull(HotspotConfig.validateSsid("小明的 iPhone")) // 空白可以
        assertTrue(HotspotConfig.validateSsid("")!!.isNotEmpty())
        assertTrue(HotspotConfig.validateSsid("a".repeat(33))!!.contains("32"))
        // 11 個中文字 = 33 bytes
        assertTrue(HotspotConfig.validateSsid("一二三四五六七八九十一") != null)
        assertNull(HotspotConfig.validateSsid("一二三四五六七八九十")) // 30 bytes
        assertTrue(HotspotConfig.validateSsid("a\nb") != null)
    }

    @Test fun hotspotPasswordValidation() {
        assertNull(HotspotConfig.validatePassword("")) // 開放熱點
        assertNull(HotspotConfig.validatePassword("pass1234"))
        assertNull(HotspotConfig.validatePassword("with space ;,:\"\\ ok")) // 熱點密碼可有空白與符號（不進 QR）
        assertNull(HotspotConfig.validatePassword("x".repeat(63)))
        assertTrue(HotspotConfig.validatePassword("short") != null)
        assertTrue(HotspotConfig.validatePassword("x".repeat(64)) != null)
        assertTrue(HotspotConfig.validatePassword("密碼密碼密碼密碼") != null)
        assertTrue(HotspotConfig.validatePassword("tab\tpass1") != null)
    }
}
