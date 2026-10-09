package com.zongkong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscTest {
    @Test
    fun parsesClockText() {
        assertEquals(570, DayClock.parse("9:30"))
        assertEquals(570, DayClock.parse("09：30"))
        assertEquals(570, DayClock.parse("930"))
        assertEquals(1320, DayClock.parse("22"))
        assertEquals(0, DayClock.parse("24:00"))
        assertNull(DayClock.parse("25:00"))
        assertNull(DayClock.parse("abc"))
        assertEquals("01:05", DayClock.hhmm(65))
    }

    @Test
    fun notionIdsFromLinks() {
        val hex = "1a2b3c4d5e6f708192a3b4c5d6e7f809"
        val uuid = "1a2b3c4d-5e6f-7081-92a3-b4c5d6e7f809"
        assertEquals(uuid, NotionIds.parse(hex))
        assertEquals(uuid, NotionIds.parse(uuid))
        assertEquals(uuid, NotionIds.parse("https://www.notion.so/myspace/Cafe-$hex?pvs=4"))
        assertEquals(uuid, NotionIds.parse("https://www.notion.so/$hex?v=ffffffffffffffffffffffffffffffff"))
        assertEquals(uuid, NotionIds.parse("https://www.notion.so/ws/abcdef-$hex#00000000000000000000000000000000"))
        assertNull(NotionIds.parse("https://www.notion.so/nothing-here"))
        assertNull(NotionIds.parse(""))
    }

    @Test
    fun effectiveCharsIgnoresTemplateLabels() {
        val template = "主线：\n· 做什么：\n次要："
        val filled = "主线：\n· 做什么：\n次要："
        assertEquals(0, TextCheck.effectiveChars(filled, template))
        val real = "主线：\n· 做什么：行测言语理解 40 题\n次要：背 50 个成语"
        assertEquals(11, TextCheck.effectiveChars(real, template))
    }

    @Test
    fun paddingDetected() {
        assertTrue(TextCheck.looksPadded("啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊"))
        assertFalse(TextCheck.looksPadded("今天做了行测四十题，正确率百分之七十五，言语理解错得最多"))
    }

    @Test
    fun configRoundTripsWithPendingOps() {
        val c = Policy.setRules(Config(), Policy.rulesOf(Config()).copy(dailyQuotaMin = 0), at(12)).config
        val c2 = Policy.removeGate(c, "think_one", at(12)).config.copy(blocked = setOf("tv.danmaku.bili"))
        val text = ZkJson.encodeToString(Config.serializer(), c2)
        val back = ZkJson.decodeFromString(Config.serializer(), text)
        assertEquals(c2, back)
        assertTrue(text.contains("\"type\":\"rules\""))
    }

    @Test
    fun oldConfigWithMissingFieldsStillLoads() {
        val back = ZkJson.decodeFromString(Config.serializer(), """{"blocked":["a"],"unknownField":1}""")
        assertEquals(setOf("a"), back.blocked)
        assertEquals(Defaults.gates().size, back.gates.size)
    }

    @Test
    fun methodOfDayRotatesAndFillsTemplate() {
        val d = java.time.LocalDate.of(2026, 10, 9)
        val names = (0L..6L).map { Defaults.methodOf(d.plusDays(it)).name }.toSet()
        assertEquals(7, names.size)
        val think = Defaults.gates().first { it.id == "think_one" }
        assertTrue(Defaults.fillTemplate(think.template, d).contains(Defaults.methodOf(d).name))
    }

    @Test
    fun defaultsAreSane() {
        val gates = Defaults.gates()
        assertEquals(gates.size, gates.map { it.id }.toSet().size)
        gates.forEach { assertTrue(it.title, DayClock.rank(it.deadline) > DayClock.rank(it.openAt)) }
        assertTrue(Defaults.suggestedBlocked.none { it in Defaults.neverBlock })
    }

    @Test
    fun factsMentionPlayAndMorningPlan() {
        val c = Config()
        var day = DayLog("2026-10-09")
        val st = Engine.evaluate(c, day, at(8), ZONE)
        day = DayOps.submit(day, st.gates.first { it.gate.id == "plan_morning" }, "主线：行测 40 题", passVerdict(), at(8))
        day = DayOps.addPlay(day, "tv.danmaku.bili", 30 * 60)
        val f = Facts.build(Engine.evaluate(c, day, at(22), ZONE), day, ZONE)
        assertTrue(f.contains("行测 40 题"))
        assertTrue(f.contains("30 分钟"))
    }
}
