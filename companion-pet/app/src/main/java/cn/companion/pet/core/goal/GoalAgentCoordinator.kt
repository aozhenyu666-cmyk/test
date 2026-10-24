package cn.companion.pet.core.goal

import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionStatus
import cn.companion.pet.core.agent.AgentContext
import cn.companion.pet.core.agent.AgentLoop
import cn.companion.pet.core.agent.AgentTurnResult
import cn.companion.pet.core.agent.AgentTurnStatus
import java.util.UUID

data class GoalTurnResult(
    val plan: GoalPlan,
    val agent: AgentTurnResult? = null,
    val taskStatus: TaskStatus? = null,
    val note: String = "",
    /**
     * false：目标这边没有可执行的任务（缺下一步/等待/全部完成）。调用方应照常走普通 AgentLoop，
     * 不能让一个没写任务的目标把所有事件都吞掉（审计 P0-2）。
     */
    val handled: Boolean = agent != null,
)

/**
 * GoalSupervisor 只给建议；有可执行任务时才调用 AgentLoop，并把工具限定为任务写明的那一个。
 * 只读成功 = 这一步 DONE；写动作只创建待确认，批准执行后由 [GoalStore.applyPendingResult] 回写。
 * “动作已发出”不等于完成；目标本身的 COMPLETED 仍要用户在设置页确认。
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
        if (plan.decision != GoalDecision.READ_TOOL && plan.decision != GoalDecision.PROPOSE_WRITE) {
            recordPlanOnce(plan, base.now)
            return GoalTurnResult(plan, note = plan.reason, handled = false)
        }
        val task = plan.taskId?.let { id -> store.state().tasks.firstOrNull { it.id == id } }
            ?: return GoalTurnResult(plan, note = "目标计划没有可执行任务", handled = false)
        val goalText = store.state().goals.firstOrNull { it.id == plan.goalId }?.text.orEmpty()
        val context = base.copy(
            goalSummary = (goalText + "；这一步：" + task.nextStep).take(300),
            goalId = plan.goalId, taskId = plan.taskId,
        )
        val allowed = ActionKind.entries.firstOrNull { it.name == task.actionKind }?.let { setOf(it) }
        val result = loop.run(context, autonomy, cancelled, allowedKinds = allowed)
        val (status, wake) = when {
            result.status == AgentTurnStatus.EXECUTED && result.execution?.status == ActionStatus.SUCCESS -> TaskStatus.DONE to ""
            result.status == AgentTurnStatus.NEEDS_CONFIRMATION && result.pendingId != null ->
                TaskStatus.WAITING to (GoalSupervisor.PENDING_PREFIX + result.pendingId)
            // 临时问题：保持 PENDING，下次事件再试（事件账本负责退避和次数上限）。
            result.status in TRANSIENT -> TaskStatus.PENDING to ""
            else -> TaskStatus.BLOCKED to ""
        }
        store.setTaskStatus(task.id, status, result.execution?.reason ?: result.reason, base.now, wakeCondition = wake)
        store.recordEpisode(AgentEpisode(
            UUID.randomUUID().toString(), plan.goalId, base.now, "agent_turn",
            (result.text.ifBlank { result.reason }).take(200),
            observationId = null, actionId = result.execution?.actionId,
            result = result.execution?.status?.name.orEmpty(), error = if (status == TaskStatus.BLOCKED) result.reason else "",
        ))
        return GoalTurnResult(plan, result, status, result.text.ifBlank { result.reason }, handled = true)
    }

    /** 同一个目标连续得到同一条计划结论，只记一次，不把 episode 刷满。 */
    private fun recordPlanOnce(plan: GoalPlan, now: Long) {
        if (plan.goalId == null) return
        val last = store.state().episodes.lastOrNull { it.goalId == plan.goalId && it.kind == "plan" }
        if (last?.summary == plan.reason.take(160)) return
        store.recordEpisode(AgentEpisode(UUID.randomUUID().toString(), plan.goalId, now, "plan", plan.reason.take(160)))
    }

    companion object {
        private val TRANSIENT = setOf(
            AgentTurnStatus.MODEL_FAILED, AgentTurnStatus.TIMEOUT, AgentTurnStatus.CANCELLED,
            AgentTurnStatus.BUDGET_EXHAUSTED, AgentTurnStatus.DISABLED,
        )
    }
}
