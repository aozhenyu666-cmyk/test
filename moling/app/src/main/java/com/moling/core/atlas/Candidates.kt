package com.moling.core.atlas

import com.moling.core.workspace.ArtifactBlock
import com.moling.core.workspace.ArtifactKind
import com.moling.core.workspace.ArtifactStatus
import com.moling.core.workspace.BlockKind
import com.moling.core.workspace.WorkspaceArtifact
import kotlinx.serialization.Serializable

/*
 * 候选记忆（第十五批 W10 T6）：「收进体系图」不再一口气收，先变成一条条候选，他逐条「收 / 不要」。
 *
 * 规矩：
 *  - 一条候选记着来源（这一批是案板）、从哪份稿子的第几版来、依据的材料编号、和体系图里哪些已有论断
 *    几乎一样（收的时候会并到它上面）；稿子改版 / 来源没了，这条候选就不作数，不会假装收进去。
 *  - 接受 / 拒绝 / 撤销都幂等：重复点只算一次，不重复加节点，也不重复删。
 *  - 练习靶子永远不能收。界面不画「收」按钮，核心也再挡一次——两边各有一道。
 *  - 这个模块只做纯逻辑（不调模型）；真正把论断织进体系图由宿主（BrainHost）调大脑完成，
 *    Brain.keepClaims 的旧行为一个字都不改。
 */

@Serializable
enum class MemoryCandidateStatus { PROPOSED, ACCEPTED, REJECTED }

@Serializable
data class MemoryCandidate(
    val id: String,
    val claim: String,
    /** 来源：这一批都是「案板」。 */
    val source: String = "",
    /** 从哪份稿子来；null = 不是稿子来的（留给以后别的来源）。 */
    val derivedFromArtifactId: String? = null,
    /** 稿子当时是第几版：稿子改版以后，这条候选不再作数。 */
    val artifactRevision: Long = 0,
    /** 依据的材料 / 论断编号（只搬稿子块上真有的，不编）。 */
    val sourceIds: List<String> = emptyList(),
    /** 练习靶子：永远不能收进体系图。 */
    val exerciseOnly: Boolean = false,
    /** 体系图里已有的、几乎一样的论断（收的时候会并到它上面）。 */
    val conflictsWith: List<String> = emptyList(),
    val status: MemoryCandidateStatus = MemoryCandidateStatus.PROPOSED,
    val createdAt: Long = 0,
    val decidedAt: Long = 0,
    /** 收进去以后真正新增的节点 id（撤销时删掉；空 = 并进已有论断，没有新增）。 */
    val addedNodeIds: List<String> = emptyList(),
)

@Serializable
data class CandidateState(val items: List<MemoryCandidate> = emptyList())

/**
 * 一次「收 / 不要 / 撤销」的结果。[error] 非空时 [state] 和原来一模一样（什么都没成）；
 * [changed] = false 表示这是重复点，幂等地什么都不做。
 */
data class CandidateResult(
    val state: CandidateState,
    val candidate: MemoryCandidate? = null,
    val error: String = "",
    val changed: Boolean = false,
    /** 收的时候并进已有论断、没有新增节点。 */
    val merged: Boolean = false,
    /** 撤销时需要从体系图删掉的节点（只删他收进来时真正新增的）。 */
    val removeNodeIds: List<String> = emptyList(),
)

object MemoryCandidates {
    const val SOURCE_BOARD = "案板"

    /** 候选最多留这么多条；还没决定的一律留着，先淘汰旧的已决定记录。 */
    const val KEEP = 200

    // ------------------------------------------------------------------ 生成

    /**
     * 从一份稿子生成候选。已经有的候选原样保留（不重置他的决定），只补新的；
     * 同一份稿子旧版本留下的、还没决定的候选会被这一版替掉。
     * 返回（新的状态，这一版全部候选——按稿子块顺序）。
     */
    fun propose(
        state: CandidateState,
        artifact: WorkspaceArtifact,
        source: String,
        atlas: Atlas,
        now: Long,
    ): Pair<CandidateState, List<MemoryCandidate>> {
        val generated = mutableListOf<MemoryCandidate>()
        val seen = mutableSetOf<String>()
        artifact.blocks.forEach { b ->
            if (b.kind != BlockKind.MARKDOWN && b.kind != BlockKind.QUOTE) return@forEach
            val practice = b.exerciseOnly || artifact.kind == ArtifactKind.EXERCISE
            // 普通正文要他采用过（或者整份采用）才算候选；练习靶子也列出来，但标成不能收。
            if (!practice && !(b.accepted || artifact.status == ArtifactStatus.ACCEPTED)) return@forEach
            val claim = claimOf(b)
            if (claim.length < 4) return@forEach
            val id = idFor(artifact.id, artifact.revision, claim)
            if (!seen.add(id)) return@forEach
            val old = state.items.firstOrNull { it.id == id }
            val c = (old ?: MemoryCandidate(id = id, claim = claim, createdAt = now)).copy(
                claim = claim,
                source = source,
                derivedFromArtifactId = artifact.id,
                artifactRevision = artifact.revision,
                sourceIds = b.sourceIds.distinct(),
                exerciseOnly = practice,
            )
            generated += if (c.status == MemoryCandidateStatus.PROPOSED) c.copy(conflictsWith = conflicts(atlas, claim)) else c
        }
        val byId = generated.associateBy { it.id }
        val kept = state.items.mapNotNull { old ->
            if (old.derivedFromArtifactId != artifact.id) return@mapNotNull old
            if (old.status != MemoryCandidateStatus.PROPOSED) return@mapNotNull old
            byId[old.id]
        }
        val merged = (kept + generated.filter { g -> kept.none { it.id == g.id } })
        return prune(state.copy(items = merged)) to generated
    }

    // ------------------------------------------------------------------ 读

    /** 某份稿子这一版还摆得出来的候选（按生成顺序）。 */
    fun forArtifact(state: CandidateState, artifactId: String, revision: Long? = null): List<MemoryCandidate> =
        state.items.filter { it.derivedFromArtifactId == artifactId && (revision == null || it.artifactRevision == revision) }

    /** 还没决定的候选。 */
    fun pending(state: CandidateState): List<MemoryCandidate> =
        state.items.filter { it.status == MemoryCandidateStatus.PROPOSED }

    /** 体系图里和 [claim] 几乎一样的已有论断（收的时候会并过去）。 */
    fun conflicts(atlas: Atlas, claim: String): List<String> =
        atlas.nodes.filter { AtlasOps.similar(it.claim, claim) }.map { it.id }.take(5)

    // ------------------------------------------------------------------ 收 / 不要 / 撤销

    /**
     * 收一条。真正加节点由 [add] 做：返回新增的节点 id，null = 这次没接上（不标成功）；
     * 空列表 = 并进已有论断、没有新增（算成功，但不重复加）。
     * [expectedRevision] 是稿子现在的版本（稿子不在了传 null）；对不上就拒绝，不误报收进成功。
     */
    suspend fun accept(
        state: CandidateState,
        id: String,
        expectedRevision: Long?,
        sourceGone: Boolean,
        now: Long,
        add: suspend (MemoryCandidate) -> List<String>?,
    ): CandidateResult {
        val c = state.items.firstOrNull { it.id == id } ?: return CandidateResult(state, error = "这条候选不在了")
        if (c.exerciseOnly) return CandidateResult(state, c, error = "练习靶子永远不能收进体系图")
        when (c.status) {
            MemoryCandidateStatus.ACCEPTED -> return CandidateResult(state, c)
            MemoryCandidateStatus.REJECTED -> return CandidateResult(state, c, error = "这条你点过「不要」了；先撤销那一下，再收")
            MemoryCandidateStatus.PROPOSED -> {}
        }
        if (sourceGone || expectedRevision == null) {
            return CandidateResult(state, c, error = "这条候选的来源（稿子）已经不在了，没收")
        }
        if (expectedRevision != c.artifactRevision) {
            return CandidateResult(
                state, c,
                error = "稿子已经改到第 $expectedRevision 版，这条按第 ${c.artifactRevision} 版做的候选过期了；重新打开「收进体系图」生成新的",
            )
        }
        val added = add(c) ?: return CandidateResult(state, c, error = "刚才没接上，这条没收进去（候选还在）")
        val done = c.copy(
            status = MemoryCandidateStatus.ACCEPTED,
            decidedAt = now,
            addedNodeIds = added.filter { it.isNotBlank() }.distinct(),
        )
        return CandidateResult(replace(state, done), done, changed = true, merged = done.addedNodeIds.isEmpty())
    }

    /** 不要这条。重复点什么都不做；已经收进去的要先撤销，不能直接改成不要。 */
    fun reject(state: CandidateState, id: String, now: Long): CandidateResult {
        val c = state.items.firstOrNull { it.id == id } ?: return CandidateResult(state, error = "这条候选不在了")
        if (c.status == MemoryCandidateStatus.REJECTED) return CandidateResult(state, c)
        if (c.status == MemoryCandidateStatus.ACCEPTED) {
            return CandidateResult(state, c, error = "这条已经收进体系图了；要退回先撤销")
        }
        val done = c.copy(status = MemoryCandidateStatus.REJECTED, decidedAt = now)
        return CandidateResult(replace(state, done), done, changed = true)
    }

    /**
     * 撤销上一次决定，回到「还没决定」。收过的那条会给出要删的节点 id（并进已有论断的没有新增，就不删）；
     * 重复撤销什么都不做。
     */
    fun undo(state: CandidateState, id: String, now: Long): CandidateResult {
        val c = state.items.firstOrNull { it.id == id } ?: return CandidateResult(state, error = "这条候选不在了")
        if (c.status == MemoryCandidateStatus.PROPOSED) return CandidateResult(state, c)
        val remove = if (c.status == MemoryCandidateStatus.ACCEPTED) c.addedNodeIds else emptyList()
        val done = c.copy(status = MemoryCandidateStatus.PROPOSED, decidedAt = 0, addedNodeIds = emptyList())
        return CandidateResult(replace(state, done), done, changed = true, removeNodeIds = remove)
    }

    // ------------------------------------------------------------------ 工具

    /** 稿子这一版里一条论断的稳定编号：同一份稿子同一版同一句话，永远是同一个 id（重复生成不会变多）。 */
    fun idFor(artifactId: String, revision: Long, claim: String): String {
        val key = artifactId + "|" + revision + "|" + claim.split(Regex("\\s+")).joinToString(" ")
        var h = -2128831035 // 0x811c9dc5
        key.forEach { ch ->
            h = h xor ch.code
            h *= 16777619
        }
        return "mc-" + (h.toLong() and 0xffffffffL).toString(16).padStart(8, '0')
    }

    fun prune(state: CandidateState, keep: Int = KEEP): CandidateState {
        if (state.items.size <= keep) return state
        val waiting = state.items.filter { it.status == MemoryCandidateStatus.PROPOSED }
        val decided = state.items.filter { it.status != MemoryCandidateStatus.PROPOSED }
        val room = (keep - waiting.size).coerceAtLeast(0)
        return state.copy(items = (waiting + decided.takeLast(room)).sortedBy { it.createdAt })
    }

    private fun replace(state: CandidateState, c: MemoryCandidate): CandidateState =
        state.copy(items = state.items.map { if (it.id == c.id) c else it })

    /** 和 Artifacts.claims 同一套正文取法（练习靶子在这里不排除，改成打标记由核心再拒）。 */
    private fun claimOf(b: ArtifactBlock): String =
        b.markdown.lines().joinToString(" ") { l -> l.trim().trimStart('#', '>', '-', '*', ' ') }.trim()
}
