package com.moling.core.workspace

import com.moling.core.mind.Ask
import com.moling.core.mind.MindStore
import com.moling.core.strategy.Prework
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 案板（v13 第九批）：版本、幂等、旧结果不覆盖、取消、练习诱饵不进记忆、无网兜底。 */
class WorkbenchTest {
    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    /** 按提示词里的关键词回话；[gate] 不为空时先等它（模拟模型慢）。 */
    private class Router(val rules: List<Pair<String, String>>) : Ask {
        val prompts = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun ask(system: String, user: String): String? {
            prompts += user
            gate?.await()
            return rules.firstOrNull { user.contains(it.first) }?.second
        }
    }

    private val material = "申论开头最容易卡。我平时先列三点再写。可是三点常常互相重复。"

    private val outlineReply = """
        【标题】申论开头三步
        【要点】开头先亮观点，别铺垫｜m-1
        【要点】三点要有推进：现象→原因→对策｜推测
        【要点】每点配一个材料里的例子｜m-1, m-999
        【待核】考官是否偏好开门见山
        【追问】你最近一次卡在哪种题？
        【靶子】只要列了三点，开头就一定不会卡｜“一定”太绝对
    """.trimIndent()

    private val criticReply = """
        【刺】“别铺垫”说得太死：有的题需要一句背景
        【A】收窄说法｜开头先亮观点，背景最多一句
        【B】补个例子｜开头先亮观点，比如“治理要靠制度”，别先讲三段历史
    """.trimIndent()

    private fun router() = Router(listOf("三点纲要" to outlineReply, "对手" to criticReply, "【要改的这一段】" to "【改】观点放第一句，十个字以内。"))

    private fun bench(ask: Ask?, store: MemStore = MemStore()) = Workbench(store, ask)

    private fun act(a: WorkspaceArtifact, kind: ArtifactActionKind, blockId: String? = null, optionId: String? = null, text: String? = null, at: Long = 10) =
        ArtifactAction(Artifacts.actionId(a, kind, blockId, optionId, text), a.id, a.revision, kind, blockId, optionId, text, at)

    private suspend fun outline(w: Workbench): WorkspaceArtifact {
        val f = w.start("申论开头怎么写", material, 1)
        return (w.prework(f.id, JobKind.OUTLINE, 2) as PortResult.Ok).value
    }

    @Test fun outlineFromTwoSentencesLabelsAssumptionsAndDropsFakeRefs() = runTest {
        val a = outline(bench(router()))
        assertEquals(ArtifactKind.OUTLINE, a.kind)
        assertEquals("申论开头三步", a.title)
        val content = a.blocks.filter { Artifacts.isContent(it) }
        assertEquals(3, content.size)
        assertEquals(BlockKind.MARKDOWN, content[0].kind)
        assertEquals(listOf("m-1"), content[0].sourceIds)
        assertEquals("推测的标成待核的假设", BlockKind.ASSUMPTION, content[1].kind)
        assertEquals("编出来的 m-999 丢掉", listOf("m-1"), content[2].sourceIds)
        val bait = a.blocks.single { it.exerciseOnly }
        assertEquals(BlockKind.CHOICE, bait.kind)
        assertTrue(a.unresolvedQuestions.any { it.contains("开门见山") })
        assertFalse(a.offline)
    }

    @Test fun exitCriterionOutlineCriticChooseRewriteExport() = runTest {
        val w = bench(router())
        var a = outline(w)
        val first = Artifacts.firstContent(a)!!
        // 对手挑刺
        a = (w.apply(act(a, ArtifactActionKind.REQUEST_CRITIC, first.id)) as PortResult.Ok).value
        val note = a.blocks.single { it.kind == BlockKind.REVIEW_NOTE }
        assertEquals(first.id, note.targetBlockId)
        assertEquals(listOf("A：收窄说法", "B：补个例子"), note.options.map { it.label })
        assertEquals("批注紧跟在被批的段落后面", a.blocks.indexOfFirst { it.id == first.id } + 1, a.blocks.indexOf(note))
        // 采用 A
        a = (w.apply(act(a, ArtifactActionKind.CHOOSE_BRANCH, note.id, note.options[0].id)) as PortResult.Ok).value
        assertEquals("开头先亮观点，背景最多一句", a.blocks.first { it.id == first.id }.markdown)
        assertTrue("批注收起", a.blocks.none { it.kind == BlockKind.REVIEW_NOTE })
        // 改首段
        a = (w.apply(act(a, ArtifactActionKind.REWRITE_BLOCK, first.id)) as PortResult.Ok).value
        assertEquals("观点放第一句，十个字以内。", a.blocks.first { it.id == first.id }.markdown)
        // 导出
        val md = w.markdown(a.id)
        assertTrue(md.startsWith("# 申论开头三步"))
        assertTrue(md.contains("观点放第一句"))
        assertTrue("假设标待核", md.contains("（待核）"))
        assertFalse("练习靶子不导出", md.contains("一定不会卡"))
        assertTrue(md.contains("## 来源") && md.contains("[m-1]"))
        assertEquals(4L, a.revision)
    }

    @Test fun duplicateActionAppliesOnce() = runTest {
        val w = bench(router())
        val a = outline(w)
        val b = Artifacts.firstContent(a)!!
        val x = act(a, ArtifactActionKind.ACCEPT_BLOCK, b.id)
        val r1 = (w.apply(x) as PortResult.Ok).value
        val r2 = (w.apply(x) as PortResult.Ok).value
        assertEquals(r1.revision, r2.revision)
        assertEquals(a.revision + 1, w.state().artifact(a.id)!!.revision)
        assertEquals(1, w.state().versions(a.id).size)
    }

    @Test fun duplicateCriticCallsModelOnce() = runTest {
        val ask = router()
        val w = bench(ask)
        val a = outline(w)
        val x = act(a, ArtifactActionKind.REQUEST_CRITIC, Artifacts.firstContent(a)!!.id)
        w.apply(x)
        val n = ask.prompts.size
        assertTrue(w.apply(x) is PortResult.Ok)
        assertEquals("同一个动作不重复花钱", n, ask.prompts.size)
    }

    @Test fun staleRevisionIsAConflict() = runTest {
        val w = bench(router())
        val a = outline(w)
        val b = Artifacts.firstContent(a)!!
        w.apply(act(a, ArtifactActionKind.ACCEPT_BLOCK, b.id))
        val r = w.apply(act(a, ArtifactActionKind.EDIT_BLOCK, b.id, text = "别的写法"))
        assertEquals(PortError.REVISION_CONFLICT, (r as PortResult.Failed).error.code)
    }

    @Test fun undoRestoresPreviousVersion() = runTest {
        val w = bench(router())
        var a = outline(w)
        val b = Artifacts.firstContent(a)!!
        a = (w.apply(act(a, ArtifactActionKind.EDIT_BLOCK, b.id, text = "我自己的开头")) as PortResult.Ok).value
        a = (w.apply(act(a, ArtifactActionKind.DELETE_BLOCK, a.blocks.last { Artifacts.isContent(it) }.id)) as PortResult.Ok).value
        a = (w.apply(act(a, ArtifactActionKind.UNDO)) as PortResult.Ok).value
        assertEquals(3, a.blocks.count { Artifacts.isContent(it) })
        assertEquals("我自己的开头", a.blocks.first { it.id == b.id }.markdown)
        a = (w.apply(act(a, ArtifactActionKind.UNDO)) as PortResult.Ok).value
        assertEquals("1. 开头先亮观点，别铺垫", a.blocks.first { it.id == b.id }.markdown)
        val r = w.apply(act(a, ArtifactActionKind.UNDO))
        assertEquals(PortError.BAD_ACTION, (r as PortResult.Failed).error.code)
    }

    @Test fun rewriteResultDiscardedIfHeEditedMeanwhile() = runTest {
        val ask = router()
        val w = bench(ask)
        val a = outline(w)
        val b = Artifacts.firstContent(a)!!
        ask.gate = CompletableDeferred()
        val pending = async { w.apply(act(a, ArtifactActionKind.REWRITE_BLOCK, b.id)) }
        yield()
        assertEquals(JobStatus.RUNNING, w.state().jobs.last().status)
        // 她想的时候，他自己把这一段改了
        val edited = (w.apply(act(a, ArtifactActionKind.EDIT_BLOCK, b.id, text = "我自己改的")) as PortResult.Ok).value
        ask.gate!!.complete(Unit)
        val r = pending.await()
        assertEquals(PortError.STALE, (r as PortResult.Failed).error.code)
        assertEquals("我自己改的", w.state().artifact(a.id)!!.blocks.first { it.id == b.id }.markdown)
        assertEquals(edited.revision, w.state().artifact(a.id)!!.revision)
        assertEquals(JobStatus.SUPERSEDED, w.state().jobs.last().status)
    }

    @Test fun cancelledJobNeverLands() = runTest {
        val ask = router()
        val w = bench(ask)
        val f = w.start("申论开头怎么写", material, 1)
        ask.gate = CompletableDeferred()
        val pending = async { w.prework(f.id, JobKind.OUTLINE, 2) }
        yield()
        val job = w.state().jobs.last()
        assertTrue(w.cancel(job.id, 3))
        ask.gate!!.complete(Unit)
        assertEquals(PortError.CANCELLED, (pending.await() as PortResult.Failed).error.code)
        assertTrue(w.state().artifacts.isEmpty())
    }

    @Test fun oldFocusResultDoesNotLandAfterMaterialChanged() = runTest {
        val ask = router()
        val w = bench(ask)
        val f = w.start("申论开头怎么写", material, 1)
        ask.gate = CompletableDeferred()
        val pending = async { w.prework(f.id, JobKind.OUTLINE, 2) }
        yield()
        w.addMaterial(f.id, "补一句：考场只有十分钟写开头。", 3)
        ask.gate!!.complete(Unit)
        assertEquals(PortError.STALE, (pending.await() as PortResult.Failed).error.code)
        assertTrue(w.state().artifacts.isEmpty())
    }

    @Test fun switchingFocusKeepsResultOnItsOwnFocus() = runTest {
        val ask = router()
        val w = bench(ask)
        val f1 = w.start("申论开头怎么写", material, 1)
        ask.gate = CompletableDeferred()
        val pending = async { w.prework(f1.id, JobKind.OUTLINE, 2) }
        yield()
        val f2 = w.start("周报", "这周做了三件事。", 3)
        ask.gate!!.complete(Unit)
        val a = (pending.await() as PortResult.Ok).value
        assertEquals(f1.id, a.focusId)
        assertEquals(f2.id, w.state().active()!!.id)
        assertTrue("新焦点上什么都没多", w.state().artifactsOf(f2.id).isEmpty())
    }

    @Test fun sameInputPreworkIsIdempotent() = runTest {
        val ask = router()
        val w = bench(ask)
        val f = w.start("申论开头怎么写", material, 1)
        val a1 = (w.prework(f.id, JobKind.OUTLINE, 2) as PortResult.Ok).value
        val n = ask.prompts.size
        val a2 = (w.prework(f.id, JobKind.OUTLINE, 3) as PortResult.Ok).value
        assertEquals(a1.id, a2.id)
        assertEquals(n, ask.prompts.size)
    }

    @Test fun busyWhileSameJobRunning() = runTest {
        val ask = router()
        val w = bench(ask)
        val f = w.start("申论开头怎么写", material, 1)
        ask.gate = CompletableDeferred()
        val first = async { w.prework(f.id, JobKind.OUTLINE, 2) }
        yield()
        assertEquals(PortError.BUSY, (w.prework(f.id, JobKind.OUTLINE, 3) as PortResult.Failed).error.code)
        ask.gate!!.complete(Unit)
        assertTrue(first.await() is PortResult.Ok)
    }

    @Test fun offlineStillGivesAnEditableSkeleton() = runTest {
        val w = bench(null)
        val a = outline(w)
        assertTrue(a.offline)
        val content = a.blocks.filter { Artifacts.isContent(it) }
        assertEquals(3, content.size)
        assertEquals("申论开头最容易卡", content[0].markdown.removePrefix("1. "))
        assertTrue(content.all { it.kind == BlockKind.ASSUMPTION || it.sourceIds == listOf("m-1") })
        // 离线挑刺：给三问 + 两条改法；离线改写：明说做不了
        val b = Artifacts.firstContent(a)!!
        val c = (w.apply(act(a, ArtifactActionKind.REQUEST_CRITIC, b.id)) as PortResult.Ok).value
        assertEquals(2, c.blocks.single { it.kind == BlockKind.REVIEW_NOTE }.options.size)
        val r = w.apply(act(c, ArtifactActionKind.REWRITE_BLOCK, b.id))
        assertEquals(PortError.OFFLINE, (r as PortResult.Failed).error.code)
        // 他自己改总是可以的
        assertTrue(w.apply(act(c, ArtifactActionKind.EDIT_BLOCK, b.id, text = "开头一句话亮观点")) is PortResult.Ok)
    }

    @Test fun exerciseDecoyNeverBecomesAClaim() = runTest {
        val w = bench(router())
        var a = outline(w)
        a = (w.apply(act(a, ArtifactActionKind.ACCEPT_ARTIFACT)) as PortResult.Ok).value
        val claims = w.claims(a.id)
        assertEquals("只有有依据的正文：假设和练习靶子都不算", 2, claims.size)
        assertTrue(claims.none { it.contains("一定不会卡") })
        assertTrue(claims.none { it.contains("现象→原因→对策") })
        // 练习靶子点了“有漏洞”也还是练习
        val bait = a.blocks.single { it.exerciseOnly }
        a = (w.apply(act(a, ArtifactActionKind.CHOOSE_BRANCH, bait.id, bait.options[0].id)) as PortResult.Ok).value
        assertTrue(Artifacts.claims(a).none { it.contains("一定") })
    }

    @Test fun survivesRestartAndMarksInterruptedJobs() = runTest {
        val store = MemStore()
        val ask = router()
        val w = bench(ask, store)
        val a = outline(w)
        w.apply(act(a, ArtifactActionKind.ACCEPT_BLOCK, Artifacts.firstContent(a)!!.id))
        ask.gate = CompletableDeferred()
        val f = w.state().active()!!
        backgroundScope.async { w.prework(f.id, JobKind.DRAFT, 5) }
        yield()
        assertEquals(JobStatus.RUNNING, w.state().jobs.last().status)
        // 进程没了，重新起来
        val w2 = bench(ask, store)
        val back = w2.state().artifact(a.id)!!
        assertEquals(a.revision + 1, back.revision)
        assertEquals(1, w2.state().versions(a.id).size)
        assertEquals(JobStatus.FAILED, w2.state().jobs.last().status)
        assertEquals(PortError.INTERRUPTED, w2.state().jobs.last().error!!.code)
        ask.gate!!.complete(Unit)
    }

    @Test fun draftUsesAcceptedOutlinePoints() = runTest {
        val ask = Router(listOf("三点纲要" to outlineReply, "写成初稿" to "【标题】初稿\n【段】开头先亮观点：考场时间紧，第一句就要让考官知道你站哪边。｜m-1\n【段】三点要层层推进，从现象到原因再到对策，避免重复。｜推测"))
        val w = bench(ask)
        val a = outline(w)
        w.apply(act(a, ArtifactActionKind.ACCEPT_BLOCK, Artifacts.firstContent(a)!!.id))
        val d = (w.prework(a.focusId, JobKind.DRAFT, 20) as PortResult.Ok).value
        assertEquals(ArtifactKind.DRAFT, d.kind)
        assertEquals(a.id, d.parentArtifactId)
        val prompt = ask.prompts.last()
        assertTrue("只给他采用过的要点", prompt.contains("开头先亮观点") && !prompt.contains("现象→原因→对策"))
        assertEquals(listOf(BlockKind.MARKDOWN, BlockKind.ASSUMPTION), d.blocks.map { it.kind })
    }

    @Test fun councilMakesASeparateReview() = runTest {
        val ask = Router(listOf("三点纲要" to outlineReply, "四个参谋" to "【对手】第二点没依据\n【教练】先补例子\n【军师】把第三点提前\n【朋友】第一点很干脆"))
        val w = bench(ask)
        val a = outline(w)
        val r = (w.prework(a.focusId, JobKind.COUNCIL, 20, a.id) as PortResult.Ok).value
        assertEquals(ArtifactKind.COUNCIL_REVIEW, r.kind)
        assertEquals(listOf("对手", "教练", "军师", "朋友"), r.blocks.map { it.voice })
        assertEquals("原稿不动", a.revision, w.state().artifact(a.id)!!.revision)
    }

    @Test fun parsersTolerateMissingFields() {
        var n = 0
        val id = { "b-${++n}" }
        assertNull("一个要点不算纲要", Prework.parseOutline("【要点】只有一点｜推测", setOf("m-1"), id))
        assertNull(Prework.parseCritic("【A】没有刺｜随便"))
        assertNull(Prework.parseCritic("【刺】只有刺"))
        assertEquals("裸文本也认", "直接改好的一段。", Prework.parseRewrite("直接改好的一段。"))
        assertEquals(listOf("m-1"), Prework.refs("材料", setOf("m-1")))
        assertEquals(emptyList<String>(), Prework.refs("材料", setOf("m-1", "m-2")))
        assertEquals(listOf("n-1700-2"), Prework.refs("n-1700-2，n-9", setOf("n-1700-2")))
    }

    @Test fun archivedFocusRejectsWork() = runTest {
        val w = bench(router())
        val f = w.start("x", material, 1)
        w.archive(f.id, 2)
        assertEquals(PortError.NOT_FOUND, (w.prework(f.id, JobKind.OUTLINE, 3) as PortResult.Failed).error.code)
        assertNull(w.state().active())
        assertNotNull(w.state().focus(f.id))
    }
}
