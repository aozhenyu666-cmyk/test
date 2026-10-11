package com.yishou.app.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RunLogTest {
    private fun tmp(): File = Files.createTempDirectory("runlog").toFile().resolve("log.jsonl")

    @Test
    fun newestFirstAndCapped() {
        var t = 0L
        val log = RunLog(tmp(), max = 3, clock = { ++t })
        (1..5).forEach { log.add("判定", "失败 $it") }
        val e = log.entries()
        assertEquals(listOf("失败 5", "失败 4", "失败 3"), e.map { it.message })
        assertEquals(5L, e.first().time)
    }

    @Test
    fun keepsErrorTypeAndSurvivesNewlines() {
        val f = tmp()
        RunLog(f).add("崩溃", "第一行\n第二行", IllegalStateException("坏了"))
        val e = RunLog(f).entries().single()
        assertTrue(e.message.startsWith("第一行\n第二行（IllegalStateException：坏了）"))
        assertTrue(e.message.contains("\n  at "))
    }

    @Test
    fun clearAndCorruptLines() {
        val f = tmp()
        f.writeText("not json\n" + LogEntry(1, "a", "b").toJson() + "\n")
        val log = RunLog(f)
        assertEquals(1, log.entries().size)
        log.clear()
        assertTrue(log.entries().isEmpty())
    }
}
