package com.b570.pilot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 360° 搖桿成為預設：舊版存的「雙搖桿」一次性改成 360°，之後使用者的選擇照常保存。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JoystickModeMigrationTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun prefs() = app.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    @Test fun freshInstallDefaultsTo360() {
        assertEquals(JoystickMode.SINGLE, AppSettings().joystickMode)
        assertEquals(JoystickMode.SINGLE, SettingsStore(app).settings.value.joystickMode)
        assertEquals("360° 搖桿", JoystickMode.SINGLE.labelZh)
        // 選擇順序：360° 第一個
        assertEquals(JoystickMode.SINGLE, JoystickMode.entries.first())
    }

    @Test fun oldDualDefaultMigratesOnceThenChoiceSticks() {
        // 舊版：任何設定變更都會把整份設定（含 joy = DUAL 預設）寫回
        prefs().edit().putString("joy", "DUAL").putFloat("expo", 0.5f).commit()
        val s1 = SettingsStore(app)
        assertEquals(JoystickMode.SINGLE, s1.settings.value.joystickMode)
        assertEquals(0.5f, s1.settings.value.expo, 0f) // 其他設定保留

        // 之後使用者改回雙搖桿 → 下次啟動仍是雙搖桿（不會再被遷移蓋掉）
        s1.update { it.copy(joystickMode = JoystickMode.DUAL) }
        assertFalse(prefs().contains("joy"))
        assertEquals(JoystickMode.DUAL, SettingsStore(app).settings.value.joystickMode)

        s1.update { it.copy(joystickMode = JoystickMode.TILT) }
        assertEquals(JoystickMode.TILT, SettingsStore(app).settings.value.joystickMode)
    }
}
