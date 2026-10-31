package com.zongkong.sentinel.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-2 回归测试：事件通道判据不能把「静置」当成「故障」。
 *
 * ## 修复前的问题
 *
 * `judge()` 直接比 `clientEventAtMono < compareRefMono`。但客户端和哨兵是**两个独立的无障碍服务**，
 * 同一次窗口切换由系统分别派发，谁先收到不确定，两端各自取 `SystemClock.elapsedRealtime()`，
 * 天然相差几十到几百毫秒。
 *
 * 于是只要静置超过观察窗口（20s），`sinceCompareMs >= graceMs` 成立、
 * `clientEventAtMono < compareRefMono` 有约一半概率成立 → **每静置一段时间就误报一次「事件通道异常」**。
 *
 * 修复：加两个东西
 *  1. 时间抖动容差 [EventChannel.SKEW_TOLERANCE_MS]：同一次切换的毫秒级先后不算落后；
 *  2. 参照事件可信年龄 [EventChannel.REF_MAX_AGE_MS]：超过它说明两端都在静置，只能 PENDING。
 *
 * 这两条**不能掩护真正的卡死**：真卡死时客户端进度会落后几十秒甚至几分钟，远超容差。
 */
class EventChannelSkewTest {

    /** 构造一个「哨兵看到事件、执行线程还健康」的基准上下文。 */
    private fun ctx(
        clientEventAtMono: Long,
        compareRefMono: Long,
        sinceCompareMs: Long,
        graceMs: Long = EventChannel.DEFAULT_GRACE_MS,
    ) = EventChannel.Context(
        sentinelSawEventAfter = true,
        clientEventAtMono = clientEventAtMono,
        compareRefMono = compareRefMono,
        sinceCompareMs = sinceCompareMs,
        graceMs = graceMs,
        dispatcherHealthy = true,
        registered = true,
        permissionEnabled = true,
    )

    /**
     * 核心用例：客户端只比参照时刻早 800ms —— 这就是同一次切换的派发抖动。
     *
     * 修复前：静置 30s 后判 ABNORMAL（误报）。
     * 修复后：判 VERIFIED（客户端进度没落后）。
     */
    @Test
    fun `同一次切换的毫秒抖动不算异常`() {
        val (verdict, why) = EventChannel.judge(
            ctx(clientEventAtMono = 4_200, compareRefMono = 5_000, sinceCompareMs = 30_000),
        )
        assertEquals("800ms 的派发抖动不是故障，实际判成：$verdict（$why）", EventChannel.Verdict.VERIFIED, verdict)
    }

    /** 容差边界：正好落在容差上，仍算已体现（闭区间，不用 `<` 卡边界）。 */
    @Test
    fun `容差边界上仍算已体现`() {
        val ref = 10_000L
        val (verdict, _) = EventChannel.judge(
            ctx(
                clientEventAtMono = ref - EventChannel.SKEW_TOLERANCE_MS,
                compareRefMono = ref,
                sinceCompareMs = 60_000,
            ),
        )
        assertEquals(EventChannel.Verdict.VERIFIED, verdict)
    }

    /** 超出容差并过了观察窗口 → 才是真的落后，必须报 ABNORMAL。 */
    @Test
    fun `超出容差且过了观察窗口才算落后`() {
        val ref = 10_000L
        val (verdict, why) = EventChannel.judge(
            ctx(
                clientEventAtMono = ref - EventChannel.SKEW_TOLERANCE_MS - 1L,
                compareRefMono = ref,
                sinceCompareMs = 60_000,
            ),
        )
        assertEquals(EventChannel.Verdict.ABNORMAL, verdict)
        assertTrue(why.contains("没有前进"))
    }

    /** 真卡死形态：客户端落后 5 分钟 → 必须报 ABNORMAL（容差掩护不了它）。 */
    @Test
    fun `客户端落后数分钟必须报异常`() {
        val (verdict, _) = EventChannel.judge(
            ctx(clientEventAtMono = 1_000, compareRefMono = 301_000, sinceCompareMs = 25_000),
        )
        assertEquals(EventChannel.Verdict.ABNORMAL, verdict)
    }

    /**
     * 核心用例二：静置一小时，客户端进度当然没动 —— 但这不是故障。
     *
     * 参照事件已经太旧（超过 [EventChannel.REF_MAX_AGE_MS]），两端都静置，
     * 判「谁卡住了」没有依据 → 只能 PENDING。
     */
    @Test
    fun `静置超过参照可信年龄时不判定`() {
        val (verdict, why) = EventChannel.judge(
            ctx(
                clientEventAtMono = 1_000,
                compareRefMono = 5_000,
                sinceCompareMs = 3_600_000,
            ),
        )
        assertEquals("长时间静置不许报异常，实际：$verdict", EventChannel.Verdict.PENDING, verdict)
        assertTrue("理由要说清是静置，实际：$why", why.contains("不做判定") || why.contains("都没有新的窗口切换"))
    }

    /** 参照可信年龄边界：正好等于上限仍可判定（ABNORMAL），超过才降级。 */
    @Test
    fun `参照年龄边界仍可判断`() {
        val ref = 5_000L
        val atLimit = EventChannel.judge(
            ctx(
                clientEventAtMono = 1_000,
                compareRefMono = ref,
                sinceCompareMs = EventChannel.REF_MAX_AGE_MS,
            ),
        )
        assertEquals("正好到上限还要能判", EventChannel.Verdict.ABNORMAL, atLimit.first)

        val overLimit = EventChannel.judge(
            ctx(
                clientEventAtMono = 1_000,
                compareRefMono = ref,
                sinceCompareMs = EventChannel.REF_MAX_AGE_MS + 1L,
            ),
        )
        assertEquals("超一点就降级为不判定", EventChannel.Verdict.PENDING, overLimit.first)
    }

    /** 观察窗口内的落后仍然只是 PENDING（不能抢跑误报）。 */
    @Test
    fun `观察窗口内不抢跑`() {
        val (verdict, _) = EventChannel.judge(
            ctx(clientEventAtMono = 1_000, compareRefMono = 5_000, sinceCompareMs = 5_000),
        )
        assertEquals(EventChannel.Verdict.PENDING, verdict)
    }

    /** 容差可注入：调用方（或未来配置）能把容差调严，判据要跟着变。 */
    @Test
    fun `容差可注入`() {
        val (verdict, _) = EventChannel.judge(
            EventChannel.Context(
                sentinelSawEventAfter = true,
                clientEventAtMono = 4_200,
                compareRefMono = 5_000,
                sinceCompareMs = 60_000,
                skewToleranceMs = 100L, // 比 800ms 的抖动更严
                dispatcherHealthy = true,
            ),
        )
        assertEquals("容差调严之后 800ms 的抖动必须被判成落后", EventChannel.Verdict.ABNORMAL, verdict)
    }
}
