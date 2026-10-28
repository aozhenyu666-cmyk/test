package cn.companion.pet.core.partner

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ObservationPrivacyTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun actualObservationEntryPointNeverPersistsItsFullVisualText() {
        val rules = DialogueRules()
        var s = rules.start("look", 1)
        s = rules.addObservation(s, "我看了一眼：第一行私密细节，第二行全部内容", 2)
        assertTrue(s.turns.single().ephemeral)
        s = rules.add(s, DialogueTurn(Speaker.USER, "我们聊这条判断", 3, TurnChannel.TYPED))
        val store = DialogueStore(tmp.root)
        store.save(s)
        assertEquals(listOf("我们聊这条判断"), store.load()!!.turns.map { it.text })
        assertFalse(File(tmp.root, "partner/dialogue.json").readText().contains("私密细节"))
    }
    @Test fun fullObservationsCannotBeSentToTheDurableSummaryCompactor() {
        val rules = DialogueRules(keepTurns = 2)
        var s = rules.start("look", 1)
        s = rules.addObservation(s, "完整画面中不应该压进持久摘要的内容", 2)
        s = rules.add(s, DialogueTurn(Speaker.USER, "我的观点", 3, TurnChannel.TYPED))
        s = rules.add(s, DialogueTurn(Speaker.PET, "回应观点", 4, TurnChannel.REPLY))
        assertTrue(rules.needsCompaction(s))
        assertTrue(rules.turnsToCompact(s).isEmpty())
        val compacted = rules.applyCompaction(s, s.summary)
        assertFalse(compacted.turns.any { it.ephemeral })
    }
    @Test fun corruptSessionIsBackedUpAndTheReadFailureIsVisible() {
        val file = File(tmp.root, "partner/dialogue.json")
        file.parentFile!!.mkdirs(); file.writeText("{broken")
        val store = DialogueStore(tmp.root)
        assertNull(store.load())
        assertNotNull(store.lastError)
        assertEquals("{broken", file.parentFile!!.listFiles()!!.single { it.name.startsWith("dialogue.bad-") }.readText())
        store.save(DialogueRules().start("new", 1))
        assertNull(store.lastError)
        assertEquals("new", store.load()!!.id)
    }
}
