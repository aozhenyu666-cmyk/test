package cn.companion.pet.core.goal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalCompletionPolicyTest {
    private fun goal(criteria: String = "连续三天") = Goal("g", "复习", criteria, createdAt = 1_000)
    private fun task(status: TaskStatus) = Task("t", "g", "下一步", status = status, updatedAt = 1_000)

    @Test
    fun allDoneWithoutCriteriaOrConfirmationIsNotComplete() {
        assertFalse(GoalCompletionPolicy.evaluate(goal(""), listOf(task(TaskStatus.DONE)), true).canComplete)
        assertFalse(GoalCompletionPolicy.evaluate(goal(), listOf(task(TaskStatus.DONE)), false).canComplete)
        assertFalse(GoalCompletionPolicy.evaluate(goal(), listOf(task(TaskStatus.WAITING)), true).canComplete)
        assertTrue(GoalCompletionPolicy.evaluate(goal(), listOf(task(TaskStatus.DONE)), true).canComplete)
    }
}
