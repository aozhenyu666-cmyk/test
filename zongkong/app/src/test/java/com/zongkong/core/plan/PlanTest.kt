package com.zongkong.core.plan

import com.zongkong.core.Config
import com.zongkong.core.DayClock
import com.zongkong.core.DayLog
import com.zongkong.core.Engine
import com.zongkong.core.FocusLock
import com.zongkong.core.Link
import com.zongkong.core.agent.FakeEnv
import com.zongkong.core.agent.ZkTools
import com.zongkong.core.work.Event
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.Kind
import com.zongkong.core.work.Work
import com.zongkong.core.work.WorkOps
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class PlanTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    // 2026-10-14 是周三
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun sample(now: Long): Pair<Work, WeekPlan> {
        val rnd = Random(1)
        val (w1, t) = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "行测"), now, rnd)
        val (w2, a) = WorkOps.create(w1, Kind.ACTION, mapOf(F.TITLE to "资料分析 20 题", F.THREAD to t.key), now, rnd)
        val (w3, other) = WorkOps.create(w2, Kind.THREAD, mapOf(F.TITLE to "整理房间"), now, rnd)
        val (w4, _) = WorkOps.create(w3, Kind.ACTION, mapOf(F.TITLE to "扔旧衣服", F.THREAD to other.key), now, rnd)
        val g = Goal("g1", "行测提到 75", threadKey = t.key)
        val plan = WeekPlan(
            "w1", "2026-10-12", PlanStatus.DRAFT, listOf(g),
            listOf(Outcome("o1", "g1", "资料分析专项 3 套", "正确率记下来", t.key, a.key)),
            dailyMinutes = 90, restDays = setOf(7),
        )
        return w4 to plan
    }

    @Test
    fun weekStartIsMondayOfLogicalDay() {
        assertEquals(LocalDate.of(2026, 10, 12), Plans.weekStart(at(14, 10), zone))
        // 周一凌晨 2 点还算上周日
        assertEquals(LocalDate.of(2026, 10, 5), Plans.weekStart(at(12, 2), zone))
    }

    @Test
    fun draftNeedsApproval() {
        val now = at(14, 9)
        val (_, plan) = sample(now)
        var b = Plans.putDraft(PlanBook(), plan)
        assertNull(Plans.active(b, now, zone))
        assertNotNull(Plans.draft(b, now, zone))
        b = Plans.approve(b, "w1", now)
        assertEquals(PlanStatus.ACTIVE, Plans.active(b, now, zone)?.status)
        assertNull(Plans.draft(b, now, zone))
        // 再立一份：旧的结项
        b = Plans.approve(Plans.putDraft(b, plan.copy(id = "w2")), "w2", now + 1)
        assertEquals("w2", Plans.active(b, now, zone)?.id)
        assertEquals(PlanStatus.CLOSED, b.weeks.first { it.id == "w1" }.status)
    }

    @Test
    fun mainBeatsUnscheduledButNotNow() {
        val now = at(14, 9)
        val (w, plan) = sample(now)
        val main = plan.outcomes[0].actionKey
        assertEquals(Focus.Why.MAIN, Focus.current(w, now, zone, main).why)
        assertEquals(main, Focus.current(w, now, zone, main).action?.key)
        // 有正在计划时间里的另一步：先做那步
        val timed = WorkOps.create(w, Kind.ACTION, mapOf(F.TITLE to "开会", F.PLAN to "2026-10-14T08:30:00+08:00|2026-10-14T10:00:00+08:00"), now, Random(9)).first
        assertEquals(Focus.Why.NOW, Focus.current(timed, now, zone, main).why)
    }

    @Test
    fun inPlanByThread() {
        val now = at(14, 9)
        val (w, plan) = sample(now)
        assertTrue(Plans.inPlan(plan, w.actions().first { it.title == "资料分析 20 题" }))
        assertFalse(Plans.inPlan(plan, w.actions().first { it.title == "扔旧衣服" }))
    }

    @Test
    fun minutesFromStartToStop() {
        val (w0, plan) = sample(at(14, 8))
        val a = plan.outcomes[0].actionKey
        val off = w0.actions().first { it.title == "扔旧衣服" }.key
        var w = w0
        w = WorkOps.event(w, Event(at(14, 9), "start", a))
        w = WorkOps.event(w, Event(at(14, 9, 40), "pause", a))
        w = WorkOps.event(w, Event(at(14, 10), "start", off))
        w = WorkOps.event(w, Event(at(14, 10, 20), "start", a)) // 换到另一步：上一段结束
        w = WorkOps.event(w, Event(at(14, 10, 50), "done:YES", a))
        val m = Plans.minutesByAction(w, at(14, 4), at(14, 23))
        assertEquals(70, m[a])
        assertEquals(20, m[off])
        val pr = Plans.progress(plan.copy(status = PlanStatus.ACTIVE), w, at(14, 12), zone)
        assertEquals(70, pr.planMinutes)
        assertEquals(20, pr.offMinutes)
        assertEquals(70, pr.todayPlanMinutes)
        assertEquals(3, pr.day)
    }

    @Test
    fun yishouTightenedWhenBehindPlan() {
        val now = at(14, 15) // 周三下午 3 点，计划内一分钟没做
        val (w, plan) = sample(now)
        var b = Plans.approve(Plans.putDraft(PlanBook(), plan), "w1", now)
        b = Plans.setDay(b, DayPlan("2026-10-14", "o1", plan.outcomes[0].actionKey))
        val cfg = Config(gates = emptyList(), silenceHours = 0)
        val st = Engine.evaluate(cfg.copy(gates = emptyList()), DayLog(DayClock.dateKey(now, zone)), now, zone)
        val inf = Link.influence(st, cfg, w, b, zone)
        assertEquals(cfg.yishouPassCap, inf.passCap)
        assertTrue(inf.noOffline)
        assertTrue(inf.note, inf.note.contains("资料分析 20 题"))
        // 早上还没到点：不收紧
        val morning = at(14, 9)
        val st2 = Engine.evaluate(cfg.copy(gates = emptyList()), DayLog(DayClock.dateKey(morning, zone)), morning, zone)
        assertEquals(0, Link.influence(st2, cfg, w, b, zone).passCap)
        // 关掉影响：不收紧
        assertEquals(0, Link.influence(st, cfg.copy(yishouInfluence = false), w, b, zone).passCap)
        // 休息日：不收紧
        val sunday = at(18, 15)
        val st3 = Engine.evaluate(cfg.copy(gates = emptyList()), DayLog(DayClock.dateKey(sunday, zone)), sunday, zone)
        assertEquals(0, Link.influence(st3, cfg, w, b, zone).passCap)
    }

    @Test
    fun focusLockHoldsYishou() {
        val now = at(14, 15)
        val (w, _) = sample(now)
        val cfg = Config(gates = emptyList(), silenceHours = 0)
        val st = Engine.evaluate(cfg.copy(gates = emptyList()), DayLog(DayClock.dateKey(now, zone)), now, zone, FocusLock("资料分析", now + 30 * 60_000))
        val inf = Link.influence(st, cfg, w, PlanBook(), zone)
        assertTrue(inf.hold)
        val map = Link.zongkongStatus(st, cfg, w, zone, PlanBook())
        assertEquals(true, map["hold"])
    }

    @Test
    fun loosenYishouInfluenceWaits() {
        val now = at(14, 9)
        val cfg = Config(gates = emptyList(), silenceHours = 0)
        val off = com.zongkong.core.Policy.setYishou(cfg, false, cfg.yishouPassCap, now)
        assertTrue(off.config.yishouInfluence)
        assertEquals(1, off.config.pending.size)
        val tighter = com.zongkong.core.Policy.setYishou(cfg, true, 3, now)
        assertEquals(3, tighter.config.yishouPassCap)
    }

    @Test
    fun agentDraftsButCannotApprove() = runTest {
        val env = FakeEnv(at(14, 9))
        val (w, _) = sample(env.nowMs)
        env.w = w
        val tools = ZkTools(env)
        val r = tools.run(
            "draft_week_plan",
            """{"goals":"行测提到 75｜11 月底笔试\n健身｜精力","outcomes":"行测提到 75｜资料分析专项 3 套｜正确率记下来\n健身｜跑步 3 次｜每次 30 分钟\n不存在的方向｜x｜y","daily_minutes":90,"rest_days":"周日","if_then":"如果晚上刷手机｜就 20:00 开专注锁"}""",
        )
        assertTrue(r.text, r.ok)
        assertEquals("plan", r.route)
        val d = Plans.draft(env.book, env.nowMs, zone)!!
        assertEquals("参谋", d.by)
        assertEquals(2, d.goals.size)
        assertEquals(2, d.outcomes.size)
        assertEquals(setOf(7), d.restDays)
        assertEquals(1, d.ifThens.size)
        assertEquals(w.threads().first { it.title == "行测" }.key, d.goals[0].threadKey.ifBlank { w.threads().first { it.title == "行测" }.key })
        assertNull(Plans.active(env.book, env.nowMs, zone))
        // 没立项不能改
        assertFalse(tools.run("revise_plan", """{"add":"健身｜拉伸｜10 分钟","why":"x"}""").ok)
    }

    @Test
    fun agentRevisesWithReasonAndSetsMain() = runTest {
        val env = FakeEnv(at(14, 9))
        val (w, plan) = sample(env.nowMs)
        env.w = w
        env.book = Plans.approve(Plans.putDraft(PlanBook(), plan), "w1", env.nowMs)
        val tools = ZkTools(env)
        assertFalse(tools.run("revise_plan", """{"add":"行测提到 75｜错题整理｜一本"}""").ok)
        assertTrue(tools.run("revise_plan", """{"add":"行测提到 75｜错题整理｜一本","why":"做题发现错题多"}""").ok)
        val p = Plans.active(env.book, env.nowMs, zone)!!
        assertEquals(2, p.outcomes.size)
        assertEquals("做题发现错题多", p.revisions.last().why)
        assertTrue(tools.run("set_day_main", """{"action":"资料分析 20 题"}""").ok)
        assertEquals(plan.outcomes[0].actionKey, Plans.day(env.book, "2026-10-14")?.mainActionKey)
        // 做完那一步：交出物自动打勾
        tools.run("complete_action", """{"action":"资料分析 20 题","result":"做完 3 套","met":"达到"}""")
        assertTrue(Plans.active(env.book, env.nowMs, zone)!!.outcomes.first { it.id == "o1" }.done)
        assertTrue(tools.run("get_plan", "{}").text.contains("今天的主线"))
    }

    @Test
    fun leftoversCarryOver() {
        val (_, plan) = sample(at(14, 9))
        val prev = plan.copy(outcomes = plan.outcomes + Outcome("o2", "g1", "done one", done = true))
        assertEquals(listOf("o1"), Plans.leftovers(prev).map { it.id })
    }
}
