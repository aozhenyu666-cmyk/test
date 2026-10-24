package cn.companion.pet.core.memory

import cn.companion.pet.core.companion.*
import cn.companion.pet.core.probe.ProbeMoment
import cn.companion.pet.core.scene.Scene
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MemoryEditsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneId.of("UTC"))
    private val day = LocalDate.of(2026, 10, 23)
    private val memory by lazy { MemoryStore(tmp.root) }
    private val events by lazy { EventLog(tmp.root, clock) }
    private val facts by lazy { FactStore(tmp.root, clock) }
    private val edits by lazy { MemoryEdits(memory, facts, events) }

    private fun fact(text: String = "喜欢猫"): Fact {
        val source = events.append(EventKind.USER_INPUT, text)
        memory.saveProfileByUser("[他说过] $text")
        return facts.recordSaid(text, listOf(source.id))!!
    }

    @Test fun correctionUpdatesBothStoresAndSurvivesNightlyOldSummary() {
        val old = fact()
        val corrected = edits.correct(old.id, "更喜欢狗")!!
        assertEquals("[他说过] 更喜欢狗", memory.profile())
        val nightly = NightlyStore(memory, events, clock, facts = facts)
        val prepared = nightly.prepare(day)!!
        assertEquals(NightlyCommitOutcome.COMMITTED,
            nightly.commit(prepared.batch, NightlyOutput(listOf("[他说过] 喜欢猫"), "今天聊天", "明天见")))
        assertEquals(corrected.id, facts.live().single().id)
        assertEquals("[他说过] 更喜欢狗", memory.profile())
    }

    @Test fun confirmationUpdatesProfileWithoutTombstoningTheSameFact() {
        memory.saveProfileByUser("[我推测] 喜欢猫")
        val guessed = facts.recordGuess("喜欢猫")!!
        edits.confirm(guessed.id)
        assertEquals("[他说过] 喜欢猫", memory.profile())
        assertTrue(memory.tombstones().isEmpty())
        assertEquals(Confidence.SAID, facts.live().single().confidence)
    }

    @Test fun deletingProfileAlsoForgetsStructuredFactAndItsParaphrasedSource() {
        val original = fact()
        val derived = events.append(EventKind.MATERIAL_SUMMARY, "他偏爱猫科动物", sourceIds = original.sourceEventIds)
        edits.deleteProfileLine("[他说过] 喜欢猫")
        assertTrue(facts.live().isEmpty())
        assertEquals("", memory.profile())
        assertFalse(EventVisibility(events.all(), memory.tombstones()).allows(derived))
    }

    @Test fun forgettingCorrectedFactInvalidatesEveryVersionAndDerivedTopic() {
        val old = fact()
        val derived = events.append(EventKind.MATERIAL_SUMMARY, "偏爱毛茸茸的小动物", sourceIds = old.sourceEventIds)
        val queue = TopicQueue(tmp.root, clock, events)
        queue.enqueue("想接着聊收养宠物", listOf(derived.id))
        val corrected = edits.correct(old.id, "更喜欢狗")!!
        assertTrue(edits.forget(corrected.id))
        assertTrue(memory.tombstones().containsAll(listOf("喜欢猫", "更喜欢狗")))
        assertTrue(queue.candidates().isEmpty())
        assertTrue(MemoryRecall(events, facts).recall("猫狗", clock.millis(), memory.tombstones()).isEmpty())
        assertFalse(edits.forget(corrected.id))
    }

    @Test fun negatedCorrectionDoesNotDisappearBecauseItContainsTheOldText() {
        val old = fact()
        edits.correct(old.id, "不喜欢猫")
        val recalled = MemoryRecall(events, facts).recall("猫", clock.millis(), memory.tombstones())
        assertTrue(recalled.any { it.text == "不喜欢猫" })
        assertFalse(recalled.any { it.text == "喜欢猫" })
    }

    @Test fun currentQuestionOverridesOldTopicsForRecall() {
        facts.recordSaid("喜欢猫")
        facts.recordSaid("讨厌香菜")
        events.append(EventKind.USER_INPUT, "香菜")
        val messages = CompanionContextAssembler(events, facts).assemble("合成角色",
            ProbeMoment("12:00", Scene.NORMAL, "桌面", "", null), null, clock.millis(), userQuery = "猫")
        val text = messages.last().content
        assertTrue(text.contains("喜欢猫"))
        assertFalse(text.contains("讨厌香菜"))
    }

    @Test fun entireProfileEditKeepsFactsInSyncAndTagPromotionIsNotDeletion() {
        val old = fact()
        edits.saveProfile("[他说过] 喜欢狗")
        assertTrue(facts.find(old.id)!!.deleted)
        assertEquals(listOf("喜欢狗"), facts.live().map { it.text })
        edits.saveProfile("[我推测] 喜欢狗")
        assertFalse("A tag edit must not mark its unchanged content forgotten", "喜欢狗" in memory.tombstones())
        assertEquals(Confidence.GUESSED, facts.live().single().confidence)
    }

    @Test fun staleFactRowCannotForkAnAlreadySupersededVersion() {
        val old = fact()
        val corrected = edits.correct(old.id, "喜欢狗")!!
        assertNull(edits.correct(old.id, "喜欢鸟"))
        assertNull(facts.correct(old.id, "喜欢鸟"))
        assertNull(facts.confirm(old.id))
        assertEquals(corrected.id, facts.live().single().id)
    }

    @Test fun missingTopicSourceAfterClearingDayCannotBeShared() {
        val original = fact()
        val queue = TopicQueue(tmp.root, clock, events)
        queue.enqueue("以后聊收养宠物", original.sourceEventIds)
        assertEquals(1, queue.candidates().size)
        events.clearDay(day)
        assertTrue(queue.candidates().isEmpty())
        assertEquals("Clearing a day does not mean forgetting a long-term fact", 1, facts.live().size)
    }

    @Test fun identicalUserSaidRecordPromotesGuessInsteadOfKeepingUnconfirmed() {
        val guessed = facts.recordGuess("喜欢猫")!!
        val confirmed = facts.recordSaid("喜欢猫")!!
        assertEquals(guessed.id, confirmed.id)
        assertEquals(Confidence.SAID, confirmed.confidence)
        assertTrue(confirmed.version > guessed.version)
    }
}
