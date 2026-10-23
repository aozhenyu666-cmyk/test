package cn.companion.pet.core.companion

import cn.companion.pet.core.memory.MemoryStore
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

class EventLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), zone)

    private fun log() = EventLog(tmp.root, clock, zone)

    private fun at(day: LocalDate, hour: Int, minute: Int): Long =
        ZonedDateTime.of(day.atTime(hour, minute), zone).toInstant().toEpochMilli()

    @Test
    fun dayAttributionUsesDeviceZoneAtWriteTime() {
        val log = log()
        val day22 = LocalDate.of(2026, 10, 22)
        log.append(EventKind.USER_INPUT, "深夜", occurredAt = at(day22, 23, 59))
        log.append(EventKind.USER_INPUT, "凌晨", occurredAt = at(day22.plusDays(1), 0, 1))

        assertEquals(listOf("深夜"), log.forDay(day22).map { it.text })
        assertEquals(listOf("凌晨"), log.forDay(day22.plusDays(1)).map { it.text })
    }

    @Test
    fun textIsAlwaysOneLine() {
        val log = log()
        val e = log.append(EventKind.USER_INPUT, "第一行\r\n第二行", occurredAt = at(LocalDate.of(2026, 10, 22), 12, 0))
        assertEquals("第一行  第二行", e.text)
        assertFalse(e.text.contains('\n'))
    }

    @Test
    fun partialLastLineIsIgnored() {
        val log = log()
        log.append(EventKind.USER_INPUT, "完整", occurredAt = at(LocalDate.of(2026, 10, 22), 12, 0))
        val file = File(log.eventsDir, "events-2026-10.jsonl")
        file.appendText("{\"id\":\"broken\", \"text\":")
        assertEquals(1, log.all().size)
    }

    @Test
    fun pendingExcludesArchivedIdsIdempotently() {
        val log = log()
        val day = LocalDate.of(2026, 10, 22)
        val a = log.append(EventKind.EXCHANGE, "- a", occurredAt = at(day, 10, 0))
        val b = log.append(EventKind.EXCHANGE, "- b", occurredAt = at(day, 11, 0))
        log.markArchived(day, listOf(a.id))
        log.markArchived(day, listOf(a.id))
        assertEquals(listOf(b.id), log.pendingForDay(day).map { it.id })
        assertEquals(1, log.archivedIds(day).size)
    }

    @Test
    fun pendingDaysAreSortedAndSkipFullyArchivedDays() {
        val log = log()
        val d1 = LocalDate.of(2026, 10, 21)
        val d2 = LocalDate.of(2026, 10, 22)
        val a = log.append(EventKind.EXCHANGE, "- old", occurredAt = at(d1, 10, 0))
        log.append(EventKind.EXCHANGE, "- new", occurredAt = at(d2, 10, 0))
        log.markArchived(d1, listOf(a.id))
        assertEquals(listOf(d2), log.pendingDays())
    }

    @Test
    fun legacyTodayIsImportedOnceAndKeptReadable() {
        val memory = MemoryStore(tmp.root)
        val day = LocalDate.of(2026, 10, 22)
        memory.appendToday(day, "- 旧交流一")
        memory.appendToday(day, "- 旧交流二")
        val log = log()

        assertEquals(2, LegacyEventImport.ensure(memory, log, zone))
        assertEquals(2, log.forDay(day).size)
        assertTrue(memory.todayFile.exists())
        assertTrue(File(memory.root, "migrations/legacy-today-" + day + ".md").exists())

        // 重复执行幂等，不产生重复事件
        assertEquals(0, LegacyEventImport.ensure(memory, log, zone))
        assertEquals(2, log.forDay(day).size)
    }
}
