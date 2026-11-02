package com.yishou.app.focus

import org.junit.Assert.*
import org.junit.Test

class FocusJsonTest {
    @Test fun roundTripKeepsRetunesMarksQuitAndPackages() {
        val run = FocusRun(FocusSession(FocusPlan("刷言语题", "两题", 30, 6, 1, listOf("GPT")), 1000, 1801000,
            listOf(Retune(421000, 5, 0, "缩短一组")), listOf(RoundMark(1, 421000, true, "做完了")), 450000, 700000),
            setOf("com.openai.chatgpt"), setOf("com.tencent.mm"), setOf(1), true)
        assertEquals(run, FocusJson.decode(FocusJson.encode(run)))
    }
}
