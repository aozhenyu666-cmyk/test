package com.zongkong.app.healthwire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T02 的核心用例：挑战必须投递到真正承担无障碍执行的线程再带回。
 *
 * 这些用例故意不碰 Android：用可控的 post 回调模拟 a11y 主线程。
 */
class A11yChallengeTest {

    private val handler = A11yChallengeHandler()

    private fun req(nonce: String = "n1", seq: Long = 7, budget: Long = 500) =
        A11yChallengeRequest(v = 2, nonce = nonce, seq = seq, budgetMs = budget)

    @Test
    fun `没连上立刻失败且不构造 ack`() {
        var posted = false
        val out = handler.handle(
            req(), "com.zongkong.app", null,
            post = { posted = true; true },
            mono = { 1_000L },
            facts = { AckFacts() },
        )
        assertFalse(out.ok)
        assertEquals("not connected", out.errorText())
        assertFalse("没连上时不许往 a11y 线程投递", posted)
    }

    @Test
    fun `投递到执行线程后才带回 ack`() {
        val handledAt = 4_242L
        val out = handler.handle(
            req(), "com.zongkong.app", "inst-1",
            post = { run -> run(); true },
            mono = { handledAt },
            facts = { AckFacts(ruleRevision = 3, lastReceiptState = "OBSERVED_LOCKED", eventSeq = 9) },
        )
        val ack = out as ChallengeOutcome.Ack
        assertEquals(2, ack.protocolVersion)
        assertEquals("inst-1", ack.instanceId)
        assertEquals("n1", ack.nonce)
        assertEquals(7L, ack.seq)
        assertEquals(handledAt, ack.handledAtMono)
        assertEquals(3, ack.facts.ruleRevision)
        assertEquals(9L, ack.facts.eventSeq)
    }

    @Test
    fun `执行线程冻结时超时返回 ok=false 而不是伪造 ack`() {
        val out = handler.handle(
            req(budget = 200), "com.zongkong.app", "inst-1",
            // 投递成功，但 a11y 线程永不执行（线程卡死/被冻结）
            post = { true },
            mono = { 1L },
            facts = { error("超时路径不许读事实并构造 ack") },
        )
        assertTrue(out is ChallengeOutcome.Timeout)
        assertFalse(out.ok)
        assertEquals("dispatcher timeout", out.errorText())
    }

    @Test
    fun `超时会回调失败原因 供看门狗使用`() {
        var reason = ""
        handler.handle(
            req(budget = 150), "p", "inst-1",
            post = { true }, mono = { 1L }, facts = { AckFacts() },
            onFailed = { reason = it },
        )
        assertTrue(reason.contains("dispatcher timeout"))
    }

    @Test
    fun `投递被拒绝等于没连上`() {
        val out = handler.handle(
            req(), "p", "inst-1",
            post = { false }, mono = { 1L }, facts = { AckFacts() },
        )
        assertEquals("not connected", out.errorText())
    }

    @Test
    fun `预算为 0 或负数时用默认 5 秒 不会变成无限等待`() {
        val started = System.nanoTime()
        val out = handler.handle(
            req(budget = 0), "p", "inst-1",
            post = { true }, mono = { 1L }, facts = { AckFacts() },
            budgetCapMs = 300,
        )
        val spentMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(out is ChallengeOutcome.Timeout)
        assertTrue("预算应被压到 300ms 上限，实际 ${spentMs}ms", spentMs < 5_000)
    }

    @Test
    fun `在途请求有上限 不会为冻结的调用无限堆线程`() {
        val gate = CountDownLatch(1)
        val a11yThread = Thread {
            // 模拟 a11y 线程被冻在这儿：一直不处理
            gate.await(30, TimeUnit.SECONDS)
        }.apply { isDaemon = true; start() }
        try {
            val blockedHandler = A11yChallengeHandler(maxInFlight = 1)
            val first = CountDownLatch(1)
            Thread {
                blockedHandler.handle(
                    req(budget = 2_000), "p", "inst-1",
                    post = { true }, mono = { 1L }, facts = { AckFacts() },
                )
                first.countDown()
            }.apply { isDaemon = true; start() }
            // 等第一个进在途
            Thread.sleep(200)
            val second = blockedHandler.handle(
                req(budget = 300), "p", "inst-1",
                post = { true }, mono = { 1L }, facts = { AckFacts() },
            )
            assertFalse(second.ok)
            assertTrue(second.errorText().contains("too many in flight"))
            first.await(5, TimeUnit.SECONDS)
        } finally {
            gate.countDown()
            a11yThread.join(1_000)
        }
    }

    @Test
    fun `旧协议版本被拒绝`() {
        val out = handler.handle(
            A11yChallengeRequest(v = 1, nonce = "n", seq = 1, budgetMs = 100),
            "p", "inst-1",
            post = { true }, mono = { 1L }, facts = { AckFacts() },
        )
        assertTrue(out.errorText().contains("协议版本过旧"))
    }

    @Test
    fun `nonce 为空被拒绝`() {
        val out = handler.handle(
            req(nonce = "  "), "p", "inst-1",
            post = { true }, mono = { 1L }, facts = { AckFacts() },
        )
        assertTrue(out.errorText().contains("nonce"))
    }
}

/** 台账：只有真实 ack 才健康；恢复必须换实例 + 恢复后的真 ack（T11）。 */
class HealthLedgerTest {

    private var mono = 1_000L
    private var nextInstance = 0
    private fun ledger() = HealthLedger(mono = { mono }, newInstanceId = { "inst-${++nextInstance}" })

    @Test
    fun `刚注册没 ack 时不是健康`() {
        val l = ledger()
        l.setRegistered(true)
        l.onConnected()
        val v = l.verdict(permissionEnabled = true)
        // 内核在这条路上给 UNKNOWN/PROBE：证据不足，绝不是绿色。
        assertNotNull(v)
        assertFalse("没有真实 ack 不许 healthy", v.healthy)
    }

    @Test
    fun `真实 ack 之后健康`() {
        val l = ledger()
        l.setRegistered(true)
        l.onConnected()
        mono += 100
        l.noteAck(seq = 1, nowMono = mono)
        val v = l.verdict(permissionEnabled = true)
        assertTrue(v.healthy)
    }

    @Test
    fun `无障碍关掉时判 DISABLED 不看 ack`() {
        val l = ledger()
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, mono)
        val v = l.verdict(permissionEnabled = false)
        assertEquals(com.zongkong.app.health.HealthStatus.DISABLED, v.status)
    }

    @Test
    fun `断开后立刻失去健康 不保留假在线`() {
        val l = ledger()
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, mono)
        assertTrue(l.verdict(permissionEnabled = true).healthy)
        l.onDisconnected(mono + 10)
        val v = l.verdict(permissionEnabled = true)
        assertFalse("断开后不许还是 HEALTHY", v.healthy)
        assertNull(l.instanceId)
    }

    @Test
    fun `重连必须换新 instanceId`() {
        val l = ledger()
        val a = l.onConnected()
        l.onDisconnected()
        val b = l.onConnected()
        assertTrue("重建连接必须换新实例", a != b)
    }

    @Test
    fun `恢复只记录 不算修好 要新实例加真 ack`() {
        val l = ledger()
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, mono)
        // 造一次失败，并且真的过了确认预算（30s）：这才允许开始恢复
        repeat(3) { l.noteFailure("dispatcher timeout") }
        mono += 31_000
        val before = l.verdict(permissionEnabled = true)
        assertTrue(l.shouldStartRecovery(before))

        val startedAt = mono + 1
        assertTrue(l.beginRecovery(maxRecoveries = 2, nowMono = startedAt))
        val recovering = l.verdict(permissionEnabled = true)
        assertEquals(com.zongkong.app.health.HealthStatus.RECOVERING, recovering.status)
        assertFalse("恢复请求发出去不等于健康", recovering.healthy)

        // 同一实例还在：不算恢复成功
        val same = l.verdict(permissionEnabled = true)
        assertFalse(same.healthy)

        // 换了新实例 + 恢复之后的真 ack：才算健康
        mono = startedAt + 500
        l.onConnected()
        l.noteAck(seq = 2, nowMono = startedAt + 800)
        val fixed = l.verdict(permissionEnabled = true)
        assertTrue("新实例 + 恢复后真 ack 才 HEALTHY，实际 ${fixed.status} ${fixed.reason}", fixed.healthy)
    }

    @Test
    fun `恢复最多两次 超了给 ESCALATE`() {
        val l = ledger()
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, mono)
        repeat(3) { l.noteFailure("x") }
        assertTrue(l.beginRecovery(maxRecoveries = 2, nowMono = mono + 1))
        l.endRecovery()
        assertTrue(l.beginRecovery(maxRecoveries = 2, nowMono = mono + 2))
        l.endRecovery()
        assertFalse("第三次必须被拒绝", l.beginRecovery(maxRecoveries = 2, nowMono = mono + 3))
    }

    @Test
    fun `恢复预算花完给失联并升级`() {
        val l = ledger()
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, mono)
        repeat(3) { l.noteFailure("x") }
        l.beginRecovery(maxRecoveries = 2, nowMono = mono)
        mono += 10 * 60_000
        val v = l.verdict(permissionEnabled = true)
        assertEquals(com.zongkong.app.health.HealthStatus.UNRESPONSIVE, v.status)
        assertEquals(com.zongkong.app.health.HealthAction.ESCALATE, v.action)
    }

    @Test
    fun `事件通道在来 ack 不通时能说出矛盾`() {
        val l = ledger()
        l.setRegistered(true)
        l.onConnected()
        l.noteEvent(nowMono = mono)
        // 事件刚来过，但从来没有过 ack
        mono += 10_000
        val text = l.channelMismatch(nowMono = mono, staleAfterMs = 30_000)
        assertNotNull(text)
        assertTrue(text!!.contains("事件还在来"))
    }

    @Test
    fun `ack 新鲜但事件停了也能说出矛盾`() {
        val l = ledger()
        l.setRegistered(true)
        l.onConnected()
        l.noteEvent(nowMono = mono)
        mono += 60_000
        l.noteAck(1, nowMono = mono)
        val text = l.channelMismatch(nowMono = mono, staleAfterMs = 30_000)
        assertNotNull(text)
        assertTrue(text!!.contains("事件通道"))
    }

    @Test
    fun `暂停时判 PAUSED`() {
        val l = ledger()
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, mono)
        l.setPaused(true)
        val v = l.verdict(permissionEnabled = true)
        assertEquals(com.zongkong.app.health.HealthStatus.PAUSED, v.status)
    }
}
