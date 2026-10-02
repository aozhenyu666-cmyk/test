package com.behaviordept.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class Provider(val label: String, val defaultBaseUrl: String) {
    ANTHROPIC("Anthropic 接口", "https://api.anthropic.com"),
    OPENAI("OpenAI 兼容接口", "https://api.deepseek.com/v1"),
}

/** 设置页里除 API Key 以外的全部配置。 */
data class AppSettings(
    val provider: Provider = Provider.ANTHROPIC,
    val baseUrl: String = Provider.ANTHROPIC.defaultBaseUrl,
    val cheapModel: String = DEFAULT_CHEAP_MODEL,
    val flagshipModel: String = DEFAULT_FLAGSHIP_MODEL,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 8,
    val reminderMinute: Int = 30,
    val onboardingDone: Boolean = false,
) {
    companion object {
        const val DEFAULT_CHEAP_MODEL = "claude-haiku-4-5"
        const val DEFAULT_FLAGSHIP_MODEL = "claude-opus-5-5"
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val provider = stringPreferencesKey("provider")
        val baseUrl = stringPreferencesKey("base_url")
        val cheapModel = stringPreferencesKey("cheap_model")
        val flagshipModel = stringPreferencesKey("flagship_model")
        val reminderEnabled = booleanPreferencesKey("reminder_enabled")
        val reminderHour = intPreferencesKey("reminder_hour")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val lastReviewNotice = stringPreferencesKey("last_review_notice")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        val provider = this[Keys.provider]?.let { runCatching { Provider.valueOf(it) }.getOrNull() } ?: d.provider
        return AppSettings(
            provider = provider,
            baseUrl = this[Keys.baseUrl] ?: provider.defaultBaseUrl,
            cheapModel = this[Keys.cheapModel] ?: d.cheapModel,
            flagshipModel = this[Keys.flagshipModel] ?: d.flagshipModel,
            reminderEnabled = this[Keys.reminderEnabled] ?: d.reminderEnabled,
            reminderHour = this[Keys.reminderHour] ?: d.reminderHour,
            reminderMinute = this[Keys.reminderMinute] ?: d.reminderMinute,
            onboardingDone = this[Keys.onboardingDone] ?: d.onboardingDone,
        )
    }

    suspend fun saveAi(provider: Provider, baseUrl: String, cheapModel: String, flagshipModel: String) {
        context.dataStore.edit {
            it[Keys.provider] = provider.name
            it[Keys.baseUrl] = baseUrl.trim().trimEnd('/')
            it[Keys.cheapModel] = cheapModel.trim()
            it[Keys.flagshipModel] = flagshipModel.trim()
        }
    }

    suspend fun saveReminder(enabled: Boolean, hour: Int, minute: Int) {
        context.dataStore.edit {
            it[Keys.reminderEnabled] = enabled
            it[Keys.reminderHour] = hour
            it[Keys.reminderMinute] = minute
        }
    }

    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[Keys.onboardingDone] = true }
    }

    /** 自测到期提醒每天最多发一次，记下发过的日期。 */
    suspend fun lastReviewNotice(): String? = context.dataStore.data.first()[Keys.lastReviewNotice]

    suspend fun setLastReviewNotice(date: String) {
        context.dataStore.edit { it[Keys.lastReviewNotice] = date }
    }
}

/**
 * API Key 单独放在 EncryptedSharedPreferences（Android Keystore 加密），
 * 不进 DataStore、不进备份文件、不写日志。
 */
class SecretStore(private val context: Context) {
    private val prefs: SharedPreferences? by lazy { open() }

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey

    /** Keystore 不可用时为 false，设置页会提示无法保存 Key。 */
    val available: Boolean get() = prefs != null

    fun load() {
        _apiKey.value = prefs?.getString(KEY, "") ?: ""
    }

    fun save(key: String) {
        val p = prefs ?: return
        p.edit().putString(KEY, key.trim()).apply()
        _apiKey.value = key.trim()
    }

    private fun open(): SharedPreferences? {
        fun create(): SharedPreferences {
            val master = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE,
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            // 密钥损坏（例如系统恢复后）时清掉旧文件重建一次；Key 需要重新填写。
            context.deleteSharedPreferences(FILE)
            runCatching { create() }.getOrNull()
        }
    }

    private companion object {
        const val FILE = "secrets"
        const val KEY = "api_key"
    }
}
