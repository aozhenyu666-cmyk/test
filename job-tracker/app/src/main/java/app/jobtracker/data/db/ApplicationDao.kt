package app.jobtracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.jobtracker.data.model.ApplicationStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ApplicationDao {
    @Insert
    suspend fun insert(application: ApplicationEntity): Long

    @Update
    suspend fun update(application: ApplicationEntity)

    @Query("DELETE FROM applications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM applications WHERE id = :id")
    suspend fun getById(id: Long): ApplicationEntity?

    @Query("SELECT * FROM applications WHERE id = :id")
    fun observeById(id: Long): Flow<ApplicationEntity?>

    /** 按下次跟进日期升序，逾期的自然排在最前；没有日期的排最后 */
    @Query(
        "SELECT * FROM applications " +
            "ORDER BY nextFollowUpAt IS NULL, nextFollowUpAt ASC, updatedAt DESC",
    )
    fun observeAll(): Flow<List<ApplicationEntity>>

    @Query(
        "SELECT * FROM applications WHERE status = :status " +
            "ORDER BY nextFollowUpAt IS NULL, nextFollowUpAt ASC, updatedAt DESC",
    )
    fun observeByStatus(status: ApplicationStatus): Flow<List<ApplicationEntity>>

    /** 当天到期和已逾期、且未结束的记录 */
    @Query(
        "SELECT * FROM applications " +
            "WHERE status != :closed AND nextFollowUpAt IS NOT NULL AND nextFollowUpAt <= :today " +
            "ORDER BY nextFollowUpAt ASC, updatedAt DESC",
    )
    fun observeDue(today: LocalDate, closed: ApplicationStatus): Flow<List<ApplicationEntity>>

    /** 导出用，按创建时间排列 */
    @Query("SELECT * FROM applications ORDER BY createdAt ASC")
    suspend fun getAll(): List<ApplicationEntity>
}
