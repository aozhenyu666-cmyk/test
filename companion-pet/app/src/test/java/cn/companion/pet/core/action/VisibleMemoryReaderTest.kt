package cn.companion.pet.core.action

import cn.companion.pet.core.companion.*
import cn.companion.pet.core.memory.MemoryStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisibleMemoryReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun tombstonedFactsCannotLeakThroughToolAndGuessesKeepTheirTag() {
        val memory = MemoryStore(tmp.root);val events = EventLog(tmp.root);val facts = FactStore(tmp.root)
        memory.saveProfileByUser("[他说过] 合成旧信息")
        facts.recordSaid("合成旧信息");facts.recordGuess("合成猜测")
        memory.addDeletedText("合成旧信息")
        val result = VisibleMemoryReader(memory, events, facts).read("", 20)
        assertFalse(result.any { it.contains("合成旧信息") })
        assertEquals(listOf("[我推测] 合成猜测"), result)
    }

    @Test fun expiredSourceHidesFactsAndMatchingProfileFromTheTool() {
        val memory = MemoryStore(tmp.root);val events = EventLog(tmp.root);val facts = FactStore(tmp.root)
        val source = events.append(EventKind.MATERIAL, "合成来源", expiresAt = 1000L)
        facts.recordSaid("合成事实", listOf(source.id));memory.saveProfileByUser("[他说过] 合成事实")
        assertTrue(VisibleMemoryReader(memory, events, facts).read("", 20, 2000L).isEmpty())
    }
}
