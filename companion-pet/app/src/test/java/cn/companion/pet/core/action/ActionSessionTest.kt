package cn.companion.pet.core.action

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class ActionSessionTest {
    @Test fun doubleSubmitFreezesArgumentsAndRunsOnlyOnce() {
        val queue = mutableListOf<Runnable>()
        var calls = 0
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult {
                calls++
                assertEquals("原稿", request.args["text"])
                return ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "已保存")
            }
        }))
        val session = ActionSession(runner, Executor { queue += it })
        val args = mutableMapOf("text" to "原稿")
        assertTrue(session.submit(ActionKind.SAVE_NOTE, args))
        args["text"] = "改稿"
        assertFalse(session.submit(ActionKind.SAVE_NOTE, args))
        assertEquals(ActionPhase.PENDING, session.state.value.phase)
        queue.single().run()
        assertEquals(1, calls)
        assertEquals(ActionStatus.SUCCESS, session.state.value.result?.status)
    }

    @Test fun cancellingQueuedWorkNeverCallsAdapterAndNextSubmissionUsesNewId() {
        val queue = mutableListOf<Runnable>()
        var calls = 0
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult {
                calls++
                return ActionResult(request.id, request.kind, ActionStatus.SUCCESS)
            }
        }))
        val session = ActionSession(runner, Executor { queue += it })
        session.submit(ActionKind.SAVE_NOTE, mapOf("text" to "一"))
        val old = session.state.value.request!!.id
        session.cancel()
        queue.removeAt(0).run()
        assertEquals(0, calls)
        assertEquals(ActionStatus.CANCELLED, session.state.value.result!!.status)
        session.submit(ActionKind.SAVE_NOTE, mapOf("text" to "二"))
        assertNotEquals(old, session.state.value.request!!.id)
        queue.removeAt(0).run()
        assertEquals(1, calls)
        session.check(ActionRequest(old, ActionKind.SAVE_NOTE))
        assertEquals(1, calls)
    }

    @Test fun closeCancelsOnlyOwnedActionAndSuppressesLateCompletion() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val stopped = mutableListOf<String>()
        val runner = ActionRunner(mapOf(ActionKind.SPEAK to object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult {
                entered.countDown()
                check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                return ActionResult(request.id, request.kind, ActionStatus.SUCCESS)
            }
        }))
        try {
            val session = ActionSession(runner, pool, stopped::add)
            session.submit(ActionKind.SPEAK, mapOf("text" to "你好"))
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val id = session.state.value.request!!.id
            session.close()
            assertEquals(listOf(id), stopped)
            assertFalse(session.submit(ActionKind.SPEAK, mapOf("text" to "再见")))
            assertNull(runner.result("unrelated"))
            release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(ActionStatus.CANCELLED, session.state.value.result!!.status)
            assertEquals(ActionStatus.CANCELLED, runner.result(id)!!.status)
            // A different owner's action is still available after closing the panel.
            assertEquals(ActionStatus.UNAVAILABLE, runner.execute(ActionRequest("other", ActionKind.READ_MEMORY)).status)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun restoredCheckIsReadOnlyAndUnavailableIsVisible() {
        var calls = 0
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult { calls++; error("must not execute") }
        }))
        val session = ActionSession(runner, Executor { it.run() })
        session.check(ActionRequest("restored", ActionKind.SAVE_NOTE))
        assertEquals(0, calls)
        assertTrue(session.state.value.result!!.reason.contains("不会自动重做"))
        session.submit(ActionKind.SPEAK, mapOf("text" to "你好"))
        assertEquals(ActionStatus.UNAVAILABLE, session.state.value.result!!.status)
    }
}
