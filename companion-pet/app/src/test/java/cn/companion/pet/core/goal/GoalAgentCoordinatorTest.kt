package cn.companion.pet.core.goal

import cn.companion.pet.core.action.ActionAdapter
import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionPlanner
import cn.companion.pet.core.action.ActionRequest
import cn.companion.pet.core.action.ActionResult
import cn.companion.pet.core.action.ActionRunner
import cn.companion.pet.core.action.ActionStatus
import cn.companion.pet.core.agent.AgentBudgetPort
import cn.companion.pet.core.agent.AgentContext
import cn.companion.pet.core.agent.AgentDecisionSource
import cn.companion.pet.core.agent.AgentLoop
import cn.companion.pet.core.agent.AgentTrace
import cn.companion.pet.core.agent.AgentTraceSink
import cn.companion.pet.core.agent.AgentTrigger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class GoalAgentCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Source : AgentDecisionSource {
        override suspend fun decide(context: AgentContext): String =
            """{"decision":"tool_call","tool":"READ_MATERIALS","reason":"复习目标需要看素材"}"""
    }

    private class Budget : AgentBudgetPort {
        override fun reserve(day: String) = true
        override fun remaining(day: String) = 10
    }

    private class Adapter : ActionAdapter {
        val calls = AtomicInteger(0)
        override fun execute(request: ActionRequest): ActionResult {
            calls.incrementAndGet()
            return ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "读取到 1 条素材", mapOf("text" to "虚构素材"))
        }
    }

    private fun context(now: Long) = AgentContext(
        trigger = AgentTrigger.PROACTIVE, now = now, day = "2026-10-24", scene = "NORMAL",
        foreground = "com.test", memoryLines = emptyList(), materialTitles = listOf("虚构素材"),
        eventIds = listOf("e1"), budgetRemaining = 5,
    )

    private fun setup(now: Long): Triple<GoalStore, GoalAgentCoordinator, Adapter> {
        val store = GoalStore(tmp.root)
        val goal = store.addGoal("复习行测错题", now = now)!!
        store.addTask(goal.id, "读取已保存素材", "READ_MATERIALS", "read", now = now)
        val adapter = Adapter()
        val runner = ActionRunner(mapOf(ActionKind.READ_MATERIALS to adapter))
        val loop = AgentLoop(Source(), runner, Budget(), object : AgentTraceSink {
            override fun append(trace: AgentTrace) {}
        }, ActionPlanner(), 1_000, { "b1" })
        return Triple(store, GoalAgentCoordinator(store, GoalSupervisor(store), loop), adapter)
    }

    @Test
    fun goalEventExecutesReadAndWritesReceiptToStore() = runBlocking {
        val (store, coordinator, adapter) = setup(1_000)
        val result = coordinator.runOnce(context(1_000), autonomy = true)
        assertEquals(GoalDecision.READ_TOOL, result.plan.decision)
        assertEquals(1, adapter.calls.get())
        assertEquals(TaskStatus.WAITING, result.taskStatus)
        assertTrue(result.agent?.execution?.status == ActionStatus.SUCCESS)
        val reopened = GoalStore(tmp.root)
        assertEquals(TaskStatus.WAITING, reopened.state().tasks.single().status)
        assertTrue(reopened.state().episodes.any { it.actionId != null })
    }

    @Test
    fun pausedGoalDoesNotRunTool() = runBlocking {
        val (store, coordinator, adapter) = setup(1_000)
        val goal = store.state().goals.single()
        store.setGoalStatus(goal.id, GoalStatus.PAUSED, 1_100)
        val result = coordinator.runOnce(context(1_100), autonomy = true)
        assertEquals(GoalDecision.NO_ACTION, result.plan.decision)
        assertEquals(0, adapter.calls.get())
    }

    @Test
    fun budgetExhaustionDoesNotExecute() = runBlocking {
        val (store, coordinator, adapter) = setup(1_000)
        val ctx = context(1_000).copy(budgetRemaining = 0)
        val result = coordinator.runOnce(ctx, autonomy = true)
        assertEquals(GoalDecision.WAIT, result.plan.decision)
        assertEquals(0, adapter.calls.get())
    }

    @Test
    fun deletedGoalDoesNotResurrect() = runBlocking {
        val (store, coordinator, _) = setup(1_000)
        val goal = store.state().goals.single()
        store.deleteGoal(goal.id, 1_100)
        val result = coordinator.runOnce(context(1_100), autonomy = true)
        assertEquals(GoalDecision.NO_ACTION, result.plan.decision)
        assertTrue(GoalStore(tmp.root).state().goals.isEmpty())
    }
}
