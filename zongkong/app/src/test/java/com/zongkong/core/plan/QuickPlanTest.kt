package com.zongkong.core.plan

import com.zongkong.core.work.Handoff
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class QuickPlanTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(h: Int, m: Int = 0, day: Int = 21) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun span(p: com.zongkong.core.work.Plan?) = p?.let { TimeParse.label(it, at(8), zone) }
    private fun hm(t: Long?) = t?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).let { z -> "%02d:%02d".format(z.hour, z.minute) } }

    @Test
    fun oneSentenceBecomesATimeSlot() {
        QuickPlan.parse("下午2点到4点刷题", at(9), zone).let {
            assertEquals("刷题", it.title)
            assertEquals("14:00", hm(TimeParse.startMillis(it.plan!!, zone)))
            assertEquals("16:00", hm(TimeParse.endMillis(it.plan!!, zone)))
        }
        QuickPlan.parse("14:00-15:30 申论大作文", at(9), zone).let {
            assertEquals("申论大作文", it.title)
            assertEquals("15:30", hm(TimeParse.endMillis(it.plan!!, zone)))
        }
        QuickPlan.parse("明天上午九点改简历一小时", at(9), zone).let {
            assertEquals("改简历", it.title)
            assertEquals("2026-10-22", it.plan!!.start.take(10))
            assertEquals("10:00", hm(TimeParse.endMillis(it.plan!!, zone)))
        }
        QuickPlan.parse("今晚八点半复盘", at(9), zone).let {
            assertEquals("复盘", it.title)
            assertEquals("20:30", hm(TimeParse.startMillis(it.plan!!, zone)))
            // 只说开始：默认一小时
            assertEquals("21:30", hm(TimeParse.endMillis(it.plan!!, zone)))
        }
        QuickPlan.parse("现在开始背单词半小时", at(9, 2), zone).let {
            assertEquals("背单词", it.title)
            assertEquals("09:05", hm(TimeParse.startMillis(it.plan!!, zone)))
            assertEquals("09:35", hm(TimeParse.endMillis(it.plan!!, zone)))
        }
        // 没说时间：进待安排
        QuickPlan.parse("给导师回邮件", at(9), zone).let {
            assertEquals("给导师回邮件", it.title)
            assertNull(it.plan)
        }
        // 做题的“做”不被吃掉
        assertEquals("做题", QuickPlan.parse("下午3点做题", at(9), zone).title)
        assertEquals("吃晚饭", QuickPlan.parse("6点吃晚饭", at(9), zone).title)
    }

    @Test
    fun postponingKeepsTheLengthAndStaysOnTheClock() {
        val p = QuickPlan.parse("10:00-11:30 资料分析", at(9), zone).plan
        val later = QuickPlan.moveTo(p, QuickPlan.Slot.LATER, at(10, 7), zone)
        assertEquals("11:10", hm(TimeParse.startMillis(later, zone)))
        assertEquals("12:40", hm(TimeParse.endMillis(later, zone)))
        assertEquals("14:00", hm(TimeParse.startMillis(QuickPlan.moveTo(p, QuickPlan.Slot.AFTERNOON, at(10, 7), zone), zone)))
        val tomorrow = QuickPlan.moveTo(p, QuickPlan.Slot.TOMORROW, at(10, 7), zone)
        assertEquals("2026-10-22", tomorrow.start.take(10))
        assertEquals("10:00", hm(TimeParse.startMillis(tomorrow, zone)))
        // 下午已经过了：挪到一小时后
        assertEquals("16:30", hm(TimeParse.startMillis(QuickPlan.moveTo(p, QuickPlan.Slot.AFTERNOON, at(15, 30), zone), zone)))
        // 没时间的排进上午：9:00 一小时
        val m = QuickPlan.moveTo(null, QuickPlan.Slot.MORNING, at(7), zone)
        assertEquals("09:00", hm(TimeParse.startMillis(m, zone)))
        assertEquals("10:00", hm(TimeParse.endMillis(m, zone)))
        assertTrue(span(m)!!.isNotBlank())
    }

    @Test
    fun dailyThreadAndGptPrompt() {
        val rnd = Random(2)
        var (w, d) = QuickPlan.daily(Work(), at(8), rnd)
        assertEquals(QuickPlan.DAILY, d.title)
        val (w2, d2) = QuickPlan.daily(w, at(8), rnd)
        assertEquals(d.key, d2.key)
        assertEquals(1, w2.threads().size)
        val prompt = QuickPlan.gptPrompt(w2, at(8), zone)
        assertTrue(prompt, prompt.contains("【总控交接】") && prompt.contains("今天 14:00-15:00"))
        // GPT 一天的回答：一件事一个块
        val reply = "好的。\n【总控交接】\n事项：日常安排\n行动：\n- 刷题 | 今天 14:00-15:00 | 完成依据：20 道\n【交接结束】\n【总控交接】\n事项：国考\n行动：\n- 查报名时间 | 今天 16:00-16:30\n【交接结束】"
        assertEquals(2, Handoff.splitBlocks(reply).size)
        assertEquals("国考", Handoff.parse(Handoff.splitBlocks(reply)[1])!!.thread)
    }
}
