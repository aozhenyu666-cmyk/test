package com.zongkong.core.plan

import com.zongkong.core.Config
import com.zongkong.core.DayLog
import com.zongkong.core.Defaults
import com.zongkong.core.VerifyMode
import com.zongkong.core.boss.Dossier
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import com.zongkong.core.work.WorkOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class DayReportTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val rnd = Random(3)
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun action(w: Work, title: String, plan: String?): Pair<Work, com.zongkong.core.work.Rec> =
        WorkOps.create(w, Kind.ACTION, buildMap {
            put(F.TITLE, title)
            plan?.let { put(F.PLAN, TimeParse.parse(it, at(16, 8), zone)!!.encode()) }
        }, at(16, 8), rnd)

    @Test
    fun listsWhatWasPlannedAndWhatHappened() {
        var w = Work()
        val (w1, a) = action(w, "国考：查报名时间", "今天 9:00-9:30"); w = w1
        val (w2, b) = action(w, "资料分析 20 题", "今天 14:00-15:00"); w = w2
        val (w3, c) = action(w, "整理桌面", null); w = w3
        val (w4, _) = action(w, "明天的申论", "明天 8:00-9:00"); w = w4
        w = WorkOps.start(w, a.key, at(16, 9), 0)
        w = WorkOps.complete(w, a.key, "11 月 1 日报名", WorkOps.Met.YES, "", at(16, 9, 25), zone, rnd)
        w = WorkOps.start(w, c.key, at(16, 16), 0)
        w = WorkOps.complete(w, c.key, "", WorkOps.Met.YES, "", at(16, 16, 20), zone, rnd)
        val book = Plans.setDay(PlanBook(), DayPlan("2026-10-16", mainActionKey = b.key))
        val log = DayLog("2026-10-16", playSeconds = mapOf("tv.danmaku.bili" to 50 * 60L), bounces = 2)

        val r = DayReport.build(w, book, log, emptyList(), Dossier(), null, at(16, 22), zone)
        // 主线在最前，没做
        assertEquals(b.key, r.planned.first().key)
        assertTrue(r.planned.first().main)
        assertEquals(DayReport.Mark.TODO, r.planned.first().mark)
        assertEquals(DayReport.Mark.DONE, r.planned.first { it.key == a.key }.mark)
        assertEquals(25, r.planned.first { it.key == a.key }.minutes)
        assertEquals(1, r.doneCount)
        assertEquals(listOf(b.key), r.notDone.map { it.key })
        // 没排但做了的
        assertEquals(listOf(c.key), r.extra.map { it.key })
        assertEquals(50, r.playMinutes)
        assertEquals("08:00 明天的申论", r.tomorrow)

        val text = DayReport.text(r)
        assertTrue(text, text.contains("做完 1/2"))
        assertTrue(text, text.contains("○ 资料分析 20 题（主线）：没做"))
        assertTrue(text, text.contains("✓ 国考：查报名时间：做完，25 分钟，11 月 1 日报名"))
        assertTrue(text, text.contains("被弹回 2 次"))
    }

    @Test
    fun oldAiReviewGateIsMigratedToTheList() {
        val old = Defaults.gates().map {
            if (it.id == "hq_review") it.copy(verify = VerifyMode.AI, minChars = 80, rubric = "逐条对照了今天排的行动，说清楚每件事完成没有") else it
        }
        val c = Defaults.migrate(Config(gates = old))
        val g = c.gates.first { it.id == "hq_review" }
        assertEquals(VerifyMode.TEXT, g.verify)
        assertEquals("", g.rubric)
        // 自己改过的不动
        val mine = Config(gates = old.map { if (it.id == "hq_review") it.copy(rubric = "我自己的标准") else it })
        assertEquals(mine, Defaults.migrate(mine))
    }
}
