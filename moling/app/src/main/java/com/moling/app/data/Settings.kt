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
)

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
    )

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

        private const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        private const val DEFAULT_MODEL = "deepseek-chat"

        private const val KEY_BASE_URL = "llm_base_url"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
        private const val KEY_PERSONA = "persona"
        private const val KEY_CALL_USER = "call_user"
        private const val KEY_SPEAK = "speak"

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