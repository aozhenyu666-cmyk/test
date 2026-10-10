package app.jobtracker.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = ${ProfileEntity.SINGLE_ROW_ID}")
    fun observe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = ${ProfileEntity.SINGLE_ROW_ID}")
    suspend fun get(): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)
}
