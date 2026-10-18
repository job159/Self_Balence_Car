package com.b570.pilot.ui.screens.tune

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import com.b570.pilot.protocol.ParamGroup
import com.b570.pilot.protocol.Params
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 設定檔種類（決定圖示與配色）。 */
internal enum class ProfileKind { FACTORY, STABLE, SPORT, USER }

/**
 * 整定設定檔：一組參數值（id → 值）。
 * 內建設定檔只調運動限制，絕不動 PID 增益；使用者設定檔不含機械零點（每台車不同）。
 */
@Immutable
internal data class TuneProfile(
    val id: String,
    val name: String,
    val values: Map<Int, Float>,
    val kind: ProfileKind = ProfileKind.USER,
    val captionZh: String = "",
    val updatedMs: Long = 0L,
) {
    val builtIn: Boolean get() = kind != ProfileKind.USER
}

internal object TunePresets {
    /** 存成設定檔時排除的參數（機械零點是每台車的校正值）。 */
    val EXCLUDED: Set<Int> = setOf(Params.ZERO_ANGLE)

    private fun def(id: Int): Float = Params.byId(id)?.default ?: 0f

    val FACTORY = TuneProfile(
        id = "builtin_factory",
        name = "原廠",
        values = Params.ALL.filter { it.group == ParamGroup.MOTION }.associate { it.id to it.default },
        kind = ProfileKind.FACTORY,
        captionZh = "${def(Params.MAX_SPEED).toInt()} mm/s",
    )
    val STABLE = TuneProfile(
        id = "builtin_stable",
        name = "穩定",
        values = mapOf(Params.MAX_SPEED to 350f, Params.ACCEL to 800f, Params.TURN_ACCEL to 4000f),
        kind = ProfileKind.STABLE,
        captionZh = "350 mm/s",
    )
    val SPORT = TuneProfile(
        id = "builtin_sport",
        name = "運動",
        // 要比原廠預設（ACCEL 2000、TURN_ACCEL 12000，主控板 v2 起）更靈敏
        values = mapOf(Params.MAX_SPEED to 800f, Params.ACCEL to 3000f, Params.TURN_ACCEL to 16000f),
        kind = ProfileKind.SPORT,
        captionZh = "800 mm/s",
    )
    val ALL: List<TuneProfile> = listOf(FACTORY, STABLE, SPORT)
}

/** 使用者設定檔 + 整定頁的小偏好（SharedPreferences JSON，只在本 package 使用）。 */
internal class TuneProfileStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("tune_screen", Context.MODE_PRIVATE)
    private val _profiles = MutableStateFlow(load())
    /** 最近修改在前。 */
    val profiles: StateFlow<List<TuneProfile>> = _profiles.asStateFlow()

    var liveApply: Boolean
        get() = prefs.getBoolean(K_LIVE, true)
        set(v) = prefs.edit { putBoolean(K_LIVE, v) }

    var chartCollapsed: Boolean
        get() = prefs.getBoolean(K_CHART, false)
        set(v) = prefs.edit { putBoolean(K_CHART, v) }

    /** 展開中的分組；null = 尚未設定過（用預設）。 */
    var expandedGroups: Set<String>?
        get() = prefs.getStringSet(K_GROUPS, null)?.toSet()
        set(v) = prefs.edit { putStringSet(K_GROUPS, v) }

    fun findByName(name: String): TuneProfile? = _profiles.value.firstOrNull { it.name == name.trim() }

    /** 儲存（同名覆蓋，保留 id）。 */
    fun save(name: String, values: Map<Int, Float>): TuneProfile {
        val n = name.trim()
        val old = findByName(n)
        val p = TuneProfile(
            id = old?.id ?: UUID.randomUUID().toString(),
            name = n,
            values = values.filterKeys { it !in TunePresets.EXCLUDED },
            updatedMs = System.currentTimeMillis(),
        )
        mutate { list -> listOf(p) + list.filter { it.id != p.id } }
        return p
    }

    fun delete(id: String) = mutate { list -> list.filter { it.id != id } }

    private fun mutate(block: (List<TuneProfile>) -> List<TuneProfile>) {
        val next = block(_profiles.value).sortedByDescending { it.updatedMs }
        _profiles.value = next
        persist(next)
    }

    private fun load(): List<TuneProfile> = runCatching {
        val arr = JSONArray(prefs.getString(K_PROFILES, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val v = o.getJSONObject("v")
            val values = LinkedHashMap<Int, Float>()
            v.keys().forEach { k ->
                val d = Params.byKey(k) ?: return@forEach
                if (d.id !in TunePresets.EXCLUDED) values[d.id] = d.clamp(v.getDouble(k).toFloat())
            }
            TuneProfile(o.getString("id"), o.getString("name"), values, updatedMs = o.optLong("t"))
        }.sortedByDescending { it.updatedMs }
    }.getOrDefault(emptyList())

    private fun persist(list: List<TuneProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            val v = JSONObject()
            p.values.forEach { (id, value) -> Params.byId(id)?.let { v.put(it.key, value.toDouble()) } }
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("t", p.updatedMs).put("v", v))
        }
        prefs.edit { putString(K_PROFILES, arr.toString()) }
    }

    private companion object {
        const val K_PROFILES = "profiles_v1"
        const val K_LIVE = "live_apply"
        const val K_CHART = "chart_collapsed"
        const val K_GROUPS = "expanded_groups"
    }
}
