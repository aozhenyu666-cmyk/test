package cn.companion.pet.core.agent

import cn.companion.pet.core.action.ActionAdapter
import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionPlanner
import cn.companion.pet.core.action.ActionRequest
import cn.companion.pet.core.action.ActionResult
import cn.companion.pet.core.action.ActionRunner
import cn.companion.pet.core.action.ActionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class AgentLoopTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource(var content: String?) : AgentDecisionSource {
        val calls = AtomicInteger(0)
        override suspend fun decide(context: AgentContext): String? {
            calls.incrementAndGet()
            return content
        }
    }

    private class ReportingSource(var content: String?, private val reportText: String) : AgentDecisionSource {
        override val canReport: Boolean = true
        val calls = AtomicInteger(0)
        var lastReceipt: ActionResult? = null
        override suspend fun decide(context: AgentContext): String? {
            calls.incrementAndGet()
            return content
        }
        override suspend fun report(context: AgentContext, decision: AgentDecision.ToolCall, result: ActionResult): String? {
            lastReceipt = result
            return reportText
        }
    }

    private class FakeBudget(var left: Int = 10) : AgentBudgetPort {
        var reserves = 0
        override fun reserve(day: String): Boolean {
            if (left <= 0) return false
            left--
            reserves++
            return true
        }
        override fun remaining(day: String): Int = left
    }

    private class CountingAdapter(private val status: ActionStatus = ActionStatus.SUCCESS) : ActionAdapter {
        val calls = AtomicInteger(0)
        override fun execute(request: ActionRequest): ActionResult {
            calls.incrementAndGet()
            return ActionResult(request.id, request.kind, status, if (status == ActionStatus.SUCCESS) "2 条素材" else "没有权限",
                if (status == ActionStatus.SUCCESS) mapOf("text" to "标题：合成素材") else emptyMap())
        }
    }

    private fun context(budget: Int = 5) = AgentContext(
        trigger = AgentTrigger.MATERIAL_SAVED,
        now = 1_000_000L,
        day = "2026-10-24",
        scene = "NORMAL",
        foreground = "浏览器",
        memoryLines = listOf("他说过 喜欢猫"),
        materialTitles = listOf("合成素材"),
        eventIds = listOf("e1", "e2"),
        budgetRemaining = budget,
    )

    private fun loop(
        source: FakeSource,
        adapter: CountingAdapter,
        budget: FakeBudget = FakeBudget(),
        timeout: Long = 1_000,
        id: String = "fixed",
    ): Pair<AgentLoop, MutableList<AgentTrace>> {
        val traces = mutableListOf<AgentTrace>()
        val runner = ActionRunner(mapOf(ActionKind.READ_MATERIALS to adapter, ActionKind.SAVE_NOTE to adapter))
        val planner = ActionPlanner(sourceAvailable = { it == "e1" })
        val traceSink = object : AgentTraceSink {
            override fun append(trace: AgentTrace) { traces += trace }
        }
        return AgentLoop(source, runner, budget, traceSink, planner, timeout, { id }) to traces
    }

    @Test
    fun toolCallExecutesAndReturnsReceipt() = runBlocking {
        val source = FakeSource("""{"decision":"tool_call","tool":"READ_MATERIALS","args":{"limit":"5"},"reason":"有新素材"}""")
        val adapter = CountingAdapter()
        val (agent, traces) = loop(source, adapter)
        val result = agent.run(context(), true)
        assertEquals(AgentTurnStatus.EXECUTED, result.status)
        assertEquals(ActionStatus.SUCCESS, result.execution?.status)
        assertEquals(1, adapter.calls.get())
        assertTrue(result.text.contains("标题：合成素材"))
        assertEquals("EXECUTED", traces.single().status)
        assertEquals("READ_MATERIALS", traces.single().tool)
        assertEquals(1, traces.single().modelAttempts)
    }

    @Test
    fun noActionDoesNotRunTool() = runBlocking {
        val source = FakeSource("""{"decision":"no_action","reason":"没有足够理由"}""")
        val adapter = CountingAdapter()
        val (agent, _) = loop(source, adapter)
        val result = agent.run(context(), true)
        assertEquals(AgentTurnStatus.NO_ACTION, result.status)
        assertEquals(0, adapter.calls.get())
    }

    @Test
    fun malformedDecisionIsInvalid() = runBlocking {
        val source = FakeSource("这不是 JSON")
        val adapter = CountingAdapter()
        val (agent, _) = loop(source, adapter)
        val result = agent.run(context(), true)
        assertEquals(AgentTurnStatus.INVALID, result.status)
        assertEquals(0, adapter.calls.get())
    }

    @Test
    fun budgetExhaustedDoesNotCallModelOrTool() = runBlocking {
        val source = FakeSource("""{"decision":"no_action","reason":"x"}""")
        val adapter = CountingAdapter()
        val (agent, _) = loop(source, adapter, FakeBudget(0))
        val result = agent.run(context(budget = 0), true)
        assertEquals(AgentTurnStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, source.calls.get())
        assertEquals(0, adapter.calls.get())
    }

    @Test
    fun writeActionNeedsConfirmationAndDoesNotExecute() = runBlocking {
        val source = FakeSource("""{"decision":"tool_call","tool":"SAVE_NOTE","args":{"text":"合成念头"},"source_ids":["e1"],"reason":"用户刚才说过"}""")
        val adapter = CountingAdapter()
        val (agent, _) = loop(source, adapter)
        val result = agent.run(context(), true)
        assertEquals(AgentTurnStatus.NEEDS_CONFIRMATION, result.status)
        assertTrue(result.text.contains("需要你确认"))
        assertEquals(0, adapter.calls.get())
    }

    @Test
    fun invalidSourceIsDenied() = runBlocking {
        val source = FakeSource("""{"decision":"tool_call","tool":"SAVE_NOTE","args":{"text":"x"},"source_ids":["missing"],"reason":"x"}""")
        val adapter = CountingAdapter()
        val (agent, _) = loop(source, adapter)
        val result = agent.run(context(), true)
        assertEquals(AgentTurnStatus.DENIED, result.status)
        assertEquals(0, adapter.calls.get())
    }

    @Test
    fun modelTimeoutIsReported() = runBlocking {
        val source = object : AgentDecisionSource {
            override suspend fun decide(context: AgentContext): String? {
                delay(2_000)
                return """{"decision":"no_action","reason":"slow"}"""
            }
        }
        val adapter = CountingAdapter()
        val traces = mutableListOf<AgentTrace>()
        val runner = ActionRunner(mapOf(ActionKind.READ_MATERIALS to adapter))
        val agent = AgentLoop(source, runner, FakeBudget(), object : AgentTraceSink {
            override fun append(trace: AgentTrace) { traces += trace }
        }, ActionPlanner(), timeoutMillis = 50)
        val result = agent.run(context(), true)
        assertEquals(AgentTurnStatus.TIMEOUT, result.status)
        assertEquals(0, adapter.calls.get())
    }

    @Test
    fun cancellationBeforeModelDoesNotExecute() = runBlocking {
        val source = FakeSource("""{"decision":"tool_call","tool":"READ_MATERIALS","reason":"x"}""")
        val adapter = CountingAdapter()
        val (agent, _) = loop(source, adapter)
        val result = agent.run(context(), true, cancelled = { true })
        assertEquals(AgentTurnStatus.CANCELLED, result.status)
        assertEquals(0, source.calls.get())
        assertEquals(0, adapter.calls.get())
    }

    @Test
    fun repeatedReadTurnUsesFreshSnapshot() = runBlocking {
        val source = FakeSource("""{"decision":"tool_call","tool":"READ_MATERIALS","reason":"x"}""")
        val adapter = CountingAdapter()
        val (agent, _) = loop(source, adapter, id = "same")
        val first = agent.run(context(), true)
        val second = agent.run(context(), true)
        assertEquals(AgentTurnStatus.EXECUTED, first.status)
        assertEquals(AgentTurnStatus.EXECUTED, second.status)
        // 只读工具按 H3 修复刻意不缓存；写动作的幂等由 ActionRunner 的持久化账本保证。
        assertEquals(2, adapter.calls.get())
    }

    @Test
    fun traceStoreSurvivesReloadAndKeepsNoSensitiveText() {
        val store = AgentTraceStore(tmp.root)
        store.append(AgentTrace("t1", 1_000L, "MATERIAL_SAVED", true, "EXECUTED", "READ_MATERIALS", "reason", "a1", "SUCCESS", "2 条素材", 1))
        val reopened = AgentTraceStore(tmp.root)
        val trace = reopened.recent(5).single()
        assertEquals("t1", trace.id)
        assertFalse(reopened.recent(5).joinToString("\n") { it.toString() }.contains("合成念头"))
    }

    @Test
    fun disabledDoesNotCallModel() = runBlocking {
        val source = FakeSource("""{"decision":"no_action","reason":"x"}""")
        val adapter = CountingAdapter()
        val (agent, _) = loop(source, adapter)
        val result = agent.run(context(), false)
        assertEquals(AgentTurnStatus.DISABLED, result.status)
        assertEquals(0, source.calls.get())
        assertNotNull(result)
    }

    @Test
    fun malformedFieldTypesAreInvalidWithoutThrowing() = runBlocking {
        val source = FakeSource("""{"decision":"tool_call","tool":{},"args":{}}""")
        val adapter = CountingAdapter()
        val (agent, _) = loop(source, adapter)
        val result = agent.run(context(), true)
        assertEquals(AgentTurnStatus.INVALID, result.status)
        assertEquals(0, adapter.calls.get())
    }

    @Test
    fun receiptGoesBackToSecondModelCall() = runBlocking {
        val source = ReportingSource("""{"decision":"tool_call","tool":"READ_MATERIALS","reason":"x"}""", "看到了这条素材")
        val adapter = CountingAdapter()
        val budget = FakeBudget(10)
        val traces = mutableListOf<AgentTrace>()
        val runner = ActionRunner(mapOf(ActionKind.READ_MATERIALS to adapter))
        val agent = AgentLoop(source, runner, budget, object : AgentTraceSink {
            override fun append(trace: AgentTrace) { traces += trace }
        }, ActionPlanner(sourceAvailable = { it == "e1" }), 1_000, { "report" })
        val result = agent.run(context(), true)
        assertEquals(2, result.modelAttempts)
        assertEquals(2, budget.reserves)
        assertEquals("标题：合成素材", source.lastReceipt?.data?.get("text"))
        assertTrue(result.text.startsWith("（回执：成功）"))
        assertTrue(result.text.contains("看到了这条素材"))
        assertEquals(2, traces.single().modelAttempts)
    }

    @Test
    fun budgetOnlyOneFallsBackToLocalTemplate() = runBlocking {
        val source = ReportingSource("""{"decision":"tool_call","tool":"READ_MATERIALS","reason":"x"}""", "不该出现")
        val adapter = CountingAdapter()
        val budget = FakeBudget(1)
        val runner = ActionRunner(mapOf(ActionKind.READ_MATERIALS to adapter))
        val agent = AgentLoop(source, runner, budget, object : AgentTraceSink {
            override fun append(trace: AgentTrace) {}
        }, ActionPlanner(), 1_000, { "one" })
        val result = agent.run(context(budget = 1), true)
        assertEquals(1, result.modelAttempts)
        assertFalse(result.text.contains("不该出现"))
        assertTrue(result.text.contains("标题：合成素材"))
    }

    @Test
    fun quietOrForeignSourceIsDenied() = runBlocking {
        val source = FakeSource("""{"decision":"tool_call","tool":"SAVE_NOTE","args":{"text":"x"},"source_ids":["e1"],"reason":"x"}""")
        val adapter = CountingAdapter()
        val (agent, _) = loop(source, adapter)
        val quiet = agent.run(context().copy(quiet = true), true)
        assertEquals(AgentTurnStatus.DENIED, quiet.status)
        val foreign = agent.run(context().copy(eventIds = listOf("e2")), true)
        assertEquals(AgentTurnStatus.DENIED, foreign.status)
        assertEquals(0, adapter.calls.get())
    }
}