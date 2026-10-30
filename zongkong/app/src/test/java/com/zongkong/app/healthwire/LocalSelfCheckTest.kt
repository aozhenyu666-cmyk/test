package com.zongkong.app.healthwire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 回归测试：本地自检必须能在**哨兵沉默**的情况下独立判断「自己还活不活」。
 *
 * ## 这条测试在防什么
 *
 * 修复前的判据是 `selfHealthy = (dispatcherAck 新鲜)`，而 ack 只有哨兵发起 challenge 才产生。
 * 于是哨兵一沉默 → 没有 ack → `selfHealthy` 恒 false → `LocalFallback.decide` 的兜底分支永不进入，
 * 本地锁屏、执行回执整条链在生产上从未运行过。
 *
 * [LocalSelfCheck] 把「发起方」换成自己，本文件逐条钉住它的判定语义：
 * **只有探针真的在无障碍线程上跑完才算通过**，其余一律失败 —— 不读开关、不读进程内布尔。
 */
class LocalSelfCheckTest {

    private var now = 1_000_000L

    private fun check(budgetMs: Long = 40L, cacheMs: Long = 0L) =
        LocalSelfCheck(budgetMs = budgetMs, cacheMs = cacheMs, nowMono = { now })

    /** 投递口没挂上（服务没连 / 已销毁）→ 必须失败，且原因是「没挂上」而不是含糊的 false。 */
    @Test
    fun `投递口没挂上时判失败`() {
        val r = check().probe(null)
        assertFalse("没有投递口不能算健康", r.ok)
        assertEquals(LocalSelfCheck.Verdict.NO_POST, r.verdict)
    }

    /** 投递被拒（Handler 已退出）→ 失败。不能因为「没有抛异常」就当成功。 */
    @Test
    fun `投递被拒时判失败`() {
        val r = check().probe { _ -> false }
        assertFalse(r.ok)
        assertEquals(LocalSelfCheck.Verdict.REJECTED, r.verdict)
    }

    /** 投递成功且线程立刻跑完 → 通过。这是唯一算通过的情形。 */
    @Test
    fun `线程真的跑完才算通过`() {
        val r = check().probe { task ->
            task()
            true
        }
        assertTrue(r.ok)
        assertEquals(LocalSelfCheck.Verdict.OK, r.verdict)
    }

    /**
     * **最关键的一条**：投递被接受了，但线程压根没跑（模拟无障碍线程卡死 / 主线程被冻住）。
     *
     * 这正是「无障碍开关开着、进程还在，但眼睛已经不动了」那种状态 ——
     * 修复前的事件通道判据在这种情况下是**判不出来**的（会沉默成 PENDING）。
     * 本地自检必须在这里给出明确的 TIMEOUT，而不是等满 800ms 才含糊地 false。
     */
    @Test
    fun `投递成功但线程不跑时判超时`() {
        val r = check(budgetMs = 40L).probe { _ ->
            // 接受投递，但永不执行 —— 线程卡死。
            true
        }
        assertFalse("线程没跑不能算健康", r.ok)
        assertEquals(LocalSelfCheck.Verdict.TIMEOUT, r.verdict)
    }

    /** 探针自己抛异常也不能把巡检线程带崩，按失败处理。 */
    @Test
    fun `投递过程抛异常时判失败且不崩`() {
        val r = check().probe { _ -> throw IllegalStateException("handler 已退出") }
        assertFalse(r.ok)
        assertEquals(LocalSelfCheck.Verdict.REJECTED, r.verdict)
    }

    /** 缓存：同一窗口内不重复戳无障碍线程，但结论照旧返回。 */
    @Test
    fun `缓存窗口内不重复投递`() {
        var posts = 0
        val selfCheck = check(cacheMs = 5_000L)

        val first = selfCheck.probe { task -> posts++; task(); true }
        assertTrue(first.ok)
        assertEquals(1, posts)

        // 1 秒后再问一次：走缓存，不再投递
        now += 1_000L
        val second = selfCheck.probe { task -> posts++; task(); true }
        assertTrue("缓存也要如实返回上次结论", second.ok)
        assertEquals(LocalSelfCheck.Verdict.CACHED, second.verdict)
        assertEquals("缓存窗口内不该重复戳线程", 1, posts)
    }

    /** 缓存过期 → 必须重新真投（不能靠旧结论一直「健康」下去）。 */
    @Test
    fun `缓存过期后重新真实投递`() {
        var posts = 0
        val selfCheck = check(cacheMs = 5_000L)
        selfCheck.probe { task -> posts++; task(); true }

        now += 5_001L
        val again = selfCheck.probe { task -> posts++; task(); true }
        assertEquals(LocalSelfCheck.Verdict.OK, again.verdict)
        assertEquals(2, posts)
    }

    /** 缓存不能把「失败」洗成「成功」：先成功后卡死，缓存过期后必须能报出 TIMEOUT。 */
    @Test
    fun `缓存过期后能如实报出失败`() {
        val selfCheck = check(budgetMs = 40L, cacheMs = 1_000L)
        assertTrue(selfCheck.probe { task -> task(); true }.ok)

        now += 1_001L
        val bad = selfCheck.probe { _ -> true } // 接受但不执行
        assertFalse("线程已经不跑了，不能再沿用旧的通过结论", bad.ok)
        assertEquals(LocalSelfCheck.Verdict.TIMEOUT, bad.verdict)
    }

    /** fresh()：通过后在有效期内为真，超过有效期自动失效（防止用很久以前的结论冒充健康）。 */
    @Test
    fun `fresh 只在有效期内为真`() {
        val selfCheck = check()
        assertFalse("还没自检过不能算新鲜", selfCheck.fresh(now))

        selfCheck.probe { task -> task(); true }
        assertTrue(selfCheck.fresh(now))

        now += LocalSelfCheck.FRESH_WINDOW_MS + 1L
        assertFalse("超过有效期必须失效", selfCheck.fresh(now))
    }

    /** fresh() 在最后一次失败后必须为假 —— 失败优先于更早的成功。 */
    @Test
    fun `最近一次失败会覆盖更早的成功`() {
        val selfCheck = check(budgetMs = 40L, cacheMs = 0L)
        selfCheck.probe { task -> task(); true }
        assertTrue(selfCheck.fresh(now))

        now += 100L
        selfCheck.probe(null) // 投递口没了
        assertFalse("最近一次失败之后不许再报健康", selfCheck.fresh(now))
    }

    /**
     * 语义守卫：自检**不接受任何「关于健康的外部断言」**。
     *
     * 这条测试的存在本身就是防回归 —— 只要有人给 [LocalSelfCheck] 加上
     * 「传入 running / 开关态 / ack 就让通过」的旁路，就必须先改这里。
     * 现在的构造签名只有 budget / cache / 时钟，测的就是这一条。
     */
    @Test
    fun `自检只认探针结果 不认任何外部健康断言`() {
        // 同一次时钟、同样的预算，唯一变量是探针跑没跑。
        val alive = check().probe { task -> task(); true }
        val wedged = check(budgetMs = 40L).probe { _ -> true }
        assertTrue("线程活着 → 通过", alive.ok)
        assertFalse("线程卡死 → 失败，无论外面怎么声称", wedged.ok)
    }
}
