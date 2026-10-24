package cn.companion.pet.core.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ActionRunnerTest {

    private class FakeAdapter(
        private val result: (ActionRequest) -> ActionResult,
        private val block: (() -> Unit)? = null,
    ) : ActionAdapter {
        val calls = AtomicInteger(0)
        override fun execute(request: ActionRequest): ActionResult {
            calls.incrementAndGet()
            block?.invoke()
            return result(request)
        }
    }

    private fun ok(kind: ActionKind) = { r: ActionRequest -> ActionResult(r.id, kind, ActionStatus.SUCCESS, "ok") }

    @Test
    fun successFailureUnavailableAndValidation() {
        val runner = ActionRunner(
            mapOf(
                ActionKind.SAVE_NOTE to FakeAdapter(ok(ActionKind.SAVE_NOTE)),
            ),
        )
        assertEquals(ActionStatus.SUCCESS, runner.execute(ActionRequest("a1", ActionKind.SAVE_NOTE, mapOf("text" to "念头"))).status)
        assertEquals(ActionStatus.UNAVAILABLE, runner.execute(ActionRequest("a2", ActionKind.SPEAK, mapOf("text" to "hi"))).status)
        assertEquals(ActionStatus.FAILED, runner.execute(ActionRequest("a3", ActionKind.SAVE_NOTE, mapOf("text" to "x", "bad" to "y"))).status)
        assertEquals(ActionStatus.FAILED, runner.execute(ActionRequest("a4", ActionKind.SAVE_NOTE)).status)
    }

    @Test
    fun sameExecutionIdRunsAdapterOnce() {
        val adapter = FakeAdapter(ok(ActionKind.SAVE_NOTE))
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter))
        val request = ActionRequest("same", ActionKind.SAVE_NOTE, mapOf("text" to "只存一次"))
        val first = runner.execute(request)
        val second = runner.execute(request)
        assertEquals(first, second)
        assertEquals(1, adapter.calls.get())
    }

    @Test
    fun concurrentSameIdRunsOnce() {
        val adapter = FakeAdapter(ok(ActionKind.SAVE_NOTE))
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter))
        val pool = Executors.newFixedThreadPool(4)
        val request = ActionRequest("concurrent", ActionKind.SAVE_NOTE, mapOf("text" to "只存一次"))
        repeat(4) { pool.submit { runner.execute(request) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, adapter.calls.get())
    }

    @Test
    fun cancellationWinsOverLateSuccess() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val adapter = FakeAdapter(ok(ActionKind.SAVE_NOTE)) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter))
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<ActionResult> { runner.execute(ActionRequest("cancel-me", ActionKind.SAVE_NOTE, mapOf("text" to "x"))) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(runner.cancel("cancel-me"))
        release.countDown()
        assertEquals(ActionStatus.CANCELLED, future.get(5, TimeUnit.SECONDS).status)
        assertEquals(ActionStatus.CANCELLED, runner.result("cancel-me")?.status)
        pool.shutdown()
    }

    @Test
    fun cancelAllStopsInFlightActions() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val adapter = FakeAdapter(ok(ActionKind.SPEAK)) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
        val runner = ActionRunner(mapOf(ActionKind.SPEAK to adapter))
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<ActionResult> { runner.execute(ActionRequest("lock", ActionKind.SPEAK, mapOf("text" to "hi"))) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        runner.cancelAll()
        release.countDown()
        assertEquals(ActionStatus.CANCELLED, future.get(5, TimeUnit.SECONDS).status)
        pool.shutdown()
    }

    @Test
    fun plannerIsDryRunAndRequiresSourceAndBudget() {
        val planner = ActionPlanner(cooldownMillis = 60_000)
        val quiet = planner.decide(ActionKind.SAVE_NOTE, listOf("e1"), 100_000, 0, true, true, 5)
        assertFalse(quiet.allowed)
        assertEquals("安静时段", quiet.reason)
        val noSource = planner.decide(ActionKind.SAVE_NOTE, emptyList(), 100_000, 0, false, true, 5)
        assertFalse(noSource.allowed)
        val noBudget = planner.decide(ActionKind.SAVE_NOTE, listOf("e1"), 100_000, 0, false, true, 0)
        assertFalse(noBudget.allowed)
        val ready = planner.decide(ActionKind.SAVE_NOTE, listOf("e1"), 100_000, 0, false, true, 5)
        assertTrue(ready.allowed)
    }

    @Test
    fun adapterUnavailableIsReturnedWithoutPretending() {
        val adapter = FakeAdapter({ r: ActionRequest -> ActionResult(r.id, r.kind, ActionStatus.UNAVAILABLE, "未安装") })
        val runner = ActionRunner(mapOf(ActionKind.OPEN_ASSISTANT to adapter))
        val result = runner.execute(ActionRequest("u1", ActionKind.OPEN_ASSISTANT, mapOf("target" to "GEMINI")))
        assertEquals(ActionStatus.UNAVAILABLE, result.status)
        assertEquals("未安装", result.reason)
        assertEquals(1, adapter.calls.get())
    }
}