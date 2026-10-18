package com.b570.pilot.ui.shell

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.b570.pilot.ui.AppViewModel
import com.b570.pilot.ui.components.ConfirmDialog
import com.b570.pilot.ui.screens.connect.HOTSPOT_CAR_TITLE_ZH
import com.b570.pilot.ui.screens.connect.PermDialog
import com.b570.pilot.ui.screens.connect.PermissionDialog
import com.b570.pilot.ui.screens.connect.hotspotCarConfirmZh
import com.b570.pilot.ui.screens.connect.wifiFirstPairTitleZh
import com.b570.pilot.ui.screens.settings.HotspotEditDialog
import com.b570.pilot.ui.theme.B570Theme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 版面：最小的手機橫放（640 × 320 dp、字級 1.3）與一般手機（780 × 360 dp），
 * 「第一次配對要開一下 Wi-Fi」（含重新掃描的「要開一下 Wi-Fi」）、「車子沒找到手機熱點」、「帶出去玩：改用熱點模式」、
 * 熱點表單（含「查看手機熱點名稱 / 密碼」）的按鈕都完整看得到
 * （沒有被對話框的捲動區裁掉）。字級要在 Activity 啟動前設定（對話框視窗用 Activity 的設定）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h320dp-land-xhdpi")
class HotspotOnboardingUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @After fun tearDown() {
        scenario?.close()
    }

    private fun show(fontScale: Float, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { a ->
            a.setContent { B570Theme { content() } }
        }
        rule.waitForIdle()
    }

    /** [text] 這個節點完整在畫面內（沒有被對話框的捲動區裁掉）。 */
    private fun assertFullyVisible(text: String) {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val clipped = node.boundsInWindow
        assertTrue("「$text」被裁掉：可見 ${clipped.height} / 實際 ${node.size.height}", clipped.height >= node.size.height - 1f)
    }

    private fun firstPairDialog(fontScale: Float, phoneHotspotOn: Boolean, promise: Boolean = true) {
        show(fontScale) {
            PermissionDialog(
                PermDialog.WIFI_FIRST_PAIR, connectMissing = false, onConfirm = {}, onDismiss = {},
                phoneHotspotOn = phoneHotspotOn, firstPairPromise = promise,
            )
        }
        assertFullyVisible(wifiFirstPairTitleZh(promise))
        assertFullyVisible("開啟 Wi-Fi")
        assertFullyVisible("取消")
    }

    private fun hotspotCarDialog(fontScale: Float, phoneHotspotOn: Boolean) {
        show(fontScale) {
            PermissionDialog(PermDialog.HOTSPOT_CAR, connectMissing = false, onConfirm = {}, onDismiss = {}, phoneHotspotOn = phoneHotspotOn)
        }
        assertFullyVisible(HOTSPOT_CAR_TITLE_ZH)
        assertFullyVisible(hotspotCarConfirmZh(phoneHotspotOn))
        assertFullyVisible("取消")
    }

    private fun onboardingPrompt(fontScale: Float) {
        show(fontScale) {
            ConfirmDialog(
                title = "帶出去玩：改用熱點模式",
                message = "以後只要打開手機的個人熱點，車子會自己連上你的手機。不需要任何 Wi-Fi 網路，手機 Wi-Fi 可以關著，照常用行動網路上網。只要設定這一次。",
                confirmText = "設定熱點模式",
                dismissText = "先不用",
                onConfirm = {},
                onDismiss = {},
            )
        }
        assertFullyVisible("設定熱點模式")
        assertFullyVisible("先不用")
    }

    private fun hotspotForm(fontScale: Float) {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        show(fontScale) {
            HotspotEditDialog(
                vm = vm, initialSsid = "我的手機", initialPassword = "", alreadyHotspot = false, demo = false,
                motorsOn = false, onOpenHotspot = {}, onDismiss = {},
            )
        }
        assertFullyVisible("查看手機熱點名稱 / 密碼")
        assertFullyVisible("下一步")
        assertFullyVisible("取消")
    }

    @Test fun firstPairDialogSmallPhoneLargeFont() = firstPairDialog(1.3f, phoneHotspotOn = false)
    @Test fun firstPairDialogHotspotOnSmallPhoneLargeFont() = firstPairDialog(1.3f, phoneHotspotOn = true)
    @Test fun rescanDialogSmallPhoneLargeFont() = firstPairDialog(1.3f, phoneHotspotOn = true, promise = false)
    @Test fun hotspotCarDialogSmallPhoneLargeFont() = hotspotCarDialog(1.3f, phoneHotspotOn = false)
    @Test fun hotspotCarDialogHotspotOnSmallPhoneLargeFont() = hotspotCarDialog(1.3f, phoneHotspotOn = true)
    @Test fun onboardingPromptSmallPhoneLargeFont() = onboardingPrompt(1.3f)
    @Test fun hotspotFormSmallPhoneLargeFont() = hotspotForm(1.3f)

    @Config(qualifiers = "w780dp-h360dp-land-xhdpi")
    @Test fun firstPairDialogPhone() = firstPairDialog(1f, phoneHotspotOn = true)

    @Config(qualifiers = "w780dp-h360dp-land-xhdpi")
    @Test fun hotspotCarDialogPhone() = hotspotCarDialog(1f, phoneHotspotOn = false)

    @Config(qualifiers = "w780dp-h360dp-land-xhdpi")
    @Test fun onboardingPromptPhone() = onboardingPrompt(1f)

    @Config(qualifiers = "w780dp-h360dp-land-xhdpi")
    @Test fun hotspotFormPhone() = hotspotForm(1f)
}
