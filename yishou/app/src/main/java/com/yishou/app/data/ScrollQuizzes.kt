package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** 刷着练问过的一句：刷到了什么、问了什么、你怎么回的、判成什么样 */
@Entity(tableName = "scroll_quiz", indices = [Index("askedAt")])
data class ScrollQuizRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val askedAt: Long,
    /** 在哪个应用里刷到的（只记包名） */
    val pkg: String = "",
    val lens: String,
    val seen: String,
    val question: String,
    val reference: String,
    /** 屏幕上的字 */
    val text: String = "",
    val answer: String = "",
    /** 取值见 scroll.Verdict 的名字；没回是空，跳过是 SKIP */
    val verdict: String = "",
    /** 判语 */
    val line: String = "",
    val answeredAt: Long? = null,
    /** 静下来重想过一次 */
    val redoAt: Long? = null,
    val redoVerdict: String = "",
)

@Dao
abstract class ScrollQuizDao {
    @Insert
    abstract suspend fun insert(q: ScrollQuizRow): Long

    @Query("UPDATE scroll_quiz SET answer = :answer, verdict = :verdict, line = :line, answeredAt = :at WHERE id = :id")
    abstract suspend fun answer(id: Long, answer: String, verdict: String, line: String, at: Long)

    @Query("UPDATE scroll_quiz SET redoAt = :at, redoVerdict = :verdict WHERE id = :id")
    abstract suspend fun redo(id: Long, verdict: String, at: Long)

    @Query("SELECT * FROM scroll_quiz WHERE id = :id")
    abstract suspend fun get(id: Long): ScrollQuizRow?

    @Query("SELECT * FROM scroll_quiz WHERE askedAt >= :from AND askedAt < :to ORDER BY askedAt")
    abstract suspend fun between(from: Long, to: Long): List<ScrollQuizRow>

    /** 没接住、差一点、还没重想过的，最新的在前 */
    @Query("SELECT * FROM scroll_quiz WHERE verdict IN ('MISS', 'NEAR') AND redoAt IS NULL AND answeredAt IS NOT NULL ORDER BY answeredAt DESC LIMIT :n")
    abstract suspend fun toRedo(n: Int): List<ScrollQuizRow>

    /** 这段时间里回过、或者重想过的 */
    @Query("SELECT * FROM scroll_quiz WHERE (answeredAt >= :from AND answeredAt < :to) OR (redoAt >= :from AND redoAt < :to)")
    abstract suspend fun touched(from: Long, to: Long): List<ScrollQuizRow>

    @Query("SELECT * FROM scroll_quiz WHERE askedAt >= :from OR redoAt >= :from ORDER BY askedAt")
    abstract fun observeSince(from: Long): kotlinx.coroutines.flow.Flow<List<ScrollQuizRow>>
}
