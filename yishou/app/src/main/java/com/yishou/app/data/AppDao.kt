package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 全部数据库读写都在这一个 DAO 里，方便一眼看全。
 * 只有本应用写入数据库。
 */
@Dao
abstract class AppDao {

    // ---------- 任务 ----------

    @Query("SELECT * FROM task WHERE isCurrent = 1 LIMIT 1")
    abstract fun observeCurrentTask(): Flow<Task?>

    @Query("SELECT * FROM task WHERE isCurrent = 1 LIMIT 1")
    abstract suspend fun getCurrentTask(): Task?

    @Query("SELECT * FROM task WHERE id = :id")
    abstract suspend fun getTask(id: Long): Task?

    @Query("SELECT * FROM task ORDER BY createdAt DESC")
    abstract fun observeAllTasks(): Flow<List<Task>>

    @Insert
    abstract suspend fun insertTask(task: Task): Long

    @Update
    abstract suspend fun updateTask(task: Task)

    @Query("UPDATE task SET isCurrent = 0 WHERE isCurrent = 1")
    abstract suspend fun clearCurrentFlag()

    @Query("UPDATE task SET isCurrent = 1 WHERE id = :id")
    abstract suspend fun setCurrentFlag(id: Long)

    /** 新建任务并设为当前任务，同时写入它的断点。返回新任务 id。 */
    @Transaction
    open suspend fun createCurrentTask(task: Task, known: String, stuck: String, nextQuestion: String, now: Long): Long {
        clearCurrentFlag()
        val id = insertTask(task.copy(id = 0, isCurrent = true))
        upsertBreakpoint(Breakpoint(id, known, stuck, nextQuestion, pendingCoachMove = null, updatedAt = now))
        return id
    }

    /**
     * 手动修改任务和断点。陪练已经出好的一手是按旧内容出的，一并清空，下次重新开局。
     */
    @Transaction
    open suspend fun editTask(task: Task, known: String, stuck: String, nextQuestion: String, now: Long) {
        updateTask(task)
        upsertBreakpoint(Breakpoint(task.id, known, stuck, nextQuestion, pendingCoachMove = null, updatedAt = now))
    }

    @Transaction
    open suspend fun switchCurrentTask(id: Long) {
        clearCurrentFlag()
        setCurrentFlag(id)
    }

    // ---------- 断点 ----------

    @Query("SELECT * FROM breakpoint WHERE taskId = :taskId")
    abstract fun observeBreakpoint(taskId: Long): Flow<Breakpoint?>

    @Query("SELECT * FROM breakpoint WHERE taskId = :taskId")
    abstract suspend fun getBreakpoint(taskId: Long): Breakpoint?

    @Upsert
    abstract suspend fun upsertBreakpoint(breakpoint: Breakpoint)

    // ---------- 轮 ----------

    @Insert
    abstract suspend fun insertRound(round: Round): Long

    /** 保存一轮并覆盖断点，两件事要么都成功要么都不做。返回 Round id。 */
    @Transaction
    open suspend fun saveRound(round: Round, breakpoint: Breakpoint): Long {
        val id = insertRound(round)
        upsertBreakpoint(breakpoint)
        return id
    }

    @Query("SELECT * FROM round WHERE createdAt >= :from AND createdAt < :to ORDER BY createdAt")
    abstract suspend fun roundsBetween(from: Long, to: Long): List<Round>

    /** 主页对话流：某个时刻以来的全部轮次 */
    @Query("SELECT * FROM round WHERE createdAt >= :since ORDER BY createdAt")
    abstract fun observeRoundsSince(since: Long): Flow<List<Round>>

    @Query("SELECT * FROM round ORDER BY createdAt DESC LIMIT 1")
    abstract suspend fun lastRound(): Round?

    @Query("SELECT COUNT(*) FROM round WHERE source = :source AND createdAt >= :from AND createdAt < :to")
    abstract suspend fun countRounds(source: String, from: Long, to: Long): Int

    /** 离线“保存并通过”的次数：未经判定的轮数 */
    @Query("SELECT COUNT(*) FROM round WHERE judged = 0 AND createdAt >= :since")
    abstract suspend fun countUnjudgedSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM round WHERE source = :source AND effective = 1 AND createdAt >= :from AND createdAt < :to")
    abstract suspend fun countEffective(source: String, from: Long, to: Long): Int

    // ---------- 放行 ----------

    @Insert
    abstract suspend fun insertPass(pass: Pass): Long

    /** 该应用组还没到期的放行，到期的自然查不到 */
    @Query("SELECT * FROM pass WHERE packageGroup = :group AND endAt > :now ORDER BY endAt DESC LIMIT 1")
    abstract suspend fun activePass(group: String, now: Long): Pass?

    /** 离线保存：原话存成一轮（judged = false），同时发放一次放行。断点不变。 */
    @Transaction
    open suspend fun saveOfflineRound(round: Round, pass: Pass): Pass {
        val roundId = insertRound(round)
        val saved = pass.copy(roundId = roundId)
        return saved.copy(id = insertPass(saved))
    }

    // ---------- 每晚总结 ----------

    @Upsert
    abstract suspend fun upsertSummary(summary: DailySummary)

    /** 最近总结出的规则（非空），让陪练在合适时提醒调用 */
    @Query("SELECT rule FROM daily_summary WHERE rule != '' ORDER BY date DESC LIMIT :limit")
    abstract suspend fun recentRules(limit: Int): List<String>

    @Query("SELECT * FROM daily_summary WHERE date = :date")
    abstract suspend fun getSummary(date: String): DailySummary?

    @Query("SELECT * FROM daily_summary ORDER BY date DESC LIMIT 60")
    abstract fun observeSummaries(): Flow<List<DailySummary>>

    /** 保存总结，并把“明天第一问”写进断点（没有当前任务时 breakpoint 为 null）。 */
    @Transaction
    open suspend fun saveSummary(summary: DailySummary, breakpoint: Breakpoint?) {
        upsertSummary(summary)
        if (breakpoint != null) upsertBreakpoint(breakpoint)
    }
}
