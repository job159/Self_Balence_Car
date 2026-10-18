package com.b570.pilot.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.b570.pilot.data.CarRepository
import com.b570.pilot.data.LaunchRequest
import com.b570.pilot.link.ConnectionState
import com.b570.pilot.link.WifiCredentials
import com.b570.pilot.protocol.StopReason
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 快速連線 / 自動重連的安全規則（用展示車走真協定）：
 * - 桌面捷徑不會丟下一台正在運轉的車去連別台；
 * - 斷線 / 自動重連中按的緊急停止，連回同一台車時立刻補送（不會被默默丟掉）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickConnectSafetyTest {

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            idleMain()
            check(System.currentTimeMillis() < end) { "condition not met in $timeoutMs ms" }
            Thread.sleep(20)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(repo: CarRepository, name: String): MutableStateFlow<T> =
        repo.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(repo) as MutableStateFlow<T>

    @Test(timeout = 60_000)
    fun shortcutDoesNotAbandonARunningCar() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app)
        val b = WifiCredentials("B570-BBBB", "password12")
        vm.saveCar(b)
        vm.connectDemo()
        waitFor { vm.telemetry.value?.motorsOn == true }
        // 把展示車當成真車 A：已連線、馬達運轉中
        field<Boolean>(vm.repo, "_isDemo").value = false
        field<WifiCredentials?>(vm.repo, "_currentCar").value = WifiCredentials("B570-AAAA", "password12")
        idleMain()

        // 桌面捷徑「連線 B」：不切換（A 中斷後會一直原地平衡，手機就停不了它）
        vm.handleLaunchRequest(LaunchRequest.ConnectCar(b.ssid))
        idleMain()
        assertNull(vm.pendingConnect.value)
        assertTrue(vm.connectionState.value is ConnectionState.Connected)

        // 停下來之後照常切換（交給連線頁做權限 / Wi-Fi 檢查）
        vm.emergencyStop()
        waitFor { vm.telemetry.value?.motorsOn == false }
        vm.handleLaunchRequest(LaunchRequest.ConnectCar(b.ssid))
        idleMain()
        assertEquals(b, vm.pendingConnect.value)
        vm.disconnect()
    }

    @Test(timeout = 60_000)
    fun estopPressedWhileDisconnectedIsSentOnReconnect() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = CarRepository.get(app)
        repo.connectDemo()
        waitFor { repo.telemetry.value?.motorsOn == true }
        repo.disconnect()
        waitFor { repo.connectionState.value == ConnectionState.Idle }

        // 斷線中（例如自動重連中）按緊急停止：現在送不到
        repo.emergencyStop()

        // 連回同一台：展示車約 1.2 s 後會自動啟動 → 一連上就補送緊急停止，馬達維持停止、原因 = 緊急停止
        repo.retry()
        waitFor { repo.connectionState.value is ConnectionState.Connected }
        waitFor { repo.telemetry.value?.stopReason == StopReason.ESTOP }
        Thread.sleep(2_000)
        val t = repo.telemetry.value!!
        assertFalse(t.motorsOn)
        assertEquals(StopReason.ESTOP, t.stopReason)
        repo.disconnect()
    }
}
