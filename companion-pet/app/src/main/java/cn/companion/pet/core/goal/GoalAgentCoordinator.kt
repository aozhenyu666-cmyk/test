package cn.companion.pet.core.goal

import cn.companion.pet.core.action.ActionStatus
import cn.companion.pet.core.agent.AgentContext
import cn.companion.pet.core.agent.AgentDecision
import cn.companion.pet.core.agent.AgentLoop
import cn.companion.pet.core.agent.AgentTurnResult
import cn.companion.pet.core.agent.AgentTurnStatus
import java.util.UUID

data class GoalTurnResult(
    val plan: GoalPlan,
    val agent: AgentTurnResult? = null,
    val taskStatus: TaskStatus? = null,
    val note: String = "",
)

/**
 * B1 最小编排：GoalSupervisor 只给建议；有真实 task 时才调用现有 AgentLoop，
 * 结果只写成 WAITING/DONE 证据或 episode，不把“动作已发出”当完成。
 */
class GoalAgentCoordinator(
    private val store: GoalStore,
    private val supervisor: GoalSupervisor,
    private val loop: AgentLoop,
) {
    suspend fun runOnce(
        base: AgentContext,
        autonomy: Boolean,
        observationValid: Boolean = base.observation != null,
        cancelled: () -> Boolean = { false },
    ): GoalTurnResult {
        val plan = supervisor.decide(base.now, base.budgetRemaining, observationValid)
        if (plan.decision == GoalDecision.COMPLETE || plan.decision == GoalDecision.NO_ACTION ||
            plan.decision == GoalDecision.ASK_USER || plan.decision == GoalDecision.WAIT) {
            store.recordEpisode(AgentEpisode(UUID.randomUUID().toString(), plan.goalId, base.now,
                "plan", plan.reason.take(160)))
            return GoalTurnResult(plan, note = plan.reason)
        }
        val task = plan.taskId?.let { id -> store.state().tasks.firstOrNull { it.id == id } }
            ?: return GoalTurnResult(plan, note = "目标计划没有可执行任务")
        val context = base.copy(goalSummary = store.state().goals.firstOrNull { it.id == plan.goalId }?.text,
            goalId = plan.goalId, taskId = plan.taskId)
        val result = loop.run(context, autonomy, cancelled)
        val status = when {
            result.status == AgentTurnStatus.EXECUTED && result.execution?.status == ActionStatus.SUCCESS &&
                task.risk == "read" -> TaskStatus.WAITING
            result.status == AgentTurnStatus.NEEDS_CONFIRMATION -> TaskStatus.WAITING
            result.status == AgentTurnStatus.CANCELLED -> TaskStatus.WAITING
            else -> TaskStatus.BLOCKED
        }
        store.setTaskStatus(task.id, status, result.execution?.reason ?: result.reason, base.now)
        store.recordEpisode(AgentEpisode(
            UUID.randomUUID().toString(), plan.goalId, base.now, "agent_turn",
            (result.text.ifBlank { result.reason }).take(200),
            observationId = null, actionId = result.execution?.actionId,
            result = result.execution?.status?.name.orEmpty(), error = if (status == TaskStatus.BLOCKED) result.reason else "",
        ))
        return GoalTurnResult(plan, result, status, result.text.ifBlank { result.reason })
    }
}
