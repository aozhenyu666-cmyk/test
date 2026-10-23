package cn.companion.pet.core.memory

import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NightlyRecoveryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), zone)
    private val today: LocalDate = LocalDate.of(2026, 10, 23)
    private val day: LocalDate = LocalDate.of(2026, 10, 22)

    private class Env(val root: File, val memory: MemoryStore, val log: EventLog, val nightly: NightlyStore, val ids: List<String>)

    private fun env(name: String): Env {
        val root = File(tmp.root, name)
        root.mkdirs()
        val memory = MemoryStore(root)
        val log = EventLog(root, clock, zone)
        val nightly = NightlyStore(memory, log, clock, zone)
        val a = log.append(EventKind.EXCHANGE, "- a", occurredAt = at(day, 10))
        val b = log.append(EventKind.EXCHANGE, "- b", occurredAt = at(day, 11))
        return Env(root, memory, log, nightly, listOf(a.id, b.id))
    }

    private fun at(d: LocalDate, hour: Int): Long = ZonedDateTime.of(d.atTime(hour, 0), zone).toInstant().toEpochMilli()

    private fun output() = NightlyOutput(listOf("[他说过] 喜欢猫"), "日记", "信")

    @Test
    fun everyCommitStepCanBeRecovered() {
        val steps = listOf(
            "journal-written",
            "target:profile.md",
            "target:diary/" + day + ".md",
            "target:letters/" + day + ".md",
            "archive",
            "mark-archived",
            "batch-committed",
        )
        steps.forEachIndexed { index, step ->
            val e = env("case-" + index)
            val prepared = e.nightly.prepare(today)!!
            e.nightly.fault = { s -> if (s == step) throw IllegalStateException("boom") }
            val threw = runCatching { e.nightly.commit(prepared.batch, output()) }.isFailure
            assertTrue("step did not throw: " + step, threw)
            e.nightly.fault = {}

            e.nightly.recover()

            assertEquals("profile after " + step, "[他说过] 喜欢猫", e.memory.profile())
            assertEquals("diary after " + step, "日记", e.memory.diary(day))
            assertEquals("letter after " + step, "信", e.memory.letter(day))
            val archive = File(e.memory.archiveDir, day.toString() + ".md").readText()
            assertEquals("archive after " + step, 1, Regex("- a").findAll(archive).count())
            assertEquals("pending after " + step, 0, e.log.pendingForDay(day).size)
        }
    }

    @Test
    fun recoverIsIdempotent() {
        val e = env("idem")
        val prepared = e.nightly.prepare(today)!!
        e.nightly.fault = { s -> if (s == "archive") throw IllegalStateException("boom") }
        runCatching { e.nightly.commit(prepared.batch, output()) }
        e.nightly.fault = {}
        assertEquals(1, e.nightly.recover().size)
        assertTrue(e.nightly.recover().isEmpty())
        assertEquals(NightlyCommitOutcome.ALREADY_COMMITTED, e.nightly.commit(prepared.batch, output()))
        val archive = File(e.memory.archiveDir, day.toString() + ".md").readText()
        assertEquals(1, Regex("- a").findAll(archive).count())
    }

    @Test
    fun userEditBeforeRecoveryAbortsBatchAndKeepsPending() {
        val e = env("user-edit")
        val prepared = e.nightly.prepare(today)!!
        e.nightly.fault = { s -> if (s == "archive") throw IllegalStateException("boom") }
        runCatching { e.nightly.commit(prepared.batch, output()) }
        e.nightly.fault = {}
        e.memory.saveProfileByUser("[他说过] 用户后来改的")
        assertTrue(e.nightly.recover().isEmpty())
        assertEquals("[他说过] 用户后来改的", e.memory.profile())
        assertEquals(2, e.log.pendingForDay(day).size)
    }

    @Test
    fun newEventDuringRecoveryStaysPending() {
        val e = env("new-event")
        val prepared = e.nightly.prepare(today)!!
        e.nightly.fault = { s -> if (s == "archive") throw IllegalStateException("boom") }
        runCatching { e.nightly.commit(prepared.batch, output()) }
        e.nightly.fault = {}
        val c = e.log.append(EventKind.EXCHANGE, "- c", occurredAt = at(day, 12))
        e.nightly.recover()
        assertEquals(listOf(c.id), e.log.pendingForDay(day).map { it.id })
        val archive = File(e.memory.archiveDir, day.toString() + ".md").readText()
        assertFalse(archive.contains("- c"))
    }
}
