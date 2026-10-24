package cn.companion.pet.core.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GoalStoreIntegrityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun corruptFileIsReportedAndNeverOverwritten() {
        val file = File(tmp.root, "goals/state.json")
        file.parentFile.mkdirs()
        file.writeText("not-json")
        val store = GoalStore(tmp.root)
        assertTrue(store.state().goals.isEmpty())
        assertEquals(GoalStoreStatus.CORRUPT, store.status)
        assertNull(store.addGoal("新目标", now = 1_000))
        assertEquals(GoalStoreStatus.CORRUPT, store.status)
        assertEquals("not-json", file.readText())
    }

    @Test
    fun failedAtomicReplaceKeepsOldFileAndReportsFailure() {
        val normal = GoalStore(tmp.root)
        val old = normal.addGoal("旧目标", now = 1_000)!!
        val failing = GoalStore(tmp.root, replace = { _, _ -> false })
        assertNull(failing.addGoal("新目标", now = 2_000))
        assertEquals(GoalStoreStatus.IO_FAILURE, failing.status)
        val reopened = GoalStore(tmp.root)
        assertEquals(listOf(old.id), reopened.state().goals.map { it.id })
    }

    @Test
    fun capacityRejectsInsteadOfEvictingActiveGoals() {
        val store = GoalStore(tmp.root)
        repeat(GoalStore.MAX_GOALS) { assertTrue(store.addGoal("g" + it, now = 1_000L + it) != null) }
        assertNull(store.addGoal("overflow", now = 2_000))
        assertEquals(GoalStore.MAX_GOALS, store.state().goals.size)
        assertTrue(store.lastError!!.contains("上限"))
    }

    @Test
    fun concurrentWritesRemainConsistent() {
        val store = GoalStore(tmp.root)
        val pool = Executors.newFixedThreadPool(8)
        repeat(20) { i -> pool.submit { store.addGoal("g" + i, now = 1_000L + i) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(20, store.state().goals.size)
        assertEquals(20, GoalStore(tmp.root).state().goals.size)
    }

    @Test
    fun episodeCapDoesNotEvictGoalsOrTasks() {
        val store = GoalStore(tmp.root, maxEpisodes = 2)
        val goal = store.addGoal("目标", now = 1_000)!!
        val task = store.addTask(goal.id, "下一步", now = 1_000)!!
        repeat(5) { store.recordEpisode(AgentEpisode("e" + it, goal.id, 1_000L + it, "k", "s")) }
        assertEquals(1, store.state().goals.size)
        assertEquals(task.id, store.state().tasks.single().id)
        assertEquals(2, store.state().episodes.size)
    }
}
