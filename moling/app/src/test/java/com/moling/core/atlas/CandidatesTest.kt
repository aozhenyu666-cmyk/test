package com.moling.core.atlas

import com.moling.core.workspace.ArtifactBlock
import com.moling.core.workspace.ArtifactKind
import com.moling.core.workspace.ArtifactStatus
import com.moling.core.workspace.BlockKind
import com.moling.core.workspace.WorkspaceArtifact
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第十五批 W10 T6：候选记忆——来源 / 版本 / 练习靶子 / 冲突节点，收、不要、撤销幂等。 */
class CandidatesTest {

    private fun block(
        id: String,
        text: String,
        accepted: Boolean = true,
        exercise: Boolean = false,
        kind: BlockKind = BlockKind.MARKDOWN,
        sourceIds: List<String> = emptyList(),
    ) = ArtifactBlock(id, kind, text, sourceIds = sourceIds, exerciseOnly = exercise, accepted = accepted)

    private fun artifact(
        revision: Long = 1,
        kind: ArtifactKind = ArtifactKind.DRAFT,
        status: ArtifactStatus = ArtifactStatus.READY_FOR_REVIEW,
        blocks: List<ArtifactBlock> = listOf(block("b1", "开头先亮观点")),
    ) = WorkspaceArtifact(
        id = "a-1", focusId = "f-1", kind = kind, title = "稿子", blocks = blocks,
        createdAt = 1, updatedAt = 1, revision = revision, status = status,
    )

    private fun atlas(vararg claims: Pair<String, String>): Atlas = Atlas(
        topics = listOf(Topic("t-1", "元认知")),
        nodes = claims.map { (id, claim) -> Node(id, "t-1", claim, NodeKind.JUDGMENT, "用户") },
    )

    private fun proposals(state: CandidateState, now: Long = 10) = state.items

    private fun propose(
        state: CandidateState = CandidateState(),
        a: WorkspaceArtifact = artifact(),
        at: Atlas = Atlas(),
        now: Long = 10,
    ) = MemoryCandidates.propose(state, a, MemoryCandidates.SOURCE_BOARD, at, now)

    @Test fun proposalCarriesSourceRevisionAndOnlyAdoptedClaims() {
        val st = artifact(
            revision = 4,
            status = ArtifactStatus.ACCEPTED,
            blocks = listOf(
                block("b1", "开头先亮观点", sourceIds = listOf("m-1")),
                block("b2", "还没采用的段落", accepted = false),
                block("b3", "整份采用也算", accepted = false),
            ),
        )
        val (state, list) = propose(a = st)
        assertEquals(3, list.size)
        assertEquals(4, list.first().artifactRevision)
        assertEquals(MemoryCandidates.SOURCE_BOARD, list.first().source)
        assertEquals(listOf("m-1"), list.first().sourceIds)
        assertEquals(3, proposals(state).size)
        assertTrue(proposals(state).all { it.status == MemoryCandidateStatus.PROPOSED })
    }

    @Test fun unadoptedDraftOnlyProposesAdoptedBlocks() {
        val (_, list) = propose(a = artifact(blocks = listOf(block("b1", "采用过的"), block("b2", "没采用的", accepted = false))))
        assertEquals(1, list.size)
        assertEquals("采用过的", list.first().claim)
    }

    @Test fun proposingTwiceDoesNotDuplicateOrResetHisDecision() = runTest {
        val (first, list1) = propose()
        val id = list1.first().id
        val accepted = MemoryCandidates.accept(first, id, 1, false, 20) { listOf("n-1") }
        assertTrue(accepted.changed)
        val (again, list2) = propose(accepted.state)
        assertEquals("同一条候选不会多出一份", 1, again.items.size)
        assertEquals(id, list2.single().id)
        assertEquals("重开窗口不重置他的决定", MemoryCandidateStatus.ACCEPTED, list2.single().status)
    }

    @Test fun practiceTargetIsProposedButAlwaysRejected() = runTest {
        val a = artifact(
            blocks = listOf(
                block("b1", "正常的一段"),
                block("b2", "只要列了三点就一定不会卡", exercise = true),
            ),
        )
        val (state, list) = propose(a = a)
        assertEquals(2, list.size)
        val practice = list.first { it.claim.contains("一定") }
        assertTrue(practice.exerciseOnly)
        var asked = false
        val r = MemoryCandidates.accept(state, practice.id, 1, false, 20) { asked = true; listOf("n-9") }
        assertTrue(r.error.contains("练习靶子"))
        assertFalse(r.changed)
        assertFalse("练习靶子连模型都不该问", asked)
        assertEquals(MemoryCandidateStatus.PROPOSED, r.state.items.first { it.id == practice.id }.status)
    }

    @Test fun exerciseArtifactBlocksAreAllPractice() = runTest {
        val (_, list) = propose(a = artifact(kind = ArtifactKind.EXERCISE))
        assertTrue(list.single().exerciseOnly)
        val r = MemoryCandidates.accept(CandidateState(list), list.single().id, 1, false, 20) { listOf("n-1") }
        assertTrue(r.error.isNotBlank())
        assertFalse(r.changed)
    }

    @Test fun acceptIsIdempotentAndRecordsNewNodes() = runTest {
        val (state, list) = propose()
        var calls = 0
        val id = list.single().id
        val first = MemoryCandidates.accept(state, id, 1, false, 20) { calls++; listOf("n-1", "n-1") }
        assertTrue(first.changed)
        assertEquals(listOf("n-1"), first.candidate?.addedNodeIds)
        val again = MemoryCandidates.accept(first.state, id, 1, false, 21) { calls++; listOf("n-2") }
        assertFalse("重复点不重复加", again.changed)
        assertEquals(1, calls)
        assertEquals(1, again.state.items.size)
    }

    @Test fun undoRemovesOnlyNewNodesAndIsIdempotent() = runTest {
        val (state, list) = propose()
        val id = list.single().id
        val accepted = MemoryCandidates.accept(state, id, 1, false, 20) { listOf("n-1") }
        val undone = MemoryCandidates.undo(accepted.state, id, 30)
        assertTrue(undone.changed)
        assertEquals(listOf("n-1"), undone.removeNodeIds)
        assertEquals(MemoryCandidateStatus.PROPOSED, undone.candidate?.status)
        assertTrue(undone.candidate!!.addedNodeIds.isEmpty())
        val again = MemoryCandidates.undo(undone.state, id, 31)
        assertFalse(again.changed)
        assertTrue(again.removeNodeIds.isEmpty())
    }

    @Test fun mergedCandidateSucceedsButUndoDeletesNothing() = runTest {
        val at = atlas("n-1" to "开头先亮观点")
        val (state, list) = propose(at = at)
        assertEquals(listOf("n-1"), list.single().conflictsWith)
        val accepted = MemoryCandidates.accept(state, list.single().id, 1, false, 20) { emptyList() }
        assertTrue(accepted.changed)
        assertTrue("并进已有论断", accepted.merged)
        assertTrue(accepted.candidate!!.addedNodeIds.isEmpty())
        val undone = MemoryCandidates.undo(accepted.state, list.single().id, 30)
        assertTrue(undone.changed)
        assertTrue("已有论断不是他收进来的，撤销不能删", undone.removeNodeIds.isEmpty())
    }

    @Test fun changedRevisionOrMissingArtifactIsRefused() = runTest {
        val (state, list) = propose()
        val id = list.single().id
        var asked = false
        val stale = MemoryCandidates.accept(state, id, 2, false, 20) { asked = true; listOf("n-1") }
        assertTrue(stale.error.contains("过期"))
        assertFalse(stale.changed)
        assertFalse(asked)
        val gone = MemoryCandidates.accept(state, id, null, true, 20) { asked = true; listOf("n-1") }
        assertTrue(gone.error.contains("不在了"))
        assertFalse(gone.changed)
        assertEquals(MemoryCandidateStatus.PROPOSED, gone.state.items.single().status)
    }

    @Test fun failureToAddDoesNotPretendSuccess() = runTest {
        val (state, list) = propose()
        val r = MemoryCandidates.accept(state, list.single().id, 1, false, 20) { null }
        assertTrue(r.error.isNotBlank())
        assertFalse(r.changed)
        assertEquals(MemoryCandidateStatus.PROPOSED, r.state.items.single().status)
    }

    @Test fun rejectIsIdempotentAndCannotUndoWithoutThinking()  = runTest {
        val (state, list) = propose()
        val id = list.single().id
        val r1 = MemoryCandidates.reject(state, id, 20)
        assertTrue(r1.changed)
        assertEquals(MemoryCandidateStatus.REJECTED, r1.candidate?.status)
        val r2 = MemoryCandidates.reject(r1.state, id, 21)
        assertFalse("重复点不要也幂等", r2.changed)
        val acceptAfterReject = MemoryCandidates.accept(r1.state, id, 1, false, 22) { listOf("n-1") }
        assertTrue(acceptAfterReject.error.contains("不要"))
        assertFalse(acceptAfterReject.changed)
        val accepted = MemoryCandidates.accept(MemoryCandidates.undo(r1.state, id, 23).state, id, 1, false, 24) { listOf("n-1") }
        val rejectAccepted = MemoryCandidates.reject(accepted.state, id, 25)
        assertTrue(rejectAccepted.error.contains("撤销"))
        assertFalse(rejectAccepted.changed)
    }

    @Test fun newerRevisionSupersedesPendingCandidatesButKeepsDecidedHistory() = runTest {
        val rev1 = artifact(blocks = listOf(block("b1", "开头先亮观点"), block("b2", "三点要有推进")))
        val (s1, l1) = propose(a = rev1)
        val accepted = MemoryCandidates.accept(s1, l1.first().id, 1, false, 20) { listOf("n-1") }
        val rev2 = rev1.copy(revision = 2, blocks = rev1.blocks + block("b3", "每点配一个例子"))
        val (s2, l2) = propose(accepted.state, rev2, now = 30)
        assertEquals(3, l2.size)
        assertEquals("旧版本还没决定的不再摆出来", 3, s2.items.count { it.derivedFromArtifactId == "a-1" && it.artifactRevision == 2L })
        val history = s2.items.first { it.id == l1.first().id }
        assertEquals("已经收进去的记录留着（还能撤销）", MemoryCandidateStatus.ACCEPTED, history.status)
        val undo = MemoryCandidates.undo(s2, l1.first().id, 40)
        assertEquals(listOf("n-1"), undo.removeNodeIds)
    }

    @Test fun conflictsAreListedForSimilarExistingClaims() {
        val at = Atlas(
            topics = listOf(Topic("t-1", "元认知")),
            nodes = listOf(
                Node("n-1", "t-1", "开头先亮观点", NodeKind.JUDGMENT, "用户"),
                Node("n-2", "t-1", "每天睡够七小时", NodeKind.FACT, "用户"),
            ),
        )
        val (_, list) = propose(at = at)
        assertEquals(listOf("n-1"), list.single().conflictsWith)
    }

    @Test fun missingCandidateIsReportedNotCrashing() = runTest {
        val r = MemoryCandidates.accept(CandidateState(), "mc-nope", 1, false, 20) { listOf("n-1") }
        assertTrue(r.error.isNotBlank())
        assertFalse(r.changed)
        assertNull(r.candidate)
        val rej = MemoryCandidates.reject(CandidateState(), "mc-nope", 20)
        assertTrue(rej.error.isNotBlank())
        val undo = MemoryCandidates.undo(CandidateState(), "mc-nope", 20)
        assertTrue(undo.error.isNotBlank())
    }

    @Test fun candidateStateRoundTripsThroughJson() {
        val at = atlas("n-1" to "开头先亮观点")
        val (s, _) = propose(at = at)
        val json = Json.encodeToString(s)
        val back = Json.decodeFromString<CandidateState>(json)
        assertEquals(s, back)
    }
}
