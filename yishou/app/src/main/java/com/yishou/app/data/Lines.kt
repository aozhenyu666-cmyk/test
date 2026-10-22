package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 主线：你现在正在想的一件事（在学的一块、要做的决定、读到的一段、手头的事）。
 * 一手一手往下走：每一手是一问一答；走到哪里，断点就记在 nextQuestion 上，下次打开接着下。
 */
@Entity(tableName = "line", indices = [Index("updatedAt")])
data class Line(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 这一局围着转的那个核心问题（不准跑题，就是不准离开它） */
    val anchor: String,
    /** 到现在为止的结论（JSON 数组，最新的在后） */
    val conclusions: String = "[]",
    /** 还没弄清的（JSON 数组） */
    val doubts: String = "[]",
    /** 断点：下一问 */
    val nextQuestion: String = "",
    /** 下一问属于六面里的哪一面 */
    val face: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
    val closedAt: Long? = null,
    /** 从哪段素材开的（可空） */
    val materialId: Long? = null,
)

/** 一手：一问一答，以及本机怎么判的 */
@Entity(tableName = "move", indices = [Index("lineId"), Index("createdAt")])
data class Move(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lineId: Long,
    val question: String,
    val answer: String,
    /** 取值见 line.Ask */
    val ask: String,
    /** 取值见 [LineVerdict] */
    val verdict: String,
    /** 判语：哪条规则没过、教练说了什么 */
    val note: String = "",
    /** 这一手提炼出的结论或疑问 */
    val gist: String = "",
    val face: Int,
    val voice: Boolean = false,
    /** 从问出来到开始交的时间 */
    val latencyMs: Long = 0,
    val createdAt: Long,
)

object LineVerdict {
    /** 接住了 */
    const val OK = "ok"
    /** 口水 / 太短 / 没结构：没过规则 */
    const val THIN = "thin"
    /** 不知道、随便、不说话 */
    const val REFUSED = "refused"
    /** 没接住问题（答非所问） */
    const val DRIFT = "drift"
    /** 降阶到底还是不想，先放着 */
    const val PARKED = "parked"
}

@Dao
abstract class LineDao {
    @Insert
    abstract suspend fun insertLine(l: Line): Long

    @Update
    abstract suspend fun updateLine(l: Line)

    @Query("SELECT * FROM line WHERE id = :id")
    abstract suspend fun getLine(id: Long): Line?

    @Query("SELECT * FROM line WHERE closedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    abstract suspend fun latestOpen(): Line?

    @Query("SELECT * FROM line ORDER BY updatedAt DESC LIMIT 100")
    abstract fun observeLines(): Flow<List<Line>>

    @Insert
    abstract suspend fun insertMove(m: Move): Long

    @Query("SELECT * FROM move WHERE lineId = :lineId ORDER BY createdAt DESC LIMIT :n")
    abstract suspend fun recentMoves(lineId: Long, n: Int): List<Move>

    @Query("SELECT * FROM move WHERE lineId = :lineId ORDER BY createdAt")
    abstract fun observeMoves(lineId: Long): Flow<List<Move>>

    @Query("SELECT * FROM move WHERE createdAt >= :since ORDER BY createdAt")
    abstract fun observeMovesSince(since: Long): Flow<List<Move>>

    @Query("SELECT * FROM move WHERE createdAt >= :from AND createdAt < :to ORDER BY createdAt")
    abstract suspend fun movesBetween(from: Long, to: Long): List<Move>
}
