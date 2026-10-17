package com.zongkong.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** 多段对话的纯逻辑：标题、交接摘要、第几段、每日上限、换段。不依赖 Android。 */
class ThreadsTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun user(text: String) = ChatMsg(ChatMsg.USER, text, at = at(17, 9))
    private fun said(text: String) = ChatMsg(ChatMsg.ASSISTANT, text, at = at(17, 10))
    private fun tool(text: String) = ChatMsg(ChatMsg.TOOL, text, at = at(17, 10), callId = "c1")

    private fun thread(
        id: String = "t1",
        createdAt: Long = at(17, 9),
        messages: List<ChatMsg> = emptyList(),
        carry: String = "",
        closed: Boolean = false,
    ) = ChatThread(id = id, createdAt = createdAt, updatedAt = createdAt, messages = messages, carry = carry, closed = closed)

    // ---------- 编号 / 标题 ----------

    @Test
    fun idIsShortAndDifferent() {
        val rnd = Random(7)
        val a = ChatThreads.newId(rnd)
        val b = ChatThreads.newId(rnd)
        assertTrue(a.startsWith("t"))
        assertFalse(a == b)
        assertEquals(a, ChatThreads.newId(Random(7)))
    }

    @Test
    fun titleTakesFirstUserMessageAndTrimsTo30() {
        val t = thread(messages = listOf(said("先说话"), user("  帮我把\n今天排一下  ")))
        assertEquals("帮我把 今天排一下", ChatThreads.titleOf(t))

        val long = thread(messages = listOf(user("一".repeat(50))))
        assertEquals(30, ChatThreads.titleOf(long).length)
    }

    @Test
    fun titleSkipsHiddenProactiveInstruction() {
        val hidden = user("系统给你的指令").copy(hidden = true)
        val t = thread(messages = listOf(hidden, user("我卡住了")))
        assertEquals("我卡住了", ChatThreads.titleOf(t))
    }

    @Test
    fun titleFallsBackCarryThenGeneric() {
        assertEquals("上一段的结论", ChatThreads.titleOf(thread(carry = "上一段的结论")))
        assertEquals(ChatThreads.FALLBACK_TITLE, ChatThreads.titleOf(thread()))
    }

    // ---------- 本地兜底摘要 ----------

    @Test
    fun carryUsesLatestAssistantConclusions() {
        val t = thread(
            messages = listOf(
                user("帮我看今天"),
                said("今天先把论文第二节提纲列出来。"),
                tool("新建了行动「列提纲」"),
                said("提纲有 5 个小标题就算完。"),
                user("好"),
                said("20:00 开始，45 分钟。\n【选项】现在就开始｜晚点提醒我"),
            ),
        )
        val carry = ChatThreads.carryOf(t)
        assertTrue(carry, carry.contains("今天先把论文第二节提纲列出来"))
        assertTrue(carry, carry.contains("20:00 开始"))
        assertFalse(carry, carry.contains("【选项】"))
        assertFalse(carry, carry.contains("新建了行动"))
    }

    @Test
    fun carryIsCappedAt600() {
        val t = thread(messages = (1..8).map { said("第$it 条：" + "字".repeat(200)) })
        assertTrue(ChatThreads.carryOf(t).length <= ChatThreads.CARRY_MAX)
    }

    @Test
    fun mergeCarryKeepsRecentPartAndCaps() {
        assertEquals("旧的；新的", ChatThreads.mergeCarry("旧的", "新的"))
        assertEquals("新的", ChatThreads.mergeCarry("", "新的"))
        val long = ChatThreads.mergeCarry("旧".repeat(500), "新".repeat(500))
        assertTrue(long.length <= ChatThreads.CARRY_MAX)
        assertTrue(long.endsWith("新"))
    }

    @Test
    fun contextCarriesTitleAndSummary() {
        val t = thread(messages = listOf(user("写论文")), carry = "列到提纲第 3 节")
        val c = ChatThreads.context(t)
        assertTrue(c, c.contains("写论文"))
        assertTrue(c, c.contains("列到提纲第 3 节"))
    }

    // ---------- 第几段 / 每日上限 ----------

    @Test
    fun contextLineCountsTodayAndBringsPreviousCarry() {
        val t1 = thread(id = "a", createdAt = at(17, 8), carry = "旧段的交接")
        val t2 = thread(id = "b", createdAt = at(17, 12))
        val line = ChatThreads.contextLine(listOf(t1, t2), "b", at(17, 13), zone)
        assertTrue(line, line.contains("今天第 2 段"))
        assertTrue(line, line.contains("旧段的交接"))
    }

    @Test
    fun contextLineForSingleThreadHasNoHandoff() {
        val t = thread(createdAt = at(17, 8))
        val line = ChatThreads.contextLine(listOf(t), "t1", at(17, 9), zone)
        assertTrue(line, line.contains("第 1 段"))
        assertFalse(line, line.contains("上一段交接"))
    }

    @Test
    fun todayCountAndIndexFollowLogicalDay() {
        val yesterday = thread(id = "y", createdAt = at(16, 22))
        val morning = thread(id = "m", createdAt = at(17, 8))
        val evening = thread(id = "e", createdAt = at(17, 23))
        val threads = listOf(yesterday, morning, evening)
        assertEquals(2, ChatThreads.todayCount(threads, at(17, 23, 30), zone))
        assertEquals(2, ChatThreads.indexToday(threads, evening, at(17, 23, 30), zone))
        // 凌晨 4 点才算新的一天：半夜 2 点还算 10/17
        assertEquals(2, ChatThreads.todayCount(threads, at(18, 2), zone))
        assertEquals(2, ChatThreads.indexToday(threads, evening, at(18, 2), zone))
    }

    @Test
    fun dailyLimitMathAndMessage() {
        assertTrue(ChatThreads.canOpen(0, 99))
        assertEquals(-1, ChatThreads.remaining(0, 99))
        assertTrue(ChatThreads.canOpen(1, 0))
        assertFalse(ChatThreads.canOpen(1, 1))
        assertEquals(0, ChatThreads.remaining(1, 1))
        assertEquals(2, ChatThreads.remaining(3, 1))
        assertTrue(ChatThreads.limitText(1, 1).contains("上限"))
        assertTrue(ChatThreads.limitText(1, 0).contains("还能新开 1 段"))
        assertTrue(ChatThreads.limitText(0, 5).contains("不限"))
    }

    // ---------- 段满换段 ----------

    @Test
    fun overflowMeansNextTurnStartsNewThread() {
        val almostFull = thread(messages = (1..59).map { said("话$it") })
        assertFalse(ChatThreads.overflows(almostFull, listOf(user("这轮刚开始"))))
        val full = almostFull.copy(messages = (1..60).map { said("话$it") })
        assertTrue(ChatThreads.overflows(full, listOf(user("再来一句"))))
    }

    @Test
    fun rolloverClosesOldWithCarryAndFreshTakesAdded() {
        val oldMessages = (1..60).map { i -> if (i % 2 == 0) said("结论$i") else user("问题$i") }
        val t = thread(id = "old", messages = oldMessages)
        val (old, fresh) = ChatThreads.rollover(t, listOf(user("新的一段从这句开始")), "new", at(17, 20))
        assertTrue(old.closed)
        assertTrue(old.carry.isNotBlank())
        assertEquals("old", old.id)
        assertEquals("new", fresh.id)
        assertEquals(1, fresh.messages.size)
        assertEquals("新的一段从这句开始", fresh.messages.first().content)
        // 新段也带着交接，接着往下压不会丢链
        assertEquals(old.carry, fresh.carry)
        assertEquals("新的一段从这句开始", ChatThreads.titleOf(fresh))
        assertFalse(fresh.closed)
    }

    @Test
    fun trimmedAndOpened() {
        val t = thread(messages = (1..70).map { said("话$it") })
        val cut = ChatThreads.trimmed(t)
        assertEquals(ChatThreads.MAX_MESSAGES, cut.messages.size)
        assertEquals("话70", cut.messages.last().content)
        assertTrue(ChatThreads.closed(t, "交接", 5).closed)
        assertFalse(ChatThreads.opened(ChatThreads.closed(t, "交接", 5)).closed)
    }
}
