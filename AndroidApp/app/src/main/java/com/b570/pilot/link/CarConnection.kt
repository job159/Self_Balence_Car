package com.b570.pilot.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.b570.pilot.protocol.Bye
import com.b570.pilot.protocol.Ctrl
import com.b570.pilot.protocol.Discover
import com.b570.pilot.protocol.DiscoverAck
import com.b570.pilot.protocol.EspPong
import com.b570.pilot.protocol.Frame
import com.b570.pilot.protocol.FrameCodec
import com.b570.pilot.protocol.FrameParser
import com.b570.pilot.protocol.HelloAck
import com.b570.pilot.protocol.Info
import com.b570.pilot.protocol.InfoGet
import com.b570.pilot.protocol.Ipv4
import com.b570.pilot.protocol.Le
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.ParamGet
import com.b570.pilot.protocol.Pong
import com.b570.pilot.protocol.ProtocolException
import com.b570.pilot.protocol.StaInfo
import com.b570.pilot.protocol.Telemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

/**
 * 真車連線（UDP）。兩條路，之後的 session 程式完全相同：
 *
 * - **直連**（[LinkMode.DIRECT]）：WifiNetworkSpecifier + 無 INTERNET 能力的 NetworkRequest 連上車子的 SoftAP：
 *   系統不會把它當預設網路，**手機行動數據照常可用**。socket 綁到這個 Network（絕不呼叫 bindProcessToNetwork）。
 *   另外監看「手機同時連著另一個有網際網路的 Wi-Fi」（STA + STA 併行：無線電要在兩個頻道間分時，延遲變高）→ [LinkListener.onDualWifi]。
 * - **熱點**（[LinkMode.HOTSPOT]，§3.6）：車子加入手機的個人熱點。熱點不是 Network（沒有 WifiNetworkSpecifier）：
 *   用**未綁定**的 socket 對熱點網段廣播 DISCOVER（+ 上次 IP 單播，每 300 ms）找到車子，之後在同一個 socket 上
 *   HELLO / session / 心跳 / 遙測。連線中失聯 → 重新廣播 DISCOVER（車子 IP 可能變了）；熱點關閉或 8 s 沒有任何回應 → Failed（交給自動重連）。
 *
 * 執行緒：
 * - 接收：專用高優先權執行緒，重複使用同一個 buffer / DatagramPacket。
 * - 傳送：專用高優先權執行緒，以**絕對時間**排程精準 50 Hz 送 CTRL：CTRL 在自己的 buffer 原地組幀（零配置），
 *   每個週期**先送 CTRL** 再處理其他；請求（requestMutex / 重送）完全不經過 CTRL 的路徑，不會拖慢它。
 *   所有 socket 寫入都在傳送執行緒進行（呼叫端不會在主執行緒碰網路）。
 * - 握手（§5）：HELLO 每 100 ms 直到 HELLO_ACK（5 s 逾時；BUSY → 失敗）→ INFO_GET → PARAM_GET(0xFF)。
 * - 約 1 s 沒收到車子（STM32）的幀 → 每 300 ms 重送 HELLO（§3.4 規則 4）；1.5 s → LinkLost；資料恢復自動回 Connected。
 *
 * 量測（[ControlTiming]）：每個 CTRL 的送出時間 → 遙測 ctrl_seq / ctrl_age 算出**控制延遲**（[LinkListener.onControlLatency]），
 * 遙測 link_q 對照送出幀數算**上行遺失**（[LinkListener.onUplinkSample]）。
 */
class CarConnection(context: Context, private val listener: LinkListener) : CarLink {

    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val lock = Any()
    @Volatile private var session: Session? = null // 寫入時持有 lock

    /** 高 16 位 = speed，低 16 位 = turn。 */
    private val control = AtomicInteger(0)

    override fun setControl(speedMmps: Int, turnPermille: Int) {
        val sp = speedMmps.coerceIn(-32768, 32767)
        val tn = turnPermille.coerceIn(-1000, 1000)
        control.set((sp shl 16) or (tn and 0xFFFF))
    }

    // ------------------------------------------------------------------ lifecycle

    override fun start(credentials: WifiCredentials) = start(LinkRequest(credentials))

    override fun start(request: LinkRequest) {
        stop()
        // 新的連線一定從 0 開始送 CTRL（stop() 在沒有 session 時不會清；不能把上一段連線的指令帶進來）
        control.set(0)
        val s = Session(request)
        val ssid = request.credentials.ssid
        synchronized(lock) {
            session = s
            _state.value = if (request.discover) ConnectionState.Discovering(ssid) else ConnectionState.RequestingWifi(ssid)
        }
        if (request.discover) scope.launch { discoverThenConnect(s) } else startDirect(s)
    }

    override fun stop() {
        val s = synchronized(lock) {
            val cur = session
            session = null
            _state.value = ConnectionState.Idle
            cur
        } ?: return
        control.set(0)
        closeSession(s, sendBye = true)
    }

    private fun fail(s: Session, failed: ConnectionState.Failed) {
        synchronized(lock) {
            if (session !== s) return
            session = null
            _state.value = failed
        }
        control.set(0)
        closeSession(s, sendBye = true)
    }

    private fun setStateIfCurrent(s: Session, st: ConnectionState) {
        synchronized(lock) { if (session === s && s.alive) _state.value = st }
    }

    private fun isCurrent(s: Session): Boolean = session === s && s.alive && !s.closing.get()

    private fun closeSession(s: Session, sendBye: Boolean) {
        if (!s.closing.compareAndSet(false, true)) return
        scope.launch {
            if (sendBye && s.socket != null && s.target != null && s.txThread != null) {
                // 讓傳送執行緒把 BYE 送出去後再收掉
                repeat(2) { s.enqueue(Bye.type, Bye.encode()) }
                delay(60)
            }
            s.alive = false
            s.helloAck.cancel()
            s.pending.get()?.deferred?.cancel()
            s.txThread?.let { LockSupport.unpark(it) }
            s.callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            s.callback = null
            s.dualCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            s.dualCb = null
            runCatching { s.socket?.close() }
            releaseWifiLocks(s)
        }
    }

    // ------------------------------------------------------------------ requests

    override fun send(type: Int, payload: ByteArray): Boolean {
        val s = currentSession() ?: return false
        if (s.socket == null || s.txThread == null) return false
        s.enqueue(type, payload)
        return true
    }

    override suspend fun request(
        type: Int,
        payload: ByteArray,
        timeoutMs: Long,
        retries: Int,
        matcher: (Frame) -> Boolean,
    ): Frame? {
        val s = currentSession() ?: return null
        return s.request(type, payload, timeoutMs, retries, matcher)
    }

    private fun currentSession(): Session? = synchronized(lock) { session }?.takeIf { it.alive && !it.closing.get() }

    override fun abortMotionRequests() {
        synchronized(lock) { session }?.abortMotion()
    }

    // ------------------------------------------------------------------ session

    private enum class Phase { WAIT_NETWORK, HANDSHAKE, SETUP, RUNNING }

    private class Pending(val matcher: (Frame) -> Boolean, val deferred: CompletableDeferred<Frame>, val motion: Boolean)

    /** 送到指定位址的幀（熱點模式重新找車的 DISCOVER 廣播）。 */
    private class Directed(val bytes: ByteArray, val to: InetSocketAddress)

    private inner class Session(val req: LinkRequest) {
        @Volatile var alive = true
        val closing = AtomicBoolean(false)
        @Volatile var callback: ConnectivityManager.NetworkCallback? = null
        @Volatile var dualCb: ConnectivityManager.NetworkCallback? = null
        val dualWifi = AtomicBoolean(false)
        @Volatile var network: Network? = null
        @Volatile var mode = LinkMode.DIRECT
        @Volatile var socket: DatagramSocket? = null
        @Volatile var target: InetSocketAddress? = null
        @Volatile var candidates: List<InetSocketAddress> = listOf(InetSocketAddress(DEFAULT_HOST, DEFAULT_PORT))
        @Volatile var phase = Phase.WAIT_NETWORK
        @Volatile var lastCarRxNs = 0L
        /** 最後收到任何幀（車子或 ESP）的時間：熱點模式判斷「車子不見了」用。 */
        @Volatile var lastAnyRxNs = 0L
        @Volatile var runningSinceNs = 0L
        @Volatile var info: Info? = null
        @Volatile var connectedSince = 0L
        @Volatile var wifiLocks: List<WifiManager.WifiLock> = emptyList()
        /** 熱點模式：找到車子的那個介面是手機熱點（關掉熱點 → 提示熱點已關閉）。 */
        @Volatile var carIfaceHotspot = false
        @Volatile var txThread: Thread? = null
        @Volatile var rxThread: Thread? = null
        val seq = AtomicInteger(0)
        val txQueue = ConcurrentLinkedQueue<ByteArray>()
        val directed = ConcurrentLinkedQueue<Directed>()
        val helloAck = CompletableDeferred<HelloAck>()
        val pending = AtomicReference<Pending?>(null)
        val requestMutex = Mutex()
        val sessionId = Random.nextLong(1, 0xFFFF_FFFFL)
        val nonce = HotspotNet.newNonce()
        val timing = ControlTiming()
        /** 防止 CTRL 在手機佇列堆積（靜止降頻、壅塞降頻、放開立即送）。 */
        val pacer = ControlPacer()
        /** 主控板 link_q 的結算時刻（上行遺失對齊用；只在接收執行緒用）。 */
        val settle = LinkQSettleTracker()
        /** 平滑後的控制延遲 / PING 來回 ms（上行樣本的時間窗位移；只在接收執行緒寫）；NaN = 還沒有。 */
        @Volatile var delayEmaMs = Float.NaN
        @Volatile var rttEmaMs = Float.NaN
        /** 每次 [abortMotion] +1；START / SEQ_PLAY 請求發現它變了就放棄（不再重送）。 */
        val motionEpoch = AtomicInteger(0)

        /** 分配下一個 SEQ（任何幀都要經過這裡，控制延遲量測才不會把非 CTRL 的 SEQ 當成 CTRL）。 */
        fun nextSeq(): Int {
            val q = seq.getAndIncrement() and 0xFF
            timing.onSeqAllocated(q)
            return q
        }

        /** 緊急停止 / 停止：進行中與排隊中的 START / SEQ_PLAY 不再送出（見 [CarLink.abortMotionRequests]）。 */
        fun abortMotion() {
            motionEpoch.incrementAndGet()
            txQueue.removeIf { isMotionFrame(it) }
            pending.get()?.let { if (it.motion) it.deferred.cancel() }
        }

        /** 排入傳送佇列並喚醒傳送執行緒（任何執行緒可呼叫）。 */
        fun enqueue(type: Int, payload: ByteArray) {
            txQueue.add(FrameCodec.build(type, nextSeq(), payload))
            txThread?.let { LockSupport.unpark(it) }
        }

        fun enqueueTo(bytes: ByteArray, to: InetSocketAddress) {
            directed.add(Directed(bytes, to))
            txThread?.let { LockSupport.unpark(it) }
        }

        /**
         * 可靠請求：整幀只組一次，逾時重送「同一個 SEQ」的同一份位元組。
         * 車子 / ESP 的回覆幀 SEQ = 請求 SEQ（PROTOCOL §2），因此只接受 SEQ 相同的回覆：
         * 遲到的舊回覆不會被誤認，車子也能把重送辨識為同一個指令（ZERO_CAL / PARAM_SAVE / SEQ_PLAY 不重做）。
         * MOTOR START / SEQ_PLAY：呼叫之後若發生 [abortMotion]（緊急停止 / 停止），不論在排隊或重送中都立即放棄。
         */
        suspend fun request(type: Int, payload: ByteArray, timeoutMs: Long, retries: Int, matcher: (Frame) -> Boolean): Frame? {
            val motion = isMotionCommand(type, payload)
            val epoch = motionEpoch.get()
            return requestMutex.withLock {
                if (motion && motionEpoch.get() != epoch) return@withLock null
                val reqSeq = nextSeq()
                val bytes = FrameCodec.build(type, reqSeq, payload)
                val p = Pending({ f -> f.seq == reqSeq && matcher(f) }, CompletableDeferred(), motion)
                pending.set(p)
                try {
                    for (attempt in 0..retries) {
                        if (!alive || closing.get()) return@withLock null
                        if (motion && motionEpoch.get() != epoch) return@withLock null
                        txQueue.add(bytes)
                        txThread?.let { LockSupport.unpark(it) }
                        val r = withTimeoutOrNull(timeoutMs) { runCatching { p.deferred.await() }.getOrNull() }
                        if (r != null) return@withLock r
                    }
                    null
                } finally {
                    pending.compareAndSet(p, null)
                }
            }
        }
    }

    // ------------------------------------------------------------------ direct (SoftAP)

    private fun startDirect(s: Session) {
        if (!isCurrent(s)) return
        val creds = s.req.credentials
        if (!wm.isWifiEnabled) {
            fail(
                s,
                if (s.req.hotspotExpected) {
                    ConnectionState.Failed("找不到車子：請開啟手機熱點（2.4\u00A0GHz），或開啟 Wi-Fi 直接連線", retryable = true, wifiDisabled = true, hotspotHint = true)
                } else {
                    ConnectionState.Failed("手機的 Wi-Fi 已關閉，請先開啟 Wi-Fi", retryable = true, wifiDisabled = true)
                },
            )
            return
        }
        setStateIfCurrent(s, ConnectionState.RequestingWifi(creds.ssid))
        val request = try {
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(creds.ssid)
                .setWpa2Passphrase(creds.password)
                .build()
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
        } catch (e: IllegalArgumentException) {
            fail(
                s,
                if (s.req.hotspotExpected) ConnectionState.Failed("在手機熱點上找不到車子：請確認手機熱點已開啟（2.4\u00A0GHz）、車子已開機", retryable = true, hotspotHint = true)
                else ConnectionState.Failed("車子名稱或密碼格式不正確", retryable = false),
            )
            return
        }
        val cb = SessionCallback(s)
        s.callback = cb
        try {
            cm.requestNetwork(request, cb, REQUEST_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork failed", e)
            s.callback = null
            fail(s, ConnectionState.Failed("系統拒絕連線請求，請稍後再試", retryable = true))
            return
        }
        // 註冊期間被中斷：closeSession 可能已經清過 callback，自己收掉
        if (s.closing.get()) runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun directUnavailable(s: Session): ConnectionState.Failed = when {
        s.req.hotspotExpected ->
            ConnectionState.Failed("找不到車子：請開啟手機熱點（2.4\u00A0GHz）並確認車子已開機", retryable = true, hotspotHint = true)
        HotspotNet.isPhoneHotspotLikelyOn(app) ->
            ConnectionState.Failed("連不上車子：有些手機開著熱點時不能同時連 Wi-Fi，請關閉手機熱點後再試", retryable = true)
        else -> ConnectionState.Failed("連不上車子：車子未開機、密碼錯誤或已拒絕連線", retryable = true)
    }

    private inner class SessionCallback(private val s: Session) : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!s.alive || s.network != null) return
            s.network = network
            scope.launch { openDirect(s, network) }
        }

        override fun onUnavailable() {
            fail(s, directUnavailable(s))
        }

        override fun onLost(network: Network) {
            if (network == s.network) fail(s, ConnectionState.Failed("車子的 Wi-Fi 中斷了（可能已關機或距離太遠）", retryable = true))
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wi = caps.transportInfo as? android.net.wifi.WifiInfo
                val rssi = wi?.rssi ?: return
                if (rssi > -127 && rssi < 0) listener.onRssi(rssi)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            s.candidates = candidatesFrom(lp)
        }
    }

    private fun candidatesFrom(lp: LinkProperties?): List<InetSocketAddress> {
        val primary = InetSocketAddress(DEFAULT_HOST, DEFAULT_PORT)
        if (lp == null) return listOf(primary)
        val extra = LinkedHashSet<InetAddress>()
        lp.routes.forEach { r -> r.gateway?.let { if (it is Inet4Address && !it.isAnyLocalAddress) extra += it } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.dhcpServerAddress?.let { extra += it }
        return listOf(primary) + extra.filter { it.hostAddress != DEFAULT_HOST }.map { InetSocketAddress(it, DEFAULT_PORT) }
    }

    private suspend fun openDirect(s: Session, network: Network) {
        setStateIfCurrent(s, ConnectionState.Handshaking)
        val sock = try {
            DatagramSocket().also {
                network.bindSocket(it)
                configureSocket(it, SESSION_SO_TIMEOUT_MS)
            }
        } catch (e: IOException) {
            Log.w(TAG, "socket", e)
            fail(s, ConnectionState.Failed("無法建立連線通道，請再試一次", retryable = true))
            return
        }
        s.socket = sock
        s.candidates = candidatesFrom(runCatching { cm.getLinkProperties(network) }.getOrNull())
        acquireWifiLocks(s)
        watchDualWifi(s, network)
        runSession(s, sock, LinkMode.DIRECT)
    }

    /**
     * 低延遲 WifiLock：LOW_LATENCY（App 在前景、螢幕亮時關閉 Wi-Fi 省電）；API < 34 另外持有 HIGH_PERF
     * （34 起已無作用；LOW_LATENCY 失效的機型 / 螢幕關閉時仍可避免省電喚醒的 100~300 ms 抖動）。
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLocks(s: Session) {
        val locks = ArrayList<WifiManager.WifiLock>(2)
        try {
            locks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "B570Pilot:link").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock (low latency)", e)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                locks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "B570Pilot:perf").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "wifi lock (high perf)", e)
            }
        }
        s.wifiLocks = locks
        // 建立期間已被中斷：closeSession 可能已經跑完，自己放掉
        if (s.closing.get() && !s.alive) releaseWifiLocks(s)
    }

    private fun releaseWifiLocks(s: Session) {
        val locks = s.wifiLocks
        s.wifiLocks = emptyList()
        locks.forEach { runCatching { if (it.isHeld) it.release() } }
    }

    /**
     * 直連時，手機還連著另一個有網際網路的 Wi-Fi（Android 12+ 的 STA + STA 併行：家裡 Wi-Fi + 車子）→
     * 兩個頻道不同時無線電要分時切換，控制延遲會變高、忽高忽低。只回報給 UI 提醒，不中斷。
     */
    private fun watchDualWifi(s: Session, ours: Network) {
        val others: MutableSet<Network> = Collections.newSetFromMap(ConcurrentHashMap())
        val cb = object : ConnectivityManager.NetworkCallback() {
            private fun report() {
                val v = others.isNotEmpty()
                if (s.dualWifi.getAndSet(v) != v && isCurrent(s)) listener.onDualWifi(v)
            }

            override fun onAvailable(network: Network) {
                if (network != ours) {
                    others += network
                    report()
                }
            }

            override fun onLost(network: Network) {
                others -= network
                report()
            }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(req, cb)
            s.dualCb = cb
        } catch (e: Exception) {
            Log.w(TAG, "dual wifi watch", e)
            return
        }
        if (s.closing.get() && !s.alive) runCatching { cm.unregisterNetworkCallback(cb) }
    }

    // ------------------------------------------------------------------ hotspot (discovery)

    private fun matchesCar(ack: DiscoverAck, ssid: String): Boolean = ssid.isBlank() || ack.ssid == ssid

    private fun notifyDiscovered(s: Session, ack: DiscoverAck, from: InetSocketAddress) {
        if (session !== s) return
        val ip = from.address?.hostAddress ?: ack.ip
        try {
            listener.onDiscovered(DiscoveredCar(ack.ssid, ip, ack.chipIdHex, ack.isHotspot, ack.busy))
        } catch (t: Throwable) {
            Log.e(TAG, "listener.onDiscovered", t)
        }
    }

    /** 熱點模式：先在手機網路上找車（[LinkRequest.discover]），找到就在同一個 socket 上建立 session；找不到依設定改直連或失敗。 */
    private suspend fun discoverThenConnect(s: Session) {
        val sock = try {
            DatagramSocket().also {
                it.broadcast = true
                configureSocket(it, DISCOVERY_SO_TIMEOUT_MS)
            }
        } catch (e: IOException) {
            Log.w(TAG, "discovery socket", e)
            null
        }
        var found: Pair<DiscoverAck, InetSocketAddress>? = null
        if (sock != null) {
            s.socket = sock
            if (isCurrent(s)) {
                HotspotNet.probe(
                    sock, s.nonce, s.nextSeq(), s.req.discoveryTimeoutMs,
                    targets = { HotspotNet.currentTargets(cm, s.req.lastIp) },
                    shouldStop = { !isCurrent(s) },
                ) { ack, from ->
                    if (matchesCar(ack, s.req.credentials.ssid)) {
                        found = ack to from
                        true
                    } else {
                        false
                    }
                }
            }
        }
        if (!isCurrent(s)) {
            runCatching { sock?.close() }
            return
        }
        val f = found
        if (f != null && sock != null) {
            val (ack, from) = f
            val addr = InetSocketAddress(from.address, from.port)
            s.candidates = listOf(addr)
            val ifaces = HotspotNet.snapshot()
            val iface = from.address?.address?.takeIf { it.size == 4 }?.let { HotspotNet.ifaceForAddress(ifaces, Ipv4.toInt(it)) }
            s.carIfaceHotspot = iface != null && HotspotNet.isHotspotIface(iface, HotspotNet.networkInterfaces(cm))
            notifyDiscovered(s, ack, addr)
            runCatching { sock.soTimeout = SESSION_SO_TIMEOUT_MS }
            runSession(s, sock, LinkMode.HOTSPOT)
            return
        }
        runCatching { sock?.close() }
        s.socket = null
        if (s.req.direct) {
            startDirect(s)
            return
        }
        val hotspotOn = HotspotNet.isPhoneHotspotLikelyOn(app)
        fail(
            s,
            if (!hotspotOn) ConnectionState.Failed("手機熱點沒有開啟：請開啟個人熱點（2.4\u00A0GHz），車子會自動連上", retryable = true, hotspotHint = true)
            else ConnectionState.Failed("在手機熱點上找不到車子：請確認車子已開機，而且設定的是這個熱點", retryable = true, hotspotHint = true),
        )
    }

    /**
     * 熱點模式連線中的看門狗（每 0.5 s）：
     * - 車子所在的介面不見了（使用者關掉熱點）超過 2.5 s → Failed（熱點已關閉）
     * - 車子（STM32）約 1 s 沒資料 → 每 1 s 重新廣播 DISCOVER（車子重開機後 IP 可能變了；DISCOVER_ACK 會更新目標）
     * - 8 s 連 ESP 都沒有任何回應 → Failed（交給 repository 的自動重連重新找車）
     */
    private suspend fun hotspotWatch(s: Session) {
        val probe = FrameCodec.build(MsgType.DISCOVER, s.nextSeq(), Discover(s.nonce).encode())
        var goneSinceNs = 0L
        var lastProbeNs = 0L
        while (s.alive && !s.closing.get()) {
            delay(HOTSPOT_WATCH_MS)
            if (!isCurrent(s)) return
            val now = System.nanoTime()
            val carIp = (s.target ?: s.candidates.firstOrNull())?.address?.address?.takeIf { it.size == 4 } ?: continue
            val ifaces = HotspotNet.snapshot()
            if (HotspotNet.ifaceForAddress(ifaces, Ipv4.toInt(carIp)) == null) {
                if (goneSinceNs == 0L) {
                    goneSinceNs = now
                } else if (now - goneSinceNs > IFACE_GONE_NS) {
                    fail(
                        s,
                        if (s.carIfaceHotspot) ConnectionState.Failed("手機熱點已關閉，和車子的連線中斷了", retryable = true, hotspotHint = true)
                        else ConnectionState.Failed("手機的網路中斷了，和車子的連線中斷了", retryable = true),
                    )
                    return
                }
            } else {
                goneSinceNs = 0L
            }
            if (s.phase != Phase.RUNNING) continue
            if (now - s.lastAnyRxNs > HOTSPOT_FAIL_NS) {
                fail(s, ConnectionState.Failed("車子沒有回應了（可能已關機、離開熱點範圍或熱點已關閉）", retryable = true, hotspotHint = true))
                return
            }
            if (now - s.lastCarRxNs > HELLO_RESEND_NS && now - lastProbeNs > REDISCOVER_NS) {
                lastProbeNs = now
                for (ip in HotspotNet.discoveryTargets(ifaces, HotspotNet.networkInterfaces(cm), Ipv4.format(carIp))) {
                    val a = Ipv4.parse(ip) ?: continue
                    s.enqueueTo(probe, InetSocketAddress(InetAddress.getByAddress(a), DEFAULT_PORT))
                }
            }
        }
    }

    /** 連線中收到 DISCOVER_ACK（重新找車）：車子換了 IP 就改送新位址（之後的 HELLO 重送會重建 session）。 */
    private fun onRediscovered(s: Session, f: Frame, pkt: DatagramPacket) {
        if (s.mode != LinkMode.HOTSPOT) return
        val ack = DiscoverAck.decode(f.payload)
        if (ack.nonce != s.nonce || !matchesCar(ack, s.req.credentials.ssid)) return
        val addr = InetSocketAddress(pkt.address, pkt.port)
        val cur = s.target ?: s.candidates.firstOrNull()
        if (cur == null || cur.address != addr.address || cur.port != addr.port) {
            Log.i(TAG, "car moved to ${addr.address?.hostAddress}")
            s.candidates = listOf(addr)
            s.target = addr
        }
        notifyDiscovered(s, ack, addr)
    }

    // ------------------------------------------------------------------ session (both modes)

    private fun configureSocket(sock: DatagramSocket, soTimeoutMs: Int) {
        sock.soTimeout = soTimeoutMs
        runCatching { sock.receiveBufferSize = 64 * 1024 }
        // 傳送緩衝縮到最小（核心會調到下限約 4 KB ≈ 幾個封包）：Wi-Fi 變慢時 send() 會等，
        // 而不是讓數百個舊 CTRL 在手機排隊（那會造成好幾秒的控制延遲）；等到時送出的是當下最新的搖桿值
        runCatching { sock.sendBufferSize = SEND_BUFFER_BYTES }
        runCatching { sock.trafficClass = 0xB8 } // DSCP EF：Wi-Fi WMM 語音佇列，降低延遲
    }

    private suspend fun runSession(s: Session, sock: DatagramSocket, mode: LinkMode) {
        setStateIfCurrent(s, ConnectionState.Handshaking)
        s.socket = sock
        s.mode = mode
        if (!s.alive || s.closing.get()) {
            // 建立期間已被中斷：closeSession 可能已經清理過，這裡自己收掉，避免 WifiLock / socket 洩漏
            runCatching { sock.close() }
            releaseWifiLocks(s)
            return
        }
        val now = System.nanoTime()
        s.lastAnyRxNs = now
        s.phase = Phase.HANDSHAKE
        s.rxThread = Thread({ rxLoop(s, sock) }, "B570-rx").apply { isDaemon = true; start() }
        s.txThread = Thread({ txLoop(s, sock) }, "B570-tx").apply { isDaemon = true; start() }
        if (mode == LinkMode.DIRECT && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) scope.launch { legacyRssiLoop(s) }
        if (mode == LinkMode.HOTSPOT) scope.launch { hotspotWatch(s) }

        val ack = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { runCatching { s.helloAck.await() }.getOrNull() }
        if (!s.alive || s.closing.get()) return
        if (ack == null) {
            fail(
                s,
                if (mode == LinkMode.HOTSPOT) ConnectionState.Failed("在熱點上找到車子，但車子沒有回應。請重新開機後再試", retryable = true)
                else ConnectionState.Failed("已連上車子的 Wi-Fi，但車子沒有回應。請重新開機後再試", retryable = true),
            )
            return
        }
        if (ack.isBusy) {
            fail(s, ConnectionState.Failed("另一支手機正在控制這台車", retryable = true))
            return
        }
        s.phase = Phase.SETUP
        val info = s.request(InfoGet.type, InfoGet.encode(), REQ_TIMEOUT_MS, REQ_RETRIES) { it.type == MsgType.INFO }
            ?.let { f -> try { Info.decode(f.payload) } catch (e: ProtocolException) { null } }
        s.request(MsgType.PARAM_GET, ParamGet().encode(), REQ_TIMEOUT_MS, REQ_RETRIES) {
            it.type == MsgType.PARAM_VALUES && it.payload.isNotEmpty() && (it.payload[0].toInt() and 0xFF) > 1
        }
        if (!s.alive || s.closing.get()) return
        s.info = info ?: s.info
        s.connectedSince = System.currentTimeMillis()
        val t = System.nanoTime()
        s.lastCarRxNs = maxOf(s.lastCarRxNs, t)
        s.lastAnyRxNs = maxOf(s.lastAnyRxNs, t)
        s.runningSinceNs = t
        s.phase = Phase.RUNNING
        if (isCurrent(s)) listener.onLinkMode(mode)
        setStateIfCurrent(s, ConnectionState.Connected(info, s.connectedSince))
    }

    // ------------------------------------------------------------------ threads

    /** 收發執行緒用最高的一般 App 優先權（像音訊執行緒）：每 20 ms 醒來一下、工作很少，被排程延誤就是延遲。 */
    private fun boostThread() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        }
    }

    private fun rxLoop(s: Session, sock: DatagramSocket) {
        boostThread()
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        val parser = FrameParser()
        var lastCrc = 0L
        while (s.alive) {
            try {
                pkt.setData(buf, 0, buf.size)
                sock.receive(pkt)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (!s.alive || sock.isClosed) break
                SystemClock.sleep(20)
                continue
            }
            // 收到的時間：控制延遲以這一刻為準（解析與 listener 的處理時間不算進去）
            val rxNs = System.nanoTime()
            parser.feed(buf, 0, pkt.length) { f -> handleFrame(s, f, pkt, rxNs) }
            if (parser.crcErrors != lastCrc) {
                lastCrc = parser.crcErrors
                listener.onCrcErrors(lastCrc)
            }
        }
    }

    private fun handleFrame(s: Session, f: Frame, pkt: DatagramPacket, rxNs: Long) {
        // stop()/fail() 已同步標記：舊連線最後幾個幀（BYE 送出前的 60 ms 內）不再交給 repository，
        // 否則會在 resetSession() / Idle 清除後把舊車的遙測寫回畫面。
        if (s.closing.get() || session !== s) return
        s.lastAnyRxNs = rxNs
        if (MsgType.isFromCar(f.type)) s.lastCarRxNs = rxNs
        try {
            when (f.type) {
                MsgType.TELEM -> onTelemetryTiming(s, f.payload, rxNs)
                MsgType.HELLO_ACK -> {
                    val a = HelloAck.decode(f.payload)
                    if (a.isOk && s.target == null) s.target = InetSocketAddress(pkt.address, pkt.port)
                    if (s.phase == Phase.RUNNING && a.isBusy) {
                        fail(s, ConnectionState.Failed("另一支手機已接手控制這台車", retryable = true))
                    }
                    s.helloAck.complete(a)
                }
                // 主控板重開機後 repository 會重讀 INFO；記下來，失聯恢復時 Connected(info) 才不會帶回舊資料
                MsgType.INFO -> s.info = Info.decode(f.payload)
                MsgType.PONG -> {
                    val rtt = (nowMs32() - Pong.decode(f.payload).t) and 0xFFFF_FFFFL
                    if (rtt < 10_000) {
                        s.rttEmaMs = if (s.rttEmaMs.isNaN()) rtt.toFloat() else s.rttEmaMs * 0.7f + rtt * 0.3f
                        listener.onRtt(rtt.toFloat())
                    }
                }
                MsgType.ESP_PONG -> {
                    val rtt = (nowMs32() - EspPong.decode(f.payload).t) and 0xFFFF_FFFFL
                    if (rtt < 10_000) listener.onEspRtt(rtt.toFloat())
                }
                // 熱點模式沒有 Network 可讀手機端 RSSI：用車子回報的熱點訊號
                MsgType.STA_INFO -> if (s.mode == LinkMode.HOTSPOT) {
                    StaInfo.decode(f.payload).rssiDbm?.let { if (it in -126..-1) listener.onRssi(it) }
                }
                MsgType.DISCOVER_ACK -> onRediscovered(s, f, pkt)
            }
        } catch (e: ProtocolException) {
            Log.w(TAG, "bad ${MsgType.name(f.type)} payload")
        }
        s.pending.get()?.let { p -> if (p.matcher(f)) p.deferred.complete(f) }
        try {
            listener.onFrame(f)
        } catch (t: Throwable) {
            Log.e(TAG, "listener.onFrame", t)
        }
    }

    /**
     * 每筆遙測：控制延遲樣本（v1.1 的 ctrl_seq / ctrl_age）+ 主控板每次結算 link_q 時一個上行樣本
     * （對齊到主控板那 1 秒，見 [LinkQSettleTracker]；手機送出速率會隨 [ControlPacer] 變，不對齊會算出假遺失）。零配置。
     */
    private fun onTelemetryTiming(s: Session, p: ByteArray, rxNs: Long) {
        if (p.size < Telemetry.SIZE) return
        // 相位在握手 / 讀參數期間就開始學（遙測一直在來）
        val q = p[35].toInt() and 0xFF
        val settleAge = s.settle.onTelemetry(Le.u16(p, 0), q)
        if (s.phase != Phase.RUNNING) return
        if (p.size >= Telemetry.SIZE_V11) {
            val ms = s.timing.latencyMs(p[40].toInt() and 0xFF, p[41].toInt() and 0xFF, rxNs)
            if (!ms.isNaN()) {
                s.pacer.onLatencySample(ms)
                s.delayEmaMs = if (s.delayEmaMs.isNaN()) ms else s.delayEmaMs * 0.9f + ms * 0.1f
                listener.onControlLatency(ms)
            }
        }
        if (settleAge >= 0 && rxNs - s.runningSinceNs > UPLINK_WARMUP_NS) {
            // 上行 + 下行延遲：用控制延遲（v1.1）；舊韌體用 PING 來回；都沒有就 10 ms
            val delayMs = s.delayEmaMs.takeUnless { it.isNaN() } ?: s.rttEmaMs.takeUnless { it.isNaN() } ?: 10f
            listener.onUplinkSample(s.timing.carFramesForSettle(rxNs, settleAge, (delayMs * 1_000_000f).toLong()), q)
        }
    }

    /** 傳送執行緒自己的 buffer（週期性的幀原地組幀、DatagramPacket 重複使用）。 */
    private class TxBuffers {
        val ctrl = ByteArray(FrameCodec.OVERHEAD + Ctrl.SIZE)
        val ctrlPkt = DatagramPacket(ctrl, ctrl.size)
        val small = ByteArray(FrameCodec.OVERHEAD + 4)
        val smallPkt = DatagramPacket(small, small.size)
        val pkt = DatagramPacket(ByteArray(0), 0)
    }

    private fun txLoop(s: Session, sock: DatagramSocket) {
        boostThread()
        val tx = TxBuffers()
        var next = System.nanoTime()
        var tick = 0L
        while (s.alive) {
            drain(s, sock, tx)
            val now = System.nanoTime()
            val wait = next - now
            if (wait > 0) {
                // 有新的幀排隊時會被 unpark 提早叫醒（立刻送出），之後再睡到下一個 20 ms 刻度
                LockSupport.parkNanos(wait)
                continue
            }
            // 絕對時間排程：下一個刻度 = 上一個 + 20 ms（不受這次送出花多久影響，不會累積漂移）
            next += PERIOD_NS
            if (now - next > PERIOD_NS * 5) next = now + PERIOD_NS // 落後太多（休眠喚醒）→ 重新對齊
            try {
                txTick(s, sock, tx, tick, now)
            } catch (t: Throwable) {
                Log.w(TAG, "txTick", t)
            }
            tick++
        }
        drain(s, sock, tx)
    }

    private fun txTick(s: Session, sock: DatagramSocket, tx: TxBuffers, tick: Long, now: Long) {
        when (s.phase) {
            Phase.WAIT_NETWORK, Phase.SETUP -> Unit
            Phase.HANDSHAKE -> if (tick % HELLO_EVERY_HANDSHAKE == 0L) sendHello(s, sock, tx, all = true)
            Phase.RUNNING -> {
                // 每個週期第一件事：CTRL（心跳兼控制），其他東西都排在它後面。
                // 由 ControlPacer 決定送不送：靜止 / 壅塞時降頻，舊指令不會在手機裡排隊
                if (s.pacer.shouldSend(tick, control.get())) sendCtrl(s, sock, tx)
                if (tick % PING_EVERY == 0L) sendSmall(s, sock, tx, MsgType.PING, nowMs32(), true, targetOf(s))
                if (tick % ESP_PING_EVERY == 37L) sendSmall(s, sock, tx, MsgType.ESP_PING, nowMs32(), true, targetOf(s))
                if (tick % WIFI_GET_EVERY == 74L) sendSmall(s, sock, tx, MsgType.WIFI_GET, 0, false, targetOf(s))
                if (s.mode == LinkMode.HOTSPOT && tick % STA_GET_EVERY == 112L) sendSmall(s, sock, tx, MsgType.STA_GET, 0, false, targetOf(s))

                val silentNs = now - s.lastCarRxNs
                val st = _state.value
                // §3.4 規則 4：約 1 秒收不到回應就重送 HELLO（ESP session 可能已逾時或 ESP 重開機）
                if (silentNs > HELLO_RESEND_NS && tick % HELLO_EVERY == 0L) sendHello(s, sock, tx, all = false)
                if (silentNs > LINK_LOST_NS) {
                    if (st is ConnectionState.Connected) setStateIfCurrent(s, ConnectionState.LinkLost(System.currentTimeMillis()))
                } else if (st is ConnectionState.LinkLost) {
                    setStateIfCurrent(s, ConnectionState.Connected(s.info, s.connectedSince))
                }
            }
        }
    }

    private fun targetOf(s: Session): InetSocketAddress = s.target ?: s.candidates.first()

    /** CTRL：在 [TxBuffers.ctrl] 原地組幀（零配置），送出前一刻記下送出時間（控制延遲量測）。 */
    private fun sendCtrl(s: Session, sock: DatagramSocket, tx: TxBuffers) {
        val c = control.get()
        Ctrl.writePayload(tx.ctrl, 5, c shr 16, (c and 0xFFFF).toShort().toInt())
        val seq = s.nextSeq()
        val len = FrameCodec.finishInto(tx.ctrl, MsgType.CTRL, seq, Ctrl.SIZE)
        tx.ctrlPkt.setData(tx.ctrl, 0, len)
        tx.ctrlPkt.socketAddress = targetOf(s)
        val t = System.nanoTime()
        s.timing.onCtrlSent(seq, t)
        s.timing.onCarFrameSent(t)
        rawSend(sock, tx.ctrlPkt)
    }

    /** payload 為空或一個 u32 的小幀（PING / ESP_PING / WIFI_GET / STA_GET / HELLO），原地組幀。 */
    private fun sendSmall(s: Session, sock: DatagramSocket, tx: TxBuffers, type: Int, value: Long, hasValue: Boolean, to: InetSocketAddress) {
        val payloadLen = if (hasValue) { Le.putU32(tx.small, 5, value); 4 } else 0
        val len = FrameCodec.finishInto(tx.small, type, s.nextSeq(), payloadLen)
        tx.smallPkt.setData(tx.small, 0, len)
        tx.smallPkt.socketAddress = to
        if (type < 0x40) s.timing.onCarFrameSent(System.nanoTime())
        rawSend(sock, tx.smallPkt)
    }

    private fun sendHello(s: Session, sock: DatagramSocket, tx: TxBuffers, all: Boolean) {
        val tgt = s.target
        if (tgt != null && !all) {
            sendSmall(s, sock, tx, MsgType.HELLO, s.sessionId, true, tgt)
        } else {
            for (addr in s.candidates) sendSmall(s, sock, tx, MsgType.HELLO, s.sessionId, true, addr)
        }
    }

    private fun drain(s: Session, sock: DatagramSocket, tx: TxBuffers) {
        while (true) {
            val b = s.txQueue.poll() ?: break
            if (b.size > 3 && (b[3].toInt() and 0xFF) < 0x40) s.timing.onCarFrameSent(System.nanoTime())
            tx.pkt.setData(b)
            tx.pkt.socketAddress = targetOf(s)
            rawSend(sock, tx.pkt)
        }
        while (true) {
            val d = s.directed.poll() ?: break
            tx.pkt.setData(d.bytes)
            tx.pkt.socketAddress = d.to
            rawSend(sock, tx.pkt)
        }
    }

    private fun rawSend(sock: DatagramSocket, pkt: DatagramPacket) {
        try {
            sock.send(pkt)
        } catch (e: IOException) {
            // 網路暫時不可用（例如正在漫遊）；由 watchdog 處理失聯
        } catch (e: IllegalArgumentException) {
            // 位址不合法（不應發生）
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun legacyRssiLoop(s: Session) {
        // API 29/30：NetworkCapabilities.transportInfo 不是 WifiInfo，改輪詢 connectionInfo
        while (s.alive && !s.closing.get()) {
            runCatching { wm.connectionInfo?.rssi }.getOrNull()?.let { if (it > -127 && it < 0) listener.onRssi(it) }
            delay(3000)
        }
    }

    private fun nowMs32(): Long = SystemClock.elapsedRealtime() and 0xFFFF_FFFFL

    companion object {
        private const val TAG = "B570Link"
        const val DEFAULT_HOST = "192.168.4.1"
        const val DEFAULT_PORT = 8888
        const val REQUEST_TIMEOUT_MS = 30_000
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        const val REQ_TIMEOUT_MS = 400L
        const val REQ_RETRIES = 3
        private const val SESSION_SO_TIMEOUT_MS = 200
        private const val DISCOVERY_SO_TIMEOUT_MS = 50
        private const val PERIOD_NS = 20_000_000L // 50 Hz
        /** socket 傳送緩衝（核心有下限，實際約 4 KB）。 */
        private const val SEND_BUFFER_BYTES = 2048
        private const val HELLO_EVERY = 15L // 300 ms
        /** 握手階段每 100 ms 送一次 HELLO：Wi-Fi 剛連上時第一個封包常掉，縮短等待（加快連線）。 */
        private const val HELLO_EVERY_HANDSHAKE = 5L
        private const val PING_EVERY = 25L // 500 ms
        private const val ESP_PING_EVERY = 100L // 2 s
        private const val WIFI_GET_EVERY = 150L // 3 s
        /** 熱點模式每 3 s 讀一次 STA_INFO（車子收到的熱點訊號 = 狀態條的訊號格）。 */
        private const val STA_GET_EVERY = 150L
        private const val HELLO_RESEND_NS = 1_000_000_000L
        private const val LINK_LOST_NS = 1_500_000_000L
        private const val HOTSPOT_WATCH_MS = 500L
        private const val IFACE_GONE_NS = 2_500_000_000L
        private const val HOTSPOT_FAIL_NS = 8_000_000_000L
        private const val REDISCOVER_NS = 1_000_000_000L
        /** RUNNING 後這麼久才開始算上行遺失（握手 / 讀參數期間手機幾乎不送主控板幀，那幾秒的 link_q 不具代表性）。 */
        private const val UPLINK_WARMUP_NS = 2_500_000_000L
    }
}
