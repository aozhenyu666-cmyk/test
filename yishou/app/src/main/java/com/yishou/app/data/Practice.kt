package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 预判本里的一条：下注（先押一个判断和把握，再看结果）或预演（动手前先说三步和关键量，做完对照）。
 */
@Entity(tableName = "bet", indices = [Index("createdAt")])
data class Bet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 取值见 [BetKind] */
    val kind: String,
    /** 预演：要做的事；下注：可空 */
    val title: String,
    /** 下注：我预计……；预演：三步和关键量 */
    val claim: String,
    /** 依据 */
    val basis: String,
    /** 把握 50–100；预演为 0 */
    val confidence: Int,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    /** 0 还没对照，1 中了 / 顺利，-1 没中 / 有偏差 */
    val outcome: Int = 0,
    /** 对照时写下的差别 */
    val note: String = "",
)

object BetKind {
    const val BET = "bet"
    const val REHEARSE = "rehearse"
}

/** 间隔回响：某一轮在第 [stage] 天被想起过一次。result：1 记得，0 模糊，-1 忘了。 */
@Entity(tableName = "recall", primaryKeys = ["roundId", "stage"])
data class Recall(
    val roundId: Long,
    val stage: Int,
    val doneAt: Long,
    val result: Int,
    /** 这次凭记忆说出的原话 */
    val said: String,
)

/** 预判本和回响的读写，和主 DAO 分开，互不影响。 */
@Dao
abstract class PracticeDao {

    @Insert
    abstract suspend fun insertBet(bet: Bet): Long

    @Update
    abstract suspend fun updateBet(bet: Bet)

    @Query("SELECT * FROM bet ORDER BY createdAt DESC LIMIT 200")
    abstract fun observeBets(): Flow<List<Bet>>

    @Query("SELECT * FROM bet WHERE kind = 'bet' AND outcome != 0")
    abstract suspend fun resolvedBets(): List<Bet>

    @Upsert
    abstract suspend fun upsertRecall(recall: Recall)

    @Query("SELECT * FROM recall WHERE doneAt >= :since")
    abstract suspend fun recallsSince(since: Long): List<Recall>

    @Query("SELECT * FROM recall")
    abstract fun observeRecalls(): Flow<List<Recall>>
}
