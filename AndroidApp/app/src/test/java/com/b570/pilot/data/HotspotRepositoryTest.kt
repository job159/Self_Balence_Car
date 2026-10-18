package com.b570.pilot.data

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.LinkMode
import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.protocol.AckStatus
import com.b570.pilot.protocol.Discover
import com.b570.pilot.protocol.DiscoverAck
import com.b570.pilot.protocol.FrameCodec
import com.b570.pilot.protocol.FrameParser
import com.b570.pilot.protocol.MsgType
import com.b570.pilot.protocol.Telemetry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Repository 串起來（用展示車走真協定）：LinkStats 的控制延遲 / 上行遺失、熱點模式設定、切換提示、掃描熱點上的車、
 * 記住的車的熱點欄位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HotspotRepositoryTest {

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            idleMain()
            check(System.currentTimeMillis() < end) { "condition not met in $timeoutMs ms" }
            Thread.sleep(20)
        }
    }

    @Test(timeout = 60_000)
    fun linkStatsReportControlLatencyAndUplinkLoss() {
        val repo = CarRepository.get(ApplicationProvider.getApplicationContext<Application>())
        repo.connectDemo()
        waitFor { repo.connectionState.value is ConnectionState.Connected }
        waitFor { repo.linkStats.value.controlLatencyMs != null && repo.linkStats.value.uplinkLossPercent != null }
        val s = repo.linkStats.value
        assertTrue("latency ${s.controlLatencyMs}", s.controlLatencyMs!! in 15f..40f)
        waitFor { repo.linkStats.value.controlLatencyP95Ms != null }
        assertTrue(repo.linkStats.value.controlLatencyP95Ms!! >= 15f)
        assertTrue(s.uplinkLossPercent!! in 0f..5f)
        assertEquals(s.controlLatencyMs, s.displayLatencyMs)
        assertEquals(LinkMode.DIRECT, repo.linkMode.value)
        assertEquals(Telemetry.SIZE_V11, repo.telemetry.value!!.encode().size)
        repo.disconnect()
        waitFor { repo.connectionState.value == ConnectionState.Idle }
        assertNull(repo.linkMode.value)
    }

    @Test(timeout = 60_000)
    fun hotspotSettingsInDemo() = runBlocking {
        val repo = CarRepository.get(ApplicationProvider.getApplicationContext<Application>())
        repo.connectDemo()
        waitFor { repo.connectionState.value is ConnectionState.Connected }
        // 連上後自動讀一次 STA_INFO
        waitFor { repo.staInfo.value != null }
        assertEquals(true, repo.hotspotSupported.value)
        assertEquals(false, repo.staInfo.value!!.enabled)

        // 檢查不合法的熱點設定（不送出）
        assertEquals(AckStatus.BAD_ARG, repo.setHotspot(true, "", "", notify = false))
        assertEquals(AckStatus.BAD_ARG, repo.setHotspot(true, "MyPhone", "1234", notify = false))

        assertEquals(AckStatus.OK, repo.setHotspot(true, "MyPhone", "pass1234", notify = false))
        waitFor { repo.linkMode.value == LinkMode.HOTSPOT }
        waitFor { repo.staInfo.value?.enabled == true }
        // 展示：切換提示走一遍（重新開機中 → 尋找中 → 連上）
        waitFor(5_000) { repo.hotspotSwitch.value == null }
        assertTrue(repo.connectionState.value is ConnectionState.Connected)

        val found = repo.discoverHotspotCars(500)
        assertEquals(1, found.size)
        assertEquals(DemoTelemetrySource.DEMO_CREDS.ssid, found[0].ssid)
        assertTrue(found[0].viaHotspot)

        assertEquals(AckStatus.OK, repo.setHotspot(false, notify = false))
        waitFor { repo.linkMode.value == LinkMode.DIRECT }
        // 展示模式不寫入記住的車
        assertNull(repo.savedCars.value.firstOrNull { it.ssid == DemoTelemetrySource.DEMO_CREDS.ssid })
        repo.disconnect()
    }

    /** 進度對話框顯示中（設定熱點的對話框）：切換過程不再另外跳出「重新開機中 / 完成 / 已連線」提示。 */
    @Test(timeout = 60_000)
    fun switchNoticesQuietWhileProgressDialogShows() = runBlocking {
        val repo = CarRepository.get(ApplicationProvider.getApplicationContext<Application>())
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            repo.events.collect { seen.add(it.messageZh) }
        }
        try {
            repo.connectDemo()
            waitFor { repo.connectionState.value is ConnectionState.Connected }
            waitFor { repo.hotspotSupported.value == true }

            repo.switchProgressOnScreen = true
            seen.clear()
            assertEquals(AckStatus.OK, repo.setHotspot(true, "MyPhone", "pass1234", notify = false))
            // 等切換流程真的開始再結束（展示車約 2.4 s），完成提示若有送出一定已經送出
            waitFor { repo.hotspotSwitch.value != null }
            waitFor { repo.linkMode.value == LinkMode.HOTSPOT && repo.hotspotSwitch.value == null }
            Thread.sleep(300)
            assertTrue(seen.toString(), seen.none { it.contains("接著會加入手機熱點") || it.startsWith("完成！") })

            // 對話框關掉後（在背景繼續）：完成提示照常跳出
            repo.switchProgressOnScreen = false
            assertEquals(AckStatus.OK, repo.setHotspot(false, notify = false))
            waitFor { repo.linkMode.value == LinkMode.DIRECT }
            seen.clear()
            assertEquals(AckStatus.OK, repo.setHotspot(true, "MyPhone", "pass1234", notify = true))
            waitFor { seen.any { it.startsWith("完成！") } }
        } finally {
            repo.switchProgressOnScreen = false
            job.cancel()
            repo.disconnect()
        }
    }

    /**
     * 離開展示模式後 isDemo 仍為 true（「重試」要回到展示），但連線頁「熱點上的車」要找真車：
     * 以前會去問已停止的展示車而永遠回傳空清單。這裡在 127.0.0.1:8888 放一台假車（記住的車 lastIp = 127.0.0.1）。
     */
    @Test(timeout = 60_000)
    fun discoverAfterLeavingDemoScansRealNetwork() = runBlocking {
        val sock = try {
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 8888)); soTimeout = 100 }
        } catch (e: Exception) {
            null
        }
        assumeTrue("UDP 127.0.0.1:8888 busy on this machine", sock != null)
        val fake = Thread {
            val buf = ByteArray(512)
            val pkt = DatagramPacket(buf, buf.size)
            val parser = FrameParser(512)
            while (!sock!!.isClosed) {
                try {
                    pkt.setData(buf, 0, buf.size)
                    sock.receive(pkt)
                } catch (e: Exception) {
                    continue
                }
                parser.reset()
                for (f in parser.feed(buf, 0, pkt.length)) {
                    if (f.type != MsgType.DISCOVER) continue
                    val nonce = Discover.decode(f.payload).nonce
                    val ack = DiscoverAck(nonce, 0x00C0FFEEL, DiscoverAck.MODE_HOTSPOT, "127.0.0.1", false, "B570-FAKE")
                    val out = FrameCodec.build(MsgType.DISCOVER_ACK, f.seq, ack.encode())
                    runCatching { sock.send(DatagramPacket(out, out.size, pkt.socketAddress)) }
                }
            }
        }.apply { isDaemon = true; start() }
        val repo = CarRepository.get(ApplicationProvider.getApplicationContext<Application>())
        try {
            repo.saveCar(WifiCredentials("B570-FAKE", "pass1234"))
            repo.javaClass.getDeclaredField("carsStore").apply { isAccessible = true }.get(repo).let { store ->
                (store as SavedCarsStore).updateHotspot("B570-FAKE", hotspotMode = true, lastIp = "127.0.0.1")
            }
            repo.connectDemo()
            waitFor { repo.connectionState.value is ConnectionState.Connected }
            assertEquals(listOf(DemoTelemetrySource.DEMO_CREDS.ssid), repo.discoverHotspotCars(300).map { it.ssid })
            repo.disconnect()
            waitFor { repo.connectionState.value == ConnectionState.Idle }
            assertTrue(repo.isDemo.value)
            val found = repo.discoverHotspotCars(800)
            assertTrue("found $found", found.any { it.ssid == "B570-FAKE" && it.viaHotspot })
            assertTrue(found.none { it.ssid == DemoTelemetrySource.DEMO_CREDS.ssid })
        } finally {
            sock!!.close()
            fake.join(1_000)
            repo.forgetCar("B570-FAKE")
        }
    }

    @Test(timeout = 30_000)
    fun planUsesSavedHotspotMode() {
        val repo = CarRepository.get(ApplicationProvider.getApplicationContext<Application>())
        val creds = WifiCredentials("B570-HSPT", "pass1234")
        repo.saveCar(creds)
        assertEquals(repo.planFor(creds).hotspotExpected, false)
        // 找到 / STA_INFO 回報熱點模式之後，下一次連線先在熱點上找
        repo.javaClass.getDeclaredField("carsStore").apply { isAccessible = true }.get(repo).let { store ->
            (store as SavedCarsStore).updateHotspot(creds.ssid, hotspotMode = true, lastIp = "192.168.43.57", hotspotSsid = "MyPhone")
        }
        val car = repo.savedCars.value.first { it.ssid == creds.ssid }
        assertTrue(car.hotspotMode)
        assertEquals("192.168.43.57", car.lastIp)
        assertEquals("MyPhone", car.hotspotSsid)
        val plan = repo.planFor(creds)
        assertTrue(plan.discover)
        assertTrue(plan.direct)
        assertTrue(plan.hotspotExpected)
        // 重新掃 QR（upsert）不會洗掉熱點設定
        repo.saveCar(creds)
        assertNotNull(repo.savedCars.value.first { it.ssid == creds.ssid }.lastIp)
        assertTrue(repo.savedCars.value.first { it.ssid == creds.ssid }.hotspotMode)
        repo.forgetCar(creds.ssid)
    }
}
