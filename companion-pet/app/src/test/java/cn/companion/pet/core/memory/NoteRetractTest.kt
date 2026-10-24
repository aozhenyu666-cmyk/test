package cn.companion.pet.core.memory

import cn.companion.pet.core.companion.CompanionContextAssembler
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventLog
import cn.companion.pet.core.companion.FactStore
import cn.companion.pet.core.companion.MemoryRecall
import cn.companion.pet.core.companion.TopicQueue
import cn.companion.pet.core.probe.ProbeMoment
import cn.companion.pet.core.scene.Scene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 点“撤销”的念头不能留在召回、最近交流、话题队列和夜间整理里。 */
class NoteRetractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), zone)

    @Test
    fun undoneNoteDisappearsEverywhere() {
        val memory = MemoryStore(tmp.root)
        val events = EventLog(tmp.root, clock, zone)
        val facts = FactStore(tmp.root, clock, zone)
        val topics = TopicQueue(tmp.root, clock, events)
        val edits = MemoryEdits(memory, facts, events)

        val text = "想把合成测试猫的名字改成团子"
        val note = events.append(EventKind.NOTE, text, source = "note")
        events.append(EventKind.EXCHANGE, "- 16:00 | 念头 | $text", source = "note", sourceIds = listOf(note.id))
        topics.enqueue(text, listOf(note.id), now = clock.millis() - 3_600_000L)

        edits.retractNote(note.id)

        assertTrue(MemoryRecall(events, facts, zone).recall("团子", clock.millis(), memory.tombstones()).isEmpty())
        assertTrue(topics.candidates(clock.millis()).isEmpty())
        val moment = ProbeMoment("16:00", Scene.NORMAL, "桌面", "在桌面", null)
        val prompt = CompanionContextAssembler(events, facts, zone).assemble(memory.persona(), moment, null, clock.millis(), memory.tombstones())[1].content
        assertFalse(prompt.contains("团子"))
        assertNull(NightlyStore(memory, events, clock, zone, facts).prepare(LocalDate.of(2026, 10, 23)))
        // 撤销不是“永远不许再提”：以后他重新说同样的话仍然能记。
        assertFalse(memory.tombstones().any { it.contains("团子") })
    }
}
