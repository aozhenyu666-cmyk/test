package cn.companion.pet.data

import android.content.Context
import cn.companion.pet.core.rhythm.RhythmConfig
import cn.companion.pet.core.scene.AppLists
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置。用 SharedPreferences 而不是 DataStore：键值少、同步读写简单，
 * 服务和界面都通过 [changes] 感知修改。API Key 不在这里，在 [SecretStore]。
 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    var endpoint by str("endpoint", "https://api.deepseek.com")
    var model by str("model", "deepseek-chat")
    var dailyLimit by int("daily_limit", 150)

    var minIntervalMin by int("min_interval", 6)
    var maxIntervalMin by int("max_interval", 12)
    var quietStartMin by int("quiet_start", 0)
    var quietEndMin by int("quiet_end", 7 * 60)

    var learningApps by set("learning_apps")
    var feedApps by set("feed_apps")
    var focusApps by set("focus_apps")
    var excludeApps by set("exclude_apps")

    var heartbeatEnabled by bool("heartbeat", false)
    var heartbeatIntervalMin by int("heartbeat_interval", 20)

    var geminiPackage by str("gemini_pkg", "com.google.android.apps.bard")
    var geminiAutoSend by bool("gemini_auto_send", false)
    var ttsEnabled by bool("tts", false)
    var petPaused by bool("paused", false)
    var onboardingDone by bool("onboarding_done", false)

    /** 用户主动点了“停止桌宠”：开机和被回收后都不自动拉起。 */
    var userStopped by bool("user_stopped", false)

    var petEdgeRight by bool("pet_edge_right", true)
    var petY by int("pet_y", -1)

    fun rhythm(): RhythmConfig = RhythmConfig(
        minIntervalMillis = minIntervalMin.coerceAtLeast(1) * 60_000L,
        maxIntervalMillis = maxOf(minIntervalMin, maxIntervalMin).coerceAtLeast(1) * 60_000L,
        quietStartMinute = quietStartMin,
        quietEndMinute = quietEndMin,
    )

    fun appLists(): AppLists = AppLists(learningApps, feedApps, focusApps, excludeApps)

    private fun touched() { _changes.value = _changes.value + 1 }

    private fun str(key: String, def: String) = Pref({ prefs.getString(key, def) ?: def }, { prefs.edit().putString(key, it).apply() })
    private fun int(key: String, def: Int) = Pref({ prefs.getInt(key, def) }, { prefs.edit().putInt(key, it).apply() })
    private fun bool(key: String, def: Boolean) = Pref({ prefs.getBoolean(key, def) }, { prefs.edit().putBoolean(key, it).apply() })
    private fun set(key: String) = Pref({ prefs.getStringSet(key, emptySet())!!.toSet() }, { prefs.edit().putStringSet(key, it).apply() })

    private inner class Pref<T>(val get: () -> T, val put: (T) -> Unit) {
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = get()
        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            put(value)
            touched()
        }
    }
}
