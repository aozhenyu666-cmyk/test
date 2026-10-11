package com.zongkong.core.agent

import com.zongkong.core.BlockMode
import com.zongkong.core.Config
import com.zongkong.core.DayClock
import com.zongkong.core.Dept
import com.zongkong.core.Gate
import com.zongkong.core.Policy
import com.zongkong.core.Shortcut
import com.zongkong.core.Status
import com.zongkong.core.VerifyMode
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.Kind
import com.zongkong.core.work.NoteType
import com.zongkong.core.work.Rec
import com.zongkong.core.work.ThreadStatus
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import com.zongkong.core.work.WorkOps
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** 一个参数。type 是 JSON Schema 的 string / integer / boolean。 */
data class Param(val name: String, val type: String, val desc: String, val required: Boolean = false, val choices: List<String> = emptyList())

/** 给模型看的一个工具。 */
data class ToolSpec(val name: String, val description: String, val params: List<Param> = emptyList()) {
    fun wire(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    params.forEach { p ->
                        put(p.name, buildJsonObject {
                            put("type", p.type)
                            put("description", p.desc)
                            if (p.choices.isNotEmpty()) put("enum", buildJsonArray { p.choices.forEach { add(JsonPrimitive(it)) } })
                        })
                    }
                })
                put("required", buildJsonArray { params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } })
            })
        })
    }
}

/**
 * 工具执行的结果。
 * text 给模型看（要具体：新建了什么、编号是什么、哪里失败）；summary 给你看，一句话。
 */
data class ToolResult(val text: String, val summary: String, val ok: Boolean = true, val route: String = "") {
    companion object {
        fun fail(msg: String) = ToolResult("失败：$msg", msg, ok = false)
    }
}

/** 参谋能碰到的东西。App 里接真实存储，测试里接内存。 */
interface AgentEnv {
    val zone: ZoneId
    val rnd: Random
    fun now(): Long
    fun config(): Config
    fun updateConfig(f: (Config) -> Config): Config
    fun work(): Work
    fun updateWork(f: (Work) -> Work): Work
    fun status(): Status
    /** 改了事项 / 行动 / 记录：安排一次同步。 */
    fun kick()
    /** 一手的状态（一段话），连不上时说原因。 */
    suspend fun yishou(): String
    suspend fun sendToYishou(actionKey: String): String
    suspend fun sync(): String
    fun memory(): List<String>
    fun remember(fact: String)
    fun forget(fact: String): Boolean
    /** 应用名 → 包名（拦截名单用）；认不出返回 null。 */
    fun resolveApp(name: String): String?
    fun appLabel(pkg: String): String
}

/**
 * 参谋的工具箱：看状态、管事项和行动、改节律和规则、开专注锁、交给一手、打开页面、加快捷按钮、记住你的习惯。
 *
 * 权限很高，但不能绕过总控的规矩：放宽的改动（推迟截止、删关卡、移出拦截名单、休假）照样 24 小时后才生效；
 * 紧急放行、关无障碍这些只能你自己在界面上做。
 */
class ZkTools(private val env: AgentEnv) {

    val specs: List<ToolSpec> = listOf(
        ToolSpec("get_overview", "看此刻的完整状态：严管没有、今天各关卡、当前一步、接下来的安排、待处理、一手、同步。上下文里已有简版，需要细节时再调。"),
        ToolSpec(
            "list_threads", "列出事项（编号、标题、状态、下一步、开着的行动数）。",
            listOf(Param("status", "string", "只看某种状态；不填看全部未结束的", choices = ThreadStatus.entries.map { it.label } + "全部")),
        ),
        ToolSpec(
            "get_thread", "看一件事的详情：为什么、当前判断、断点、下一步、完成依据、全部行动（带行动键）、最近记录。",
            listOf(Param("thread", "string", "事项编号（ZK-XXXX）或标题", required = true)),
        ),
        ToolSpec("list_gates", "列出节律关卡（id、部门、时间、验收方式、今天的状态）和待生效的放宽改动。"),
        ToolSpec("yishou_status", "读一手（行为管理部）今天的训练：练了几组、目标几组、在练什么、陪练窗口、昨晚总结的规则。"),
        ToolSpec(
            "create_thread", "新建一件事（事项）。",
            listOf(
                Param("title", "string", "事项标题，短", required = true),
                Param("why", "string", "为什么要做"),
                Param("next", "string", "下一步"),
                Param("criteria", "string", "做到什么算完"),
                Param("priority", "string", "优先级", choices = listOf("高", "中", "低")),
            ),
        ),
        ToolSpec(
            "update_thread", "修改事项的字段。只填要改的。",
            listOf(
                Param("thread", "string", "事项编号或标题", required = true),
                Param("title", "string", "新标题"),
                Param("status", "string", "状态", choices = ThreadStatus.entries.map { it.label }),
                Param("why", "string", "为什么"),
                Param("judgment", "string", "当前判断"),
                Param("unsure", "string", "还不确定的"),
                Param("next", "string", "下一步"),
                Param("criteria", "string", "完成依据"),
                Param("priority", "string", "优先级", choices = listOf("高", "中", "低")),
            ),
        ),
        ToolSpec(
            "add_action", "在一件事下面加一步行动。时间用中文写（今晚 20:00-21:00、明天 9:00、周六 10:00），不排时间就不填。",
            listOf(
                Param("thread", "string", "事项编号或标题", required = true),
                Param("title", "string", "要做的一步，动词开头，具体", required = true),
                Param("time", "string", "计划时间"),
                Param("criteria", "string", "做到什么算完（可检查）"),
            ),
        ),
        ToolSpec(
            "update_action", "修改一步行动：标题、完成依据、状态。改时间用 reschedule_action，做完用 complete_action。",
            listOf(
                Param("action", "string", "行动键（get_thread 里有）或标题", required = true),
                Param("title", "string", "新标题"),
                Param("criteria", "string", "完成依据"),
                Param("status", "string", "状态", choices = listOf("待做", "进行中", "待确认", "完成", "卡住")),
            ),
        ),
        ToolSpec(
            "reschedule_action", "改一步行动的计划时间（会记一次改期）。",
            listOf(Param("action", "string", "行动键或标题", required = true), Param("time", "string", "新时间，中文写法", required = true)),
        ),
        ToolSpec(
            "complete_action", "记一步行动的结果。对照完成依据：达到 → 完成；部分达到 → 待确认；没达到 → 卡住。",
            listOf(
                Param("action", "string", "行动键或标题", required = true),
                Param("result", "string", "结果，具体", required = true),
                Param("met", "string", "对照完成依据", required = true, choices = WorkOps.Met.entries.map { it.label }),
                Param("next", "string", "下一步"),
            ),
        ),
        ToolSpec(
            "cancel_action", "取消一步行动（要写原因）。",
            listOf(Param("action", "string", "行动键或标题", required = true), Param("reason", "string", "为什么取消", required = true)),
        ),
        ToolSpec(
            "add_note", "在一件事下记一条：念头、问题、材料、结论（判断）。",
            listOf(
                Param("thread", "string", "事项编号或标题；不挂事项就不填"),
                Param("type", "string", "类型", required = true, choices = listOf("念头", "问题", "材料", "结论")),
                Param("title", "string", "一句话标题", required = true),
                Param("text", "string", "原文 / 详细内容"),
                Param("url", "string", "链接"),
            ),
        ),
        ToolSpec(
            "start_focus", "开始做一步行动，打开计时页。lock = true 时开专注锁：锁定期间娱乐应用一打开就被弹回。",
            listOf(
                Param("action", "string", "行动键或标题", required = true),
                Param("minutes", "integer", "专注锁多少分钟（5–180）；不填按计划结束时间或默认时长"),
                Param("lock", "boolean", "开不开专注锁，默认开"),
            ),
        ),
        ToolSpec(
            "send_to_yishou", "把一步行动交给一手陪练：设为一手的当前任务，一手会就这一步一手一手地问你。",
            listOf(Param("action", "string", "行动键或标题", required = true)),
        ),
        ToolSpec(
            "set_gate", "新建或修改一道节律关卡（每天必须交的一样东西）。收紧立即生效；放宽（推迟截止、减少日子、改成超时才拦）24 小时后生效。",
            listOf(
                Param("id", "string", "要修改的关卡 id；新建不填"),
                Param("dept", "string", "哪个部门", choices = Dept.entries.map { it.label }),
                Param("title", "string", "关卡名，短"),
                Param("instruction", "string", "做什么、交什么"),
                Param("open", "string", "几点开放，HH:mm"),
                Param("deadline", "string", "几点截止，HH:mm"),
                Param("days", "string", "哪几天：每天 / 工作日 / 周末 / 1,3,5（1 = 周一）"),
                Param("block", "string", "什么时候开始拦娱乐应用", choices = BlockMode.entries.map { it.label }),
                Param("verify", "string", "怎么验收", choices = listOf(VerifyMode.TEXT.label, VerifyMode.AI.label, VerifyMode.EVIDENCE.label)),
                Param("evidence", "string", "看事项证据时看哪种", choices = Focus.Evidence.entries.map { it.label }),
                Param("evidence_min", "integer", "证据至少几条 / 一手至少几组"),
                Param("min_chars", "integer", "字数验收的最少字数"),
                Param("rubric", "string", "AI 验收的标准，一行一条"),
            ),
        ),
        ToolSpec("remove_gate", "删除一道关卡（这是放宽，24 小时后生效，可撤回）。", listOf(Param("id", "string", "关卡 id", required = true))),
        ToolSpec(
            "set_rules", "改总控的规则。收紧立即生效，放宽 24 小时后生效。只填要改的。",
            listOf(
                Param("silence_hours", "integer", "多少小时不汇报就严管（0 = 不查）"),
                Param("active_start", "string", "沉默检查从几点开始，HH:mm"),
                Param("active_end", "string", "沉默检查到几点结束，HH:mm"),
                Param("daily_quota_min", "integer", "每天娱乐上限（分钟，0 = 不限）"),
                Param("focus_minutes", "integer", "开始一步时专注锁的默认分钟数（0 = 默认不锁）"),
            ),
        ),
        ToolSpec(
            "block_apps", "把应用加进拦截名单（立即生效）。移出名单是放宽，24 小时后生效。",
            listOf(
                Param("add", "string", "要加的应用，名字或包名，逗号分隔（抖音, B站, com.xxx）"),
                Param("remove", "string", "要移出的应用，逗号分隔"),
            ),
        ),
        ToolSpec(
            "open_page", "替你打开总控的某个页面（你说完话后自动跳过去）。",
            listOf(
                Param("page", "string", "页面", required = true, choices = PAGES.keys.toList()),
                Param("target", "string", "thread 填事项编号，action 填行动键，gate 填关卡 id，dept 填部门名"),
            ),
        ),
        ToolSpec(
            "add_shortcut", "在首页加一个快捷按钮。kind = ask：点了把 payload 这句话发给你（参谋）；kind = open：点了打开 payload 指的页面（open_page 的 page 名，可带 :target）。",
            listOf(
                Param("label", "string", "按钮上的字，4–8 个字", required = true),
                Param("kind", "string", "类型", required = true, choices = listOf(Shortcut.ASK, Shortcut.OPEN)),
                Param("payload", "string", "要发的话或页面", required = true),
                Param("dept", "string", "归哪个部门（显示在部门页）", choices = Dept.entries.map { it.label }),
            ),
        ),
        ToolSpec("remove_shortcut", "删掉一个首页快捷按钮。", listOf(Param("label", "string", "按钮上的字", required = true))),
        ToolSpec("sync_notion", "现在和 Notion 同步一次（拉 GPT 写进去的、推总控改过的）。"),
        ToolSpec(
            "remember", "记住一条关于他的长期事实或偏好（例：晚上 10 点后效率低；周三下午有课），以后每次对话都会带上。",
            listOf(Param("fact", "string", "一句话", required = true)),
        ),
        ToolSpec("forget", "删掉一条记住的事。", listOf(Param("fact", "string", "要删的那句（原样或包含的关键词）", required = true))),
    )

    suspend fun run(name: String, argsJson: String): ToolResult {
        val a = Args.parse(argsJson) ?: return ToolResult.fail("参数不是 JSON：${argsJson.take(80)}")
        return try {
            when (name) {
                "get_overview" -> ToolResult(Snapshot.full(env), "看了一眼全局")
                "list_threads" -> listThreads(a)
                "get_thread" -> getThread(a)
                "list_gates" -> ToolResult(Snapshot.gates(env.config(), env.status(), env.now()), "看了节律关卡")
                "yishou_status" -> env.yishou().let { ToolResult(it, "读了一手的训练") }
                "create_thread" -> createThread(a)
                "update_thread" -> updateThread(a)
                "add_action" -> addAction(a)
                "update_action" -> updateAction(a)
                "reschedule_action" -> reschedule(a)
                "complete_action" -> complete(a)
                "cancel_action" -> cancel(a)
                "add_note" -> addNote(a)
                "start_focus" -> startFocus(a)
                "send_to_yishou" -> sendToYishou(a)
                "set_gate" -> setGate(a)
                "remove_gate" -> removeGate(a)
                "set_rules" -> setRules(a)
                "block_apps" -> blockApps(a)
                "open_page" -> openPage(a)
                "add_shortcut" -> addShortcut(a)
                "remove_shortcut" -> removeShortcut(a)
                "sync_notion" -> env.sync().let { ToolResult(it, it) }
                "remember" -> a.s("fact").takeIf { it.isNotBlank() }?.let { env.remember(it); ToolResult("记住了：$it", "记住：$it") } ?: ToolResult.fail("fact 不能空")
                "forget" -> if (env.forget(a.s("fact"))) ToolResult("删掉了", "不再记：${a.s("fact")}") else ToolResult.fail("没找到这条")
                else -> ToolResult.fail("没有这个工具：$name")
            }
        } catch (e: Exception) {
            ToolResult.fail("${name} 出错：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 找东西 ----------

    private fun findThread(ref: String): Rec? {
        val r = ref.trim()
        if (r.isEmpty()) return null
        val w = env.work()
        return w.threadByCode(r) ?: w.rec(r)?.takeIf { it.kind == Kind.THREAD }
            ?: w.threads().firstOrNull { it.title == r }
            ?: w.threads().filter { it.title.contains(r) || r.contains(it.title) }.maxByOrNull { it.updatedAt }
    }

    private fun findAction(ref: String): Rec? {
        val r = ref.trim()
        if (r.isEmpty()) return null
        val w = env.work()
        return w.rec(r)?.takeIf { it.kind == Kind.ACTION }
            ?: w.actions().firstOrNull { it.title == r && it.actionStatus.open }
            ?: w.actions().firstOrNull { it.title == r }
            ?: w.actions().filter { it.title.contains(r) }.maxByOrNull { it.updatedAt }
    }

    private fun noThread(ref: String) = ToolResult.fail("没找到事项“$ref”。先 list_threads 看编号")
    private fun noAction(ref: String) = ToolResult.fail("没找到行动“$ref”。先 get_thread 看行动键")

    // ---------- 读 ----------

    private fun listThreads(a: Args): ToolResult {
        val want = a.s("status")
        val w = env.work()
        val list = w.threads().filter { t ->
            when {
                want == "全部" -> true
                want.isNotBlank() -> t.threadStatus.label == want
                else -> t.threadStatus.open
            }
        }.sortedByDescending { it.updatedAt }
        if (list.isEmpty()) return ToolResult("没有符合的事项", "列了事项（0 件）")
        val text = list.take(30).joinToString("\n") { t ->
            val open = w.actionsOf(t.key).count { it.actionStatus.open }
            "${t[F.CODE]}｜${t.title}｜${t.threadStatus.label}｜开着的行动 $open｜下一步：${t[F.NEXT].ifBlank { "（没写）" }.take(60)}"
        }
        return ToolResult(text, "列了事项（${list.size} 件）")
    }

    private fun getThread(a: Args): ToolResult {
        val t = findThread(a.s("thread")) ?: return noThread(a.s("thread"))
        return ToolResult(Snapshot.thread(env.work(), t, env.zone), "看了「${t.title}」")
    }

    // ---------- 事项、行动、记录 ----------

    private fun createThread(a: Args): ToolResult {
        val title = a.s("title").ifBlank { return ToolResult.fail("标题不能空") }
        findThread(title)?.takeIf { it.title == title && it.threadStatus.open }?.let {
            return ToolResult("已经有同名的事项 ${it[F.CODE]}，没有重复新建", "已有「${it.title}」", ok = true)
        }
        var rec: Rec? = null
        env.updateWork { w ->
            val (w2, r) = WorkOps.create(w, Kind.THREAD, mapOf(
                F.TITLE to title, F.WHY to a.s("why"), F.NEXT to a.s("next"), F.CRITERIA to a.s("criteria"), F.PRIORITY to a.s("priority"),
            ), env.now(), env.rnd)
            rec = r
            w2
        }
        env.kick()
        val r = rec!!
        return ToolResult("新建了事项 ${r[F.CODE]}「$title」", "新建事项「$title」", route = "")
    }

    private fun updateThread(a: Args): ToolResult {
        val t = findThread(a.s("thread")) ?: return noThread(a.s("thread"))
        val changes = buildMap {
            a.opt("title")?.let { put(F.TITLE, it) }
            a.opt("status")?.let { s -> ThreadStatus.entries.firstOrNull { it.label == s }?.let { put(F.STATUS, it.label) } }
            a.opt("why")?.let { put(F.WHY, it) }
            a.opt("judgment")?.let { put(F.JUDGE, it) }
            a.opt("unsure")?.let { put(F.UNSURE, it) }
            a.opt("next")?.let { put(F.NEXT, it) }
            a.opt("criteria")?.let { put(F.CRITERIA, it) }
            a.opt("priority")?.let { put(F.PRIORITY, it) }
        }
        if (changes.isEmpty()) return ToolResult.fail("没有要改的字段")
        env.updateWork { WorkOps.update(it, t.key, changes, env.now()) }
        env.kick()
        val names = changes.keys.joinToString("、")
        return ToolResult("改了 ${t[F.CODE]}「${t.title}」的 $names", "改了「${t.title}」的$names")
    }

    private fun addAction(a: Args): ToolResult {
        val t = findThread(a.s("thread")) ?: return noThread(a.s("thread"))
        val title = a.s("title").ifBlank { return ToolResult.fail("行动标题不能空") }
        val time = a.s("time")
        val plan = if (time.isBlank()) null else TimeParse.parse(time, env.now(), env.zone)
            ?: return ToolResult.fail("时间“$time”没认出来。换个写法：今晚 20:00-21:00、明天 9:00、周六 10:00")
        var rec: Rec? = null
        env.updateWork { w ->
            val (w2, r) = WorkOps.create(w, Kind.ACTION, buildMap {
                put(F.TITLE, title); put(F.THREAD, t.key); put(F.CRITERIA, a.s("criteria"))
                plan?.let { put(F.PLAN, it.encode()) }
            }, env.now(), env.rnd)
            rec = r
            val w3 = if (t.threadStatus == ThreadStatus.COLLECTING || t.threadStatus == ThreadStatus.THINKING) {
                WorkOps.update(w2, t.key, mapOf(F.STATUS to ThreadStatus.READY.label), env.now())
            } else w2
            w3
        }
        env.kick()
        val when_ = plan?.let { " · ${TimeParse.label(it, env.now(), env.zone)}" } ?: "（没排时间）"
        return ToolResult("在 ${t[F.CODE]} 下新建了行动「$title」，行动键 ${rec!!.key}$when_", "新建行动「$title」$when_")
    }

    private fun updateAction(a: Args): ToolResult {
        val x = findAction(a.s("action")) ?: return noAction(a.s("action"))
        val changes = buildMap {
            a.opt("title")?.let { put(F.TITLE, it) }
            a.opt("criteria")?.let { put(F.CRITERIA, it) }
            a.opt("status")?.let { s -> ActionStatus.entries.firstOrNull { it.label == s }?.let { put(F.STATUS, it.label) } }
        }
        if (changes.isEmpty()) return ToolResult.fail("没有要改的字段")
        env.updateWork { WorkOps.update(it, x.key, changes, env.now()) }
        env.kick()
        return ToolResult("改了行动「${x.title}」（${x.key}）：${changes.keys.joinToString("、")}", "改了「${x.title}」")
    }

    private fun reschedule(a: Args): ToolResult {
        val x = findAction(a.s("action")) ?: return noAction(a.s("action"))
        val plan = TimeParse.parse(a.s("time"), env.now(), env.zone) ?: return ToolResult.fail("时间“${a.s("time")}”没认出来")
        env.updateWork { WorkOps.reschedule(it, x.key, plan, env.now()) }
        env.kick()
        val label = TimeParse.label(plan, env.now(), env.zone)
        return ToolResult("「${x.title}」改到 $label", "「${x.title}」改到 $label")
    }

    private fun complete(a: Args): ToolResult {
        val x = findAction(a.s("action")) ?: return noAction(a.s("action"))
        val met = WorkOps.Met.entries.firstOrNull { it.label == a.s("met") || it.name.equals(a.s("met"), true) }
            ?: return ToolResult.fail("met 只能是 达到 / 部分达到 / 没达到")
        val result = a.s("result").ifBlank { return ToolResult.fail("结果不能空") }
        env.updateWork { WorkOps.complete(it, x.key, result, met, a.s("next"), env.now(), env.zone, env.rnd) }
        env.kick()
        val st = env.work().rec(x.key)?.actionStatus?.label.orEmpty()
        return ToolResult("「${x.title}」记了结果，状态：$st", "「${x.title}」→ $st")
    }

    private fun cancel(a: Args): ToolResult {
        val x = findAction(a.s("action")) ?: return noAction(a.s("action"))
        val reason = a.s("reason").ifBlank { return ToolResult.fail("取消要写原因") }
        env.updateWork { WorkOps.cancel(it, x.key, reason, env.now()) }
        env.kick()
        return ToolResult("取消了「${x.title}」：$reason", "取消「${x.title}」")
    }

    private fun addNote(a: Args): ToolResult {
        val ref = a.s("thread")
        val t = if (ref.isBlank()) null else findThread(ref) ?: return noThread(ref)
        val type = NoteType.entries.firstOrNull { it.label == a.s("type") } ?: NoteType.IDEA
        val title = a.s("title").ifBlank { a.s("text").take(40) }.ifBlank { return ToolResult.fail("标题和内容不能都空") }
        val now = env.now()
        env.updateWork { w ->
            WorkOps.create(w, Kind.NOTE, mapOf(
                F.TITLE to (if (type == NoteType.CONCLUSION && !title.startsWith("判断")) "判断：$title" else title).take(80),
                F.TYPE to type.label, F.THREAD to (t?.key ?: ""), F.RAW to a.s("text").ifBlank { title }, F.URL to a.s("url"),
                F.DATE to Instant.ofEpochMilli(now).atZone(env.zone).toLocalDate().toString(),
            ), now, env.rnd).first
        }
        env.kick()
        val where = t?.let { " → ${it[F.CODE]}「${it.title}」" } ?: ""
        return ToolResult("记了一条${type.label}「$title」$where", "记下${type.label}「$title」$where")
    }

    private fun startFocus(a: Args): ToolResult {
        val x = findAction(a.s("action")) ?: return noAction(a.s("action"))
        if (!x.actionStatus.open) return ToolResult.fail("「${x.title}」已经${x.actionStatus.label}了")
        val now = env.now()
        val lock = a.b("lock") ?: true
        val planned = x.plan?.let { TimeParse.endMillis(it, env.zone) }?.takeIf { it > now + 5 * 60_000 }?.let { ((it - now) / 60_000).toInt() }
        val minutes = if (!lock) 0 else (a.i("minutes") ?: planned ?: env.config().focusMinutes).coerceIn(5, 180)
        env.updateWork { WorkOps.start(it, x.key, now, minutes) }
        env.kick()
        val lockText = if (minutes > 0) "，专注锁 $minutes 分钟" else ""
        return ToolResult("开始做「${x.title}」$lockText，已打开计时页", "开始「${x.title}」$lockText", route = "session")
    }

    private suspend fun sendToYishou(a: Args): ToolResult {
        val x = findAction(a.s("action")) ?: return noAction(a.s("action"))
        val msg = env.sendToYishou(x.key)
        val ok = !msg.startsWith("失败") && !msg.contains("没装") && !msg.contains("连不上")
        return ToolResult(msg, if (ok) "「${x.title}」交给了一手" else msg, ok = ok)
    }

    // ---------- 节律、规则 ----------

    private fun setGate(a: Args): ToolResult {
        val cfg = env.config()
        val id = a.s("id")
        val old = if (id.isBlank()) null else cfg.gates.firstOrNull { it.id == id } ?: return ToolResult.fail("没有 id 为 $id 的关卡。先 list_gates")
        val dept = a.opt("dept")?.let { d -> Dept.entries.firstOrNull { it.label == d || it.name == d } } ?: old?.dept
            ?: return ToolResult.fail("新建关卡要写部门")
        val title = a.opt("title") ?: old?.title ?: return ToolResult.fail("新建关卡要写标题")
        val open = a.opt("open")?.let { DayClock.parse(it) ?: return ToolResult.fail("开放时间“$it”格式不对，用 HH:mm") } ?: old?.openAt ?: DayClock.DAY_START_MIN
        val deadline = a.opt("deadline")?.let { DayClock.parse(it) ?: return ToolResult.fail("截止时间“$it”格式不对，用 HH:mm") } ?: old?.deadline
            ?: return ToolResult.fail("新建关卡要写截止时间")
        if (DayClock.rank(deadline) <= DayClock.rank(open)) return ToolResult.fail("截止要晚于开放")
        val days = a.opt("days")?.let { parseDays(it) ?: return ToolResult.fail("日子“$it”看不懂：每天 / 工作日 / 周末 / 1,3,5") } ?: old?.days ?: Gate.ALL_DAYS
        val block = a.opt("block")?.let { b -> BlockMode.entries.firstOrNull { it.label == b } } ?: old?.block ?: BlockMode.AFTER_DEADLINE
        val verify = a.opt("verify")?.let { v -> VerifyMode.entries.firstOrNull { it.label == v } } ?: old?.verify ?: VerifyMode.TEXT
        val evidence = a.opt("evidence")?.let { e -> Focus.Evidence.entries.firstOrNull { it.label == e || it.name == e }?.name } ?: old?.evidence.orEmpty()
        if (verify == VerifyMode.EVIDENCE && evidence.isBlank()) return ToolResult.fail("看事项证据的关卡要写 evidence")
        val gate = Gate(
            id = old?.id ?: ("g" + env.now().toString(36) + env.rnd.nextInt(1000)),
            dept = dept,
            title = title,
            instruction = a.opt("instruction") ?: old?.instruction ?: title,
            template = old?.template.orEmpty(),
            openAt = open,
            deadline = deadline,
            days = days,
            block = block,
            verify = verify,
            minChars = a.i("min_chars") ?: old?.minChars ?: 30,
            rubric = a.opt("rubric") ?: old?.rubric.orEmpty(),
            notionDb = old?.notionDb.orEmpty(),
            notionMinPages = old?.notionMinPages ?: 1,
            launch = old?.launch.orEmpty().ifBlank { if (evidence == Focus.Evidence.YISHOU_SETS.name) "com.yishou.app" else "" },
            evidence = evidence,
            evidenceMin = a.i("evidence_min") ?: old?.evidenceMin ?: 1,
        )
        var msg = ""
        env.updateConfig { c -> Policy.putGate(c, gate, env.now()).also { msg = it.message }.config }
        return ToolResult("关卡 ${gate.id}：$msg", msg.lines().first(), route = "")
    }

    private fun parseDays(s: String): Set<Int>? = when (s.trim()) {
        "每天", "全部", "天天" -> Gate.ALL_DAYS
        "工作日" -> setOf(1, 2, 3, 4, 5)
        "周末" -> setOf(6, 7)
        else -> s.split(',', '，', '、', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet().takeIf { it.isNotEmpty() }
    }

    private fun removeGate(a: Args): ToolResult {
        val id = a.s("id")
        if (env.config().gates.none { it.id == id }) return ToolResult.fail("没有 id 为 $id 的关卡")
        var msg = ""
        env.updateConfig { c -> Policy.removeGate(c, id, env.now()).also { msg = it.message }.config }
        return ToolResult(msg, msg.lines().first())
    }

    private fun setRules(a: Args): ToolResult {
        val c0 = env.config()
        val r0 = Policy.rulesOf(c0)
        val r = r0.copy(
            silenceHours = a.i("silence_hours")?.coerceIn(0, 24) ?: r0.silenceHours,
            activeStart = a.opt("active_start")?.let { DayClock.parse(it) ?: return ToolResult.fail("时间“$it”格式不对") } ?: r0.activeStart,
            activeEnd = a.opt("active_end")?.let { DayClock.parse(it) ?: return ToolResult.fail("时间“$it”格式不对") } ?: r0.activeEnd,
            dailyQuotaMin = a.i("daily_quota_min")?.coerceIn(0, 24 * 60) ?: r0.dailyQuotaMin,
        )
        val parts = mutableListOf<String>()
        if (r != r0) env.updateConfig { c -> Policy.setRules(c, r, env.now()).also { parts += it.message }.config }
        a.i("focus_minutes")?.let { m ->
            val v = m.coerceIn(0, 180)
            // 默认专注锁变短是放宽，但只影响以后新开始的一步，这里直接改；变成 0 时提醒
            env.updateConfig { it.copy(focusMinutes = v) }
            parts += if (v == 0) "开始一步时默认不开专注锁" else "专注锁默认 $v 分钟"
        }
        if (parts.isEmpty()) return ToolResult.fail("没有要改的")
        val msg = parts.joinToString("\n")
        return ToolResult(msg, msg.lines().first())
    }

    private fun blockApps(a: Args): ToolResult {
        val add = a.s("add").split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }
        val remove = a.s("remove").split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }
        if (add.isEmpty() && remove.isEmpty()) return ToolResult.fail("add 和 remove 都空")
        val unknown = mutableListOf<String>()
        val addPkgs = add.mapNotNull { env.resolveApp(it) ?: run { unknown += it; null } }.toSet()
        val removePkgs = remove.mapNotNull { env.resolveApp(it) ?: run { unknown += it; null } }.toSet()
        val never = com.zongkong.core.Defaults.neverBlock
        val refused = addPkgs.filter { it in never }
        var msg = ""
        env.updateConfig { c ->
            val next = (c.blocked + (addPkgs - never)) - removePkgs
            Policy.setBlocked(c, next, env::appLabel, env.now()).also { msg = it.message }.config
        }
        val extra = buildList {
            if (unknown.isNotEmpty()) add("没认出：" + unknown.joinToString("、") + "（手机上没装，或写包名）")
            if (refused.isNotEmpty()) add("这些不能拦：" + refused.joinToString("、") { env.appLabel(it) })
        }
        val all = (listOf(msg) + extra).joinToString("\n")
        return ToolResult(all, msg.lines().first(), ok = addPkgs.isNotEmpty() || removePkgs.isNotEmpty())
    }

    // ---------- 界面 ----------

    private fun openPage(a: Args): ToolResult {
        val page = a.s("page")
        val target = a.s("target")
        val route = route(page, target) ?: return ToolResult.fail("打不开 $page${if (target.isNotBlank()) ":$target" else ""}")
        return ToolResult("会在回复后打开「${PAGES[page]}」", "打开「${PAGES[page]}」", route = route)
    }

    /** 页面名 → 应用里的导航路由。找不到目标时返回 null。 */
    fun route(page: String, target: String = ""): String? = when (page) {
        "thread" -> findThread(target)?.let { "thread/${it.key}" }
        "action" -> findAction(target)?.let { "action/${it.key}" }
        "gate" -> env.config().gates.firstOrNull { it.id == target || it.title == target }?.let { "gate/${it.id}" }
        "dept" -> (Dept.entries.firstOrNull { it.label == target || it.name == target || it.seal == target } ?: Dept.INFO).let { "dept/${it.name}" }
        else -> ROUTES[page]
    }

    private fun addShortcut(a: Args): ToolResult {
        val label = a.s("label").take(10).ifBlank { return ToolResult.fail("按钮的字不能空") }
        val kind = a.s("kind").takeIf { it == Shortcut.ASK || it == Shortcut.OPEN } ?: return ToolResult.fail("kind 只能是 ask 或 open")
        val payload = a.s("payload").ifBlank { return ToolResult.fail("payload 不能空") }
        if (kind == Shortcut.OPEN) {
            val (p, t) = payload.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (route(p, t) == null) return ToolResult.fail("页面“$payload”打不开")
        }
        val dept = a.opt("dept")?.let { d -> Dept.entries.firstOrNull { it.label == d } }
        env.updateConfig { c ->
            val rest = c.shortcuts.filterNot { it.label == label }
            c.copy(shortcuts = (rest + Shortcut("s" + env.now().toString(36), label, kind, payload, dept)).takeLast(12))
        }
        return ToolResult("首页加了快捷按钮「$label」", "加了按钮「$label」")
    }

    private fun removeShortcut(a: Args): ToolResult {
        val label = a.s("label")
        if (env.config().shortcuts.none { it.label == label }) return ToolResult.fail("没有叫「$label」的按钮")
        env.updateConfig { c -> c.copy(shortcuts = c.shortcuts.filterNot { it.label == label }) }
        return ToolResult("删掉了按钮「$label」", "删掉按钮「$label」")
    }

    companion object {
        /** 参谋能打开的页面。 */
        val PAGES: Map<String, String> = linkedMapOf(
            "today" to "今天",
            "depts" to "四部",
            "dept" to "某个部门",
            "threads" to "事项列表",
            "thread" to "某件事",
            "action" to "某一步行动",
            "capture" to "收集",
            "plan" to "晨间部署向导",
            "review" to "晚间复盘向导",
            "rhythm" to "今天的节律关卡",
            "gate" to "某道关卡",
            "session" to "正在做（计时页）",
            "results" to "查看结果",
            "stuck" to "我卡住了",
            "settings" to "设置",
            "permissions" to "权限与运行状态",
            "block" to "拦截名单（含紧急放行）",
            "rules" to "规则",
            "gates" to "关卡设置",
            "notion" to "Notion 连接",
            "ai" to "AI 接口",
        )

        private val ROUTES: Map<String, String> = mapOf(
            "today" to "home",
            "depts" to "depts",
            "threads" to "threads",
            "capture" to "capture",
            "plan" to "wizard/plan",
            "review" to "wizard/review",
            "rhythm" to "rhythm",
            "session" to "session",
            "results" to "results",
            "stuck" to "stuckpick",
            "settings" to "settings",
            "permissions" to "settings/perm",
            "block" to "settings/block",
            "rules" to "settings/rules",
            "gates" to "settings/gates",
            "notion" to "settings/notion",
            "ai" to "settings/ai",
        )
    }
}

/** 模型给的参数。全按字符串读，数字、布尔宽松转换（有的模型会把数字写成字符串）。 */
class Args(private val o: JsonObject) {
    fun s(k: String): String = o[k]?.let { el -> (el as? JsonPrimitive)?.contentOrNull ?: el.toString() }?.trim().orEmpty()
    fun opt(k: String): String? = s(k).takeIf { it.isNotEmpty() }
    fun i(k: String): Int? = (o[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull()?.toInt() }
    fun b(k: String): Boolean? = (o[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.let { s -> s == "true" || s == "是" } }

    companion object {
        fun parse(json: String): Args? = try {
            val t = json.trim().ifEmpty { "{}" }
            com.zongkong.core.Net.parseObject(t)?.let { Args(it) }
        } catch (e: Exception) {
            null
        }
    }
}
