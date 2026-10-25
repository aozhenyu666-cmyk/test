package cn.companion.pet.core.goal

/**
 * 目标完成判定：任务全 DONE 只是候选，必须成功标准非空且用户确认，不能凭列表空自动 COMPLETE。
 */
data class CompletionDecision(val canComplete: Boolean, val reason: String)

object GoalCompletionPolicy {
    fun evaluate(goal: Goal, tasks: List<Task>, userConfirmed: Boolean): CompletionDecision {
        if (goal.status == GoalStatus.COMPLETED) return CompletionDecision(true, "目标已是完成态")
        val own = tasks.filter { it.goalId == goal.id }
        if (own.isEmpty()) return CompletionDecision(false, "还没有任务")
        if (own.any { it.status != TaskStatus.DONE }) return CompletionDecision(false, "仍有未完成任务")
        if (goal.successCriteria.isBlank()) return CompletionDecision(false, "缺少成功标准")
        if (!userConfirmed) return CompletionDecision(false, "任务完成，等待用户确认成功标准")
        return CompletionDecision(true, "任务完成且用户确认")
    }
}
