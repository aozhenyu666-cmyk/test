package com.zongkong.core

import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
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

class LinkTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 11, 9, 30, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun yishouStatusFromBundleValues() {
        val s = YishouStatus.fromMap(mapOf("doneSets" to 2, "targetSets" to 2, "setSize" to 3, "effectiveToday" to 7, "taskTitle" to "资料分析", "ref" to "ZK-AB12/k1x", "windowActive" to true, "future" to "x"))
        assertTrue(s.hitToday)
        assertEquals("k1x", s.actionKey)
        assertTrue(s.summary().contains("2/2 组"))
        assertTrue(s.summary().contains("陪练窗口"))
        assertFalse(YishouStatus.fromMap(emptyMap()).hitToday)
    }

    @Test
    fun taskOrderCarriesThreadContextAndRef() {
        val rnd = Random(1)
        val (w1, t) = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "行测", F.WHY to "国考", F.JUDGE to "资料分析最弱"), now, rnd)
        val (w2, a) = WorkOps.create(w1, Kind.ACTION, mapOf(F.TITLE to "资料分析 10 题", F.THREAD to t.key, F.CRITERIA to "正确率 70%"), now, rnd)
        val o = Link.taskOrder(w2, a.key)!!
        assertEquals("资料分析 10 题", o["title"])
        assertEquals("正确率 70%", o["goal"])
        assertTrue(o["material"]!!.contains("国考"))
        assertEquals("${t[F.CODE]}/${a.key}", o["ref"])
        assertNull(Link.taskOrder(w2, "nope"))
    }

    @Test
    fun zongkongStatusListsBlocksAndCurrentStep() {
        val rnd = Random(1)
        val (w1, t) = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "行测"), now, rnd)
        val (w2, a) = WorkOps.create(w1, Kind.ACTION, mapOf(F.TITLE to "资料分析 10 题", F.THREAD to t.key), now, rnd)
        val w3 = WorkOps.start(w2, a.key, now, 30)
        val cfg = Config(blocked = setOf("tv.danmaku.bili"))
        val st = Engine.evaluate(cfg, DayLog(DayClock.dateKey(now, zone)), now, zone, FocusLock(a.title, now + 30 * 60_000))
        val m = Link.zongkongStatus(st, cfg, w3, zone)
        assertEquals(true, m["strict"])
        assertEquals("tv.danmaku.bili", m["blocks"])
        assertEquals("资料分析 10 题", m["actionTitle"])
        assertEquals(now + 30 * 60_000, m["focusUntil"])
    }
}
