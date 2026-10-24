package cn.companion.pet.core.goal

/** 只做本地计划和状态机，不执行动作；动作仍走 ActionRunner/AgentLoop。 */
class GoalSupervisor(
    private val store: GoalStore,
    /** 等待确认的任务（wakeCondition = "pending:<id>"）：那条确认还开着就不再提议。 */
    private val pendingOpen: (String) -> Boolean = { false },
) {
    fun decide(now: Long, budgetRemaining: Int, observationValid: Boolean = false): GoalPlan {
        val state = store.state()
        val active = state.goals.filter { it.status == GoalStatus.ACTIVE }.sortedByDescending { it.priority }
        if (active.isEmpty()) {
            return if (state.goals.any { it.status == GoalStatus.COMPLETED }) GoalPlan(GoalDecision.COMPLETE, reason = "目标已完成")
            else GoalPlan(GoalDecision.NO_ACTION, reason = "没有进行中的目标")
        }
        val goal = active.first()
        val tasks = state.tasks.filter { it.goalId == goal.id }
        if (tasks.isNotEmpty() && tasks.all { it.status == TaskStatus.DONE }) {
            return GoalPlan(GoalDecision.COMPLETE, goal.id, reason = "该目标的任务都已完成")
        }
        val dueFollow = state.followUps.filter { it.goalId == goal.id && it.active && it.nextCheckAt <= now }.minByOrNull { it.nextCheckAt }
        if (dueFollow != null && !observationValid) {
            return GoalPlan(GoalDecision.WAIT, goal.id, reason = "等待可用的新观察")
        }
        val task = tasks.filter { isRunnable(it, now) }
            .minByOrNull { if (it.dueAt == 0L) Long.MAX_VALUE else it.dueAt }
            ?: return GoalPlan(GoalDecision.ASK_USER, goal.id, reason = "目标还缺可执行的下一步，需要你确认")
        if (budgetRemaining <= 0) return GoalPlan(GoalDecision.WAIT, goal.id, task.id, "今日模型预算不足")
        return if (task.risk == "read") {
            GoalPlan(GoalDecision.READ_TOOL, goal.id, task.id, "下一步是只读工具：" + task.nextStep.take(80))
        } else {
            GoalPlan(GoalDecision.PROPOSE_WRITE, goal.id, task.id, "下一步需要用户确认：" + task.nextStep.take(80))
        }
    }

    /**
     * 能拿来做的任务：PENDING；或 WAITING 但等的那条确认已经过期/没了（再提议一次）。
     * WAITING 且确认还开着、BLOCKED、DONE 都不选，避免同一步反复提议或反复烧预算。
     */
    private fun isRunnable(task: Task, now: Long): Boolean = when (task.status) {
        TaskStatus.PENDING -> task.dueAt == 0L || task.dueAt <= now
        TaskStatus.WAITING -> {
            val pendingId = task.wakeCondition.removePrefix(PENDING_PREFIX).takeIf { task.wakeCondition.startsWith(PENDING_PREFIX) }
            pendingId != null && !pendingOpen(pendingId)
        }
        else -> false
    }

    companion object {
        const val PENDING_PREFIX = "pending:"
    }
}

/** 事件路由只返回“该考虑哪些目标”，不直接执行。 */
class GoalEventRouter(private val store: GoalStore) {
    fun route(kind: String, now: Long): List<String> {
        val state = store.state()
        val active = state.goals.filter { it.status == GoalStatus.ACTIVE }
        return active.filter { goal ->
            state.followUps.any { it.goalId == goal.id && it.active && it.nextCheckAt <= now } ||
                state.tasks.any { it.goalId == goal.id && it.wakeCondition.isNotBlank() && it.wakeCondition == kind }
        }.map { it.id }
    }
}
