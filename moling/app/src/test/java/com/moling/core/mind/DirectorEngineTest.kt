package com.moling.core.mind

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DirectorEngineTest {
    private fun at(d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(2026, 10, d, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    /** 下午 2 点的主线“申论第二题”计划 13:30 开始，没开始，正在刷抖音 16 分钟。 */
    private fun stalled(now: Long) = Facts(
        now = now, minute = 14 * 60,
        mainTitle = "申论第二题", mainKey = "ZK-1", mainPlannedAt = now - 30 * 60_000L,
        foregroundPkg = "com.ss.android.ugc.aweme", foregroundLabel = "抖音", foregroundCategory = "ENTERTAINMENT", foregroundMinutes = 16,
        playMinutesToday = 40, workMinutesToday = 30, lastContactAt = now - 2 * 3_600_000L,
    )

    private fun engine(facts: () -> Facts, ask: Ask? = null, store: MindStore = MemStore(), cfg: DirectorConfig = DirectorConfig()) =
        DirectorEngine(FactsSource { facts() }, ask, store, { cfg })

    private fun sense(kind: SenseKind, at: Long, payload: Map<String, String> = emptyMap()) = Sense(kind, at, "test", payload)

    @Test fun stallAsksPickQuestionAndLogsHypothesis() = runTest {
        val t = at(19, 14)
        val e = engine({ stalled(t) })
        val card = e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t))
        assertNotNull(card)
        assertEquals(Mode.START, card!!.mode)
        assertTrue(card.ask.contains("申论第二题"))
        assertEquals(4, card.choices.size)
        val h = e.snapshot().hypotheses.single()
        assertEquals("待验证", h.outcome)
        assertTrue(h.alternatives.isNotEmpty())
        assertEquals(card.id, e.currentCard(t)!!.id)
    }

    @Test fun fullLoopStuckToTalkToConclusionToFollowUp() = runTest {
        var now = at(19, 14)
        var f = stalled(now)
        val e = engine({ f })
        val c1 = e.onSense(sense(SenseKind.PLAN_DUE, now))!!
        val c2 = e.onReply(Reply.Pick(c1.id, now, "stuck"))!!
        assertEquals("对了", e.snapshot().hypotheses.single().outcome)
        assertTrue(c2.choices.any { it.key == CHOICE_GO })
        val c3 = e.onReply(Reply.Pick(c2.id, now, "s_idea"))!!
        assertEquals(Actor.GEMINI_LIVE, c3.actor)

        val op = e.opening(Actor.GEMINI_LIVE, c3.id, now)
        assertEquals(4, op.cards.size)
        assertTrue(op.text.contains("申论第二题"))
        assertTrue(op.text.contains("别替我总结"))

        now += 12 * 60_000L
        val ok = e.onSession(SessionLog(op.sessionId, Actor.GEMINI_LIVE, now - 12 * 60_000L, now, 11 * 60_000L, 0, "复制", true, "第二题要先找共同原因，下一步今天3点半重写第二段"))
        assertNotNull(ok)
        val b = e.snapshot().baton!!
        assertTrue(b.known.contains("共同原因"))
        assertEquals("今天3点半重写第二段", b.nextAsk)
        val fu = e.snapshot().followUps.single()

        // 到点后没有任何进展 → 回头问
        now = fu.dueAt + 60_000L
        f = stalled(now).copy(lastProgressAt = 0)
        val back = e.onSense(sense(SenseKind.TICK, now))!!
        assertTrue(back.ask.contains("重写第二段"))
        e.onReply(Reply.Pick(back.id, now, "started"))
        assertTrue(e.snapshot().followUps.single().done)
    }

    @Test fun followUpIsSilentWhenProgressHappened() = runTest {
        var now = at(19, 14)
        var f = stalled(now)
        val e = engine({ f })
        val ok = e.onReply(Reply.Conclusion("x", now, "想清楚了归纳的顺序，下一步重写第二段"))
        assertNotNull(ok)
        val fu = e.snapshot().followUps.single()
        now = fu.dueAt + 1
        f = Facts(now = now, minute = 15 * 60, lastProgressAt = fu.since + 1)
        assertNull(e.onSense(sense(SenseKind.TICK, now)))
        assertTrue(e.snapshot().followUps.single().done)
    }

    @Test fun quietHoursQueueButDoNotPush() = runTest {
        val t = at(19, 14)
        val e = engine({ stalled(t).copy(quiet = true) })
        assertNull(e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t)))
        assertNotNull(e.currentCard(t))
    }

    @Test fun focusBlocksButHealthStillGetsThrough() = runTest {
        val t = at(19, 14)
        val e = engine({ stalled(t).copy(inFocus = true) })
        assertNull(e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t)))
        val h = e.onSense(sense(SenseKind.HEALTH, t, mapOf("permission" to "false", "connected" to "false")))
        assertNotNull(h)
        assertTrue(h!!.choices.any { it.key == CHOICE_FIX })
        assertEquals("无障碍", e.snapshot().incidents.last().kind)
    }

    @Test fun userDisabledAccessibilityIsRespected() = runTest {
        val t = at(19, 14)
        val e = engine({ stalled(t).copy(inFocus = true) })
        assertNull(e.onSense(sense(SenseKind.HEALTH, t, mapOf("permission" to "false", "userDisabled" to "true"))))
        assertTrue(e.snapshot().incidents.last().detail.contains("用户自己关的"))
    }

    @Test fun sameTopicWaitsAndGivesUpAfterBeingIgnored() = runTest {
        var t = at(19, 14)
        val e = engine({ stalled(t) }, cfg = DirectorConfig(topicGapMin = 20, topicPatience = 2))
        val c = e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t))!!
        t += 5 * 60_000L
        assertNull("间隔太短", e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t)))
        e.onReply(Reply.Dismissed(c.id, t, timeout = true))
        t += 25 * 60_000L
        val c2 = e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t))!!
        e.onReply(Reply.Dismissed(c2.id, t))
        t += 25 * 60_000L
        assertNull("被无视两次，今天不再提", e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t)))
        assertEquals(2, e.snapshot().incidents.count { it.kind == "被划掉" })
    }

    @Test fun dailyPushCap() = runTest {
        var t = at(19, 14)
        val e = engine({ stalled(t).copy(mainKey = "k$t") }, cfg = DirectorConfig(pushesPerDay = 2))
        assertNotNull(e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t)))
        t += 1
        assertNotNull(e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t)))
        t += 1
        assertNull(e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t)))
    }

    @Test fun wrongGuessIsRecordedAndSnoozed() = runTest {
        var t = at(19, 14)
        val e = engine({ stalled(t) })
        val c = e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t))!!
        assertNull(e.onReply(Reply.Pick(c.id, t, "work")))
        assertEquals("错了", e.snapshot().hypotheses.single().outcome)
        t += 30 * 60_000L
        assertNull("说了在做事，一小时内不再问", e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t)))
        val w = e.onReply(Reply.Wrong(c.id, t, "在看申论讲解视频"))
        assertNotNull(w)
        assertTrue(e.snapshot().incidents.any { it.kind == "猜错" })
    }

    @Test fun modelRewritesQuestionButKeepsChoices() = runTest {
        val t = at(19, 14)
        val ask = Ask { _, _ -> "【问】申论第二题过点半小时了，抖音刷了16分钟，你是在？\n【朗读】申论还没开始，你在干嘛？" }
        val e = engine({ stalled(t) }, ask)
        val c = e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t))!!
        assertTrue(c.ask.startsWith("申论第二题过点半小时了"))
        assertEquals(4, c.choices.size)
        assertEquals(1, e.snapshot().costToday(dayKey(t)).calls)
    }

    @Test fun budgetExhaustedFallsBackToTemplate() = runTest {
        val t = at(19, 14)
        var called = 0
        val ask = Ask { _, _ -> called++; "【问】不该出现" }
        val e = engine({ stalled(t) }, ask, cfg = DirectorConfig(dailyChars = 10))
        val c = e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t))!!
        assertEquals(0, called)
        assertTrue(c.ask.contains("过点"))
    }

    @Test fun failingModelNeverBreaksTheLoop() = runTest {
        val t = at(19, 14)
        val e = engine({ stalled(t) }, Ask { _, _ -> throw IllegalStateException("网断了") })
        assertNotNull(e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t)))
        val op = e.opening(Actor.GEMINI_LIVE, null, t)
        assertEquals(4, op.cards.size)
    }

    @Test fun emptyConclusionAsksAgainAndIsLogged() = runTest {
        val t = at(19, 14)
        val e = engine({ stalled(t) })
        val c = e.onReply(Reply.Conclusion("x", t, "好的"))!!
        assertTrue(c.ask.contains("再说一句"))
        assertTrue(e.snapshot().incidents.any { it.kind == "收线没内容" })
    }

    @Test fun sessionWithoutConclusionIsAnIncident() = runTest {
        val t = at(19, 14)
        val e = engine({ stalled(t) })
        assertNull(e.onSession(SessionLog("s1", Actor.GEMINI_LIVE, t - 600_000, t, 300_000, 2, "复制", false)))
        val kinds = e.snapshot().incidents.map { it.kind }
        assertTrue(kinds.contains("没收线"))
        assertTrue(kinds.contains("交接失败"))
    }

    @Test fun inboxClosingAndMemoryConflictBecomesQuestion() = runTest {
        var t = at(19, 14)
        val e = engine({ Facts(now = t, minute = 14 * 60) })
        e.onSense(sense(SenseKind.INBOX, t, mapOf("source" to "chatgpt", "text" to "收线｜投这个岗位｜改简历第二段｜明天10点")))
        assertEquals("改简历第二段", e.snapshot().baton!!.nextAsk)
        e.onSense(sense(SenseKind.INBOX, t, mapOf("source" to "chatgpt", "text" to "记住[作息]：晚上效率高")))
        t += 1000
        e.onSense(sense(SenseKind.INBOX, t, mapOf("source" to "gemini", "text" to "记住【作息】：晚上效率低")))
        e.onSense(sense(SenseKind.NIGHT, at(19, 23)))
        val q = e.currentCard(at(19, 23))!!
        assertTrue(q.ask.contains("晚上效率高"))
        e.onReply(Reply.Pick(q.id, at(19, 23), "c_new"))
        val kept = e.snapshot().memories.filter { it.key == "作息" }
        assertEquals(1, kept.size)
        assertTrue(kept.single().confirmed)
        assertEquals("晚上效率低", kept.single().text)
    }

    @Test fun nightWritesDiaryOnceAndFindsPatterns() = runTest {
        val store = MemStore()
        var t = at(15, 14)
        val e = engine({ stalled(t) }, store = store)
        for (d in 15..18) {
            t = at(d, 14)
            val c = e.onSense(sense(SenseKind.LONG_ENTERTAINMENT, t))!!
            e.onReply(Reply.Pick(c.id, t, "stuck"))
            e.onSense(sense(SenseKind.NIGHT, at(d, 23)))
            e.onSense(sense(SenseKind.NIGHT, at(d, 23, 30)))
        }
        val m = e.snapshot()
        assertEquals(4, m.diary.size)
        assertEquals(4, m.episodes.size)
        val p = m.memories.single { it.key == "pattern:卡住@下午" }
        assertEquals("中", p.confidence)
        assertTrue(p.text.contains("下午容易卡住"))
        // 记忆存下来了，重启后还在
        val again = engine({ stalled(t) }, store = store)
        assertEquals(4, again.snapshot().diary.size)
        assertTrue(again.nowText(t).contains("下午容易卡住"))
    }

    @Test fun modelDiaryIsUsedWhenAvailable() = runTest {
        val t = at(19, 23)
        val e = engine({ Facts(now = t, minute = 23 * 60) }, Ask { _, u -> if (u.contains("日记")) "【日记】今天你在申论第二题前停了很久。明天我们从归纳开始试试。" else null })
        e.onSense(sense(SenseKind.NIGHT, t))
        val d = e.snapshot().diary.single()
        assertTrue(d.byModel)
        assertTrue(d.text.contains("归纳"))
    }

    @Test fun unlockOffersBatonAfterLongSilence() = runTest {
        var t = at(19, 10)
        var f = Facts(now = t, minute = 600, lastContactAt = t)
        val e = engine({ f })
        e.onReply(Reply.Conclusion("x", t, "归纳要先找共同原因，下一步重写第二段"))
        t += 4 * 3_600_000L
        f = Facts(now = t, minute = 840, lastContactAt = t - 4 * 3_600_000L)
        val c = e.onSense(sense(SenseKind.UNLOCK, t))!!
        assertTrue(c.ask.contains("重写第二段"))
        val go = e.onReply(Reply.Pick(c.id, t, "go_on"))!!
        assertEquals(Mode.TRAIN, go.mode)
    }

    @Test fun corruptedMemoryStartsFresh() {
        assertEquals(MindState(), MindCodec.decode("{not json"))
        assertEquals(MindState(), MindCodec.decode(null))
    }

    @Test fun nowTextHasSituationAndBaton() = runTest {
        val t = at(19, 14)
        val e = engine({ stalled(t) })
        e.onReply(Reply.Conclusion("x", t, "归纳要先找共同原因，下一步重写第二段"))
        val s = e.nowText(t)
        assertTrue(s.contains("申论第二题"))
        assertTrue(s.contains("接力棒"))
    }
}
