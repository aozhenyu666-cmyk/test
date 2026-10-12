package com.zongkong.core.agent

import com.zongkong.core.ApiResult
import com.zongkong.core.Config
import com.zongkong.core.DayClock
import com.zongkong.core.DayLog
import com.zongkong.core.Engine
import com.zongkong.core.Status
import com.zongkong.core.VerifyMode
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.NoteType
import com.zongkong.core.work.Work
import com.zongkong.core.work.WorkOps
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class FakeEnv(var nowMs: Long = ZonedDateTime.of(2026, 10, 11, 9, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()) : AgentEnv {
    override val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    override val rnd = Random(7)
    var cfg = Config()
    var w = Work()
    var book = com.zongkong.core.plan.PlanBook()
    var kicks = 0
    val mem = mutableListOf<String>()
    val sentToYishou = mutableListOf<String>()
    override fun now() = nowMs
    override fun config() = cfg
    override fun updateConfig(f: (Config) -> Config): Config = f(cfg).also { cfg = it }
    override fun work() = w
    override fun updateWork(f: (Work) -> Work): Work = f(w).also { w = it }
    override fun plans() = book
    override fun updatePlans(f: (com.zongkong.core.plan.PlanBook) -> com.zongkong.core.plan.PlanBook) = f(book).also { book = it }
    override fun status(): Status = Engine.evaluate(cfg, DayLog(DayClock.dateKey(nowMs, zone)), nowMs, zone)
    override fun kick() { kicks++ }
    override suspend fun yishou() = "一手：今天 1/2 组"
    override suspend fun sendToYishou(actionKey: String): String { sentToYishou += actionKey; return "已设为一手的当前任务" }
    override suspend fun sync() = "已同步 0 条"
    override fun memory() = mem
    override fun remember(fact: String) { mem += fact }
    override fun forget(fact: String) = mem.removeIf { it.contains(fact) }
    override fun resolveApp(name: String) = mapOf("抖音" to "com.ss.android.ugc.aweme", "B站" to "tv.danmaku.bili")[name] ?: name.takeIf { '.' in it }
    override fun appLabel(pkg: String) = mapOf("com.ss.android.ugc.aweme" to "抖音", "com.tencent.mm" to "微信")[pkg] ?: pkg
    var dossier = com.zongkong.core.boss.Dossier()
    val events = mutableListOf<com.zongkong.core.boss.DevEvent>()
    override fun dossier() = dossier
    override fun updateDossier(f: (com.zongkong.core.boss.Dossier) -> com.zongkong.core.boss.Dossier) = f(dossier).also { dossier = it }
    override fun deviceEvents(from: Long, to: Long) = events.toList()
}

/** 按顺序回放的假模型。记下每次收到的消息，方便检查上下文。 */
class Scripted(vararg turns: Turn) : ChatApi {
    private val queue = ArrayDeque(turns.toList())
    val seen = mutableListOf<List<ChatMsg>>()
    val systems = mutableListOf<String>()
    override suspend fun chat(system: String, messages: List<ChatMsg>, tools: List<ToolSpec>): ApiResult<Turn> {
        seen += messages
        systems += system
        return queue.removeFirstOrNull()?.let { ApiResult.Ok(it) } ?: ApiResult.Err("没有更多回复")
    }
}

private fun call(name: String, args: String, id: String = "c_$name") = ToolCall(id, name, args)

class AgentTest {

    @Test
    fun createsThreadAndActionThroughTools() = runTest {
        val env = FakeEnv()
        val api = Scripted(
            Turn("", listOf(call("create_thread", """{"title":"写论文第二节","why":"周五交初稿","next":"列提纲"}"""))),
            Turn("", listOf(call("add_action", """{"thread":"写论文第二节","title":"列第二节提纲","time":"今天 20:00-21:00","criteria":"提纲有 5 个小标题"}"""))),
            Turn("建好了：今晚 20:00 列提纲。\n【选项】现在就开始｜晚点提醒我", emptyList()),
        )
        val out = Agent(api, env).reply(emptyList(), "帮我把写论文第二节安排上，今晚八点列提纲")
        val t = env.w.threads().single()
        assertEquals("写论文第二节", t.title)
        assertEquals("待行动", t[F.STATUS])
        val a = env.w.actions().single()
        assertEquals(t.key, a.threadKey)
        assertEquals("提纲有 5 个小标题", a[F.CRITERIA])
        assertTrue(a[F.PLAN].contains("T20:00"))
        assertTrue(env.kicks >= 2)
        // 用户、两次工具调用 + 结果、最终回复
        assertEquals(listOf("user", "assistant", "tool", "assistant", "tool", "assistant"), out.map { it.role })
        assertTrue(out[2].summary.contains("新建事项"))
        val (body, opts) = Agent.options(out.last().content)
        assertEquals("建好了：今晚 20:00 列提纲。", body)
        assertEquals(listOf("现在就开始", "晚点提醒我"), opts)
        // 第二次请求时，模型看得到第一次的工具结果
        assertEquals("tool", api.seen[1].last().role)
        assertTrue(api.systems[0].contains("此刻的总控"))
    }

    @Test
    fun badTimeIsReportedNotGuessed() = runTest {
        val env = FakeEnv()
        env.w = WorkOps.create(env.w, Kind.THREAD, mapOf(F.TITLE to "健身"), env.nowMs, env.rnd).first
        val res = ZkTools(env).run("add_action", """{"thread":"健身","title":"跑步","time":"某个时候"}""")
        assertFalse(res.ok)
        assertTrue(res.text.contains("没认出来"))
        assertTrue(env.w.actions().isEmpty())
    }

    @Test
    fun startFocusLocksAndOpensSession() = runTest {
        val env = FakeEnv()
        var w = WorkOps.create(env.w, Kind.THREAD, mapOf(F.TITLE to "阅读"), env.nowMs, env.rnd)
        val t = w.second
        val (w2, a) = WorkOps.create(w.first, Kind.ACTION, mapOf(F.TITLE to "读第三章", F.THREAD to t.key), env.nowMs, env.rnd)
        env.w = w2
        val res = ZkTools(env).run("start_focus", """{"action":"读第三章","minutes":"30"}""")
        assertTrue(res.ok)
        assertEquals("session", res.route)
        assertEquals(a.key, env.w.session?.actionKey)
        assertEquals(env.nowMs + 30 * 60_000L, env.w.session?.lockUntil)
        // 计入严管：专注锁
        val st = Engine.evaluate(env.cfg, DayLog(DayClock.dateKey(env.nowMs, env.zone)), env.nowMs, env.zone, com.zongkong.core.FocusLock("读第三章", env.w.session!!.lockUntil))
        assertTrue(st.strict)
    }

    @Test
    fun completeRecordsResultNote() = runTest {
        val env = FakeEnv()
        val (w1, t) = WorkOps.create(env.w, Kind.THREAD, mapOf(F.TITLE to "阅读"), env.nowMs, env.rnd)
        val (w2, a) = WorkOps.create(w1, Kind.ACTION, mapOf(F.TITLE to "读第三章", F.THREAD to t.key, F.CRITERIA to "写 3 条要点"), env.nowMs, env.rnd)
        env.w = w2
        val res = ZkTools(env).run("complete_action", """{"action":"${a.key}","result":"写了 2 条","met":"部分达到","next":"补第 3 条"}""")
        assertTrue(res.ok)
        assertEquals(ActionStatus.CONFIRM, env.w.rec(a.key)!!.actionStatus)
        assertEquals(1, env.w.notes().count { it.noteType == NoteType.RESULT })
        assertEquals("补第 3 条", env.w.rec(t.key)!![F.NEXT])
    }

    @Test
    fun tighteningGateAppliesNowLooseningWaits() = runTest {
        val env = FakeEnv()
        val tools = ZkTools(env)
        val gate = env.cfg.gates.first { it.id == "think_one" }
        // 提前截止：收紧，立即生效
        val earlier = tools.run("set_gate", """{"id":"think_one","deadline":"20:00"}""")
        assertTrue(earlier.text, earlier.text.contains("已生效"))
        assertEquals(20 * 60, env.cfg.gates.first { it.id == "think_one" }.deadline)
        // 推迟截止：放宽，进待生效
        val later = tools.run("set_gate", """{"id":"think_one","deadline":"23:30"}""")
        assertTrue(later.text, later.text.contains("24 小时后生效"))
        assertEquals(20 * 60, env.cfg.gates.first { it.id == "think_one" }.deadline)
        assertEquals(1, env.cfg.pending.size)
        // 删关卡也要等
        tools.run("remove_gate", """{"id":"${gate.id}"}""")
        assertTrue(env.cfg.gates.any { it.id == gate.id })
    }

    @Test
    fun newEvidenceGateNeedsEvidenceKind() = runTest {
        val env = FakeEnv()
        val tools = ZkTools(env)
        val bad = tools.run("set_gate", """{"dept":"行为管理部","title":"晚间一手","deadline":"22:00","verify":"看事项证据"}""")
        assertFalse(bad.ok)
        val ok = tools.run("set_gate", """{"dept":"行为管理部","title":"晚间一手","open":"18:00","deadline":"22:00","verify":"看事项证据","evidence":"一手练了几组","evidence_min":1}""")
        assertTrue(ok.text, ok.ok)
        val g = env.cfg.gates.first { it.title == "晚间一手" }
        assertEquals(VerifyMode.EVIDENCE, g.verify)
        assertEquals("YISHOU_SETS", g.evidence)
        assertEquals("com.yishou.app", g.launch)
    }

    @Test
    fun blockAddsNowUnblockWaitsAndNeverBlocksSelf() = runTest {
        val env = FakeEnv()
        val tools = ZkTools(env)
        tools.run("block_apps", """{"add":"抖音, com.zongkong.app"}""")
        assertTrue("com.ss.android.ugc.aweme" in env.cfg.blocked)
        assertFalse("com.zongkong.app" in env.cfg.blocked)
        tools.run("block_apps", """{"remove":"抖音"}""")
        assertTrue("com.ss.android.ugc.aweme" in env.cfg.blocked)
        assertTrue(env.cfg.pending.any { it.summary.contains("移除") })
    }

    @Test
    fun loosenRulesWaits() = runTest {
        val env = FakeEnv()
        val before = env.cfg.dailyQuotaMin
        val res = ZkTools(env).run("set_rules", """{"daily_quota_min":${before + 60}}""")
        assertTrue(res.text, res.text.contains("24 小时"))
        assertEquals(before, env.cfg.dailyQuotaMin)
        ZkTools(env).run("set_rules", """{"daily_quota_min":${before - 30}}""")
        assertEquals(before - 30, env.cfg.dailyQuotaMin)
    }

    @Test
    fun openPageAndShortcuts() = runTest {
        val env = FakeEnv()
        val tools = ZkTools(env)
        assertEquals("settings/perm", tools.run("open_page", """{"page":"permissions"}""").route)
        assertEquals("dept/ACT", tools.run("open_page", """{"page":"dept","target":"行为管理部"}""").route)
        assertFalse(tools.run("open_page", """{"page":"thread","target":"不存在"}""").ok)
        assertTrue(tools.run("add_shortcut", """{"label":"晚间复盘","kind":"open","payload":"review"}""").ok)
        assertFalse(tools.run("add_shortcut", """{"label":"乱跳","kind":"open","payload":"nowhere"}""").ok)
        assertTrue(tools.run("add_shortcut", """{"label":"拆一步","kind":"ask","payload":"帮我把当前这步拆成 5 分钟能做的"}""").ok)
        assertEquals(listOf("晚间复盘", "拆一步"), env.cfg.shortcuts.map { it.label })
        tools.run("remove_shortcut", """{"label":"晚间复盘"}""")
        assertEquals(listOf("拆一步"), env.cfg.shortcuts.map { it.label })
    }

    @Test
    fun rememberGoesIntoSystemPrompt() = runTest {
        val env = FakeEnv()
        val api = Scripted(
            Turn("", listOf(call("remember", """{"fact":"晚上 10 点后效率低"}"""))),
            Turn("记住了。", emptyList()),
            Turn("好。", emptyList()),
        )
        val agent = Agent(api, env)
        val first = agent.reply(emptyList(), "我晚上十点以后基本干不动")
        agent.reply(first, "那今晚怎么排")
        assertTrue(api.systems.last().contains("晚上 10 点后效率低"))
    }

    @Test
    fun sendToYishouUsesActionKey() = runTest {
        val env = FakeEnv()
        val (w1, t) = WorkOps.create(env.w, Kind.THREAD, mapOf(F.TITLE to "行测"), env.nowMs, env.rnd)
        val (w2, a) = WorkOps.create(w1, Kind.ACTION, mapOf(F.TITLE to "资料分析 10 题", F.THREAD to t.key), env.nowMs, env.rnd)
        env.w = w2
        val res = ZkTools(env).run("send_to_yishou", """{"action":"资料分析 10 题"}""")
        assertTrue(res.ok)
        assertEquals(listOf(a.key), env.sentToYishou)
    }

    @Test
    fun errorStopsAndIsNotSentBack() = runTest {
        val env = FakeEnv()
        val api = Scripted() // 第一次就出错
        val out = Agent(api, env).reply(emptyList(), "在吗")
        assertTrue(out.last().error)
        val api2 = Scripted(Turn("在。", emptyList()))
        Agent(api2, env).reply(out, "在吗")
        assertTrue(api2.seen[0].none { it.error })
    }

    @Test
    fun runawayLoopStops() = runTest {
        val env = FakeEnv()
        val turns = Array(Agent.MAX_STEPS) { Turn("", listOf(call("get_overview", "{}", "c$it"))) }
        val out = Agent(Scripted(*turns), env).reply(emptyList(), "看看")
        assertTrue(out.last().content.contains("先停在这里"))
    }

    @Test
    fun contextStartsAtUserAndTrimsOldToolResults() {
        val long = "x".repeat(1000)
        val msgs = buildList {
            repeat(20) { i ->
                add(ChatMsg("user", "q$i"))
                add(ChatMsg("assistant", "", calls = listOf(ToolCall("c$i", "get_overview", "{}"))))
                add(ChatMsg("tool", long, callId = "c$i"))
                add(ChatMsg("assistant", "a$i"))
            }
        }
        val ctx = Agent.context(msgs)
        assertEquals("user", ctx.first().role)
        assertTrue(ctx.size <= Agent.WINDOW)
        val tools = ctx.filter { it.role == "tool" }
        assertTrue(tools.first().content.length < 400)
        assertEquals(1000, tools.last().content.length)
    }

    @Test
    fun argsAreLenient() {
        val a = Args.parse("""{"minutes":"25","lock":"true","n":3.0,"s":12}""")!!
        assertEquals(25, a.i("minutes"))
        assertEquals(true, a.b("lock"))
        assertEquals(3, a.i("n"))
        assertEquals("12", a.s("s"))
        assertEquals(null, Args.parse("not json"))
    }

    @Test
    fun clientSendsToolsAndParsesToolCalls() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_9","type":"function","function":{"name":"get_overview","arguments":"{}"}}]}}]}""",
            ),
        )
        server.start()
        val client = ChatClient({ com.zongkong.core.AiConfig(server.url("/v1").toString(), "sk-test", "deepseek-chat") })
        val r = client.chat(
            "sys",
            listOf(
                ChatMsg("user", "你好"),
                ChatMsg("assistant", "", calls = listOf(ToolCall("c1", "list_gates", "{}"))),
                ChatMsg("tool", "关卡：…", callId = "c1"),
            ),
            ZkTools(FakeEnv()).specs,
        )
        val turn = (r as ApiResult.Ok).value
        assertEquals("get_overview", turn.calls.single().name)
        assertEquals("call_9", turn.calls.single().id)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"tool_call_id\":\"c1\""))
        assertTrue(body.contains("\"tool_calls\""))
        server.shutdown()
    }

    @Test
    fun clientExplainsToolUnsupported() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"tools is not supported for this model"}}"""))
        server.start()
        val r = ChatClient({ com.zongkong.core.AiConfig(server.url("/").toString(), "k", "m") }).chat("s", listOf(ChatMsg("user", "hi")), emptyList())
        assertTrue((r as ApiResult.Err).message.contains("不支持工具调用"))
        server.shutdown()
    }

    @Test
    fun overviewMentionsKeysAndGates() = runTest {
        val env = FakeEnv()
        val (w1, t) = WorkOps.create(env.w, Kind.THREAD, mapOf(F.TITLE to "行测"), env.nowMs, env.rnd)
        val (w2, a) = WorkOps.create(w1, Kind.ACTION, mapOf(F.TITLE to "资料分析", F.THREAD to t.key, F.PLAN to "2026-10-11T10:00:00+08:00|2026-10-11T11:00:00+08:00"), env.nowMs, env.rnd)
        env.w = w2
        val text = ZkTools(env).run("get_overview", "{}").text
        assertTrue(text, text.contains(a.key))
        assertTrue(text.contains("id=plan_morning"))
        assertNotNull(Snapshot.brief(env))
    }

    /**
     * 总控者核实一个说法：用户说下午做了 5 小时饭 → 记陈述 → 查时间线（抖音 2 小时多）→ 判“对不上” → 记约定。
     * 判“属实”没依据会被代码拦下；已判的结论不能改好。
     */
    @Test
    fun interrogatesClaimAgainstTimeline() = runTest {
        val env = FakeEnv(ZonedDateTime.of(2026, 10, 11, 19, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli())
        env.cfg = env.cfg.copy(blocked = setOf("com.ss.android.ugc.aweme"))
        fun t(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 11, h, m, 0, 0, env.zone).toInstant().toEpochMilli()
        env.events += listOf(
            com.zongkong.core.boss.DevEvent(t(13, 50), "-"),
            com.zongkong.core.boss.DevEvent(t(14, 30), "com.ss.android.ugc.aweme"),
            com.zongkong.core.boss.DevEvent(t(16, 40), "com.tencent.mm"),
            com.zongkong.core.boss.DevEvent(t(17, 0), "-"),
        )
        val agent = Agent(Scripted(), env)
        val first = agent.tools.run("record_claim", """{"text":"下午做了 5 小时饭","from":"14:00","to":"19:00"}""")
        assertTrue(first.text, first.ok)
        val id = env.dossier.claims.single().id
        assertEquals(com.zongkong.core.boss.ClaimVerdict.PENDING, env.dossier.claims.single().verdict)
        assertEquals(t(14, 0), env.dossier.claims.single().from)

        val tl = agent.tools.run("device_timeline", """{"from":"14:00","to":"19:00"}""")
        assertTrue(tl.text, tl.text.contains("抖音 2 小时 10 分〔娱乐〕"))
        assertTrue(tl.text, tl.text.contains("娱乐合计 2 小时 10 分"))
        assertTrue(tl.text, tl.text.contains("最长连续：抖音 14:30–16:40"))

        val noEvidence = agent.tools.run("judge_claim", """{"id":"$id","verdict":"属实","evidence":""}""")
        assertFalse(noEvidence.ok)
        val judged = agent.tools.run("judge_claim", """{"id":"$id","verdict":"对不上","evidence":"14:30–16:40 抖音 2 小时 10 分"}""")
        assertTrue(judged.text, judged.ok)
        assertTrue(judged.text, judged.text.contains("信用现在 58"))
        // 判过“对不上”，求情也改不回“属实”
        val upgrade = agent.tools.run("judge_claim", """{"id":"$id","verdict":"属实","evidence":"他说其实在等锅"}""")
        assertFalse(upgrade.ok)

        val c = agent.tools.run("commit", """{"what":"做饭前报一句做什么、几点结束","due":"今晚 22:00","check":"看对话记录"}""")
        assertTrue(c.text, c.ok)
        val y = env.dossier.commitments.single()
        assertEquals(t(22, 0), y.due)
        assertEquals(t(22, 5), env.dossier.checks.single().due)

        // 系统提示里有宪章和档案
        val sys = agent.systemPrompt()
        assertTrue(sys.contains(com.zongkong.core.boss.Charter.VERSION))
        assertTrue(sys, sys.contains("信用 58 / 100 → 态度：审慎"))
        assertTrue(sys.contains(y.id))
    }

    @Test
    fun escalateOnlyTightensAndExcusesAreLimited() = runTest {
        val env = FakeEnv()
        val tools = ZkTools(env)
        assertTrue(tools.run("escalate", """{"stance":"严格","hours":24,"reason":"连续三天对不上"}""").ok)
        assertEquals(com.zongkong.core.boss.Stance.STRICT, com.zongkong.core.boss.Boss.stance(env.dossier, emptyList(), env.nowMs))
        // 已经严格了，再“收紧”到审慎不行
        assertFalse(tools.run("escalate", """{"stance":"审慎","hours":2,"reason":"试试放松"}""").ok)
        assertTrue(tools.run("commit", """{"what":"10 点前做完一套题","due":"2小时后","check":"拍照"}""").ok)
        val id = env.dossier.commitments.single().id
        // 严格态度下不给免
        val excuse = tools.run("resolve_commitment", """{"id":"$id","status":"免了","note":"突然有朋友来家里吃饭，走不开"}""")
        assertFalse(excuse.ok)
        assertTrue(excuse.text.contains("严格"))
        assertTrue(tools.run("resolve_commitment", """{"id":"$id","status":"没做到","note":"没有照片"}""").ok)
        // 结了的约定不能再改
        assertFalse(tools.run("resolve_commitment", """{"id":"$id","status":"做到了","note":"其实做了"}""").ok)
    }
}
