package com.yishou.app.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationRulesTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `extracts a preference`() {
        val m = RelationRules.extract("我喜欢先结论后论证", now)
        assertEquals(1, m.size)
        assertEquals(MemoryType.PREFERENCE, m[0].type)
        assertEquals("user", m[0].source)
    }

    @Test
    fun `extracts a commitment`() {
        val m = RelationRules.extract("我答应明天投三份简历", now)
        assertTrue(m.any { it.type == MemoryType.COMMITMENT })
    }

    @Test
    fun `extracts a fact`() {
        val m = RelationRules.extract("我最近在准备省考", now)
        assertTrue(m.any { it.type == MemoryType.FACT })
    }

    @Test
    fun `extracts an event`() {
        val m = RelationRules.extract("今天去了图书馆", now)
        assertTrue(m.any { it.type == MemoryType.EVENT })
    }

    @Test
    fun `filler produces nothing`() {
        assertTrue(RelationRules.extract("嗯", now).isEmpty())
        assertTrue(RelationRules.extract("好的", now).isEmpty())
    }

    @Test
    fun `an open topic gets a follow up time`() {
        val t = RelationRules.topicFrom("卡在登录接口，还没搞定", now)
        assertTrue(t != null)
        assertEquals(TopicState.OPEN, t!!.state)
        assertEquals(now + 2 * 60 * 60_000L, t.followUpAt)
    }

    @Test
    fun `a resolved sentence is not a new topic`() {
        assertNull(RelationRules.topicFrom("已经解决了", now))
        assertTrue(RelationRules.isResolved("做完了"))
        assertFalse(RelationRules.isResolved("还在弄"))
    }

    @Test
    fun `negation is not completion`() {
        assertFalse(RelationRules.isResolved("还没搞定"))
        assertFalse(RelationRules.isResolved("尚未解决"))
        assertFalse(RelationRules.isResolved("没做完"))
        assertTrue(RelationRules.isResolved("搞定了"))
    }

    @Test
    fun `resolved answer closes the topic`() {
        val t = Topic(1, "登录接口", TopicState.OPEN, now - 1000, now - 1000, now - 1)
        val closed = RelationRules.updateTopicAfterAnswer(t, "解决了", now, resolved = true)
        assertEquals(TopicState.RESOLVED, closed.state)
        assertEquals(0L, closed.followUpAt)
    }

    @Test
    fun `an open answer pushes the next follow up out`() {
        val t = Topic(1, "登录接口", TopicState.OPEN, now - 1000, now - 1000, now - 1)
        val next = RelationRules.updateTopicAfterAnswer(t, "弄了一半", now, resolved = false)
        assertEquals(TopicState.OPEN, next.state)
        assertEquals(now + 2 * 60 * 60_000L, next.followUpAt)
    }

    @Test
    fun `correction closes the old memory`() {
        val old = RelationMemory(1, MemoryType.FACT, "用户在准备考研", observedAt = now - 1000)
        val closed = RelationRules.closeOnCorrection(old, now)
        assertEquals(now, closed.validTo)
    }

    @Test
    fun `prompt selection skips closed memories and prefers commitments`() {
        val memories = listOf(
            RelationMemory(1, MemoryType.FACT, "准备省考", observedAt = now - 5000),
            RelationMemory(2, MemoryType.COMMITMENT, "明天投三份简历", observedAt = now - 1000),
            RelationMemory(3, MemoryType.EVENT, "昨天去了图书馆", observedAt = now - 100, validTo = now),
        )
        val topics = listOf(Topic(7, "登录接口", TopicState.OPEN, now - 1000, now - 1000, now - 1))
        val picked = RelationRules.selectForPrompt(memories, topics, now, limit = 3)
        assertEquals(MemoryType.COMMITMENT, picked[0].type)
        assertTrue(picked.none { it.text == "昨天去了图书馆" })
        assertTrue(picked.any { it.text == "登录接口" })
    }

    @Test
    fun `topic question quotes the topic`() {
        val q = RelationRules.topicQuestion(Topic(1, "登录接口", state = TopicState.OPEN, createdAt = now, lastMentionedAt = now, followUpAt = now))
        assertTrue(q.contains("登录接口"))
        assertTrue(q.contains("哪一步"))
    }
}
