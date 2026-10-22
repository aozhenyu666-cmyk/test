package com.moling.core.atlas

import com.moling.core.think.Recall
import com.moling.core.workspace.ArtifactStatus
import com.moling.core.workspace.Artifacts
import com.moling.core.workspace.BlockKind
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.WorkspaceArtifact
import kotlinx.serialization.Serializable

/*
 * 候选记忆（v13 W10 T6，第十五批 B2）：案板上他采用过的论断，先变成一条条“候选”，他逐条点「收 / 不要」才进体系图。
 *
 *  - 只从他采用过的正文来（段落 / 引文）；练习靶子、待核的假设、批注一律不来，练习靶子就算混进来也收不进去；
 *  - 体系图里已经有几乎一样的、或者他以前点过「不要」的，不再推；
 *  - 和体系图里已有论断有关的，列出来让他看一眼（可能是支撑，也可能是冲突），不替他判断；
 *  - 收 / 不要 / 撤销都幂等：同一条收两次只建一个节点；撤销收下的会把那个节点删掉，回到待定；
 *  - 不调模型：收下就是原话进体系图（来源“案板（你确认的）”），把握按有没有材料依据给“中 / 低”。
 */

@Serializable
enum class CandidateStatus { PROPOSED, ACCEPTED, REJECTED }

@Serializable
data class MemoryCandidate(
    val id: String,
    val claim: String,
    val focusId: String = "",
    val artifactId: String = "",
    val artifactRevision: Long = 0,
    val sourceIds: List<String> = emptyList(),
    val exerciseOnly: Boolean = false,
    val status: CandidateStatus = CandidateStatus.PROPOSED,
    /** 体系图里可能相关 / 冲突的论断编号（给他看，不自动处理）。 */
    val related: List<String> = emptyList(),
    val createdAt: Long = 0,
    val decidedAt: Long = 0,
    /** 收下后在体系图里的节点编号。 */
    val nodeId: String? = null,
)

@Serializable
data class CandidateBook(val items: List<MemoryCandidate> = emptyList(), val seq: Long = 0) {
    fun pending() = items.filter { it.status == CandidateStatus.PROPOSED }
    fun get(id: String) = items.firstOrNull { it.id == id }
}

object MemoryCandidates {
    const val SOURCE = "案板（你确认的）"
    const val KEEP = 200

    /** 从一份稿子里提候选。返回新账本和这次新提的（已有的不重复提）。 */
    fun propose(book: CandidateBook, a: WorkspaceArtifact, atlas: Atlas, now: Long): Pair<CandidateBook, List<MemoryCandidate>> {
        var seq = book.seq
        val made = mutableListOf<MemoryCandidate>()
        val adopted = a.blocks.filter { (it.kind == BlockKind.MARKDOWN || it.kind == BlockKind.QUOTE) && !it.exerciseOnly && (it.accepted || a.status == ArtifactStatus.ACCEPTED) }
        for (b in adopted) {
            val claim = clean(b.markdown)
            if (claim.length < 4) continue
            // 已经在体系图里 / 已经提过（包括他点过“不要”的）：不再推
            if (atlas.nodes.any { AtlasOps.similar(it.claim, claim) }) continue
            if ((book.items + made).any { AtlasOps.similar(it.claim, claim) }) continue
            seq++
            made += MemoryCandidate(
                id = "mc-$seq",
                claim = claim.take(200),
                focusId = a.focusId,
                artifactId = a.id,
                artifactRevision = a.revision,
                sourceIds = b.sourceIds,
                related = related(atlas, claim),
                createdAt = now,
            )
        }
        val items = (book.items + made).let { all -> if (all.size > KEEP) all.filter { it.status != CandidateStatus.REJECTED }.takeLast(KEEP) else all }
        return CandidateBook(items, seq) to made
    }

    /**
     * 收下：建一个体系图节点（原话），候选标 ACCEPTED。已经收过且节点还在：原样返回，不重复建。
     * 练习靶子永远收不进去（直接标 REJECTED）。[topicId] 必须是体系图里存在的主题。
     */
    fun accept(book: CandidateBook, id: String, atlas: Atlas, topicId: String, now: Long): Triple<CandidateBook, Atlas, PortResult<String>> {
        val c = book.get(id) ?: return Triple(book, atlas, fail(PortError.NOT_FOUND, "这条候选不在了"))
        if (c.exerciseOnly) {
            val b = update(book, c.copy(status = CandidateStatus.REJECTED, decidedAt = now))
            return Triple(b, atlas, fail(PortError.BAD_ACTION, "这是练习用的靶子，不能当事实收进去"))
        }
        if (c.status == CandidateStatus.ACCEPTED && c.nodeId != null && atlas.nodes.any { it.id == c.nodeId }) {
            return Triple(book, atlas, PortResult.Ok(c.nodeId))
        }
        if (atlas.topics.none { it.id == topicId }) return Triple(book, atlas, fail(PortError.NOT_FOUND, "这个主题不在了"))
        val nodeId = "n-$now-c${id.removePrefix("mc-")}"
        val node = Node(
            id = nodeId,
            topicId = topicId,
            claim = c.claim,
            kind = NodeKind.JUDGMENT,
            source = SOURCE,
            confidence = if (c.sourceIds.any { it.startsWith("m-") || it.startsWith("n-") }) "中" else "低",
            createdAt = now,
            confirmed = true,
        )
        val nextAtlas = atlas.copy(nodes = atlas.nodes + node)
        return Triple(update(book, c.copy(status = CandidateStatus.ACCEPTED, decidedAt = now, nodeId = nodeId)), nextAtlas, PortResult.Ok(nodeId))
    }

    /** 不要：标 REJECTED（以后同样的话不再推）。已经收下的要先撤销。重复点不报错。 */
    fun reject(book: CandidateBook, id: String, now: Long): Pair<CandidateBook, PortResult<Unit>> {
        val c = book.get(id) ?: return book to fail(PortError.NOT_FOUND, "这条候选不在了")
        if (c.status == CandidateStatus.REJECTED) return book to PortResult.Ok(Unit)
        if (c.status == CandidateStatus.ACCEPTED) return book to fail(PortError.BAD_ACTION, "已经收下了，要先撤销")
        return update(book, c.copy(status = CandidateStatus.REJECTED, decidedAt = now)) to PortResult.Ok(Unit)
    }

    /** 撤销：收下的把节点（连同它的连线）删掉、回到待定；不要的回到待定。待定的不动。 */
    fun undo(book: CandidateBook, id: String, atlas: Atlas, now: Long): Triple<CandidateBook, Atlas, PortResult<Unit>> {
        val c = book.get(id) ?: return Triple(book, atlas, fail(PortError.NOT_FOUND, "这条候选不在了"))
        return when (c.status) {
            CandidateStatus.PROPOSED -> Triple(book, atlas, PortResult.Ok(Unit))
            CandidateStatus.REJECTED -> Triple(update(book, c.copy(status = CandidateStatus.PROPOSED, decidedAt = now)), atlas, PortResult.Ok(Unit))
            CandidateStatus.ACCEPTED -> {
                val nid = c.nodeId
                val a = if (nid == null) atlas else atlas.copy(nodes = atlas.nodes.filter { it.id != nid }, edges = atlas.edges.filter { it.from != nid && it.to != nid })
                Triple(update(book, c.copy(status = CandidateStatus.PROPOSED, decidedAt = now, nodeId = null)), a, PortResult.Ok(Unit))
            }
        }
    }

    /** 体系图里和这句话有关的（最多 3 条编号）。 */
    fun related(atlas: Atlas, claim: String): List<String> {
        val lines = atlas.nodes.map { "${it.id}\u0001${it.claim}" }
        return Recall.relevant(claim, lines, 3).map { it.substringBefore('\u0001') }
    }

    private fun clean(md: String) = md.lines().joinToString(" ") { it.trim().trimStart('#', '>', '-', '*', ' ') }
        .replace(Regex("""^\d+[.、]\s*"""), "").trim()

    private fun update(book: CandidateBook, c: MemoryCandidate) = book.copy(items = book.items.map { if (it.id == c.id) c else it })

    private fun <T> fail(code: String, msg: String): PortResult<T> = PortResult.Failed(PortError(code, msg))
}
