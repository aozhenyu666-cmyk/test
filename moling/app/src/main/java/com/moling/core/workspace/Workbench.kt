package com.moling.core.workspace

import com.moling.core.atlas.Atlas
import com.moling.core.mind.Ask
import com.moling.core.mind.MindStore
import com.moling.core.strategy.Draft
import com.moling.core.strategy.Prework
import com.moling.core.think.Recall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * 案板的协调层（v13 第九批）：焦点、稿子、撤销、作业。和 [com.moling.core.Brain] 分开：自己的锁、自己的文件。
 *
 * 规矩：
 *  - 只有他点了才花钱：[prework]（“替我先做一块”）、挑刺、改写、四参谋；没有任何定时 / 自动调用；
 *  - 调模型时不拿锁；结果回来再拿锁核对：作业被取消 → 丢掉；焦点被换掉材料 / 归档 → 作废；要改的那一段已经被他改过 → 作废。都不覆盖他的修改；
 *  - 每个动作带 expectedRevision：对不上就是“版本冲突”；同一个动作 id 再来一次，返回第一次的结果，不重复改、不重复花钱；
 *  - 进程被杀时还在跑的作业，下次启动记成“中断”（不会永远转圈）。
 */
class Workbench(
    private val store: MindStore,
    private val ask: Ask?,
    /** 做纲要 / 初稿 / 四参谋用的模型；null = 和 [ask] 同一个。 */
    private val deep: Ask? = null,
    private val persona: () -> String = { "你是墨灵，他的伙伴。" },
    private val about: () -> String = { "" },
    /** 体系图里和这件事相关的论断，每行以 [n-…] 开头（宿主用 [atlasLines] 从大脑的体系图拿）。 */
    private val atlas: (String) -> List<String> = { emptyList() },
    /** 每次存盘后通知宿主（界面刷新）。 */
    private val onChange: (WorkbenchState) -> Unit = {},
) {
    private val lock = Mutex()

    @Volatile
    private var st: WorkbenchState = recover(decode(store.load()))

    fun state(): WorkbenchState = st

    // ------------------------------------------------------------------ 焦点

    /** 开一件新事：问题 + 他的材料。之前正在做的那件挂起（不删）。 */
    suspend fun start(question: String, text: String, now: Long, kind: String = "paste", title: String = ""): Focus = lock.withLock {
        val q = question.trim().ifBlank { text.trim().lineSequence().firstOrNull().orEmpty().take(30) }.ifBlank { "没起名的一件事" }
        val m = text.trim().takeIf { it.isNotBlank() }?.let { Material(next("m"), kind, title.ifBlank { q }.take(40), it.take(6000), now) }
        val f = Focus(next("f"), q.take(80), startedAt = now, materialIds = listOfNotNull(m?.id))
        st = st.copy(
            focuses = st.focuses.map { if (it.status == FocusStatus.ACTIVE) it.copy(status = FocusStatus.PAUSED) else it } + f,
            materials = st.materials + listOfNotNull(m),
        )
        log(now, "开了一件事：${f.question.take(20)}")
        save()
        f
    }

    /** 给焦点补材料（焦点版本 + 1：之前还没回来的代工结果作废）。 */
    suspend fun addMaterial(focusId: String, text: String, now: Long, kind: String = "paste", title: String = ""): PortResult<Focus> = lock.withLock {
        val f = st.focus(focusId)?.takeIf { it.status != FocusStatus.ARCHIVED } ?: return@withLock fail(PortError.NOT_FOUND, "这件事不在了")
        val t = text.trim().takeIf { it.isNotBlank() } ?: return@withLock fail(PortError.BAD_ACTION, "材料是空的")
        val m = Material(next("m"), kind, title.ifBlank { t.take(20) }, t.take(6000), now)
        val nf = f.copy(materialIds = f.materialIds + m.id, revision = f.revision + 1)
        st = st.copy(focuses = st.focuses.map { if (it.id == f.id) nf else it }, materials = st.materials + m)
        save()
        PortResult.Ok(nf)
    }

    /** 切回某件事（别的挂起）。 */
    suspend fun switchTo(focusId: String, now: Long): PortResult<Focus> = lock.withLock {
        val f = st.focus(focusId)?.takeIf { it.status != FocusStatus.ARCHIVED } ?: return@withLock fail(PortError.NOT_FOUND, "这件事不在了")
        st = st.copy(focuses = st.focuses.map {
            when {
                it.id == f.id -> it.copy(status = FocusStatus.ACTIVE)
                it.status == FocusStatus.ACTIVE -> it.copy(status = FocusStatus.PAUSED)
                else -> it
            }
        })
        save()
        PortResult.Ok(st.focus(focusId)!!)
    }

    /** 收起一件事：还在做的代工取消，结果回来也不要了。 */
    suspend fun archive(focusId: String, now: Long): PortResult<Focus> = lock.withLock {
        val f = st.focus(focusId) ?: return@withLock fail(PortError.NOT_FOUND, "这件事不在了")
        st = st.copy(
            focuses = st.focuses.map { if (it.id == f.id) it.copy(status = FocusStatus.ARCHIVED) else it },
            jobs = st.jobs.map { if (it.focusId == f.id && it.status.live()) it.copy(status = JobStatus.CANCELLED, finishedAt = now) else it },
        )
        log(now, "收起：${f.question.take(20)}")
        save()
        PortResult.Ok(st.focus(focusId)!!)
    }

    // ------------------------------------------------------------------ 代工（他点了才做）

    /**
     * “替我先做一块”：OUTLINE = 三点纲要（从材料 + 体系图）；DRAFT = 把纲要写成初稿（[artifactId] 指定纲要，不给就用这件事最新的纲要）；
     * COUNCIL = 四参谋对 [artifactId] 各说一句（新开一份 COUNCIL_REVIEW）。
     * 同样的输入做过了就直接返回那份（不重复花钱）；正在做就返回 BUSY。模型没接上：纲要 / 初稿给本地骨架（offline = true），四参谋返回 OFFLINE。
     */
    suspend fun prework(focusId: String, kind: JobKind, now: Long, artifactId: String? = null): PortResult<WorkspaceArtifact> {
        require(kind == JobKind.OUTLINE || kind == JobKind.DRAFT || kind == JobKind.COUNCIL) { "挑刺 / 改写走 apply" }
        // 1) 登记作业
        val (job, input) = lock.withLock {
            val f = st.focus(focusId)?.takeIf { it.status != FocusStatus.ARCHIVED } ?: return fail(PortError.NOT_FOUND, "这件事不在了")
            val base = when (kind) {
                JobKind.OUTLINE -> null
                JobKind.DRAFT -> (artifactId?.let { st.artifact(it) } ?: st.artifactsOf(f.id).lastOrNull { it.kind == ArtifactKind.OUTLINE })
                    ?: return fail(PortError.BAD_ACTION, "先做一份纲要")
                else -> artifactId?.let { st.artifact(it) } ?: return fail(PortError.NOT_FOUND, "要评的稿子不在了")
            }
            val ms = st.materialsOf(f)
            val hash = hash(kind.name, f.id, f.revision.toString(), base?.id.orEmpty(), base?.revision?.toString().orEmpty(), *ms.map { it.text }.toTypedArray())
            st.jobs.lastOrNull { it.inputHash == hash && it.status == JobStatus.READY }?.artifactId?.let { st.artifact(it) }
                ?.takeIf { it.status != ArtifactStatus.ARCHIVED }?.let { return PortResult.Ok(it) }
            if (st.jobs.any { it.inputHash == hash && it.status.live() && now < it.deadline }) return fail(PortError.BUSY, "她还在做这一块")
            val j = PreworkJob(
                next("j"), kind, f.id, f.revision, "${kind.label}：${f.question.take(40)}", ms.map { it.id } + listOfNotNull(base?.id), hash,
                JobStatus.RUNNING, now, now + Prework.DEADLINE_MS, modelAttempts = 1, artifactId = base?.id,
            )
            st = st.copy(jobs = keepJobs(st.jobs + j))
            log(now, "开工 ${j.id}：${j.taskBrief}")
            save()
            j to Triple(f, ms, base)
        }
        val (f, ms, base) = input
        // 2) 调模型（不拿锁）
        val valid = ms.map { it.id }.toSet()
        val atlasLines = if (kind == JobKind.COUNCIL) emptyList() else runCatching { atlas(f.question) }.getOrDefault(emptyList())
        // 能引用的编号：这件事的材料 + 给她看的体系图论断（材料是从体系图选的主题时，里面的论断编号也算）
        val allValid = valid + (atlasLines + ms.map { it.text }).flatMap { NODE_ID.findAll(it).map { m -> m.value }.toList() }
        val outlineBlocks = base?.let { b -> b.blocks.filter { Artifacts.isContent(it) && !it.exerciseOnly }.let { c -> c.filter { it.accepted }.ifEmpty { c } } }.orEmpty()
        val reply = when (kind) {
            JobKind.OUTLINE -> think(true, Prework.outlinePrompt(persona(), f.question, ms, atlasLines, about()))
            JobKind.DRAFT -> think(true, Prework.draftPrompt(persona(), f.question, outlineBlocks, ms, about()))
            else -> think(true, Prework.councilPrompt(persona(), base!!.title, base.blocks))
        }
        // 3) 核对、落盘
        return lock.withLock {
            stale(job.id, now)?.let { return@withLock it }
            val ids = { next("b") }
            val made: WorkspaceArtifact? = when (kind) {
                JobKind.COUNCIL -> reply?.let { Prework.parseCouncil(it, ids) }?.let { notes ->
                    WorkspaceArtifact(next("a"), f.id, ArtifactKind.COUNCIL_REVIEW, "四参谋：${base!!.title.take(16)}", notes, createdAt = now, updatedAt = now,
                        parentArtifactId = base.id, producerJobId = job.id)
                }
                else -> {
                    val parsed: Draft? = reply?.let {
                        if (kind == JobKind.OUTLINE) Prework.parseOutline(it, allValid, ids) else Prework.parseDraft(it, allValid, ids)
                    }
                    val d = parsed ?: if (kind == JobKind.OUTLINE) Prework.localOutline(f.question, ms, ids) else Prework.localDraft(f.question, outlineBlocks, ids)
                    WorkspaceArtifact(
                        next("a"), f.id, if (kind == JobKind.OUTLINE) ArtifactKind.OUTLINE else ArtifactKind.DRAFT,
                        d.title.ifBlank { f.question.take(20) }, d.blocks,
                        sourceIds = d.blocks.flatMap { it.sourceIds }.distinct(), createdAt = now, updatedAt = now,
                        status = ArtifactStatus.READY_FOR_REVIEW, parentArtifactId = base?.id, producerJobId = job.id,
                        unresolvedQuestions = d.questions, offline = parsed == null,
                    )
                }
            }
            if (made == null) {
                finish(job.id, JobStatus.FAILED, now, error = PortError(PortError.OFFLINE, "模型没接上，这一下先不做"))
                save()
                return@withLock fail(PortError.OFFLINE, "模型没接上，这一下先不做")
            }
            st = st.copy(
                artifacts = st.artifacts + made,
                focuses = st.focuses.map { if (it.id == f.id) it.copy(artifactIds = it.artifactIds + made.id, nextChoice = nextChoice(made)) else it },
            )
            finish(job.id, JobStatus.READY, now, artifactId = made.id)
            log(now, "${job.id} 做好了：${made.title.take(20)}${if (made.offline) "（离线骨架）" else ""}")
            save()
            PortResult.Ok(made)
        }
    }

    /** 取消一个还在做的作业（结果回来也不落盘、不出声）。 */
    suspend fun cancel(jobId: String, now: Long): Boolean = lock.withLock {
        val j = st.job(jobId)?.takeIf { it.status.live() } ?: return@withLock false
        finish(j.id, JobStatus.CANCELLED, now)
        log(now, "取消 ${j.id}")
        save()
        true
    }

    // ------------------------------------------------------------------ 他在稿子上的动作

    /**
     * 编辑 / 采用 / 删 / 选路线 / 整份采用 / 撤销：立刻生效。挑刺（REQUEST_CRITIC）、改写（REWRITE_BLOCK）要等模型：
     * 结果回来时那一段被他改过了就作废（STALE）；挑刺没模型时给离线版的三问 + 两条改法，改写没模型返回 OFFLINE。
     */
    suspend fun apply(action: ArtifactAction): PortResult<WorkspaceArtifact> {
        val now = action.at
        val job = lock.withLock {
            st.done[action.id]?.let { rev -> return PortResult.Ok(versionAt(action.artifactId, rev)) }
            if (st.jobs.any { it.actionId == action.id && it.status.live() }) return fail(PortError.BUSY, "这一下还在做")
            val a = st.artifact(action.artifactId) ?: return fail(PortError.NOT_FOUND, "稿子不在了")
            if (a.revision != action.expectedRevision) return fail(PortError.REVISION_CONFLICT, "稿子刚变过（现在是第 ${a.revision} 版），看一眼再点")
            when (action.kind) {
                ArtifactActionKind.UNDO -> {
                    val v = st.versions(a.id).lastOrNull() ?: return fail(PortError.BAD_ACTION, "没有可以撤销的了")
                    val back = v.snapshot.copy(revision = a.revision + 1, updatedAt = now)
                    st = st.copy(artifacts = st.artifacts.map { if (it.id == a.id) back else it }, history = st.history - v)
                    done(action.id, back.revision)
                    log(now, "撤销：${v.note}")
                    save()
                    return PortResult.Ok(back)
                }
                ArtifactActionKind.REQUEST_CRITIC, ArtifactActionKind.REWRITE_BLOCK -> {
                    val b = a.blocks.firstOrNull { it.id == action.blockId }?.takeIf { Artifacts.isContent(it) }
                        ?: return fail(PortError.BAD_ACTION, "这一块不能${if (action.kind == ArtifactActionKind.REQUEST_CRITIC) "挑刺" else "改写"}")
                    val kind = if (action.kind == ArtifactActionKind.REQUEST_CRITIC) JobKind.CRITIC else JobKind.REWRITE
                    if (st.jobs.any { it.artifactId == a.id && it.blockId == b.id && it.kind == kind && it.status.live() && now < it.deadline }) {
                        return fail(PortError.BUSY, "这一段还在${kind.label}")
                    }
                    val f = st.focus(a.focusId)
                    val j = PreworkJob(
                        next("j"), kind, a.focusId, f?.revision ?: 0, "${kind.label}：${Artifacts.label(b)}", b.sourceIds,
                        hash(action.id), JobStatus.RUNNING, now, now + Prework.DEADLINE_MS, 1, a.id, b.id, b.markdown, action.id,
                    )
                    st = st.copy(jobs = keepJobs(st.jobs + j))
                    save()
                    Triple(j, a, b)
                }
                else -> {
                    val (na, note) = Artifacts.applyLocal(a, action, now) ?: return fail(PortError.BAD_ACTION, "这一下做不了")
                    commit(a, na, note, action.id, now)
                    save()
                    return PortResult.Ok(na)
                }
            }
        }
        val (j, a, b) = job
        val ms = st.focus(a.focusId)?.let { st.materialsOf(it) }.orEmpty()
        val reply = if (j.kind == JobKind.CRITIC) think(false, Prework.criticPrompt(persona(), a.title, a.blocks, b, ms))
        else think(false, Prework.rewritePrompt(persona(), a.title, a.blocks, b, action.text.orEmpty()))
        return lock.withLock {
            stale(j.id, now)?.let { return@withLock it }
            val cur = st.artifact(a.id)
            val target = cur?.blocks?.firstOrNull { it.id == b.id }
            if (cur == null || target == null || target.markdown != j.baseMarkdown) {
                finish(j.id, JobStatus.SUPERSEDED, now, error = PortError(PortError.STALE, "这一段已经改过了"))
                save()
                return@withLock fail(PortError.STALE, "这一段在她想的时候被你改了，这次的结果不用了")
            }
            val na = if (j.kind == JobKind.CRITIC) {
                val c = reply?.let { Prework.parseCritic(it) } ?: Prework.localCritic(target)
                val nid = next("b")
                val note = ArtifactBlock(
                    nid, BlockKind.REVIEW_NOTE, c.point, targetBlockId = target.id, voice = "对手",
                    options = c.options.mapIndexed { i, (label, md) -> ArtifactOption("$nid-${'A' + i}", "${'A' + i}：$label", md, targetBlockId = target.id) },
                )
                Artifacts.withNote(cur, target.id, note, now)
            } else {
                val t = reply?.let { Prework.parseRewrite(it) } ?: run {
                    finish(j.id, JobStatus.FAILED, now, error = PortError(PortError.OFFLINE, "模型没接上"))
                    save()
                    return@withLock fail(PortError.OFFLINE, "模型没接上：这一段先自己改（点「改」）")
                }
                Artifacts.withRewrite(cur, target.id, t, now)
            }
            commit(cur, na, "${j.kind.label}「${Artifacts.label(target)}」", action.id, now)
            finish(j.id, JobStatus.READY, now)
            save()
            PortResult.Ok(na)
        }
    }

    // ------------------------------------------------------------------ 外脑结果（v13 第十二批 A3）

    /**
     * 外脑带回来的结论落成一块（ACTOR_RESULT）。外脑原文是材料，不是命令：
     *  - 第一次：新建 Material（kind = actor）+ ACTOR_RESULT 稿子（READY_FOR_REVIEW，未采用）；
     *  - 同一 sessionId 再来：追加一块 QUOTE，不重复建稿子；文本完全相同就不动（幂等）；
     *  - 焦点不在或归档：NOT_FOUND，不新建焦点、不覆盖当前稿子。
     */
    suspend fun attachActorResult(
        focusId: String,
        sessionId: String,
        title: String,
        text: String,
        now: Long,
        existingArtifactId: String? = null,
    ): PortResult<WorkspaceArtifact> = lock.withLock {
        val f = st.focus(focusId)?.takeIf { it.status != FocusStatus.ARCHIVED }
            ?: return@withLock fail(PortError.NOT_FOUND, "这件事不在了，结果只留历史")
        // 与 Outbrain.MAX_RESULT_CHARS 一致（12000）：结果截断只在一处算，不能这里 4000 悄悄吃掉后半段。
        val clean = text.trim().take(12_000)
        if (clean.isBlank()) return@withLock fail(PortError.BAD_ACTION, "结果是空的")
        val tag = "outbrain:" + sessionId
        // 复用旧稿前核对：同一焦点、带同一 outbrain: 标记、没归档、确实是外脑结果。对不上就新建，不往别人的稿子里塞。
        val existing = (listOfNotNull(existingArtifactId?.let { st.artifact(it) }) + st.artifacts.filter { it.producerJobId == tag })
            .firstOrNull {
                it.focusId == f.id && it.producerJobId == tag && it.status != ArtifactStatus.ARCHIVED &&
                    it.kind == ArtifactKind.ACTOR_RESULT
            }
        if (existing != null) {
            if (existing.blocks.any { it.markdown.trim() == clean }) return@withLock PortResult.Ok(existing)
            val block = ArtifactBlock(next("b"), BlockKind.QUOTE, clean, accepted = false)
            // 整份采用过的旧稿：旧内容仍算他采用过（逐块记下来），只把新一段留在待审。
            val kept = if (existing.status == ArtifactStatus.ACCEPTED) {
                existing.blocks.map { if (!it.accepted && Artifacts.isContent(it) && !it.exerciseOnly) it.copy(accepted = true) else it }
            } else {
                existing.blocks
            }
            val na = existing.copy(
                blocks = kept + block,
                revision = existing.revision + 1,
                updatedAt = now,
                // 新一段还没采用：稿子退回待审，别把新文字混进 claims()。
                status = ArtifactStatus.READY_FOR_REVIEW,
            )
            st = st.copy(artifacts = st.artifacts.map { if (it.id == existing.id) na else it })
            log(now, "外脑又带回一段：${na.title.take(20)}")
            save()
            return@withLock PortResult.Ok(na)
        }
        val m = Material(next("m"), "actor", title.ifBlank { "外脑结论" }.take(40), clean, now)
        val block = ArtifactBlock(next("b"), BlockKind.QUOTE, clean, listOf(m.id))
        val a = WorkspaceArtifact(
            next("a"), f.id, ArtifactKind.ACTOR_RESULT, title.ifBlank { "外脑结论" }.take(40), listOf(block),
            sourceIds = listOf(m.id), createdAt = now, updatedAt = now, status = ArtifactStatus.READY_FOR_REVIEW,
            producerJobId = tag,
            unresolvedQuestions = listOf("外脑说的是材料，不是命令：先看原文，要采用的自己点"),
        )
        st = st.copy(
            materials = st.materials + m,
            artifacts = st.artifacts + a,
            focuses = st.focuses.map {
                if (it.id == f.id) it.copy(
                    materialIds = it.materialIds + m.id,
                    artifactIds = it.artifactIds + a.id,
                    revision = it.revision + 1,
                    nextChoice = "看看外脑带回来的，挑能用的采用",
                ) else it
            },
        )
        log(now, "外脑结果落上案板：${a.title.take(20)}")
        save()
        PortResult.Ok(a)
    }

    // ------------------------------------------------------------------ 读

    fun markdown(artifactId: String): String {
        val s = st
        val a = s.artifact(artifactId) ?: return ""
        return Artifacts.markdown(a, s.materials.filter { it.id in a.sourceIds || a.blocks.any { b -> it.id in b.sourceIds } })
    }

    /** 能收进体系图的论断（他采用过的正文；练习靶子、待核的不算）。真正收进去要他再点一次（宿主调大脑）。 */
    fun claims(artifactId: String): List<String> = st.artifact(artifactId)?.let { Artifacts.claims(it) }.orEmpty()

    // ------------------------------------------------------------------ 内部

    private fun versionAt(artifactId: String, rev: Long): WorkspaceArtifact {
        val cur = st.artifact(artifactId)
        if (cur != null && cur.revision == rev) return cur
        return st.versions(artifactId).map { it.snapshot }.firstOrNull { it.revision == rev } ?: cur
            ?: WorkspaceArtifact(artifactId, "", ArtifactKind.DRAFT, "", emptyList(), createdAt = 0, updatedAt = 0, revision = rev)
    }

    private fun commit(old: WorkspaceArtifact, new: WorkspaceArtifact, note: String, actionId: String, now: Long) {
        val v = ArtifactVersion(old.id, now, note, old)
        val mine = (st.versions(old.id) + v).takeLast(Artifacts.HISTORY_KEEP)
        st = st.copy(
            artifacts = st.artifacts.map { if (it.id == old.id) new else it },
            history = st.history.filter { it.artifactId != old.id } + mine,
        )
        done(actionId, new.revision)
        log(now, "第 ${new.revision} 版：$note")
    }

    private fun done(actionId: String, rev: Long) {
        st = st.copy(done = (st.done + (actionId to rev)).entries.toList().takeLast(Artifacts.DONE_KEEP).associate { it.key to it.value })
    }

    /** 结果回来时：作业被取消 / 焦点没了或换了材料 → 返回失败（并记账）；还能用 → null。 */
    private fun stale(jobId: String, now: Long): PortResult.Failed? {
        val j = st.job(jobId) ?: return PortResult.Failed(PortError(PortError.CANCELLED, "作业不在了"))
        if (j.status == JobStatus.CANCELLED) return PortResult.Failed(PortError(PortError.CANCELLED, "已经取消了"))
        val f = st.focus(j.focusId)
        val focusGone = f == null || f.status == FocusStatus.ARCHIVED
        val focusMoved = (j.kind == JobKind.OUTLINE || j.kind == JobKind.DRAFT) && f != null && f.revision != j.focusRevision
        if (focusGone || focusMoved) {
            finish(j.id, JobStatus.SUPERSEDED, now, error = PortError(PortError.STALE, "这件事已经变了"))
            save()
            return PortResult.Failed(PortError(PortError.STALE, "这件事在她做的时候变了，这次的结果不用了"))
        }
        return null
    }

    private fun finish(jobId: String, status: JobStatus, now: Long, artifactId: String? = null, error: PortError? = null) {
        st = st.copy(jobs = st.jobs.map {
            if (it.id == jobId) it.copy(status = status, finishedAt = now, artifactId = artifactId ?: it.artifactId, error = error ?: it.error) else it
        })
    }

    private fun nextChoice(a: WorkspaceArtifact) = when (a.kind) {
        ArtifactKind.OUTLINE -> "挑一段的刺，或者直接写成初稿"
        ArtifactKind.DRAFT -> "让四参谋看一眼，或者导出"
        else -> null
    }

    private suspend fun think(deepFirst: Boolean, user: String): String? {
        val sys = persona()
        if (deepFirst && deep != null) call(deep, sys, user)?.let { return it }
        return ask?.let { call(it, sys, user) }
    }

    private suspend fun call(a: Ask, system: String, user: String): String? = try {
        a.ask(system, user)?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun next(prefix: String): String {
        st = st.copy(seq = st.seq + 1)
        return "$prefix-${st.seq}"
    }

    private fun log(now: Long, s: String) {
        st = st.copy(log = (st.log + "${now / 1000} $s").takeLast(100))
    }

    private fun keepJobs(list: List<PreworkJob>): List<PreworkJob> {
        val over = list.size - Artifacts.JOBS_KEEP
        if (over <= 0) return list
        val drop = list.filter { !it.status.live() }.take(over).map { it.id }.toSet()
        return list.filterNot { it.id in drop }
    }

    private fun save() {
        runCatching { store.save(json.encodeToString(WorkbenchState.serializer(), st)) }
        runCatching { onChange(st) }
    }

    private fun <T> fail(code: String, msg: String): PortResult<T> = PortResult.Failed(PortError(code, msg))

    companion object {
        private val NODE_ID = Regex("""n-\d+(?:-\d+)?""")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        fun decode(s: String?): WorkbenchState =
            if (s.isNullOrBlank()) WorkbenchState() else runCatching { json.decodeFromString(WorkbenchState.serializer(), s) }.getOrDefault(WorkbenchState())

        /** 上次进程没了时还在跑的作业：记成“中断”。 */
        fun recover(s: WorkbenchState): WorkbenchState = s.copy(jobs = s.jobs.map {
            if (it.status.live()) it.copy(status = JobStatus.FAILED, error = PortError(PortError.INTERRUPTED, "上次没做完就被打断了")) else it
        })

        fun hash(vararg parts: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0001").toByteArray())
            return d.take(12).joinToString("") { "%02x".format(it) }
        }

        /** 体系图里和 [query] 相关的论断，每行“[n-…] 论断”（给 [Workbench] 的 atlas 参数用）。 */
        fun atlasLines(a: Atlas, query: String, k: Int = 6): List<String> =
            Recall.relevant(query, a.nodes.map { "[${it.id}] ${it.claim}" }, k)

        /** 从体系图选一个主题当材料：标题 + 每条论断一行（带编号，纲要可以引用）。 */
        fun topicMaterial(a: Atlas, topicId: String): Pair<String, String>? {
            val t = a.topics.firstOrNull { it.id == topicId } ?: return null
            val lines = a.nodes.filter { it.topicId == t.id }.joinToString("\n") { "[${it.id}] ${it.claim}" }
            return if (lines.isBlank()) null else t.name to lines
        }
    }
}

private fun JobStatus.live() = this == JobStatus.QUEUED || this == JobStatus.RUNNING
