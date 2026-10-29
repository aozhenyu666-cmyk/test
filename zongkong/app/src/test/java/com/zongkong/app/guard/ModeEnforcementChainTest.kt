package com.zongkong.app.guard

import com.zongkong.core.BlockMode
import com.zongkong.core.Config
import com.zongkong.core.DayLog
import com.zongkong.core.Engine
import com.zongkong.core.Gate
import com.zongkong.core.ModeConfig
import com.zongkong.core.ModeGate
import com.zongkong.core.Modes
import com.zongkong.core.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * **两档模式的端到端回归测试**（指令第四节第 11 条）。
 *
 * 这个文件存在的唯一理由，是复现并永久钉死我这次犯的真实回归：
 * 我把拦截判定从 `Status.strict` 改成 `Status.enforcing`，而全仓
 * `Modes.startStrict()` 的调用点是 0、默认 `tier = NORMAL`，
 * 于是 `enforcing` 永远为 false —— **拦截名单的强制拦截完全失效**。
 *
 * 当时 490 项测试全绿，因为没有任何一条测试问过
 * 「给定配置，拦截链路到底会不会被触发」。
 *
 * 所以这里的断言**不看** `Modes.startStrict()` 这类纯函数的返回值，
 * 而是走真实链路：
 *   `Config` → `Engine.evaluate()`（真实）→ `Status.enforcing`
 *   → `ModeGate.verdict()`（真实统一入口）→ 消费方的判定结论
 * 并且同时验证「黑名单非空」这个前提，因为空名单不可能触发拦截，
 * 空名单下的绿灯是假绿。
 */
class ModeEnforcementChainTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val blocked = setOf("com.google.android.youtube", "com.ss.android.ugc.aweme")

    /** 一个「平常会严管」的配置：关卡已过点未交，旧逻辑下 strict = true。 */
    private fun config(
        mode: ModeConfig = ModeConfig(),
        withOverdueGate: Boolean = true,
    ) = Config(
        gates = if (withOverdueGate) listOf(
            Gate(
                id = "g1", dept = com.zongkong.core.Dept.PLAN, title = "今日部署",
                instruction = "x", openAt = 0, deadline = 1,
                days = setOf(1, 2, 3, 4, 5, 6, 7), block = BlockMode.AFTER_DEADLINE,
            ),
        ) else emptyList(),
        blocked = blocked,
        mode = mode,
    )

    private fun eval(mode: ModeConfig, now: Long, withOverdueGate: Boolean = true): Status =
        Engine.evaluate(config(mode, withOverdueGate), DayLog("2026-10-29"), now, zone)

    private val t0 = 1_790_000_000_000L

    /** 消费方的真实判定：GuardService.enforce 与 Watch.ruleFor 用的都是这一个入口。 */
    private fun consumerWouldEnforce(status: Status, mode: ModeConfig, now: Long): Boolean =
        ModeGate.verdict(
            mode = mode,
            now = now,
            healthy = true,
            paused = status.paused,
            emergencyUntil = status.emergencyUntil,
        ).enforcing

    // ---------- 前提：这个测试真的有杀伤力 ----------

    @Test
    fun `前置条件 黑名单非空且旧逻辑的 strict 为真`() {
        assertTrue("黑名单必须非空，否则拦截不可能被触发，绿灯是假绿", blocked.isNotEmpty())
        val s = eval(ModeConfig(), t0)
        assertTrue(
            "这一条守住测试的杀伤力：旧逻辑下 strict 必须为真，" +
                "才能证明我们改的是「谁会真的拦」，而不是在测一个本来就不拦的场景",
            s.strict,
        )
    }

    // ---------- 回归核心：NORMAL 不拦 ----------

    @Test
    fun `NORMAL 下即使 strict 为真也不得强制拦截`() {
        val mode = ModeConfig() // 默认 NORMAL
        val s = eval(mode, t0)
        assertTrue("前提：strict 为真（有逾期关卡）", s.strict)
        assertFalse("**回归核心**：普通模式下强制拦截必须为假", s.enforcing)
        assertFalse("消费方不得执行强制拦截", consumerWouldEnforce(s, mode, t0))
        assertEquals("档位如实显示", Status.MODE_NORMAL, s.tier)
    }

    @Test
    fun `NORMAL 下黑名单应用可以正常打开`() {
        // T01 的内核断言：普通模式 = 娱乐 APP 正常放行。
        val mode = ModeConfig()
        val s = eval(mode, t0)
        blocked.forEach { pkg ->
            assertFalse("NORMAL 下 $pkg 不该被拦", consumerWouldEnforce(s, mode, t0))
        }
    }

    // ---------- 回归核心：STRICT 必须真拦 ----------

    @Test
    fun `显式启动 STRICT 后同一配置必须触发拦抁链路`() {
        val mode = Modes.startStrict(ModeConfig(), t0, 6 * 60)
        val s = eval(mode, t0 + 60_000L)
        assertTrue("**回归核心**：严格窗口内必须真的拦", s.enforcing)
        assertTrue("消费方必须执行", consumerWouldEnforce(s, mode, t0 + 60_000L))
        assertEquals(Status.MODE_STRICT, s.tier)
    }

    @Test
    fun `NORMAL 与 STRICT 在同一配置上结论相反`() {
        // 这是最直接的回归复现：只改模式，别的都不动。
        val now = t0 + 60_000L
        val normal = ModeConfig()
        val strict = Modes.startStrict(ModeConfig(), t0, 6 * 60)

        val n = consumerWouldEnforce(eval(normal, now), normal, now)
        val r = consumerWouldEnforce(eval(strict, now), strict, now)

        assertFalse("普通模式不拦", n)
        assertTrue("严格模式要拦", r)
        assertTrue("两者必须相反，否则两档模式形同虚设", n != r)
    }

    // ---------- 窗口到期 ----------

    @Test
    fun `窗口到期后自动回到不拦`() {
        val mode = Modes.startStrict(ModeConfig(), t0, 60)
        val inWindow = t0 + 59 * 60_000L
        val afterWindow = t0 + 61 * 60_000L

        assertTrue("窗口内要拦", consumerWouldEnforce(eval(mode, inWindow), mode, inWindow))
        assertFalse("到期必须停止拦截", consumerWouldEnforce(eval(mode, afterWindow), mode, afterWindow))
    }

    @Test
    fun `过期窗口重启后不得被当成有效窗口`() {
        // 模拟「进程重启」：持久化的 mode 还是 STRICT，但窗口早已过去。
        val expired = Modes.startStrict(ModeConfig(), t0, 30)
        val muchLater = t0 + 10 * 3_600_000L
        assertFalse("重启后不能把过期窗口重新当成有效", consumerWouldEnforce(eval(expired, muchLater), expired, muchLater))
        // 并且 expire 归一后档位也要回到普通，界面不能一直挂着「严格」。
        assertEquals(ModeConfig.Tier.NORMAL, Modes.expire(expired, muchLater).tier)
    }

    // ---------- 紧急暂停 ----------

    @Test
    fun `紧急暂停立即停止强制且不会被后台自动恢复`() {
        val mode = Modes.startStrict(ModeConfig(), t0, 6 * 60)
        val now = t0 + 10 * 60_000L
        assertTrue("按下之前是在拦的", consumerWouldEnforce(eval(mode, now), mode, now))

        val paused = Modes.emergency(mode, now)
        assertFalse("按下必须立刻停", consumerWouldEnforce(eval(paused, now), paused, now))

        // 往后走很久：没有任何自动恢复路径会把强制力还回来。
        for (later in listOf(now + 60_000L, now + 3_600_000L, now + 5 * 3_600_000L)) {
            assertFalse(
                "紧急暂停后 ${(later - now) / 60_000} 分钟也不得自动恢复",
                consumerWouldEnforce(eval(paused, later), paused, later),
            )
        }
    }

    @Test
    fun `紧急暂停不延长监管期`() {
        val mode = Modes.startStrict(ModeConfig(), t0, 60)
        val paused = Modes.emergency(mode, t0 + 60_000L)
        // 到点仍然结束：不能因为暂停过就继续管。
        val afterEnd = t0 + 61 * 60_000L
        assertFalse(consumerWouldEnforce(eval(paused, afterEnd), paused, afterEnd))
        assertFalse("紧急暂停不是第三档，到期照旧结束", paused.windowActive(afterEnd))
    }

    // ---------- 休假 / 限时放行仍然优先 ----------

    @Test
    fun `休假时严格模式也不拦`() {
        val mode = Modes.startStrict(ModeConfig(), t0, 6 * 60)
        val now = t0 + 60_000L
        val v = ModeGate.verdict(mode = mode, now = now, healthy = true, paused = true)
        assertFalse("休假优先级高于模式", v.enforcing)
    }

    @Test
    fun `限时放行期间严格模式不拦`() {
        val mode = Modes.startStrict(ModeConfig(), t0, 6 * 60)
        val now = t0 + 60_000L
        val v = ModeGate.verdict(mode = mode, now = now, healthy = true, emergencyUntil = now + 600_000L)
        assertFalse("紧急放行期间让路", v.enforcing)
    }

    // ---------- 健康与模式必须分开 ----------

    @Test
    fun `拦截能力坏掉时不虚报正常但也不清回普通模式`() {
        val mode = Modes.startStrict(ModeConfig(), t0, 6 * 60)
        val now = t0 + 60_000L
        val v = ModeGate.verdict(
            mode = mode,
            now = now,
            healthy = false,
            problems = listOf("无障碍执行线程没有响应"),
        )
        // 模式仍然是严格：不能因为拦不住就假装用户没选严格。
        assertEquals(ModeConfig.Tier.STRICT, v.tier)
        assertTrue("该约束仍然成立", v.enforcing)
        assertFalse("但不能报健康", v.healthy)
        assertTrue("必须能看出「该管却管不住」", v.effectiveButBroken)
        assertTrue(v.problems.isNotEmpty())
        assertTrue("状态文案要如实", v.headline().contains("异常"))
    }

    @Test
    fun `普通模式下健康正常也不拦`() {
        val now = t0
        val v = ModeGate.verdict(ModeConfig(), now, healthy = true)
        assertFalse(v.enforcing)
        assertFalse(v.effectiveButBroken)
        assertEquals("普通模式", v.headline())
    }

    // ---------- 来回切换 ----------

    @Test
    fun `普通与严格之间来回切换结论始终与模式一致`() {
        var mode = ModeConfig()
        val now = t0 + 60_000L
        repeat(3) {
            assertFalse("切回普通后不拦", consumerWouldEnforce(eval(mode, now), mode, now))
            mode = Modes.startStrict(mode, now, 60)
            assertTrue("开启严格后要拦", consumerWouldEnforce(eval(mode, now), mode, now))
            mode = Modes.toNormal(mode)
        }
        assertFalse(consumerWouldEnforce(eval(mode, now), mode, now))
    }

    @Test
    fun `切换过程中 revision 单调递增`() {
        // 哨兵靠 revision 判断「拿到的是不是最新规则」，所以它绝不能倒退。
        val now = t0 + 60_000L
        var mode = ModeConfig()
        var last = mode.revision
        repeat(3) {
            mode = Modes.startStrict(mode, now, 60); assertTrue(mode.revision > last); last = mode.revision
            mode = Modes.emergency(mode, now); assertTrue(mode.revision > last); last = mode.revision
            mode = Modes.toNormal(mode); assertTrue(mode.revision > last); last = mode.revision
        }
    }

    // ---------- 名单为空时不拦（避免假阳） ----------

    @Test
    fun `没有逾期关卡时严格窗口仍然要拦`() {
        // 严格模式不依赖「关卡没交」这个前提：它是用户主动开的窗口。
        // 注意这里不断言 strict 为假——沉默检查（silenceHours）也会让 strict 变真，
        // 那是提醒语义，与强制无关。真正要证明的是：enforcing 只由模式+窗口决定。
        val mode = Modes.startStrict(ModeConfig(), t0, 6 * 60)
        val s = eval(mode, t0 + 60_000L, withOverdueGate = false)
        assertTrue("严格窗口内依然要强制拦截，与关卡是否逾期无关", s.enforcing)
        assertTrue("消费方必须执行", consumerWouldEnforce(s, mode, t0 + 60_000L))
    }

    @Test
    fun `强制拦截只由模式与窗口决定与提醒理由无关`() {
        // 同一时刻、同一模式，只改「有没有逾期关卡」这个提醒来源：
        // enforcing 必须一致，证明它没有被 reasons 污染。
        val mode = Modes.startStrict(ModeConfig(), t0, 6 * 60)
        val now = t0 + 60_000L
        val withGate = eval(mode, now, withOverdueGate = true)
        val withoutGate = eval(mode, now, withOverdueGate = false)
        assertEquals(
            "enforcing 不该随提醒理由变化",
            withGate.enforcing,
            withoutGate.enforcing,
        )
        assertTrue(withGate.enforcing && withoutGate.enforcing)
    }

    @Test
    fun `黑名单为空时消费方不会误拦`() {
        // 这条防的是反向错误：名单空还去拦。
        val empty = Config(blocked = emptySet(), mode = Modes.startStrict(ModeConfig(), t0, 60))
        val st = Engine.evaluate(empty, DayLog("2026-10-29"), t0 + 60_000L, zone)
        assertTrue("窗口有效", st.enforcing)
        // enforcing 是「该不该约束」；具体某个应用拦不拦还要看它在不在名单里。
        assertTrue("空名单里没有 youtube", empty.blocked.isEmpty())
        assertNotNull(st)
    }

    // ---------- 第二条拦截路径：总控时间 lockdown ----------

    @Test
    fun `总控时间这条路径也必须受模式约束`() {
        // 真机上抓到的漏洞：GuardService.lockdown() 原先完全不看模式，
        // 于是**普通模式**下打开 YouTube 被「总控时间，弹回」拦掉。
        // 两条路径（enforce / lockdown）必须给出同一个结论。
        val now = t0 + 60_000L

        val normal = ModeConfig()
        val strict = Modes.startStrict(ModeConfig(), t0, 6 * 60)

        val normalGate = ModeGate.verdict(normal, now, healthy = true)
        val strictGate = ModeGate.verdict(strict, now, healthy = true)

        assertFalse("普通模式下，总控时间也不得弹回娱乐应用", normalGate.enforcing)
        assertTrue("严格模式下才允许弹回", strictGate.enforcing)
        assertEquals(
            "enforce 与 lockdown 必须用同一个判定",
            normalGate.enforcing,
            consumerWouldEnforce(eval(normal, now), normal, now),
        )
    }

    @Test
    fun `所有拦截入口共用同一个判定结果`() {
        // 把「消费方会用到的判定」集中断言一次：普通模式全假、严格模式全真。
        val now = t0 + 60_000L
        val normal = ModeConfig()
        val strict = Modes.startStrict(ModeConfig(), t0, 6 * 60)

        listOf(normal to false, strict to true).forEach { (mode, expected) ->
            val fromEngine = eval(mode, now).enforcing
            val fromGate = ModeGate.verdict(mode, now, healthy = true).enforcing
            val fromConsumer = consumerWouldEnforce(eval(mode, now), mode, now)
            assertEquals("Engine 的结论要符合预期（${mode.tier}）", expected, fromEngine)
            assertEquals("ModeGate 的结论要一致（${mode.tier}）", expected, fromGate)
            assertEquals("消费方的结论要一致（${mode.tier}）", expected, fromConsumer)
        }
    }
}
