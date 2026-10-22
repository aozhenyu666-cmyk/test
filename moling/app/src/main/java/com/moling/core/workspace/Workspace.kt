package com.moling.core.workspace

import kotlinx.serialization.Serializable

/*
 * 案板（v13 第九批）：他给两三句材料，她先做一块（纲要 / 初稿），他在上面挑刺、选路线、改段落，最后拿走一份能复制的稿子。
 *
 *  - 焦点 [Focus]：他眼下要攻的一件事（一个问题 + 材料）；同时只有一个“正在做”。
 *  - 稿子 [WorkspaceArtifact]：一块块 [ArtifactBlock]（段落 / 引文 / 待核的假设 / 选择 / 批注），每改一次 revision + 1。
 *  - 动作 [ArtifactAction]：带“我看到的是第几版”（expectedRevision）；版本对不上就退回“版本冲突”，同一个动作 id 重复提交只算一次。
 *  - 作业 [PreworkJob]：她替他做的一块（纲要、初稿、挑刺、改写、四参谋）；作业结果回来时焦点 / 稿子已经变了，就作废，不覆盖他的修改。
 *
 * 和大脑分开存（mind/workbench.json），不往 BrainState 里塞。这里全是纯数据和纯函数；调模型、加锁、存盘在 [Workbench]。
 * 契约来源：partner-blueprint/v13/V13Contracts.kt（这是生产版，字段按本波需要取舍）。
 */

@Serializable
enum class FocusStatus { ACTIVE, PAUSED, ARCHIVED }

/** 他给的材料：粘贴的几句、从体系图选的一个主题、分享进来的文字。 */
@Serializable
data class Material(
    val id: String,
    /** paste / atlas / share */
    val kind: String,
    val title: String,
    val text: String,
    val at: Long,
)

@Serializable
data class Focus(
    val id: String,
    val question: String,
    val goal: String = "",
    val status: FocusStatus = FocusStatus.ACTIVE,
    val startedAt: Long,
    /** 他最后一次碰这件事之后两小时（[FocusLife.ACTIVE_MS]）；0 = 旧记录，按最后动静算。 */
    val expiresAt: Long = 0,
    /** 收起（到期 / 他切走）的时候；0 = 没收起过。 */
    val pausedAt: Long = 0,
    /** 为什么收起：expired = 两小时没动自己收起；switched = 他开了 / 切到别的事。 */
    val pauseReason: String = "",
    /** 材料或问题变了就 + 1；基于旧版本的作业结果回来时作废。 */
    val revision: Long = 1,
    val materialIds: List<String> = emptyList(),
    val artifactIds: List<String> = emptyList(),
    /** 她建议的下一步（界面上一个按钮）。 */
    val nextChoice: String? = null,
)

@Serializable
enum class ArtifactKind { SOURCE_PACK, OUTLINE, DRAFT, COUNCIL_REVIEW, DISCOVERY, EXERCISE, ACTOR_RESULT }

@Serializable
enum class ArtifactStatus { DRAFT, READY_FOR_REVIEW, ACCEPTED, ARCHIVED }

@Serializable
enum class BlockKind { MARKDOWN, QUOTE, ASSUMPTION, CHOICE, REVIEW_NOTE }

/** 一条路线：A / B。[markdown] = 选它之后目标段落变成什么；[targetBlockId] = 改哪一块。 */
@Serializable
data class ArtifactOption(
    val id: String,
    val label: String,
    val markdown: String = "",
    val explanation: String = "",
    val targetBlockId: String? = null,
)

@Serializable
data class ArtifactBlock(
    val id: String,
    val kind: BlockKind,
    val markdown: String,
    /** 依据：材料 id（m-…）或体系图论断 id（n-…）。空 = 没有依据（ASSUMPTION 一定要在界面上标“待核”）。 */
    val sourceIds: List<String> = emptyList(),
    val options: List<ArtifactOption> = emptyList(),
    /** 练习用的靶子（故意带漏洞）：不导出、不进体系图。 */
    val exerciseOnly: Boolean = false,
    /** 他点过“采用”。 */
    val accepted: Boolean = false,
    /** REVIEW_NOTE：批的是哪一块。 */
    val targetBlockId: String? = null,
    /** REVIEW_NOTE：谁说的（对手 / 教练 / 军师 / 朋友）。 */
    val voice: String = "",
)

@Serializable
data class WorkspaceArtifact(
    val id: String,
    val focusId: String,
    val kind: ArtifactKind,
    val title: String,
    val blocks: List<ArtifactBlock>,
    val sourceIds: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val status: ArtifactStatus = ArtifactStatus.DRAFT,
    val parentArtifactId: String? = null,
    val branchId: String = "main",
    val producerJobId: String? = null,
    /** 待核 / 待他回答的问题。 */
    val unresolvedQuestions: List<String> = emptyList(),
    /** 这一块是本地兜底做的（模型没接上）：界面提示“离线骨架”。 */
    val offline: Boolean = false,
)

/** 撤销栈里的一格：[snapshot] 是改之前的样子，[note] 是这一下做了什么。 */
@Serializable
data class ArtifactVersion(
    val artifactId: String,
    val at: Long,
    val note: String,
    val snapshot: WorkspaceArtifact,
)

@Serializable
enum class ArtifactActionKind { EDIT_BLOCK, ACCEPT_BLOCK, DELETE_BLOCK, CHOOSE_BRANCH, REQUEST_CRITIC, REWRITE_BLOCK, ACCEPT_ARTIFACT, UNDO }

/**
 * 他在稿子上的一下。[expectedRevision] = 他点的时候看到的版本；[text] = EDIT_BLOCK 的新内容 / REWRITE_BLOCK 的要求（可空）。
 * [id] 用 [Artifacts.actionId] 生成：同一版本上同一个按钮连点，id 相同，只算一次。
 */
@Serializable
data class ArtifactAction(
    val id: String,
    val artifactId: String,
    val expectedRevision: Long,
    val kind: ArtifactActionKind,
    val blockId: String? = null,
    val optionId: String? = null,
    val text: String? = null,
    val at: Long,
)

@Serializable
data class PortError(val code: String, val message: String) {
    companion object {
        const val NOT_FOUND = "NOT_FOUND"
        const val REVISION_CONFLICT = "REVISION_CONFLICT"
        const val BAD_ACTION = "BAD_ACTION"
        /** 模型没接上，这一下只能他自己改。 */
        const val OFFLINE = "OFFLINE"
        /** 结果回来时稿子 / 焦点已经变了：作废，不覆盖。 */
        const val STALE = "STALE"
        /** 同一件事还在做。 */
        const val BUSY = "BUSY"
        const val CANCELLED = "CANCELLED"
        const val INTERRUPTED = "INTERRUPTED"
    }
}

sealed class PortResult<out T> {
    data class Ok<T>(val value: T) : PortResult<T>()
    data class Failed(val error: PortError) : PortResult<Nothing>()

    fun valueOrNull(): T? = (this as? Ok<T>)?.value
}

@Serializable
enum class JobKind(val label: String) { OUTLINE("三点纲要"), DRAFT("初稿"), CRITIC("对手挑刺"), REWRITE("改写"), COUNCIL("四参谋") }

@Serializable
enum class JobStatus { QUEUED, RUNNING, WAITING_INPUT, READY, FAILED, CANCELLED, SUPERSEDED }

@Serializable
data class PreworkJob(
    val id: String,
    val kind: JobKind,
    val focusId: String,
    val focusRevision: Long,
    val taskBrief: String,
    val sourceIds: List<String> = emptyList(),
    /** 同样的输入（焦点版本 + 材料 + 任务）只做一次。 */
    val inputHash: String,
    val status: JobStatus,
    val createdAt: Long,
    val deadline: Long,
    val modelAttempts: Int = 0,
    /** 产出 / 作用的稿子。 */
    val artifactId: String? = null,
    /** CRITIC / REWRITE：作用的块和当时的内容（结果回来时内容变了就作废）。 */
    val blockId: String? = null,
    val baseMarkdown: String? = null,
    /** CRITIC / REWRITE：触发它的动作 id（重复提交认得出来）。 */
    val actionId: String? = null,
    val error: PortError? = null,
    val finishedAt: Long = 0,
)

@Serializable
data class WorkbenchState(
    val focuses: List<Focus> = emptyList(),
    val materials: List<Material> = emptyList(),
    val artifacts: List<WorkspaceArtifact> = emptyList(),
    val history: List<ArtifactVersion> = emptyList(),
    val jobs: List<PreworkJob> = emptyList(),
    /** 处理过的动作 id → 处理后的版本（重复提交返回同一个结果）。 */
    val done: Map<String, Long> = emptyMap(),
    val seq: Long = 0,
    val log: List<String> = emptyList(),
) {
    fun active(): Focus? = focuses.lastOrNull { it.status == FocusStatus.ACTIVE }
    fun focus(id: String) = focuses.firstOrNull { it.id == id }
    fun artifact(id: String) = artifacts.firstOrNull { it.id == id }
    fun artifactsOf(focusId: String) = artifacts.filter { it.focusId == focusId && it.status != ArtifactStatus.ARCHIVED }
    fun materialsOf(f: Focus) = materials.filter { it.id in f.materialIds }
    fun job(id: String) = jobs.firstOrNull { it.id == id }
    /** 撤销栈（新的在后）。 */
    fun versions(artifactId: String) = history.filter { it.artifactId == artifactId }
}

/**
 * 焦点的寿命（v13 W10 T8）：一件事默认活跃两小时，从他最后一次碰它算起（开、切回来、补材料、在稿子上动一下）。
 * 到期就收起成“可以续上”：材料、稿子、撤销历史都不删；他点续上就是同一件事（同一个 id），不另起题。
 * 她在做的代工还没回来时不收起（免得结果回来找不到人）。
 */
object FocusLife {
    const val ACTIVE_MS = 2 * 3_600_000L
    const val EXPIRED = "expired"
    const val SWITCHED = "switched"

    /** 他最后一次碰这件事的时间。 */
    fun lastTouch(st: WorkbenchState, f: Focus): Long = maxOf(
        f.startedAt,
        if (f.expiresAt > 0) f.expiresAt - ACTIVE_MS else 0L,
        st.materialsOf(f).maxOfOrNull { it.at } ?: 0L,
        st.artifacts.filter { it.focusId == f.id }.maxOfOrNull { it.updatedAt } ?: 0L,
    )

    fun dueAt(st: WorkbenchState, f: Focus): Long = lastTouch(st, f) + ACTIVE_MS

    private fun busy(st: WorkbenchState, f: Focus, now: Long) =
        st.jobs.any { it.focusId == f.id && (it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED) && now < it.deadline }

    /** 到期的那件“正在做”的事收起成可续上；没变返回原对象。 */
    fun expire(st: WorkbenchState, now: Long): WorkbenchState {
        val due = st.focuses.filter { it.status == FocusStatus.ACTIVE && now >= dueAt(st, it) && !busy(st, it, now) }.map { it.id }.toSet()
        if (due.isEmpty()) return st
        return st.copy(focuses = st.focuses.map {
            if (it.id in due) it.copy(status = FocusStatus.PAUSED, pausedAt = now, pauseReason = EXPIRED) else it
        })
    }

    /** 他碰了一下：再活两小时。 */
    fun touch(f: Focus, now: Long): Focus = f.copy(expiresAt = maxOf(f.expiresAt, now + ACTIVE_MS))

    /** 收起的事上给他看的一行：多久前收起的、有几份稿子、几条材料。 */
    fun resumeLine(st: WorkbenchState, f: Focus, now: Long): String {
        val drafts = st.artifactsOf(f.id).size
        val mats = f.materialIds.size
        val ago = ((now - (if (f.pausedAt > 0) f.pausedAt else lastTouch(st, f))) / 60_000L).coerceAtLeast(0)
        val whenText = when {
            ago < 60 -> "$ago 分钟前"
            ago < 24 * 60 -> "${ago / 60} 小时前"
            else -> "${ago / (24 * 60)} 天前"
        }
        val why = if (f.pauseReason == EXPIRED) "两小时没动，先收起了" else "收起"
        return "$why · $whenText · 材料 $mats 条 · 稿子 $drafts 份 · 点一下续上（原样接着做）"
    }
}

/** 稿子上的纯操作（不调模型、不加锁）。 */
object Artifacts {
    const val HISTORY_KEEP = 30
    const val DONE_KEEP = 300
    const val JOBS_KEEP = 60

    /** 同一版本上同一个按钮 → 同一个 id（连点、两个界面同时报都只算一次）。 */
    fun actionId(a: WorkspaceArtifact, kind: ArtifactActionKind, blockId: String? = null, optionId: String? = null, text: String? = null): String =
        "${a.id}@${a.revision}:${kind.name}:${blockId.orEmpty()}:${optionId.orEmpty()}" + (text?.let { ":" + it.hashCode().toUInt().toString(16) } ?: "")

    /** 正文块（他能挑刺 / 采用 / 改的）：段落、引文、假设。 */
    fun isContent(b: ArtifactBlock) = b.kind == BlockKind.MARKDOWN || b.kind == BlockKind.QUOTE || b.kind == BlockKind.ASSUMPTION

    /** 第一个正文段（“改首段”用）。 */
    fun firstContent(a: WorkspaceArtifact): ArtifactBlock? = a.blocks.firstOrNull { isContent(it) && !it.exerciseOnly }

    /**
     * 不用模型的动作：编辑、采用、删、选路线、整份采用。成功返回新版本（revision + 1）和撤销栈里那一格的说明。
     * 版本号、幂等由调用方查；REQUEST_CRITIC / REWRITE_BLOCK / UNDO 不在这里。
     */
    fun applyLocal(a: WorkspaceArtifact, action: ArtifactAction, now: Long): Pair<WorkspaceArtifact, String>? {
        fun block(id: String?) = a.blocks.firstOrNull { it.id == id }
        fun bump(blocks: List<ArtifactBlock>, status: ArtifactStatus = a.status) =
            a.copy(blocks = blocks, revision = a.revision + 1, updatedAt = now, status = status)
        return when (action.kind) {
            ArtifactActionKind.EDIT_BLOCK -> {
                val b = block(action.blockId)?.takeIf { isContent(it) } ?: return null
                val t = action.text?.trim()?.takeIf { it.isNotBlank() } ?: return null
                if (t == b.markdown) return null
                // 他亲手改过的就不再是“待核的假设”之外的东西：种类不变，采用标记清掉（内容变了要重新采用）
                bump(a.blocks.map { if (it.id == b.id) it.copy(markdown = t.take(4000), accepted = false) else it }) to "改了「${label(b)}」"
            }
            ArtifactActionKind.ACCEPT_BLOCK -> {
                val b = block(action.blockId)?.takeIf { isContent(it) && !it.accepted } ?: return null
                bump(a.blocks.map { if (it.id == b.id) it.copy(accepted = true) else it }) to "采用「${label(b)}」"
            }
            ArtifactActionKind.DELETE_BLOCK -> {
                val b = block(action.blockId) ?: return null
                // 删一块，批它的批注一起删
                bump(a.blocks.filterNot { it.id == b.id || it.targetBlockId == b.id }) to "删了「${label(b)}」"
            }
            ArtifactActionKind.CHOOSE_BRANCH -> {
                val note = block(action.blockId) ?: return null
                val opt = note.options.firstOrNull { it.id == action.optionId } ?: return null
                val targetId = opt.targetBlockId ?: note.targetBlockId
                val target = block(targetId)
                when {
                    // 批注上的路线：目标段落换成这条路线的写法，批注收起
                    target != null && opt.markdown.isNotBlank() ->
                        bump(
                            a.blocks.mapNotNull {
                                when (it.id) {
                                    target.id -> it.copy(markdown = opt.markdown.take(4000), accepted = false)
                                    note.id -> null
                                    else -> it
                                }
                            },
                        ) to "选了${opt.label}（${label(target)}）"
                    // 选择块（比如练习靶子）：记下他选了哪个，块留着
                    note.kind == BlockKind.CHOICE ->
                        bump(a.blocks.map { if (it.id == note.id) it.copy(accepted = true, options = it.options.filter { o -> o.id == opt.id }) else it }) to "选了「${opt.label}」"
                    else -> null
                }
            }
            ArtifactActionKind.ACCEPT_ARTIFACT -> {
                if (a.status == ArtifactStatus.ACCEPTED) return null
                bump(a.blocks, ArtifactStatus.ACCEPTED) to "整份采用"
            }
            else -> null
        }
    }

    /** 挑刺的结果插在被批的那一块后面（同一块上旧的对手批注换掉）。 */
    fun withNote(a: WorkspaceArtifact, targetId: String, note: ArtifactBlock, now: Long): WorkspaceArtifact {
        val kept = a.blocks.filterNot { it.kind == BlockKind.REVIEW_NOTE && it.targetBlockId == targetId && it.voice == note.voice }
        val i = kept.indexOfFirst { it.id == targetId }
        val blocks = if (i < 0) kept + note else kept.take(i + 1) + note + kept.drop(i + 1)
        return a.copy(blocks = blocks, revision = a.revision + 1, updatedAt = now)
    }

    fun withRewrite(a: WorkspaceArtifact, targetId: String, markdown: String, now: Long): WorkspaceArtifact =
        a.copy(
            blocks = a.blocks.map { if (it.id == targetId) it.copy(markdown = markdown.take(4000), accepted = false) else it },
            revision = a.revision + 1, updatedAt = now,
        )

    fun label(b: ArtifactBlock) = b.markdown.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        .trimStart('#', '>', '-', '*', ' ', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '.', '、').take(12)

    /**
     * 能进体系图的论断：他采用过的正文段（或整份采用的稿子里的正文段）。
     * 练习靶子、待核的假设、批注、选择都不算——练习诱饵永远不混进事实记忆。
     */
    fun claims(a: WorkspaceArtifact): List<String> = a.blocks
        .filter { (it.kind == BlockKind.MARKDOWN || it.kind == BlockKind.QUOTE) && !it.exerciseOnly }
        .filter { it.accepted || a.status == ArtifactStatus.ACCEPTED }
        .map { it.markdown.lines().joinToString(" ") { l -> l.trim().trimStart('#', '>', '-', '*', ' ') }.trim() }
        .filter { it.length >= 4 }

    /** 导出成 Markdown（复制 / 分享用）：不带批注、选择和练习靶子；假设标“待核”；末尾列来源和待核问题。 */
    fun markdown(a: WorkspaceArtifact, materials: List<Material>): String = buildString {
        appendLine("# ${a.title}")
        appendLine()
        a.blocks.filterNot { it.exerciseOnly || it.kind == BlockKind.REVIEW_NOTE || it.kind == BlockKind.CHOICE }.forEach { b ->
            when (b.kind) {
                BlockKind.QUOTE -> appendLine(b.markdown.lines().joinToString("\n") { "> $it" })
                BlockKind.ASSUMPTION -> appendLine("${b.markdown}（待核）")
                else -> appendLine(b.markdown)
            }
            appendLine()
        }
        if (a.unresolvedQuestions.isNotEmpty()) {
            appendLine("## 待核 / 待回答")
            a.unresolvedQuestions.forEach { appendLine("- $it") }
            appendLine()
        }
        val used = a.blocks.flatMap { it.sourceIds }.toSet() + a.sourceIds
        val ms = materials.filter { it.id in used }
        // 联网发现引用的是来源库编号（s-…）：材料里查不到，但要在导出里列出“来自哪几个网页来源”
        val sourceRefs = used.filterNot { it.startsWith("m-") || it.startsWith("n-") }
        if (ms.isNotEmpty() || sourceRefs.isNotEmpty()) {
            appendLine("## 来源")
            ms.forEach { appendLine("- [${it.id}] ${it.title.ifBlank { it.text.take(30) }}") }
            sourceRefs.forEach { appendLine("- [$it] 来源库网页（正文在来源库，未核实）") }
            appendLine()
        }
        append(
            if (a.kind == ArtifactKind.DISCOVERY) {
                "（联网查过：网页内容未核实，引用编号对应来源库里的原文；标「待核」的要你自己核实。）"
            } else {
                "（未联网查证：依据只有你给的材料和体系图；标“待核”的要你自己核实。）"
            },
        )
    }.trim()
}
