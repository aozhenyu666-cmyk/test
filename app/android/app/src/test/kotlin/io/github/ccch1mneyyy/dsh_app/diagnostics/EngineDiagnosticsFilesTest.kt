package io.github.ccch1mneyyy.dsh_app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineDiagnosticsFilesTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun home(): File = dir.newFolder("home").apply {
        File(this, "companion").mkdirs()
        File(this, "feiyu-mood").mkdirs()
    }

    @Test
    fun readsTheAllowlistedEngineFiles() {
        val home = home()
        File(home, "companion/ledger.jsonl").writeText("{\"state\":\"generated\"}\n")
        File(home, "companion/config.json").writeText("{\"enabled\":true}\n")
        File(home, "companion/not-listed.json").writeText("secret")

        val files = EngineDiagnosticsFiles.collect(listOf(home), emptyList())

        assertEquals(setOf("engine/companion/ledger.jsonl", "engine/companion/config.json"), files.keys)
        assertEquals("{\"state\":\"generated\"}\n", files["engine/companion/ledger.jsonl"])
        assertTrue(files.keys.none { it.contains("not-listed") })
    }

    @Test
    fun firstHomeWithTheFileWinsAndAppFilesKeepTheirNames() {
        val old = home()
        val current = dir.newFolder("current").apply { File(this, "companion").mkdirs() }
        File(old, "companion/ledger.jsonl").writeText("old")
        File(current, "companion/ledger.jsonl").writeText("new")
        val exits = dir.newFile("engine-exits.jsonl").apply { writeText("{\"event\":\"engineExit\"}\n") }

        val files = EngineDiagnosticsFiles.collect(
            listOf(current, old),
            listOf("engine/engine-exits.jsonl" to exits),
        )

        assertEquals("new", files["engine/companion/ledger.jsonl"])
        assertEquals("{\"event\":\"engineExit\"}\n", files["engine/engine-exits.jsonl"])
        assertEquals(listOf("engine/engine-exits.jsonl", "engine/companion/ledger.jsonl"), files.keys.toList())
    }

    @Test
    fun aLargeLogKeepsWholeNewestLines() {
        val home = home()
        val log = File(home, "companion/ledger.jsonl")
        // Comfortably over the 512 KiB per-file cap.
        log.writeText((1..80_000).joinToString("") { "line $it padded-padded-padded\n" })

        val text = EngineDiagnosticsFiles.collect(listOf(home), emptyList())["engine/companion/ledger.jsonl"]!!
        val lines = text.trimEnd('\n').split('\n')

        assertTrue(text.length <= 512 * 1024)
        assertTrue(lines.first().matches(Regex("line \\d+ padded-padded-padded")))
        assertEquals("line 80000 padded-padded-padded", lines.last())
    }

    @Test
    fun missingFilesAreSkippedInsteadOfFailing() {
        val files = EngineDiagnosticsFiles.collect(listOf(home()), emptyList())
        assertTrue(files.isEmpty())
    }

    @Test
    fun namesListWhatABundleCanCarry() {
        val names = EngineDiagnosticsFiles.names()
        assertTrue(names.contains("engine/companion/ledger.jsonl"))
        assertTrue(names.contains("engine/engine-exits.jsonl"))
        assertTrue(names.contains("phone/foreground.jsonl"))
        assertTrue(names.contains("phone/a11y-watchdog.jsonl"))
    }
}
