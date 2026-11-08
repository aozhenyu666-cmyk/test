package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 硬化轮: the 训练题 is a *checkable* drill, not a countdown.
 *
 * Round 4's page granted passage when it had been on screen for a minute, so putting the phone down
 * and coming back was a pass. These pin the three properties that make the drill mean something:
 *
 *  1. the guard refuses [InterceptGuard.RELEASE_TRAINING] until the drill is solved — the page
 *     cannot grant itself, whatever button it reports;
 *  2. a wrong press never gets through, it only restarts the sequence;
 *  3. the drill is minted from the eventId, so it is stable across a retry and across a process
 *     death (a killed app must not come back to an easier drill).
 */
class InterceptDrillTest {

    private var wall = 1_793_340_000_000L
    private var seq = 0
    private var day = 20_000L
    private val bili = "tv.danmaku.bili"

    private fun book() = InterceptLedgerBook(MemorySnapshotStore()).also { it.recover() }

    private fun guard(book: InterceptLedgerBook = book(), store: MemorySnapshotStore = MemorySnapshotStore()) =
        InterceptGuard(store, book, { wall }) { "evt-" + (++seq) }.also { it.recover(wall) }

    private fun env() = InterceptEnv(screenOn = true, unlocked = true, dshBusy = false, minuteOfDay = 720, dayKey = day)

    private fun InterceptGuard.armed() = also { it.setConfig(InterceptConfig(enabled = true), wall) }

    private fun blocked(g: InterceptGuard): InterceptDecision.Block {
        val d = g.onForeground(bili, env(), wall)
        assertTrue("a configured target blocks", d is InterceptDecision.Block)
        return d as InterceptDecision.Block
    }

    private fun InterceptDrill.order() = if (ascending) candidates.sorted() else candidates.sortedDescending()

    // ---- 1. the guard is the one that decides ----------------------------------------------

    @Test
    fun trainingIsRefusedUntilTheDrillIsSolved() {
        val g = guard().armed()
        val block = blocked(g)
        g.onPageShown(block.eventId, wall)
        // The page may claim it passed; the guard does not take the page's word for it.
        assertFalse("an unsolved drill grants nothing", g.onReleased(block.eventId, InterceptGuard.RELEASE_TRAINING, wall))
        assertNull("nothing was recorded as released", g.receipts().firstOrNull())
        assertNotNull("the interception is still open", g.openEventId())

        for (v in block.drill.order()) assertTrue(g.tapDrill(block.eventId, v))
        assertTrue("solved", g.drill()!!.solved)
        assertTrue(g.onReleased(block.eventId, InterceptGuard.RELEASE_TRAINING, wall))
        assertEquals(InterceptOutcome.RELEASED, g.receipts().first().outcome)
    }

    @Test
    fun aWrongPressRestartsTheSequenceAndNeverPasses() {
        val g = guard().armed()
        val block = blocked(g)
        g.onPageShown(block.eventId, wall)
        val order = block.drill.order()
        // One correct press, then a wrong one: the progress is thrown away, not kept.
        assertTrue(g.tapDrill(block.eventId, order[0]))
        assertEquals(1, g.drill()!!.cursor)
        val wrong = block.drill.candidates.first { it != order[1] }
        assertTrue(g.tapDrill(block.eventId, wrong))
        assertEquals("progress restarted", 0, g.drill()!!.cursor)
        assertEquals("the wrong press is counted", 1, g.drill()!!.wrongAttempts)
        assertFalse("still not solved", g.drill()!!.solved)
        assertFalse(g.onReleased(block.eventId, InterceptGuard.RELEASE_TRAINING, wall))
    }

    @Test
    fun theDrillIsDeterministicForOneEventId() {
        assertEquals(InterceptDrill.of("evt-1"), InterceptDrill.of("evt-1"))
        assertEquals(InterceptDrill.of("evt-1").candidates, InterceptDrill.of("evt-1").candidates)
    }

    @Test
    fun aDifferentEventGetsADifferentDrillOftenEnough() {
        // Not a statistical claim about randomness: a retry must not be able to "reroll" for an
        // easier one, so two ids that differ must usually differ in what they ask.
        val drills = (1..40).map { InterceptDrill.of("evt-" + it) }
        assertTrue("drills are not all the same", drills.toSet().size > 1)
    }

    // ---- 2. what a process death must not do -----------------------------------------------

    @Test
    fun aKilledAppComesBackToTheSameDrillNotAnEasierOne() {
        val path = MemorySnapshotStore()
        val first = guard(store = path).armed()
        val block = blocked(first)
        val order = block.drill.order()
        assertTrue(first.tapDrill(block.eventId, order[0]))

        // The process dies mid-drill. A fresh guard reads the same store.
        val revived = guard(store = path).also { it.recover(wall) }
        // recover() records the open interception as ended (the page died with the process) and
        // clears the drill: nothing is granted, and the next attempt starts a fresh interception.
        assertNull("nothing is open after a restart", revived.openEventId())
        assertNull("and no drill is left over", revived.drill())

        // The next attempt is refused by the cooldown the first interception already spent, which is
        // the honest outcome: the person is not charged twice, and nothing is granted.
        val next = revived.onForeground(bili, env(), wall + 1_000L)
        assertTrue("a restart grants nothing", next !is InterceptDecision.Block)
        assertNull("still nothing open", revived.openEventId())
    }

    // ---- 3. only the open interception can be drilled --------------------------------------

    @Test
    fun aStalePageCannotDrillItsWayIntoAPassForSomeoneElse() {
        val g = guard().armed()
        val block = blocked(g)
        g.onPageShown(block.eventId, wall)
        assertFalse("a forged eventId", g.tapDrill("forged", block.drill.candidates.first()))
        assertEquals(0, g.drill()!!.cursor)
    }

    @Test
    fun everyCandidateIsAReadableSingleDigit() {
        val drill = InterceptDrill.of("evt-42")
        assertEquals(InterceptDrill.SIZE, drill.candidates.size)
        assertEquals("no repeats", drill.candidates.size, drill.candidates.toSet().size)
        assertTrue("one digit each", drill.candidates.all { it in 1..9 })
    }
}
