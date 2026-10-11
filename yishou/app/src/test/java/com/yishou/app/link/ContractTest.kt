package com.yishou.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {

    @Test
    fun zongkongStatusRoundTripsFromBundleValues() {
        val s = ZongkongStatus.fromMap(
            mapOf(
                "strict" to true,
                "headline" to "今日部署还没交",
                "reasons" to "今日部署还没交\n专注锁",
                "focusUntil" to 1_700_000_000_000L,
                "actionTitle" to "写第二节",
                "blocks" to "com.ss.android.ugc.aweme,tv.danmaku.bili",
                "gatesDone" to 2,
                "gatesTotal" to 5,
            ),
        )
        assertTrue(s.strict)
        assertEquals(listOf("今日部署还没交", "专注锁"), s.reasons)
        assertEquals(1_700_000_000_000L, s.focusUntil)
        assertTrue(s.bounces("tv.danmaku.bili"))
        assertFalse(s.bounces("com.tencent.mm"))
        assertEquals(2, s.gatesDone)
    }

    @Test
    fun notStrictNeverBounces() {
        val s = ZongkongStatus.fromMap(mapOf("strict" to false, "blocks" to "tv.danmaku.bili"))
        assertFalse(s.bounces("tv.danmaku.bili"))
    }

    @Test
    fun unknownOrMissingFieldsFallBack() {
        val s = ZongkongStatus.fromMap(mapOf("strict" to "yes", "future" to 1))
        assertFalse(s.strict)
        assertEquals(emptySet<String>(), s.blocks)
    }

    @Test
    fun taskOrderNeedsTitleAndGoal() {
        assertNull(TaskOrder.fromMap(mapOf("title" to "写第二节")))
        val o = TaskOrder.fromMap(mapOf("title" to "  写第二节 ", "goal" to "写完 800 字", "ref" to "ZK-AB12/k1"))!!
        assertEquals("写第二节", o.title)
        assertEquals("ZK-AB12/k1", o.ref)
        assertEquals("", o.known)
    }

    @Test
    fun taskOrderTruncatesLongTitle() {
        val o = TaskOrder.fromMap(mapOf("title" to "长".repeat(200), "goal" to "g"))!!
        assertEquals(60, o.title.length)
    }

    @Test
    fun yishouStatusCarriesVersion() {
        val m = YishouStatus(taskTitle = "资料分析", doneSets = 1, targetSets = 3).toMap()
        assertEquals(Link.VERSION, m["v"])
        assertEquals("资料分析", m["taskTitle"])
        assertEquals(3, m["targetSets"])
    }

    @Test
    fun zongkongCanTightenPasses() {
        val s = ZongkongStatus.fromMap(mapOf("passCap" to 5, "noOffline" to true, "note" to "总控：今天的主线「写第二节」还没开始", "mainTitle" to "写第二节"))
        assertEquals(5, s.capPass(10))
        assertEquals(3, s.capPass(3))
        assertTrue(s.noOffline)
        assertFalse(s.hold)
        assertEquals("写第二节", s.mainTitle)
        assertEquals(10, ZongkongStatus.fromMap(emptyMap()).capPass(10))
    }

    @Test
    fun focusLockHoldsPasses() {
        assertTrue(ZongkongStatus.fromMap(mapOf("hold" to true)).hold)
    }
}
