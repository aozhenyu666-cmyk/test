package com.moling.core.atlas

import com.moling.core.workspace.ArtifactBlock
import com.moling.core.workspace.ArtifactKind
import com.moling.core.workspace.ArtifactOption
import com.moling.core.workspace.ArtifactStatus
import com.moling.core.workspace.BlockKind
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.WorkspaceArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 候选记忆：只来自采用过的正文；练习靶子收不进；收 / 不要 / 撤销幂等；不重复推。 */
class MemoryCandidatesTest {
    private val topic = Topic("t-1", "申论")
    private val atlas0 = Atlas(
        topics = listOf(topic),
        nodes = listOf(Node("n-1-0", "t-1", "申论开头要先亮观点", NodeKind.METHOD, "用户")),
    )

    private fun artifact(status: ArtifactStatus = ArtifactStatus.READY_FOR_REVIEW) = WorkspaceArtifact(
        id = "a-1", focusId = "f-1", kind = ArtifactKind.OUTLINE, title = "纲要",
        blocks = listOf(
            ArtifactBlock("b-1", BlockKind.MARKDOWN, "1. 三点之间要有推进，不能换个说法重复", listOf("m-1"), accepted = true),
            ArtifactBlock("b-2", BlockKind.MARKDOWN, "2. 每一点都配一个材料里的例子", accepted = false),
            ArtifactBlock("b-3", BlockKind.ASSUMPTION, "3. 考官更喜欢开门见山", accepted = true),
            ArtifactBlock("b-4", BlockKind.CHOICE, "练习 · 找茬：「只要列了三点就一定不会卡」", options = listOf(ArtifactOption("o", "有漏洞")), exerciseOnly = true, accepted = true),
            ArtifactBlock("b-5", BlockKind.MARKDOWN, "申论开头要先亮观点。", accepted = true),
            ArtifactBlock("b-6", BlockKind.REVIEW_NOTE, "对手：太绝对", accepted = true),
        ),
        createdAt = 1, updatedAt = 1, revision = 4, status = status,
    )

    @Test fun onlyAdoptedContentNoDecoysNoAssumptionsNoDuplicates() {
        val (book, made) = MemoryCandidates.propose(CandidateBook(), artifact(), atlas0, 10)
        assertEquals(listOf("三点之间要有推进，不能换个说法重复"), made.map { it.claim })
        assertEquals(listOf("m-1"), made.single().sourceIds)
        assertEquals(4L, made.single().artifactRevision)
        // 再提一次：不重复
        val (book2, again) = MemoryCandidates.propose(book, artifact(), atlas0, 11)
        assertTrue(again.isEmpty())
        assertEquals(book.items, book2.items)
    }

    @Test fun wholeArtifactAcceptedBringsUnacceptedParagraphsToo() {
        val (_, made) = MemoryCandidates.propose(CandidateBook(), artifact(ArtifactStatus.ACCEPTED), atlas0, 10)
        assertEquals(2, made.size)
        assertTrue(made.none { it.claim.contains("一定不会卡") || it.claim.contains("开门见山") || it.claim.contains("太绝对") })
    }

    @Test fun acceptIsIdempotentAndUndoRemovesTheNode() {
        val (book, made) = MemoryCandidates.propose(CandidateBook(), artifact(), atlas0, 10)
        val id = made.single().id
        val (b1, a1, r1) = MemoryCandidates.accept(book, id, atlas0, "t-1", 20)
        val nodeId = (r1 as PortResult.Ok).value
        assertEquals(2, a1.nodes.size)
        val node = a1.nodes.first { it.id == nodeId }
        assertEquals(MemoryCandidates.SOURCE, node.source)
        assertEquals("有材料依据给“中”", "中", node.confidence)
        assertTrue(node.confirmed)
        val (b2, a2, r2) = MemoryCandidates.accept(b1, id, a1, "t-1", 21)
        assertEquals(nodeId, (r2 as PortResult.Ok).value)
        assertEquals("收两次只建一个节点", a1.nodes, a2.nodes)
        val (b3, a3, _) = MemoryCandidates.undo(b2, id, a2, 22)
        assertEquals(atlas0.nodes, a3.nodes)
        assertEquals(CandidateStatus.PROPOSED, b3.get(id)!!.status)
        assertNull(b3.get(id)!!.nodeId)
    }

    @Test fun rejectedIsNotProposedAgainAndCanBeUndone() {
        val (book, made) = MemoryCandidates.propose(CandidateBook(), artifact(), atlas0, 10)
        val id = made.single().id
        val (b1, r) = MemoryCandidates.reject(book, id, 20)
        assertTrue(r is PortResult.Ok)
        assertTrue("重复点不要不报错", MemoryCandidates.reject(b1, id, 21).second is PortResult.Ok)
        val (_, again) = MemoryCandidates.propose(b1, artifact(), atlas0, 30)
        assertTrue("点过不要的不再推", again.isEmpty())
        val (b2, _, _) = MemoryCandidates.undo(b1, id, atlas0, 31)
        assertEquals(CandidateStatus.PROPOSED, b2.get(id)!!.status)
    }

    @Test fun acceptedMustBeUndoneBeforeReject() {
        val (book, made) = MemoryCandidates.propose(CandidateBook(), artifact(), atlas0, 10)
        val (b1, _, _) = MemoryCandidates.accept(book, made.single().id, atlas0, "t-1", 20)
        val r = MemoryCandidates.reject(b1, made.single().id, 21).second
        assertEquals(PortError.BAD_ACTION, (r as PortResult.Failed).error.code)
    }

    @Test fun exerciseDecoyCanNeverBeAccepted() {
        val decoy = MemoryCandidate("mc-9", "只要列了三点就一定不会卡", exerciseOnly = true)
        val (b, a, r) = MemoryCandidates.accept(CandidateBook(listOf(decoy), 9), "mc-9", atlas0, "t-1", 5)
        assertEquals(PortError.BAD_ACTION, (r as PortResult.Failed).error.code)
        assertEquals(CandidateStatus.REJECTED, b.get("mc-9")!!.status)
        assertEquals(atlas0, a)
    }

    @Test fun relatedNodesAreListedNotJudged() {
        val atlas = atlas0.copy(nodes = atlas0.nodes + Node("n-2-0", "t-1", "三点之间常常互相重复", NodeKind.FACT, "用户"))
        val (_, made) = MemoryCandidates.propose(CandidateBook(), artifact(), atlas, 10)
        assertTrue(made.single().related.contains("n-2-0"))
    }

    @Test fun missingTopicFailsWithoutChangingAnything() {
        val (book, made) = MemoryCandidates.propose(CandidateBook(), artifact(), atlas0, 10)
        val (b, a, r) = MemoryCandidates.accept(book, made.single().id, atlas0, "t-nope", 20)
        assertEquals(PortError.NOT_FOUND, (r as PortResult.Failed).error.code)
        assertEquals(book, b)
        assertEquals(atlas0, a)
    }
}
