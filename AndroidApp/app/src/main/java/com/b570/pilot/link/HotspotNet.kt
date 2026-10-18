package com.b570.pilot.link

import android.content.Context
import android.net.ConnectivityManager
import com.b570.pilot.protocol.Discover
import com.b570.pilot.protocol.DiscoverAck
import com.b570.pilot.protocol.FrameCodec
import com.b570.pilot.protocol.FrameParser
import com.b570.pilot.protocol.Ipv4
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.ProtocolException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.random.Random

/** 介面上的一個 IPv4 位址。[address] / [broadcast] 是 [Ipv4.toInt] 的大端整數。 */
data class IfaceAddr(val address: Int, val prefixLength: Int, val broadcast: Int? = null) {
    /** 子網路廣播位址（介面沒給就用前綴長度算；/31、/32 沒有廣播位址 → null）。 */
    val directedBroadcast: Int?
        get() = broadcast?.takeIf { it != 0 } ?: if (prefixLength in 1..30) address or ((1 shl (32 - prefixLength)) - 1) else null

    fun contains(ip: Int): Boolean {
        if (prefixLength !in 0..32) return false
        if (prefixLength == 0) return true
        val mask = if (prefixLength == 32) -1 else ((1 shl (32 - prefixLength)) - 1).inv()
        return (address and mask) == (ip and mask)
    }

    companion object {
        /** 測試 / 除錯用："192.168.43.1/24" → IfaceAddr。 */
        fun of(cidr: String, broadcast: String? = null): IfaceAddr {
            val (ip, len) = cidr.split('/').let { it[0] to (it.getOrNull(1)?.toInt() ?: 32) }
            return IfaceAddr(Ipv4.toInt(requireNotNull(Ipv4.parse(ip))), len, broadcast?.let { Ipv4.toInt(requireNotNull(Ipv4.parse(it))) })
        }
    }
}

/** 網路介面快照（[HotspotNet.snapshot]），只留 IPv4。 */
data class IfaceInfo(val name: String, val up: Boolean, val loopback: Boolean, val addrs: List<IfaceAddr>)

/**
 * 熱點模式的網路工具：判斷手機熱點是否開著、算出 DISCOVER 要送到哪些位址。
 * 判斷規則都是純函式（[discoveryTargets]、[isHotspotIface]、[hotspotLikelyOn]、[ifaceForAddress]），有單元測試。
 *
 * 熱點（tether）介面不是 ConnectivityManager 的 Network：名稱像 `ap0`、`swlan0`、`softap0` 的一定是熱點；
 * `wlan*` 但不屬於任何 Network 的（Pixel / 高通常見 `wlan1`、`wlan2`，也有機型直接用 `wlan0` 開熱點）也是熱點。
 * 我們自己的直連網路、家裡的 Wi-Fi 都是 Network，不會被誤判。
 */
object HotspotNet {

    /** 行動網路 / VPN / 虛擬介面（不廣播）。 */
    private val EXCLUDED_PREFIXES = listOf(
        "lo", "rmnet", "r_rmnet", "rev_rmnet", "ccmni", "pdp", "seth", "wwan", "clat", "v4-", "dummy",
        "tun", "ppp", "ipsec", "ifb", "sit", "ip6", "gre", "ip_vti", "epdg", "ims", "bond", "radio",
    )
    private val AP_NAME = Regex("^(ap|swlan|softap|wlan_ap|wifi_ap|wl_ap)\\d*$")
    private val WLAN_NAME = Regex("^wlan\\d+$")

    /** 行動網路、VPN、迴路等不該送 DISCOVER 的介面。 */
    fun isExcluded(name: String): Boolean = EXCLUDED_PREFIXES.any { name.startsWith(it) }

    /** 名稱一看就是熱點（SoftAP）介面。 */
    fun isApName(name: String): Boolean = AP_NAME.matches(name)

    /**
     * 這個介面看起來是手機熱點：up、有 IPv4，且（名稱像 AP，或是 `wlan*` 但不是任何 Network 的介面）。
     * @param networkIfaces ConnectivityManager 所有 Network 的介面名稱；拿不到（null）時只把 `wlan1` 以後當熱點
     */
    fun isHotspotIface(i: IfaceInfo, networkIfaces: Set<String>?): Boolean {
        if (!i.up || i.loopback || i.addrs.isEmpty() || isExcluded(i.name)) return false
        if (isApName(i.name)) return true
        if (!WLAN_NAME.matches(i.name)) return false
        // Wi-Fi Direct 群組擁有者固定用 192.168.49.1/24（AOSP Tethering 保留給 P2P，熱點不會用這個網段）：
        // 少數機型的 P2P 介面叫 wlan1，不能當成手機熱點（否則「請開啟手機熱點」提示會被藏起來）
        if (i.addrs.all { WIFI_P2P_SUBNET.contains(it.address) }) return false
        return if (networkIfaces != null) i.name !in networkIfaces else i.name != "wlan0"
    }

    /** Wi-Fi Direct（P2P）群組擁有者的網段 192.168.49.0/24。 */
    private val WIFI_P2P_SUBNET = IfaceAddr.of("192.168.49.1/24")

    /** 手機熱點看起來開著（有熱點介面）。 */
    fun hotspotLikelyOn(ifaces: List<IfaceInfo>, networkIfaces: Set<String>?): Boolean =
        ifaces.any { isHotspotIface(it, networkIfaces) }

    /**
     * DISCOVER 要送的位址（"a.b.c.d"，依序、不重複）：
     * 1. [lastIp]（車子上次的位址，單播）
     * 2. 熱點介面的子網路廣播
     * 3. 其他非行動網路、up 的 IPv4 介面的子網路廣播（例如車子加入的是同一個 Wi-Fi）
     */
    fun discoveryTargets(ifaces: List<IfaceInfo>, networkIfaces: Set<String>?, lastIp: String?): List<String> {
        val out = LinkedHashSet<String>()
        Ipv4.parse(lastIp)?.let { b -> if (!Ipv4.isZero(lastIp) && (b[0].toInt() and 0xFF) !in setOf(0, 127, 255)) out += Ipv4.format(b) }
        val usable = ifaces.filter { it.up && !it.loopback && !isExcluded(it.name) }
        val (hot, other) = usable.partition { isHotspotIface(it, networkIfaces) }
        for (i in hot + other) {
            for (a in i.addrs) a.directedBroadcast?.let { out += Ipv4.formatInt(it) }
        }
        return out.toList()
    }

    /** [ip] 屬於哪個介面的子網路（熱點連線中檢查「熱點還在不在」）。 */
    fun ifaceForAddress(ifaces: List<IfaceInfo>, ip: Int): IfaceInfo? =
        ifaces.firstOrNull { i -> i.up && !i.loopback && i.addrs.any { it.prefixLength in 1..32 && it.contains(ip) } }

    // ------------------------------------------------------------------ Android

    /** 目前所有介面（只留 IPv4）。失敗回傳空清單，不丟例外。 */
    fun snapshot(): List<IfaceInfo> = try {
        val list = ArrayList<IfaceInfo>(8)
        val en = NetworkInterface.getNetworkInterfaces()
        while (en != null && en.hasMoreElements()) {
            val ni = en.nextElement()
            val addrs = ArrayList<IfaceAddr>(1)
            for (ia in ni.interfaceAddresses ?: emptyList()) {
                val a = ia?.address as? Inet4Address ?: continue
                val b = ia.broadcast as? Inet4Address
                addrs += IfaceAddr(Ipv4.toInt(a.address), ia.networkPrefixLength.toInt(), b?.let { Ipv4.toInt(it.address) })
            }
            list += IfaceInfo(ni.name ?: "", runCatching { ni.isUp }.getOrDefault(false), runCatching { ni.isLoopback }.getOrDefault(false), addrs)
        }
        list
    } catch (e: Exception) {
        emptyList()
    }

    /** ConnectivityManager 所有 Network 的介面名稱（熱點介面不在裡面）。拿不到回傳 null。 */
    @Suppress("DEPRECATION")
    fun networkInterfaces(cm: ConnectivityManager?): Set<String>? = try {
        cm?.allNetworks?.mapNotNullTo(HashSet()) { n -> runCatching { cm.getLinkProperties(n)?.interfaceName }.getOrNull() }
    } catch (e: Exception) {
        null
    }

    /** 手機個人熱點看起來開著（約 1–5 ms，可在主執行緒偶爾呼叫）。 */
    fun isPhoneHotspotLikelyOn(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return hotspotLikelyOn(snapshot(), networkInterfaces(cm))
    }

    /** 目前要送 DISCOVER 的位址。 */
    fun currentTargets(cm: ConnectivityManager?, lastIp: String?): List<String> =
        discoveryTargets(snapshot(), networkInterfaces(cm), lastIp)

    fun newNonce(): Long = Random.nextLong(1L, 0xFFFF_FFFFL)

    /**
     * 在 [sock]（未綁定 Network 的 UDP socket，已 setBroadcast）上找車：每 [LinkRequest.DISCOVERY_PERIOD_MS] 重新計算目標
     * （使用者剛打開熱點也找得到）並送 DISCOVER(nonce)；收到 nonce 相符的 DISCOVER_ACK 呼叫 [onAck]，回傳 true 就結束。
     * 阻塞到逾時、[shouldStop] 或 [onAck] 回傳 true。socket 被關閉時直接結束。
     */
    fun probe(
        sock: DatagramSocket,
        nonce: Long,
        seq: Int,
        timeoutMs: Long,
        targets: () -> List<String>,
        shouldStop: () -> Boolean,
        onAck: (ack: DiscoverAck, from: InetSocketAddress) -> Boolean,
    ) {
        val frame = FrameCodec.build(MsgType.DISCOVER, seq and 0xFF, Discover(nonce).encode())
        val out = DatagramPacket(frame, frame.size)
        val buf = ByteArray(1024)
        val pkt = DatagramPacket(buf, buf.size)
        val parser = FrameParser(1024)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var nextSend = System.nanoTime()
        while (!shouldStop() && !sock.isClosed) {
            val now = System.nanoTime()
            if (now - deadline >= 0) return
            if (now - nextSend >= 0) {
                nextSend = now + LinkRequest.DISCOVERY_PERIOD_MS * 1_000_000L
                for (ip in targets()) {
                    val addr = Ipv4.parse(ip) ?: continue
                    try {
                        out.socketAddress = InetSocketAddress(InetAddress.getByAddress(addr), CarConnection.DEFAULT_PORT)
                        sock.send(out)
                    } catch (e: IOException) {
                        // 這個介面暫時送不出去（例如熱點剛關）：下一輪再試
                    } catch (e: SecurityException) {
                        // 不會發生（只需要 INTERNET 權限）
                    }
                }
            }
            try {
                pkt.setData(buf, 0, buf.size)
                sock.receive(pkt)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (sock.isClosed) return
                continue
            }
            parser.reset()
            var done = false
            parser.feed(buf, 0, pkt.length) { f ->
                if (!done && f.type == MsgType.DISCOVER_ACK) {
                    val ack = try { DiscoverAck.decode(f.payload) } catch (e: ProtocolException) { null }
                    if (ack != null && ack.nonce == nonce) {
                        val from = InetSocketAddress(pkt.address, pkt.port)
                        if (onAck(ack, from)) done = true
                    }
                }
            }
            if (done) return
        }
    }

    /**
     * 掃描手機網路（熱點）上所有的車，[timeoutMs] 後回傳（同一台車只列一次，依車名排序）。
     * 用自己的 socket，不影響目前的連線。阻塞，請在 IO 執行緒呼叫。
     */
    fun scanCars(context: Context, timeoutMs: Long, lastIps: Collection<String> = emptyList()): List<DiscoveredCar> {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val found = LinkedHashMap<String, DiscoveredCar>()
        val sock = try {
            DatagramSocket().apply { broadcast = true; soTimeout = 50 }
        } catch (e: IOException) {
            return emptyList()
        }
        try {
            probe(
                sock, newNonce(), Random.nextInt(256), timeoutMs,
                targets = {
                    val base = currentTargets(cm, null)
                    (lastIps.filter { Ipv4.parse(it) != null && !Ipv4.isZero(it) } + base).distinct()
                },
                shouldStop = { false },
            ) { ack, from ->
                val ip = from.address.hostAddress ?: ack.ip
                found[ack.ssid.ifEmpty { ip }] = DiscoveredCar(ack.ssid, ip, ack.chipIdHex, ack.isHotspot, ack.busy)
                false
            }
        } finally {
            runCatching { sock.close() }
        }
        return found.values.sortedBy { it.ssid }
    }
}
