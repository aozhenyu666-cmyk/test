package cn.companion.pet.core.memory

import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NightlyBatchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), zone)
    private val today: LocalDate = LocalDate.of(2026, 10, 23)
    private val day: LocalDate = LocalDate.of(2026, 10, 22)

    private fun at(d: LocalDate, hour: Int): Long = ZonedDateTime.of(d.atTime(hour, 0), zone).toInstant().toEpochMilli()

    private class Env(val memory: MemoryStore, val log: EventLog, val nightly: NightlyStore)

    private fun env(): Env {
        val memory = MemoryStore(tmp.root)
        val log = EventLog(tmp.root, clock, zone)
        return Env(memory, log, NightlyStore(memory, log, clock, zone))
    }

    private fun output(profile: String = "[他说过] 喜欢猫", diary: String = "日记", letter: String = "信"): NightlyOutput =
        NightlyOutput(profile.lines().filter { it.isNotBlank() }, diary, letter)

    @Test
    fun commitArchivesOnlyThePreparedBatch() {
        val e = env()
        val a = e.log.append(EventKind.EXCHANGE, "- a", occurredAt = at(day, 10))
        val b = e.log.append(EventKind.EXCHANGE, "- b", occurredAt = at(day, 11))
        val prepared = e.nightly.prepare(today)
        assertNotNull(prepared)
        assertEquals(listOf(a.id, b.id), prepared!!.batch.eventIds)

        // 请求期间新增交流：不进批次，也不应该被归档
        val c = e.log.append(EventKind.EXCHANGE, "- c", occurredAt = at(day, 12))
        assertEquals(NightlyCommitOutcome.COMMITTED, e.nightly.commit(prepared.batch, output()))

        assertEquals(listOf(c.id), e.log.pendingForDay(day).map { it.id })
        val archive = e.memory.diaryDir.parentFile!!.let { java.io.File(it, "archive/" + day + ".md") }.readText()
        assertTrue(archive.contains("- a") && archive.contains("- b"))
        assertFalse(archive.contains("- c"))
        assertEquals("[他说过] 喜欢猫", e.memory.profile())
        assertEquals("日记", e.memory.diary(day))
        assertEquals("信", e.memory.letter(day))
    }

    @Test
    fun secondCommitOfSameBatchIsIdempotent() {
        val e = env()
        e.log.append(EventKind.EXCHANGE, "- a", occurredAt = at(day, 10))
        val prepared = e.nightly.prepare(today)!!
        assertEquals(NightlyCommitOutcome.COMMITTED, e.nightly.commit(prepared.batch, output()))
        assertEquals(NightlyCommitOutcome.ALREADY_COMMITTED, e.nightly.commit(prepared.batch, output()))
        val archive = java.io.File(e.memory.root, "archive/" + day + ".md").readText()
        assertEquals(1, Regex("- a").findAll(archive).count())
    }

    @Test
    fun userProfileEditDuringRequestWinsAndEventsStayPending() {
        val e = env()
        e.log.append(EventKind.EXCHANGE, "- a", occurredAt = at(day, 10))
        val prepared = e.nightly.prepare(today)!!
        e.memory.saveProfileByUser("[他说过] 用户新改的")
        assertEquals(NightlyCommitOutcome.CONFLICT, e.nightly.commit(prepared.batch, output()))
        assertEquals("[他说过] 用户新改的", e.memory.profile())
        assertEquals(1, e.log.pendingForDay(day).size)
        assertNull(e.memory.diary(day))
    }

    @Test
    fun noPendingEventsMeansNoBatch() {
        assertNull(env().nightly.prepare(today))
    }

    @Test
    fun backlogIsProcessedOldestFirst() {
        val e = env()
        val oldDay = day.minusDays(1)
        e.log.append(EventKind.EXCHANGE, "- old", occurredAt = at(oldDay, 10))
        e.log.append(EventKind.EXCHANGE, "- new", occurredAt = at(day, 10))
        val first = e.nightly.prepare(today)!!
        assertEquals(oldDay.toString(), first.batch.day)
        assertEquals(NightlyCommitOutcome.COMMITTED, e.nightly.commit(first.batch, output()))
        val second = e.nightly.prepare(today)!!
        assertEquals(day.toString(), second.batch.day)
    }

    @Test
    fun profileIsClampedAndTombstonedAtCommit() {
        val e = env()
        e.memory.saveProfileByUser("[他说过] 喜欢猫\n[我推测] 怕狗")
        e.memory.deleteProfileLine("[我推测] 怕狗")
        e.log.append(EventKind.EXCHANGE, "- a", occurredAt = at(day, 10))
        val prepared = e.nightly.prepare(today)!!
        val out = NightlyOutput(listOf("[他说过] 喜欢猫", "[他说过] 怕 狗"), "d", "l")
        assertEquals(NightlyCommitOutcome.COMMITTED, e.nightly.commit(prepared.batch, out))
        assertEquals(listOf("[他说过] 喜欢猫"), e.memory.profileLines())
    }

    @Test
    fun userProfileDeletionDuringRequestWins() {
        val e = env()
        e.memory.saveProfileByUser("[他说过] a\n[他说过] b")
        e.log.append(EventKind.EXCHANGE, "- x", occurredAt = at(day, 10))
        val prepared = e.nightly.prepare(today)!!
        e.memory.deleteProfileLine("[他说过] b")
        assertEquals(NightlyCommitOutcome.CONFLICT, e.nightly.commit(prepared.batch, output()))
        assertEquals(listOf("[他说过] a"), e.memory.profileLines())
        assertEquals(1, e.log.pendingForDay(day).size)
    }


    @Test
    fun forgottenContentIsNotSentToNightly() {
        val e = env()
        e.memory.addDeletedText("秘密")
        e.log.append(EventKind.EXCHANGE, "秘密", occurredAt = at(day, 10))
        assertNull(e.nightly.prepare(today))
        assertEquals(0, e.log.pendingForDay(day).size)
        assertNull(e.nightly.prepare(today))
    }

}