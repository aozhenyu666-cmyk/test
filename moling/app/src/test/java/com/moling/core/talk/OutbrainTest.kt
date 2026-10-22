package com.moling.core.talk

import com.moling.core.mind.Actor
import com.moling.core.mind.MindStore
import com.moling.core.workspace.ArtifactAction
import com.moling.core.workspace.ArtifactActionKind
import com.moling.core.workspace.ArtifactBlock
import com.moling.core.workspace.ArtifactKind
import com.moling.core.workspace.ArtifactStatus
import com.moling.core.workspace.BlockKind
import com.moling.core.workspace.Focus
import com.moling.core.workspace.Material
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.Workbench
import com.moling.core.workspace.WorkspaceArtifact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第十二批 A1–A3：选路、进场包、状态机、幂等、归属、重启恢复、外脑原文落案板但不采用。 */
class OutbrainTest {
    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private val epShare = OutbrainEndpoint("ep-share", "分享给 ChatGPT", OutbrainTransport.SHARE, Actor.CHATGPT, configured = true, preference = 20)
    private val epRelay = OutbrainEndpoint("ep-relay", "联络站", OutbrainTransport.RELAY, Actor.CHATGPT, configured = true, preference = 5)
    private val epApiOff = OutbrainEndpoint("ep-api", "模型 API", OutbrainTransport.API, Actor.NONE, configured = false, preference = 1)

    private fun focus(materials: List<Material> = emptyList()) = Focus(
        id = "f-1",
        question = "申论开头怎么写",
        goal = "十分钟内出第一段",
        startedAt = 1,
        revision = 3,
        materialIds = materials.map { it.id },
    )

    private fun material(id: String, text: String, title: String = "") =
        Material(id, "paste", title.ifBlank { text.take(10) }, text, 1)

    // -------------------------------------------------------------- 选路

    @Test
    fun routerByCapabilityConfiguredAndPreference() {
        val r1 = OutbrainRouter.pick(listOf(epShare, epRelay, epApiOff))
        assertTrue(r1 is OutbrainRoute.Pick)
        assertEquals("配置好的端点里偏好数字小的先用", "ep-relay", (r1 as OutbrainRoute.Pick).endpoint.id)
        // 用户排在前面：分享先用（哪怕 preference 更大）
        val r2 = OutbrainRouter.pick(listOf(epShare, epRelay), preferred = setOf("ep-share"))
        assertEquals("ep-share", (r2 as OutbrainRoute.Pick).endpoint.id)
        // 需要异步读黑板：分享没有这个能力
        val r3 = OutbrainRouter.pick(listOf(epShare, epRelay), need = setOf(OutbrainCaps.SEND_PACK, OutbrainCaps.ASYNC_RESULT))
        assertEquals("ep-relay", (r3 as OutbrainRoute.Pick).endpoint.id)
        assertEquals(
            "只有分享端点时要求异步：没有能胜任的",
            "NO_CAPABLE",
            ((OutbrainRouter.pick(listOf(epShare), need = setOf(OutbrainCaps.ASYNC_RESULT))) as OutbrainRoute.Failed).error.code,
        )
        assertEquals(
            "能胜任但都没配置：NOT_CONFIGURED",
            "NOT_CONFIGURED",
            ((OutbrainRouter.pick(listOf(epApiOff))) as OutbrainRoute.Failed).error.code,
        )
    }

    // -------------------------------------------------------------- 进场包

    @Test
    fun packSummarisesSensitiveAndBounded() {
        val ms = listOf(
            material("m-1", "第一段材料", "材料一"),
            material("m-2", "长".repeat(900), "长材料"),
            material("m-3", "我的密码是 abc123", "账号"),
            material("m-4", "第四条", "四"),
        )
        val artifacts = listOf(
            WorkspaceArtifact(
                "a-1", "f-1", ArtifactKind.DRAFT, "草稿",
                listOf(
                    ArtifactBlock("b-1", BlockKind.MARKDOWN, "没采用的一段"),
                    ArtifactBlock("b-2", BlockKind.MARKDOWN, "已采用的一段", accepted = true),
                ),
                createdAt = 5, updatedAt = 5,
            ),
        )
        val pack = Packs.build("ob-7", focus(ms), ms, artifacts, spine = listOf("n-1 主干"), gap = "缺反例", now = 9,
            exclude = setOf("m-4"), maxMaterials = 3)
        assertEquals("敏感材料默认不放", listOf("m-1", "m-2"), pack.materials.map { it.id })
        assertEquals("没放进来 2 条（他删的 + 敏感的）", 2, pack.omitted)
        assertTrue("长材料被截短", pack.materials[1].trimmed)
        assertTrue(pack.materials[1].summary.length <= EntryPackLimits.MAX_MATERIAL_CHARS)
        assertEquals("只给还没采用的草稿块", listOf("a-1/b-1"), pack.drafts.map { it.artifactId + "/" + it.blockId })
        val text = pack.text()
        assertTrue(text.contains("[[外脑:ob-7]]"))
        assertTrue(text.contains("n-1 主干"))
        assertTrue("不把整库塞进去", !text.contains("密码"))
        assertTrue(text.length <= EntryPackLimits.MAX_TEXT_CHARS + 40)
    }

    // -------------------------------------------------------------- 状态机

    @Test
    fun stateFlowIsIdempotentAndPartialIsNotFinal() {
        val f = focus(listOf(material("m-1", "材料")))
        val pack = { sid: String -> Packs.build(sid, f, listOf(material("m-1", "材料")), emptyList(), now = 1) }
        val (s0, r0) = Outbrain.prepare(OutbrainState(), f, pack, Actor.CHATGPT, epShare, now = 1_000)
        val a = (r0 as PortResult.Ok).value
        assertEquals(OutbrainStatus.PREPARED, a.status)
        // 重复点：同一份包不重开
        val (s1, r1) = Outbrain.prepare(s0, f, pack, Actor.CHATGPT, epShare, now = 1_100)
        assertEquals(a.id, (r1 as PortResult.Ok).value.id)
        assertEquals(1, s1.sessions.size)
        // 打开 → 等结果
        val (s2, _) = Outbrain.open(s1, a.id, 2_000)
        assertEquals(OutbrainStatus.OPENED, s2.sessions.single().status)
        val (s3, _) = Outbrain.waiting(s2, a.id, 2_100)
        assertEquals(OutbrainStatus.WAITING_RESULT, s3.sessions.single().status)
        // partial：只进时间线，不算最终结论
        val (s4, p) = Outbrain.receive(s3, "结论前半", now = 3_000, partial = true, turn = 1, source = "api")
        val po = (p as PortResult.Ok).value
        assertTrue(po.partialOnly)
        assertEquals(OutbrainStatus.WAITING_RESULT, s4.sessions.single().status)
        assertTrue(Outbrain.finalResults(s4, a.id).isEmpty())
        // 最终版进来：同一轮流式的 partial 被收掉
        val (s5, fin) = Outbrain.receive(s4, "结论前半，结论后半", now = 4_000, turn = 1, source = "api")
        val fo = (fin as PortResult.Ok).value
        assertFalse(fo.partialOnly)
        assertEquals(OutbrainStatus.RESULT_RECEIVED, s5.sessions.single().status)
        assertEquals(1, Outbrain.finalResults(s5, a.id).size)
        assertEquals("同一轮的 partial 不留在最终结果里", 1, s5.results.size)
        // 重复提交同一个最终版：不重复收
        val (s6, dup) = Outbrain.receive(s5, "结论前半，结论后半", now = 4_500, turn = 1, source = "api")
        assertTrue((dup as PortResult.Ok).value.duplicate)
        assertEquals(1, s6.results.size)
        // 复查（结束会话）
        val (s7, rev) = Outbrain.reviewed(s6, a.id, 5_000)
        assertEquals(OutbrainStatus.REVIEWED, s7.sessions.single().status)
        assertTrue(rev is PortResult.Ok)
        // 没有结果的会话不能标复查
        val (s8, r8) = Outbrain.prepare(s7, f, pack, Actor.CHATGPT, epShare, now = 6_000)
        val b = (r8 as PortResult.Ok).value
        assertNotEquals("复查过的会话不复用", a.id, b.id)
        assertTrue((Outbrain.reviewed(s8, b.id, 6_100).second as PortResult.Failed).error.code == "BAD_STATE")
    }

    @Test
    fun ambiguousResultsNeedAttribution() {
        val f = focus(listOf(material("m-1", "材料")))
        val pack = { sid: String -> Packs.build(sid, f, listOf(material("m-1", "材料")), emptyList(), now = 1) }
        var st = OutbrainState()
        var a = ""
        var b = ""
        listOf(epShare, epRelay).forEachIndexed { i, e ->
            val (ns, r) = Outbrain.prepare(st, f, pack, e.actor, e.copy(id = e.id + i), now = 1_000)
            st = ns
            val s = (r as PortResult.Ok).value
            if (i == 0) a = s.id else b = s.id
            st = Outbrain.waiting(st, s.id, 1_100).first
        }
        assertEquals(2, Outbrain.waiting(st).size)
        // 没有 sessionId：不凭时间猜
        val (same, fail) = Outbrain.receive(st, "带回来的结论", now = 2_000)
        assertTrue(fail is PortResult.Failed)
        assertEquals("NEEDS_ATTRIBUTION", (fail as PortResult.Failed).error.code)
        assertEquals("结果没乱挂", 0, same.results.size)
        // 明确点归属
        val (s2, ok) = Outbrain.receive(st, "带回来的结论", now = 2_100, sessionId = b)
        val out = (ok as PortResult.Ok).value
        assertEquals(b, out.session!!.id)
        assertEquals(OutbrainStatus.RESULT_RECEIVED, s2.sessions.first { it.id == b }.status)
        assertEquals("另一个还在等", 1, Outbrain.waiting(s2).size)
        assertEquals(a, Outbrain.waiting(s2).single().id)
    }


    @Test
    fun pastedTagWinsOverManualPickAndIsStripped() {
        val f = focus(listOf(material("m-1", "材料")))
        val pack = { sid: String -> Packs.build(sid, f, listOf(material("m-1", "材料")), emptyList(), now = 1) }
        var st = OutbrainState()
        val ids = mutableListOf<String>()
        listOf(epShare, epRelay).forEach { e ->
            val (n, r) = Outbrain.prepare(st, f, pack, e.actor, e, now = 1_000)
            st = n
            val s = (r as PortResult.Ok).value
            st = Outbrain.waiting(st, s.id, 1_100).first
            ids += s.id
        }
        assertEquals(2, Outbrain.waiting(st).size)
        // 手选第二个，但文本里带着第一个的标记：标记优先
        val (s2, r) = Outbrain.receive(st, "[[外脑:" + ids[0] + "]] 结论：先亮观点", now = 2_000, sessionId = ids[1])
        val out = (r as PortResult.Ok).value
        assertEquals("标记优先于手选", ids[0], out.session!!.id)
        assertEquals(OutbrainStatus.RESULT_RECEIVED, s2.sessions.first { it.id == ids[0] }.status)
        assertEquals(OutbrainStatus.WAITING_RESULT, s2.sessions.first { it.id == ids[1] }.status)
        assertEquals("标记不落进材料", "结论：先亮观点", out.result.text)
        assertFalse("存储里也不带标记", s2.results.single().text.contains("[[外脑:"))
    }

    @Test
    fun lateResultAfterCancelOnlyGoesHistory() {
        val f = focus(listOf(material("m-1", "材料")))
        val pack = { sid: String -> Packs.build(sid, f, listOf(material("m-1", "材料")), emptyList(), now = 1) }
        val (s0, r0) = Outbrain.prepare(OutbrainState(), f, pack, Actor.CHATGPT, epShare, now = 1_000)
        val a = (r0 as PortResult.Ok).value
        val (s1, _) = Outbrain.open(s0, a.id, 1_100)
        val (s2, _) = Outbrain.cancel(s1, a.id, 1_200)
        val (s3, r3) = Outbrain.receive(s2, "迟到结论", now = 9_000, sessionId = a.id)
        val out = (r3 as PortResult.Ok).value
        assertTrue("迟到结果只进历史", out.history)
        assertEquals("取消的状态不被覆盖", OutbrainStatus.CANCELLED, s3.sessions.single().status)
        assertEquals(1, s3.results.size)
    }

    @Test
    fun noSessionResultIsHistoryOnly() {
        val (s1, r) = Outbrain.receive(OutbrainState(), "没有会话的结果", now = 5)
        val out = (r as PortResult.Ok).value
        assertNull(out.session)
        assertTrue(out.history)
        assertEquals(1, s1.results.size)
        assertTrue(s1.sessions.isEmpty())
    }

    @Test
    fun expireKeepsSessionAndRejectsBoundedSteps() {
        val f = focus(listOf(material("m-1", "材料")))
        val pack = { sid: String -> Packs.build(sid, f, listOf(material("m-1", "材料")), emptyList(), now = 1) }
        val (s0, r0) = Outbrain.prepare(OutbrainState(), f, pack, Actor.CHATGPT, epShare, now = 1_000, ttlMs = 60_000)
        val a = (r0 as PortResult.Ok).value
        var st = Outbrain.open(s0, a.id, 1_100).first
        st = Outbrain.waiting(st, a.id, 1_200).first
        val (s1, n) = Outbrain.expire(st, now = 62_000)
        assertEquals(1, n)
        assertEquals(OutbrainStatus.EXPIRED, s1.sessions.single().status)
        assertTrue("过期仍在收线卡里", Outbrain.needCollect(s1).isNotEmpty())
        val (s2, r2) = Outbrain.receive(s1, "过期后人工补的结果", now = 63_000, sessionId = a.id)
        val out = (r2 as PortResult.Ok).value
        assertTrue(out.history)
        assertEquals("过期会话的状态不被结果改掉", OutbrainStatus.EXPIRED, s2.sessions.single().status)
        assertEquals(1, s2.results.size)
        // 过期后不能再发
        assertTrue((Outbrain.open(s2, a.id, 64_000).second as PortResult.Failed).error.code == "BAD_STATE")
    }

    @Test
    fun restartRecoversWaitingSessions() {
        val f = focus(listOf(material("m-1", "材料")))
        val pack = { sid: String -> Packs.build(sid, f, listOf(material("m-1", "材料")), emptyList(), now = 1) }
        val (s0, r0) = Outbrain.prepare(OutbrainState(), f, pack, Actor.CHATGPT, epShare, now = 1_000)
        val a = (r0 as PortResult.Ok).value
        var st = Outbrain.open(s0, a.id, 1_100).first
        st = Outbrain.waiting(st, a.id, 1_200).first
        val raw = OutbrainCodec.encode(st)
        val back = Outbrain.recover(OutbrainCodec.decode(raw))
        assertEquals(OutbrainStatus.WAITING_RESULT, back.sessions.single().status)
        assertTrue(back.sessions.single().timeline.any { it.kind == OutbrainEventKind.RECOVERED })
        // 重启后结果照样接回来
        val (s3, r3) = Outbrain.receive(back, "重启后回来的结论", now = 2_000)
        assertEquals(OutbrainStatus.RESULT_RECEIVED, s3.sessions.single().status)
        assertTrue(r3 is PortResult.Ok)
    }


    // -------------------------------------------------------------- F12-2 id / 复用

    @Test
    fun sessionIdsCarryTimestampAndSurviveUnreadableFile() {
        val f = focus()
        val pack = { sid: String -> Packs.build(sid, f, emptyList(), emptyList(), now = 1) }
        val a = (Outbrain.prepare(OutbrainState(), f, pack, Actor.CHATGPT, epShare, now = 1_000).second as PortResult.Ok).value
        assertTrue("会话 id 带时间戳：" + a.id, a.id.matches(Regex("ob-\\d+-\\d+")))
        assertEquals("带时间戳的 id 也认得出标记", a.id, Outbrain.taggedSessionId("[[外脑:" + a.id + "]] 结论"))
        assertEquals("落材料前去掉标记", "结论", Outbrain.clean("[[外脑:" + a.id + "]] 结论"))
        // outbrain.json 读坏了：不把编号归零，新会话不和旧的撞
        val broken = OutbrainCodec.decode("{不是 json")
        assertTrue("读坏后 seq 不从 0 开始", broken.seq > 0)
        val b = (Outbrain.prepare(broken, f, pack, Actor.CHATGPT, epShare, now = 2_000).second as PortResult.Ok).value
        assertNotEquals(a.id, b.id)
        assertTrue(b.id.matches(Regex("ob-\\d+-\\d+")))
    }

    @Test
    fun attachReusesOnlyMatchingOutbrainArtifact() = runTest {
        val wb = Workbench(store = MemStore(), ask = null)
        val f1 = wb.start("第一件", "材料", now = 1)
        val f2 = wb.start("第二件", "材料", now = 2)
        val a1 = (wb.attachActorResult(f1.id, "ob-9-100", "外脑：甲", "结论一", 10) as PortResult.Ok).value
        // 同一会话同一焦点：复用旧稿
        val a1b = (wb.attachActorResult(f1.id, "ob-9-100", "外脑：甲", "结论二", 11) as PortResult.Ok).value
        assertEquals(a1.id, a1b.id)
        assertEquals(2, a1b.blocks.size)
        // 拿着别的稿子 id 冒充、焦点又不同：新建，不塞进旧稿
        val other = (wb.attachActorResult(f2.id, "ob-8-200", "外脑：乙", "结论三", 20) as PortResult.Ok).value
        assertEquals(f2.id, other.focusId)
        val fresh = (wb.attachActorResult(f2.id, "ob-9-100", "外脑：甲", "结论四", 21, existingArtifactId = a1.id) as PortResult.Ok).value
        assertNotEquals("焦点不对的外脑稿子不复用", a1.id, fresh.id)
        assertEquals(f2.id, fresh.focusId)
        assertTrue("旧稿没被塞进别的焦点的文字", wb.state().artifact(a1.id)!!.blocks.none { it.markdown.contains("结论四") })
        assertEquals(2, wb.state().artifacts.count { it.kind == ArtifactKind.ACTOR_RESULT && it.focusId == f2.id })
    }

    // -------------------------------------------------------------- 落案板

    @Test
    fun deskLandsActorResultAsUnacceptedArtifact() = runTest {
        val store = MemStore()
        val wb = Workbench(store = store, ask = null)
        val f = wb.start("申论开头怎么写", "申论开头最容易卡。我平时先列三点。", now = 1)
        val desk = OutbrainDesk(MemStore(), wb)
        val prepared = desk.prepare(Actor.CHATGPT, epShare, now = 2_000)
        val session = (prepared as PortResult.Ok).value
        assertEquals(OutbrainStatus.PREPARED, session.status)
        assertTrue(session.pack.text().contains(session.pack.question))
        assertTrue(session.pack.text().contains("[[外脑:" + session.id + "]]"))
        desk.open(session.id, 2_100)
        desk.waiting(session.id, 2_200)
        // 没带 sessionId，但只有一个会话在等：认它
        val received = desk.receive("结论：开头先亮观点。依据：申论要给阅卷人省力。不确定：字数限制。", now = 3_000, source = "paste")
        assertTrue(received is PortResult.Ok)
        val (outcome, artifact) = (received as PortResult.Ok).value
        assertEquals(session.id, outcome.session!!.id)
        assertNotNull("最终结论落成 ACTOR_RESULT 稿子", artifact)
        assertEquals(ArtifactKind.ACTOR_RESULT, artifact!!.kind)
        assertEquals("未采用，等他点", ArtifactStatus.READY_FOR_REVIEW, artifact.status)
        assertEquals(BlockKind.QUOTE, artifact.blocks.single().kind)
        assertFalse(artifact.blocks.single().accepted)
        assertTrue(artifact.unresolvedQuestions.any { it.contains("材料") })
        // 材料已挂到焦点上（后面可以引用），但论断还不能进体系图
        val benchState = wb.state()
        val focusNow = benchState.focus(f.id)!!
        assertTrue(focusNow.materialIds.size >= 2)
        assertTrue(artifact.id in focusNow.artifactIds)
        assertTrue("没采用就不进体系图", wb.claims(artifact.id).isEmpty())
        // 重复粘贴同一段：不重复落
        val again = desk.receive("结论：开头先亮观点。依据：申论要给阅卷人省力。不确定：字数限制。", now = 3_100, source = "paste")
        val (dup, made) = (again as PortResult.Ok).value
        assertTrue(dup.duplicate)
        assertNull(made)
        assertEquals(1, wb.state().artifacts.count { it.kind == ArtifactKind.ACTOR_RESULT })
        // 他看过了：会话结束
        val rev = desk.reviewed(session.id, 4_000)
        assertTrue(rev is PortResult.Ok)
        assertEquals(OutbrainStatus.REVIEWED, desk.state().sessions.single().status)
    }


    @Test
    fun appendedResultReturnsToReviewAndIsNotClaimed() = runTest {
        val wb = Workbench(store = MemStore(), ask = null)
        wb.start("申论开头怎么写", "材料", now = 1)
        val desk = OutbrainDesk(MemStore(), wb)
        val s = (desk.prepare(Actor.CHATGPT, epShare, now = 2_000) as PortResult.Ok).value
        desk.open(s.id, 2_100)
        desk.waiting(s.id, 2_200)
        val first = (desk.receive("第一段结论：先亮观点", now = 3_000, source = "paste") as PortResult.Ok).value
        val a = first.second!!
        // 他整份采用（旧块都成 accepted）
        assertTrue(wb.apply(ArtifactAction("ac-1", a.id, a.revision, ArtifactActionKind.ACCEPT_ARTIFACT, at = 3_100)) is PortResult.Ok)
        assertEquals(ArtifactStatus.ACCEPTED, wb.state().artifact(a.id)!!.status)
        assertTrue(wb.claims(a.id).any { it.contains("第一段结论") })
        // 同一会话又带回一段：追加，但整份退回待审、新块未采用
        val second = (desk.receive("第二段结论：再给依据", now = 4_000, source = "paste") as PortResult.Ok).value
        val b = second.second!!
        assertEquals("同一会话追加，不新建稿子", a.id, b.id)
        assertEquals(2, b.blocks.size)
        assertFalse("新块 accepted=false", b.blocks.last().accepted)
        assertEquals("追加后回到待审", ArtifactStatus.READY_FOR_REVIEW, wb.state().artifact(a.id)!!.status)
        assertFalse("新文字不混进已采用", wb.claims(a.id).any { it.contains("第二段结论") })
        assertTrue("旧文字仍是已采用", wb.claims(a.id).any { it.contains("第一段结论") })
    }

    @Test
    fun deskStoresResultWhenFocusGone() = runTest {
        val store = MemStore()
        val wb = Workbench(store = store, ask = null)
        val f = wb.start("一件事", "材料", now = 1)
        val desk = OutbrainDesk(MemStore(), wb)
        val session = (desk.prepare(Actor.CHATGPT, epShare, now = 2_000) as PortResult.Ok).value
        desk.open(session.id, 2_100)
        desk.waiting(session.id, 2_200)
        wb.archive(f.id, 3_000)
        val r = desk.receive("带回来的结论", now = 4_000, sessionId = session.id)
        val (outcome, artifact) = (r as PortResult.Ok).value
        assertNull("焦点不在了就不落稿子", artifact)
        assertTrue(outcome.history)
        assertEquals("结果仍然记下来", 1, desk.results(session.id).size)
        assertTrue(desk.state().sessions.single().timeline.any { it.kind == OutbrainEventKind.ATTRIBUTION })
    }
}
