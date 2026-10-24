package cn.companion.pet.core.action

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 审计 P0-1 / P2-2 / P2-3 / P3 的回归：确认链路能真的批准、执行一次、崩溃可核对、文件不丢不胀。 */
class PendingConfirmationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val budget = object : AgentBudgetPort {
        override fun reserve(day: String) = true
        override fun remaining(day: String) = 100
    }
    private val traces = object : AgentTraceSink { override fun append(trace: AgentTrace) {} }

    private class Counting : ActionAdapter {
        val calls = AtomicInteger()
        override fun execute(request: ActionRequest): ActionResult {
            calls.incrementAndGet()
            return ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "已保存")
        }
    }

    private fun agentContext() = AgentContext(
        trigger = AgentTrigger.NOTE_SAVED, now = 1_000, day = "2026-10-24", scene = "NORMAL",
        foreground = "哔哩哔哩", foregroundPackage = "tv.danmaku.bili",
        memoryLines = emptyList(), materialTitles = emptyList(), eventIds = listOf("e1"),
        budgetRemaining = 100, eventId = "e1", taskId = "t1",
    )

    private fun saveNoteSource() = object : AgentDecisionSource {
        override suspend fun decide(context: AgentContext): String =
            """{"decision":"tool_call","tool":"SAVE_NOTE","args":{"text":"记一下"},"source_ids":["e1"],"reason":"r"}"""
    }

    @Test
    fun agentProposalIsBoundToPackageAndCanBeApprovedOnce() = runBlocking {
        val pending = PendingActionStore(tmp.root)
        val adapter = Counting()
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter))
        val turn = AgentLoop(saveNoteSource(), runner, budget, traces, ActionPlanner(), pending = pending)
            .run(agentContext(), autonomy = true)
        assertEquals(AgentTurnStatus.NEEDS_CONFIRMATION, turn.status)
        val item = pending.find(turn.pendingId!!)!!
        assertEquals("tv.danmaku.bili", item.packageName)
        assertEquals("t1", item.taskId)

        val executor = PendingExecutor(pending, runner)
        assertTrue(executor.approveAndRun(item.id, 1_100, "com.other.app", item.nonce) is PendingExecutor.Outcome.Rejected)
        val done = executor.approveAndRun(item.id, 1_200, "tv.danmaku.bili", item.nonce)
        assertTrue(done is PendingExecutor.Outcome.Executed)
        assertEquals(ActionStatus.SUCCESS, (done as PendingExecutor.Outcome.Executed).result.status)
        assertTrue(executor.approveAndRun(item.id, 1_300, "tv.danmaku.bili", item.nonce) is PendingExecutor.Outcome.Rejected)
        assertEquals(1, adapter.calls.get())
        assertEquals(PendingStatus.EXECUTED, pending.find(item.id)!!.status)
    }

    @Test
    fun missingSourceIdFallsBackToTheTriggeringEvent() = runBlocking {
        // 真机 G2：“帮我打开 Gemini”没有反应——模型漏写 source_ids，以前会被静默拒绝。
        val pending = PendingActionStore(tmp.root)
        val source = object : AgentDecisionSource {
            override suspend fun decide(context: AgentContext): String =
                """{"decision":"tool_call","tool":"OPEN_ASSISTANT","args":{"target":"GEMINI"},"reason":"他想打开 Gemini"}"""
        }
        val turn = AgentLoop(source, ActionRunner(emptyMap()), budget, traces, ActionPlanner(), pending = pending)
            .run(agentContext(), autonomy = true)
        assertEquals(AgentTurnStatus.NEEDS_CONFIRMATION, turn.status)
        assertEquals(listOf("e1"), pending.find(turn.pendingId!!)!!.sourceIds)
    }

    @Test
    fun wrongNonceIsRejected() {
        val store = PendingActionStore(tmp.root)
        val item = store.create(ActionKind.SAVE_NOTE, mapOf("text" to "x"), "p", "com.test", now = 1_000)!!
        assertNull(store.approve(item.id, 1_100, "com.test", nonce = "forged"))
        assertNotNull(store.approve(item.id, 1_100, "com.test", nonce = item.nonce))
    }

    @Test
    fun approvedThenCrashBecomesExecutedOrInterruptedNeverReplayed() {
        val store = PendingActionStore(tmp.root)
        val a = store.create(ActionKind.SAVE_NOTE, mapOf("text" to "a"), "p", "com.test", now = 1_000)!!
        val b = store.create(ActionKind.SAVE_NOTE, mapOf("text" to "b"), "p", "com.test", now = 1_000)!!
        store.approve(a.id, 1_100, "com.test", a.nonce)
        store.approve(b.id, 1_100, "com.test", b.nonce)
        // 进程死掉；a 的动作已经进了账本，b 没有。
        val reopened = PendingActionStore(tmp.root)
        val known = ActionResult(a.executionId, ActionKind.SAVE_NOTE, ActionStatus.SUCCESS, "已保存")
        val recovered = reopened.recoverInterrupted(5_000) { id -> if (id == a.executionId) known else null }
        assertEquals(2, recovered.size)
        assertEquals(PendingStatus.EXECUTED, reopened.find(a.id)!!.status)
        assertEquals(PendingStatus.INTERRUPTED, reopened.find(b.id)!!.status)
        assertNull("INTERRUPTED must never be approvable again", reopened.approve(b.id, 5_100, "com.test", b.nonce))
    }

    @Test
    fun corruptFileIsBackedUpNotOverwritten() {
        val dir = File(tmp.root, "actions").apply { mkdirs() }
        File(dir, "pending.json").writeText("[{\"id\":\"old\",broken")
        val store = PendingActionStore(tmp.root)
        assertNotNull(store.create(ActionKind.SAVE_NOTE, mapOf("text" to "x"), "p", "com.test", now = 1_000))
        val backups = dir.listFiles { f -> f.name.startsWith("pending.corrupt-") }.orEmpty()
        assertEquals(1, backups.size)
        assertTrue(backups.single().readText().contains("\"old\""))
    }

    @Test
    fun finishedItemsArePrunedButOpenOnesKept() {
        val store = PendingActionStore(tmp.root, maxFinished = 5)
        val open = store.create(ActionKind.SAVE_NOTE, mapOf("text" to "open"), "p", "", now = 1)!!
        repeat(30) { i ->
            val item = store.create(ActionKind.SAVE_NOTE, mapOf("text" to "x$i"), "p", "", now = 10L + i)!!
            store.approve(item.id, 10L + i)
            store.finish(item.id, ActionResult(item.executionId, ActionKind.SAVE_NOTE, ActionStatus.SUCCESS))
        }
        val all = store.list(100)
        assertEquals(6, all.size)
        assertTrue(all.any { it.id == open.id && it.status == PendingStatus.PENDING })
    }

    @Test
    fun rejectNotifiesHookOnlyOnce() {
        val store = PendingActionStore(tmp.root)
        val rejected = mutableListOf<String>()
        val executor = PendingExecutor(store, ActionRunner(emptyMap()), onRejected = { rejected += it.id })
        val item = store.create(ActionKind.SAVE_NOTE, mapOf("text" to "x"), "p", "", now = 1_000)!!
        assertTrue(executor.reject(item.id))
        assertFalse(executor.reject(item.id))
        assertEquals(listOf(item.id), rejected)
    }
}
