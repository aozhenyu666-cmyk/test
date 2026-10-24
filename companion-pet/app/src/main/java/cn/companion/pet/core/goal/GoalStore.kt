package cn.companion.pet.core.goal

import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 目标/任务/待跟进的持久账本。原子替换，损坏文件不覆盖；删除目标时写墓碑，
 * 防止旧调用把已删除目标重新加回来。不做后台常驻调度。
 */
class GoalStore(private val root: File, private val maxEpisodes: Int = 200) {
    private val file = File(root, "goals/state.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var writable = true

    fun state(): GoalState = synchronized(lock) { read() }

    fun activeSummary(): String? = synchronized(lock) {
        read().goals.filter { it.status == GoalStatus.ACTIVE }.maxByOrNull { it.priority }
            ?.let { it.text.take(120) }
    }

    fun addGoal(text: String, successCriteria: String = "", allowedActions: List<String> = emptyList(), now: Long): Goal? = synchronized(lock) {
        val clean = text.trim().take(300)
        if (clean.isEmpty() || !writable) return null
        val s = read()
        val goal = Goal(UUID.randomUUID().toString(), clean, successCriteria.take(300), allowedActions.take(8),
            createdAt = now, lastProgressAt = now)
        write(s.copy(goals = (s.goals + goal).takeLast(50)))
        goal
    }

    fun setGoalStatus(id: String, status: GoalStatus, now: Long): Boolean = synchronized(lock) {
        val s = read()
        if (s.goals.none { it.id == id }) return false
        write(s.copy(goals = s.goals.map { if (it.id == id) it.copy(status = status, lastProgressAt = now) else it }))
        true
    }

    fun deleteGoal(id: String, now: Long): Boolean = synchronized(lock) {
        val s = read()
        if (s.goals.none { it.id == id }) return false
        write(s.copy(
            goals = s.goals.filterNot { it.id == id },
            tasks = s.tasks.filterNot { it.goalId == id },
            followUps = s.followUps.filterNot { it.goalId == id },
            episodes = s.episodes.filterNot { it.goalId == id },
            tombstones = (s.tombstones + id).takeLast(200),
        ))
        true
    }

    fun addTask(goalId: String, nextStep: String, actionKind: String = "", risk: String = "read", dueAt: Long = 0, now: Long): Task? = synchronized(lock) {
        val s = read()
        if (s.goals.none { it.id == goalId }) return null
        val task = Task(UUID.randomUUID().toString(), goalId, nextStep.take(300), actionKind, risk, dueAt = dueAt, updatedAt = now)
        write(s.copy(tasks = (s.tasks + task).takeLast(100)))
        task
    }

    fun setTaskStatus(id: String, status: TaskStatus, evidence: String, now: Long): Boolean = synchronized(lock) {
        val s = read()
        if (s.tasks.none { it.id == id }) return false
        write(s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(status = status, evidence = evidence.take(200), updatedAt = now) else it }))
        true
    }

    fun addFollowUp(goalId: String, condition: String, nextCheckAt: Long): FollowUp? = synchronized(lock) {
        val s = read()
        if (s.goals.none { it.id == goalId }) return null
        val f = FollowUp(UUID.randomUUID().toString(), goalId, condition.take(200), nextCheckAt)
        write(s.copy(followUps = (s.followUps + f).takeLast(100)))
        f
    }

    fun recordEpisode(episode: AgentEpisode): Boolean = synchronized(lock) {
        val s = read()
        write(s.copy(episodes = (s.episodes + episode).takeLast(maxEpisodes)))
        true
    }

    private fun read(): GoalState {
        if (!writable) return GoalState()
        if (!file.exists()) return GoalState()
        return runCatching { json.decodeFromString(GoalState.serializer(), file.readText()) }.getOrElse {
            writable = false
            GoalState()
        }
    }

    private fun write(state: GoalState) {
        if (!writable) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "." + file.name + ".tmp")
        tmp.writeText(json.encodeToString(GoalState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "rename failed: goals" }
        }
    }
}
