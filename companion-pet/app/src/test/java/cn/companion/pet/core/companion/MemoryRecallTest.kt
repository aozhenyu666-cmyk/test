package cn.companion.pet.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MemoryRecallTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), zone)
    private val today: LocalDate = LocalDate.of(2026, 10, 23)
    private val yesterday: LocalDate = today.minusDays(1)

    private fun at(d: LocalDate, hour: Int): Long = ZonedDateTime.of(d.atTime(hour, 0), zone).toInstant().toEpochMilli()

    private class Env(val events: EventLog, val facts: FactStore, val recall: MemoryRecall)

    private fun env(): Env {
        val events = EventLog(tmp.root, clock, zone)
        val facts = FactStore(tmp.root, clock, zone)
        return Env(events, facts, MemoryRecall(events, facts, zone))
    }

    @Test
    fun yesterdayInterestIsRecalledByTodayTopic() {
        val e = env()
        e.events.append(EventKind.USER_INPUT, "我最近很喜欢猫", occurredAt = at(yesterday, 20))
        val items = e.recall.recall("猫", at(today, 10))
        assertEquals(1, items.size)
        assertTrue(items.single().text.contains("猫"))
        assertTrue(items.single().day.startsWith("2026-10-22"))
    }

    @Test
    fun unrelatedTopicDoesNotInjectAnything() {
        val e = env()
        e.events.append(EventKind.USER_INPUT, "我最近很喜欢猫", occurredAt = at(yesterday, 20))
        assertTrue(e.recall.recall("量子力学", at(today, 10)).isEmpty())
    }

    @Test
    fun correctionLeavesOnlyNewFact() {
        val e = env()
        val old = e.facts.recordSaid("喜欢猫")!!
        e.facts.correct(old.id, "现在喜欢狗")
        assertTrue(e.recall.recall("猫", at(today, 10)).isEmpty())
        val dog = e.recall.recall("狗", at(today, 10))
        assertEquals(1, dog.size)
        assertEquals("现在喜欢狗", dog.single().text)
    }

    @Test
    fun deletedFactDoesNotComeBackAfterRestart() {
        val e = env()
        val f = e.facts.recordSaid("喜欢猫")!!
        e.facts.forget(f.id)
        val reopened = FactStore(tmp.root, clock, zone)
        val recall = MemoryRecall(e.events, reopened, zone)
        assertTrue(recall.recall("猫", at(today, 10)).isEmpty())
    }

    @Test
    fun limitIsRespectedAndNoPadding() {
        val e = env()
        repeat(10) { i ->
            e.events.append(EventKind.USER_INPUT, "猫的话题 " + i, occurredAt = at(yesterday, 10 + i))
        }
        val items = e.recall.recall("猫", at(today, 10), limits = RecallLimits(maxItems = 3, maxChars = 50))
        assertTrue(items.size <= 3)
        assertTrue(items.sumOf { it.text.length } <= 60)
    }
}
