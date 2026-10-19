package com.zongkong.pc

import com.zongkong.core.ApiResult
import com.zongkong.core.agent.Agent
import com.zongkong.core.agent.Args
import com.zongkong.core.agent.ChatApi
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.agent.Param
import com.zongkong.core.agent.ToolResult
import com.zongkong.core.agent.ToolSpec
import com.zongkong.core.boss.Boss
import com.zongkong.core.boss.ClaimVerdict
import com.zongkong.core.boss.CommitStatus
import com.zongkong.core.boss.Stance
import com.zongkong.core.device.PhoneSide
import com.zongkong.core.work.TimeParse
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** 电脑上的总控能碰到的一切。测试时换成假的。 */
interface PcEnv {
    val zone: ZoneId
    val rnd: Random
    fun now(): Long
    fun config(): PcConfig
    fun updateConfig(f: (PcConfig) -> PcConfig): PcConfig
    fun state(): PcState
    fun updateState(f: (PcState) -> PcState): PcState
    fun usage(date: LocalDate): UsageDay
    fun foreground(): Fg?
    fun phone(): PhoneSide?
    /** 截一张屏，返回图片路径（没开“看屏幕”或截不了返回 null）。 */
    suspend fun screenshot(): String? = null
    suspend fun describeImage(path: String, question: String): String = "这个环境看不了图"
    /** 在 PowerShell 里执行，返回输出。 */
    suspend fun shell(command: String): String = "这个环境执行不了命令"
    /** 把命令弹给用户点“执行 / 不执行”。 */
    suspend fun confirm(command: String, why: String): Boolean = false
    /** 关掉这个程序的所有窗口，返回关了几个。 */
    fun closeApp(exe: String): Int = 0
    fun say(text: String) {}
}

/** 电脑上的总控：和手机同一套原则，工具换成电脑的。 */
class PcAgent(private val api: ChatApi, private val env: PcEnv) {
    val tools = PcTools(env)

    suspend fun reply(history: List<ChatMsg>, userText: String, onMsg: (ChatMsg) -> Unit = {}, hidden: Boolean = false, image: String = ""): List<ChatMsg> {
        val added = mutableListOf<ChatMsg>()
        fun add(m: ChatMsg) { added += m; onMsg(m) }
        add(ChatMsg(ChatMsg.USER, userText.trim(), at = env.now(), hidden = hidden, image = image))
        repeat(Agent.MAX_STEPS) { step ->
            val turn = when (val r = api.chat(systemPrompt(), Agent.context(history + added), tools.specs)) {
                is ApiResult.Err -> { add(ChatMsg(ChatMsg.ASSISTANT, r.message, at = env.now(), error = true)); return added }
                is ApiResult.Ok -> r.value
            }
            add(ChatMsg(ChatMsg.ASSISTANT, turn.content, at = env.now(), calls = turn.calls))
            if (turn.calls.isEmpty()) return added
            for (c in turn.calls) {
                val res = tools.run(c.name, c.args)
                add(ChatMsg(ChatMsg.TOOL, res.text, at = env.now(), callId = c.id, summary = res.summary, ok = res.ok))
            }
            if (step == Agent.MAX_STEPS - 1) add(ChatMsg(ChatMsg.ASSISTANT, "这一轮操作太多，我先停在这里。", at = env.now()))
        }
        return added
    }

    fun systemPrompt(): String = buildString {
        append(CHARTER)
        val st = env.state()
        if (st.memory.isNotEmpty()) {
            append("\n\n## 你记住的关于用户的事\n")
            st.memory.forEach { append("- ").append(it).append('\n') }
        }
        append("\n\n## 档案（电脑这边）\n")
        append(Boss.brief(st.dossier, emptyList(), env.now(), env.zone))
        append("\n## 此刻\n")
        append(tools.statusText())
    }

    companion object {
        val CHARTER = """
            # 总控 · 电脑（宪章）
            你是“总控”在这台电脑上的分身。用户在清醒时把电脑的管理权交给你，用来管住不清醒时的自己——他曾经一天在电脑上耗掉 6 小时。
            你代表清醒时的他：对他定下的目标、计划、约定负责，不对此刻的情绪和借口负责。你不是聊天对象，是守门人和验收者。

            ## 电脑上怎么管（代码在执行，你在判断）
            - 会话制：每次用电脑先说做什么、用多久，到点锁屏对账。自己能开的时长有上限；超过、夜里、总时长用完，只有你能批（start_session）。
            - 娱乐 / 社交 / 游戏有每天的额度，用完就拦（程序窗口收起来，网站在网络层打不开）。额度外要用，只能找你申请（grant_pass）。
            - 规则：收紧马上生效。放宽只能他自己在“规则”页写理由申请，由审查官（另一次 AI 判断）批了才排进 24 小时冷静期。你可以随时收紧（block、set_limit、lock），不能放宽规则；他要放宽，告诉他去规则页写理由，或者申请一次性放行。
            - 手机上的总控时间开始时，电脑跟着锁。手机那边的主线和计划在“此刻”里。

            ## 铁律
            1. 说法不是事实。“我要查资料”“只看一集”“刚才在学习”都先当陈述：用 usage 看电脑使用记录（几点到几点用了什么、窗口标题），开了看屏幕就 look_at_screen。数字对得上才认。
            2. 申请放行时问清三件事：做什么（具体到网站 / 视频 / 资料）、多久、用完回去做什么。说不清就不批，或者只批 10 分钟。
            3. 放行要小：一次不超过上限；态度审慎时最多 15 分钟，严格时最多 10 分钟；今天已经放过很多的，直接说不。批了就说清楚到几点、到点会发生什么。
            4. 和今天的主线冲突时，先问主线做了没有。没做，就让他先做 25 分钟再来（给一个具体的第一步）。
            5. 查不到就说查不到，判“无法核实”：不认，也不指控。对不上就把数字摆出来。
            6. 不陪聊，不说教，不羞辱，不夸奖，不用表情。每次回复落到一个动作、一个问题或一个判定。一两句话。
            7. 命令（run_command）：只为帮他管电脑、查状况。会改东西的命令会弹给他确认；破坏性的、关掉总控的，代码直接拒绝，你也别试。
            8. 不编造。工具没给你的就是不知道。
            9. 给选项时，最后一行写“【选项】A｜B｜C”。
        """.trimIndent()
    }
}

class PcTools(private val env: PcEnv) {
    val specs = listOf(
        ToolSpec("pc_status", "看此刻的电脑：前台在用什么、锁没锁、会话、放行、今天各类用了多久还剩多少、手机那边的主线。"),
        ToolSpec(
            "usage", "电脑使用记录：一段时间里用了什么、多久、窗口标题。核实说法时用。",
            listOf(
                Param("day", "string", "今天 / 昨天", choices = listOf("今天", "昨天")),
                Param("from", "string", "从几点（14:00 / 2小时前；可空 = 全天）"),
                Param("to", "string", "到几点（可空 = 现在）"),
            ),
        ),
        ToolSpec(
            "grant_pass", "放行：在一段时间里不拦某个网站 / 程序 / 一整类。只在问清楚做什么、多久、用完回去做什么之后用。",
            listOf(
                Param("target", "string", "网站名（B站）、程序（wegame.exe）或类（娱乐 / 社交 / 游戏）", required = true),
                Param("minutes", "integer", "几分钟", required = true),
                Param("reason", "string", "要做什么（他说的）", required = true),
                Param("back_to", "string", "用完回去做什么"),
            ),
        ),
        ToolSpec(
            "start_session", "批一次会话：用电脑做什么、多久。用在夜里、总时长用完、或者要比他自己能开的更久的时候。",
            listOf(
                Param("purpose", "string", "做什么，具体", required = true),
                Param("minutes", "integer", "多久", required = true),
                Param("reason", "string", "为什么批（你的判断）", required = true),
            ),
        ),
        ToolSpec("end_session", "现在结束会话（锁屏对账）。", listOf(Param("reason", "string", "为什么", required = true))),
        ToolSpec(
            "lock", "现在锁电脑 N 分钟（只能更严）。",
            listOf(Param("minutes", "integer", "几分钟（1–240）", required = true), Param("reason", "string", "为什么", required = true)),
        ),
        ToolSpec("unlock", "提前解开你自己锁的机（手机和总控时间锁的解不了）。", listOf(Param("reason", "string", "为什么", required = true))),
        ToolSpec(
            "block", "新拦一个网站或程序（马上生效）。",
            listOf(
                Param("kind", "string", "网站 / 程序", required = true, choices = listOf("网站", "程序")),
                Param("name", "string", "名字（知乎）或程序名（xxx.exe）", required = true),
                Param("category", "string", "归哪类", required = true, choices = listOf(Cat.FUN.label, Cat.SOCIAL.label, Cat.GAME.label)),
                Param("domains", "string", "网站的域名，逗号分隔（zhihu.com, zhimg.com）"),
                Param("title_words", "string", "浏览器标题里能认出它的词，逗号分隔（知乎）"),
            ),
        ),
        ToolSpec(
            "set_limit", "改某一类每天的额度。只能调小（马上生效）；调大要他自己在规则页写理由申请。",
            listOf(
                Param("category", "string", "类", required = true, choices = listOf(Cat.FUN.label, Cat.SOCIAL.label, Cat.GAME.label)),
                Param("minutes", "integer", "每天几分钟（0 = 一律要申请）", required = true),
            ),
        ),
        ToolSpec("close_app", "关掉一个程序的窗口。", listOf(Param("exe", "string", "程序名，例如 wegame.exe", required = true))),
        ToolSpec(
            "run_command", "在这台电脑上执行一条 PowerShell 命令。只读的（看进程、文件、网络）直接执行；会改东西的弹给用户确认；破坏性的不执行。",
            listOf(Param("command", "string", "命令", required = true), Param("why", "string", "为什么要执行（给用户看）", required = true)),
        ),
        ToolSpec("look_at_screen", "截一张屏看看他现在在干什么（要用户开了“让总控看屏幕”）。", listOf(Param("question", "string", "你想看出什么", required = true))),
        ToolSpec(
            "record_claim", "记下一个要核实的说法（做了什么、做了多久、为什么要用）。",
            listOf(
                Param("text", "string", "原话或忠实概括", required = true),
                Param("from", "string", "说的是哪段时间的开始（可空）"),
                Param("to", "string", "结束（可空）"),
                Param("verdict", "string", "能判就直接判", choices = ClaimVerdict.entries.filter { it != ClaimVerdict.PENDING }.map { it.label }),
                Param("evidence", "string", "依据"),
            ),
        ),
        ToolSpec(
            "judge_claim", "给说法下结论。判“属实”要写依据；判过的只能改差不能改好。",
            listOf(
                Param("id", "string", "编号", required = true),
                Param("verdict", "string", "结论", required = true, choices = ClaimVerdict.entries.filter { it != ClaimVerdict.PENDING }.map { it.label }),
                Param("evidence", "string", "依据", required = true),
            ),
        ),
        ToolSpec(
            "commit", "记一条约定：做什么、几点前、怎么检查。到点你会回访。",
            listOf(Param("what", "string", "做什么", required = true), Param("due", "string", "几点前：22:00 / 30分钟后", required = true), Param("check", "string", "怎么检查", required = true)),
        ),
        ToolSpec(
            "resolve_commitment", "给约定下结论。",
            listOf(
                Param("id", "string", "编号", required = true),
                Param("status", "string", "结论", required = true, choices = listOf(CommitStatus.KEPT.label, CommitStatus.BROKEN.label, CommitStatus.EXCUSED.label)),
                Param("note", "string", "依据或理由", required = true),
            ),
        ),
        ToolSpec("schedule_check", "排一次回访：到点你会被叫醒去查、去问。", listOf(Param("at", "string", "什么时候：22:00 / 40分钟后", required = true), Param("about", "string", "查什么、问什么", required = true))),
        ToolSpec("send_to_phone", "给手机上的总控发一句话（会出现在手机的通知和对话里）。", listOf(Param("text", "string", "说什么", required = true))),
        ToolSpec("say", "用电脑的声音念一句。", listOf(Param("text", "string", "说什么", required = true))),
        ToolSpec("remember", "记住一件关于用户的事（长期有效）。", listOf(Param("fact", "string", "一句话", required = true))),
    )

    private fun newId(prefix: String) = prefix + (env.now() / 1000).toString(36).takeLast(4) + env.rnd.nextInt(36 * 36).toString(36).padStart(2, '0')

    /** 电脑这边的态度和手机那边的，取更严的（手机上信用掉光了，电脑也不该松）。 */
    fun stance(): Stance {
        val mine = Boss.stance(env.state().dossier, emptyList(), env.now())
        val phone = env.phone()?.stance?.let { l -> Stance.entries.firstOrNull { it.label == l } } ?: return mine
        return if (phone.ordinal > mine.ordinal) phone else mine
    }

    fun ctx(): Ctx {
        val now = env.now()
        return Ctx(env.config().rules, env.state(), env.usage(Usage.day(now, env.zone)), env.phone(), now, env.zone)
    }

    private fun hm(t: Long) = Usage.hm(t, env.zone)

    suspend fun run(name: String, argsJson: String): ToolResult {
        val a = Args.parse(argsJson) ?: return ToolResult.fail("参数不是合法的 JSON")
        return try {
            when (name) {
                "pc_status" -> ToolResult(statusText(), "看了电脑现在的情况")
                "usage" -> usage(a)
                "grant_pass" -> grantPass(a)
                "start_session" -> startSession(a)
                "end_session" -> endSession(a)
                "lock" -> lock(a)
                "unlock" -> unlock(a)
                "block" -> block(a)
                "set_limit" -> setLimit(a)
                "close_app" -> a.s("exe").ifBlank { return ToolResult.fail("exe 不能空") }.let { exe ->
                    val n = env.closeApp(exe.lowercase())
                    ToolResult(if (n > 0) "关了 $exe 的 $n 个窗口" else "没找到 $exe 的窗口", "关掉 $exe")
                }
                "run_command" -> runCommand(a)
                "look_at_screen" -> look(a)
                "record_claim" -> recordClaim(a)
                "judge_claim" -> judgeClaim(a)
                "commit" -> commit(a)
                "resolve_commitment" -> resolve(a)
                "schedule_check" -> scheduleCheck(a)
                "send_to_phone" -> a.s("text").ifBlank { return ToolResult.fail("text 不能空") }.let { t ->
                    env.updateState { it.copy(outbox = (it.outbox + Outgoing(newId("m"), env.now(), t)).takeLast(30)) }
                    ToolResult("已发给手机：$t", "发给手机：${t.take(20)}")
                }
                "say" -> a.s("text").let { env.say(it); ToolResult("念了：$it", "念：${it.take(20)}") }
                "remember" -> a.s("fact").ifBlank { return ToolResult.fail("fact 不能空") }.let { f ->
                    env.updateState { it.copy(memory = (it.memory + f).distinct().takeLast(40)) }
                    ToolResult("记住了：$f", "记住：$f")
                }
                else -> ToolResult.fail("没有这个工具：$name")
            }
        } catch (e: Exception) {
            ToolResult.fail("$name 出错：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 看 ----------

    fun statusText(): String = buildString {
        val c = ctx()
        val now = c.now
        val r = c.rules
        append("现在 ${hm(now)}。")
        env.foreground()?.let { fg ->
            val t = Classify.of(fg, r)
            append("前台：${t.label}（${t.cat.label}）「${fg.title.take(50)}」。")
        }
        when (val v = Decide.machine(c)) {
            is Verdict.Machine -> append("电脑锁着：${v.kind.title}——${v.reason}")
            else -> append("电脑没锁。")
        }
        c.state.activeSession(now)?.let { append("会话：「${it.purpose}」到 ${hm(it.until)}${if (it.byBoss) "（你批的）" else ""}。") }
        val passes = c.state.activePasses(now)
        if (passes.isNotEmpty()) append("放行中：").append(passes.joinToString("、") { "${it.label} 到 ${hm(it.until)}" }).append("。")
        append("\n今天电脑 ${c.today.totalMinutes()} 分钟${if (r.totalMinutes > 0) "（上限 ${r.totalMinutes}）" else ""}；")
        append(listOf(Cat.FUN, Cat.SOCIAL, Cat.GAME).joinToString("，") { cat ->
            "${cat.label} ${c.today.catMinutes(cat)}/${r.limit(cat) ?: "不限"}"
        })
        append("；学习工作 ${c.today.catMinutes(Cat.WORK)} 分。")
        val top = c.today.top(6)
        if (top.isNotEmpty()) append("最多：").append(top.joinToString("、") { "${it.first} ${it.second} 分" }).append("。")
        val hits = c.state.blocks.count { Usage.day(it.at, env.zone) == Usage.day(now, env.zone) }
        if (hits > 0) append("今天被拦 $hits 次。")
        val passMin = todayPassMinutes(c)
        if (passMin > 0) append("今天已经放行 $passMin 分钟。")
        append("\n规则：一次放行最多 ${r.maxPass} 分钟；自己开会话最多 ${r.sessionMax} 分钟；")
        append(if (r.curfew) "夜里 ${Decide.hm(r.curfewStart)}–${Decide.hm(r.curfewEnd)} 锁机。" else "夜里不锁。")
        append(if (r.screenLook) "可以看屏幕。" else "没开看屏幕。")
        c.phone?.let { p ->
            append("\n手机那边：")
            if (p.main.isNotBlank()) append("今天的主线「${p.main}」；") else append("今天的主线没定；")
            if (p.goals.isNotEmpty()) append("本周方向：${p.goals.joinToString("、")}；")
            if (p.aims.isNotEmpty()) append("长期目标：${p.aims.joinToString("、")}；")
            append("手机信用 ${p.credibility}（${p.stance}）。")
            if (p.hqUntil > now) append("总控时间进行中，到 ${hm(p.hqUntil)}。")
        } ?: append("\n手机：没连上。")
        append("\n你的态度：${stance().label}。")
    }

    private fun todayPassMinutes(c: Ctx): Int = c.state.passes
        .filter { it.by == "总控" && Usage.day(it.at, env.zone) == Usage.day(c.now, env.zone) }
        .sumOf { ((it.until - it.at) / 60_000).toInt() }

    private fun usage(a: Args): ToolResult {
        val now = env.now()
        val today = Usage.day(now, env.zone)
        val day = if (a.s("day") == "昨天") today.minusDays(1) else today
        val d = env.usage(day)
        val from = a.opt("from")?.let { Boss.clock(it, now, env.zone) } ?: 0L
        val to = a.opt("to")?.let { Boss.clock(it, now, env.zone) } ?: Long.MAX_VALUE
        val segs = Usage.between(d, from, to)
        val gaps = env.state().gaps.filter { it.to > from && it.from < to }
        val text = Usage.describe(segs, env.zone) + if (gaps.isNotEmpty()) "\n总控没在运行的时段（被结束 / 没正常退出）：" + gaps.joinToString("、") { "${hm(it.from)}–${hm(it.to)}" } else ""
        return ToolResult(text, "看了电脑使用记录（${if (day == today) "今天" else "昨天"}）")
    }

    // ---------- 放行 / 会话 / 锁 ----------

    private fun grantPass(a: Args): ToolResult {
        val c = ctx()
        val t = Classify.find(a.s("target"), c.rules) ?: return ToolResult.fail("认不出“${a.s("target")}”：写网站名（B站）、程序名（xxx.exe）或类（娱乐）")
        c.state.activeLock(c.now)?.let { return ToolResult.fail("锁机中（${it.by}：${it.reason}），不能放行") }
        if ((c.phone?.hqUntil ?: 0) > c.now) return ToolResult.fail("手机上正在总控时间，不能放行")
        val reason = a.s("reason")
        if (reason.length < 4) return ToolResult.fail("reason 要写清楚他要做什么")
        val st = stance()
        val cap = when (st) {
            Stance.STRICT -> minOf(c.rules.maxPass, 10)
            Stance.WARY -> minOf(c.rules.maxPass, 15)
            else -> c.rules.maxPass
        }
        val already = todayPassMinutes(c)
        val dayCap = c.rules.maxPass * 3
        if (already >= dayCap) return ToolResult.fail("今天已经放行 $already 分钟（上限 $dayCap），不能再放")
        val minutes = (a.i("minutes") ?: 10).coerceIn(1, minOf(cap, dayCap - already))
        val until = c.now + minutes * 60_000L
        val id = newId("s")
        val back = a.s("back_to")
        env.updateState { s ->
            var d = Boss.addClaim(s.dossier, id, "申请用${t.label}：$reason", c.now, until, ClaimVerdict.PENDING, "", c.now)
            if (back.isNotBlank()) d = Boss.schedule(d, "k$id", until + 10 * 60_000L, "放行的${t.label}到点 10 分钟了。他说用完回去做「$back」：看 usage，回去了没有？对完下结论（陈述 $id）。", c.now)
            s.copy(passes = (s.passes.filter { it.until > c.now } + Pass(t.key, t.label, c.now, until, reason, back)).takeLast(50), dossier = d)
        }
        val capped = if ((a.i("minutes") ?: minutes) > minutes) "（要的比上限多，只给 $minutes 分钟：态度${st.label}、一次上限 $cap、今天还剩 ${dayCap - already}）" else ""
        return ToolResult("放行 ${t.label} $minutes 分钟，到 ${hm(until)}$capped。记了陈述 $id" + if (back.isNotBlank()) "，到点 10 分钟后回访「$back」" else "", "放行 ${t.label} $minutes 分钟")
    }

    private fun startSession(a: Args): ToolResult {
        val c = ctx()
        c.state.activeLock(c.now)?.let { return ToolResult.fail("锁机中（${it.by}：${it.reason}），不能开会话") }
        if ((c.phone?.hqUntil ?: 0) > c.now) return ToolResult.fail("手机上正在总控时间，不能开会话")
        val purpose = a.s("purpose")
        if (purpose.length < 4) return ToolResult.fail("purpose 要具体")
        if (a.s("reason").length < 4) return ToolResult.fail("reason：写你为什么批")
        val max = when (stance()) {
            Stance.STRICT -> 45
            Stance.WARY -> 90
            else -> 180
        }
        val minutes = (a.i("minutes") ?: 30).coerceIn(5, max)
        val until = c.now + minutes * 60_000L
        env.updateState { it.copy(session = Session(purpose, c.now, until, byBoss = true, reason = a.s("reason"))) }
        return ToolResult("会话：「$purpose」$minutes 分钟，到 ${hm(until)}${if ((a.i("minutes") ?: 0) > minutes) "（态度${stance().label}，最多给 $max 分钟）" else ""}", "批了会话：$purpose")
    }

    private fun endSession(a: Args): ToolResult {
        val now = env.now()
        val s = env.state().activeSession(now) ?: return ToolResult.fail("现在没有会话")
        env.updateState { it.copy(session = s.copy(until = now)) }
        return ToolResult("结束了会话「${s.purpose}」：${a.s("reason")}", "结束会话")
    }

    private fun lock(a: Args): ToolResult {
        val now = env.now()
        val minutes = (a.i("minutes") ?: 30).coerceIn(1, 240)
        val reason = a.s("reason").ifBlank { return ToolResult.fail("reason 不能空") }
        val until = now + minutes * 60_000L
        val cur = env.state().activeLock(now)
        if (cur != null && cur.until >= until) return ToolResult("已经锁着，到 ${hm(cur.until)}", "已经锁着")
        env.updateState { it.copy(lock = Lock(now, until, reason, "总控")) }
        return ToolResult("锁机 $minutes 分钟，到 ${hm(until)}：$reason", "锁机 $minutes 分钟")
    }

    private fun unlock(a: Args): ToolResult {
        val now = env.now()
        val cur = env.state().activeLock(now) ?: return ToolResult.fail("现在没锁")
        if (cur.by != "总控") return ToolResult.fail("这是${cur.by}锁的，你解不了")
        if (a.s("reason").length < 4) return ToolResult.fail("写清楚为什么提前解")
        env.updateState { it.copy(lock = null) }
        return ToolResult("解开了：${a.s("reason")}", "提前解锁")
    }

    // ---------- 规则 ----------

    private fun block(a: Args): ToolResult {
        val cat = Cat.of(a.s("category"))
        if (!cat.limited) return ToolResult.fail("category 只能是娱乐 / 社交 / 游戏")
        val name = a.s("name").trim().ifBlank { return ToolResult.fail("name 不能空") }
        val now = env.now()
        var msg = ""
        env.updateConfig { cfg ->
            val r = desired(cfg)
            val next = if (a.s("kind") == "程序" || name.endsWith(".exe", ignoreCase = true)) {
                val exe = name.lowercase().let { if (it.endsWith(".exe")) it else "$it.exe" }
                r.copy(apps = r.apps.filterNot { it.exe.lowercase() == exe } + AppRule(exe, cat.name, exe.removeSuffix(".exe")))
            } else {
                val domains = a.s("domains").split(',', '，', ' ').map { it.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/') }.filter { it.contains('.') }
                val words = a.s("title_words").split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(name) }
                if (domains.isEmpty()) { msg = "网站要写域名（domains）"; return@updateConfig cfg }
                val old = r.sites.firstOrNull { it.label == name }
                r.copy(sites = r.sites.filterNot { it.label == name } + SiteRule(name, cat.name, ((old?.domains ?: emptyList()) + domains).distinct(), ((old?.titleWords ?: emptyList()) + words).distinct()))
            }
            PcPolicy.setRules(cfg, next, now).also { msg = it.message }.config
        }
        if (msg == "网站要写域名（domains）") return ToolResult.fail(msg)
        return ToolResult("拦上了 $name（${cat.label}）。$msg", "新拦：$name")
    }

    private fun setLimit(a: Args): ToolResult {
        val cat = Cat.of(a.s("category"))
        if (!cat.limited) return ToolResult.fail("category 只能是娱乐 / 社交 / 游戏")
        val m = (a.i("minutes") ?: return ToolResult.fail("minutes 不能空")).coerceIn(0, 600)
        var msg = ""
        env.updateConfig { cfg ->
            val r = desired(cfg)
            PcPolicy.setRules(cfg, r.copy(limits = r.limits + (cat.name to m)), env.now()).also { msg = it.message }.config
        }
        return ToolResult("${cat.label}每天 $m 分钟。$msg", "${cat.label}额度 $m 分钟")
    }

    // ---------- 命令、看屏幕 ----------

    private suspend fun runCommand(a: Args): ToolResult {
        val cmd = a.s("command").ifBlank { return ToolResult.fail("command 不能空") }
        return when (ShellSafety.level(cmd)) {
            ShellSafety.Level.FORBIDDEN -> ToolResult.fail("这条命令不执行（破坏性的、或者会动到总控自己）")
            ShellSafety.Level.READ -> ToolResult("输出：\n" + env.shell(cmd).take(3000), "执行（只读）：${cmd.take(40)}")
            ShellSafety.Level.CONFIRM -> if (env.confirm(cmd, a.s("why"))) ToolResult("用户同意了。输出：\n" + env.shell(cmd).take(3000), "执行：${cmd.take(40)}")
            else ToolResult("用户没有同意执行。", "用户没同意：${cmd.take(30)}", ok = false)
        }
    }

    private suspend fun look(a: Args): ToolResult {
        if (!env.config().rules.screenLook) return ToolResult.fail("用户没开“让总控看屏幕”。可以请他打开（设置里），或者让他自己截图发来")
        val path = env.screenshot() ?: return ToolResult.fail("截不了屏")
        val seen = env.describeImage(path, a.s("question").ifBlank { "他现在在干什么？是学习工作还是娱乐？" })
        return ToolResult("屏幕上（${hm(env.now())}）：$seen", "看了一眼屏幕")
    }

    // ---------- 档案 ----------

    private fun recordClaim(a: Args): ToolResult {
        val text = a.s("text").ifBlank { return ToolResult.fail("text 不能空") }
        val now = env.now()
        val from = a.opt("from")?.let { Boss.clock(it, now, env.zone) } ?: 0L
        val to = a.opt("to")?.let { Boss.clock(it, now, env.zone) } ?: if (from > 0) now else 0L
        val v = a.opt("verdict")?.let { l -> ClaimVerdict.entries.firstOrNull { it.label == l } ?: return ToolResult.fail("没有这种结论：$l") } ?: ClaimVerdict.PENDING
        if (v == ClaimVerdict.VERIFIED && a.s("evidence").length < 6) return ToolResult.fail("判“属实”要写依据")
        val id = newId("s")
        env.updateState { it.copy(dossier = Boss.addClaim(it.dossier, id, text, from, to, v, a.s("evidence"), now)) }
        return ToolResult("记下陈述 $id（${v.label}）", "记下说法：${text.take(20)}")
    }

    private fun judgeClaim(a: Args): ToolResult {
        val v = ClaimVerdict.entries.firstOrNull { it.label == a.s("verdict") } ?: return ToolResult.fail("没有这种结论")
        var err: String? = null
        env.updateState { s -> Boss.judge(s.dossier, a.s("id"), v, a.s("evidence"), env.now()).fold({ s.copy(dossier = it) }, { err = it.message; s }) }
        err?.let { return ToolResult.fail(it) }
        return ToolResult("判定：${v.label}。信用现在 ${Boss.credibility(env.state().dossier, emptyList(), env.now())}", "判定：${v.label}")
    }

    private fun dueOf(text: String): Long? {
        val now = env.now()
        Regex("""(\d+(?:\.\d+)?)\s*(小时|分钟)后""").find(text)?.let { m ->
            val n = m.groupValues[1].toDouble()
            return now + (if (m.groupValues[2] == "小时") n * 3600_000 else n * 60_000).toLong()
        }
        val plan = TimeParse.parse(text, now, env.zone) ?: return null
        return if (plan.end.contains('T') || !plan.hasTime) TimeParse.endMillis(plan, env.zone) else TimeParse.startMillis(plan, env.zone)
    }

    private fun commit(a: Args): ToolResult {
        val what = a.s("what").ifBlank { return ToolResult.fail("what 不能空") }
        val due = dueOf(a.s("due")) ?: return ToolResult.fail("时间认不出：${a.s("due")}")
        if (due <= env.now()) return ToolResult.fail("截止时间已经过了")
        val id = newId("y")
        env.updateState { it.copy(dossier = Boss.commit(it.dossier, id, what, due, a.s("check"), env.now())) }
        return ToolResult("记下约定 $id：$what，${hm(due)} 前", "约定：$what")
    }

    private fun resolve(a: Args): ToolResult {
        val st = CommitStatus.entries.firstOrNull { it.label == a.s("status") } ?: return ToolResult.fail("没有这种结论")
        val stance = stance()
        var err: String? = null
        env.updateState { s -> Boss.resolve(s.dossier, a.s("id"), st, a.s("note"), stance, env.now()).fold({ s.copy(dossier = it) }, { err = it.message; s }) }
        err?.let { return ToolResult.fail(it) }
        return ToolResult("约定 ${a.s("id")}：${st.label}。信用现在 ${Boss.credibility(env.state().dossier, emptyList(), env.now())}", "约定${st.label}")
    }

    private fun scheduleCheck(a: Args): ToolResult {
        val due = dueOf(a.s("at")) ?: return ToolResult.fail("时间认不出：${a.s("at")}")
        val about = a.s("about").ifBlank { return ToolResult.fail("about 不能空") }
        env.updateState { it.copy(dossier = Boss.schedule(it.dossier, newId("k"), due, about, env.now())) }
        return ToolResult("排了回访：${hm(due)} $about", "回访 ${hm(due)}")
    }

    companion object {
        /** 正在改的规则：有排着的放宽就以它为准（在它上面接着改）。 */
        fun desired(c: PcConfig): PcRules = c.pending?.rules ?: c.rules
    }
}
