package com.zongkong.core.plan

import com.zongkong.core.Config
import com.zongkong.core.DayLog
import com.zongkong.core.EarnConfig
import com.zongkong.core.Engine
import com.zongkong.core.Reason
import com.zongkong.core.WatchConfig
import com.zongkong.core.WatchRule
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import com.zongkong.core.work.WorkOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class EarnTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val rnd = Random(4)
    private val cfg = EarnConfig()
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 18, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun action(w: Work, title: String, plan: String): Pair<Work, com.zongkong.core.work.Rec> =
        WorkOps.create(w, Kind.ACTION, mapOf(F.TITLE to title, F.PLAN to TimeParse.parse(plan, at(7), zone)!!.encode()), at(7), rnd)

    @Test
    fun doingThingsEarnsPlayTime() {
        var (w, a) = action(Work(), "资料分析", "今天 9:00-10:00")
        assertEquals(0, Earn.earned(w, at(9), zone, cfg))
        w = WorkOps.start(w, a.key, at(9), 0)
        w = WorkOps.complete(w, a.key, "做完", WorkOps.Met.YES, "", at(10, 5), zone, rnd)
        // 做完一步 +20，做了 65 分钟 = 两个半小时 +20
        assertEquals(40, Earn.earned(w, at(11), zone, cfg))
        val c = Config(dailyQuotaMin = 120)
        assertEquals(60, Engine.allowedMinutes(c, 40))
        assertEquals(120, Engine.allowedMinutes(c, 500))
        // 底数 20 分钟用完：严管；挣了 40 就还能玩
        val played = DayLog("2026-10-18", playSeconds = mapOf("x" to 25 * 60L))
        assertTrue(Engine.evaluate(c.copy(gates = emptyList(), silenceHours = 0), played, at(11), zone, earned = 0).reasons.any { it is Reason.Budget })
        assertFalse(Engine.evaluate(c.copy(gates = emptyList(), silenceHours = 0), played, at(11), zone, earned = 40).strict)
    }

    @Test
    fun anOverduePlanThatNeverStartedIsADebt() {
        var (w, a) = action(Work(), "资料分析", "今天 9:00-10:00")
        assertNull(Earn.debt(w, PlanBook(), at(9, 20), zone, cfg))
        assertEquals(a.key, Earn.debt(w, PlanBook(), at(9, 31), zone, cfg)?.key)
        // 开始做：解开（停下也不算再欠）
        val started = WorkOps.start(w, a.key, at(9, 40), 0)
        assertNull(Earn.debt(started, PlanBook(), at(11), zone, cfg))
        // 改到下午：解开，到下午再看
        val moved = WorkOps.update(w, a.key, mapOf(F.PLAN to TimeParse.parse("今天 15:00-16:00", at(9), zone)!!.encode()), at(9, 40))
        assertNull(Earn.debt(moved, PlanBook(), at(11), zone, cfg))
        // 标卡住：解开
        val stuck = WorkOps.update(w, a.key, mapOf(F.STATUS to com.zongkong.core.work.ActionStatus.STUCK.label), at(9, 40))
        assertNull(Earn.debt(stuck, PlanBook(), at(11), zone, cfg))
        // 定了主线：只看主线
        val (w2, b) = action(w, "申论", "今天 14:00-15:00")
        w = w2
        val book = Plans.setDay(PlanBook(), DayPlan("2026-10-18", mainActionKey = b.key))
        assertNull(Earn.debt(w, book, at(11), zone, cfg))
        assertEquals(b.key, Earn.debt(w, book, at(14, 31), zone, cfg)?.key)
        // 欠着 → 严管
        val st = Engine.evaluate(Config(gates = emptyList(), silenceHours = 0), DayLog("2026-10-18"), at(14, 31), zone, earned = 0, debt = b.key to b.title)
        assertTrue(st.strict)
        assertEquals("「申论」过了点还没开始：开始做，或者改个时间", st.reasons.single().text)
    }

    @Test
    fun entertainmentAppsAskAQuestionOnOpen() {
        val w = WatchConfig()
        val r = w.ruleFor("tv.danmaku.bili", setOf("tv.danmaku.bili"))!!
        assertTrue(r.onOpen)
        assertEquals(10, r.maxMinutes)
        assertNull(w.ruleFor("com.tencent.mm", setOf("tv.danmaku.bili")))
        assertNull(w.copy(openGate = false).ruleFor("tv.danmaku.bili", setOf("tv.danmaku.bili")))
        // 自己加的低屏规则优先（开着低屏模式时）
        val own = w.copy(enabled = true, rules = listOf(WatchRule("tv.danmaku.bili", maxMinutes = 2)))
        assertFalse(own.ruleFor("tv.danmaku.bili", setOf("tv.danmaku.bili"))!!.onOpen)
    }
}
