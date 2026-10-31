package com.zongkong.core.buddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 伙伴什么时候开口、聊什么、怎么让路、没回怎么退避。 */
class BuddyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val min = 60_000L

    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 31, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun sig(now: Long, f: Signals.() -> Signals = { this }) = Signals(now = now, zone = zone).f()

    @Test fun morningGreetingFirst() {
        val d = Buddy.decide(BuddyBook(), sig(at(8)), roll = 0.5)
        assertEquals(Topic.MORNING, d?.topic)
    }

    @Test fun idleNeedsALowRoll() {
        val now = at(15)
        val book = BuddyBook(messages = listOf(BuddyMsg(BuddyMsg.BUDDY, "早", at(8))))
        assertNull(Buddy.decide(book, sig(now), roll = 0.9))
        assertEquals(Topic.IDLE, Buddy.decide(book, sig(now), roll = 0.01)?.topic)
    }

    @Test fun eventsTakePriority() {
        val now = at(15)
        val book = BuddyBook(messages = listOf(BuddyMsg(BuddyMsg.BUDDY, "早", at(8))))
        val miss = sig(now) { copy(missAt = now - 10 * min, miss = "论证缺了哪一层") }
        assertEquals(Topic.MISS, Buddy.decide(book, miss, 0.3)?.topic)
        // 聊过的那一题不再聊
        assertEquals(Topic.UNLOCK, Buddy.decide(book.copy(missSeenAt = now - 10 * min), miss.copy(unlockedAt = now - 60_000), 0.3)?.topic)
        val binge = sig(now) { copy(appName = "抖音", appMinutes = 25, appIsPlay = true) }
        assertEquals(Topic.BINGE, Buddy.decide(book, binge, 0.3)?.topic)
        // 非娱乐应用里待得久不算
        assertEquals(Topic.IDLE, Buddy.decide(book, binge.copy(appIsPlay = false), 0.01)?.topic)
    }

    @Test fun yieldsAndQuiet() {
        val now = at(15)
        val book = BuddyBook()
        assertEquals("屏幕没亮", Buddy.blocked(book, sig(now) { copy(interactive = false) }))
        assertEquals("总控时间里，让路", Buddy.blocked(book, sig(now) { copy(hqActive = true) }))
        assertEquals("你正在和总控说话，让路", Buddy.blocked(book, sig(now) { copy(lastZongkongChatAt = now - 2 * min) }))
        assertEquals("安静时段", Buddy.blocked(book, sig(at(23, 45))))
        assertEquals("安静时段", Buddy.blocked(book, sig(at(3))))
        assertNull(Buddy.blocked(book, sig(at(7, 30))))
        assertEquals("关着", Buddy.blocked(book.copy(config = BuddyConfig(enabled = false)), sig(now)))
    }

    @Test fun capsAndBackoff() {
        val now = at(15)
        val full = BuddyBook(spokeAt = listOf(now - 50 * min, now - 40 * min, now - 30 * min, now - 20 * min))
        assertEquals("这一小时说够了", Buddy.blocked(full, sig(now)))
        val recent = BuddyBook(spokeAt = listOf(now - 10 * min))
        assertNull(Buddy.blocked(recent, sig(now)))
        // 没回两次：8 分钟 × 4 = 32 分钟
        assertEquals(32 * min, Buddy.gapMs(recent.copy(unanswered = 2)))
        assertTrue(Buddy.blocked(recent.copy(unanswered = 2), sig(now))!!.startsWith("离上次不到 32 分钟"))
        assertEquals(64 * min, Buddy.gapMs(recent.copy(unanswered = 9)))
    }

    @Test fun spokeAndHeardTrackUnanswered() {
        val now = at(15)
        var b = Buddy.spoke(BuddyBook(), Decision(Topic.MISS, "x"), "那题卡哪了？", now, missAt = now - 5)
        assertEquals(1, b.unanswered)
        assertEquals(now - 5, b.missSeenAt)
        assertEquals("speakup:miss", b.messages.last().origin)
        b = Buddy.heard(b, "选项看错了", now + min)
        assertEquals(0, b.unanswered)
        assertEquals(now + min, b.lastUserAt)
        assertEquals("正在聊，不另起话头", Buddy.blocked(b, sig(now + 2 * min)))
    }

    @Test fun parseSplitsMemoryAndRelay() {
        val p = Buddy.parse("好呀，那就九点开始？\n【记住】他周末要考驾照\n【告诉总控】答应九点开始写提纲")
        assertEquals("好呀，那就九点开始？", p.text)
        assertEquals(listOf("他周末要考驾照"), p.remember)
        assertEquals(listOf("答应九点开始写提纲"), p.toZongkong)
        val b = Buddy.remember(BuddyBook(facts = listOf("a", "b")), listOf("b", "c"))
        assertEquals(listOf("a", "b", "c"), b.facts)
    }

    @Test fun personaIsNotTheEnforcer() {
        val s = Buddy.system(BuddyConfig(name = "阿鲸", note = "多开玩笑"), listOf("喜欢猫"), "主线：写提纲")
        assertTrue(s.startsWith("你叫「阿鲸」"))
        assertTrue("不下命令、不验收、不打分、不说教" in s)
        assertTrue("多开玩笑" in s && "- 喜欢猫" in s && "主线：写提纲" in s)
    }
}
