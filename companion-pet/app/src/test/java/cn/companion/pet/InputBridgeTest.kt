package cn.companion.pet

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class InputBridgeTest {
    @After fun cleanup() { InputBridge.clear() }
    @Test fun lateOldKeyboardCannotSubmitAnAnswerToANewerQuestion() {
        val answers = mutableListOf<String>()
        val old = InputBridge.open("old", { answers += "old:$it" }, {})
        val current = InputBridge.open("new", { answers += "new:$it" }, {})
        assertFalse(InputBridge.submit(old, "旧题答案"))
        assertTrue(InputBridge.isActive(current))
        assertTrue(InputBridge.submit(current, "新题答案"))
        assertEquals(listOf("new:新题答案"), answers)
    }
    @Test fun closingOldActivityDoesNotCancelNewInput() {
        var cancelled = 0
        val old = InputBridge.open("old", {}, { cancelled++ })
        val current = InputBridge.open("new", {}, { cancelled++ })
        assertEquals(1, cancelled)
        assertFalse(InputBridge.cancel(old))
        assertTrue(InputBridge.isActive(current))
        assertEquals(1, cancelled)
    }
    @Test fun duplicateSubmissionIsConsumedOnlyOnce() {
        var calls = 0
        val id = InputBridge.open("", { calls++ }, {})
        assertTrue(InputBridge.submit(id, "one"))
        assertFalse(InputBridge.submit(id, "two"))
        assertEquals(1, calls)
    }
    @Test fun stoppingServiceInvalidatesTheOldInputTicket() {
        val id = InputBridge.open("private", { fail("old callback") }, {})
        InputBridge.clear()
        assertFalse(InputBridge.submit(id, "text"))
        assertEquals("", InputBridge.hint(id))
    }
    @Test fun aReplacedInputCannotReadTheNewQuestionsHint() {
        val old = InputBridge.open("old", {}, {})
        val current = InputBridge.open("new", {}, {})
        assertEquals("", InputBridge.hint(old))
        assertEquals("new", InputBridge.hint(current))
    }
}
