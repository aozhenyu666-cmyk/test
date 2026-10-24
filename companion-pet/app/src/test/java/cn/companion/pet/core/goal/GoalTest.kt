package cn.companion.pet.core.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GoalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun goalPersistsAcrossRestartAndPauses() {
        val store = GoalStore(tmp.root)
        val goal = store.addGoal("每天读一页", "连续三天", now = 1_000)!!
        assertTrue(store.setGoalStatus(goal.id, GoalStatus.PAUSED, 2_000))
        val reopened = GoalStore(tmp.root)
        assertEquals(GoalStatus.PAUSED, reopened.state().goals.single().status)
        assertFalse(reopened.deleteGoal("missing", 3_000))
        assertTrue(reopened.deleteGoal(goal.id, 3_000))
        assertTrue(reopened.state().goals.isEmpty())
        assertTrue(reopened.state().tombstones.contains(goal.id))
    }

    @Test
    fun corruptStoreDoesNotOverwriteOrPretendEmpty() {
        val file = java.io.File(tmp.root, "goals/state.json")
        file.parentFile.mkdirs()
        file.writeText("not-json")
        val store = GoalStore(tmp.root)
        assertTrue(store.state().goals.isEmpty())
        assertNull(store.addGoal("新目标", now = 1_000))
        assertEquals("not-json", file.readText())
    }

    @Test
    fun supervisorChoosesReadWriteWaitCompleteAndAsk() {
        val store = GoalStore(tmp.root)
        val supervisor = GoalSupervisor(store)
        assertEquals(GoalDecision.NO_ACTION, supervisor.decide(1_000, 5).decision)
        val goal = store.addGoal("目标", now = 1_000)!!
        assertEquals(GoalDecision.ASK_USER, supervisor.decide(1_000, 5).decision)
        val read = store.addTask(goal.id, "看一页", "READ_MEMORY", "read", now = 1_000)!!
        assertEquals(GoalDecision.READ_TOOL, supervisor.decide(1_000, 5).decision)
        assertEquals(GoalDecision.WAIT, supervisor.decide(1_000, 0).decision)
        store.setTaskStatus(read.id, TaskStatus.DONE, "读完", 2_000)
        assertEquals(GoalDecision.COMPLETE, supervisor.decide(2_000, 5).decision)
        store.setGoalStatus(goal.id, GoalStatus.ACTIVE, 3_000)
        store.addTask(goal.id, "写一条", "SAVE_NOTE", "write", now = 3_000)
        assertEquals(GoalDecision.PROPOSE_WRITE, supervisor.decide(3_000, 5).decision)
    }

    @Test
    fun followUpRoutesOnlyDueActiveGoals() {
        val store = GoalStore(tmp.root)
        val goal = store.addGoal("跟进", now = 1_000)!!
        store.addFollowUp(goal.id, "明天再看", 2_000)
        assertTrue(GoalEventRouter(store).route("NOTE", 1_500).isEmpty())
        assertEquals(listOf(goal.id), GoalEventRouter(store).route("NOTE", 2_000))
        store.setGoalStatus(goal.id, GoalStatus.PAUSED, 2_100)
        assertTrue(GoalEventRouter(store).route("NOTE", 2_500).isEmpty())
    }

    @Test
    fun episodeIsBoundedAndDeletedWithGoal() {
        val store = GoalStore(tmp.root, maxEpisodes = 2)
        val goal = store.addGoal("目标", now = 1_000)!!
        repeat(3) { store.recordEpisode(AgentEpisode("e" + it, goal.id, 1_000L + it, "kind", "summary")) }
        assertEquals(2, store.state().episodes.size)
        store.deleteGoal(goal.id, 2_000)
        assertTrue(store.state().episodes.isEmpty())
    }
}
