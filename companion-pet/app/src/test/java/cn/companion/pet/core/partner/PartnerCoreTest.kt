package cn.companion.pet.core.partner

import cn.companion.pet.core.vision.VisionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreadBookTest {
    @get:Rule val tmp = TemporaryFolder()
    private val h = 3_600_000L

    @Test fun silenceBacksOffButNeverStops() {
        val b = ThreadBook()
        b.start("t1", ThreadKind.OPEN_PROBLEM, "和室友的矛盾", 3, 0)
        var now = 0L
        repeat(10) { b.onFollowUpIgnored("t1", now); now = b.get("t1")!!.nextFollowUpAt }
        val t = b.get("t1")!!
        assertEquals(ThreadStatus.OPEN, t.status) // 沉默十次也不关
        assertTrue(ThreadRules.backoffAfterSilence(3, 10) <= 24 * h) // 重要的事最长一天回来问一次
        assertTrue(ThreadRules.backoffAfterSilence(3, 2) > ThreadRules.backoffAfterSilence(3, 1))
    }

    @Test fun replyRevivesAndResetsBackoff() {
        val b = ThreadBook()
        b.start("t1", ThreadKind.GOAL, "早睡", 2, 0)
        b.onFollowUpIgnored("t1", 0); b.onFollowUpIgnored("t1", 0)
        val t = b.onUserTurn("t1", 10, "这周有三天十二点前睡了")!!
        assertEquals(0, t.unansweredFollowUps)
        assertEquals("这周有三天十二点前睡了", t.lastProgress)
        assertEquals(10 + ThreadRules.followUpAfterReply(2), t.nextFollowUpAt)
    }

    @Test fun dueOrdersByImportanceAndPausedComesBack() {
        val b = ThreadBook()
        b.start("low", ThreadKind.UNFINISHED_TALK, "电影", 1, 0)
        b.start("high", ThreadKind.GOAL, "自律", 3, 0)
        val later = 100 * h
        assertEquals(listOf("high", "low"), b.due(later).map { it.id })
        b.pause("high", later + h)
        assertEquals(listOf("low"), b.due(later).map { it.id })
        assertEquals(listOf("high", "low"), b.due(later + h).map { it.id })
    }

    @Test fun duplicateTitleDoesNotOpenSecondThread() {
        val b = ThreadBook()
        b.start("a", ThreadKind.GOAL, "自律", 3, 0)
        assertEquals("a", b.start("b", ThreadKind.GOAL, "自律", 3, 1).id)
        assertEquals(1, b.all.size)
    }

    @Test fun forgettingSourceFactClosesThreadThatOnlyCameFromIt() {
        val b = ThreadBook()
        b.start("a", ThreadKind.VIEW, "对拖延的看法", 2, 0, refs = listOf("f1"))
        b.start("c", ThreadKind.GOAL, "自律", 3, 0, refs = listOf("f1", "f2"))
        b.onFactForgotten("f1")
        assertEquals(ThreadStatus.CLOSED, b.get("a")!!.status)
        assertEquals(listOf("f2"), b.get("c")!!.sourceRefs)
        assertTrue(b.due(1000 * h).none { it.id == "a" })
    }

    @Test fun closedOnlyByExplicitCloseAndSurvivesRestart() {
        val b = ThreadBook()
        b.start("a", ThreadKind.GOAL, "自律", 3, 0)
        b.close("a", "他说已经解决")
        assertNull(b.onUserTurn("a", 5)) // 关了就不再动
        val store = ThreadBookStore(tmp.root)
        assertTrue(store.save(b))
        assertEquals(b.all, ThreadBookStore(tmp.root).load().all)
    }

    @Test fun corruptFileIsBackedUpNotFatal() {
        val f = java.io.File(tmp.root, "partner/threads.json"); f.parentFile.mkdirs(); f.writeText("{坏")
        val s = ThreadBookStore(tmp.root)
        assertTrue(s.load().all.isEmpty())
        assertNotNull(s.lastError)
        assertTrue(f.parentFile.listFiles()!!.any { it.name.startsWith("threads.bad-") })
    }
}

class DialogueSessionTest {
    private val r = DialogueRules(keepTurns = 4)
    private fun u(t: String, at: Long) = DialogueTurn(Speaker.USER, t, at, TurnChannel.TYPED)
    private fun p(t: String, at: Long) = DialogueTurn(Speaker.PET, t, at, TurnChannel.REPLY)

    @Test fun noTurnCap() {
        var s = r.start("s", 0)
        for (i in 1..30) { s = r.add(s, u("第$i 句", i * 1000L)); s = r.add(s, p("回$i", i * 1000L + 1)) }
        assertEquals(30, s.userTurns)
        assertEquals(SessionState.ACTIVE, s.state)
    }

    @Test fun awayThenResumeKeepsContext() {
        var s = r.add(r.start("s", 0), u("我在想要不要换专业", 1))
        s = r.away(s, 2)
        assertTrue(r.canResume(s, 2 + 20 * 60_000))
        s = r.resume(s, 2 + 20 * 60_000)
        assertEquals(SessionState.ACTIVE, s.state)
        assertTrue(r.contextLines(s).any { it.contains("换专业") })
        val stale = r.away(s, 0)
        assertFalse(r.canResume(stale, 31 * 60_000L + 1))
    }

    @Test fun idleClosesWithReason() {
        val s = r.tick(r.add(r.start("s", 0), u("hi", 0)), 31 * 60_000L)
        assertEquals(SessionState.CLOSED, s.state)
        assertTrue(s.closedReason.contains("闲置"))
    }

    @Test fun compactionKeepsRecentAndSummary() {
        var s = r.start("s", 0)
        for (i in 1..6) s = r.add(s, u("句$i", i.toLong()))
        assertTrue(r.needsCompaction(s))
        assertEquals(listOf("句1", "句2"), r.turnsToCompact(s).map { it.text })
        s = r.applyCompaction(s, "他前面在说换专业的犹豫")
        assertEquals(4, s.turns.size)
        assertTrue(r.contextLines(s).first().contains("换专业"))
    }

    @Test fun uncertainVoiceIsMarked() {
        val s = r.add(r.start("s", 0), DialogueTurn(Speaker.USER, "我今天骑了二十公里", 1, TurnChannel.VOICE, uncertain = true))
        assertTrue(r.contextLines(s).single().contains("可能听错"))
    }

    @Test fun awaitingUserAfterPetSpeaks() {
        val s = r.add(r.start("s", 0), p("你刚才说的那句我有点不同意", 1))
        assertTrue(s.awaitingUser)
    }
}

class ProactiveGovernorTest {
    private val min = 60_000L
    private fun sig(now: Long, ctx: UsageContext = UsageContext.BROWSING_FEED, screen: Boolean = true) = GovernorSignals(now, ctx, screen)
    private fun screenText(now: Long, anchor: String = "“所有成功的人都早起”") = TopicCandidate("c-$now", TopicSource.SCREEN_TEXT, anchor, 1, now)
    private fun thread(now: Long, imp: Int = 2) = TopicCandidate("t", TopicSource.THREAD_FOLLOW_UP, "上次说的室友矛盾", imp, now)

    @Test fun defaultRateIsAboutTwentyPerHour() {
        val g = ProactiveGovernor(20)
        var now = 0L; var spoken = 0
        while (now < 60 * min) {
            val d = g.decide(sig(now), listOf(screenText(now)))
            if (d is GovernorDecision.Speak) { g.onShown(now); g.onUserReplied(); spoken++ }
            now += 10_000
        }
        // 间隔改为随机引擎采样后，具体次数不再固定；这里只验证量级正确。
        // 精确的分布正确性由 RandomTriggerEngineTest 用多种子统计钉住。
        assertTrue("一小时只说了 $spoken 次，20 次/小时档位不该这么沉默", spoken >= 10)
        assertTrue("一小时说了 $spoken 次，突发限流失效", spoken <= 32)
    }

    @Test fun silenceSlowsDownButDoesNotStop() {
        val g = ProactiveGovernor(20)
        var now = 0L; var spoken = 0
        while (now < 120 * min) {
            if (g.decide(sig(now), listOf(screenText(now))) is GovernorDecision.Speak) { g.onShown(now); spoken++ }
            now += 10_000
        }
        assertTrue("still talks: $spoken", spoken >= 10)   // 不回应也还会开口
        assertTrue("slower: $spoken", spoken < 40)         // 但比有回应时慢
        assertTrue(g.ignoredStreak > 0)
    }

    @Test fun hardSilenceRules() {
        val g = ProactiveGovernor(40)
        val c = listOf(screenText(0))
        assertTrue(g.decide(sig(0).copy(typing = true), c) is GovernorDecision.Silent)
        assertTrue(g.decide(sig(0).copy(inCall = true), c) is GovernorDecision.Silent)
        assertTrue(g.decide(sig(0).copy(capturingVoice = true), c) is GovernorDecision.Silent)
        assertTrue(g.decide(sig(0).copy(sleepHours = true), c) is GovernorDecision.Silent)
        assertTrue(g.decide(sig(0).copy(dismissedUntil = 10 * min), c) is GovernorDecision.Silent)
        assertTrue(g.decide(sig(0).copy(busyReason = "严格回合"), c) is GovernorDecision.Silent)
        assertTrue(g.decide(sig(0, screen = false), c) is GovernorDecision.Silent)
    }

    @Test fun doesNotTalkOverWhileWaitingForReply() {
        val g = ProactiveGovernor(40)
        assertTrue(g.decide(sig(0), listOf(screenText(0))) is GovernorDecision.Speak)
        g.onShown(0)
        assertTrue(g.decide(sig(30_000), listOf(screenText(30_000))) is GovernorDecision.Silent)
    }

    @Test fun ridingIsVoiceOnlyAndNeverUsesScreen() {
        val g = ProactiveGovernor(20)
        val d = g.decide(sig(0, UsageContext.RIDING, screen = false), listOf(screenText(0), thread(0)))
        d as GovernorDecision.Speak
        assertTrue(d.voiceOnly)
        assertEquals(TopicSource.THREAD_FOLLOW_UP, d.candidate.source)
        val onlyScreen = ProactiveGovernor(20).decide(sig(0, UsageContext.RIDING, screen = false), listOf(screenText(0)))
        assertTrue(onlyScreen is GovernorDecision.Silent)
    }

    @Test fun readingPrefersCurrentTextOverOldThread() {
        val d = ProactiveGovernor().decide(sig(0, UsageContext.READING), listOf(thread(0), screenText(0))) as GovernorDecision.Speak
        assertEquals(TopicSource.SCREEN_TEXT, d.candidate.source)
    }

    @Test fun staleScreenAndAnchorlessAndRepeatsAreSkipped() {
        val g = ProactiveGovernor()
        val now = 10 * min
        val stale = screenText(now - 3 * min)
        val empty = TopicCandidate("e", TopicSource.SCREEN_TEXT, "", 1, now)
        val repeat = screenText(now).copy(tooSimilarToRecent = true)
        assertTrue(g.decide(sig(now), listOf(stale, empty, repeat)) is GovernorDecision.Silent)
    }

    @Test fun shownOnlyCountsWhenConfirmed() {
        val g = ProactiveGovernor(20)
        assertTrue(g.decide(sig(0), listOf(screenText(0))) is GovernorDecision.Speak)
        // 显示失败：不调用 onShown，下一次立刻可以再试，也不进“等回应”。
        assertTrue(g.decide(sig(1_000), listOf(screenText(1_000))) is GovernorDecision.Speak)
        assertEquals(0, g.shownLastHour(1_000))
    }

    @Test fun importantMattersComeSooner() {
        // 间隔改为随机采样后，"100 秒一定说出"不再成立。
        // 但这个测试真正要钉的意图仍然存在：**重要的事硬下限更短**。
        // 现在直接验证这条规则本身（minGapFor），而不是依赖随机结果。
        val g = ProactiveGovernor(20)
        val normalMin = g.minGapForForTest(1.0)
        val importantMin = g.minGapForForTest(0.5)
        assertTrue(
            "重要的事应当允许更短的硬下限（normal=$normalMin, important=$importantMin）",
            importantMin < normalMin,
        )
        assertTrue("硬下限不许低于 22.5 秒，否则会变成刷屏", importantMin >= 22_000L)

        // 同时保证：刚说完话的瞬间，无论多重要都不许立刻再说（minGap 仍是硬约束）。
        val g2 = ProactiveGovernor(10)
        g2.decide(sig(0), listOf(thread(0, 3)))
        g2.onShown(0)
        g2.onUserReplied()
        assertTrue(
            "刚开口 1 秒内不该再说第二句",
            g2.decide(sig(1_000), listOf(thread(1_000, 3))) is GovernorDecision.Silent,
        )
    }
}

class ReplyContractTest {
    private val input = ReplyInput(
        anchor = "原文：自律的本质是把选择提前做好。", anchorSource = TopicSource.SCREEN_TEXT,
        memories = mapOf("f1" to "他说过晚上总是刷手机到两点"), proactive = true,
    )

    @Test fun goodReplyPasses() {
        val raw = "好的：```json\n{\"say\":\"“把选择提前做好”——你晚上刷到两点，是不是因为睡前根本没提前决定几点放下手机？\",\"anchor\":\"把选择提前做好\",\"memoryRefs\":[\"f1\"],\"newThread\":null,\"memoryProposal\":\"\"}\n```"
        val r = ReplyContract.parse(raw)!!
        assertEquals(emptyList<ReplyViolation>(), ReplyCheck.violations(r, input))
    }

    @Test fun rejectsWhatHeHates() {
        fun v(say: String, anchor: String = "把选择提前做好", refs: List<String> = emptyList()) =
            ReplyCheck.violations(PartnerReply(say, anchor, refs), input)
        assertTrue(ReplyViolation.SUMMARY_OPENER in v("总的来说，这段讲的是自律"))
        assertTrue(ReplyViolation.FLATTERY_OPENER in v("你说得对，自律很重要"))
        assertTrue(ReplyViolation.TOO_MANY_QUESTIONS in v("你怎么看？你会这样做吗？"))
        assertTrue(ReplyViolation.ANCHOR_NOT_FOUND in v("自律需要毅力", anchor = "需要坚强的毅力"))
        assertTrue(ReplyViolation.UNKNOWN_MEMORY_REF in v("你以前说过……", refs = listOf("f9")))
        assertTrue(ReplyViolation.UNASKED_ADVICE_LIST in v("建议：\n1. 早睡\n2. 关手机"))
    }

    @Test fun voiceModeIsShort() {
        val voice = input.copy(voiceOnly = true)
        val long = PartnerReply("把选择提前做好".repeat(12), "把选择提前做好", emptyList())
        assertTrue(ReplyViolation.TOO_LONG in ReplyCheck.violations(long, voice))
        assertTrue(ReplyContract.systemRules(voice).contains("骑车"))
    }

    @Test fun anchorMayComeFromCitedMemory() {
        val r = PartnerReply("你说过刷到两点，今天呢？", "晚上总是刷手机到两点", listOf("f1"))
        assertTrue(ReplyCheck.ok(r, input))
    }

    @Test fun parseExtractsThreadAndProposal() {
        val r = ReplyContract.parse("{\"say\":\"嗯\",\"anchor\":\"x\",\"memoryRefs\":[],\"newThread\":{\"title\":\"想早睡\",\"importance\":3},\"memoryProposal\":\"他的长期目标是早睡\"}")!!
        assertEquals("想早睡", r.newThreadTitle)
        assertEquals(3, r.newThreadImportance)
        assertEquals("他的长期目标是早睡", r.memoryProposal)
        assertNull(ReplyContract.parse("不是 JSON"))
    }

    @Test fun smallTalkNeedsNoAnchor() {
        val chat = ReplyInput("", TopicSource.SMALL_TALK, proactive = true)
        assertTrue(ReplyCheck.ok(PartnerReply("今天风大吗？", "", emptyList()), chat))
    }
}

class VisionTalkTest {
    private fun s(conf: String, vararg el: String) = VisionSummary("B站视频：如何戒掉拖延", el.toList(), listOf("右下角小字"), "", "他在看第 3 分钟的“两分钟法则”", conf, "")

    @Test fun promptCarriesHisQuestion() {
        val p = VisionTalk.prompt("这个图表的结论站得住吗", "在聊拖延")
        assertTrue(p.contains("这个图表的结论站得住吗"))
        assertTrue(p.contains("在聊拖延"))
    }

    @Test fun concreteSummaryBecomesCandidate() {
        val c = VisionTalk.toCandidate("v1", s("high", "字幕：先做两分钟就好", "进度条 3:12"), 0)!!
        assertEquals(TopicSource.SCREEN_IMAGE, c.source)
        assertTrue(c.anchor.contains("先做两分钟就好"))
    }

    @Test fun vagueOrLowConfidenceIsNotUsed() {
        assertNull(VisionTalk.toCandidate("v", s("low", "字幕：先做两分钟就好"), 0))
        assertNull(VisionTalk.toCandidate("v", s("high", "按钮", "图标"), 0))
    }

    @Test fun displayShowsMoreThanSubject() {
        val t = VisionTalk.displayText(s("high", "字幕：先做两分钟就好"))
        assertTrue(t.contains("先做两分钟就好"))
        assertTrue(t.contains("我想聊的"))
        assertTrue(t.contains("看不清"))
    }
}
