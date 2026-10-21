package com.moling.core

import com.moling.core.flow.Channel
import com.moling.core.flow.ChannelKind
import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import com.moling.core.mind.BEIJING
import com.moling.core.usage.Session
import com.moling.core.usage.UsageDay
import com.moling.core.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 手机使用的脉络：按小时、最长一次、拿起次数、计划被占多少、最容易被拉走的时段。 */
class UsageTest {
    private fun at(h: Int, mi: Int = 0) = LocalDateTime.of(2026, 10, 21, h, mi).atZone(BEIJING).toInstant().toEpochMilli()
    private val day = "2026-10-21"
    private val start = Flow.dayStart(day)
    private fun play(a: Long, b: Long, pkg: String = "aweme", label: String = "抖音") = Session(pkg, label, "ENTERTAINMENT", a, b)

    @Test fun dayDigest() {
        val s = listOf(
            play(at(13, 50), at(14, 20)),
            play(at(14, 20, ), at(14, 40)), // 接着刷：算同一次
            Session("notion.id", "Notion", "WORK", at(15), at(15, 45)),
            play(at(23, 10), at(23, 40), "bili", "哔哩哔哩"),
        )
        val d = UsageLog.day(day, s, start)
        assertEquals(80, d.playMin)
        assertEquals(45, d.workMin)
        assertEquals(10, d.playByHour[13])
        assertEquals(40, d.playByHour[14])
        assertEquals(30, d.playByHour[23])
        assertEquals("连着的同一个应用合成一次", 50, d.longestPlay)
        assertEquals(3, d.pickups)
        assertEquals("抖音 50", d.top.first())
    }

    @Test fun planVsActual() {
        val plan = FlowPlan(
            day, "写申论",
            channels = listOf(
                Channel("c1", ChannelKind.MAIN, "申论第一篇", 14 * 60, 90),
                Channel("c2", ChannelKind.FREE, "随便刷", 13 * 60, 60),
            ),
        )
        val lines = UsageLog.planVsActual(plan, listOf(play(at(13, 50), at(14, 35))), start, at(16))
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("被娱乐占了 35 分钟（抖音）"))
    }

    @Test fun weakHoursAcrossDays() {
        fun d(h: Int, m: Int) = UsageDay("x", playByHour = List(24) { if (it == h) m else 0 })
        val w = UsageLog.weakHours(listOf(d(22, 40), d(23, 50), d(22, 30)))
        assertEquals("22–24 点（平均 40 分钟）", w.first())
    }
}
