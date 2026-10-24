package cn.companion.pet.core.goal

import cn.companion.pet.core.action.ActionAdapter
import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionPlanner
import cn.companion.pet.core.action.ActionRequest
import cn.companion.pet.core.action.ActionResult
import cn.companion.pet.core.action.ActionRunner
import cn.companion.pet.core.action.ActionStatus
import cn.companion.pet.core.action.PendingActionStore
import cn.companion.pet.core.action.PendingExecutor
import cn.companion.pet.core.action.PendingStatus
import cn.companion.pet.core.agent.AgentBudgetPort
import cn.companion.pet.core.agent.AgentContext
import cn.companion.pet.core.agent.AgentDecisionSource
import cn.companion.pet.core.agent.AgentLoop
import cn.companion.pet.core.agent.AgentTrace
import cn.companion.pet.core.agent.AgentTraceSink
import cn.companion.pet.core.agent.AgentTrigger
import cn.companion.pet.core.agent.AgentTurnStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/** 审计 P0-2 / P2-1：空目标不吞事件；任务能完成；写步骤经确认回写；拒绝不反复提议。 */
class GoalChainTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Source(var json: String) : AgentDecisionSource {
        val calls = AtomicInteger()
        override suspend fun decide(context: AgentContext): String { calls.incrementAndGet(); return json }
    }
    private val budget = object : AgentBudgetPort {
        override fun reserve(day: String) = true
        override fun remaining(day: String) = 100
    }
    private val traces = object : AgentTraceSink { override fun append(trace: AgentTrace) {} }
    private val ok = object : ActionAdapter {
        override fun execute(request: ActionRequest) = ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "ok")
    }

    private fun ctx(now: Long) = AgentContext(
        trigger = AgentTrigger.NOTE_SAVED, now = now, day = "2026-10-24", scene = "NORMAL",
        foreground = "测试", foregroundPackage = "com.test", memoryLines = emptyList(), materialTitles = emptyList(),
        eventIds = listOf("e1"), budgetRemaining = 100, eventId = "e1",
    )

    private data class Env(val store: GoalStore, val pending: PendingActionStore, val coordinator: GoalAgentCoordinator,
                           val source: Source, val runner: ActionRunner)

    private fun env(json: String): Env {
        val store = GoalStore(tmp.root)
        val pending = PendingActionStore(tmp.root)
        val source = Source(json)
        val runner = ActionRunner(mapOf(ActionKind.READ_MATERIALS to ok, ActionKind.SAVE_NOTE to ok))
        val loop = AgentLoop(source, runner, budget, traces, ActionPlanner(), pending = pending)
        val supervisor = GoalSupervisor(store) { id -> pending.find(id)?.status.let { it == PendingStatus.PENDING || it == PendingStatus.APPROVED } }
        return Env(store, pending, GoalAgentCoordinator(store, supervisor, loop), source, runner)
    }

    @Test
    fun goalWithoutTaskIsNotHandledSoCallerRunsNormalAgent() = runBlocking {
        val e = env("""{"decision":"no_action","reason":"x"}""")
        e.store.addGoal("每天复习英语", now = 1_000)
        val r = e.coordinator.runOnce(ctx(1_000), autonomy = true)
        assertEquals(GoalDecision.ASK_USER, r.plan.decision)
        assertFalse(r.handled)
        // 连续多次也只记一条 plan episode。
        repeat(5) { e.coordinator.runOnce(ctx(2_000L + it), autonomy = true) }
        assertEquals(1, e.store.state().episodes.count { it.kind == "plan" })
    }

    @Test
    fun readStepRunsOnceThenGoalWaitsForUserToConfirmCompletion() = runBlocking {
        val e = env("""{"decision":"tool_call","tool":"READ_MATERIALS","args":{"limit":"3"},"source_ids":[],"reason":"r"}""")
        val goal = e.store.addGoal("看素材", now = 1_000)!!
        e.store.addTask(goal.id, "读素材", "READ_MATERIALS", "read", now = 1_000)
        val first = e.coordinator.runOnce(ctx(1_000), autonomy = true)
        assertTrue(first.handled)
        assertEquals(TaskStatus.DONE, first.taskStatus)
        repeat(3) { e.coordinator.runOnce(ctx(2_000L + it), autonomy = true) }
        assertEquals("done step is not re-run", 1, e.source.calls.get())
        assertEquals(GoalDecision.COMPLETE, GoalSupervisor(e.store).decide(3_000, 5).decision)
        assertEquals(GoalStatus.ACTIVE, e.store.state().goals.single().status) // 目标完成仍由用户确认
    }

    @Test
    fun toolOutsideTheStepIsRefusedAndBlocks() = runBlocking {
        val e = env("""{"decision":"tool_call","tool":"READ_MEMORY","args":{},"source_ids":[],"reason":"r"}""")
        val goal = e.store.addGoal("看素材", now = 1_000)!!
        e.store.addTask(goal.id, "读素材", "READ_MATERIALS", "read", now = 1_000)
        val r = e.coordinator.runOnce(ctx(1_000), autonomy = true)
        assertEquals(AgentTurnStatus.DENIED, r.agent?.status)
        assertEquals(TaskStatus.BLOCKED, r.taskStatus)
    }

    @Test
    fun writeStepWaitsForConfirmationAndCompletesAfterApproval() = runBlocking {
        val e = env("""{"decision":"tool_call","tool":"SAVE_NOTE","args":{"text":"记"},"source_ids":["e1"],"reason":"r"}""")
        val goal = e.store.addGoal("记下来", now = 1_000)!!
        val task = e.store.addTask(goal.id, "记一条", "SAVE_NOTE", "write", now = 1_000)!!
        val r = e.coordinator.runOnce(ctx(1_000), autonomy = true)
        assertEquals(TaskStatus.WAITING, r.taskStatus)
        val pendingId = r.agent!!.pendingId!!
        // 确认还开着：下一个事件不再重复提议。
        e.coordinator.runOnce(ctx(1_100), autonomy = true)
        assertEquals(1, e.pending.list(1_200).count { it.status == PendingStatus.PENDING })

        val executor = PendingExecutor(e.pending, e.runner,
            onFinished = { a, res -> e.store.applyPendingResult(a.taskId, res, 1_300) })
        val item = e.pending.find(pendingId)!!
        assertNotNull(executor.approveAndRun(item.id, 1_300, "com.test", item.nonce) as? PendingExecutor.Outcome.Executed)
        assertEquals(TaskStatus.DONE, e.store.state().tasks.single { it.id == task.id }.status)
    }

    @Test
    fun rejectedStepIsNotProposedAgainUntilRetried() = runBlocking {
        val e = env("""{"decision":"tool_call","tool":"SAVE_NOTE","args":{"text":"记"},"source_ids":["e1"],"reason":"r"}""")
        val goal = e.store.addGoal("记下来", now = 1_000)!!
        val task = e.store.addTask(goal.id, "记一条", "SAVE_NOTE", "write", now = 1_000)!!
        val r = e.coordinator.runOnce(ctx(1_000), autonomy = true)
        val executor = PendingExecutor(e.pending, e.runner, onRejected = { e.store.markTaskRejected(it.taskId, 1_100) })
        assertTrue(executor.reject(r.agent!!.pendingId!!))
        val again = e.coordinator.runOnce(ctx(1_200), autonomy = true)
        assertFalse(again.handled)
        assertEquals(1, e.source.calls.get())
        e.store.setTaskStatus(task.id, TaskStatus.PENDING, "用户重试", 1_300, wakeCondition = "")
        assertTrue(e.coordinator.runOnce(ctx(1_400), autonomy = true).handled)
    }

    @Test
    fun expiredConfirmationIsProposedAgain() = runBlocking {
        val e = env("""{"decision":"tool_call","tool":"SAVE_NOTE","args":{"text":"记"},"source_ids":["e1"],"reason":"r"}""")
        val goal = e.store.addGoal("记下来", now = 1_000)!!
        e.store.addTask(goal.id, "记一条", "SAVE_NOTE", "write", now = 1_000)
        e.coordinator.runOnce(ctx(1_000), autonomy = true)
        e.pending.list(1_000 + 6 * 60_000) // TTL 5 分钟后过期
        val again = e.coordinator.runOnce(ctx(1_000 + 6 * 60_000), autonomy = true)
        assertTrue(again.handled)
        assertEquals(2, e.source.calls.get())
    }

    @Test
    fun unverifiedExecutionBlocksInsteadOfCompleting() {
        val store = GoalStore(tmp.root)
        val goal = store.addGoal("点一下", now = 1_000)!!
        val task = store.addTask(goal.id, "点按钮", "TAP_NODE", "write", now = 1_000)!!
        store.applyPendingResult(task.id, ActionResult("p", ActionKind.TAP_NODE, ActionStatus.UNAVAILABLE, "已点击；结果需重新观察",
            mapOf("unverified" to "true")), 2_000)
        assertEquals(TaskStatus.BLOCKED, store.state().tasks.single().status)
    }
}
