package cn.companion.pet.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface SecretReadResult {
    data class Available(val value: String) : SecretReadResult
    data object Missing : SecretReadResult
    data object NeedsReconfiguration : SecretReadResult
}

sealed interface SecretWriteResult {
    data object Success : SecretWriteResult
    data object Failure : SecretWriteResult
}

/**
 * API Key 存储：AndroidKeyStore 里不可导出的 AES-GCM 密钥加密，密文放独立的私有 SharedPreferences。
 * 加解密会碰磁盘和 Keystore，调用方放在 IO 线程。
 */
class SecretStore(
    context: Context,
    prefsName: String = PREFS,
    /** 每个存储用自己的 Keystore 别名；视觉服务的 Key 与搭话 Key 分开存。 */
    private val alias: String = ALIAS,
) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun read(): SecretReadResult {
        val version = prefs.getInt(KEY_VERSION, 0)
        val iv = prefs.getString(KEY_IV, null)
        val data = prefs.getString(KEY_DATA, null)
        if (version == 0 && iv == null && data == null) return SecretReadResult.Missing
        if (version != FORMAT_VERSION || iv == null || data == null) return SecretReadResult.NeedsReconfiguration
        return try {
            val key = existingKey() ?: return SecretReadResult.NeedsReconfiguration
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val plain = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
            SecretReadResult.Available(plain)
        } catch (e: Exception) {
            SecretReadResult.NeedsReconfiguration
        }
    }

    fun save(value: String): SecretWriteResult {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return clear()
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            try {
                cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
            } catch (e: Exception) {
                // 密钥失效（例如系统清了锁屏凭据）：用户明确保存新 Key 时允许重建
                deleteKey()
                cipher.init(Cipher.ENCRYPT_MODE, createKey())
            }
            val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val ok = prefs.edit()
                .putInt(KEY_VERSION, FORMAT_VERSION)
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
            if (ok) SecretWriteResult.Success else SecretWriteResult.Failure
        } catch (e: Exception) {
            SecretWriteResult.Failure
        }
    }

    fun clear(): SecretWriteResult {
        val ok = prefs.edit().clear().commit()
        val keyOk = runCatching { deleteKey() }.isSuccess
        return if (ok && keyOk) SecretWriteResult.Success else SecretWriteResult.Failure
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun createKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    companion object {
        const val ALIAS = "companion-pet-api-token"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val PREFS = "secret_store"
        private const val KEY_VERSION = "v"
        private const val KEY_IV = "iv"
        private const val KEY_DATA = "data"
        private const val FORMAT_VERSION = 1
    }
}
