package cn.companion.pet.core.memory

import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MemoryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val day = LocalDate.of(2026, 10, 22)
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), zone)

    private fun store() = MemoryStore(tmp.root)

    private fun eventAt(day: LocalDate): Long =
        day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun commit(memory: MemoryStore, day: LocalDate, lines: List<String>, output: NightlyOutput) {
        val log = EventLog(memory.root, clock, zone)
        lines.forEach { log.append(EventKind.EXCHANGE, it, occurredAt = eventAt(day)) }
        val nightly = NightlyStore(memory, log, clock, zone)
        val prepared = nightly.prepare(day.plusDays(1)) ?: error("no batch")
        assertEquals(NightlyCommitOutcome.COMMITTED, nightly.commit(prepared.batch, output))
    }

    @Test
    fun personaDefaultsAndSaves() {
        val s = store()
        assertTrue(s.persona().contains("熟人同桌"))
        s.savePersona("新人设")
        assertEquals("新人设", s.persona())
    }

    @Test
    fun todayAppendsWithDateHeader() {
        val s = store()
        s.appendToday(day, "- a")
        s.appendToday(day, "- b\nc")
        assertEquals(listOf("- a", "- b c"), s.todayLines())
        assertEquals(day, s.todayDate())
        assertEquals(listOf("- b c"), s.todayRecent(1))
    }

    @Test
    fun forgetPhraseDetected() {
        assertTrue(TodayLog.wantsForget("这个别记这个啊"))
        assertFalse(TodayLog.wantsForget("记一下"))
        assertFalse(TodayLog.wantsForget(null))
    }

    @Test
    fun formatHasNoNewlinesAndMarksIgnored() {
        val line = TodayLog.format(Exchange("10:00", "游荡", "在想啥\n呢", null))
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("（被忽略）"))
    }

    @Test
    fun nightlyCommitWritesAllAndArchives() {
        val s = store()
        val out = Nightly.parse(
            """{"profile":"[他说过] 喜欢猫\n随便一行\n[我推测] 晚上效率低","diary":"今天他理我了","letter":"昨天你说的猫叫什么？"}""",
            emptyList(),
        )!!
        assertEquals(2, out.profileLines.size)
        commit(s, day, listOf("- 交流"), out)
        assertEquals("[他说过] 喜欢猫\n[我推测] 晚上效率低", s.profile())
        assertEquals("今天他理我了", s.diary(day))
        assertEquals("昨天你说的猫叫什么？", s.letter(day))
        assertTrue(File(s.archiveDir, "$day.md").readText().contains("- 交流"))
    }

    @Test
    fun deletedLinesNeverComeBack() {
        val s = store()
        s.saveProfileByUser("[他说过] 喜欢猫\n[我推测] 怕狗")
        s.deleteProfileLine("[我推测] 怕狗")
        assertEquals(listOf("[他说过] 喜欢猫"), s.profileLines())
        // 模型换了标签、加了空格又写回来
        val out = Nightly.parse("""{"profile":"[他说过] 喜欢猫\n[他说过] 怕 狗","diary":"d","letter":"l"}""", s.profileLines())!!
        commit(s, day, listOf("- x"), out)
        assertEquals(listOf("[他说过] 喜欢猫"), s.profileLines())
    }

    @Test
    fun userEditRemovingLineIsTombstoned() {
        val s = store()
        s.saveProfileByUser("[他说过] a\n[我推测] b")
        s.saveProfileByUser("[他说过] a")
        assertTrue("b" in s.tombstones())
    }

    @Test
    fun nightlyRejectsPartialOutput() {
        assertNull(Nightly.parse("""{"profile":"[他说过] a","diary":"","letter":"l"}""", emptyList()))
        assertNull(Nightly.parse("""{"profile":"[他说过] a","diary":"d"}""", emptyList()))
        assertNull(Nightly.parse("not json", emptyList()))
        // 旧档案非空但模型输出的档案一行都不合格：拒绝，避免一夜清空
        assertNull(Nightly.parse("""{"profile":"乱写","diary":"d","letter":"l"}""", listOf("[他说过] a")))
        val tooLongLetter = "长".repeat(200)
        val longLetterJson = "{\"profile\":\"\",\"diary\":\"d\",\"letter\":\"" + tooLongLetter + "\"}"
        assertNull(Nightly.parse(longLetterJson, emptyList()))
        assertTrue(Nightly.parse("""{"profile":"","diary":"d","letter":"l"}""", emptyList()) != null)
    }

    @Test
    fun failedNightlyKeepsOldFiles() {
        val s = store()
        s.saveProfileByUser("[他说过] a")
        s.appendToday(day, "- x")
        val out = Nightly.parse("garbage", s.profileLines())
        assertNull(out)
        assertEquals("[他说过] a", s.profile())
        assertTrue(s.todayFile.exists())
    }

    @Test
    fun retryAppendsToExistingArchive() {
        val s = store()
        val log = EventLog(s.root, clock, zone)
        log.append(EventKind.EXCHANGE, "- first", occurredAt = eventAt(day))
        val nightly = NightlyStore(s, log, clock, zone)
        val first = nightly.prepare(day.plusDays(1))!!
        assertEquals(NightlyCommitOutcome.COMMITTED, nightly.commit(first.batch, NightlyOutput(emptyList(), "d", "l")))
        log.append(EventKind.EXCHANGE, "- second", occurredAt = eventAt(day))
        val second = nightly.prepare(day.plusDays(1))!!
        assertEquals(NightlyCommitOutcome.COMMITTED, nightly.commit(second.batch, NightlyOutput(emptyList(), "d2", "l2")))
        val a = File(s.archiveDir, "$day.md").readText()
        assertTrue(a.contains("- first") && a.contains("- second"))
    }

    @Test
    fun profileClampedTo3000() {
        val lines = (1..400).map { "[他说过] 第" + it + "条记忆内容" }
        val c = ProfileRules.clamp(lines)
        assertTrue(c.joinToString("\n").codePointCount(0, c.joinToString("\n").length) <= 3000)
        assertTrue(c.size < lines.size)
    }

    @Test
    fun unreadLetterAndDiarySummary() {
        val s = store()
        commit(s, day, listOf("- 交流"), NightlyOutput(emptyList(), "昨天的日记", "信"))
        assertEquals(day to "信", s.unreadLetter())
        s.markLetterRead(day)
        assertNull(s.unreadLetter())
        assertEquals("昨天的日记", s.diarySummary(day.plusDays(1)))
        assertEquals("", s.diarySummary(day))
    }

    @Test
    fun pruneOldArchives() {
        val s = store()
        File(s.archiveDir, day.minusDays(31).toString() + ".md").writeText("old")
        File(s.archiveDir, day.minusDays(5).toString() + ".md").writeText("new")
        s.pruneArchive(day)
        assertEquals(listOf(day.minusDays(5)), s.listDates(s.archiveDir))
    }

    @Test
    fun confirmGuess() {
        val s = store()
        s.saveProfileByUser("[我推测] 喜欢夜里看书")
        s.confirmProfileLine("[我推测] 喜欢夜里看书")
        assertEquals("[他说过] 喜欢夜里看书", s.profile())
    }

    @Test
    fun appendAcrossMidnightArchivesOldDay() {
        val s = store()
        val next = day.plusDays(1)
        s.appendToday(day, "- old")
        s.appendToday(next, "- new")
        assertEquals(next, s.todayDate())
        assertEquals(listOf("- new"), s.todayLines())
        assertTrue(File(s.archiveDir, "$day.md").readText().contains("- old"))
    }
}