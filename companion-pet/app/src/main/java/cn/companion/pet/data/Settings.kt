package cn.companion.pet.data

import android.content.Context
import cn.companion.pet.core.rhythm.RhythmConfig
import cn.companion.pet.core.scene.AppLists
import cn.companion.pet.core.session.SessionConfig
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

    var sessionAutoStart by bool("session_auto_start", true)
    var sessionAutoStartSec by int("session_auto_start_sec", 20)
    var sessionPullBack by bool("session_pull_back", true)
    var sessionGraceSec by int("session_grace_sec", 10)
    var sessionAllowedGraceSec by int("session_allowed_grace_sec", 90)
    var sessionAllowedApps by set("session_allowed_apps")

    /** 进行中的会话（服务重启后接着守）：模式名，空字符串表示没有会话。 */
    var sessionMode by str("session_mode", "")
    var sessionStartedAt by long("session_started_at", 0L)

    /** 深聊用哪个 AI App：GEMINI / CHATGPT / QWEN。 */
    var assistant by str("assistant", "GEMINI")
    var geminiPackage by str("gemini_pkg", "com.google.android.apps.bard")
    var chatgptPackage by str("chatgpt_pkg", "")
    var qwenPackage by str("qwen_pkg", "")
    /** 进行中会话的 AI App。 */
    var sessionAssistant by str("session_assistant", "GEMINI")
    var sessionPackage by str("session_package", "")
    /** 划过留痕：滑动时在内存里记下经过的段落。 */
    var skimEnabled by bool("skim", true)
    /** 新连续性路径：按相关召回 + 结构化事实；默认关，关掉后走旧的整份 profile 路径。 */
    var continuityEnabled by bool("continuity", false)
    /** A1 自主决策：默认关；只自动执行只读工具，写入/打开/朗读只提议不执行。 */
    var agentAutonomy by bool("agent_autonomy", false)
    /** 视觉模型单独配置；默认 deepseek-flash，截图上传必须显式打开。 */
    var visionModel by str("vision_model", "deepseek-flash")
    var visionUploadEnabled by bool("vision_upload", false)
    /** A3：允许 Agent 操作的应用名单；空名单时全部拒绝。 */
    var deviceAllowedApps by set("device_apps")
    var geminiAutoSend by bool("gemini_auto_send", false)
    var ttsEnabled by bool("tts", false)
    /** 默认关：说话改为弹出输入框、用输入法自带的话筒。系统语音识别在不少国产机后台会直接失败。 */
    var systemVoice by bool("system_voice", false)
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

    fun sessionConfig(): SessionConfig = SessionConfig(
        graceMillis = sessionGraceSec.coerceIn(3, 600) * 1000L,
        allowedGraceMillis = sessionAllowedGraceSec.coerceIn(3, 3600) * 1000L,
    )

    fun appLists(): AppLists = AppLists(learningApps, feedApps, focusApps, excludeApps)

    private fun touched() { _changes.value = _changes.value + 1 }

    private fun str(key: String, def: String) = Pref({ prefs.getString(key, def) ?: def }, { prefs.edit().putString(key, it).apply() })
    private fun int(key: String, def: Int) = Pref({ prefs.getInt(key, def) }, { prefs.edit().putInt(key, it).apply() })
    private fun long(key: String, def: Long) = Pref({ prefs.getLong(key, def) }, { prefs.edit().putLong(key, it).apply() })
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
