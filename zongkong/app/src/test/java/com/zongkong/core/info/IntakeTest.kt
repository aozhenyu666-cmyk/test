package com.zongkong.core.info

import com.zongkong.core.plan.Calibrate
import com.zongkong.core.work.Event
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.Work
import com.zongkong.core.work.WorkOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class IntakeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun funnelStatesFreshnessAndSources() {
        var w = Work()
        fun note(title: String, type: String, url: String, t: Long) { w = WorkOps.create(w, Kind.NOTE, mapOf(F.TITLE to title, F.TYPE to type, F.URL to url), t, Random(title.hashCode())).first }
        note("念头一", "念头", "", at(5, 10))
        note("B站视频", "材料", "https://www.bilibili.com/video/1", at(11, 9))
        note("B站视频2", "材料", "https://b23.tv/x", at(12, 9))
        note("公众号文章", "材料", "https://mp.weixin.qq.com/s/1", at(12, 10))
        var book = IntakeBook(dailyCap = 2, backlogCap = 2)
        val now = at(12, 20)
        val items = Intake.items(w, book, now)
        // 念头 3 天保鲜：10-05 收的已经过期
        assertEquals(IntakeState.EXPIRED, items.first { it.rec.title == "念头一" }.state)
        assertEquals("B站", items.first { it.rec.title == "B站视频2" }.source)
        val k = items.first { it.rec.title == "公众号文章" }.rec.key
        book = Intake.distill(book, k, "一句话收获", now)
        assertEquals(IntakeState.DISTILLED, Intake.items(w, book, now).first { it.rec.key == k }.state)
        val s = Intake.stats(w, book, now, zone)
        assertEquals(2, s.todayIn)
        assertEquals(2, s.backlog)
        assertTrue(!s.overDaily && !s.overBacklog)
        val src = Intake.sources(w, book, now)
        assertEquals("B站", src.first().source)
        assertEquals(0, src.first().useful)
        assertTrue(Intake.brief(w, book, now, zone).contains("今天新收 2/2"))
    }

    @Test
    fun estimateFactorEnergyAndBackward() {
        var w = Work()
        // 三步：计划 30 分钟，实际 45、75、40 → 系数 1.5（中位数）
        listOf(45, 75, 40).forEachIndexed { i, actual ->
            val d = 6 + i
            val (w2, a) = WorkOps.create(w, Kind.ACTION, mapOf(F.TITLE to "第 $i 步", F.PLAN to "2026-10-0${d}T09:00:00+08:00|2026-10-0${d}T09:30:00+08:00", F.STATUS to "完成"), at(d, 8), Random(i))
            w = WorkOps.event(WorkOps.event(w2, Event(at(d, 9), "start", a.key)), Event(at(d, 9) + actual * 60_000L, "done:YES", a.key))
        }
        val samples = Calibrate.samples(w, zone)
        assertEquals(3, samples.size)
        assertEquals(1.5, Calibrate.factor(samples)!!, 0.01)
        assertNull(Calibrate.factor(samples.take(2)))
        val e = Calibrate.energy(w, zone, at(12, 20))
        assertEquals(listOf(9, 10), Calibrate.bestHours(e, 2))
        val (slots, crowded) = Calibrate.backward(listOf("A" to 60, "B" to 30), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 12), zone, 1.5, 9)
        assertTrue(!crowded)
        assertEquals(90, slots[0].minutes)
        assertTrue(slots[1].plan.start, slots[1].plan.start.startsWith("2026-10-19T09:00"))
        assertTrue(slots[0].plan.start < slots[1].plan.start)
        val (tight, c2) = Calibrate.backward(listOf("A" to 30, "B" to 30, "C" to 30), LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 12), zone, null, null)
        assertTrue(c2)
        assertTrue(tight[0].plan.start.startsWith("2026-10-12T20:00"))
    }
}
