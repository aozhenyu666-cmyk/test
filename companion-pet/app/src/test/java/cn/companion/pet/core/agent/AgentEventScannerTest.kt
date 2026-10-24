package cn.companion.pet.core.agent

import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventLog
import cn.companion.pet.core.companion.EventVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AgentEventScannerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), zone)
    private val now = clock.millis()

    @Test
    fun noteFollowedByExchangeIsStillFound() {
        val events = EventLog(tmp.root, clock, zone)
        val note = events.append(EventKind.NOTE, "用户念头", occurredAt = now - 100, source = "note")
        events.append(EventKind.EXCHANGE, "nightly exchange", occurredAt = now, source = "chat")
        val visibility = EventVisibility(events.all(), emptySet(), now)
        val found = AgentEventScanner().next(events.all(), emptySet(), now, visibility)
        assertEquals(note.id, found?.id)
    }

    @Test
    fun sameMillisecondNotesAreBothProcessedInOrder() {
        val events = EventLog(tmp.root, clock, zone)
        val first = events.append(EventKind.NOTE, "第一条", occurredAt = now, source = "note")
        val second = events.append(EventKind.NOTE, "第二条", occurredAt = now, source = "note")
        val visibility = EventVisibility(events.all(), emptySet(), now)
        val scanner = AgentEventScanner()
        val one = scanner.next(events.all(), emptySet(), now, visibility)
        val two = scanner.next(events.all(), setOf(one!!.id), now, visibility)
        val again = scanner.next(events.all(), setOf(first.id, second.id), now, visibility)
        assertTrue(one.id == first.id || one.id == second.id)
        assertTrue(two!!.id != one.id)
        assertNull(again)
    }

    @Test
    fun processedIdsSurviveRestart() {
        val events = EventLog(tmp.root, clock, zone)
        val first = events.append(EventKind.NOTE, "第一条", occurredAt = now, source = "note")
        val second = events.append(EventKind.NOTE, "第二条", occurredAt = now, source = "note")
        val store = AgentTraceStore(tmp.root)
        store.append(AgentTrace("t1", now, "NOTE_SAVED", true, "NO_ACTION", eventId = first.id))
        val processed = AgentTraceStore(tmp.root).processedEventIds(100)
        val found = AgentEventScanner().next(events.all(), processed, now, EventVisibility(events.all(), emptySet(), now))
        assertEquals(second.id, found?.id)
    }

    @Test
    fun deletedNoteDoesNotTriggerAgain() {
        val events = EventLog(tmp.root, clock, zone)
        val note = events.append(EventKind.NOTE, "会被删除", occurredAt = now, source = "note")
        events.append(EventKind.FORGET, "删除念头", occurredAt = now + 1, source = "user", sourceIds = listOf(note.id))
        val visibility = EventVisibility(events.all(), emptySet(), now + 2)
        assertNull(AgentEventScanner().next(events.all(), emptySet(), now + 2, visibility))
    }

    @Test
    fun oldEventsBeyondWindowAreIgnored() {
        val events = EventLog(tmp.root, clock, zone)
        events.append(EventKind.NOTE, "很老的", occurredAt = now - 8L * 24 * 60 * 60 * 1000, source = "note")
        val found = AgentEventScanner().next(
            events.all(), emptySet(), now, EventVisibility(events.all(), emptySet(), now),
        )
        assertNull(found)
    }

    @Test
    fun eventLogNotifiesListenersAndCanStop() {
        val events = EventLog(tmp.root, clock, zone)
        val seen = mutableListOf<String>()
        val listener: (cn.companion.pet.core.companion.CompanionEvent) -> Unit = { seen += it.id }
        events.addListener(listener)
        val note = events.append(EventKind.NOTE, "新念头", occurredAt = now, source = "note")
        assertEquals(listOf(note.id), seen)
        events.removeListener(listener)
        events.append(EventKind.NOTE, "另一个", occurredAt = now + 1, source = "note")
        assertEquals(listOf(note.id), seen)
    }
}