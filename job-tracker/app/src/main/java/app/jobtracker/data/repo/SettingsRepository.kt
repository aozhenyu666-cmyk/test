package app.jobtracker.data.repo

import app.jobtracker.config.AppConfig
import app.jobtracker.data.db.SettingsDao
import app.jobtracker.data.db.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 还没保存过设置时，读到的是 AppConfig 里的默认值 */
class SettingsRepository(
    private val dao: SettingsDao,
    private val config: AppConfig,
) {
    fun observe(): Flow<SettingsEntity> = dao.observe().map { it ?: defaults() }

    suspend fun get(): SettingsEntity = dao.get() ?: defaults()

    suspend fun update(transform: (SettingsEntity) -> SettingsEntity) {
        dao.upsert(transform(get()).copy(id = SettingsEntity.SINGLE_ROW_ID))
    }

    private fun defaults() = SettingsEntity(
        strongModel = config.defaultStrongModel,
        fastModel = config.defaultFastModel,
        reminderTime = config.defaultReminderTime,
    )
}
