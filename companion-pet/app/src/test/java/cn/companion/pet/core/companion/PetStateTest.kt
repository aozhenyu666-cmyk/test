package cn.companion.pet.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class PetStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), zone)

    @Test
    fun stateStaysBounded() {
        val store = PetStateStore(tmp.root, clock)
        repeat(100) { store.apply(StateEvent.USER_REPLIED) }
        val state = store.current()
        assertTrue(state.closeness in 0.0..1.0)
        assertTrue(state.interest in 0.0..1.0)
        assertTrue(state.energy in 0.0..1.0)
    }

    @Test
    fun decayMovesTowardBaseline() {
        val start = PetState(energy = 1.0, interest = 0.0, closeness = 1.0, updatedAt = 1_000_000L)
        val after = start.decay(1_000_000L + 6L * 60L * 60L * 1000L, halfLifeHours = 6.0)
        assertEquals(0.8, after.energy, 0.001)
        assertEquals(0.25, after.interest, 0.001)
        assertEquals(0.675, after.closeness, 0.001)
        // 再半衰一次
        val later = after.decay(after.updatedAt + 6L * 60L * 60L * 1000L, 6.0)
        assertEquals(0.7, later.energy, 0.001)
    }

    @Test
    fun stateSurvivesRestart() {
        val first = PetStateStore(tmp.root, clock)
        first.apply(StateEvent.USER_REPLIED)
        val reopened = PetStateStore(tmp.root, clock)
        assertTrue(reopened.current().closeness > 0.35)
        assertEquals("他回了一句", reopened.current().lastReason)
    }

    @Test
    fun ignoredDoesNotPunishOnlyLowersInterest() {
        val store = PetStateStore(tmp.root, clock)
        val before = store.current()
        val after = store.apply(StateEvent.PROACTIVE_IGNORED)
        assertTrue(after.interest <= before.interest)
        assertEquals(before.closeness, after.closeness, 0.0001)
        assertEquals(before.energy, after.energy, 0.0001)
    }
}
