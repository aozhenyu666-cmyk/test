package cn.companion.pet.core.companion

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class EventTailRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneId.of("UTC"))

    @Test fun completeUtf8EventWithoutFinalNewlineSurvivesNextAppend() {
        val log = EventLog(tmp.root, clock)
        val first = log.append(EventKind.USER_INPUT, "合成中文😀")
        val file = File(log.eventsDir, "events-2026-10.jsonl")
        file.writeText(file.readText().trimEnd('\n'))
        val restarted = EventLog(tmp.root, clock)
        val next = restarted.append(EventKind.USER_INPUT, "合成新事件")
        assertEquals(setOf(first.id, next.id), restarted.all().map { it.id }.toSet())
        assertEquals("合成中文😀", restarted.all().first { it.id == first.id }.text)
        assertFalse(File(log.eventsDir, "broken-tails.log").exists())
    }

    @Test fun singleBrokenFirstWriteDoesNotSwallowFirstSuccessfulEvent() {
        val log = EventLog(tmp.root, clock)
        File(log.eventsDir, "events-2026-10.jsonl").writeText("{\"id\":\"partial\"")
        val next = EventLog(tmp.root, clock).append(EventKind.USER_INPUT, "合成完整事件")
        assertEquals(listOf(next.id), log.all().map { it.id })
        assertTrue(File(log.eventsDir, "broken-tails.log").exists())
    }
}
