package com.behaviordept.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyDao {
    @Query("SELECT * FROM study_unit ORDER BY createdAt DESC")
    fun observeUnits(): Flow<List<StudyUnit>>

    @Query("SELECT * FROM study_unit ORDER BY createdAt DESC")
    suspend fun allUnits(): List<StudyUnit>

    @Query("SELECT * FROM study_unit WHERE id = :id")
    fun observeUnit(id: Long): Flow<StudyUnit?>

    @Query("SELECT * FROM study_unit WHERE id = :id")
    suspend fun unit(id: Long): StudyUnit?

    @Insert
    suspend fun insertUnit(unit: StudyUnit): Long

    @Update
    suspend fun updateUnit(unit: StudyUnit)

    @Query("DELETE FROM study_unit WHERE id = :id")
    suspend fun deleteUnit(id: Long)

    @Query("SELECT * FROM review WHERE unitId = :unitId ORDER BY createdAt ASC")
    fun observeReviews(unitId: Long): Flow<List<Review>>

    @Query("SELECT * FROM review ORDER BY createdAt ASC")
    fun observeAllReviews(): Flow<List<Review>>

    @Query("SELECT * FROM review WHERE unitId = :unitId ORDER BY createdAt ASC")
    suspend fun reviewsOf(unitId: Long): List<Review>

    @Query("SELECT * FROM review WHERE id = :id")
    suspend fun review(id: Long): Review?

    /** 某单元尚未完成的自测（出了题但还没判定），用于中断后接着做。 */
    @Query("SELECT * FROM review WHERE unitId = :unitId AND finishedAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun openReview(unitId: Long): Review?

    @Insert
    suspend fun insertReview(review: Review): Long

    @Update
    suspend fun updateReview(review: Review)

    @Query("DELETE FROM review WHERE unitId = :unitId")
    suspend fun deleteReviewsOf(unitId: Long)

    @Query("SELECT * FROM transfer WHERE unitId = :unitId ORDER BY createdAt ASC")
    fun observeTransfers(unitId: Long): Flow<List<Transfer>>

    @Insert
    suspend fun insertTransfer(transfer: Transfer): Long

    @Query("DELETE FROM transfer WHERE unitId = :unitId")
    suspend fun deleteTransfersOf(unitId: Long)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: TrainingSession): Long

    @Query("UPDATE session SET `end` = :end WHERE id = :id AND ended = 0")
    suspend fun touch(id: Long, end: Long)

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun get(id: Long): TrainingSession?

    @Update
    suspend fun update(session: TrainingSession)

    @Query("SELECT * FROM session WHERE ended = 0")
    suspend fun unfinished(): List<TrainingSession>
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: Event): Long

    @Query("SELECT * FROM event WHERE time >= :from ORDER BY time DESC")
    fun observeSince(from: Long): Flow<List<Event>>

    @Query("SELECT * FROM event ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Event>>

    @Query("SELECT * FROM event WHERE time >= :from AND time < :to AND type = :type")
    suspend fun ofTypeBetween(type: String, from: Long, to: Long): List<Event>
}

@Dao
interface AiCallDao {
    @Insert
    suspend fun insert(call: AiCall): Long
}

/** 备份用：每张表的全量读、写、清空。 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM skill") suspend fun skills(): List<Skill>
    @Query("SELECT * FROM sub_skill") suspend fun subSkills(): List<SubSkill>
    @Query("SELECT * FROM drill") suspend fun drills(): List<Drill>
    @Query("SELECT * FROM study_unit") suspend fun units(): List<StudyUnit>
    @Query("SELECT * FROM review") suspend fun reviews(): List<Review>
    @Query("SELECT * FROM transfer") suspend fun transfers(): List<Transfer>
    @Query("SELECT * FROM match_log") suspend fun matchLogs(): List<MatchLog>
    @Query("SELECT * FROM session") suspend fun sessions(): List<TrainingSession>
    @Query("SELECT * FROM rule") suspend fun rules(): List<Rule>
    @Query("SELECT * FROM usage_day") suspend fun usageDays(): List<UsageDay>
    @Query("SELECT * FROM urge_log") suspend fun urgeLogs(): List<UrgeLog>
    @Query("SELECT * FROM event") suspend fun events(): List<Event>
    @Query("SELECT * FROM weekly_review") suspend fun weeklyReviews(): List<WeeklyReview>
    @Query("SELECT * FROM ai_call") suspend fun aiCalls(): List<AiCall>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSkills(items: List<Skill>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSubSkills(items: List<SubSkill>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDrills(items: List<Drill>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putUnits(items: List<StudyUnit>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReviews(items: List<Review>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTransfers(items: List<Transfer>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMatchLogs(items: List<MatchLog>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSessions(items: List<TrainingSession>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRules(items: List<Rule>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putUsageDays(items: List<UsageDay>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putUrgeLogs(items: List<UrgeLog>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEvents(items: List<Event>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWeeklyReviews(items: List<WeeklyReview>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAiCalls(items: List<AiCall>)

    @Query("DELETE FROM skill") suspend fun clearSkill()
    @Query("DELETE FROM sub_skill") suspend fun clearSubSkill()
    @Query("DELETE FROM drill") suspend fun clearDrill()
    @Query("DELETE FROM study_unit") suspend fun clearStudyUnit()
    @Query("DELETE FROM review") suspend fun clearReview()
    @Query("DELETE FROM transfer") suspend fun clearTransfer()
    @Query("DELETE FROM match_log") suspend fun clearMatchLog()
    @Query("DELETE FROM session") suspend fun clearSession()
    @Query("DELETE FROM rule") suspend fun clearRule()
    @Query("DELETE FROM usage_day") suspend fun clearUsageDay()
    @Query("DELETE FROM urge_log") suspend fun clearUrgeLog()
    @Query("DELETE FROM event") suspend fun clearEvent()
    @Query("DELETE FROM weekly_review") suspend fun clearWeeklyReview()
    @Query("DELETE FROM ai_call") suspend fun clearAiCall()

}
