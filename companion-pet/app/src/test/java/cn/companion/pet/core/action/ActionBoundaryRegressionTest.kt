package cn.companion.pet.core.action

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ActionBoundaryRegressionTest {
    private fun success(request: ActionRequest) = ActionResult(request.id, request.kind, ActionStatus.SUCCESS)

    @Test fun reusedIdWithDifferentPayloadIsRejected() {
        val calls = AtomicInteger()
        val adapter = object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult { calls.incrementAndGet(); return success(request) }
        }
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter))
        assertEquals(ActionStatus.SUCCESS, runner.execute(ActionRequest("same", ActionKind.SAVE_NOTE, mapOf("text" to "合成一"))).status)
        assertEquals(ActionStatus.FAILED, runner.execute(ActionRequest("same", ActionKind.SAVE_NOTE, mapOf("text" to "合成二"))).status)
        assertEquals(1, calls.get())
    }

    @Test fun cancellationReleasesDuplicateWaiterBeforeAdapterReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult {
                entered.countDown(); release.await(5, TimeUnit.SECONDS); return success(request)
            }
        }))
        val request = ActionRequest("cancel", ActionKind.SAVE_NOTE, mapOf("text" to "合成"))
        try {
            pool.submit<ActionResult> { runner.execute(request) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val complete = CountDownLatch(1)
            val value = AtomicReference<ActionResult>()
            val duplicate = thread {
                value.set(runner.execute(request)); complete.countDown()
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (duplicate.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals("Duplicate must already be waiting on the shared future", Thread.State.WAITING, duplicate.state)
            runner.cancel(request.id)
            assertTrue(complete.await(300, TimeUnit.MILLISECONDS))
            assertEquals(ActionStatus.CANCELLED, value.get().status)
            duplicate.join(1000)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun memoryReadDoesNotReturnPreviouslyCachedPrivateContent() {
        val revision = AtomicInteger(1)
        val runner = ActionRunner(mapOf(ActionKind.READ_MEMORY to object : ActionAdapter {
            override fun execute(request: ActionRequest) = success(request).copy(data = mapOf("revision" to revision.get().toString()))
        }))
        val request = ActionRequest("read", ActionKind.READ_MEMORY)
        assertEquals("1", runner.execute(request).data["revision"])
        revision.set(2)
        assertEquals("2", runner.execute(request).data["revision"])
    }

    @Test fun parametersAreFrozenBeforeAdapterRuns() {
        val entered = CountDownLatch(1);val release = CountDownLatch(1)
        val args = mutableMapOf("text" to "合成原文")
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult {
                entered.countDown();release.await(2, TimeUnit.SECONDS)
                return success(request).copy(data = mapOf("text" to request.args.getValue("text")))
            }
        }))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<ActionResult> { runner.execute(ActionRequest("frozen", ActionKind.SAVE_NOTE, args)) }
            assertTrue(entered.await(1, TimeUnit.SECONDS));args["text"] = "合成变更";release.countDown()
            assertEquals("合成原文", future.get(2, TimeUnit.SECONDS).data["text"])
        } finally {release.countDown();pool.shutdownNow()}
    }

    @Test fun invalidLimitsAndTargetsDoNotSilentlyExecuteAnotherAction() {
        val runner = ActionRunner(ActionKind.entries.associateWith { object : ActionAdapter {
            override fun execute(request: ActionRequest) = success(request)
        } })
        assertEquals(ActionStatus.FAILED, runner.execute(ActionRequest("bad-limit", ActionKind.READ_MEMORY, mapOf("limit" to "abc"))).status)
        assertEquals(ActionStatus.FAILED, runner.execute(ActionRequest("bad-target", ActionKind.OPEN_ASSISTANT, mapOf("target" to "NOT_AN_ASSISTANT"))).status)
    }

    @Test fun noteLimitIsInCodePointsAndIsEnforced() {
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to object : ActionAdapter {
            override fun execute(request: ActionRequest) = success(request)
        }))
        assertEquals(ActionStatus.FAILED, runner.execute(ActionRequest("too-long", ActionKind.SAVE_NOTE, mapOf("text" to "文".repeat(501)))).status)
    }

    @Test fun cancellationReachesCooperativeAdapterBeforeItsEffect() {
        val entered = CountDownLatch(1);val release = CountDownLatch(1);val effects = AtomicInteger()
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult = error("Use cancellation-aware execution")
            override fun execute(request: ActionRequest, cancellation: ActionCancellation): ActionResult {
                entered.countDown();release.await(2, TimeUnit.SECONDS);cancellation.throwIfCancelled()
                effects.incrementAndGet();return success(request)
            }
        }))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<ActionResult> { runner.execute(ActionRequest("cooperative", ActionKind.SAVE_NOTE, mapOf("text" to "合成"))) }
            assertTrue(entered.await(1, TimeUnit.SECONDS));runner.cancelAll();release.countDown()
            assertEquals(ActionStatus.CANCELLED, future.get(1, TimeUnit.SECONDS).status);assertEquals(0, effects.get())
        } finally { release.countDown();pool.shutdownNow() }
    }

    @Test fun adapterCannotAcknowledgeADifferentAction() {
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to object : ActionAdapter {
            override fun execute(request: ActionRequest) = ActionResult("other", ActionKind.OPEN_ASSISTANT, ActionStatus.SUCCESS)
        }))
        assertEquals(ActionStatus.FAILED, runner.execute(ActionRequest("expected", ActionKind.SAVE_NOTE, mapOf("text" to "合成"))).status)
    }

    @Test fun identityHistoryIsBoundedWithoutCachingReadPayloads() {
        val calls = AtomicInteger()
        val runner = ActionRunner(mapOf(ActionKind.READ_MEMORY to object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult { calls.incrementAndGet();return success(request) }
        }), maxRememberedActions = 1)
        val first = ActionRequest("read-one", ActionKind.READ_MEMORY)
        runner.execute(first);runner.execute(first)
        assertNull(runner.result(first.id))
        assertEquals(2, calls.get())
        assertEquals(ActionStatus.FAILED, runner.execute(first.copy(id = "read-two")).status)
    }

    @Test fun cancellingSpeechDoesNotCancelUnrelatedNoteWrite() {
        val entered = CountDownLatch(2);val release = CountDownLatch(1)
        val adapter = object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult { entered.countDown();release.await(2, TimeUnit.SECONDS);return success(request) }
        }
        val runner = ActionRunner(mapOf(ActionKind.SPEAK to adapter, ActionKind.SAVE_NOTE to adapter));val pool = Executors.newFixedThreadPool(2)
        try {
            val speech = pool.submit<ActionResult> { runner.execute(ActionRequest("speech-kind", ActionKind.SPEAK, mapOf("text" to "合成"))) }
            val note = pool.submit<ActionResult> { runner.execute(ActionRequest("note-kind", ActionKind.SAVE_NOTE, mapOf("text" to "合成"))) }
            assertTrue(entered.await(1, TimeUnit.SECONDS));runner.cancelKind(ActionKind.SPEAK);release.countDown()
            assertEquals(ActionStatus.CANCELLED, speech.get(1, TimeUnit.SECONDS).status)
            assertEquals(ActionStatus.SUCCESS, note.get(1, TimeUnit.SECONDS).status)
        } finally {release.countDown();pool.shutdownNow()}
    }

    @Test fun plannerValidatesActualSourcesThroughItsPort() {
        val planner = ActionPlanner(sourceAvailable = { it == "visible-source" })
        assertFalse(planner.decide(ActionKind.SAVE_NOTE, listOf("missing"), 100_000, 0, false, true, 1).allowed)
        assertTrue(planner.decide(ActionKind.SAVE_NOTE, listOf("visible-source"), 100_000, 0, false, true, 1).allowed)
    }
}
