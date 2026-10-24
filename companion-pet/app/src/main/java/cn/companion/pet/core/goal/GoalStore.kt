package cn.companion.pet.core.goal

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class GoalStoreStatus { OK, EMPTY, CORRUPT, READ_ONLY, IO_FAILURE }

/**
 * 目标持久化：写入必须成功才返回新对象；失败保留旧文件。损坏文件不会被当成“没有目标”，
 * 也不会被下一次写入覆盖。活动目标/任务/跟进达到上限时拒绝新增，不静默逐出。
 */
class GoalStore(
    private val root: File,
    private val maxEpisodes: Int = 200,
    private val replace: (File, File) -> Boolean = { from, to ->
        runCatching {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrElse {
            runCatching {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
        }
    },
) {
    private val file = File(root, "goals/state.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var writable = true
    @Volatile var status: GoalStoreStatus = GoalStoreStatus.EMPTY
        private set
    @Volatile var lastError: String? = null
        private set

    fun state(): GoalState = synchronized(lock) { read() }

    fun activeSummary(): String? = synchronized(lock) {
        read().goals.filter { it.status == GoalStatus.ACTIVE }.maxByOrNull { it.priority }
            ?.let { it.text.take(120) }
    }

    fun addGoal(text: String, successCriteria: String = "", allowedActions: List<String> = emptyList(), now: Long): Goal? = synchronized(lock) {
        if (!ready()) return null
        val clean = text.trim().take(300)
        if (clean.isEmpty()) return null
        val s = read()
        if (s.goals.size >= MAX_GOALS) return fail("目标数量已达上限")
        val goal = Goal(UUID.randomUUID().toString(), clean, successCriteria.take(300), allowedActions.take(8),
            createdAt = now, lastProgressAt = now)
        if (!write(s.copy(goals = s.goals + goal))) return null
        goal
    }

    fun setGoalStatus(id: String, status: GoalStatus, now: Long): Boolean = synchronized(lock) {
        if (!ready()) return false
        val s = read()
        if (s.goals.none { it.id == id }) return false
        write(s.copy(goals = s.goals.map { if (it.id == id) it.copy(status = status, lastProgressAt = now) else it }))
    }

    fun deleteGoal(id: String, now: Long): Boolean = synchronized(lock) {
        if (!ready()) return false
        val s = read()
        if (s.goals.none { it.id == id }) return false
        write(s.copy(
            goals = s.goals.filterNot { it.id == id },
            tasks = s.tasks.filterNot { it.goalId == id },
            followUps = s.followUps.filterNot { it.goalId == id },
            episodes = s.episodes.filterNot { it.goalId == id },
            tombstones = (s.tombstones + id).takeLast(MAX_TOMBSTONES),
        ))
    }

    fun addTask(goalId: String, nextStep: String, actionKind: String = "", risk: String = "read", dueAt: Long = 0, now: Long): Task? = synchronized(lock) {
        if (!ready()) return null
        val s = read()
        if (s.goals.none { it.id == goalId }) return null
        if (s.tasks.count { it.goalId == goalId } >= MAX_TASKS_PER_GOAL) return fail("任务数量已达上限")
        val task = Task(UUID.randomUUID().toString(), goalId, nextStep.take(300), actionKind, risk, dueAt = dueAt, updatedAt = now)
        if (!write(s.copy(tasks = s.tasks + task))) return null
        task
    }

    fun setTaskStatus(id: String, status: TaskStatus, evidence: String, now: Long, wakeCondition: String? = null): Boolean = synchronized(lock) {
        if (!ready()) return false
        val s = read()
        val task = s.tasks.firstOrNull { it.id == id } ?: return false
        val goals = if (status == TaskStatus.DONE) s.goals.map { if (it.id == task.goalId) it.copy(lastProgressAt = now) else it } else s.goals
        write(s.copy(goals = goals, tasks = s.tasks.map {
            if (it.id == id) it.copy(status = status, evidence = evidence.take(200), updatedAt = now,
                wakeCondition = wakeCondition ?: it.wakeCondition) else it
        }))
    }

    /**
     * 用户批准的写动作执行完后回写任务：只有回执 SUCCESS 才算 DONE；
     * 已执行但无法核实、失败、不可用都停在 BLOCKED 等用户处理；被取消回到 PENDING 可再提议。
     */
    fun applyPendingResult(taskId: String, result: cn.companion.pet.core.action.ActionResult, now: Long): Boolean {
        val (status, evidence) = when {
            result.status == cn.companion.pet.core.action.ActionStatus.SUCCESS -> TaskStatus.DONE to ("已执行：" + result.reason)
            result.data["unverified"] == "true" -> TaskStatus.BLOCKED to ("已执行但没能确认结果，请看一眼后手动标记：" + result.reason)
            result.status == cn.companion.pet.core.action.ActionStatus.CANCELLED -> TaskStatus.PENDING to "执行被取消，可以再提议"
            else -> TaskStatus.BLOCKED to ("没成功：" + result.reason)
        }
        return setTaskStatus(taskId, status, evidence, now, wakeCondition = "")
    }

    /** 用户拒绝了这一步：停在 BLOCKED，不再反复提议，直到用户在设置页点“重试”。 */
    fun markTaskRejected(taskId: String, now: Long): Boolean =
        setTaskStatus(taskId, TaskStatus.BLOCKED, "你拒绝了这一步", now, wakeCondition = "")

    fun addFollowUp(goalId: String, condition: String, nextCheckAt: Long): FollowUp? = synchronized(lock) {
        if (!ready()) return null
        val s = read()
        if (s.goals.none { it.id == goalId }) return null
        if (s.followUps.count { it.goalId == goalId } >= MAX_FOLLOW_UPS_PER_GOAL) return fail("跟进数量已达上限")
        val f = FollowUp(UUID.randomUUID().toString(), goalId, condition.take(200), nextCheckAt)
        if (!write(s.copy(followUps = s.followUps + f))) return null
        f
    }

    fun recordEpisode(episode: AgentEpisode): Boolean = synchronized(lock) {
        if (!ready()) return false
        val s = read()
        // 只裁旧 episode（可观察历史），绝不裁活动目标/任务/跟进。
        write(s.copy(episodes = (s.episodes + episode).takeLast(maxEpisodes)))
    }

    private fun ready(): Boolean = synchronized(lock) {
        if (!writable) {
            lastError = lastError ?: "目标存储不可写"
            return false
        }
        if (!file.exists()) {
            status = GoalStoreStatus.EMPTY
            return true
        }
        read()
        writable
    }

    private fun read(): GoalState {
        if (!file.exists()) {
            if (writable) status = GoalStoreStatus.EMPTY
            return GoalState()
        }
        return runCatching { json.decodeFromString(GoalState.serializer(), file.readText()) }
            .getOrElse {
                writable = false
                status = GoalStoreStatus.CORRUPT
                lastError = "目标文件损坏，未覆盖；请先恢复/备份"
                GoalState()
            }.also { if (writable) status = GoalStoreStatus.OK }
    }

    private fun write(state: GoalState): Boolean {
        if (!writable) return false
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(GoalState.serializer(), state).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!replace(tmp, file)) {
                runCatching { tmp.delete() }
                status = GoalStoreStatus.IO_FAILURE
                lastError = "目标写入未能原子替换，旧文件保留"
                return false
            }
            status = GoalStoreStatus.OK
            lastError = null
            true
        } catch (t: Throwable) {
            runCatching { File(file.parentFile, "." + file.name + ".tmp").delete() }
            status = GoalStoreStatus.IO_FAILURE
            lastError = "目标写入失败，旧文件保留"
            false
        }
    }

    private fun fail(message: String): Nothing? {
        lastError = message
        return null
    }

    companion object {
        const val MAX_GOALS = 50
        const val MAX_TASKS_PER_GOAL = 100
        const val MAX_FOLLOW_UPS_PER_GOAL = 50
        const val MAX_TOMBSTONES = 200
    }
}
