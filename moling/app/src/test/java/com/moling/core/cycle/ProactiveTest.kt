package com.moling.core.cycle

import com.moling.core.mind.BEIJING
import com.moling.core.workspace.ArtifactKind
import com.moling.core.workspace.Focus
import com.moling.core.workspace.Material
import com.moling.core.workspace.WorkbenchState
import com.moling.core.workspace.WorkspaceArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** 她主动准备第一块：只在有材料、没稿子、等过他、预算够、不在安静期 / 夜里时，对同一版材料只做一次。 */
class ProactiveTest {
    private fun at(h: Int, mi: Int = 0) = LocalDateTime.of(2026, 10, 22, h, mi).atZone(BEIJING).toInstant().toEpochMilli()
    private val budget = AutonomyBudget(day = "2026-10-22", enabled = true)

    private fun ws(materialAt: Long = at(10), artifacts: Boolean = false) = WorkbenchState(
        focuses = listOf(Focus("f-1", "申论开头", startedAt = at(9), revision = 2, materialIds = listOf("m-1"))),
        materials = listOf(Material("m-1", "paste", "材料", "申论开头最容易卡。", materialAt)),
        artifacts = if (artifacts) listOf(WorkspaceArtifact("a-1", "f-1", ArtifactKind.OUTLINE, "纲要", emptyList(), createdAt = 1, updatedAt = 1)) else emptyList(),
    )

    @Test fun preparesOnceAfterWaiting() {
        val d = ProactivePrep.pick(ws(), at(10, 15), quiet = false, budget = budget, done = emptySet())
        assertEquals("f-1", d.focusId)
        assertEquals("f-1@2", d.key)
        assertNull("同一版材料只做一次", ProactivePrep.pick(ws(), at(10, 30), false, budget, setOf(d.key)).focusId)
    }

    @Test fun waitsForHimFirstAndIgnoresStaleMaterial() {
        assertNull(ProactivePrep.pick(ws(), at(10, 5), false, budget, emptySet()).focusId)
        assertNull("材料和这件事都放了超过 12 小时", ProactivePrep.pick(ws(materialAt = at(7)), at(21, 30), false, budget, emptySet()).focusId)
    }

    @Test fun respectsQuietNightBudgetAndExistingDrafts() {
        assertNull(ProactivePrep.pick(ws(), at(10, 15), quiet = true, budget = budget, done = emptySet()).focusId)
        assertNull(ProactivePrep.pick(ws(materialAt = at(22, 50)), at(23, 10), false, budget, emptySet()).focusId)
        assertNull(ProactivePrep.pick(ws(), at(10, 15), false, budget.copy(jobs = budget.maxJobs), emptySet()).focusId)
        assertNull(ProactivePrep.pick(ws(), at(10, 15), false, budget.copy(unread = budget.maxUnread), emptySet()).focusId)
        assertNull("预算没开就不做", ProactivePrep.pick(ws(), at(10, 15), false, budget.copy(enabled = false), emptySet()).focusId)
        assertNull(ProactivePrep.pick(ws(artifacts = true), at(10, 15), false, budget, emptySet()).focusId)
    }
}
