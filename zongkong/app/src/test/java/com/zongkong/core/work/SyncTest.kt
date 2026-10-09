package com.zongkong.core.work

import com.zongkong.core.ApiResult
import com.zongkong.core.NotionClient
import com.zongkong.core.NotionSim
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

/**
 * 双向同步：总控和“GPT”（另一个客户端，按 GPT 通过 Notion 连接写入的方式）共用模拟 Notion。
 * 每个用例都走真实的 HTTP 请求、真实的 Notion 数据格式。
 */
class SyncTest {
    private lateinit var sim: NotionSim
    private lateinit var zk: NotionClient
    private lateinit var gpt: NotionClient
    private lateinit var dbs: Dbs
    private val zone = ZoneId.of("Asia/Shanghai")
    private val rnd = Random(7)
    private var t = 1_791_000_000_000L // 2026-10 左右

    @Before
    fun setUp() = runTest {
        sim = NotionSim().start()
        sim.clock = { t }
        zk = NotionClient({ "secret_zk" }, base = sim.baseUrl)
        gpt = NotionClient({ "secret_gpt" }, base = sim.baseUrl)
        dbs = (WorkSetup.create(zk, sim.rootPage) as ApiResult.Ok).value
    }

    @After
    fun tearDown() = sim.shutdown()

    private fun tick(minutes: Int = 1) { t += minutes * 60_000L }

    private suspend fun gptWrite(db: String, fields: Map<String, String>, threadId: String? = null): String {
        val schema = (gpt.schema(db) as ApiResult.Ok).value.props
        val all = fields + (F.SOURCE to "GPT") + (if (threadId != null) mapOf(F.THREAD to "关联") else emptyMap())
        val p = NotionMap.toProps(all, all.keys, schema) { threadId }
        return NotionMap.fromPage((gpt.createPageObject(db, p.json, JsonArray(emptyList())) as ApiResult.Ok).value).id
    }

    private suspend fun gptUpdate(pageId: String, db: String, fields: Map<String, String>) {
        val schema = (gpt.schema(db) as ApiResult.Ok).value.props
        val p = NotionMap.toProps(fields + (F.SOURCE to "GPT"), fields.keys + F.SOURCE, schema) { null }
        assertTrue(gpt.updatePage(pageId, p.json) is ApiResult.Ok)
    }

    @Test
    fun setupCreatesThreeLinkedDatabases() {
        assertEquals(3, sim.dbs.size)
        val action = sim.dbs.getValue(dbs.action)
        assertEquals("relation", action.props.getValue(F.THREAD)["type"].toString().trim('"'))
        assertTrue(WorkSetup.missingColumns(Kind.THREAD, sim.dbs.getValue(dbs.thread).props.mapValues { it.value["type"].toString().trim('"') }).isEmpty())
    }

    @Test
    fun localThreadAndActionArePushedWithRelation() = runTest {
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        var (w, th) = WorkOps.create(ref.get(), Kind.THREAD, mapOf(F.TITLE to "国考行测提分", F.WHY to "11 月底笔试"), t, rnd)
        val (w2, act) = WorkOps.create(w, Kind.ACTION, mapOf(F.TITLE to "言语理解 40 题", F.THREAD to th.key, F.PLAN to "2026-10-10T09:00:00+08:00|2026-10-10T10:00:00+08:00", F.CRITERIA to "正确率记录"), t, rnd)
        ref.update { w2 }
        val r = engine.sync(ref, dbs, t)
        assertTrue(r.message, r.ok)
        assertEquals(2, r.pushed)
        val local = ref.get()
        assertTrue(local.recs.all { it.sync == SyncState.SYNCED && it.notionId.isNotEmpty() })
        val ta = local.rec(th.key)!!
        val aa = local.rec(act.key)!!
        assertEquals("国考行测提分", sim.text(ta.notionId, F.TITLE))
        assertEquals(ta.notionId, sim.text(aa.notionId, F.THREAD))
        assertEquals(ta[F.CODE], sim.text(aa.notionId, F.THREAD_CODE))
        assertEquals("2026-10-10T09:00:00.000+08:00|2026-10-10T10:00:00.000+08:00", sim.text(aa.notionId, F.PLAN))
        // 再同步一次：日期格式差异（.000）不能被当成改动
        tick()
        val again = engine.sync(ref, dbs, t)
        assertEquals(0, again.pushed)
        assertEquals(0, again.changed)
    }

    @Test
    fun gptWritesAreDetectedAndLinkedByCode() = runTest {
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        // GPT 新建一个事项（没写编号）和一个只写了“事项编号”的行动——先建事项，等总控补上编号
        val tid = gptWrite(dbs.thread, mapOf(F.TITLE to "改简历", F.STATUS to "待行动", F.JUDGE to "先改项目经历", F.NEXT to "重写第二段"))
        tick()
        engine.sync(ref, dbs, t)
        val thread = ref.get().byNotion(tid)!!
        assertTrue(thread[F.CODE].startsWith("ZK-"))
        assertEquals("先改项目经历", thread[F.JUDGE])
        assertEquals(thread[F.CODE], sim.text(tid, F.CODE)) // 编号写回了 Notion
        assertTrue(ref.get().changes.any { it.what.contains("新出现") && it.by == "GPT" })

        tick()
        val aid = gptWrite(dbs.action, mapOf(F.TITLE to "重写项目经历第二段", F.THREAD_CODE to thread[F.CODE], F.STATUS to "待做", F.CRITERIA to "用 STAR 写完并发给朋友看"))
        tick()
        engine.sync(ref, dbs, t)
        val action = ref.get().byNotion(aid)!!
        assertEquals(thread.key, action.threadKey)
        // 总控发现只有编号没有关联，把关联补写回 Notion
        tick()
        WorkOps.update(ref.get(), action.key, mapOf(F.THREAD to thread.key), t).let { w -> ref.update { w } }
        engine.sync(ref, dbs, t)
        assertEquals(tid, sim.text(aid, F.THREAD))
    }

    @Test
    fun resultWrittenBackAndGptContinues() = runTest {
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        val tid = gptWrite(dbs.thread, mapOf(F.TITLE to "行测提分", F.STATUS to "待行动"))
        val aid = gptWrite(dbs.action, mapOf(F.TITLE to "资料分析 1 套", F.STATUS to "待做", F.CRITERIA to "做完并对答案"), threadId = tid)
        tick()
        engine.sync(ref, dbs, t)
        val a = ref.get().byNotion(aid)!!
        // 用户开始、完成
        tick()
        ref.update { WorkOps.start(it, a.key, t, 0) }
        tick(40)
        ref.update { WorkOps.complete(it, a.key, "做完，正确率 70%，增长率题全错", WorkOps.Met.YES, "专练增长率", t, zone, rnd) }
        val r = engine.sync(ref, dbs, t)
        assertTrue(r.message, r.ok)
        assertEquals("完成", sim.text(aid, F.STATUS))
        assertTrue(sim.text(aid, F.RESULT).contains("增长率题全错"))
        assertEquals("专练增长率", sim.text(tid, F.NEXT))
        val resultNote = sim.pagesIn(dbs.note).single()
        assertEquals("结果", sim.text(resultNote.id, F.TYPE))
        assertEquals(tid, sim.text(resultNote.id, F.THREAD))
        // GPT 读到结果，写新判断和新行动；总控拉到
        tick()
        gptUpdate(tid, dbs.thread, mapOf(F.JUDGE to "增长率是短板，先补公式"))
        val a2 = gptWrite(dbs.action, mapOf(F.TITLE to "增长率专项 20 题", F.STATUS to "待做"), threadId = tid)
        tick()
        val r2 = engine.sync(ref, dbs, t)
        assertTrue(r2.message, r2.ok)
        val th = ref.get().byNotion(tid)!!
        assertEquals("增长率是短板，先补公式", th[F.JUDGE])
        assertEquals(th.key, ref.get().byNotion(a2)!!.threadKey)
        assertTrue(ref.get().changes.any { it.what.startsWith("当前判断") && it.by == "GPT" })
    }

    @Test
    fun statusChangedInNotionIsPulled() = runTest {
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        val (w, a) = WorkOps.create(Work(), Kind.ACTION, mapOf(F.TITLE to "投递简历"), t, rnd)
        ref.update { w }
        engine.sync(ref, dbs, t)
        val nid = ref.get().rec(a.key)!!.notionId
        tick()
        gptUpdate(nid, dbs.action, mapOf(F.STATUS to "改期", F.PLAN to "2026-10-12T20:00:00+08:00"))
        tick()
        engine.sync(ref, dbs, t)
        val got = ref.get().rec(a.key)!!
        assertEquals(ActionStatus.RESCHEDULED, got.actionStatus)
        assertEquals("2026-10-12T20:00:00+08:00", got.plan!!.start)
        assertTrue(ref.get().changes.any { it.what == "状态：待做 → 改期" })
    }

    @Test
    fun editInSameMinuteAfterPullIsNotMissed() = runTest {
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        val tid = gptWrite(dbs.thread, mapOf(F.TITLE to "A"))
        engine.sync(ref, dbs, t)
        t += 10_000 // 同一分钟里 GPT 又改了
        gptUpdate(tid, dbs.thread, mapOf(F.BREAK to "同一分钟的改动"))
        engine.sync(ref, dbs, t)
        assertEquals("同一分钟的改动", ref.get().byNotion(tid)!![F.BREAK])
    }

    @Test
    fun bothSidesEditSameFieldKeepsBoth() = runTest {
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        val (w, th) = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "考研还是工作"), t, rnd)
        ref.update { w }
        engine.sync(ref, dbs, t)
        val nid = ref.get().rec(th.key)!!.notionId
        // 手机上先改断点（离线，没同步），之后 GPT 在 Notion 里也改了断点
        tick()
        ref.update { WorkOps.update(it, th.key, mapOf(F.BREAK to "手机：列完了利弊"), t) }
        tick()
        gptUpdate(nid, dbs.thread, mapOf(F.BREAK to "GPT：补充了就业数据"))
        tick()
        val r = engine.sync(ref, dbs, t)
        assertEquals(1, r.conflicts)
        val got = ref.get().rec(th.key)!!
        assertEquals("GPT：补充了就业数据", got[F.BREAK]) // 后改的为准
        assertEquals("手机：列完了利弊", got.conflicts[F.BREAK]) // 手机上的保留
        assertEquals(SyncState.CONFLICT, got.sync)
        assertEquals("GPT：补充了就业数据", sim.text(nid, F.BREAK))
        ref.update { WorkOps.resolveConflicts(it, th.key) }
        assertEquals(SyncState.SYNCED, ref.get().rec(th.key)!!.sync)
    }

    @Test
    fun localNewerEditWinsAndRemoteIsKept() = runTest {
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        val (w, th) = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "X"), t, rnd)
        ref.update { w }
        engine.sync(ref, dbs, t)
        val nid = ref.get().rec(th.key)!!.notionId
        tick()
        gptUpdate(nid, dbs.thread, mapOf(F.NEXT to "GPT 的下一步"))
        tick(3)
        ref.update { WorkOps.update(it, th.key, mapOf(F.NEXT to "我刚定的下一步"), t) }
        engine.sync(ref, dbs, t)
        assertEquals("我刚定的下一步", sim.text(nid, F.NEXT))
        assertEquals("GPT 的下一步", ref.get().rec(th.key)!!.conflicts[F.NEXT])
    }

    @Test
    fun lostCreateResponseDoesNotDuplicate() = runTest {
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        val (w, th) = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "只该有一条"), t, rnd)
        ref.update { w }
        sim.failAfterWriteNext = 1 // Notion 建好了，但手机收到 502
        val r1 = engine.sync(ref, dbs, t)
        assertFalse(r1.ok)
        val after1 = ref.get().rec(th.key)!!
        assertTrue(after1.createTried)
        assertEquals("", after1.notionId)
        tick()
        val r2 = engine.sync(ref, dbs, t)
        assertTrue(r2.message, r2.ok)
        assertEquals(1, sim.pagesIn(dbs.thread).size)
        assertEquals(SyncState.SYNCED, ref.get().rec(th.key)!!.sync)
    }

    @Test
    fun offlineKeepsChangesPendingWithReason() = runTest {
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        val (w, _) = WorkOps.create(Work(), Kind.NOTE, mapOf(F.TITLE to "一个念头", F.TYPE to "念头"), t, rnd)
        ref.update { w }
        sim.failNext = 100
        val r = engine.sync(ref, dbs, t)
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("503") || r.message.contains("Notion"))
        assertFalse(ref.get().lastSyncOk)
        assertEquals(SyncState.PENDING, ref.get().recs.single().sync)
        sim.failNext = 0
        tick()
        assertTrue(engine.sync(ref, dbs, t).ok)
        assertEquals(SyncState.SYNCED, ref.get().recs.single().sync)
    }

    @Test
    fun actionWaitsForItsThread() = runTest {
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        var (w, th) = WorkOps.create(Work(), Kind.THREAD, mapOf(F.TITLE to "T"), t, rnd)
        val (w2, a) = WorkOps.create(w, Kind.ACTION, mapOf(F.TITLE to "A", F.THREAD to th.key), t, rnd)
        ref.update { w2 }
        // 事项推送时被拒（校验失败）→ 行动不能先建
        sim.failNext = 0
        engine.sync(ref, dbs, t)
        assertEquals(ref.get().rec(th.key)!!.notionId, sim.text(ref.get().rec(a.key)!!.notionId, F.THREAD))
    }

    @Test
    fun missingColumnIsSkippedNotStuck() = runTest {
        // 用户自己建的行动库，没有“卡点”和“断点”列
        val threadDb = dbs.thread
        val props = NotionMap.dbProperties(Kind.ACTION, threadDb).let { o ->
            kotlinx.serialization.json.JsonObject(o.filterKeys { it != F.STUCK && it != F.BREAK })
        }
        val own = (zk.createDatabase(sim.rootPage, "我的行动", props) as ApiResult.Ok).value
        val mine = dbs.copy(action = own)
        val ref = MemoryRef()
        val engine = SyncEngine(zk, rnd)
        val (w, a) = WorkOps.create(Work(), Kind.ACTION, mapOf(F.TITLE to "A"), t, rnd)
        ref.update { w }
        engine.sync(ref, mine, t)
        tick()
        ref.update { WorkOps.stuck(it, a.key, StuckReason.CANT, "不知道从哪开始", t, zone, rnd) }
        val r = engine.sync(ref, mine, t)
        assertTrue(r.message, r.ok)
        val got = ref.get().rec(a.key)!!
        assertEquals(SyncState.SYNCED, got.sync)
        assertTrue(got.syncError.contains("卡点"))
        assertEquals("卡住", sim.text(got.notionId, F.STATUS))
    }

    @Test
    fun paginatesLargePulls() = runTest {
        repeat(130) { i -> gptWrite(dbs.note, mapOf(F.TITLE to "材料 $i", F.TYPE to "材料")) }
        val ref = MemoryRef()
        val r = SyncEngine(zk, rnd).sync(ref, dbs, t)
        assertTrue(r.message, r.ok)
        assertEquals(130, ref.get().notes().size)
        assertTrue(sim.requests.count { it.endsWith("/query") } >= 2 + 2)
    }

    @Test
    fun selfTestPassesAgainstWellFormedNotion() = runTest {
        val steps = SelfTest.run(zk, dbs, t)
        steps.forEach { assertTrue("${it.name}: ${it.detail}", it.ok) }
        assertEquals(0, sim.pagesIn(dbs.thread).size) // 测试页已归档
        assertTrue(steps.any { it.name == "增量查询" })
    }

    @Test
    fun selfTestReportsSharingProblem() = runTest {
        val broken = dbs.copy(note = "1a2b3c4d5e6f708192a3b4c5d6e7f809")
        val steps = SelfTest.run(zk, broken, t)
        val bad = steps.first { !it.ok }
        assertEquals("读取记录库", bad.name)
        assertTrue(bad.detail.contains("连接"))
    }

    @Test
    fun conflictMergeIsPure() {
        val engine = SyncEngine(zk, rnd)
        val local = Rec(Kind.THREAD, "k1", notionId = "n1", f = mapOf(F.TITLE to "T", F.NEXT to "A"), base = mapOf(F.TITLE to "T", F.NEXT to "A"), updatedAt = t)
        val m = engine.merge(local, RemotePage("n1", "", "2026-10-10T01:00:00.000Z", false, mapOf(F.TITLE to "T", F.NEXT to "B")), t)
        assertEquals("B", m.rec[F.NEXT])
        assertEquals(1, m.changes.size)
        assertEquals(SyncState.SYNCED, m.rec.sync)
    }
}
