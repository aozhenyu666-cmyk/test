package app.jobtracker

import android.content.Context
import app.jobtracker.config.AppConfig
import app.jobtracker.data.db.AppDatabase
import app.jobtracker.data.repo.ApplicationRepository
import app.jobtracker.data.repo.ProfileRepository
import app.jobtracker.data.repo.SettingsRepository
import app.jobtracker.security.ApiKeyStore
import app.jobtracker.security.EncryptedApiKeyStore
import java.time.Clock

/** 手动依赖注入：整个应用共用一份数据库和仓库 */
class AppContainer(private val context: Context) {
    val config: AppConfig = AppConfig.Default
    private val clock: Clock = Clock.systemDefaultZone()
    private val database: AppDatabase = AppDatabase.create(context)

    val applications = ApplicationRepository(database.applicationDao(), clock)
    val profile = ProfileRepository(database.profileDao(), clock)
    val settings = SettingsRepository(database.settingsDao(), config)

    /** 打开 Keystore 较慢，用到时再初始化 */
    val apiKeys: ApiKeyStore by lazy { EncryptedApiKeyStore(context) }
}
