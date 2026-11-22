package io.github.ccch1mneyyy.dsh_app.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BoutsTileStatusTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun write(home: String, json: String) {
        val file = File(File(dir.root, home), "companion/bouts-status.json")
        file.parentFile.mkdirs()
        file.writeText(json, Charsets.UTF_8)
    }

    @Test
    fun noSummaryYetMeansNoSecondLine() {
        assertNull(BoutsTileStatus.read(dir.root))
        assertNull(BoutsTileStatus.beats(null))
        assertFalse(BoutsTileStatus.running(null))
    }

    @Test
    fun readsTheSummaryFromTheRuntimeHome() {
        write("runtime/rootfs/root/.dsh", """{"at":"2026-11-12T21:00:00Z","beats":5,"running":false}""")

        val status = BoutsTileStatus.read(dir.root)

        assertEquals(5, BoutsTileStatus.beats(status)!!)
        assertFalse(BoutsTileStatus.running(status))
    }

    @Test
    fun fallsBackToTheFlatHome() {
        write(".dsh", """{"beats":3,"running":true}""")

        val status = BoutsTileStatus.read(dir.root)

        assertEquals(3, BoutsTileStatus.beats(status)!!)
        assertTrue(BoutsTileStatus.running(status))
    }

    @Test
    fun theRuntimeHomeWinsWhenBothExist() {
        write("runtime/rootfs/root/.dsh", """{"beats":9}""")
        write(".dsh", """{"beats":1}""")

        assertEquals(9, BoutsTileStatus.beats(BoutsTileStatus.read(dir.root))!!)
    }

    @Test
    fun aCorruptSummaryFallsThroughToTheOtherHome() {
        write("runtime/rootfs/root/.dsh", "{not json")
        write(".dsh", """{"beats":2}""")

        assertEquals(2, BoutsTileStatus.beats(BoutsTileStatus.read(dir.root))!!)
    }

    @Test
    fun aCorruptSummaryAloneMeansNoSecondLine() {
        write(".dsh", """{"beats":2""")

        assertNull(BoutsTileStatus.read(dir.root))
    }

    @Test
    fun looselyTypedFieldsAreIgnored() {
        // The plugin writes a number and a boolean; anything else must not become「今日 7 拍」.
        assertNull(BoutsTileStatus.beats(mapOf("beats" to "7")))
        assertFalse(BoutsTileStatus.running(mapOf("running" to "true")))
        assertEquals(7, BoutsTileStatus.beats(mapOf("beats" to 7L))!!)
    }

    @Test
    fun readsTheOngoingNotificationFields() {
        write(
            "runtime/rootfs/root/.dsh",
            """{"beats":4,"running":true,"bout":"学习局","ask":"这一步要交什么","until":1794497000000}""",
        )

        val status = BoutsTileStatus.read(dir.root)

        assertTrue(BoutsTileStatus.running(status))
        assertEquals("学习局", BoutsTileStatus.bout(status))
        assertEquals("这一步要交什么", BoutsTileStatus.ask(status))
        assertEquals(1794497000000L, BoutsTileStatus.until(status))
    }

    @Test
    fun missingNotificationFieldsFallBackToNothing() {
        // A summary written before those fields existed, or one whose plugin lost the 局.
        write(".dsh", """{"beats":2,"running":true}""")

        val status = BoutsTileStatus.read(dir.root)

        assertEquals("", BoutsTileStatus.bout(status))
        assertEquals("", BoutsTileStatus.ask(status))
        assertEquals(0L, BoutsTileStatus.until(status))
    }

    @Test
    fun aNonNumericUntilIsZeroNotACrash() {
        val status = mapOf("until" to "soon", "ask" to 7, "bout" to false)

        assertEquals(0L, BoutsTileStatus.until(status))
        assertEquals("", BoutsTileStatus.ask(status))
        assertEquals("", BoutsTileStatus.bout(status))
    }

    @Test
    fun readsTheNudgeLineJustBeforeABeatRunsOut() {
        write(
            "runtime/rootfs/root/.dsh",
            """{"beats":4,"running":true,"bout":"学习局","ask":"这一步要交什么","nudge":"还剩 40 秒。交。"}""",
        )

        val status = BoutsTileStatus.read(dir.root)

        assertEquals("还剩 40 秒。交。", BoutsTileStatus.nudge(status))
    }

    @Test
    fun aMissingNudgeIsBlankRatherThanCrashing() {
        // Written before the field existed, or between beats: the notification just shows the question again.
        write(".dsh", """{"beats":2,"running":true,"ask":"这一步要交什么"}""")

        assertEquals("", BoutsTileStatus.nudge(BoutsTileStatus.read(dir.root)))
        assertEquals("", BoutsTileStatus.nudge(mapOf("nudge" to 7)))
        assertEquals("", BoutsTileStatus.nudge(null))
    }
}
