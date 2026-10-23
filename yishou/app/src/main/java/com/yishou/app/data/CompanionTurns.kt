package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** 一场视频陪练。 */
@Entity(tableName = "companion_session", indices = [Index("startedAt")])
data class CompanionSessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** 这次要弄懂什么 */
    val goal: String,
    val minutes: Int,
    val gear: String,
    /** 目标应用，只记包名 */
    val pkg: String = "",
    /** 真的听到看到多久（不是挂了多久） */
    val materialSec: Int = 0,
    val askedCount: Int = 0,
    val missedCount: Int = 0,
)

/**
 * 一问一答。
 *
 * `sourceText` 是材料原文摘录，`criteria` 和 `delta` 是模型/引擎的判断——**两者分开存**，
 * 事后回看能分清「材料确实这么说」和「当时觉得他该这么说」。
 *
 * `dedupeKey` 唯一：同一问重试只落一行。重试往往来自网络重发和晚到的旧回调，
 * 不挡住的话一次提问会在账上变成三次。
 */
@Entity(
    tableName = "companion_turn",
    indices = [Index("sessionId"), Index("askedAt"), Index(value = ["dedupeKey"], unique = true)],
)
data class CompanionTurnRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val turnId: Long,
    val askedAt: Long,
    /** 思考动作：复述 / 预测 / 迁移 / 反例 / 找条件 */
    val move: String,
    val question: String,
    /** 内部判据，不念给用户听 */
    val criteria: String = "",
    /** 出处：材料里的证据编号，逗号分开 */
    val evidenceIds: String = "",
    /** 材料原文摘录 */
    val sourceText: String = "",
    val answer: String = "",
    /** HIT / NEAR / MISS / NONE（没判成）/ SKIP（跳过）；后两种不算答错 */
    val verdict: String = "NONE",
    val delta: String = "",
    val answeredAt: Long? = null,
    val dedupeKey: String,
    val epoch: Int = 1,
    /** 用户选择了保留训练卡图片才有值；删行时要一起删文件 */
    val imagePath: String? = null,
)

@Dao
abstract class CompanionDao {

    @Insert
    abstract suspend fun openSession(s: CompanionSessionRow): Long

    @Query("UPDATE companion_session SET endedAt = :at, materialSec = :sec, askedCount = :asked, missedCount = :missed WHERE id = :id")
    abstract suspend fun closeSession(id: Long, at: Long, sec: Int, asked: Int, missed: Int)

    /** 同一问重试只落一行：撞到 dedupeKey 就忽略，返回 -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTurn(t: CompanionTurnRow): Long

    /** 答案只写第一次；已经答过的不会被重试覆盖 */
    @Query("UPDATE companion_turn SET answer = :answer, verdict = :verdict, delta = :delta, answeredAt = :at WHERE dedupeKey = :key AND answeredAt IS NULL")
    abstract suspend fun answer(key: String, answer: String, verdict: String, delta: String, at: Long): Int

    @Query("SELECT * FROM companion_turn WHERE dedupeKey = :key")
    abstract suspend fun byKey(key: String): CompanionTurnRow?

    @Query("SELECT * FROM companion_turn WHERE sessionId = :sessionId ORDER BY askedAt")
    abstract suspend fun turnsOf(sessionId: Long): List<CompanionTurnRow>

    /** 账本要看这段时间答过的（没答的不进账） */
    @Query("SELECT * FROM companion_turn WHERE answeredAt >= :from AND answeredAt < :to ORDER BY answeredAt")
    abstract suspend fun answeredBetween(from: Long, to: Long): List<CompanionTurnRow>

    /** 下一问要接上以前的差值：最近几条没到/差一点的 */
    @Query("SELECT * FROM companion_turn WHERE verdict IN ('MISS', 'NEAR') AND answeredAt IS NOT NULL ORDER BY answeredAt DESC LIMIT :n")
    abstract suspend fun recentWeak(n: Int): List<CompanionTurnRow>

    @Query("SELECT imagePath FROM companion_turn WHERE sessionId = :sessionId AND imagePath IS NOT NULL")
    abstract suspend fun imagePaths(sessionId: Long): List<String>

    @Query("DELETE FROM companion_turn WHERE sessionId = :sessionId")
    abstract suspend fun clearTurns(sessionId: Long): Int

    @Query("DELETE FROM companion_session WHERE id = :sessionId")
    abstract suspend fun clearSession(sessionId: Long): Int

    @Query("SELECT * FROM companion_session ORDER BY startedAt DESC LIMIT :n")
    abstract suspend fun recentSessions(n: Int): List<CompanionSessionRow>
}
