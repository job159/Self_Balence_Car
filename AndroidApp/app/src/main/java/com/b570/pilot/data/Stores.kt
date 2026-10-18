package com.b570.pilot.data

import android.content.Context
import android.content.SharedPreferences
import com.b570.pilot.protocol.SeqMode
import com.b570.pilot.protocol.SeqStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 記住的車子（依最近連線排序）。 */
class SavedCarsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("saved_cars", Context.MODE_PRIVATE)
    private val _cars = MutableStateFlow(load())
    val cars: StateFlow<List<SavedCar>> = _cars.asStateFlow()

    fun find(ssid: String): SavedCar? = _cars.value.firstOrNull { it.ssid == ssid }

    /** 新增或更新（保留原暱稱與熱點設定，除非傳入新的）。 */
    fun upsert(ssid: String, password: String, nickname: String? = null, lastConnected: Long? = null) = mutate { list ->
        val old = list.firstOrNull { it.ssid == ssid }
        val car = (old ?: SavedCar(ssid, password)).copy(
            password = password,
            nickname = nickname ?: old?.nickname.orEmpty(),
            lastConnected = lastConnected ?: old?.lastConnected ?: 0L,
        )
        listOf(car) + list.filter { it.ssid != ssid }
    }

    fun rename(ssid: String, nickname: String) = mutate { list ->
        list.map { if (it.ssid == ssid) it.copy(nickname = nickname.trim()) else it }
    }

    /** SSID 改名（車子 Wi-Fi 設定被修改後），保留暱稱與熱點設定。 */
    fun replaceSsid(oldSsid: String, newSsid: String, newPassword: String) = mutate { list ->
        val old = list.firstOrNull { it.ssid == oldSsid }
        val car = (old ?: SavedCar(newSsid, newPassword)).copy(ssid = newSsid, password = newPassword, lastConnected = System.currentTimeMillis())
        listOf(car) + list.filter { it.ssid != oldSsid && it.ssid != newSsid }
    }

    /**
     * 更新熱點模式資訊（null = 不改）。這台車還沒記住時：[password] 非 null 就新增（從熱點上找到的車沒有 AP 密碼 → 空字串），
     * 否則不動。內容沒變不會寫檔。
     */
    fun updateHotspot(
        ssid: String,
        hotspotMode: Boolean? = null,
        hotspotSsid: String? = null,
        lastIp: String? = null,
        password: String? = null,
    ) {
        var changed = false
        _cars.update { list ->
            val old = list.firstOrNull { it.ssid == ssid }
            if (old == null && password == null) {
                changed = false
                return@update list
            }
            val base = old ?: SavedCar(ssid, password.orEmpty())
            val car = base.copy(
                hotspotMode = hotspotMode ?: base.hotspotMode,
                hotspotSsid = hotspotSsid ?: base.hotspotSsid,
                lastIp = lastIp ?: base.lastIp,
            )
            changed = car != old
            if (!changed) list
            else (if (old == null) list + car else list.map { if (it.ssid == ssid) car else it }).sortedByDescending { it.lastConnected }
        }
        if (changed) save(_cars.value)
    }

    fun forget(ssid: String) = mutate { list -> list.filter { it.ssid != ssid } }

    private fun mutate(block: (List<SavedCar>) -> List<SavedCar>) {
        _cars.update { cur -> block(cur).sortedByDescending { it.lastConnected } }
        save(_cars.value)
    }

    private fun load(): List<SavedCar> = SavedCarsCodec.decode(prefs.getString(KEY, "[]"))

    private fun save(list: List<SavedCar>) {
        prefs.edit().putString(KEY, SavedCarsCodec.encode(list)).apply()
    }

    private companion object { const val KEY = "cars_v1" }
}

/**
 * 記住的車子 JSON（SharedPreferences "cars_v1"）。舊版只有 ssid / pw / nick / last：
 * 讀舊資料時熱點欄位用預設值（不是熱點模式、沒有 lastIp），不需要遷移；寫回時多了 hs / hss / ip。
 */
object SavedCarsCodec {
    fun encode(list: List<SavedCar>): String {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject().put("ssid", it.ssid).put("pw", it.password).put("nick", it.nickname).put("last", it.lastConnected)
                .put("hs", it.hotspotMode).put("hss", it.hotspotSsid)
            if (it.lastIp != null) o.put("ip", it.lastIp)
            arr.put(o)
        }
        return arr.toString()
    }

    /** 壞掉的 JSON → 空清單；單筆缺 ssid / pw → 略過那筆。 */
    fun decode(json: String?): List<SavedCar> = runCatching {
        val arr = JSONArray(json ?: "[]")
        val out = ArrayList<SavedCar>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ssid = o.optString("ssid").takeIf { it.isNotEmpty() && o.has("pw") } ?: continue
            out += SavedCar(
                ssid = ssid,
                password = o.optString("pw"),
                nickname = o.optString("nick"),
                lastConnected = o.optLong("last"),
                hotspotMode = o.optBoolean("hs", false),
                lastIp = if (o.has("ip") && !o.isNull("ip")) o.optString("ip").takeIf { it.isNotEmpty() } else null,
                hotspotSsid = o.optString("hss"),
            )
        }
        out.sortedByDescending { it.lastConnected }
    }.getOrDefault(emptyList())
}

/**
 * 「帶出去玩：改用熱點模式」邀請已經回答過的車（車子 AP 的 SSID）：每台車只問一次。
 * 與記住的車分開存（SharedPreferences "onboarding" 的字串集合），不動 [SavedCar] 與它的 JSON。
 */
class HotspotPromptStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    private val _answered = MutableStateFlow(load())
    val answered: StateFlow<Set<String>> = _answered.asStateFlow()

    /** 記下 [ssid] 已回答（按了「先不用」、沒套用就關掉熱點表單、切換過連線方式、或車子回報熱點模式）。已記過不會再寫檔。 */
    fun markAnswered(ssid: String) {
        if (ssid.isEmpty() || ssid in _answered.value) return
        _answered.update { it + ssid }
        save()
    }

    /** 忘記這台車時一起清掉（重新配對後會再問一次）。 */
    fun forget(ssid: String) {
        if (ssid !in _answered.value) return
        _answered.update { it - ssid }
        save()
    }

    private fun load(): Set<String> =
        runCatching { prefs.getStringSet(KEY, null)?.toSet() }.getOrNull().orEmpty()

    private fun save() {
        // getStringSet 回傳的集合不能修改：一律寫入新的副本
        prefs.edit().putStringSet(KEY, HashSet(_answered.value)).apply()
    }

    private companion object { const val KEY = "hotspot_prompt_answered_v1" }
}

/** App 設定。 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        save(_settings.value)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            hapticsEnabled = prefs.getBoolean("haptics", d.hapticsEnabled),
            // 一次性遷移：舊版的 "joy" 不再讀取 → 所有人升級後都落在 360° 搖桿（預設），之後的選擇存在 KEY_JOY
            joystickMode = enumOr(prefs.getString(KEY_JOY, null), d.joystickMode),
            expo = prefs.getFloat("expo", d.expo).coerceIn(0f, 1f),
            defaultGear = enumOr(prefs.getString("gear", null), d.defaultGear),
            tiltSensitivity = prefs.getFloat("tilt", d.tiltSensitivity).coerceIn(0f, 1f),
            accent = enumOr(prefs.getString("accent", null), d.accent),
            keepScreenOn = prefs.getBoolean("screen_on", d.keepScreenOn),
            demoMode = prefs.getBoolean("demo", d.demoMode),
            autoConnect = prefs.getBoolean("auto_connect", d.autoConnect),
            autoReconnect = prefs.getBoolean("auto_reconnect", d.autoReconnect),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("haptics", s.hapticsEnabled)
            .putString(KEY_JOY, s.joystickMode.name)
            .remove(KEY_JOY_LEGACY)
            .putFloat("expo", s.expo)
            .putString("gear", s.defaultGear.name)
            .putFloat("tilt", s.tiltSensitivity)
            .putString("accent", s.accent.name)
            .putBoolean("screen_on", s.keepScreenOn)
            .putBoolean("demo", s.demoMode)
            .putBoolean("auto_connect", s.autoConnect)
            .putBoolean("auto_reconnect", s.autoReconnect)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def

    private companion object {
        /**
         * 搖桿模式（v2）。舊版存在 [KEY_JOY_LEGACY]，而且任何設定變更都會把整份設定寫回，
         * 所以分不出「使用者選了雙搖桿」還是「只是舊預設」；360° 搖桿上線時改用新 key，
         * 讓每個人升級後先落在 360°，之後的選擇照常保存。
         */
        const val KEY_JOY = "joy_v2"
        const val KEY_JOY_LEGACY = "joy"
    }
}

/** 使用者編排。 */
class SequencesStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sequences", Context.MODE_PRIVATE)
    private val _sequences = MutableStateFlow(load())
    val sequences: StateFlow<List<SavedSequence>> = _sequences.asStateFlow()

    fun get(id: String): SavedSequence? = _sequences.value.firstOrNull { it.id == id }

    /** 新增或覆蓋（依 id），回傳存入的版本（updatedMs 已更新）。 */
    fun save(seq: SavedSequence): SavedSequence {
        val s = seq.copy(updatedMs = System.currentTimeMillis())
        _sequences.update { list -> (listOf(s) + list.filter { it.id != s.id }).sortedByDescending { it.updatedMs } }
        persist()
        return s
    }

    fun delete(id: String) {
        _sequences.update { list -> list.filter { it.id != id } }
        persist()
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun load(): List<SavedSequence> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val st = o.getJSONArray("steps")
            SavedSequence(
                id = o.getString("id"),
                name = o.getString("name"),
                steps = List(st.length()) { j ->
                    val s = st.getJSONArray(j)
                    SeqStep(s.getInt(0), s.getInt(1), s.getInt(2), SeqMode.of(s.getInt(3)), s.getInt(4))
                },
                loops = o.optInt("loops", 1),
                updatedMs = o.optLong("updated"),
            )
        }.sortedByDescending { it.updatedMs }
    }.getOrDefault(emptyList())

    private fun persist() {
        val arr = JSONArray()
        _sequences.value.forEach { seq ->
            val steps = JSONArray()
            seq.steps.forEach { s ->
                steps.put(JSONArray().put(s.speed).put(s.turn).put(s.durMs).put(s.mode.code).put(s.period20ms))
            }
            arr.put(JSONObject().put("id", seq.id).put("name", seq.name).put("loops", seq.loops).put("updated", seq.updatedMs).put("steps", steps))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "sequences_v1" }
}

/** 內建範例編排（編排頁可提供「從範例開始」）。 */
object SequencePresets {
    val ALL: List<SavedSequence> = listOf(
        SavedSequence(
            id = "preset-wiggle", name = "搖擺舞", loops = 2,
            steps = listOf(
                SeqStep(0, 600, 400), SeqStep(0, -600, 400), SeqStep(0, 600, 400), SeqStep(0, -600, 400),
                SeqStep(150, 0, 600, SeqMode.SINE_SPEED, 30), SeqStep(0, 0, 400),
            ),
            updatedMs = 0,
        ),
        SavedSequence(
            id = "preset-figure8", name = "八字繞行", loops = 1,
            steps = listOf(
                SeqStep(250, 0, 600, SeqMode.RAMP), SeqStep(250, 450, 3200), SeqStep(250, -450, 3200),
                SeqStep(0, 0, 800, SeqMode.RAMP),
            ),
            updatedMs = 0,
        ),
        SavedSequence(
            id = "preset-spin", name = "原地旋轉", loops = 1,
            steps = listOf(SeqStep(0, 800, 600, SeqMode.RAMP), SeqStep(0, 800, 2400), SeqStep(0, 0, 600, SeqMode.RAMP)),
            updatedMs = 0,
        ),
        SavedSequence(
            id = "preset-slalom", name = "蛇行", loops = 2,
            steps = listOf(
                SeqStep(200, 0, 500, SeqMode.RAMP), SeqStep(200, 500, 4000, SeqMode.SINE_TURN, 75),
                SeqStep(0, 0, 600, SeqMode.RAMP),
            ),
            updatedMs = 0,
        ),
    )
}
