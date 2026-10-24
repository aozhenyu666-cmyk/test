package cn.companion.pet.core.companion

import cn.companion.pet.core.text.UnicodeText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import cn.companion.pet.core.memory.MemoryStore
import cn.companion.pet.core.memory.NightlyStore
import cn.companion.pet.core.memory.NightlyOutput
import cn.companion.pet.core.memory.NightlyCommitOutcome

class MaterialBoundaryRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneId.of("UTC"))
    private val now = clock.millis()
    private val events by lazy { EventLog(tmp.root, clock) }
    private val topics by lazy { TopicQueue(tmp.root, clock, events) }
    private val inbox by lazy { MaterialInbox(tmp.root, events, clock, topics) }

    @Test fun shorterTranscriptCannotRelabelAnUnrelatedLongExcerpt() {
        val first = inbox.save(MaterialDraft(url = "https://example.com/video", body = "旧摘录".repeat(40))) as MaterialSaveResult.Created
        val updated = inbox.save(MaterialDraft(url = first.material.url, body = "真正的字幕", contentType = MaterialContentType.TRANSCRIPT)) as MaterialSaveResult.AlreadySaved
        assertEquals(MaterialContentType.TRANSCRIPT, updated.material.contentType)
        assertEquals("真正的字幕", updated.material.body)
    }

    @Test fun readingAfterExpiryInvalidatesStillLiveDerivedTopic() {
        val item = (inbox.save(MaterialDraft(body = "合成材料")) as MaterialSaveResult.Created).material
        topics.enqueue("合成派生话题", listOf(item.sourceEventId), ttlMillis = 30L * 24 * 60 * 60 * 1000, now = now)
        val later = now + MaterialInbox.TTL_MILLIS + 1
        assertTrue(inbox.all(later).isEmpty())
        assertTrue(topics.candidates(later).isEmpty())
    }

    @Test fun linkOnlyEvidenceWarningSurvivesLongUrlAndSmallContextBudget() {
        inbox.save(MaterialDraft(url = "https://example.com/" + "a".repeat(1200)))
        assertTrue(inbox.contextBlock(now).contains("不能假装知道"))
    }

    @Test fun codePointLimitDoesNotCutValidEmojiTextInHalf() {
        val body = "文" + "😀".repeat(2500)
        val item = (inbox.save(MaterialDraft(body = body)) as MaterialSaveResult.Created).material
        assertTrue(UnicodeText.count(body) < MaterialInbox.MAX_BODY)
        assertEquals(body, item.body)
    }

    @Test fun failedForgetAppendDoesNotAlreadyRemoveMaterial() {
        val item = (inbox.save(MaterialDraft(body = "合成材料")) as MaterialSaveResult.Created).material
        val monthly = File(events.eventsDir, "events-2026-10.jsonl")
        val backup = File(tmp.root, "event-backup.jsonl")
        assertTrue(monthly.renameTo(backup));assertTrue(monthly.mkdir())
        try {
            assertTrue(runCatching { inbox.delete(item.id) }.isFailure)
        } finally { monthly.delete();assertTrue(backup.renameTo(monthly)) }
        assertEquals("A failed deletion must not partially apply before its forget event", 1, inbox.all().size)
    }

    @Test fun sourceLifetimeInvalidatesDerivedTopicEvenBeforePurge() {
        val item = (inbox.save(MaterialDraft(body = "合成材料")) as MaterialSaveResult.Created).material
        topics.enqueue("合成长期话题", listOf(item.sourceEventId), ttlMillis = 30L * 24 * 60 * 60 * 1000, now = now)
        assertTrue(topics.candidates(now + MaterialInbox.TTL_MILLIS + 1).isEmpty())
        assertFalse(events.all().any { it.kind == EventKind.FORGET })
    }

    @Test fun corruptMaterialStorageIsNeverReplacedByAnEmptyDatabase() {
        inbox.save(MaterialDraft(body = "合成材料"))
        val file = File(tmp.root, "materials.json");file.writeText("{broken")
        assertTrue(inbox.save(MaterialDraft(body = "另一条合成材料")) is MaterialSaveResult.Rejected)
        assertEquals("{broken", file.readText());assertTrue(inbox.all().isEmpty());assertFalse(inbox.storageReadable())
        assertEquals(1, events.all().count { it.kind == EventKind.MATERIAL })
    }

    @Test fun activeMaterialVersionChangesOnEditAndDelete() {
        val empty = inbox.versionHash()
        val item = (inbox.save(MaterialDraft(url = "https://example.com/v", body = "合成材料")) as MaterialSaveResult.Created).material
        val first = inbox.versionHash();assertNotEquals(empty, first)
        inbox.save(MaterialDraft(url = item.url, body = "合成改文"))
        assertNotEquals(first, inbox.versionHash())
        inbox.delete(item.id);assertEquals(empty, inbox.versionHash())
    }

    @Test fun materialWithdrawnDuringNightlyRequestRejectsEveryOutput() {
        val item = (inbox.save(MaterialDraft(body = "合成材料")) as MaterialSaveResult.Created).material
        val memory = MemoryStore(tmp.root);val nightly = NightlyStore(memory, events, clock)
        val day = LocalDate.of(2026, 10, 24);val batch = nightly.prepare(day)!!.batch
        inbox.delete(item.id)
        assertEquals(NightlyCommitOutcome.CONFLICT, nightly.commit(batch, NightlyOutput(emptyList(), "旧材料的转述", "旧材料的信")))
        assertNull(memory.diary(day));assertNull(memory.letter(day))
    }

    @Test fun materialWithdrawnAfterJournalCreationCannotReturnOnRecovery() {
        val item = (inbox.save(MaterialDraft(body = "合成材料")) as MaterialSaveResult.Created).material
        val memory = MemoryStore(tmp.root);val nightly = NightlyStore(memory, events, clock)
        val day = LocalDate.of(2026, 10, 24);val batch = nightly.prepare(day)!!.batch
        nightly.fault = { if (it == "journal-written") { inbox.delete(item.id);error("synthetic interruption") } }
        assertTrue(runCatching { nightly.commit(batch, NightlyOutput(emptyList(), "合成日记", "合成信")) }.isFailure)
        assertTrue(NightlyStore(memory, events, clock).recover().isEmpty())
        assertNull(memory.diary(day));assertNull(memory.letter(day))
    }

    @Test fun diaryFromWithdrawnSourceIsNotFedBackToConversation() {
        val item = (inbox.save(MaterialDraft(body = "合成材料")) as MaterialSaveResult.Created).material
        val memory = MemoryStore(tmp.root);val nightly = NightlyStore(memory, events, clock)
        val day = LocalDate.of(2026, 10, 24);val batch = nightly.prepare(day)!!.batch
        assertEquals(NightlyCommitOutcome.COMMITTED, nightly.commit(batch, NightlyOutput(emptyList(), "合成材料的转述", "合成信")))
        inbox.delete(item.id)
        assertEquals("", nightly.diaryForContext(day))
        assertEquals("", nightly.diarySummary(day.plusDays(1)))
    }
}
