package cn.companion.pet.core.memory

import cn.companion.pet.core.companion.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** On-disk compatibility fixtures, deliberately excluding credentials and personal content. */
class LegacyUpgradeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneId.of("UTC"))

    @Test fun preOriginFactsRemainReadableAndCanBeCorrectedAfterUpgrade() {
        val old = """[{"id":"legacy-fact","factKey":"legacy-key","text":"合成旧偏好","confidence":"said","createdAt":1792756800000,"sourceEventIds":[],"version":1,"supersededBy":null,"deleted":false}]"""
        File(tmp.root, "facts.json").writeText(old)
        val facts = FactStore(tmp.root, clock)
        assertEquals("合成旧偏好", facts.live().single().text)
        assertEquals(old, File(tmp.root, "facts.json").readText())
        val memory = MemoryStore(tmp.root)
        memory.saveProfileByUser("[他说过] 合成旧偏好")
        val corrected = MemoryEdits(memory, facts, EventLog(tmp.root, clock)).correct("legacy-fact", "合成新偏好")!!
        assertEquals(FactOrigin.USER_EDIT, corrected.origin)
        assertEquals("[他说过] 合成新偏好", memory.profile())
        assertEquals(corrected.id, FactStore(tmp.root, clock).live().single().id)
    }

    @Test fun interruptedOldTodayMigrationResumesWithoutDuplicateEventsOrLostBackup() {
        val memory = MemoryStore(tmp.root)
        val old = "# 2026-10-22\n- 合成交流一\n- 合成交流二\n"
        memory.todayFile.writeText(old)
        val events = EventLog(tmp.root, clock)
        assertEquals(2, LegacyEventImport.ensure(memory, events))
        File(tmp.root, "migrations/events-v1.done").delete() // interrupted just before the marker
        assertEquals(0, LegacyEventImport.ensure(MemoryStore(tmp.root), EventLog(tmp.root, clock)))
        assertEquals(2, events.all().size)
        assertEquals(old, File(tmp.root, "migrations/legacy-today-2026-10-22.md").readText())
        assertEquals(old, memory.todayFile.readText())
    }

    @Test fun oldJournalWithoutDeletionVersionIsDiscardedWithoutOverwritingUserProfile() {
        val memory = MemoryStore(tmp.root)
        memory.saveProfileByUser("[他说过] 合成用户档案")
        val events = EventLog(tmp.root, clock)
        val source = events.append(EventKind.EXCHANGE, "合成交流")
        val oldProfile = memory.profile()
        val newProfile = "[他说过] 旧事务的输出"
        val journal = NightlyJournal("old-batch", "2026-10-23",
            listOf(JournalTarget("profile.md", NightlyStore.hash(oldProfile), NightlyStore.hash(newProfile), newProfile)),
            listOf("合成交流"), listOf(source.id), clock.millis())
        // Default fields are omitted, matching the format before deletedVersion was introduced.
        val oldJson = Json.encodeToString(NightlyJournal.serializer(), journal)
        assertFalse(oldJson.contains("deletedVersion"))
        val journalFile = File(tmp.root, "nightly/journal-old-batch.json")
        journalFile.writeText(oldJson)
        assertTrue(NightlyStore(memory, events, clock).recover().isEmpty())
        assertEquals(oldProfile, memory.profile())
        assertFalse(journalFile.exists())
        assertEquals(1, events.pendingForDay(LocalDate.of(2026, 10, 23)).size)
    }

    @Test fun oldStateAndTopicFilesLoadWithoutRewritingTheirData() {
        val state = """{"energy":0.6,"interest":0.5,"closeness":0.4,"updatedAt":1792756800000}"""
        val topics = """[{"id":"old-topic","summary":"合成旧话题","sourceIds":["old-source"],"createdAt":1792756800000,"expiresAt":1793361600000}]"""
        File(tmp.root, "state.json").writeText(state)
        File(tmp.root, "topics.json").writeText(topics)
        assertEquals(0.4, PetStateStore(tmp.root, clock).current().closeness, 1e-9)
        assertEquals("old-topic", TopicQueue(tmp.root, clock).candidates().single().id)
        assertEquals(state, File(tmp.root, "state.json").readText())
        assertEquals(topics, File(tmp.root, "topics.json").readText())
    }
}
