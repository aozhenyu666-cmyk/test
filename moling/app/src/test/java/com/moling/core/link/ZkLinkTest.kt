package com.moling.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZkLinkTest {
    @Test fun commandsRoundTrip() {
        val all = listOf(
            ZkCommand.SetMain("写申论", 1_000L, "800 字", "水道"),
            ZkCommand.AddAction("背单词", 1_000L, 2_000L, "", "水道"),
            ZkCommand.StartFocus(25, "写申论", "偏了"),
            ZkCommand.EnterHq(30, "乱了"),
            ZkCommand.Tighten("block_app", "com.ss.android.ugc.aweme", "刷多了"),
            ZkCommand.RequestLoosen("unblock_app", "tv.danmaku.bili", "周末"),
            ZkCommand.RequestLoosen("play_quota", "90", "周末"),
            ZkCommand.Note("开头先亮观点", "对话"),
        )
        all.forEach { assertEquals(it, ZkWire.fromMap(ZkWire.toMap(it))) }
    }

    @Test fun onlyKindsZongkongCanDo() {
        assertFalse(ZkWire.valid(ZkCommand.Tighten("cut_play", "30", "")))
        assertFalse(ZkWire.valid(ZkCommand.Tighten("strict", "30", "")))
        assertFalse(ZkWire.valid(ZkCommand.Tighten("block_app", "com.android.phone", "")))
        assertFalse(ZkWire.valid(ZkCommand.RequestLoosen("pause_all", "1", "")))
        assertFalse(ZkWire.valid(ZkCommand.RequestLoosen("play_quota", "很多", "")))
        assertTrue(ZkWire.valid(ZkCommand.RequestLoosen("play_quota", "90", "")))
    }

    @Test fun statusFromZongkong() {
        val s = ZkWire.status(
            mapOf(
                "v" to "1", "at" to "5", "strict" to "true", "mainTitle" to "写申论", "playLeftMin" to "-1",
                "aims" to "上岸\n考研", "blocked" to "", "credit" to "80", "sentinelOn" to "true",
            ),
        )
        assertTrue(s.strict)
        assertEquals("写申论", s.mainTitle)
        assertEquals(listOf("上岸", "考研"), s.aims)
        assertTrue(s.blocked.isEmpty())
        assertEquals(80, s.credit)
        assertEquals(-1, s.playLeftMin)
        assertTrue(s.sentinelOn)
        // 缺字段时用默认值
        assertEquals(-1, ZkWire.status(emptyMap()).credit)
    }

    @Test fun receiptFromZongkong() {
        assertTrue(ZkWire.receipt(mapOf("ok" to "true", "message" to "收下了"), 1).ok)
        val refused = ZkWire.receipt(mapOf("error" to "只接受墨灵的连接"), 1)
        assertFalse(refused.ok)
        assertEquals("只接受墨灵的连接", refused.message)
    }
}
