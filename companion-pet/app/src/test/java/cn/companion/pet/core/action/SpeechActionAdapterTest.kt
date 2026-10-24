package cn.companion.pet.core.action

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpeechActionAdapterTest {
    private class Port : SpeechPort {
        val started = CountDownLatch(1)
        val stops = AtomicInteger()
        var receipt: ((Boolean, String) -> Unit)? = null
        override fun submit(text: String, id: String, receipt: (Boolean, String) -> Unit) { this.receipt = receipt;started.countDown() }
        override fun stop(id: String) { stops.incrementAndGet() }
    }
    private val request = ActionRequest("speech", ActionKind.SPEAK, mapOf("text" to "合成播报"))

    @Test fun initializationFailureCannotBeReportedAsSuccess() {
        val port = Port();val adapter = SpeechActionAdapter(port, { true });val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<ActionResult> { adapter.execute(request) }
            assertTrue(port.started.await(1, TimeUnit.SECONDS));assertFalse(future.isDone)
            port.receipt!!(false, "合成初始化失败")
            assertEquals(ActionStatus.UNAVAILABLE, future.get(1, TimeUnit.SECONDS).status)
        } finally { pool.shutdownNow() }
    }

    @Test fun acceptedReceiptIsRequiredForSuccess() {
        val port = object : SpeechPort {
            override fun submit(text: String, id: String, receipt: (Boolean, String) -> Unit) = receipt(true, "系统已接受，不代表播放完成")
            override fun stop(id: String) {}
        }
        val result = SpeechActionAdapter(port, { true }).execute(request)
        assertEquals(ActionStatus.SUCCESS, result.status)
        assertTrue(result.reason.contains("不代表播放完成"))
    }

    @Test fun cancellationNotifiesThePortWhileItIsInitializing() {
        val port = Port();val pool = Executors.newSingleThreadExecutor()
        val runner = ActionRunner(mapOf(ActionKind.SPEAK to SpeechActionAdapter(port, { true })))
        try {
            val future = pool.submit<ActionResult> { runner.execute(request) }
            assertTrue(port.started.await(1, TimeUnit.SECONDS));assertTrue(runner.cancel(request.id))
            assertEquals(ActionStatus.CANCELLED, future.get(1, TimeUnit.SECONDS).status)
            assertEquals(1, port.stops.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun timeoutWithdrawsPendingSpeechAndLateReceiptCannotChangeResult() {
        val port = Port();val runner = ActionRunner(mapOf(ActionKind.SPEAK to SpeechActionAdapter(port, { true }, 30)))
        val result = runner.execute(request)
        assertEquals(ActionStatus.UNAVAILABLE, result.status);assertEquals(1, port.stops.get())
        port.receipt!!(true, "迟到的合成成功")
        assertEquals(result, runner.execute(request))
    }

    @Test fun disabledSpeechDoesNotInitializeAnEngine() {
        val port = Port();val result = SpeechActionAdapter(port, { false }).execute(request)
        assertEquals(ActionStatus.UNAVAILABLE, result.status);assertEquals(1L, port.started.count)
    }
}
