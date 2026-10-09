package com.yishou.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.yishou.app.llm.LlmConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置项，保存在 EncryptedSharedPreferences（密钥由系统 Keystore 保护），只存在本机。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = openEncrypted(context.applicationContext)

    private val _llm = MutableStateFlow(readLlm())
    val llm: StateFlow<LlmConfig> = _llm.asStateFlow()

    fun saveLlm(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putBoolean(KEY_JSON_MODE, config.jsonMode)
            .apply()
        _llm.value = readLlm()
    }

    private fun readLlm() = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
        jsonMode = prefs.getBoolean(KEY_JSON_MODE, true),
    )

    companion object {
        private const val TAG = "SettingsStore"
        private const val FILE = "yishou_settings"
        private const val KEY_BASE_URL = "llm_base_url"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
        private const val KEY_JSON_MODE = "llm_json_mode"

        /**
         * 少数机型在系统更新或备份恢复后，Keystore 里的密钥会对不上，打开加密文件会抛异常。
         * 这时删掉旧文件重建：已填的设置需要重填，但应用能继续用。
         * 重建仍失败就直接抛出，不退回明文保存密钥。
         */
        private fun openEncrypted(context: Context): SharedPreferences {
            return try {
                create(context)
            } catch (e: Exception) {
                Log.w(TAG, "加密设置文件无法打开，删除后重建", e)
                context.deleteSharedPreferences(FILE)
                create(context)
            }
        }

        private fun create(context: Context): SharedPreferences =
            EncryptedSharedPreferences.create(
                FILE,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
    }
}
