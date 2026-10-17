package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 煮过的一锅海龟汤：哪道题、问了几问、亮选项前有没有猜中、最后选对没有 */
@Entity(tableName = "soup", indices = [Index("cardId"), Index("createdAt")])
data class Soup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    /** 问了几问；去 ChatGPT 玩的记 -1 */
    val asks: Int,
    val guesses: Int,
    val solved: Boolean,
    val picked: Int? = null,
    val correct: Boolean? = null,
    /** 问答记录（JSON 数组，每条“问｜答”） */
    val log: String = "[]",
    val createdAt: Long,
)

@Dao
abstract class SoupDao {
    @Insert
    abstract suspend fun insert(s: Soup): Long

    @Query("SELECT cardId FROM soup")
    abstract suspend fun playedCardIds(): List<Long>

    @Query("SELECT * FROM soup ORDER BY createdAt DESC")
    abstract fun observe(): Flow<List<Soup>>
}
