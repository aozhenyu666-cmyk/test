package app.jobtracker.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = ${SettingsEntity.SINGLE_ROW_ID}")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = ${SettingsEntity.SINGLE_ROW_ID}")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}
