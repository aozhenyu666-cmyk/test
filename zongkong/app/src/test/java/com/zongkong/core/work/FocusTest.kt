package com.zongkong.core.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class FocusTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(h: Int, m: Int = 0, d: Int = 9) = LocalDate.of(2026, 10, d).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val rnd = Random(5)

    private fun action(w: Work, title: String, plan: String?, thread: String = "", criteria: String = "依据"): Pair<Work, Rec> =
        WorkOps.create(w, Kind.ACTION, buildMap {
            put(F.TITLE, title); put(F.CRITERIA, criteria); put(F.THREAD, thread)
            plan?.let { put(F.PLAN, TimeParse.parse(it, at(8), zone)!!.encode()) }
        }, at(8), rnd)

    @Test
    fun picksTheRightCurrentStep() {
        var w = Work()
        val (w1, morning) = action(w, "早上的", "今天 9:00-10:00"); w = w1
        val (w2, evening) = action(w, "晚上的", "今天 20:00-21:00"); w = w2
        val (w3, loose) = action(w, "没排时间的", null); w = w3

        assertEquals(Focus.Why.NOW to morning.key, Focus.current(w, at(9, 30), zone).let { it.why to it.action!!.key })
        assertEquals(Focus.Why.OVERDUE to morning.key, Focus.current(w, at(11), zone).let { it.why to it.action!!.key })
        w = WorkOps.complete(w, morning.key, "做完", WorkOps.Met.YES, "", at(11), zone, rnd)
        assertEquals(Focus.Why.LATER_TODAY to evening.key, Focus.current(w, at(12), zone).let { it.why to it.action!!.key })
        w = WorkOps.start(w, evening.key, at(20), 0)
        assertEquals(Focus.Why.RUNNING, Focus.current(w, at(20, 5), zone).why)
        w = WorkOps.pause(w, evening.key, "写了一半", "数据没找到", "先查数据", at(20, 30), zone, rnd)
        assertEquals(Focus.Why.CONTINUE to evening.key, Focus.current(w, at(22), zone).let { it.why to it.action!!.key })
        w = WorkOps.complete(w, evening.key, "写完", WorkOps.Met.YES, "", at(22), zone, rnd)
        assertEquals(Focus.Why.UNSCHEDULED to loose.key, Focus.current(w, at(22, 30), zone).let { it.why to it.action!!.key })
        w = WorkOps.cancel(w, loose.key, "不需要了", at(23))
        assertEquals(Focus.Why.NONE, Focus.current(w, at(23), zone).why)
    }

    @Test
    fun breakpointFlowsToThread() {
        var (w, t) = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "简历"), at(8), rnd)
        val (w2, a) = action(w, "改第二段", "今天 20:00", t.key); w = w2
        w = WorkOps.start(w, a.key, at(20), 25)
        assertEquals(at(20) + 25 * 60_000L, w.session!!.lockUntil)
        w = WorkOps.pause(w, a.key, "改完前两句", "不知道怎么量化", "找项目数据", at(20, 40), zone, rnd)
        val th = w.rec(t.key)!!
        assertTrue(th[F.BREAK].contains("改完前两句"))
        assertTrue(th[F.BREAK].contains("不知道怎么量化"))
        assertEquals("找项目数据", th[F.NEXT])
        assertNull(w.session)
        assertEquals(1, w.notesOf(t.key).count { it.noteType == NoteType.BREAKPOINT })
    }

    @Test
    fun completionStatesFollowCriteria() {
        var (w, t) = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "T"), at(8), rnd)
        val (w2, a) = action(w, "A", "今天 9:00", t.key); w = w2
        val (w3, b) = action(w, "B", "今天 10:00", t.key); w = w3
        w = WorkOps.complete(w, a.key, "做了一半", WorkOps.Met.PARTIAL, "", at(10), zone, rnd)
        assertEquals(ActionStatus.CONFIRM, w.rec(a.key)!!.actionStatus)
        assertEquals(ThreadStatus.ACTIVE, w.rec(t.key)!!.threadStatus)
        w = WorkOps.complete(w, b.key, "没做成", WorkOps.Met.NO, "", at(11), zone, rnd)
        assertEquals(ActionStatus.STUCK, w.rec(b.key)!!.actionStatus)
        assertEquals(2, Focus.needsAttention(w).size)
    }

    @Test
    fun remindersAndFollowUps() {
        var (w, a) = action(Work(), "A", "今天 20:00")
        assertTrue(Focus.dueReminders(w, at(19, 59), zone).isEmpty())
        assertEquals(listOf(false), Focus.dueReminders(w, at(20, 1), zone).map { it.follow })
        assertEquals(listOf(false, true), Focus.dueReminders(w, at(20, 16), zone).map { it.follow })
        w = WorkOps.start(w, a.key, at(20, 5), 0)
        assertTrue(Focus.dueReminders(w, at(20, 16), zone).isEmpty())
        assertEquals(2, Focus.alarmTimes(action(Work(), "B", "明天 9:00").first, at(12), zone).size)
    }

    @Test
    fun evidenceCounts() {
        var (w, t) = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "T"), at(8), rnd)
        w = action(w, "排了时间", "今天 15:00", t.key).first
        w = action(w, "没写依据", "今天 16:00", t.key, criteria = "").first
        w = action(w, "明天的", "明天 9:00", t.key).first
        assertEquals(1, Focus.evidence(w, Focus.Evidence.PLANNED_TODAY, at(12), zone).first)
        w = WorkOps.create(w, Kind.NOTE, mapOf(F.TITLE to "材料", F.TYPE to "材料", F.THREAD to t.key, F.DATE to "2026-10-09"), at(12), rnd).first
        w = WorkOps.create(w, Kind.NOTE, mapOf(F.TITLE to "没关联", F.TYPE to "念头", F.DATE to "2026-10-09"), at(12), rnd).first
        assertEquals(1, Focus.evidence(w, Focus.Evidence.LINKED_NOTES, at(12), zone).first)
        assertEquals(1, Focus.evidence(w, Focus.Evidence.LINKED_NOTES, at(1, 0, 10), zone).first)
    }

    @Test
    fun captureGuessesAndSuggests() {
        assertEquals(NoteType.QUESTION, Capture.guess("为什么我总是晚上才开始学").type)
        assertEquals(NoteType.QUESTION, Capture.guess("实习空窗要写吗").type)
        val m = Capture.guess("看看这个 https://mp.weixin.qq.com/s/abc 讲行测的")
        assertEquals(NoteType.MATERIAL, m.type)
        assertEquals("https://mp.weixin.qq.com/s/abc", m.url)
        assertEquals(NoteType.IDEA, Capture.guess("可以把错题按题型分").type)
        assertEquals("国考行测 | 某站", Capture.htmlTitle("<html><head><title>别的</title><meta property=\"og:title\" content=\"国考行测 | 某站\"></head>"))
        assertEquals("A & B", Capture.htmlTitle("<title>A &amp; B</title>"))

        var w = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "国考行测提分", F.NEXT to "资料分析专项"), at(8), rnd).first
        w = WorkOps.create(w, Kind.THREAD, mapOf(F.TITLE to "改简历"), at(8), rnd).first
        assertEquals("国考行测提分", Capture.suggest(w, "行测资料分析的增长率公式").first().title)
        assertTrue(Capture.suggest(w, "今天天气不错").isEmpty())
    }

    @Test
    fun insightFromEvents() {
        var (w, a) = action(Work(), "A", "今天 20:00")
        w = WorkOps.event(w, Event(at(20), "remind", a.key))
        w = WorkOps.start(w, a.key, at(20, 10), 0)
        w = WorkOps.event(w, Event(at(9), "remind", "other"))
        w = WorkOps.stuck(w, a.key, StuckReason.DISTRACTED, "", at(21), zone, rnd)
        val i = Focus.insight(w, zone)
        assertEquals(2, i.reminders)
        assertEquals(1, i.startedAfterReminder)
        assertEquals(1 to 1, i.byPeriod["晚上"])
        assertEquals(0 to 1, i.byPeriod["上午"])
        assertEquals(mapOf("去娱乐了" to 1), i.stuck)
    }
}
