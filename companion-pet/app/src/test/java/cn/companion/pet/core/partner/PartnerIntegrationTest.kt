package cn.companion.pet.core.partner

import cn.companion.pet.core.companion.*
import cn.companion.pet.core.life.*
import cn.companion.pet.core.memory.*
import cn.companion.pet.core.voice.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PartnerIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val input = ReplyInput("我打算每天走两公里", TopicSource.THREAD_FOLLOW_UP, proactive = false)
    private val valid = """{"say":"每天走两公里，你准备从哪段路开始？","anchor":"每天走两公里","memoryRefs":[]}"""

    @Test fun validResponseUsesTheSameContractWithoutRetry() = runBlocking {
        var calls = 0
        val result = PartnerResponder().respond(input, "同桌", listOf("他：我打算每天走两公里")) { messages ->
            calls++
            assertTrue(messages.first().content.contains("只返回 JSON"))
            assertTrue(messages.last().content.contains("每天走两公里"))
            valid
        }
        assertEquals(1, calls)
        assertEquals("每天走两公里", result!!.anchorQuote)
    }

    @Test fun invalidAnchorIsRepairedOnceWithTheActualViolation() = runBlocking {
        var calls = 0
        val result = PartnerResponder().respond(input, "同桌", emptyList()) { messages ->
            calls++
            if (calls == 1) """{"say":"你准备跑马拉松吗？","anchor":"马拉松","memoryRefs":[]}"""
            else { assertTrue(messages.last().content.contains("anchor 必须照抄")); valid }
        }
        assertNotNull(result)
        assertEquals(2, calls)
    }

    @Test fun repeatedInvalidOutputIsNotDelivered() = runBlocking {
        var calls = 0
        val result = PartnerResponder().respond(input, "同桌", emptyList()) {
            calls++
            """{"say":"你说得对，总的来说很好。","anchor":"每天走两公里","memoryRefs":["invented"]}"""
        }
        assertNull(result)
        assertEquals(2, calls)
    }

    @Test fun networkFailureDoesNotTriggerAnExtraRewrite() = runBlocking {
        var calls = 0
        assertNull(PartnerResponder().respond(input, "同桌", emptyList()) { calls++; null })
        assertEquals(1, calls)
    }

    @Test fun ridingReplyCannotSlipPastTheVoiceLimit() = runBlocking {
        var calls = 0
        val result = PartnerResponder().respond(input.copy(voiceOnly = true), "同桌", emptyList()) {
            calls++
            if (calls == 1) """{"say":"${"很长".repeat(50)}","anchor":"每天走两公里","memoryRefs":[]}""" else valid
        }
        assertNotNull(result)
        assertEquals(2, calls)
        assertTrue(result!!.say.length <= ReplyContract.MAX_CHARS_VOICE)
    }

    @Test fun failedDisplayLeavesIntentAndGovernorUntouchedThenSuccessfulReceiptCommitsOnce() {
        val queue = VoiceIntentQueue()
        val intent = queue.enqueue("own_life", "读到一个关于散步的搜索摘要", now = 0)!!
        val governor = ProactiveGovernor()
        val ledger = SayingLedger()
        val delivery = PartnerDelivery(governor, queue, ledger)
        val candidate = TopicCandidate(intent.id, TopicSource.OWN_READING, intent.summary, observedAt = 0)
        assertFalse(delivery.confirm("r1", candidate, "散步这件事我想接着聊。", 0, false))
        assertNull(queue.all().single().spokenAt)
        assertEquals(0, governor.shownLastHour(0))
        assertTrue(delivery.confirm("r1", candidate, "散步这件事我想接着聊。", 0, true))
        assertFalse(delivery.confirm("r1", candidate, "重复回执", 1, true))
        assertEquals(1, governor.shownLastHour(1))
        assertEquals(0L, queue.all().single().spokenAt)
        assertTrue(ledger.recent(5).any { it.contains("散步这件事我想接着聊") })
        assertFalse(ledger.recent(5).any { it == intent.summary })
    }

    @Test fun changingHourlyTargetPreservesRecentReceipts() {
        val governor = ProactiveGovernor(20)
        governor.onShown(0)
        governor.configureTarget(40)
        assertEquals(1, governor.shownLastHour(1))
        assertTrue(governor.decide(GovernorSignals(1, UsageContext.IDLE, true), listOf(TopicCandidate("x", TopicSource.SMALL_TALK, "", observedAt = 1))) is GovernorDecision.Silent)
    }

    @Test fun tenRoundSessionSurvivesCompactionStorageAndTwentyMinuteAbsence() {
        val rules = DialogueRules()
        val store = DialogueStore(tmp.root)
        var session = rules.start("same-session", 0)
        repeat(10) { i ->
            session = rules.add(session, DialogueTurn(Speaker.USER, "第 $i 轮：我想讨论散步", i * 1000L, TurnChannel.TYPED))
            session = rules.add(session, DialogueTurn(Speaker.PET, "接着第 $i 轮聊", i * 1000L + 1, TurnChannel.REPLY))
        }
        assertEquals(10, session.userTurns)
        assertTrue(rules.needsCompaction(session))
        session = rules.applyCompaction(session, "最初讨论了散步计划，用户希望每天走两公里。")
        session = rules.away(session, 10_000)
        store.save(session)
        val restored = store.load()!!
        assertTrue(rules.canResume(restored, 10_000 + 20 * 60_000))
        val resumed = rules.resume(restored, 10_000 + 20 * 60_000)
        assertEquals("same-session", resumed.id)
        assertEquals(16, resumed.turns.size)
        assertTrue(rules.contextLines(resumed).first().contains("每天走两公里"))
        assertEquals(SessionState.CLOSED, rules.tick(resumed, resumed.lastActivityAt + 31 * 60_000).state)
    }

    @Test fun forgettingAConfirmedFactClosesItsPersistedFollowUp() {
        val memory = MemoryStore(tmp.root)
        val events = EventLog(tmp.root)
        val facts = FactStore(tmp.root)
        val book = ThreadBook()
        val user = events.append(EventKind.USER_INPUT, "我在为换工作烦心", source = "partner")
        val fact = facts.recordSaid(user.text, listOf(user.id))!!
        book.start("work", ThreadKind.OPEN_PROBLEM, "换工作", 3, 0, listOf(fact.id))
        val edits = MemoryEdits(memory, facts, events, onForgotten = { book.onFactForgotten(it) })
        assertTrue(edits.forget(fact.id))
        ThreadBookStore(tmp.root).save(book)
        assertTrue(ThreadBookStore(tmp.root).load().due(100 * 3_600_000L).isEmpty())
        assertFalse(EventVisibility(events.all(), memory.tombstones()).allows(facts.find(fact.id)!!))
    }

    @Test fun nightlyCannotTurnUnconfirmedPartnerHistoryIntoAProfile() {
        val clock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC)
        val memory = MemoryStore(tmp.root)
        val events = EventLog(tmp.root, clock)
        val facts = FactStore(tmp.root, clock)
        events.append(EventKind.USER_INPUT, "也许我想换工作，但还没想好", source = "partner")
        val nightly = NightlyStore(memory, events, clock = clock, facts = facts)
        val batch = nightly.prepare(LocalDate.parse("2026-10-28"))!!.batch
        assertEquals(NightlyCommitOutcome.COMMITTED, nightly.commit(batch, NightlyOutput(listOf("[他说过] 已决定换工作"), "今天聊到工作。", "等你想好再聊。")))
        assertTrue(facts.live().isEmpty())
        assertFalse(memory.profile().contains("已决定换工作"))
    }

    @Test fun oneSecondTicksAccumulateReadingWithoutOneWritePerTick() {
        var state = OwnLifeState(activities = listOf(OwnActivity(id = "a", title = "摘录", sourceUrl = "https://example.com", totalHint = 4)), lastAdvancedAt = 0)
        var changes = 0
        for (second in 1..3600) {
            val next = OwnLifePlanner.advance(state, second * 1000L)
            if (next != state) changes++
            state = next
        }
        assertEquals(2, state.activities.single().progress)
        assertEquals(2, changes)
    }

    @Test fun partialReadingTimeCarriesOverAndUnknownLengthDoesNotBlockNextSearchForever() {
        val activity = OwnActivity(id = "a", title = "搜索摘要", sourceUrl = "https://example.com", sourceExcerpt = "散步有助于思考", totalHint = null, startedAt = 0)
        var state = OwnLifeState(activities = listOf(activity), lastAdvancedAt = 0)
        state = OwnLifePlanner.advance(state, 31 * 60_000)
        assertEquals(30 * 60_000L, state.lastAdvancedAt)
        state = OwnLifePlanner.advance(state, 60 * 60_000)
        assertEquals(2, state.activities.single().progress)
        assertTrue(ReadingEvidence.blocksNextSearch(activity, 30 * 60_000))
        assertFalse(ReadingEvidence.blocksNextSearch(activity, 46 * 60_000))
        assertTrue(ReadingEvidence.anchor(activity).contains("尚未读取文章正文"))
        assertTrue(ReadingEvidence.anchor(activity).contains("散步有助于思考"))
    }
    @Test fun fullScreenObservationsAreNotPersistedAsChatHistory() {
        val rules = DialogueRules()
        var session = rules.start("screen", 0)
        session = rules.add(session, DialogueTurn(Speaker.PET, "整份画面观察：第一行、第二行、第三行", 1, TurnChannel.REPLY, ephemeral = true))
        session = rules.add(session, DialogueTurn(Speaker.USER, "我们就讨论第一行", 2, TurnChannel.TYPED))
        val store = DialogueStore(tmp.root)
        store.save(session)
        val restored = store.load()!!
        assertEquals(1, restored.turns.size)
        assertEquals("我们就讨论第一行", restored.turns.single().text)
        assertFalse(java.io.File(tmp.root, "partner/dialogue.json").readText().contains("第二行"))
    }

    @Test fun persistedFollowUpRecoversItsReplyWindowAndThenBacksOff() {
        val book = ThreadBook()
        val thread = book.start("pending", ThreadKind.OPEN_PROBLEM, "工作的烦心事", 3, 0)
        val shownAt = thread.nextFollowUpAt
        book.onPetFollowUpShown(thread.id, shownAt)
        val store = ThreadBookStore(tmp.root)
        store.save(book)
        val restored = store.load()
        assertEquals(thread.id, restored.awaitingReply()!!.id)
        assertEquals(shownAt, restored.awaitingReply()!!.lastPetTurnAt)
        restored.onFollowUpIgnored(thread.id, shownAt + 90_000)
        store.save(restored)
        assertNull(store.load().awaitingReply())
        assertTrue(store.load().get(thread.id)!!.nextFollowUpAt > shownAt + 90_000)
    }

}
