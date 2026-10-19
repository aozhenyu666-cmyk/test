package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** 两条语音路线共用的计划与收线；未收线的记录保留给下次接续。 */
@Entity(tableName = "partner_session")
data class PartnerSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val material: String,
    val goal: String,
    val minutes: Int,
    val gear: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val presentMs: Long = 0,
    val leaves: Int = 0,
    val closeLine: String = "",
    val nextStep: String = "",
    val route: String,
)

@Dao
abstract class PartnerDao {
    @Insert abstract suspend fun insert(row: PartnerSession): Long
    @Query("SELECT * FROM partner_session WHERE closeLine = '' ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun pending(): PartnerSession?
    @Query("SELECT * FROM partner_session WHERE closeLine != '' ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun latestClosed(): PartnerSession?
    @Query("SELECT * FROM partner_session WHERE id = :id")
    abstract suspend fun get(id: Long): PartnerSession?
    @Query("UPDATE partner_session SET presentMs = :presentMs, leaves = :leaves WHERE id = :id AND endedAt IS NULL")
    abstract suspend fun checkpoint(id: Long, presentMs: Long, leaves: Int)
    @Query("UPDATE partner_session SET endedAt = :at, presentMs = :presentMs, leaves = :leaves WHERE id = :id AND endedAt IS NULL")
    abstract suspend fun end(id: Long, at: Long, presentMs: Long, leaves: Int)
    @Query("UPDATE partner_session SET closeLine = :line, nextStep = :next WHERE id = :id AND endedAt IS NOT NULL AND closeLine = ''")
    abstract suspend fun close(id: Long, line: String, next: String): Int
    @Query("DELETE FROM partner_session WHERE id = :id AND endedAt IS NOT NULL")
    abstract suspend fun delete(id: Long)
}
