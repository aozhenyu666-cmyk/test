package com.moling.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.moling.core.mind.BEIJING
import com.moling.core.mind.DEFAULT_PERSONA
import java.time.LocalDate

/** 文字模型的配置。人格和称呼也放这里：导演和对话页都从这份配置取。 */
data class ModelSettings(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val persona: String = DEFAULT_PERSONA,
    val callUser: String = "你",
    val speak: Boolean = false,
    /** 深想模型（可以是另一家，比如 OpenAI 的 GPT）：空 = 和上面同一个。只由 [SettingsStore.saveDeep] 写。 */
    val deepBaseUrl: String = "",
    val deepApiKey: String = "",
    val deepModel: String = "",
) {
    fun hasDeep(): Boolean = deepModel.isNotBlank()

    /** 深想模型的连接配置：没单独填的接口地址和密钥沿用平时的。 */
    fun deep(): ModelSettings = copy(
        baseUrl = deepBaseUrl.ifBlank { baseUrl },
        apiKey = deepApiKey.ifBlank { apiKey },
        model = deepModel.ifBlank { model },
    )
}

/** 她的声音：OpenAI 兼容的 /audio/speech（不填 = 用系统朗读）。 */
data class VoiceSettings(val baseUrl: String = "", val apiKey: String = "", val model: String = "", val voice: String = "") {
    fun ready(): Boolean = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** 联络站（Cloudflare Worker）：[url] 形如 https://moling.xxx.workers.dev；[token] 墨灵用；[mcpSecret] MCP 地址里的那段。 */
data class RelaySettings(val url: String = "", val token: String = "", val mcpSecret: String = "") {
    fun ready(): Boolean = url.startsWith("https://") && token.isNotBlank()
    fun base(): String = url.trim().trimEnd('/')
    fun mcpUrl(): String = if (url.isBlank() || mcpSecret.isBlank()) "" else "${base()}/mcp/$mcpSecret"
}

/** 和 Notion 贯通的设置。[token] 是 Notion 内部集成的密钥（加密存）；[root] 是他分享给集成的那个页面。 */
data class NotionSettings(
    val token: String = "",
    val root: String = "",
    val boardId: String = "",
    val inboxId: String = "",
) {
    fun ready(): Boolean = token.isNotBlank() && root.isNotBlank()
}

/** 当天调用了多少次模型、一共多少字。跨天清零，给设置页看。 */
data class Usage(val day: String, val calls: Int, val chars: Int)

/**
 * 设置的读写。
 * 密钥用 EncryptedSharedPreferences 存（由系统 Keystore 保护），只在这台手机上；
 * 打开加密文件失败（少数机型系统更新后 Keystore 会坏）不崩，退回普通 SharedPreferences，并记一条日志。
 */
class SettingsStore(context: Context) {

    private val app = context.applicationContext
    private val prefs: SharedPreferences = openEncrypted(app)
    private val usage: SharedPreferences = app.getSharedPreferences(USAGE_FILE, Context.MODE_PRIVATE)
    /** 开关类的设置（不涉密）：后台陪着、早上自动排水道（M12）。 */
    private val toggles: SharedPreferences = app.getSharedPreferences(TOGGLE_FILE, Context.MODE_PRIVATE)

    fun load(): ModelSettings = ModelSettings(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL,
        apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
        model = prefs.getString(KEY_MODEL, null) ?: DEFAULT_MODEL,
        persona = prefs.getString(KEY_PERSONA, null) ?: DEFAULT_PERSONA,
        callUser = prefs.getString(KEY_CALL_USER, null) ?: "你",
        speak = prefs.getBoolean(KEY_SPEAK, false),
        deepBaseUrl = prefs.getString(KEY_DEEP_URL, null).orEmpty(),
        deepApiKey = prefs.getString(KEY_DEEP_KEY, null).orEmpty(),
        deepModel = prefs.getString(KEY_DEEP_MODEL, null).orEmpty(),
    )

    fun saveDeep(url: String, key: String, model: String) {
        prefs.edit()
            .putString(KEY_DEEP_URL, url.trim())
            .putString(KEY_DEEP_KEY, key.trim())
            .putString(KEY_DEEP_MODEL, model.trim())
            .apply()
    }

    fun voice(): VoiceSettings = VoiceSettings(
        baseUrl = prefs.getString(KEY_VOICE_URL, null).orEmpty(),
        apiKey = prefs.getString(KEY_VOICE_KEY, null).orEmpty(),
        model = prefs.getString(KEY_VOICE_MODEL, null).orEmpty(),
        voice = prefs.getString(KEY_VOICE_NAME, null).orEmpty(),
    )

    fun saveVoice(v: VoiceSettings) {
        prefs.edit()
            .putString(KEY_VOICE_URL, v.baseUrl.trim())
            .putString(KEY_VOICE_KEY, v.apiKey.trim())
            .putString(KEY_VOICE_MODEL, v.model.trim())
            .putString(KEY_VOICE_NAME, v.voice.trim())
            .apply()
    }

    fun relay(): RelaySettings = RelaySettings(
        url = prefs.getString(KEY_RELAY_URL, null).orEmpty(),
        token = prefs.getString(KEY_RELAY_TOKEN, null).orEmpty(),
        mcpSecret = prefs.getString(KEY_RELAY_MCP, null).orEmpty(),
    )

    /** 换了地址就从头收（收过的条目大脑记着，不会重复收）。 */
    fun saveRelay(r: RelaySettings) {
        val old = relay()
        prefs.edit()
            .putString(KEY_RELAY_URL, r.url.trim())
            .putString(KEY_RELAY_TOKEN, r.token.trim())
            .putString(KEY_RELAY_MCP, r.mcpSecret.trim())
            .apply()
        if (old.url != r.url.trim()) toggles.edit().putLong(KEY_RELAY_AFTER, 0).apply()
    }

    fun relayAfter(): Long = toggles.getLong(KEY_RELAY_AFTER, 0)

    fun setRelayAfter(n: Long) {
        toggles.edit().putLong(KEY_RELAY_AFTER, n).apply()
    }

    fun relayStatus(): String = toggles.getString(KEY_RELAY_STATUS, null).orEmpty()

    fun setRelayStatus(text: String) {
        toggles.edit().putString(KEY_RELAY_STATUS, text).apply()
    }

    fun notion(): NotionSettings = NotionSettings(
        token = prefs.getString(KEY_NOTION_TOKEN, null).orEmpty(),
        root = toggles.getString(KEY_NOTION_ROOT, null).orEmpty(),
        boardId = toggles.getString(KEY_NOTION_BOARD, null).orEmpty(),
        inboxId = toggles.getString(KEY_NOTION_INBOX, null).orEmpty(),
    )

    /** 换了密钥或根页面：黑板和收件箱的页面要重新找。 */
    fun saveNotion(token: String, root: String) {
        val old = notion()
        prefs.edit().putString(KEY_NOTION_TOKEN, token.trim()).apply()
        val e = toggles.edit().putString(KEY_NOTION_ROOT, root.trim())
        if (old.token != token.trim() || old.root != root.trim()) e.remove(KEY_NOTION_BOARD).remove(KEY_NOTION_INBOX)
        e.apply()
    }

    fun saveNotionPages(boardId: String, inboxId: String) {
        toggles.edit().putString(KEY_NOTION_BOARD, boardId).putString(KEY_NOTION_INBOX, inboxId).apply()
    }

    /** 同步的结果（给贯通页看）。 */
    fun notionStatus(): String = toggles.getString(KEY_NOTION_STATUS, null).orEmpty()

    fun setNotionStatus(text: String) {
        toggles.edit().putString(KEY_NOTION_STATUS, text).apply()
    }

    /** 上次导入 ChatGPT 导出记录时，最新那段对话的时间（秒）：下次只收更新的。 */
    fun gptImportedUntil(): Double = toggles.getString(KEY_GPT_UNTIL_S, null)?.toDoubleOrNull() ?: 0.0

    fun setGptImportedUntil(sec: Double) {
        toggles.edit().putString(KEY_GPT_UNTIL_S, sec.toString()).apply()
    }

    fun save(s: ModelSettings) {
        prefs.edit()
            .putString(KEY_BASE_URL, s.baseUrl.trim())
            .putString(KEY_API_KEY, s.apiKey.trim())
            .putString(KEY_MODEL, s.model.trim())
            .putString(KEY_PERSONA, s.persona.trim())
            .putString(KEY_CALL_USER, s.callUser.trim())
            .putBoolean(KEY_SPEAK, s.speak)
            .apply()
    }

    /** 今天的用量。日期换了就当成 0，不用等定时任务清。 */
    fun usageToday(): Usage {
        val today = todayKey()
        if (usage.getString(KEY_USAGE_DAY, null) != today) return Usage(today, 0, 0)
        return Usage(today, usage.getInt(KEY_CALLS, 0), usage.getInt(KEY_CHARS, 0))
    }

    /** 记一次调用。[chars] 是这次输入加输出的字数。 */
    @Synchronized
    fun addUsage(chars: Int) {
        val today = todayKey()
        val sameDay = usage.getString(KEY_USAGE_DAY, null) == today
        val calls = if (sameDay) usage.getInt(KEY_CALLS, 0) else 0
        val total = if (sameDay) usage.getInt(KEY_CHARS, 0) else 0
        usage.edit()
            .putString(KEY_USAGE_DAY, today)
            .putInt(KEY_CALLS, calls + 1)
            .putInt(KEY_CHARS, total + chars.coerceAtLeast(0))
            .apply()
    }

    /** 后台陪着（常驻通知）：默认开。 */
    fun backgroundHeart(): Boolean = toggles.getBoolean(KEY_BACKGROUND, true)

    fun setBackgroundHeart(on: Boolean) {
        toggles.edit().putBoolean(KEY_BACKGROUND, on).apply()
    }

    /** 早上自动排水道（只排草稿、不定稿）：默认开。 */
    fun autoPlan(): Boolean = toggles.getBoolean(KEY_AUTO_PLAN, true)

    fun setAutoPlan(on: Boolean) {
        toggles.edit().putBoolean(KEY_AUTO_PLAN, on).apply()
    }

    /** 本地保底计划代驾：默认关；开了也只做“本地采用”，不自动交给总控。 */
    fun planAdoptionOn(): Boolean = toggles.getBoolean(KEY_PLAN_ADOPTION, false)

    fun setPlanAdoptionOn(on: Boolean) {
        toggles.edit().putBoolean(KEY_PLAN_ADOPTION, on).apply()
    }

    /** 计划实际可见多久才自动本地采用：默认 20 分钟（只允许 5–60）。 */
    fun planAdoptionWaitMin(): Int = toggles.getInt(KEY_PLAN_ADOPTION_WAIT, 20).coerceIn(5, 60)

    fun setPlanAdoptionWaitMin(min: Int) {
        toggles.edit().putInt(KEY_PLAN_ADOPTION_WAIT, min.coerceIn(5, 60)).apply()
    }

    /** 她会主动找你（刷久了、夜里躺着刷时蹦出来）：默认开。 */
    fun liveOn(): Boolean = toggles.getBoolean(KEY_LIVE, true)

    fun setLiveOn(on: Boolean) {
        toggles.edit().putBoolean(KEY_LIVE, on).apply()
    }

    /** 她可以拦住你（只拦娱乐 / 购物，每天最多 3 次、每次最多 5 分钟、永远有“急事”）：默认开。 */
    fun gateOn(): Boolean = toggles.getBoolean(KEY_GATE, true)

    fun setGateOn(on: Boolean) {
        toggles.edit().putBoolean(KEY_GATE, on).apply()
    }

    /** 她多主动（少 / 平常 / 多）：默认平常。 */
    fun eagerness(): com.moling.core.live.Eagerness =
        runCatching { com.moling.core.live.Eagerness.valueOf(toggles.getString(KEY_EAGER, null).orEmpty()) }.getOrDefault(com.moling.core.live.Eagerness.MID)

    fun setEagerness(e: com.moling.core.live.Eagerness) {
        toggles.edit().putString(KEY_EAGER, e.name).apply()
    }

    /** 他不想要的出现方式（桌宠 / 盯着 / 耳语 / 参谋弹幕 / 捉虫题）；拦人（GATE）不在这里。 */
    fun disabledModalities(): Set<com.moling.core.live.Modality> = runCatching {
        toggles.getString(KEY_DISABLED_MODALITIES, null).orEmpty()
            .split(',')
            .mapNotNull { name -> name.trim().takeIf { it.isNotEmpty() }?.let { com.moling.core.live.Modality.valueOf(it) } }
            .toSet()
    }.getOrDefault(emptySet())

    fun setDisabledModalities(set: Set<com.moling.core.live.Modality>) {
        toggles.edit().putString(KEY_DISABLED_MODALITIES, set.joinToString(",") { it.name }).apply()
    }

    /** 她可以在耳机里说话（Z3 耳语）：默认开。 */
    fun whisperOn(): Boolean = toggles.getBoolean(KEY_WHISPER, true)

    fun setWhisperOn(on: Boolean) {
        toggles.edit().putBoolean(KEY_WHISPER, on).apply()
    }

    /** 刷着刷着慢慢褪色（Z6）：默认关，完全由他自己打开。 */
    fun fadeOn(): Boolean = toggles.getBoolean(KEY_FADE, false)

    fun setFadeOn(on: Boolean) {
        toggles.edit().putBoolean(KEY_FADE, on).apply()
    }

    /** 北京时间日期。按用户的作息，一天从零点算。 */
    private fun todayKey(): String = LocalDate.now(BEIJING).toString()

    private companion object {
        private const val TAG = "SettingsStore"
        private const val FILE = "moling_settings"

        /** 加密文件打不开时的退路。用另一个文件名，免得把原来的密文覆盖掉。 */
        private const val FALLBACK_FILE = "moling_settings_plain"
        private const val USAGE_FILE = "moling_usage"
        private const val TOGGLE_FILE = "moling_toggles"
        private const val KEY_BACKGROUND = "background_heart"
        private const val KEY_AUTO_PLAN = "auto_plan"
        private const val KEY_PLAN_ADOPTION = "plan_adoption"
        private const val KEY_PLAN_ADOPTION_WAIT = "plan_adoption_wait_min"
        private const val KEY_LIVE = "live"
        private const val KEY_GATE = "gate"
        private const val KEY_EAGER = "eagerness"
        private const val KEY_WHISPER = "whisper"
        private const val KEY_FADE = "fade"
        private const val KEY_DISABLED_MODALITIES = "disabled_modalities"

        private const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        private const val DEFAULT_MODEL = "deepseek-chat"

        private const val KEY_BASE_URL = "llm_base_url"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
        private const val KEY_PERSONA = "persona"
        private const val KEY_CALL_USER = "call_user"
        private const val KEY_SPEAK = "speak"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_RELAY_TOKEN = "relay_token"
        private const val KEY_RELAY_MCP = "relay_mcp"
        private const val KEY_RELAY_AFTER = "relay_after"
        private const val KEY_RELAY_STATUS = "relay_status"
        private const val KEY_VOICE_URL = "voice_base_url"
        private const val KEY_VOICE_KEY = "voice_api_key"
        private const val KEY_VOICE_MODEL = "voice_model"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_DEEP_URL = "deep_base_url"
        private const val KEY_DEEP_KEY = "deep_api_key"
        private const val KEY_DEEP_MODEL = "deep_model"
        private const val KEY_NOTION_TOKEN = "notion_token"
        private const val KEY_NOTION_ROOT = "notion_root"
        private const val KEY_NOTION_BOARD = "notion_board"
        private const val KEY_NOTION_INBOX = "notion_inbox"
        private const val KEY_NOTION_STATUS = "notion_status"
        private const val KEY_GPT_UNTIL_S = "gpt_until_s"

        private const val KEY_USAGE_DAY = "usage_day"
        private const val KEY_CALLS = "usage_calls"
        private const val KEY_CHARS = "usage_chars"

        private fun openEncrypted(context: Context): SharedPreferences = runCatching {
            // security-crypto 1.0.0 的 API：MasterKeys 拿别名的字符串，create 收文件名和别名。
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                FILE,
                alias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { e ->
            // Keystore 坏了还崩在启动路径上，App 就打不开了；宁可先退回明文，至少能改设置。
            Log.w(TAG, "加密设置打不开，退回普通 SharedPreferences", e)
            context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
        }
    }
}