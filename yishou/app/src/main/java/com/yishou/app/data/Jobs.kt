package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 做事：一件要做完的事，一步一步走。
 * 可以开头就拆好（“10 道题”拆成 10 步、自己列几步），也可以走一步看一步（做完一步再说下一步是什么）。
 */
@Entity(tableName = "job", indices = [Index("createdAt")])
data class Job(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 取值见 [JobMode] */
    val mode: String,
    /** 估计总共要多少分钟（可空） */
    val estimateMinutes: Int?,
    val createdAt: Long,
    val finishedAt: Long? = null,
    /** 做完时收口的一句 */
    val takeaway: String = "",
)

object JobMode {
    /** 按份数拆：10 道题、3 篇文章 */
    const val COUNT = "count"
    /** 自己列好几步 */
    const val LIST = "list"
    /** 走一步看一步 */
    const val OPEN = "open"
}

@Entity(tableName = "job_step", indices = [Index("jobId")])
data class JobStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val idx: Int,
    val text: String,
    val startedAt: Long? = null,
    val doneAt: Long? = null,
    /** 取值见 [StepResult]；空表示还没做 */
    val result: String = "",
    /** 这一步做完记的一句 */
    val note: String = "",
)

object StepResult {
    const val OK = "ok"
    const val MISS = "miss"
    const val STUCK = "stuck"
    const val SKIP = "skip"

    fun label(r: String) = when (r) {
        OK -> "做成了"
        MISS -> "没做成"
        STUCK -> "卡住了"
        SKIP -> "跳过"
        else -> "还没做"
    }
}

@Dao
abstract class JobDao {
    @Insert
    abstract suspend fun insertJob(j: Job): Long

    @Update
    abstract suspend fun updateJob(j: Job)

    @Delete
    abstract suspend fun deleteJob(j: Job)

    @Query("SELECT * FROM job WHERE id = :id")
    abstract suspend fun getJob(id: Long): Job?

    @Query("SELECT * FROM job WHERE id = :id")
    abstract fun observeJob(id: Long): Flow<Job?>

    @Query("SELECT * FROM job ORDER BY createdAt DESC LIMIT 100")
    abstract fun observeJobs(): Flow<List<Job>>

    @Insert
    abstract suspend fun insertSteps(steps: List<JobStep>)

    @Insert
    abstract suspend fun insertStep(step: JobStep): Long

    @Update
    abstract suspend fun updateStep(step: JobStep)

    @Query("DELETE FROM job_step WHERE jobId = :jobId")
    abstract suspend fun deleteSteps(jobId: Long)

    @Query("SELECT * FROM job_step WHERE jobId = :jobId ORDER BY idx")
    abstract suspend fun steps(jobId: Long): List<JobStep>

    @Query("SELECT * FROM job_step WHERE jobId = :jobId ORDER BY idx")
    abstract fun observeSteps(jobId: Long): Flow<List<JobStep>>

    @Query("SELECT * FROM job_step WHERE doneAt >= :since ORDER BY doneAt")
    abstract fun observeStepsDoneSince(since: Long): Flow<List<JobStep>>

    @Query("SELECT * FROM job_step")
    abstract fun observeAllSteps(): Flow<List<JobStep>>
}
