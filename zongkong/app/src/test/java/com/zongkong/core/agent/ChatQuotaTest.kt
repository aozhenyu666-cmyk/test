package com.zongkong.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 每日段数额度的语义：3 段/天怎么算、两条线怎么各算各的。
 *
 * 用户的原话是“总共应该可以开两到三个对话”，所以默认上限是 3；这里钉住这个值下的行为，
 * 以及「一边开满不影响另一边」这条隔离——额度共用的话，用户切到整体就莫名其妙不能开新段了。
 * 全是纯函数，不碰 Android。
 */
class ChatQuotaTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun user(text: String, t: Long) = ChatMsg(ChatMsg.USER, text, at = t)

    private fun thread(id: String, mode: ChatMode, createdAt: Long, said: Boolean = true) = ChatThread(
        id = id,
        createdAt = createdAt,
        updatedAt = createdAt,
        // 有消息才算“用掉额度”；空段（刚切模式自动建的）不算，见 openedToday 的注释
        messages = if (said) listOf(user("在吗", createdAt)) else emptyList(),
        mode = mode.key,
    )

    /** 3 段/天的默认上限：同一天开满 3 段以后就没有了。 */
    @Test
    fun threePerDayRunsOutAfterThreeSegments() {
        val now = at(17, 20)
        val perDay = 3
        val open = (1..3).map { i -> thread("n$i", ChatMode.NOW, at(17, 8 + i)) }
        // 第 1、2 段之后还有；第 3 段之后到上限
        assertEquals(2, ChatThreads.remaining(perDay, ChatThreads.openedToday(open.take(1), now, zone, ChatMode.NOW, 1)))
        assertEquals(1, ChatThreads.remaining(perDay, ChatThreads.openedToday(open.take(2), now, zone, ChatMode.NOW, 2)))
        assertEquals(0, ChatThreads.remaining(perDay, ChatThreads.openedToday(open, now, zone, ChatMode.NOW, 3)))
        assertFalse(ChatThreads.canOpen(perDay, ChatThreads.openedToday(open, now, zone, ChatMode.NOW, 3)))
        assertTrue(ChatThreads.limitText(perDay, 3, ChatMode.NOW).contains("上限"))
    }

    /** 两条线互不占额度：当下开满 3 段，整体照样还有 3 段。 */
    @Test
    fun twoLinesDoNotShareQuota() {
        val now = at(17, 20)
        val perDay = 3
        val nowThreads = (1..3).map { i -> thread("n$i", ChatMode.NOW, at(17, 8 + i)) }
        val overallThreads = listOf(thread("o1", ChatMode.OVERALL, at(17, 12)))
        val all = nowThreads + overallThreads

        val nowOpened = ChatThreads.openedToday(all, now, zone, ChatMode.NOW, 3)
        val overallOpened = ChatThreads.openedToday(all, now, zone, ChatMode.OVERALL, 1)
        assertEquals(0, ChatThreads.remaining(perDay, nowOpened))
        assertEquals(2, ChatThreads.remaining(perDay, overallOpened))
        // 当下到上限不影响整体还能不能开
        assertFalse(ChatThreads.canOpen(perDay, nowOpened))
        assertTrue(ChatThreads.canOpen(perDay, overallOpened))
    }

    /** 换日归零：昨天的 3 段不占今天的额度。 */
    @Test
    fun quotaResetsOnTheNextLogicalDay() {
        val perDay = 3
        val yesterday = (1..3).map { i -> thread("y$i", ChatMode.NOW, at(16, 8 + i)) }
        val today = listOf(thread("t1", ChatMode.NOW, at(17, 8)))
        val opened = ChatThreads.openedToday(yesterday + today, at(17, 12), zone, ChatMode.NOW, 1)
        assertEquals(1, opened)
        assertEquals(2, ChatThreads.remaining(perDay, opened))
        // 凌晨 4 点前还算昨天：这一段仍然是今天唯一的额度
        assertEquals(1, ChatThreads.openedToday(yesterday + today, at(18, 3), zone, ChatMode.NOW, 1))
        // 过了 4 点，昨天那三段彻底不算了
        assertEquals(0, ChatThreads.openedToday(yesterday, at(18, 5), zone, ChatMode.NOW, 0))
    }

    /** 上限提示必须点明是哪条线：不带 mode 的老重载说过不分，用户就不知道该收尾哪一条。 */
    @Test
    fun limitTextAlwaysNamesItsLine() {
        val now = ChatThreads.limitText(3, 3, ChatMode.NOW)
        assertTrue(now, now.contains("当下"))
        assertTrue(now, now.contains("3"))
        val overall = ChatThreads.limitText(3, 1, ChatMode.OVERALL)
        assertTrue(overall, overall.contains("整体"))
        assertTrue(overall, overall.contains("还能新开 2 段"))
        // 0 = 不限，但也要说清是哪条线不限
        assertTrue(ChatThreads.limitText(0, 9, ChatMode.OVERALL).contains("整体"))
    }
}
