package com.zongkong.app.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拦截收据的纯逻辑测试（P1-04）：把「请求发出去了」和「拦截页真的到前台了」分开。
 *
 * 关心四件事：真的看到页面才算成功；没看到必须记失败；超时不会重复上报；重试有界。
 */
class BlockReceiptTest {

    private val block = BlockReceipt.BLOCK_SCREEN
    private val gate = BlockReceipt.GATE_SCREEN

    private fun machine() = BlockReceipt.Machine()

    // ---------- 请求 → 观察到 ----------

    @Test
    fun requestThenOwnScreenForegroundIsObservedWithLatency() {
        val m = machine()
        val a = m.onRequest("com.ss.android.ugc.aweme", 1_000L)
        assertEquals("com.ss.android.ugc.aweme", a.pkg)
        assertEquals(0, a.retries)
        assertEquals(a, m.pendingAttempt())

        val d = m.onForeground("com.zongkong.app", block, 1_420L)
        assertNotNull(d.receipt)
        val r = d.receipt!!
        assertEquals(BlockReceipt.Outcome.Observed, r.outcome)
        assertTrue(r.ok)
        assertEquals(420L, r.latencyMillis)
        assertEquals(BlockReceipt.KIND_OK, r.kind())
        assertTrue(r.describe().contains("420ms"))
        // 结案后没有在途请求了
        assertNull(m.pendingAttempt())
        assertNull(d.retry)
    }

    @Test
    fun unrelatedWindowDoesNotSettleTheAttempt() {
        val m = machine()
        m.onRequest("com.xingin.xhs", 1_000L)
        // 中间路过的别的应用（桌面、系统界面……）不能当成成功，也不能当成失败
        assertNull(m.onForeground("com.android.launcher", "com.android.launcher.Launcher", 1_100L).receipt)
        assertNull(m.onForeground("com.zongkong.app", "com.zongkong.app.ui.MainActivity", 1_200L).receipt)
        assertNotNull(m.pendingAttempt())
        // 之后真的看到拦截页，还是这一次请求
        assertEquals(BlockReceipt.Outcome.Observed, m.onForeground("com.zongkong.app", block, 1_600L).receipt?.outcome)
    }

    @Test
    fun gateAttemptIsOnlyObservedByGateScreen() {
        val m = machine()
        m.onRequest("tv.danmaku.bili", 1_000L, BlockReceipt.Target.GATE)
        // 低屏模式的请求只认做题页
        assertNull(m.onForeground("com.zongkong.app", block, 1_100L).receipt)
        val r = m.onForeground("com.zongkong.app", gate, 1_300L).receipt
        assertEquals(BlockReceipt.Outcome.Observed, r?.outcome)
        assertEquals(BlockReceipt.Target.GATE, r?.target)
        assertEquals(300L, r?.latencyMillis)
        assertTrue(r!!.describe().contains("做题页"))
    }

    @Test
    fun blockAndGateAttemptsDoNotSettleEachOther() {
        // 拦截页和做题页各有各的在途请求：低屏模式弹做题页，不能把正在等的拦截页判成失败
        val m = machine()
        m.onRequest("com.ss.android.ugc.aweme", 1_000L, BlockReceipt.Target.BLOCK)
        m.onRequest("com.ss.android.ugc.aweme", 1_000L, BlockReceipt.Target.GATE)
        // 做题页出现了：只有做题页那笔结案，拦截页还在等
        val d = m.onForeground("com.zongkong.app", gate, 1_400L)
        assertEquals(1, d.receipts.size)
        assertEquals(BlockReceipt.Target.GATE, d.receipt?.target)
        assertEquals(BlockReceipt.Target.BLOCK, m.pendingAttempt(BlockReceipt.Target.BLOCK)?.target)
        assertNull(m.pendingAttempt(BlockReceipt.Target.GATE))
        // 拦截页随后也到了：两笔各自成功，谁来结案都不会互相顶掉
        assertEquals(BlockReceipt.Outcome.Observed, m.onForeground("com.zongkong.app", block, 1_700L).receipt?.outcome)
        assertEquals(2, m.recent().size)
        assertTrue(m.recent().all { it.ok })
    }

    @Test
    fun blockAttemptIsNotObservedByGateScreenAndViceVersa() {
        // 请求拦截页时出现的是做题页（或反过来）：不算成功，继续等
        val m = machine()
        m.onRequest("com.xingin.xhs", 1_000L, BlockReceipt.Target.BLOCK)
        assertNull(m.onForeground("com.zongkong.app", gate, 1_200L).receipt)
        assertNotNull(m.pendingAttempt(BlockReceipt.Target.BLOCK))
    }

    // ---------- 超时 ----------

    @Test
    fun noActivityObservedTimesOut() {
        val m = machine()
        m.onRequest("com.smile.gifmaker", 1_000L)
        // 还没到时限：什么都不说
        assertNull(m.onTick(1_000L + BlockReceipt.TIMEOUT_MILLIS - 1).receipt)
        assertNotNull(m.pendingAttempt())
        // 到点了：记失败，且不算成功
        val r = m.onTick(1_000L + BlockReceipt.TIMEOUT_MILLIS).receipt
        assertEquals(BlockReceipt.Outcome.TimedOut, r?.outcome)
        assertFalse(r!!.ok)
        assertEquals(-1L, r.latencyMillis)
        assertEquals(BlockReceipt.KIND_FAIL, r.kind())
        assertTrue(r.describe().contains("没看到"))
    }

    @Test
    fun timeoutIsReportedOnlyOnceForTheSameAttempt() {
        val m = machine()
        m.onRequest("com.zhihu.android", 1_000L)
        val first = m.onTick(4_000L)
        assertEquals(BlockReceipt.Outcome.TimedOut, first.receipt?.outcome)
        // 同一个请求只结一次案：马上再问，不会有第二张收据
        assertNull(m.onTick(4_100L).receipt)
        assertEquals(1, m.recent().size)
        // 之后报的是「重试那一次」自己超时，不是同一张收据被重复上报
        assertNull(m.onTick(4_000L + BlockReceipt.TIMEOUT_MILLIS - 1).receipt)
        val second = m.onTick(4_000L + BlockReceipt.TIMEOUT_MILLIS).receipt
        assertEquals(BlockReceipt.Outcome.TimedOut, second?.outcome)
        assertEquals(1, second?.retries)
        assertEquals(2, m.recent().size)
        assertTrue(m.recent().map { it.seq }.toSet().size == 2)
    }

    @Test
    fun observedAttemptIsNotReportedAgainByLaterTicks() {
        val m = machine()
        m.onRequest("com.zhihu.android", 1_000L)
        assertEquals(BlockReceipt.Outcome.Observed, m.onForeground("com.zongkong.app", block, 1_300L).receipt?.outcome)
        // 拦住以后，随便 tick 多少次都不会再冒失败出来
        repeat(5) { assertNull(m.onTick(2_000L + it * 5_000L).receipt) }
        assertNull(m.pendingAttempt())
        assertEquals(1, m.recent().size)
        assertTrue(m.last()!!.ok)
    }

    @Test
    fun noPendingAttemptMeansNoTimeout() {
        val m = machine()
        assertNull(m.onTick(10_000L).receipt)
        assertNull(m.onTick(10_000L).retry)
        assertTrue(m.recent().isEmpty())
    }

    // ---------- 重试有界 ----------

    @Test
    fun timeoutRetriesAtMostOnce() {
        val m = machine()
        val a = m.onRequest("com.sina.weibo", 1_000L)
        // 第一次超时：结案 + 给一个重试请求（同一个应用、带一次 retries）
        val d1 = m.onTick(4_000L)
        assertEquals(BlockReceipt.Outcome.TimedOut, d1.receipt?.outcome)
        assertNotNull(d1.retry)
        val retry = d1.retry!!
        assertEquals(a.pkg, retry.pkg)
        assertEquals(1, retry.retries)
        assertTrue(retry.id != a.id)
        assertEquals(retry, m.pendingAttempt())

        // 重试也超时：结案，但没有下一次重试了（不会无限循环）
        val d2 = m.onTick(7_000L)
        assertEquals(BlockReceipt.Outcome.TimedOut, d2.receipt?.outcome)
        assertEquals(1, d2.receipt?.retries)
        assertNull(d2.retry)
        assertNull(m.pendingAttempt())
        // 再来多少次 tick 都不会再冒请求出来
        repeat(10) { assertNull(m.onTick(8_000L + it).retry) }
        assertEquals(2, m.recent().size)
    }

    @Test
    fun retriedAttemptCanStillBeObserved() {
        val m = machine()
        m.onRequest("com.baidu.tieba", 1_000L)
        val d1 = m.onTick(4_000L)
        assertNotNull(d1.retry)
        val d2 = m.onForeground("com.zongkong.app", block, 4_250L)
        val r = d2.receipt
        assertEquals(BlockReceipt.Outcome.Observed, r?.outcome)
        assertEquals(250L, r?.latencyMillis)
        assertEquals(1, r?.retries)
        assertEquals(2, m.recent().size)
    }

    // ---------- 被拦的应用又回来了 ----------

    @Test
    fun blockedPkgReturningWhilePendingIsRegressedAndPkgIsReforeground() {
        val m = machine()
        m.onRequest("com.ss.android.ugc.aweme", 1_000L)
        // 过了宽限期又看到被拦的应用在前台：说明拦截页没顶住
        val d = m.onForeground("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.SplashActivity", 2_000L)
        assertEquals(BlockReceipt.Outcome.Regressed, d.receipt?.outcome)
        assertFalse(d.receipt!!.ok)
        // 此刻被拦的应用就是前台应用：调用方（GuardService）照常再拦一次
        assertEquals(BlockReceipt.KIND_BACK, d.receipt!!.kind())
        assertNull(m.pendingAttempt())

        // 重新拦一次：新请求，verdict 由后面的窗口事件决定
        val again = m.onRequest("com.ss.android.ugc.aweme", 2_100L)
        assertEquals(0, again.retries)
        assertEquals(BlockReceipt.Outcome.Observed, m.onForeground("com.zongkong.app", block, 2_300L).receipt?.outcome)
    }

    @Test
    fun immediateEchoOfTheBlockedAppIsNotARegression() {
        val m = machine()
        m.onRequest("com.smile.gifmaker", 1_000L)
        // 请求刚发出去时系统补发的同应用窗口事件：那是「还没走」，不是「又回来了」
        assertNull(m.onForeground("com.smile.gifmaker", "com.smile.gifmaker.Main", 1_100L).receipt)
        assertNotNull(m.pendingAttempt())
    }

    // ---------- 启动失败 ----------

    @Test
    fun launchFailureIsRecordedWithReason() {
        val m = machine()
        m.onRequest("com.tencent.tmgp.sgame", 1_000L)
        val d = m.onLaunchFailed("com.tencent.tmgp.sgame", 1_010L, "SecurityException")
        val r = d.receipt
        assertEquals(BlockReceipt.Outcome.FailedNoActivity, r?.outcome)
        assertFalse(r!!.ok)
        assertTrue(r.describe().contains("SecurityException"))
        assertNull(d.retry) // 打不开不重试，重试也没用
        assertNull(m.pendingAttempt())
    }

    // ---------- 缓冲与旧账 ----------

    @Test
    fun receiptsAreCappedAndOldestDropped() {
        val m = BlockReceipt.Machine(capacity = 3)
        repeat(5) { i ->
            m.onRequest("pkg$i", 1_000L + i * 100L)
            m.onForeground("com.zongkong.app", block, 1_050L + i * 100L)
        }
        val recent = m.recent()
        assertEquals(3, recent.size)
        assertEquals(listOf("pkg2", "pkg3", "pkg4"), recent.map { it.pkg })
        assertEquals("pkg4", m.last()?.pkg)
        // 序列号一直往前走，不会因为环形缓冲回绕
        assertEquals(5L, m.last()?.seq)
    }

    @Test
    fun staleAttemptFromPreviousIncidentDoesNotPoisonTheNextOne() {
        val m = machine()
        // 上一场：超时了一次、重试也超时（结案两次，都留在收据里）
        m.onRequest("com.xingin.xhs", 1_000L)
        m.onTick(4_000L)
        m.onTick(7_000L)
        assertEquals(2, m.recent().size)

        // 新一场：全新的请求，延迟只按这一次算，旧账不会污染它
        val fresh = m.onRequest("com.xingin.xhs", 60_000L)
        assertEquals(0, fresh.retries)
        val r = m.onForeground("com.zongkong.app", block, 60_180L).receipt
        assertEquals(BlockReceipt.Outcome.Observed, r?.outcome)
        assertEquals(180L, r?.latencyMillis)
        assertEquals(3, m.recent().size)
        // 上一次的失败还看得见（诊断要真话），但最近一张是成功的
        assertTrue(m.recent().take(2).all { !it.ok })
        assertTrue(m.last()!!.ok)
    }

    @Test
    fun newRequestSupersedesAnUnsettledOneInsteadOfClaimingSuccess() {
        val m = machine()
        m.onRequest("pkgA", 1_000L)
        // 还没看到页面就换了个应用：旧请求按「没观测到」结案，不冒充成功
        m.onRequest("pkgB", 1_500L)
        val old = m.recent().single()
        assertEquals("pkgA", old.pkg)
        assertEquals(BlockReceipt.Outcome.FailedNoActivity, old.outcome)
        assertFalse(old.ok)
        assertEquals("pkgB", m.pendingAttempt()?.pkg)
    }

    @Test
    fun describeSaysSeenOrNotWithoutFakingSuccess() {
        val m = machine()
        m.onRequest("com.kuaishou.nebula", 1_000L)
        m.onTick(4_000L)
        val text = m.last()!!.describe()
        assertTrue(text, "没看到" in text)
        assertFalse(text, "延迟" in text)
        assertTrue(text, "com.kuaishou.nebula" in text)
    }
}
