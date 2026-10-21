package com.zongkong.core.moling

import com.zongkong.core.Config
import com.zongkong.core.DayClock
import com.zongkong.core.DayLog
import com.zongkong.core.Engine
import com.zongkong.core.Reason
import com.zongkong.core.agent.FakeEnv
import com.zongkong.core.agent.ZkTools
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AuthorityTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(h: Int, m: Int = 0, d: Int = 11) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun focus(min: Int, auto: Boolean = true) =
        mapOf("cmd" to "start_focus", "minutes" to "$min", "title" to "写申论", "v" to "1", "reason" to "偏了") + if (auto) mapOf("auto" to "true") else emptyMap()

    private val focusGrant = Grant(GrantKind.FOCUS, maxMinutes = 45, perDay = 2, fromMin = 8 * 60, toMin = 22 * 60)

    // ---------------------------------------------------------------- 授权书本身

    @Test fun givingMoreIsImmediateTakingBackWaits() {
        var (b, msg) = Grants.set(GrantBook(), GrantKind.FOCUS, focusGrant, at(10))
        assertEquals(listOf(focusGrant), b.grants)
        assertTrue(msg.contains("立即"))
        // 放大：立即
        b = Grants.set(b, GrantKind.FOCUS, focusGrant.copy(maxMinutes = 60), at(10)).first
        assertEquals(60, b.grants.single().maxMinutes)
        // 缩小：冷静期
        val (b2, m2) = Grants.set(b, GrantKind.FOCUS, focusGrant.copy(maxMinutes = 20), at(11))
        assertTrue(m2.contains("24 小时"))
        assertEquals(60, Grants.applyDue(b2, at(23)).grants.single().maxMinutes)
        assertEquals(20, Grants.applyDue(b2, at(11, d = 12)).grants.single().maxMinutes)
        // 收回：冷静期，到点整行没了
        val (b3, _) = Grants.set(b, GrantKind.FOCUS, null, at(11))
        assertEquals(1, Grants.applyDue(b3, at(12)).grants.size)
        assertTrue(Grants.applyDue(b3, at(12, d = 12)).grants.isEmpty())
        // 窄化时间段也算缩小
        assertTrue(Grants.set(b, GrantKind.FOCUS, focusGrant.copy(maxMinutes = 60, fromMin = 9 * 60), at(11)).second.contains("24 小时"))
    }

    @Test fun neverGrantableKinds() {
        assertNull(Grants.kindOf(mapOf("cmd" to "tighten", "kind" to "block_app", "value" to "x")))
        assertNull(Grants.kindOf(mapOf("cmd" to "request_loosen", "kind" to "unblock_app", "value" to "x")))
        assertNull(Grants.kindOf(mapOf("cmd" to "note", "text" to "x")))
    }

    @Test fun checkRespectsWindowMinutesAndCount() {
        val b = GrantBook(listOf(focusGrant))
        assertNotNull(Grants.check(b, focus(30), at(10), zone, 0).first)
        assertTrue(Grants.check(b, focus(30), at(23), zone, 0).second.contains("时间段"))
        assertTrue(Grants.check(b, focus(60), at(10), zone, 0).second.contains("45"))
        assertTrue(Grants.check(b, focus(30), at(10), zone, 2).second.contains("2 次"))
        assertTrue(Grants.check(GrantBook(), focus(30), at(10), zone, 0).second.contains("没有"))
        val plan = GrantBook(listOf(Grant(GrantKind.PLAN, fromMin = 0, toMin = 0)))
        val tomorrow = mapOf("cmd" to "add_action", "title" to "x", "start" to at(9, d = 12).toString(), "end" to "0", "v" to "1")
        assertTrue(Grants.check(plan, tomorrow, at(10), zone, 0).second.contains("今天"))
    }

    // ---------------------------------------------------------------- 收：授权内倒计时，授权外等确认

    @Test fun autoWithinGrantCountsDownOutsideWaits() {
        val book = MolingBook(grants = GrantBook(listOf(focusGrant)))
        val (b1, r1) = Moling.receive(book, focus(30), at(10), zone)
        assertTrue(r1.auto)
        val o = b1.orders.single()
        assertEquals(OrderState.SCHEDULED, o.state)
        assertEquals(at(10) + Moling.VETO_MS, o.runAt)
        assertTrue(Moling.pending(b1, at(10)).isEmpty())
        assertTrue(Moling.due(b1, at(10)).isEmpty())
        assertEquals(listOf(o), Moling.due(b1, at(10, 2)))
        // 超过授权：等确认
        val (b2, r2) = Moling.receive(b1, focus(60), at(10, 1), zone)
        assertFalse(r2.auto)
        assertEquals(OrderState.PENDING, b2.orders.last().state)
        // 没要求授权执行：照旧等确认
        val (_, r3) = Moling.receive(book, focus(30, auto = false), at(10), zone)
        assertFalse(r3.auto)
    }

    @Test fun vetoStopsIt() {
        val (b, r) = Moling.receive(MolingBook(grants = GrantBook(listOf(focusGrant))), focus(30), at(10), zone)
        val v = Moling.veto(b, r.id, at(10, 0))
        assertEquals(OrderState.VETOED, v.orders.single().state)
        assertTrue(Moling.due(v, at(10, 5)).isEmpty())
    }

    @Test fun perDayCountIncludesCountdowns() {
        var b = MolingBook(grants = GrantBook(listOf(focusGrant)))
        b = Moling.receive(b, focus(30), at(10), zone).first
        b = Moling.receive(b, focus(25), at(11), zone).first
        val (_, r) = Moling.receive(b, focus(20), at(12), zone)
        assertFalse("每天 2 次用完了", r.auto)
    }

    // ---------------------------------------------------------------- 做：临时开关和工具

    @Test fun strictHoldMakesZongkongStrictUntilItEnds() = runTest {
        val env = FakeEnv()
        val f = mapOf("cmd" to "tighten", "kind" to "strict", "value" to "90", "v" to "1", "reason" to "今晚不刷")
        val (b, r) = Moling.receive(MolingBook(), f, env.nowMs, zone)
        val (after, res) = Moling.execute(b, r.id, ZkTools(env), { env.w }, env.cfg, env.nowMs, zone)
        assertTrue(res.text, res.ok)
        assertEquals(OrderState.DONE, after.orders.single().state)
        val day = DayLog(DayClock.dateKey(env.nowMs, zone))
        val clean = com.zongkong.core.config()
        assertFalse("没有临时严管时不严管", Engine.evaluate(clean, day, env.nowMs + 10 * 60_000L, zone).strict)
        val st = Engine.evaluate(clean, day, env.nowMs + 10 * 60_000L, zone, holds = after.holds)
        assertTrue(st.strict)
        assertTrue(st.reasons.single() is Reason.Hold)
        assertFalse(Engine.evaluate(clean, day, env.nowMs + 91 * 60_000L, zone, holds = after.holds).strict)
    }

    @Test fun cutPlayLowersTodaysAllowanceOnly() = runTest {
        val env = FakeEnv()
        env.cfg = com.zongkong.core.config(quota = 60)
        val f = mapOf("cmd" to "tighten", "kind" to "cut_play", "value" to "30", "v" to "1")
        val (b, r) = Moling.receive(MolingBook(), f, env.nowMs, zone)
        val (after, res) = Moling.execute(b, r.id, ZkTools(env), { env.w }, env.cfg, env.nowMs, zone)
        assertTrue(res.text, res.ok)
        val played = DayLog(DayClock.dateKey(env.nowMs, zone), playSeconds = mapOf("com.ss.android.ugc.aweme" to 35 * 60L))
        val st = Engine.evaluate(env.cfg, played, env.nowMs, zone, holds = after.holds)
        assertTrue("玩了 35 分钟，上限 60 减 30 = 30，严管", st.strict)
        assertEquals(30, st.quotaMin)
        val tomorrow = env.nowMs + 24 * 3600_000L
        val st2 = Engine.evaluate(env.cfg, played.copy(date = DayClock.dateKey(tomorrow, zone)), tomorrow, zone, holds = after.holds)
        assertFalse("明天恢复", st2.strict)
    }

    @Test fun cutPlayNeedsALimit() = runTest {
        val env = FakeEnv()
        val (b, r) = Moling.receive(MolingBook(), mapOf("cmd" to "tighten", "kind" to "cut_play", "value" to "30", "v" to "1"), env.nowMs, zone)
        val noLimit = com.zongkong.core.config().copy(earn = com.zongkong.core.EarnConfig(enabled = false))
        val (after, res) = Moling.execute(b, r.id, ZkTools(env), { env.w }, noLimit, env.nowMs, zone)
        assertFalse(res.ok)
        assertTrue(after.holds.isEmpty())
        assertEquals(OrderState.FAILED, after.orders.single().state)
    }

    @Test fun emergencyStillWorksUnderAHold() {
        val holds = listOf(Holds.strictFor("h", 60, at(10), "x"))
        val day = DayLog(DayClock.dateKey(at(10), zone))
        val clean = com.zongkong.core.config()
        val st = Engine.evaluate(clean, day, at(10, 5), zone, holds = holds)
        val (next, _) = com.zongkong.core.DayOps.emergency(day, clean, st, "家里有急事必须马上用手机联系家人处理事情", at(10, 5))
        assertNotNull("紧急放行必须对约好的严管也有效", next)
    }

    @Test fun commitAndDirectiveGoThroughTheAdvisorsTools() = runTest {
        val env = FakeEnv()
        val tools = ZkTools(env)
        val commit = mapOf("cmd" to "commit", "what" to "写完申论第二题", "due" to (env.nowMs + 3 * 3600_000L).toString(), "check" to "看行动记录", "v" to "1")
        val (b1, r1) = Moling.receive(MolingBook(), commit, env.nowMs, zone)
        val (_, res1) = Moling.execute(b1, r1.id, tools, { env.w }, env.cfg, env.nowMs, zone)
        assertTrue(res1.text, res1.ok)
        assertEquals("写完申论第二题", env.dossier.commitments.single().what)
        val dir = mapOf("cmd" to "directive", "title" to "申论三篇", "text" to "这周写完三篇大作文，每篇 1000 字", "items" to "要做｜周三写完第一篇｜周三 21:00", "v" to "1")
        val (b2, r2) = Moling.receive(MolingBook(), dir, env.nowMs, zone)
        val (_, res2) = Moling.execute(b2, r2.id, tools, { env.w }, env.cfg, env.nowMs, zone)
        assertTrue(res2.text, res2.ok)
    }

    @Test fun executeRunsOnlyOnce() = runTest {
        val env = FakeEnv()
        val (b, r) = Moling.receive(MolingBook(), mapOf("cmd" to "note", "text" to "材料", "v" to "1"), env.nowMs, zone)
        val (after, first) = Moling.execute(b, r.id, ZkTools(env), { env.w }, env.cfg, env.nowMs, zone)
        assertTrue(first.ok)
        val (_, second) = Moling.execute(after, r.id, ZkTools(env), { env.w }, env.cfg, env.nowMs, zone)
        assertFalse(second.ok)
        assertEquals(1, env.w.notes().size)
    }
}
