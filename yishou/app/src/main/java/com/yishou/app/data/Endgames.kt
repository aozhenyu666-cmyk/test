package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 一个残局：真材料里截下来的一小段（前面 80% 的铺垫已经替你走完），里面藏着一个决定结论的破绽。
 * 你先点破绽，再翻底牌。残局只从你导入或分享的东西里加工出来，不现编。
 */
@Entity(tableName = "endgame", indices = [Index(value = ["sourceKey"], unique = true), Index("dueAt")])
data class Endgame(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 残局正文（原文截出来的，几十到一百多字） */
    val text: String,
    /** 破绽：正文里的一段原文 */
    val crux: String,
    /** 题目：要你干什么（题卡来的就是原题的问题） */
    val ask: String,
    /** 准星：往哪个方向看，不点破 */
    val aim: String,
    /** 底牌：高手那一枪 */
    val shot: String,
    /** 走子铁律：A + B = 必 C；题卡来的可以没有 */
    val law: String = "",
    /** 取值见 [EndgameSource] */
    val source: String,
    /** 去重用：card:12、material:5#1 */
    val sourceKey: String,
    val createdAt: Long,
    val seen: Int = 0,
    /** 第一下就点中的次数 */
    val hits: Int = 0,
    val lastAt: Long? = null,
    val lastHit: Boolean = false,
    /** 下次什么时候再出 */
    val dueAt: Long = 0,
)

object EndgameSource {
    const val CARD = "card"
    const val MATERIAL = "material"

    fun label(s: String) = when (s) {
        CARD -> "题库"
        else -> "素材"
    }
}

@Dao
abstract class EndgameDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(e: Endgame): Long

    @Update
    abstract suspend fun update(e: Endgame)

    @Query("SELECT * FROM endgame WHERE dueAt <= :now ORDER BY dueAt LIMIT :n")
    abstract suspend fun due(now: Long, n: Int): List<Endgame>

    @Query("SELECT COUNT(*) FROM endgame WHERE dueAt <= :now")
    abstract suspend fun countDue(now: Long): Int

    @Query("SELECT sourceKey FROM endgame")
    abstract suspend fun keys(): List<String>

    @Query("SELECT * FROM endgame ORDER BY createdAt DESC")
    abstract fun observe(): Flow<List<Endgame>>

    @Query("SELECT * FROM endgame WHERE lastAt >= :from AND lastAt < :to ORDER BY lastAt")
    abstract suspend fun playedBetween(from: Long, to: Long): List<Endgame>

    @Query("SELECT * FROM endgame WHERE lastAt >= :since ORDER BY lastAt")
    abstract fun observePlayedSince(since: Long): kotlinx.coroutines.flow.Flow<List<Endgame>>
}
