package com.zongkong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两档模式：普通 / 严格（报告 §3）。
 *
 * 这组测试盯的是报告的核心诉求，也是原代码**完全没有**的东西：
 *  - 「普通模式：允许正常使用手机」「严格模式：用户主动开启一定时长的监管窗口」
 *  - 「不开发第三档模式」
 *  - 「不得未经用户明确授权自动延长窗口」
 *  - 「紧急暂停不能设置答题、等待或字数门槛」
 *
 * 背景：改造前 `Status.strict` 是**自动推导**的（关卡没交 / 额度超了 / 沉默超时），
 * 消费者拿它直接决定要不要弹回娱乐 APP。后果是：只要有一道关卡没过，
 * 用户在「普通」状态下也一律打不开娱乐 APP——名义两档、实际永远严格。
 */
class ModesTest {

    private val now = 1_790_000_000_000L
    private val hour = 3_600_000L

    // ---------- 默认必须是普通模式 ----------

    @Test
    fun `默认是普通模式`() {
        val m = ModeConfig()
        assertEquals(ModeConfig.Tier.NORMAL, m.tier)
        assertFalse("默认不该有任何窗口", m.windowActive(now))
    }

    @Test
    fun `普通模式下娱乐应用不被强制拦截`() {
        // 这是 T01 的内核断言：普通模式 = 不强制约束。
        val m = ModeConfig()
        assertFalse(m.windowActive(now))
    }

    // ---------- 严格窗口 ----------

    @Test
    fun `开启严格模式后窗口生效`() {
        val m = Modes.startStrict(ModeConfig(), now, 6 * 60)
        assertEquals(ModeConfig.Tier.STRICT, m.tier)
        assertTrue(m.windowActive(now))
        assertTrue(m.windowActive(now + 5 * hour))
    }

    @Test
    fun `窗口到点自动结束且不延长`() {
        val m = Modes.startStrict(ModeConfig(), now, 60)
        assertTrue("快到点时还在窗口内", m.windowActive(now + 59 * 60_000L))
        assertFalse("到点必须结束", m.windowActive(now + 60 * 60_000L))
        assertFalse("过点也不能自己续上", m.windowActive(now + 61 * 60_000L))
        // 关键：windowActive 是纯时间判定，任何「续期」都只能来自用户重新 startStrict。
        assertEquals(m.endsAt, Modes.startStrict(m, now, 60).copy(startsAt = m.startsAt).let { m.endsAt })
    }

    @Test
    fun `时长被夹在合法区间`() {
        assertEquals(Modes.MIN_WINDOW_MIN * 60_000L, Modes.startStrict(ModeConfig(), now, 0).let { it.endsAt - it.startsAt })
        assertEquals(Modes.MAX_WINDOW_MIN * 60_000L, Modes.startStrict(ModeConfig(), now, 999_999).let { it.endsAt - it.startsAt })
    }

    @Test
    fun `到期归一：tier 落回普通`() {
        val m = Modes.startStrict(ModeConfig(), now, 30)
        assertEquals(ModeConfig.Tier.STRICT, Modes.expire(m, now + 10 * 60_000L).tier)
        assertEquals("过点后必须归一成普通", ModeConfig.Tier.NORMAL, Modes.expire(m, now + 31 * 60_000L).tier)
    }

    @Test
    fun `回到普通模式立即停止拦截`() {
        val m = Modes.startStrict(ModeConfig(), now, 6 * 60)
        val back = Modes.toNormal(m)
        assertEquals(ModeConfig.Tier.NORMAL, back.tier)
        assertFalse(back.windowActive(now))
        assertEquals(0L, back.endsAt)
    }

    // ---------- 紧急暂停：无任何门槛 ----------

    @Test
    fun `紧急暂停立即生效且不要求任何条件`() {
        val m = Modes.startStrict(ModeConfig(), now, 6 * 60)
        val paused = Modes.emergency(m, now + hour)
        assertFalse("按下就必须立刻停止强制", paused.windowActive(now + hour))
        assertTrue(paused.emergencyActive(now + hour))
    }

    @Test
    fun `紧急暂停不会把监管期延长`() {
        val m = Modes.startStrict(ModeConfig(), now, 6 * 60)
        val paused = Modes.emergency(m, now + hour)
        assertEquals("结束时间不变", m.endsAt, paused.endsAt)
        // 到点仍然结束：不能因为暂停过就继续管。
        assertFalse(paused.windowActive(m.endsAt + 1))
    }

    @Test
    fun `普通模式下按紧急暂停是空操作`() {
        val m = ModeConfig()
        assertEquals(m, Modes.emergency(m, now))
    }

    @Test
    fun `可以解除紧急暂停恢复监管`() {
        val m = Modes.startStrict(ModeConfig(), now, 6 * 60)
        val paused = Modes.emergency(m, now + hour)
        val resumed = Modes.resume(paused)
        assertTrue(resumed.windowActive(now + 2 * hour))
    }

    @Test
    fun `新开窗口会清掉上一次的紧急暂停`() {
        val paused = Modes.emergency(Modes.startStrict(ModeConfig(), now, 60), now + 60_000L)
        val fresh = Modes.startStrict(paused, now + 2 * hour, 60)
        assertTrue("新窗口必须真的生效，不能被上轮的暂停带着一起开始", fresh.windowActive(now + 2 * hour))
        assertEquals(0L, fresh.emergencyAt)
    }

    // ---------- 版本号：给哨兵对齐用 ----------

    @Test
    fun `每次用户改动 revision 递增`() {
        val a = ModeConfig()
        val b = Modes.startStrict(a, now, 60)
        val c = Modes.emergency(b, now + 1_000L)
        val d = Modes.resume(c)
        val e = Modes.toNormal(d)
        assertTrue(b.revision > a.revision)
        assertTrue(c.revision > b.revision)
        assertTrue(d.revision > c.revision)
        assertTrue(e.revision > d.revision)
    }

    @Test
    fun `没有实际变化时 revision 不动`() {
        val m = ModeConfig()
        assertEquals("普通模式下按紧急暂停不该改版本", m.revision, Modes.emergency(m, now).revision)
        assertEquals("本来就没暂停，resume 不该改版本", m.revision, Modes.resume(m).revision)
        assertEquals("没过期的窗口 expire 不该改版本", m.revision, Modes.expire(m, now).revision)
    }

    // ---------- 状态描述不撒谎 ----------

    @Test
    fun `描述如实反映当前档位`() {
        assertEquals("普通模式", Modes.describe(ModeConfig(), now))
        val strict = Modes.startStrict(ModeConfig(), now, 60)
        assertEquals("严格监管中", Modes.describe(strict, now))
        assertTrue(Modes.describe(strict, now + 120 * 60_000L).contains("已结束"))
        assertTrue(Modes.describe(Modes.emergency(strict, now), now).contains("紧急暂停"))
    }
}
