package io.github.ccch1mneyyy.dsh_app.loop.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D7 只读面板：主状态只认 `state.layers[0]`（§7.2 第 1 条），义务和额度照快照显示。 */
class PanelTest {
    @Test
    fun thePanelShowsTheStrictestLayerAndItsReason() {
        val plan = mapOf(
            "state" to mapOf(
                "state" to "standby",
                "layers" to listOf(
                    mapOf("kind" to "sprint", "why" to "任务没完，锁着"),
                    mapOf("kind" to "followup", "why" to "不该出现在面板上"),
                ),
            ),
        )
        val text = panelTextOf(plan)
        assertEquals("现在：冲刺 · 任务没完，锁着", text)
    }

    @Test
    fun aPlanWithoutStateIsSilentInsteadOfCrashing() {
        assertNull(panelTextOf(mapOf("v" to 1)))
        // 老引擎没有 state，就退回 rules —— 但面板没有可显示的主状态，剩什么显示什么。
        assertNull(panelTextOf(mapOf("state" to mapOf("layers" to emptyList<Any?>()))))
    }

    @Test
    fun obligationsAndTheWeeklyQuotaComeStraightFromTheSnapshot() {
        val plan = mapOf(
            "state" to mapOf("state" to "standby"),
            "obligations" to listOf(
                mapOf("kind" to "morning", "text" to "补今天立项"),
                mapOf("kind" to "review", "text" to "这轮不放风"),
                mapOf("kind" to "evening", "text" to "今晚不开申请"),
                mapOf("kind" to "standby", "text" to "第四条不显示"),
            ),
            "wall" to mapOf("relaxRemaining" to 2, "relaxToday" to false, "tightenToday" to false),
        )
        val lines = panelTextOf(plan)!!.split("\n")
        assertEquals("现在：待命", lines[0])
        assertEquals("义务：补今天立项；这轮不放风；今晚不开申请", lines[1])
        assertEquals("额度：本周还剩 2 次放松", lines[2])
    }

    @Test
    fun aTightenedDaySaysSoInsteadOfCountingQuota() {
        val plan = mapOf(
            "state" to mapOf("state" to "night"),
            "wall" to mapOf("relaxRemaining" to 3, "tightenToday" to true),
        )
        val text = panelTextOf(plan)!!
        assertTrue(text.contains("现在：深夜锁"))
        assertTrue(text.contains("额度：今天收紧了，不放风"))
    }
}
