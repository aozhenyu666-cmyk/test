package com.yishou.app.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveEngineTest {

    private val cfg = ProactiveConfig()
    private val now = 1_800_000_000_000L
    private fun ctx(
        hour: Int = 14,
        watched: Boolean = true,
        fg: Int = 10,
        task: String? = "行测",
        stuck: String? = "",
        topics: List<TopicBrief> = emptyList(),
        ownUi: Boolean = false,
        pending: Boolean = false,
    ) = ProactiveContext(
        now = now,
        hour = hour,
        foregroundPkg = "com.google.android.youtube",
        watched = watched,
        foregroundMinutes = fg,
        taskTitle = task,
        stuck = stuck,
        openTopics = topics,
        ownUiOpen = ownUi,
        hasPending = pending,
    )

    @Test
    fun `disabled means silent`() {
        assertEquals(
            ProactiveDecision.Silent,
            ProactiveEngine.decide(cfg.copy(enabled = false), ProactiveState(), ctx(), 0),
        )
    }

    @Test
    fun `outside active hours is silent`() {
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(cfg, ProactiveState(), ctx(hour = 3), 0))
    }

    @Test
    fun `own ui speaking or pending is silent`() {
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(cfg, ProactiveState(), ctx(ownUi = true), 0))
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(cfg, ProactiveState(), ctx(pending = true), 0))
    }

    @Test
    fun `resting suppresses until the rest ends`() {
        val resting = ProactiveState(mode = ProactiveMode.RESTING, restUntil = now + 10 * 60_000L)
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(cfg, resting, ctx(), 0))
        val ended = resting.copy(restUntil = now - 1)
        assertTrue(ProactiveEngine.decide(cfg, ended, ctx(), 0) is ProactiveDecision.Ask)
    }

    @Test
    fun `deferred suppresses until the time`() {
        val deferred = ProactiveState(mode = ProactiveMode.DEFERRED, deferUntil = now + 10 * 60_000L)
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(cfg, deferred, ctx(), 0))
    }

    @Test
    fun `the gap is measured from the answer too`() {
        val recent = ProactiveState(lastAnswerAt = now - 5 * 60_000L)
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(cfg, recent, ctx(), 0))
        val old = ProactiveState(lastAnswerAt = now - 13 * 60_000L)
        assertTrue(ProactiveEngine.decide(cfg, old, ctx(), 0) is ProactiveDecision.Ask)
    }

    @Test
    fun `hourly cap stops more prompts`() {
        val capped = ProactiveState(promptsThisHour = cfg.maxPerHour)
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(cfg, capped, ctx(), 0))
    }

    @Test
    fun `backoff stays silent unless a topic is due`() {
        val backoff = ProactiveState(mode = ProactiveMode.BACKOFF, unanswered = 2)
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(cfg, backoff, ctx(), 0))

        val due = TopicBrief(1, "登录接口", "OPEN", now - 4 * 60 * 60_000L, now - 60 * 60_000L)
        val d = ProactiveEngine.decide(cfg, backoff, ctx(topics = listOf(due)), 0)
        assertTrue(d is ProactiveDecision.Ask)
        assertEquals(AskKind.TOPIC_FOLLOW_UP, (d as ProactiveDecision.Ask).kind)
    }

    @Test
    fun `a due topic wins over the app nudge`() {
        val due = TopicBrief(7, "简历投递", "OPEN", now - 4 * 60 * 60_000L, now - 1)
        val d = ProactiveEngine.decide(cfg, ProactiveState(), ctx(topics = listOf(due)), 0)
        assertEquals(AskKind.TOPIC_FOLLOW_UP, (d as ProactiveDecision.Ask).kind)
        assertEquals(7L, d.topicId)
    }

    @Test
    fun `a recently mentioned topic is skipped`() {
        val recent = TopicBrief(7, "简历投递", "OPEN", now - 60 * 60_000L, now - 1)
        val d = ProactiveEngine.decide(cfg, ProactiveState(), ctx(topics = listOf(recent)), 0)
        assertEquals(AskKind.TASK_NUDGE, (d as ProactiveDecision.Ask).kind)
    }

    @Test
    fun `watched with a task asks about the task`() {
        val d = ProactiveEngine.decide(cfg, ProactiveState(), ctx(task = "行测", stuck = "卡在两个选项"), 0)
        assertEquals(AskKind.TASK_NUDGE, (d as ProactiveDecision.Ask).kind)
    }

    @Test
    fun `watched without a task asks a gentle rest check`() {
        val d = ProactiveEngine.decide(cfg, ProactiveState(), ctx(task = null, stuck = null), 0)
        assertEquals(AskKind.REST_CHECK, (d as ProactiveDecision.Ask).kind)
    }

    @Test
    fun `no context means silence`() {
        assertEquals(
            ProactiveDecision.Silent,
            ProactiveEngine.decide(cfg, ProactiveState(), ctx(watched = false, task = null, stuck = null), 0),
        )
    }

    @Test
    fun `the opportunity roll gates every prompt`() {
        assertEquals(
            ProactiveDecision.Silent,
            ProactiveEngine.decide(cfg, ProactiveState(), ctx(), roll = cfg.opportunityPercent),
        )
    }

    @Test
    fun `short presence does not qualify`() {
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(cfg, ProactiveState(), ctx(fg = 1), 0))
    }
}
