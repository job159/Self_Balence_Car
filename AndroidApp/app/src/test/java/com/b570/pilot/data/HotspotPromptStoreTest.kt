package com.b570.pilot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 「改用熱點模式」邀請已回答的車：存在自己的 key、重開 App 還在、不動記住的車。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HotspotPromptStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun answeredSurvivesRestart() {
        val s1 = HotspotPromptStore(app)
        assertTrue(s1.answered.value.isEmpty())
        s1.markAnswered("B570-A1B2")
        s1.markAnswered("B570-A1B2") // 重複不影響
        s1.markAnswered("")          // 空的不記
        assertEquals(setOf("B570-A1B2"), s1.answered.value)
        assertEquals(setOf("B570-A1B2"), HotspotPromptStore(app).answered.value)
        // 獨立的 SharedPreferences，記住的車的 JSON 不受影響
        assertTrue(app.getSharedPreferences("onboarding", Context.MODE_PRIVATE).contains("hotspot_prompt_answered_v1"))
        val carsJson = app.getSharedPreferences("saved_cars", Context.MODE_PRIVATE).getString("cars_v1", "[]").orEmpty()
        assertFalse(carsJson.contains("B570-A1B2"))
    }

    @Test fun forgetClearsOnlyThatCar() {
        val s = HotspotPromptStore(app)
        s.markAnswered("B570-AAAA")
        s.markAnswered("B570-BBBB")
        s.forget("B570-AAAA")
        s.forget("B570-ZZZZ")
        assertEquals(setOf("B570-BBBB"), s.answered.value)
        assertEquals(setOf("B570-BBBB"), HotspotPromptStore(app).answered.value)
    }
}
