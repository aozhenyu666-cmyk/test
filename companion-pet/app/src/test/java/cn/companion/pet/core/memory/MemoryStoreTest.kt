package cn.companion.pet.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class MemoryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val day = LocalDate.of(2026, 10, 22)

    private fun store() = MemoryStore(tmp.root)

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
        s.appendToday(day, "- 交流")
        val out = Nightly.parse(
            """{"profile":"[他说过] 喜欢猫\n随便一行\n[我推测] 晚上效率低","diary":"今天他理我了","letter":"昨天你说的猫叫什么？"}""",
            emptyList(),
        )!!
        assertEquals(2, out.profileLines.size)
        s.commitNightly(day, out)
        assertEquals("[他说过] 喜欢猫\n[我推测] 晚上效率低", s.profile())
        assertEquals("今天他理我了", s.diary(day))
        assertEquals("昨天你说的猫叫什么？", s.letter(day))
        assertFalse(s.todayFile.exists())
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
        s.commitNightly(day, out)
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
        assertNull(Nightly.parse("""{"profile":"","diary":"d","letter":"${"长".repeat(200)}"}""", emptyList()))
        assertNotNull(Nightly.parse("""{"profile":"","diary":"d","letter":"l"}""", emptyList()))
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
        s.appendToday(day, "- first")
        s.commitNightly(day, NightlyOutput(emptyList(), "d", "l"))
        s.appendToday(day, "- second")
        s.commitNightly(day, NightlyOutput(emptyList(), "d2", "l2"))
        val a = File(s.archiveDir, "$day.md").readText()
        assertTrue(a.contains("- first") && a.contains("- second"))
    }

    @Test
    fun profileClampedTo3000() {
        val lines = (1..400).map { "[他说过] 第${it}条记忆内容" }
        val c = ProfileRules.clamp(lines)
        assertTrue(c.joinToString("\n").codePointCount(0, c.joinToString("\n").length) <= 3000)
        assertTrue(c.size < lines.size)
    }

    @Test
    fun unreadLetterAndDiarySummary() {
        val s = store()
        s.commitNightly(day, NightlyOutput(emptyList(), "昨天的日记", "信"))
        assertEquals(day to "信", s.unreadLetter())
        s.markLetterRead(day)
        assertNull(s.unreadLetter())
        assertEquals("昨天的日记", s.diarySummary(day.plusDays(1)))
        assertEquals("", s.diarySummary(day))
    }

    @Test
    fun pruneOldArchives() {
        val s = store()
        File(s.archiveDir, "${day.minusDays(31)}.md").writeText("old")
        File(s.archiveDir, "${day.minusDays(5)}.md").writeText("new")
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
}
