package com.yishou.app.healthwire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * T02 / T04 的一手侧证据：挑战必须真的投递到 a11y 主线程、在那里处理完再回。
 * 线程冻结时只能如实报超时，绝不代答。
 */
class A11yChallengeTest {

    private fun req(nonce: String = "n1", seq: Long = 1, budget: Long = 1_000) =
        ChallengeRequest(v = 2, nonce = nonce, seq = seq, budgetMs = budget)

    @Test
    fun `无障碍没连上时立刻返回 not connected`() {
        val handler = A11yChallengeHandler()
        val out = handler.handle(
            req = req(),
            pkg = "com.yishou.app",
            instanceId = null,
            post = { error("不应该被调用") },
            mono = { 0L },
            facts = { AckFacts() },
        )
        assertTrue(out is ChallengeOutcome.Fail)
        assertEquals("not connected", (out as ChallengeOutcome.Fail).error)
        assertFalse(out.ok)
    }

    @Test
    fun `挑战在 a11y 线程上处理完才回 ack`() {
        val handler = A11yChallengeHandler()
        val mainThread = Thread.currentThread()
        val ranOn = AtomicLong(0)
        val out = handler.handle(
            req = req(seq = 7),
            pkg = "com.yishou.app",
            instanceId = "inst-1",
            post = { block ->
                // 模拟 Handler.post 到另一条线程；用独立线程代表 a11y 主线程。
                Thread {
                    ranOn.set(Thread.currentThread().id)
                    block()
                }.start()
                true
            },
            mono = { 12_345L },
            facts = { AckFacts(ruleRevision = 3, eventSeq = 9, eventAtMono = 900) },
        )
        assertTrue(out is ChallengeOutcome.Ack)
        val ack = out as ChallengeOutcome.Ack
        assertEquals("com.yishou.app", ack.pkg)
        assertEquals("inst-1", ack.instanceId)
        assertEquals("n1", ack.nonce)
        assertEquals(7L, ack.seq)
        assertEquals(12_345L, ack.handledAtMono)
        assertEquals(3, ack.facts.ruleRevision)
        assertEquals(9L, ack.facts.eventSeq)
        // 处理确实发生在别的线程（不是 Binder/调用线程自己造的 ack）
        assertTrue(ranOn.get() != mainThread.id)
    }

    @Test
    fun `a11y 线程被冻结时超时且不构造 ack`() {
        val handler = A11yChallengeHandler()
        val started = System.currentTimeMillis()
        val out = handler.handle(
            req = req(budget = 250),
            pkg = "com.yishou.app",
            instanceId = "inst-1",
            // 投递成功，但那条线程永远不执行（模拟无障碍主线程卡死）
            post = { true },
            mono = { System.currentTimeMillis() },
            facts = { error("超时路径不该读 facts") },
        )
        val cost = System.currentTimeMillis() - started
        assertTrue(out is ChallengeOutcome.Timeout)
        assertEquals("dispatcher timeout", out.errorText())
        assertFalse(out.ok)
        // 必须真的等了预算（不是立刻返回、也不是无限等）
        assertTrue("等待了 ${cost}ms，应该 >= 预算", cost >= 200)
        assertTrue("等待了 ${cost}ms，不该远超预算", cost < 3_000)
    }

    @Test
    fun `post 失败时如实回 not connected`() {
        val handler = A11yChallengeHandler()
        val out = handler.handle(
            req = req(),
            pkg = "com.yishou.app",
            instanceId = "inst-1",
            post = { false },
            mono = { 1L },
            facts = { AckFacts() },
        )
        assertTrue(out is ChallengeOutcome.Fail)
        assertEquals("not connected", (out as ChallengeOutcome.Fail).error)
    }

    // ---------- C-1：facts 必须在执行线程上读（与总控 / 同桌同构） ----------

    @Test
    fun `facts 在执行线程上读 不是 Binder 线程`() {
        val handler = A11yChallengeHandler()
        val binderThreadId = Thread.currentThread().id
        val factsThreadId = AtomicLong(0)
        val factsCallCount = AtomicInteger(0)

        val out = handler.handle(
            req = req(),
            pkg = "com.yishou.app",
            instanceId = "inst-1",
            post = { block -> Thread { block() }.start(); true },
            mono = { 777L },
            facts = {
                factsCallCount.incrementAndGet()
                factsThreadId.set(Thread.currentThread().id)
                AckFacts(ruleRevision = 4, eventSeq = 11)
            },
        )

        assertTrue(out is ChallengeOutcome.Ack)
        assertEquals("facts 只应被读一次", 1, factsCallCount.get())
        assertTrue("facts 必须在执行线程上读", factsThreadId.get() != 0L)
        assertNotEquals(
            "facts 绝不能在 Binder（调用）线程上读——那就是后台代答",
            binderThreadId,
            factsThreadId.get(),
        )
        val ack = out as ChallengeOutcome.Ack
        assertEquals(4, ack.facts.ruleRevision)
        assertEquals(11L, ack.facts.eventSeq)
    }

    @Test
    fun `线程冻结时既不读 facts 也不构造 ack`() {
        val handler = A11yChallengeHandler()
        val factsCalls = AtomicInteger(0)
        val onHandledCalls = AtomicInteger(0)

        val out = handler.handle(
            req = req(budget = 250),
            pkg = "com.yishou.app",
            instanceId = "inst-1",
            // 投递成功但执行线程永不运行（模拟 a11y 主线程卡死）
            post = { true },
            mono = { System.currentTimeMillis() },
            facts = {
                factsCalls.incrementAndGet()
                AckFacts()
            },
            onHandled = { onHandledCalls.incrementAndGet() },
        )

        assertTrue(out is ChallengeOutcome.Timeout)
        assertEquals("冻结时一次都不许在 Binder 线程补读 facts", 0, factsCalls.get())
        assertEquals("冻结时执行线程根本没跑，不该有 onHandled", 0, onHandledCalls.get())
        assertFalse(out.ok)
        assertEquals("dispatcher timeout", out.errorText())
    }

    @Test
    fun `facts 在 handledAt 之前于同一执行线程读到 顺序固定`() {
        val handler = A11yChallengeHandler()
        val order = mutableListOf<String>()
        val factsThreadId = AtomicLong(0)
        val handledThreadId = AtomicLong(0)

        handler.handle(
            req = req(),
            pkg = "com.yishou.app",
            instanceId = "inst",
            post = { block -> Thread { block() }.start(); true },
            mono = {
                handledThreadId.set(Thread.currentThread().id)
                order += "mono"
                500L
            },
            facts = {
                factsThreadId.set(Thread.currentThread().id)
                order += "facts"
                AckFacts()
            },
            onHandled = { order += "onHandled" },
        )

        // 与总控 / 同桌同构：先取 facts 再取时刻，且都在同一条执行线程上。
        assertEquals("facts 应先于 mono 读取", listOf("facts", "mono", "onHandled"), order)
        assertEquals(factsThreadId.get(), handledThreadId.get())
    }

    @Test
    fun `在途数量超过上限时直接拒绝 不堆线程`() {
        val handler = A11yChallengeHandler(maxInFlight = 1)
        val hold = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = AtomicBoolean(false)
        val t = Thread {
            handler.handle(
                req = req(nonce = "slow", budget = 3_000),
                pkg = "com.yishou.app",
                instanceId = "inst",
                post = { block ->
                    blocked.set(true)
                    hold.countDown()
                    // 一直不跑，占住这个在途名额，等测试放行
                    release.await(2, TimeUnit.SECONDS)
                    block()
                    true
                },
                mono = { 5L },
                facts = { AckFacts() },
            )
        }
        t.isDaemon = true
        t.start()
        assertTrue(hold.await(2, TimeUnit.SECONDS))
        assertTrue(blocked.get())

        val second = handler.handle(
            req = req(nonce = "second", budget = 500),
            pkg = "com.yishou.app",
            instanceId = "inst",
            post = { true },
            mono = { 6L },
            facts = { AckFacts() },
        )
        assertTrue(second is ChallengeOutcome.Fail)
        assertTrue((second as ChallengeOutcome.Fail).error.contains("too many"))

        release.countDown()
        t.join(3_000)
    }

    @Test
    fun `nonce 为空或协议版本过旧都按失败处理`() {
        val handler = A11yChallengeHandler()
        val noNonce = handler.handle(
            req = ChallengeRequest(v = 2, nonce = "", seq = 1, budgetMs = 100),
            pkg = "com.yishou.app",
            instanceId = "inst",
            post = { true },
            mono = { 1L },
            facts = { AckFacts() },
        )
        assertTrue(noNonce is ChallengeOutcome.Fail)
        assertEquals("nonce 为空", (noNonce as ChallengeOutcome.Fail).error)

        val old = handler.handle(
            req = ChallengeRequest(v = 1, nonce = "x", seq = 1, budgetMs = 100),
            pkg = "com.yishou.app",
            instanceId = "inst",
            post = { true },
            mono = { 1L },
            facts = { AckFacts() },
        )
        assertTrue(old is ChallengeOutcome.Fail)
        assertTrue((old as ChallengeOutcome.Fail).error.contains("协议版本过旧"))
    }

    @Test
    fun `事实仓库按真实发生过的事记账`() {
        Facts.reset()
        assertNull(Facts.snapshot().instanceId)
        assertFalse(Facts.snapshot().connected)

        Facts.onConnected("inst-A")
        assertTrue(Facts.snapshot().connected)
        assertEquals(0L, Facts.snapshot().eventSeq)

        assertEquals(1L, Facts.onEvent(1_000))
        assertEquals(2L, Facts.onEvent(2_000))
        assertEquals(2L, Facts.snapshot().eventSeq)
        assertEquals(2_000L, Facts.snapshot().eventAtMono)

        Facts.onReceipt("OBSERVED_LOCKED", 3_000)
        Facts.onRuleRevision(5)
        val ack = Facts.ackFacts()
        assertEquals("OBSERVED_LOCKED", ack.lastReceiptState)
        assertEquals(3_000L, ack.lastReceiptAtMono)
        assertEquals(5, ack.ruleRevision)
        assertEquals(2L, ack.eventSeq)

        Facts.onDisconnected()
        assertFalse(Facts.snapshot().connected)
        Facts.reset()
        assertEquals(0L, Facts.snapshot().eventSeq)
    }

    @Test
    fun `ack 必须带执行线程处理时刻`() {
        val handler = A11yChallengeHandler()
        val out = handler.handle(
            req = req(),
            pkg = "com.yishou.app",
            instanceId = "inst",
            post = { block -> Thread { block() }.start(); true },
            // 时钟返回 0 = 没有可用的处理时刻
            mono = { 0L },
            facts = { AckFacts() },
        )
        assertTrue(out is ChallengeOutcome.Fail)
        assertEquals("dispatcher did not report handled", (out as ChallengeOutcome.Fail).error)
        assertNotNull(out.errorText())
    }
}
