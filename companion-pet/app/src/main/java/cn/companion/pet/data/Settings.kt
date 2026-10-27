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

    /**
     * 允许同桌在“本次已分享的材料”上主动提问。
     * 显式授权：保存材料本身不等于允许持续打扰；默认关。
     */
    var materialAssist by bool("material_assist", false)

    /** 专注策略：FIXED_FOCUS 不产生休息许可；PRACTICE_FOR_BREAK 练完换休息。 */
    var focusPolicyType by str("focus_policy_type", "FIXED_FOCUS")
    /** 练习换休息时，一次兑换多少分钟。 */
    var restMinutes by int("rest_minutes", 10)
    /** 练习换休息：攒满几道合格题换一次休息。 */
    var practiceQuestions by int("practice_questions", 5)
    /** 练习换休息：整轮的硬截止（分钟），到了就结束，不加题、不延长。 */
    var practiceWindowMinutes by int("practice_window_minutes", 45)

    /** 严格陪伴：默认关；开启后“想一想”进入有时限的受约束回合。 */
    var strictEnabled by bool("strict_enabled", false)
    var strictMinutes by int("strict_minutes", 10)

    var heartbeatEnabled by bool("heartbeat", false)
    var heartbeatIntervalMin by int("heartbeat_interval", 20)

    var sessionAutoStart by bool("session_auto_start", true)
    var sessionAutoStartSec by int("session_auto_start_sec", 20)
    /** 专注守护：SOFT 只提醒（默认）；STRICT 到点全屏拦截并有界自动拉回，必须用户自己打开。 */
    var focusEnforcement by str("focus_enforcement", "SOFT")
    /** 守护时长（分钟），5–180，到期自动结束。 */
    var focusMinutes by int("focus_minutes", 25)
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
    /**
     * 本机主动陪伴。**R3 起默认开启**：它现在攒着自己的话、挑时机说，不再是"到点提醒"。
     * 关掉时完全走旧的规则开口逻辑。
     */
    var presenceEnabled by bool("presence_enabled", true)
    /** 每天主动开口上限（本机提示 + 模型开口），可选 10/20/30/50。 */
    var presenceDailyCap by int("presence_daily_cap", 20)
    /**
     * 是否按星期与时段浮动活跃度（工作日白天 25 / 晚上 40 / 周末 60 / 凌晨 0）。
     * 默认开；关掉后用上面那个固定上限。
     */
    var useAdaptiveBudget by bool("use_adaptive_budget", true)
    /** A1 自主决策：默认关；只自动执行只读工具，写入/打开/朗读只提议不执行。 */
    var agentAutonomy by bool("agent_autonomy", false)
    /** 视觉模型单独配置；默认 deepseek-flash，截图上传必须显式打开。 */
    /** 能看图的模型名；空 = 还没配置，“看一眼”不会发请求。 */
    var visionModel by str("vision_model_v2", "")
    /** 视觉服务的接口地址；空 = 和搭话用同一个地址。 */
    var visionEndpoint by str("vision_endpoint", "")
    var visionUploadEnabled by bool("vision_upload", false)
    /** 看一眼时在截图上给可点元素标号，并把编号表发给视觉模型。 */
    var visionMarks by bool("vision_marks", true)
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
