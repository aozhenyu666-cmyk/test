package app.jobtracker.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * API 密钥的唯一存放处。密钥不进数据库、不进日志、不进导出文件。
 * 除了 get() 之外，任何地方都只需要知道"有没有"。
 */
interface ApiKeyStore {
    /** 是否已保存密钥 */
    val hasKey: StateFlow<Boolean>

    fun get(): String?

    fun set(key: String)

    fun clear()
}

/** 用 Android Keystore 里的主密钥加密保存 */
class EncryptedApiKeyStore(context: Context) : ApiKeyStore {
    private val prefs: SharedPreferences = openPrefs(context.applicationContext)
    private val _hasKey = MutableStateFlow(!get().isNullOrEmpty())
    override val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    override fun get(): String? = prefs.getString(KEY_API_KEY, null)

    override fun set(key: String) {
        prefs.edit { putString(KEY_API_KEY, key) }
        _hasKey.value = key.isNotEmpty()
    }

    override fun clear() {
        prefs.edit { remove(KEY_API_KEY) }
        _hasKey.value = false
    }

    private companion object {
        const val FILE_NAME = "secure_settings"
        const val KEY_API_KEY = "api_key"

        fun openPrefs(context: Context): SharedPreferences =
            try {
                create(context)
            } catch (e: GeneralSecurityException) {
                recreate(context)
            } catch (e: IOException) {
                recreate(context)
            }

        // 少数机型的 Keystore 会损坏，导致旧文件解不开。此时只能丢弃旧密钥，让用户重新填写。
        fun recreate(context: Context): SharedPreferences {
            context.deleteSharedPreferences(FILE_NAME)
            return create(context)
        }

        fun create(context: Context): SharedPreferences =
            EncryptedSharedPreferences.create(
                FILE_NAME,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
    }
}
