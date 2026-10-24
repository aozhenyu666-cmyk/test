package cn.companion.pet.core.goal

import kotlinx.serialization.Serializable

@Serializable
enum class GoalStatus { ACTIVE, PAUSED, COMPLETED, CANCELLED }

@Serializable
enum class TaskStatus { PENDING, RUNNING, WAITING, BLOCKED, DONE }

@Serializable
data class Goal(
    val id: String,
    val text: String,
    val successCriteria: String = "",
    val allowedActions: List<String> = emptyList(),
    val priority: Int = 0,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val createdAt: Long,
    val lastProgressAt: Long = createdAt,
    val lastError: String = "",
)

@Serializable
data class Task(
    val id: String,
    val goalId: String,
    val nextStep: String,
    val actionKind: String = "",
    val risk: String = "read",
    val status: TaskStatus = TaskStatus.PENDING,
    val dueAt: Long = 0,
    val wakeCondition: String = "",
    val evidence: String = "",
    val updatedAt: Long,
)

@Serializable
data class FollowUp(
    val id: String,
    val goalId: String,
    val condition: String,
    val nextCheckAt: Long,
    val lastResult: String = "",
    val attempts: Int = 0,
    val active: Boolean = true,
)

@Serializable
data class AgentEpisode(
    val id: String,
    val goalId: String? = null,
    val at: Long,
    val kind: String,
    val summary: String,
    val observationId: String? = null,
    val actionId: String? = null,
    val result: String = "",
    val error: String = "",
)

@Serializable
data class GoalState(
    val goals: List<Goal> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val followUps: List<FollowUp> = emptyList(),
    val episodes: List<AgentEpisode> = emptyList(),
    val tombstones: List<String> = emptyList(),
)

enum class GoalDecision { NO_ACTION, ASK_USER, READ_TOOL, PROPOSE_WRITE, EXECUTE_ALLOWED, WAIT, COMPLETE }

data class GoalPlan(
    val decision: GoalDecision,
    val goalId: String? = null,
    val taskId: String? = null,
    val reason: String,
)
