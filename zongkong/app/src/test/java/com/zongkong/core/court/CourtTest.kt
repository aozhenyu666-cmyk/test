package com.zongkong.core.court

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourtTest {
    private val hhmm: (Long) -> String = { it.toString() }

    private fun drill(id: String, date: String, condense: String = "资料分析 增长率") = Drill(
        id, 0, date, "资料分析第 $id 题",
        mapOf(
            DrillStep.CONDENSE to condense, DrillStep.FORESEE to "考基期", DrillStep.ASSOCIATE to "增长量公式",
            DrillStep.QUESTION to "B 是不是算反了", DrillStep.RECALL to "没看清单位",
        ),
        "对",
    )

    private fun withExam(date: String = "2026-11-30") = CourtBook(since = 1).let {
        Court.setBaseline(it, Baseline("省考", date, "每天 5 道行测五步", listOf("国企文员", "教培助教"), "考完第 2 天"), 1)
    }

    @Test
    fun conciseIsOneShortLine() {
        assertTrue(Court.concise("行测不碰是因为怕考不上"))
        assertFalse(Court.concise(""))
        assertFalse(Court.concise("   "))
        assertFalse(Court.concise("第一行\n第二行"))
        assertFalse(Court.concise("一".repeat(31)))
        assertTrue(Court.concise("一".repeat(30)))
    }

    @Test
    fun drillNeedsAllFiveSteps() {
        val b = CourtBook()
        assertNotNull(Court.addDrill(b, drill("1", "2026-11-09")))
        val missing = drill("2", "2026-11-09").let { it.copy(steps = it.steps - DrillStep.RECALL) }
        assertNull(Court.addDrill(b, missing))
        // 精简超过 30 字不算
        assertNull(Court.addDrill(b, drill("3", "2026-11-09", condense = "字".repeat(31))))
    }

    @Test
    fun fuseTripsAfterTwoZeroDaysAndClearsWithFiveDrills() {
        var b = withExam()
        // 开始用公审的第一天、第二天不熔断
        assertNull(Court.fuse(b, "2026-11-09", "2026-11-09"))
        assertNull(Court.fuse(b, "2026-11-10", "2026-11-09"))
        // 前天和昨天都是 0：熔断
        val f = Court.fuse(b, "2026-11-11", "2026-11-09")
        assertNotNull(f)
        assertEquals(2, f!!.zeroDays)
        assertEquals(5, f.needToday)
        // 今天做了 4 道还开着，第 5 道解开
        repeat(4) { b = Court.addDrill(b, drill("t$it", "2026-11-11"))!! }
        assertEquals(1, Court.fuse(b, "2026-11-11", "2026-11-09")!!.needToday)
        b = Court.addDrill(b, drill("t5", "2026-11-11"))!!
        assertNull(Court.fuse(b, "2026-11-11", "2026-11-09"))
        // 昨天有做：不熔断
        assertNull(Court.fuse(b, "2026-11-12", "2026-11-09"))
    }

    @Test
    fun fuseNeedsExamDateAndStopsAfterExam() {
        assertNull(Court.fuse(CourtBook(since = 1), "2026-11-20", "2026-11-01"))
        val b = withExam("2026-11-30")
        assertNotNull(Court.fuse(b, "2026-11-20", "2026-11-01"))
        assertNull(Court.fuse(b, "2026-12-01", "2026-11-01"))
        assertEquals(10, Court.daysLeft(b, "2026-11-20"))
    }

    @Test
    fun habitStreakCountsFullDays() {
        var b = CourtBook()
        listOf("2026-11-07", "2026-11-08").forEach { d -> b.habits.forEach { h -> b = Court.confirmHabit(b, d, h.id) } }
        // 今天还没确认完：从昨天往回数
        b = Court.confirmHabit(b, "2026-11-09", b.habits.first().id)
        assertFalse(Court.habitsDone(b, "2026-11-09"))
        assertEquals(2, Court.habitStreak(b, "2026-11-09"))
        b.habits.forEach { h -> b = Court.confirmHabit(b, "2026-11-09", h.id) }
        assertEquals(3, Court.habitStreak(b, "2026-11-09"))
    }

    @Test
    fun slipsWaitForARule() {
        var b = Court.sort(CourtBook(), "n1", Slot.SLIP, 1, "出门忘带身份证")
        b = Court.sort(b, "n2", Slot.ESCAPE, 2, "想改长流代码")
        assertEquals(listOf("n1"), Court.pendingSlips(b).map { it.noteKey })
        assertNull(Court.addRule(b, "r1", "出门前", "", "", "n1", 3))
        b = Court.addRule(b, "r1", "出门前手摸门把手", "摸口袋：身份证、钥匙、手机", "想着别的事", "n1", 3)!!
        assertTrue(Court.pendingSlips(b).isEmpty())
        assertEquals(setOf("n1", "n2"), Court.sortedKeys(b))
    }

    @Test
    fun newOrderClosesTheUncheckedOne() {
        var b = Court.issue(CourtBook(), "o1", "做 5 道言语理解", 100, 10)
        assertEquals("o1", Court.openOrder(b)?.id)
        b = Court.issue(b, "o2", "投 2 份简历", 200, 20)
        assertEquals("o2", Court.openOrder(b)?.id)
        assertFalse(b.orders.first().done)
        b = Court.check(b, "o2", true, "", 30)
        assertNull(Court.openOrder(b))
        assertTrue(b.orders.last().done)
    }

    @Test
    fun briefAndMarkdownCarryTheBaseline() {
        var b = withExam()
        b = Court.issue(b, "o1", "做 5 道言语理解", 100, 10)
        val brief = Court.brief(b, "2026-11-20", "2026-11-01", hhmm)
        assertTrue(brief.contains("前线死令：每天 5 道行测五步"))
        assertTrue(brief.contains("还剩 10 天"))
        assertTrue(brief.contains("熔断中"))
        assertTrue(brief.contains("未查账"))
        val md = Court.markdown(b, "2026-11-20", "2026-11-01", hhmm)
        assertTrue(md.startsWith("---\n"))
        assertTrue(md.contains("| 退路底牌 | 国企文员；教培助教 |"))
    }

    @Test
    fun bookSurvivesJson() {
        var b = withExam()
        b = Court.addDrill(b, drill("1", "2026-11-09"))!!
        b = Court.judge(b, "i1", "想重写长流", Verdict.KILL, 5)
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val back = json.decodeFromString(CourtBook.serializer(), json.encodeToString(CourtBook.serializer(), b))
        assertEquals(b, back)
        // 旧文件没有这些字段也能读
        assertEquals(Court.DEFAULT_HABITS, json.decodeFromString(CourtBook.serializer(), "{}").habits)
    }
}
