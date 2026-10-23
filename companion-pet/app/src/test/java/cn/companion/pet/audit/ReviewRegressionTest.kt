package cn.companion.pet.audit

import cn.companion.pet.core.companion.*
import cn.companion.pet.core.memory.*
import cn.companion.pet.core.probe.ProbeMoment
import cn.companion.pet.core.scene.Scene
import cn.companion.pet.core.rhythm.SpeakScheduler
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Independent expected-behaviour regressions. All input is synthetic. */
class ReviewRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), zone)
    private val day = LocalDate.of(2026, 10, 23)
    private val text = "喜欢合成测试猫"

    @Test fun appendAfterTruncatedTailPreservesNextCompleteEvent() {
        val log = EventLog(tmp.root, clock, zone)
        val first = log.append(EventKind.USER_INPUT, "完整事件一")
        File(log.eventsDir, "events-2026-10.jsonl").appendText("{\"id\":\"broken\",\"text\":")
        val restarted = EventLog(tmp.root, clock, zone)
        val next = restarted.append(EventKind.USER_INPUT, "完整事件二")
        assertEquals("A truncated prior write must not swallow the next successful append",
            setOf(first.id, next.id), restarted.all().map { it.id }.toSet())
    }

    @Test fun userCorrectionSurvivesNextProfileSync() {
        val memory = MemoryStore(tmp.root)
        val facts = FactStore(tmp.root, clock, zone)
        memory.saveProfileByUser("[他说过] $text")
        facts.syncFromProfile(memory.profileLines(), listOf("synthetic-source"), emptySet())
        val corrected = facts.correct(facts.live().single().id, "现在喜欢合成测试狗")!!
        // This is the unchanged profile consumed by NightlyStore after MemoryTab.onCorrect.
        facts.syncFromProfile(memory.profileLines(), listOf("synthetic-next-batch"), memory.tombstones())
        assertEquals("Explicit correction must remain authoritative after nightly profile sync",
            corrected.id, facts.live().single().id)
    }

    private fun forgetLikeMemoryTab(memory: MemoryStore, facts: FactStore, events: EventLog) {
        val fact = facts.live().single()
        facts.forget(fact.id)
        memory.addDeletedText(fact.text)
        events.append(EventKind.FORGET, fact.text, source = "user")
    }

    @Test fun forgottenFactDoesNotReenterRecentContext() {
        val memory = MemoryStore(tmp.root)
        val events = EventLog(tmp.root, clock, zone)
        val facts = FactStore(tmp.root, clock, zone)
        val source = events.append(EventKind.USER_INPUT, text)
        facts.recordSaid(text, listOf(source.id))
        events.append(EventKind.EXCHANGE, "- 12:00 | 用户：$text | 回复：收到", sourceIds = listOf(source.id))
        forgetLikeMemoryTab(memory, facts, events)
        val messages = CompanionContextAssembler(events, facts, zone).assemble(
            "合成角色", ProbeMoment("12:00", Scene.NORMAL, "桌面", "", null), null,
            clock.millis(), memory.tombstones())
        assertFalse("Forgotten source must not be sent through recent exchanges",
            messages.joinToString("\n") { it.content }.contains(text))
    }

    @Test fun forgottenFactDoesNotReenterNightlyExchangeSection() {
        val memory = MemoryStore(tmp.root)
        val events = EventLog(tmp.root, clock, zone)
        val facts = FactStore(tmp.root, clock, zone)
        val source = events.append(EventKind.USER_INPUT, text)
        facts.recordSaid(text, listOf(source.id))
        events.append(EventKind.EXCHANGE, "- 12:00 | 用户：$text | 回复：收到", sourceIds = listOf(source.id))
        forgetLikeMemoryTab(memory, facts, events)
        val prepared = NightlyStore(memory, events, clock, zone, facts).prepare(day)
        val exchangeSection = prepared?.messages?.last()?.content?.substringAfter("# 今天的交流记录\n").orEmpty()
        assertFalse("Formatted exchange containing forgotten source is still being summarized", exchangeSection.contains(text))
    }

    @Test fun recoveryHonorsForgetAfterJournalWasWritten() {
        val memory = MemoryStore(tmp.root)
        val events = EventLog(tmp.root, clock, zone)
        val facts = FactStore(tmp.root, clock, zone)
        val source = events.append(EventKind.USER_INPUT, text)
        facts.recordSaid(text, listOf(source.id))
        val nightly = NightlyStore(memory, events, clock, zone, facts)
        val batch = nightly.prepare(day)!!.batch
        nightly.fault = {
            if (it == "journal-written") {
                // The UI can forget after journal creation, before this commit finishes.
                forgetLikeMemoryTab(memory, facts, events)
                throw IllegalStateException("synthetic process crash after user forget")
            }
        }
        assertTrue(runCatching { nightly.commit(batch, NightlyOutput(listOf("[他说过] $text"), "合成日记", "合成信")) }.isFailure)
        NightlyStore(memory, events, clock, zone, facts).recover()
        assertFalse("Recovery must not restore an already-forgotten fact into profile.md", memory.profile().contains(text))
    }

    @Test fun forgottenSourceIsNotAnEligibleTopic() {
        val memory = MemoryStore(tmp.root)
        val events = EventLog(tmp.root, clock, zone)
        val facts = FactStore(tmp.root, clock, zone)
        val topics = TopicQueue(tmp.root, clock)
        val source = events.append(EventKind.NOTE, text)
        facts.recordSaid(text, listOf(source.id))
        topics.enqueue(text, listOf(source.id))
        forgetLikeMemoryTab(memory, facts, events)
        assertNull("A topic sourced solely from forgotten content must become ineligible",
            TopicGate(topics).next(clock.millis(), quiet = false, userActive = true, lastProactiveAt = 0L))
    }

    @Test fun eventAppliesToDecayedStateAfterRestart() {
        val state = PetStateStore(tmp.root, clock)
        val start = clock.millis()
        state.apply(StateEvent.USER_REPLIED, start)
        val restarted = PetStateStore(tmp.root, clock)
        val later = start + 24L * 60 * 60 * 1000
        val expected = restarted.current(later).closeness + 0.06
        assertEquals("A new event must not undo the elapsed 24-hour decay",
            expected, restarted.apply(StateEvent.USER_REPLIED, later).closeness, 1e-9)
    }

    @Test fun userConfirmationBetweenVersionCheckAndJournalSnapshotIsNotOverwritten() {
        val memory = MemoryStore(tmp.root)
        val events = EventLog(tmp.root, clock, zone)
        val guessed = "[我推测] $text"
        memory.saveProfileByUser(guessed)
        events.append(EventKind.EXCHANGE, "合成交流")
        val nightly = NightlyStore(memory, events, clock, zone)
        val batch = nightly.prepare(day)!!.batch
        var confirmed = false
        // Deterministic interleaving: output iteration happens after the version check,
        // before target() captures beforeHash. Simulate the real UI confirm operation here.
        val outputLines = object : AbstractList<String>() {
            override val size: Int = 1
            override fun get(index: Int): String {
                if (!confirmed) {
                    memory.confirmProfileLine(guessed)
                    confirmed = true
                }
                return guessed
            }
        }
        val outcome = nightly.commit(batch, NightlyOutput(outputLines, "合成日记", "合成信"))
        assertTrue(confirmed)
        assertEquals("User confirmation was overwritten; commit outcome=" + outcome,
            "[他说过] $text", memory.profile())
    }

    @Test fun correctionWithoutReplayWorks_control() {
        val facts = FactStore(tmp.root, clock, zone)
        val initial = facts.recordSaid(text)!!
        val correction = facts.correct(initial.id, "喜欢合成测试狗")!!
        assertEquals(correction.id, facts.live().single().id)
    }

    @Test fun schedulerAndTopicGateDoNotStarveQueuedTopic() {
        val queue = TopicQueue(tmp.root, clock)
        val gate = TopicGate(queue)
        val scheduler = SpeakScheduler(zone = zone)
        var lastProactive = clock.millis()
        scheduler.onSpoke(lastProactive)
        queue.enqueue("合成待分享话题", listOf("synthetic-source"), now = lastProactive + 1)
        var shared = false
        // Reproduce the wiring in PetBrain.tick()/present() with real scheduler/gate:
        // even when gate returns null, a normal successful utterance resets both clocks.
        repeat(10) {
            val due = scheduler.dueAt(false)!!
            assertTrue(scheduler.isDue(due, false))
            if (gate.next(due, false, true, lastProactive) != null) shared = true
            scheduler.onSpoke(due)
            lastProactive = due
        }
        assertTrue("Ten ordinary 6-12 minute utterances must not starve a pending topic forever", shared)
    }

    @Test fun cleanAppendAfterRestartWorks_control() {
        val first = EventLog(tmp.root, clock, zone).append(EventKind.USER_INPUT, "合成一")
        val restarted = EventLog(tmp.root, clock, zone)
        val second = restarted.append(EventKind.USER_INPUT, "合成二")
        assertEquals(setOf(first.id, second.id), restarted.all().map { it.id }.toSet())
    }
}