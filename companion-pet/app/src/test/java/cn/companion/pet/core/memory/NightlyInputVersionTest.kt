package cn.companion.pet.core.memory

import cn.companion.pet.core.companion.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class NightlyInputVersionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneId.of("UTC"))
    private val day = LocalDate.of(2026, 10, 23)
    private val memory by lazy { MemoryStore(tmp.root) }
    private val events by lazy { EventLog(tmp.root, clock) }
    private val facts by lazy { FactStore(tmp.root, clock) }
    private val nightly by lazy { NightlyStore(memory, events, clock, facts = facts) }
    private val output = NightlyOutput(listOf("[他说过] 喜欢猫"), "合成日记", "合成信")

    private fun prepare(): PreparedNightly {
        events.append(EventKind.EXCHANGE, "- 合成交流")
        return nightly.prepare(day)!!
    }

    @Test fun forgetDuringRequestRejectsAllStaleOutputs() {
        val prepared = prepare()
        memory.addDeletedText("喜欢猫")
        assertEquals(NightlyCommitOutcome.CONFLICT, nightly.commit(prepared.batch, output))
        assertEquals("", memory.profile())
        assertNull(memory.diary(day))
        assertNull(memory.letter(day))
        assertEquals(1, events.pendingForDay(day).size)
        assertNotEquals(prepared.batch.id, nightly.prepare(day)!!.batch.id)
    }

    @Test fun personaChangeDuringRequestRejectsOldPersonaOutput() {
        val prepared = prepare()
        memory.savePersona("用户刚修改的人设")
        assertEquals(NightlyCommitOutcome.CONFLICT, nightly.commit(prepared.batch, output))
        assertEquals("用户刚修改的人设", memory.persona())
        assertEquals("", memory.profile())
    }

    @Test fun factConfirmationDuringRequestRejectsOldFactSnapshot() {
        val guess = facts.recordGuess("喜欢猫")!!
        val prepared = prepare()
        facts.confirm(guess.id)
        assertEquals(NightlyCommitOutcome.CONFLICT, nightly.commit(prepared.batch, output))
        assertEquals(Confidence.SAID, facts.live().single().confidence)
        assertNull(memory.diary(day))
    }

    @Test fun recoveryPreflightsAllTargetsBeforeChangingProfile() {
        memory.saveProfileByUser("[他说过] 原有档案")
        val prepared = prepare()
        nightly.fault = { if (it == "journal-written") error("synthetic crash") }
        assertTrue(runCatching { nightly.commit(prepared.batch, output) }.isFailure)
        memory.writeAtomic(File(memory.diaryDir, "$day.md"), "用户后来写的日记")
        nightly.fault = {}
        assertTrue(nightly.recover().isEmpty())
        assertEquals("[他说过] 原有档案", memory.profile())
        assertEquals("用户后来写的日记", memory.diary(day))
        assertNull(memory.letter(day))
        assertEquals(1, events.pendingForDay(day).size)
    }

    @Test fun forgottenTextIsRemovedFromPriorDiaryInputAndOutput() {
        memory.addDeletedText("合成旧秘密")
        memory.writeAtomic(File(memory.diaryDir, "${day.minusDays(1)}.md"), "合成旧秘密\n昨天天气晴")
        val prepared = prepare()
        val oldDiarySection = prepared.messages.last().content.substringAfter("# 我昨天的日记\n").substringBefore("# 今天的交流记录")
        assertFalse(oldDiarySection.contains("合成旧秘密"))
        assertEquals(NightlyCommitOutcome.COMMITTED, nightly.commit(prepared.batch,
            output.copy(diary = "合成旧秘密\n今天下雨", letter = "合成旧秘密\n明天见")))
        assertEquals("今天下雨", memory.diary(day))
        assertEquals("明天见", memory.letter(day))
    }
}
