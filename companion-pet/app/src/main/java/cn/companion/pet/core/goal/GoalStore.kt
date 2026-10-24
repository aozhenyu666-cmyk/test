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

    fun setTaskStatus(id: String, status: TaskStatus, evidence: String, now: Long): Boolean = synchronized(lock) {
        if (!ready()) return false
        val s = read()
        if (s.tasks.none { it.id == id }) return false
        write(s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(status = status, evidence = evidence.take(200), updatedAt = now) else it }))
    }

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
