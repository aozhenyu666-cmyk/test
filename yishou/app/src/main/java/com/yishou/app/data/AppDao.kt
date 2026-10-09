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
}
