package com.zongkong.core.agent

import com.zongkong.core.BlockMode
import com.zongkong.core.Config
import com.zongkong.core.DayClock
import com.zongkong.core.Dept
import com.zongkong.core.Gate
import com.zongkong.core.Policy
import com.zongkong.core.Shortcut
import com.zongkong.core.Status
import com.zongkong.core.boss.Boss
import com.zongkong.core.boss.ClaimVerdict
import com.zongkong.core.boss.CommitStatus
import com.zongkong.core.boss.Stance
import com.zongkong.core.boss.Timeline
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
    fun plans(): com.zongkong.core.plan.PlanBook
    fun updatePlans(f: (com.zongkong.core.plan.PlanBook) -> com.zongkong.core.plan.PlanBook): com.zongkong.core.plan.PlanBook
    fun status(): Status
    /** 改了事项 / 行动 / 记录：安排一次同步。 */
    fun kick()
    /** 一手的状态（一段话），连不上时说原因。 */
    suspend fun yishou(): String
    suspend fun sendToYishou(actionKey: String): String
    /** 总控时间进行中时：当前环节和要求（空 = 没在总控时间里）。 */
    fun sessionContext(): String = ""

    /** 进入总控时间（参谋觉得你该停下来修一修了）。 */
    fun startHq(minutes: Int): String = "这个环境进不了总控时间"

    // ---------- 总控者：档案和眼睛 ----------

    fun dossier(): com.zongkong.core.boss.Dossier = com.zongkong.core.boss.Dossier()
    fun updateDossier(f: (com.zongkong.core.boss.Dossier) -> com.zongkong.core.boss.Dossier): com.zongkong.core.boss.Dossier = dossier()
    fun hqSessions(): List<com.zongkong.core.hq.HqSession> = emptyList()
    fun ledger(): com.zongkong.core.ledger.LedgerBook = com.zongkong.core.ledger.LedgerBook()
    fun updateLedger(f: (com.zongkong.core.ledger.LedgerBook) -> com.zongkong.core.ledger.LedgerBook): com.zongkong.core.ledger.LedgerBook = ledger()
    /** 今天的日记录（娱乐时长、被弹回、紧急放行）。 */
    fun dayLog(): com.zongkong.core.DayLog = com.zongkong.core.DayLog(today().toString())
    /** [from, to] 的设备事件（含 from 之前最后一条）。 */
    fun deviceEvents(from: Long, to: Long): List<com.zongkong.core.boss.DevEvent> = emptyList()
    /** 现在能看到什么（使用情况授权、截图抽查开没开），写给总控看。 */
    fun sensors(): String = ""
    fun screenshots(from: Long, to: Long): List<com.zongkong.core.boss.Shot> = emptyList()
    /** 让识图模型看一张图，回答 [question]。 */
    suspend fun describeImage(path: String, question: String): String = "这个环境看不了图"

    // ---------- 信息收集部的漏斗 ----------

    /** 正在进行的战役（大统筹、大谋划……）放进系统提示的那一段；没有就空。 */
    fun campaign(): String = ""
    /** 开一场战役（大统筹 / 大谋划 / 整理收集箱）。 */
    fun startCampaign(kind: String, topic: String): String = "这里开不了战役"
    /** 结束战役：记下进度。 */
    fun endCampaign(summary: String): String = "没有进行中的战役"
    /** 电脑那边最后一次的状态（没连电脑 = null）。 */
    fun pcSide(): com.zongkong.core.device.PcSide? = null
    /** 给电脑发命令：lock / unlock / say / block。 */
    fun pcCommand(kind: String, minutes: Int, text: String): String = "没连电脑"
    fun intake(): com.zongkong.core.info.IntakeBook = com.zongkong.core.info.IntakeBook()
    fun updateIntake(f: (com.zongkong.core.info.IntakeBook) -> com.zongkong.core.info.IntakeBook): com.zongkong.core.info.IntakeBook = intake()

    // ---------- 脉络（谋划思考部） ----------

    fun vein(): com.zongkong.core.vein.Vein = com.zongkong.core.vein.Vein()
    fun updateVein(f: (com.zongkong.core.vein.Vein) -> com.zongkong.core.vein.Vein): com.zongkong.core.vein.Vein = vein()
    /** 今天的日期（北京时间自然日）。 */
    fun today(): java.time.LocalDate = java.time.Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()

    /** 让一手现在开一局陪练窗口。 */
    suspend fun startYishouWindow(): String = "这个环境开不了一手的窗口"
    suspend fun sync(): String
    fun memory(): List<String>
    /** 当前这段对话的交接信息（上一段对话压缩成的摘要、今天是第几段），写进系统提示。 */
    fun threadContext(): String = ""
    fun remember(fact: String)
    fun forget(fact: String): Boolean
    /** 应用名 → 包名（拦截名单用）；认不出返回 null。 */
    fun resolveApp(name: String): String?
    fun appLabel(pkg: String): String

    // ---------- 外接谋划单（外部谋划者交进来的东西，总控负责监督而不是替它想） ----------

    fun directives(): List<com.zongkong.core.plan.Directive> = emptyList()
    fun updateDirectives(f: (List<com.zongkong.core.plan.Directive>) -> List<com.zongkong.core.plan.Directive>): List<com.zongkong.core.plan.Directive> = directives()
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
        ToolSpec("today_report", "今天的清单：排了的每一步做了没有（✓ 做完 / ○ 没做 / ✗ 卡住 / → 改期）、另外做了什么、到期的约定、做事和娱乐时长、明天第一步。照着记录列，用户问今天做了什么、哪些没完成、日终时先调这个。"),
        ToolSpec(
            "set_day_script",
            "写今天的预案（试验）：一天排成几段，每段写 B 方案，情况变了换 B 做而不是整段放掉。一行一段，写法：09:00-10:30 资料分析两套 | B: 下雨不去图书馆就在家做一套。" +
                "整份替换。已经定稿的要写 why；已经开始的段保留。用户明确说“定了”才 lock=true（定稿后到点会提醒、速记、要结果）。",
            listOf(
                Param("script", "string", "一行一段", required = true),
                Param("why", "string", "定稿后改要写为什么"),
                Param("lock", "boolean", "用户说定了就 true"),
            ),
        ),
        ToolSpec(
            "script_block",
            "预案里的一段：开始 / 速记 / 做完 / 走B方案 / 跳过。跳过要写原因；走 B 方案用那段写好的 B。",
            listOf(
                Param("id", "string", "段的 id（预案简报里有），或者段的内容", required = true),
                Param("act", "string", "做什么", required = true, choices = listOf("开始", "速记", "做完", "走B方案", "跳过")),
                Param("text", "string", "速记内容 / 结果 / 原因"),
            ),
        ),
        ToolSpec(
            "add_expense",
            "记一笔支出（账本，试验）。他说“午饭 18”、发来付款截图（你看图读出商户和金额）时用。看不清金额就问，不要猜。",
            listOf(
                Param("amount", "string", "金额（元），如 18.5", required = true),
                Param("what", "string", "花在什么上（商户 / 东西）", required = true),
                Param("category", "string", "分类（不填按商户猜）", choices = com.zongkong.core.ledger.Ledger.CATS),
                Param("time", "string", "什么时候（不填 = 现在），如 今天 12:30"),
                Param("source", "string", "来源", choices = listOf("手记", "截图")),
            ),
        ),
        ToolSpec("ledger_summary", "看账本：今天、本周花了多少，按分类，最近几笔（带 id）。"),
        ToolSpec("remove_expense", "删掉记错的一笔。", listOf(Param("id", "string", "那一笔的 id", required = true))),
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
            "send_to_yishou", "把一步行动交给一手陪练：设为一手的当前任务，一手会就这一步一手一手地问你。window = true 时一手马上开一局陪练窗口。",
            listOf(Param("action", "string", "行动键或标题", required = true), Param("window", "boolean", "要不要让一手现在开一局陪练窗口")),
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
        ToolSpec("get_plan", "看本周计划：方向、要交出的东西（带 id）、节奏、预演、今天的主线、计划内外各花了多少时间。"),
        ToolSpec(
            "draft_week_plan",
            "起草一份周计划（计划模式）。只是草稿：他在计划页点“立项”才生效，你不能替他立项。方向最多 3 个，每个方向交出 1–2 样能检查的东西。" +
                "多条内容用换行分隔，每行里用“｜”分字段。",
            listOf(
                Param("week", "string", "哪一周", choices = listOf("本周", "下周")),
                Param("goals", "string", "方向，每行：方向标题｜为什么｜对应的事项编号（可空）", required = true),
                Param("outcomes", "string", "要交出的东西，每行：方向标题｜交出什么｜做到什么算完", required = true),
                Param("daily_minutes", "integer", "每天给计划内的事多少分钟（建议 60–180，宁少勿多）"),
                Param("rest_days", "string", "休息日，例：6,7 或 周日；没有就不填"),
                Param("if_then", "string", "预演，每行：如果……｜就……"),
            ),
        ),
        ToolSpec(
            "set_day_main", "把一步行动定为今天的主线（首页会优先推它）。",
            listOf(Param("action", "string", "行动键或标题", required = true)),
        ),
        ToolSpec(
            "complete_outcome", "把本周计划里的一样东西标成已交出。",
            listOf(Param("outcome", "string", "交出物的 id 或标题", required = true)),
        ),
        ToolSpec(
            "revise_plan", "改生效中的周计划：加或去掉一样要交出的东西。要写为什么（会留痕）。",
            listOf(
                Param("add", "string", "要加的，格式：方向标题｜交出什么｜做到什么算完"),
                Param("drop", "string", "要去掉的交出物 id 或标题"),
                Param("why", "string", "为什么改", required = true),
            ),
        ),
        ToolSpec(
            "start_hq_session", "让他马上进入总控时间：手机只剩总控和白名单应用，按“静→收→谋→筹→行→封”把四个部门修一遍。他明显在逃避、失联、混乱时用。",
            listOf(Param("minutes", "integer", "多长（15–90 分钟，默认按设置）")),
        ),
        ToolSpec(
            "device_timeline", "看一段时间里手机在干什么：亮屏 / 熄屏多久、每个应用多久、娱乐合计、最长连续一段、流水。核实“我在做 X”之类的说法时先调这个。",
            listOf(
                Param("from", "string", "开始：14:00 / 昨天 22:30 / 下午3点 / 3小时前；不填 = 3 小时前"),
                Param("to", "string", "结束，同上；不填 = 现在"),
            ),
        ),
        ToolSpec("app_usage", "某一天各应用用了多久（逻辑日，凌晨 4 点换天）。", listOf(Param("day", "string", "哪天", choices = listOf("今天", "昨天")))),
        ToolSpec(
            "list_screenshots", "列出一段时间里的抽查截图（时间、当时的应用、编号）。截图抽查要用户在设置里开。",
            listOf(Param("from", "string", "开始，写法同 device_timeline；不填 = 3 小时前"), Param("to", "string", "结束；不填 = 现在")),
        ),
        ToolSpec(
            "look_at_screenshot", "让识图模型看一张抽查截图，回答你的问题（例：他在看什么内容？是学习还是娱乐？）。",
            listOf(Param("id", "string", "截图编号", required = true), Param("question", "string", "想知道什么", required = true)),
        ),
        ToolSpec(
            "record_claim", "记下用户的一个说法（要核实的：做了什么、做了多久、为什么没做、要什么豁免）。先记，再取证。",
            listOf(
                Param("text", "string", "原话，或忠实的概括", required = true),
                Param("from", "string", "说的是哪段时间的开始（可空）"),
                Param("to", "string", "结束（可空）"),
                Param("verdict", "string", "已经能判就直接判；否则不填（待查）", choices = ClaimVerdict.entries.filter { it != ClaimVerdict.PENDING }.map { it.label }),
                Param("evidence", "string", "依据"),
            ),
        ),
        ToolSpec(
            "judge_claim", "给一条说法下结论。判“属实”必须写依据；已判的结论只能改得更差，不能改好。",
            listOf(
                Param("id", "string", "陈述编号", required = true),
                Param("verdict", "string", "结论", required = true, choices = ClaimVerdict.entries.filter { it != ClaimVerdict.PENDING }.map { it.label }),
                Param("evidence", "string", "依据：时间线里的数字、截图、照片、产出", required = true),
            ),
        ),
        ToolSpec(
            "commit", "记一条约定：用户答应做什么、几点前、怎么检查。到点后 5 分钟你会被叫醒回访。",
            listOf(
                Param("what", "string", "做什么，具体", required = true),
                Param("due", "string", "几点前：今晚 22:00 / 明天 9:00 / 30分钟后 / 2小时后", required = true),
                Param("check", "string", "怎么检查：看时间线、拍照、看一手组数、看行动记录……", required = true),
            ),
        ),
        ToolSpec(
            "resolve_commitment", "给约定下结论。做到了要写依据；免掉要写理由，七天最多一次，严格态度下不能免。",
            listOf(
                Param("id", "string", "约定编号", required = true),
                Param("status", "string", "结论", required = true, choices = listOf(CommitStatus.KEPT.label, CommitStatus.BROKEN.label, CommitStatus.EXCUSED.label)),
                Param("note", "string", "依据或理由", required = true),
            ),
        ),
        ToolSpec(
            "schedule_check", "给自己排一次回访：到点你会被叫醒，带着这件事去查证据、找用户。用来预判高危时段、追踪说过的话。",
            listOf(
                Param("at", "string", "什么时候：今晚 22:00 / 明天 9:00 / 40分钟后", required = true),
                Param("about", "string", "到时候查什么、问什么", required = true),
            ),
        ),
        ToolSpec(
            "escalate", "把自己的态度收紧一段时间（只能更严）。信用没到但你判断需要时用，要写原因并告诉用户。",
            listOf(
                Param("stance", "string", "收紧到", required = true, choices = listOf(Stance.WARY.label, Stance.STRICT.label)),
                Param("hours", "integer", "多少小时（1–168）", required = true),
                Param("reason", "string", "为什么", required = true),
            ),
        ),
        ToolSpec(
            "vein_search", "在脉络（谋划思考部的知识结构）里搜节点：标题、结论、激活词。用户聊到某个想法、问题、规律时先搜一下有没有现成的。",
            listOf(Param("query", "string", "关键词", required = true)),
        ),
        ToolSpec(
            "vein_node", "看脉络里一个节点的结构：路径、结论、把握、验证方式、回填记录、上下游和对立的邻居。",
            listOf(Param("node", "string", "节点 id（n-0001）或标题", required = true)),
        ),
        ToolSpec(
            "vein_propose", "往脉络里提议一个新节点（进候选，用户确认才写入——结构由用户决定）。推断类必须 status=假设 并写 verify。",
            listOf(
                Param("system", "string", "哪个体系（没有就新起一个名字）", required = true),
                Param("title", "string", "标题，短", required = true),
                Param("claim", "string", "一句结论", required = true),
                Param("type", "string", "类型", choices = com.zongkong.core.vein.NodeType.entries.map { it.label }),
                Param("keywords", "string", "3–5 个激活词，顿号分隔"),
                Param("status", "string", "状态", choices = com.zongkong.core.vein.VStatus.entries.map { it.label }),
                Param("confidence", "integer", "把握 0–100"),
                Param("verify", "string", "验证方式（假设必填）"),
                Param("parent", "string", "层级"),
                Param("relate_to", "string", "和哪个已有节点有关系（id 或标题）"),
                Param("relation", "string", "新节点对那个节点的关系", choices = com.zongkong.core.vein.Rel.TYPES),
            ),
        ),
        ToolSpec(
            "vein_backfill", "给一个假设回填验证结果（用户告诉你验证做了、结果如何时）。会改状态和把握。",
            listOf(
                Param("node", "string", "节点 id 或标题", required = true),
                Param("result", "string", "验证结果，具体（做了几次、看到了什么）", required = true),
                Param("status", "string", "回填后的状态", required = true, choices = com.zongkong.core.vein.VStatus.entries.map { it.label }),
                Param("confidence", "integer", "回填后的把握 0–100"),
            ),
        ),
        ToolSpec("intake_status", "信息收集部的漏斗：今天收了多少、积压多少没提炼、哪些快过期、哪个来源有用率低。列出待提炼的（带记录键）。"),
        ToolSpec(
            "distill", "提炼一条收进来的东西：写一句话收获；可以顺手归位到一件事。用户说“这条的意思是……”或者你帮用户消化收集箱时用。",
            listOf(
                Param("note", "string", "记录键或标题", required = true),
                Param("gist", "string", "一句话收获：它对我有什么用", required = true),
                Param("thread", "string", "归位到哪件事（编号或标题，可空）"),
            ),
        ),
        ToolSpec("calibration", "统筹规划部的数据：估时系数（计划 vs 实际）、精力最好的钟头、同时进行中的步数。排时间之前看一眼。"),
        ToolSpec(
            "backward_plan", "倒推规划：从截止日往回排一串步骤，每天最多一步、留一天余量，时长按估时系数放大，放在精力最好的钟头。会直接建行动。",
            listOf(
                Param("thread", "string", "哪件事（编号或标题）", required = true),
                Param("deadline", "string", "截止日：11-30 / 下周五 / 2026-12-01", required = true),
                Param("steps", "string", "步骤，每行：标题｜估计分钟（按先后顺序）", required = true),
            ),
        ),
        ToolSpec(
            "set_aim", "挂一个长期目标（求职、考试这类跨好几周、周计划装不下的事）或改它。首页上方会挂一弯月亮显示时间走了多少；关联事项后能看出这周有没有一步落在它上面。最多 ${com.zongkong.core.plan.Aims.MAX} 个。",
            listOf(
                Param("id", "string", "改哪一个（空 = 新建）"),
                Param("title", "string", "目标，短：秋招拿到 offer / 12 月考过 CPA 会计"),
                Param("why", "string", "为什么要它（一句）"),
                Param("deadline", "string", "截止日：12-20 / 2027-03-01；没有期限就空着"),
                Param("threads", "string", "关联的事项（编号或标题，逗号分隔）"),
                Param("done", "boolean", "true = 达成或放下，从首页摘掉"),
            ),
        ),
        ToolSpec(
            "set_milestones", "给一个长期目标写里程碑：从截止日往回倒推 3–5 个能检查的产出和日期（整组替换，做完的保留）。大统筹里用。",
            listOf(
                Param("aim", "string", "目标 id 或名字", required = true),
                Param("items", "string", "每行：做出什么｜日期（11-20 / 2026-12-01）", required = true),
            ),
        ),
        ToolSpec(
            "milestone_done", "把一个里程碑标成做完了（要有证据：交出的东西、记录）。",
            listOf(Param("aim", "string", "目标 id 或名字", required = true), Param("milestone", "string", "里程碑 id 或名字", required = true), Param("evidence", "string", "依据", required = true)),
        ),
        ToolSpec(
            "start_campaign", "开一场战役：大统筹（从长期目标倒推到这周的日程）、大谋划（把一个问题想透写进脉络）、整理收集箱、立项（把一件很薄的新事展开）、消化讨论（把外面聊过的提炼归位）。他同意了再开；开了以后按阶段一段一段推进。",
            listOf(
                Param("kind", "string", "哪一种", required = true, choices = com.zongkong.core.agent.Campaign.entries.map { it.title }),
                Param("topic", "string", "题目（大谋划要想的问题；可空）"),
            ),
        ),
        ToolSpec("campaign_done", "结束正在进行的战役，记下做到了哪一步。", listOf(Param("summary", "string", "三到五行：做成了什么、还差什么", required = true))),
        ToolSpec(
            "pc", "电脑：看电脑今天用了多久、在做什么（状态），或者给电脑下命令：锁机 N 分钟、解锁（只解你锁的）、说一句（电脑上念出来、弹出来）、拦一个网站（今天剩下的时间都打不开）。",
            listOf(
                Param("action", "string", "做什么", required = true, choices = listOf("状态", "锁机", "解锁", "说一句", "拦网站")),
                Param("minutes", "integer", "锁机几分钟（1–600）"),
                Param("text", "string", "说什么 / 锁机原因 / 网站名或域名"),
            ),
        ),
        ToolSpec("vein_due", "脉络里该回头看的：等验证的假设（按多久没回填排）、今天该温故的、候选卡片数、体检分。主动提问时用。"),
        ToolSpec("get_dossier", "看档案：信用分的每一笔、态度、全部未结约定、最近的陈述和判定、排了的回访。"),
        ToolSpec("sync_notion", "现在和 Notion 同步一次（拉 GPT 写进去的、推总控改过的）。"),
        ToolSpec(
            "remember", "记住一条关于他的长期事实或偏好（例：晚上 10 点后效率低；周三下午有课），以后每次对话都会带上。",
            listOf(Param("fact", "string", "一句话", required = true)),
        ),
        ToolSpec("forget", "删掉一条记住的事。", listOf(Param("fact", "string", "要删的那句（原样或包含的关键词）", required = true))),
        ToolSpec(
            "directive_add",
            "收下一份外接的谋划（用户在 Gemini / GPT / Notion 里聊出来的报告、计划、问题清单）。你负责把它拆成能监督的条目，" +
                "不替用户重写专业内容、不自己另做一份谋划。title 一句话；text 原文尽量完整；items 每行一条：类型｜一句话｜时间说法（类型可省）。",
            listOf(
                Param("title", "string", "这份谋划叫什么", required = true),
                Param("text", "string", "原文（长文整段贴）", required = true),
                Param("source", "string", "从哪来：Gemini / GPT / Notion / 粘贴"),
                Param("summary", "string", "一句话结论：这份文档要用户做到什么"),
                Param("items", "string", "每行：类型｜一句话｜时间说法（后两个可省；类型：要做/风险/待决/事实）"),
            ),
        ),
        ToolSpec("directive_list", "列出所有外接谋划单：标题、来源、几条已落实、几条没落实。"),
        ToolSpec(
            "directive_update",
            "改一份外接谋划单：加条目、勾掉条目、改结论、收掉或归档。",
            listOf(
                Param("directive", "string", "id 或标题", required = true),
                Param("act", "string", "做什么", required = true, choices = listOf("加条目", "勾掉", "取消勾掉", "改结论", "收掉", "归档")),
                Param("item", "string", "条目 id 或条目里的一句话（勾掉 / 取消勾掉用）"),
                Param("text", "string", "新条目（一行：类型｜一句话｜时间）或新结论"),
            ),
        ),
        ToolSpec(
            "directive_check",
            "对账：看一份谋划单（不填就看全部）里哪些说要做、现在到哪了。盯落实时先调这个，再去查事项/行动的证据。",
            listOf(Param("directive", "string", "id 或标题；不填看全部")),
        ),
    )

    suspend fun run(name: String, argsJson: String): ToolResult {
        val a = Args.parse(argsJson) ?: return ToolResult.fail("参数不是 JSON：${argsJson.take(80)}")
        return try {
            when (name) {
                "get_overview" -> ToolResult(Snapshot.full(env), "看了一眼全局")
                "list_threads" -> listThreads(a)
                "get_thread" -> getThread(a)
                "today_report" -> ToolResult(
                    com.zongkong.core.plan.DayReport.text(
                        com.zongkong.core.plan.DayReport.build(env.work(), env.plans(), env.dayLog(), env.hqSessions(), env.dossier(), env.pcSide(), env.now(), env.zone, env.ledger()),
                    ),
                    "列了今天的清单",
                )
                "add_expense" -> addExpense(a)
                "ledger_summary" -> ToolResult(com.zongkong.core.ledger.Ledger.brief(env.ledger(), env.now(), env.zone), "看了账本")
                "remove_expense" -> {
                    val id = a.s("id")
                    val e = env.ledger().items.firstOrNull { it.id == id } ?: return ToolResult.fail("账本里没有 $id")
                    env.updateLedger { com.zongkong.core.ledger.Ledger.remove(it, id) }
                    ToolResult("删了：${e.what} ${com.zongkong.core.ledger.Ledger.yuan(e.cents)} 元", "删一笔账")
                }
                "set_day_script" -> setDayScript(a)
                "script_block" -> scriptBlock(a)
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
                "get_plan" -> getPlan()
                "draft_week_plan" -> draftPlan(a)
                "set_day_main" -> setDayMain(a)
                "complete_outcome" -> completeOutcome(a)
                "revise_plan" -> revisePlan(a)
                "start_hq_session" -> env.startHq(a.i("minutes") ?: env.config().hq.minutes).let { ToolResult(it, "进入总控时间", route = "hq") }
                "device_timeline" -> deviceTimeline(a)
                "app_usage" -> appUsage(a)
                "list_screenshots" -> listShots(a)
                "look_at_screenshot" -> lookShot(a)
                "record_claim" -> recordClaim(a)
                "judge_claim" -> judgeClaim(a)
                "commit" -> commit(a)
                "resolve_commitment" -> resolveCommitment(a)
                "schedule_check" -> scheduleCheck(a)
                "escalate" -> escalate(a)
                "get_dossier" -> ToolResult(dossierText(), "看了档案")
                "intake_status" -> intakeStatus()
                "distill" -> distill(a)
                "calibration" -> ToolResult(com.zongkong.core.plan.Calibrate.brief(env.work(), env.zone, env.now()), "看了估时和精力地图")
                "backward_plan" -> backwardPlan(a)
                "set_aim" -> setAim(a)
                "pc" -> pc(a)
                "set_milestones" -> setMilestones(a)
                "milestone_done" -> milestoneDone(a)
                "start_campaign" -> ToolResult(env.startCampaign(a.s("kind"), a.s("topic")), "开始${a.s("kind")}")
                "campaign_done" -> a.s("summary").ifBlank { return ToolResult.fail("summary 不能空") }.let { ToolResult(env.endCampaign(it), "战役收尾") }
                "vein_search" -> veinSearch(a)
                "vein_node" -> veinNode(a)
                "vein_propose" -> veinPropose(a)
                "vein_backfill" -> veinBackfill(a)
                "vein_due" -> ToolResult(veinDue(), "看了脉络里该回头看的")
                "sync_notion" -> env.sync().let { ToolResult(it, it) }
                "remember" -> a.s("fact").takeIf { it.isNotBlank() }?.let { env.remember(it); ToolResult("记住了：$it", "记住：$it") } ?: ToolResult.fail("fact 不能空")
                "forget" -> if (env.forget(a.s("fact"))) ToolResult("删掉了", "不再记：${a.s("fact")}") else ToolResult.fail("没找到这条")
                "directive_add" -> directiveAdd(a)
                "directive_list" -> directiveList()
                "directive_update" -> directiveUpdate(a)
                "directive_check" -> directiveCheck(a)
                else -> ToolResult.fail("没有这个工具：$name")
            }
        } catch (e: Exception) {
            ToolResult.fail("${name} 出错：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 外接谋划单：收下、拆条目、盯落实 ----------

    private fun dirId() = "D" + (env.now() / 1000).toString(36).takeLast(4) + env.rnd.nextInt(36 * 36).toString(36).padStart(2, '0')

    private fun itemId(seq: Int) = "i$seq" + env.rnd.nextInt(36 * 36).toString(36).padStart(2, '0')

    /** 解析"类型｜一句话｜时间"行；类型认不出就把第一列当内容。 */
    private fun parseDirectiveItems(raw: String): List<com.zongkong.core.plan.DirectiveItem> =
        raw.lines().map { it.trim() }.filter { it.isNotBlank() }.take(60).mapIndexed { idx, line ->
            val parts = line.split('｜', '|').map { p -> p.trim() }
            val kind = when (parts.getOrNull(0).orEmpty()) {
                "风险", "risk" -> com.zongkong.core.plan.DirectiveItem.KIND_RISK
                "待决", "question" -> com.zongkong.core.plan.DirectiveItem.KIND_QUESTION
                "事实", "fact" -> com.zongkong.core.plan.DirectiveItem.KIND_FACT
                "要做", "task" -> com.zongkong.core.plan.DirectiveItem.KIND_TASK
                else -> null
            }
            val body: String
            val due: String
            if (kind == null) {
                body = parts.getOrNull(0).orEmpty()
                due = parts.getOrNull(1).orEmpty()
            } else {
                body = parts.getOrNull(1).orEmpty()
                due = parts.getOrNull(2).orEmpty()
            }
            com.zongkong.core.plan.DirectiveItem(
                id = itemId(idx),
                text = body.take(200),
                kind = kind ?: com.zongkong.core.plan.DirectiveItem.KIND_TASK,
                due = due.take(40),
            )
        }.filter { it.text.isNotBlank() }

    private fun directiveAdd(a: Args): ToolResult {
        val title = a.s("title").take(60).ifBlank { return ToolResult.fail("title 不能空") }
        val text = a.s("text")
        if (text.isBlank()) return ToolResult.fail("text 不能空（把外部文档整段贴进来）")
        val now = env.now()
        val d = com.zongkong.core.plan.Directive(
            id = dirId(),
            title = title,
            source = a.s("source").take(20).ifBlank { "粘贴" },
            at = now,
            raw = text.take(20000),
            summary = a.s("summary").take(300),
            items = parseDirectiveItems(a.s("items")),
        )
        val list = env.updateDirectives { (it + d).takeLast(30) }
        val active = list.count { it.status == com.zongkong.core.plan.Directive.STATUS_ACTIVE }
        return ToolResult(
            "收下了「${d.title}」，拆成 ${d.items.size} 条。现在共 $active 份在办的外接谋划。",
            "收下外接谋划：${d.title}",
        )
    }

    private fun directiveList(): ToolResult {
        val list = env.directives()
        if (list.isEmpty()) return ToolResult("还没有外接谋划单。用户在 Gemini / GPT 里聊完后，把整段贴进来，就能在这里盯落实。", "没有外接谋划")
        val sb = StringBuilder()
        list.forEach { d ->
            sb.append("· ").append(d.title).append(" [").append(d.id).append("]（").append(d.source).append("） ")
            sb.append(d.doneCount).append("/").append(d.items.size).append(" 条落实")
            if (d.status != com.zongkong.core.plan.Directive.STATUS_ACTIVE) sb.append(" · ").append(d.status)
            sb.append('\n')
            d.open.take(5).forEach { i ->
                sb.append("   - [").append(com.zongkong.core.plan.DirectiveItem.kindLabel(i.kind)).append("] ").append(i.text)
                if (i.due.isNotBlank()) sb.append("（").append(i.due).append("）")
                sb.append('\n')
            }
        }
        return ToolResult(sb.toString().trim(), "看了外接谋划（${list.size} 份）")
    }

    private fun directiveUpdate(a: Args): ToolResult {
        val key = a.s("directive")
        val d = com.zongkong.core.plan.Directives.find(env.directives(), key) ?: return ToolResult.fail("没有这份谋划：$key")
        val act = a.s("act")
        val item = a.s("item")
        val text = a.s("text")
        when (act) {
            "加条目" -> {
                val items = parseDirectiveItems(text)
                if (items.isEmpty()) return ToolResult.fail("text 里没有条目（一行一条：类型｜一句话）")
                env.updateDirectives { list -> list.map { if (it.id == d.id) it.copy(items = it.items + items) else it } }
                return ToolResult("加了 ${items.size} 条到「${d.title}」。", "谋划加条目")
            }
            "勾掉", "取消勾掉" -> {
                val hit = d.items.firstOrNull { it.id == item } ?: d.items.firstOrNull { item.isNotBlank() && it.text.contains(item) }
                    ?: return ToolResult.fail("没找到这条：$item")
                val done = act == "勾掉"
                env.updateDirectives { list -> list.map { x -> if (x.id == d.id) x.copy(items = x.items.map { if (it.id == hit.id) it.copy(done = done) else it }) else x } }
                return ToolResult("「${hit.text}」${if (done) "勾掉了" else "恢复成没做"}。", if (done) "谋划落实一条" else "谋划恢复一条")
            }
            "改结论" -> {
                if (text.isBlank()) return ToolResult.fail("text 不能空")
                env.updateDirectives { list -> list.map { if (it.id == d.id) it.copy(summary = text.take(300)) else it } }
                return ToolResult("改了「${d.title}」的结论。", "改谋划结论")
            }
            "收掉" -> {
                env.updateDirectives { list -> list.map { if (it.id == d.id) it.copy(status = com.zongkong.core.plan.Directive.STATUS_DONE) else it } }
                return ToolResult("「${d.title}」收掉了（记成已完成）。", "收掉谋划")
            }
            "归档" -> {
                env.updateDirectives { list -> list.map { if (it.id == d.id) it.copy(status = com.zongkong.core.plan.Directive.STATUS_ARCHIVED) else it } }
                return ToolResult("「${d.title}」归档了。", "归档谋划")
            }
            else -> return ToolResult.fail("不认识的动作用：加条目 / 勾掉 / 取消勾掉 / 改结论 / 收掉 / 归档")
        }
    }

    private fun directiveCheck(a: Args): ToolResult {
        val key = a.s("directive")
        val list = if (key.isBlank()) env.directives() else listOfNotNull(com.zongkong.core.plan.Directives.find(env.directives(), key))
        if (list.isEmpty()) return ToolResult("没有这份谋划：$key", "没找到谋划")
        val sb = StringBuilder()
        list.forEach { d ->
            sb.append("「").append(d.title).append("」（").append(d.source).append("）\n")
            if (d.summary.isNotBlank()) sb.append("结论：").append(d.summary).append('\n')
            if (d.items.isEmpty()) sb.append("（还没拆条目：让总控把原文拆成一条条能检查的事）\n")
            d.items.forEach { i ->
                sb.append(if (i.done) "✓ " else "○ ")
                sb.append("[").append(com.zongkong.core.plan.DirectiveItem.kindLabel(i.kind)).append("] ").append(i.text)
                if (i.due.isNotBlank()) sb.append("（").append(i.due).append("）")
                if (i.threadKey.isNotBlank()) sb.append(" → 事项 ").append(i.threadKey)
                if (i.actionKey.isNotBlank()) sb.append(" → 行动 ").append(i.actionKey)
                sb.append('\n')
            }
            sb.append('\n')
        }
        return ToolResult(sb.toString().trim(), "对了外接谋划的账")
    }

    // ---------- 总控者 ----------

    private fun newId(prefix: String) = prefix + (env.now() / 1000).toString(36).takeLast(4) + env.rnd.nextInt(36 * 36).toString(36).padStart(2, '0')

    /** 读一段时间：from / to 都可空。 */
    private fun range(a: Args, defaultHours: Int = 3): Pair<Long, Long>? {
        val now = env.now()
        val to = a.opt("to")?.let { Boss.clock(it, now, env.zone) ?: return null } ?: now
        val from = a.opt("from")?.let { Boss.clock(it, now, env.zone) ?: return null } ?: (to - defaultHours * 3600_000L)
        return if (from < to) from to minOf(to, now) else null
    }

    private fun timelineText(from: Long, to: Long): String {
        val cfg = env.config()
        val segs = Timeline.segments(env.deviceEvents(from, to), from, to)
        return Timeline.summarize(segs, from, to, env.zone, { env.appLabel(it) }, { it in cfg.blocked })
    }

    private fun deviceTimeline(a: Args): ToolResult {
        val (from, to) = range(a) ?: return ToolResult.fail("时间写法认不出：from=${a.s("from")} to=${a.s("to")}（例：14:00、昨天 22:30、3小时前）")
        if (to - from > 3 * 86_400_000L) return ToolResult.fail("一次最多看 3 天")
        return ToolResult(timelineText(from, to), "看了 ${Timeline.hm(from, env.zone)}–${Timeline.hm(to, env.zone)} 的手机记录")
    }

    private fun appUsage(a: Args): ToolResult {
        val now = env.now()
        val date = DayClock.date(now, env.zone).minusDays(if (a.s("day") == "昨天") 1 else 0)
        val from = DayClock.startOf(date, env.zone)
        val to = minOf(now, from + 86_400_000L)
        return ToolResult(timelineText(from, to), "看了${a.s("day").ifBlank { "今天" }}的应用时长")
    }

    private fun listShots(a: Args): ToolResult {
        val (from, to) = range(a) ?: return ToolResult.fail("时间写法认不出")
        val list = env.screenshots(from, to)
        if (list.isEmpty()) return ToolResult("这段时间没有抽查截图（截图抽查${if (env.config().boss.shots) "开着，但这段时间没截到" else "没开"}）", "没有截图")
        return ToolResult(list.takeLast(30).joinToString("\n") { "${it.id}｜${Timeline.hm(it.at, env.zone)}｜${env.appLabel(it.pkg)}" }, "列了 ${list.size} 张截图")
    }

    private suspend fun lookShot(a: Args): ToolResult {
        val id = a.s("id")
        val shot = env.screenshots(0, env.now()).firstOrNull { it.id == id } ?: return ToolResult.fail("没有这张截图：$id")
        val text = env.describeImage(shot.path, a.s("question").ifBlank { "这个人在手机上做什么？" })
        return ToolResult("截图 $id（${Timeline.hm(shot.at, env.zone)}，${env.appLabel(shot.pkg)}）：$text", "看了一张截图")
    }

    private fun verdictOf(label: String) = ClaimVerdict.entries.firstOrNull { it.label == label.trim() }

    private fun recordClaim(a: Args): ToolResult {
        val text = a.s("text").ifBlank { return ToolResult.fail("text 不能空") }
        val now = env.now()
        val from = a.opt("from")?.let { Boss.clock(it, now, env.zone) } ?: 0L
        val to = a.opt("to")?.let { Boss.clock(it, now, env.zone) } ?: if (from > 0) now else 0L
        val verdict = a.opt("verdict")?.let { verdictOf(it) ?: return ToolResult.fail("没有这种结论：$it") } ?: ClaimVerdict.PENDING
        if (verdict == ClaimVerdict.VERIFIED && a.s("evidence").length < 6) return ToolResult.fail("判“属实”要写依据")
        val id = newId("s")
        env.updateDossier { Boss.addClaim(it, id, text, from, to, verdict, a.s("evidence"), now) }
        return ToolResult("记下陈述 $id（${verdict.label}）" + if (from > 0) "，时间段 ${Timeline.hm(from, env.zone)}–${Timeline.hm(to, env.zone)}" else "", "记下说法：${text.take(20)}（${verdict.label}）")
    }

    private fun judgeClaim(a: Args): ToolResult {
        val v = verdictOf(a.s("verdict")) ?: return ToolResult.fail("没有这种结论：${a.s("verdict")}")
        var err: String? = null
        env.updateDossier { d -> Boss.judge(d, a.s("id"), v, a.s("evidence"), env.now()).getOrElse { e -> err = e.message; d } }
        err?.let { return ToolResult.fail(it) }
        val d = env.dossier()
        return ToolResult("判定：${v.label}。信用现在 ${Boss.credibility(d, env.hqSessions(), env.now(), env.work().events)}，态度 ${Boss.stance(d, env.hqSessions(), env.now(), env.work().events).label}", "判定说法：${v.label}")
    }

    /** 约定、回访的时间：“30分钟后”“2小时后”或者中文时间。 */
    private fun dueOf(text: String): Long? {
        val now = env.now()
        Regex("""(\d+(?:\.\d+)?)\s*(小时|分钟)后""").find(text)?.let { m ->
            val n = m.groupValues[1].toDouble()
            return now + (if (m.groupValues[2] == "小时") n * 3600_000 else n * 60_000).toLong()
        }
        val plan = TimeParse.parse(text, now, env.zone) ?: return null
        // “今晚 22:00”就是 22:00；“20:00-21:00”取结束；只有日期取那天结束
        return if (plan.end.contains('T') || !plan.hasTime) TimeParse.endMillis(plan, env.zone) else TimeParse.startMillis(plan, env.zone)
    }

    private fun commit(a: Args): ToolResult {
        val what = a.s("what").ifBlank { return ToolResult.fail("what 不能空") }
        val due = dueOf(a.s("due")) ?: return ToolResult.fail("时间认不出：${a.s("due")}")
        if (due <= env.now()) return ToolResult.fail("截止时间已经过了")
        val id = newId("y")
        env.updateDossier { Boss.commit(it, id, what, due, a.s("check"), env.now()) }
        return ToolResult("记下约定 $id：$what，${Timeline.hm(due, env.zone)} 前；到点后 5 分钟回访", "约定：$what（${Timeline.hm(due, env.zone)} 前）")
    }

    private fun resolveCommitment(a: Args): ToolResult {
        val st = CommitStatus.entries.firstOrNull { it.label == a.s("status") } ?: return ToolResult.fail("没有这种结论：${a.s("status")}")
        val now = env.now()
        val stance = Boss.stance(env.dossier(), env.hqSessions(), now, env.work().events)
        var err: String? = null
        env.updateDossier { d -> Boss.resolve(d, a.s("id"), st, a.s("note"), stance, now).getOrElse { e -> err = e.message; d } }
        err?.let { return ToolResult.fail(it) }
        return ToolResult("约定 ${a.s("id")}：${st.label}。信用现在 ${Boss.credibility(env.dossier(), env.hqSessions(), now, env.work().events)}", "约定${st.label}")
    }

    private fun scheduleCheck(a: Args): ToolResult {
        val due = dueOf(a.s("at")) ?: return ToolResult.fail("时间认不出：${a.s("at")}")
        if (due <= env.now()) return ToolResult.fail("时间已经过了")
        val about = a.s("about").ifBlank { return ToolResult.fail("about 不能空") }
        env.updateDossier { Boss.schedule(it, newId("k"), due, about, env.now()) }
        return ToolResult("排了回访：${Timeline.hm(due, env.zone)}，$about", "排回访 ${Timeline.hm(due, env.zone)}")
    }

    private fun escalate(a: Args): ToolResult {
        val to = Stance.entries.firstOrNull { it.label == a.s("stance") } ?: return ToolResult.fail("没有这种态度：${a.s("stance")}")
        val now = env.now()
        val cur = Boss.stance(env.dossier(), env.hqSessions(), now, env.work().events)
        var err: String? = null
        env.updateDossier { d -> Boss.escalate(d, to, a.i("hours") ?: 24, a.s("reason"), cur, now).getOrElse { e -> err = e.message; d } }
        err?.let { return ToolResult.fail(it) }
        return ToolResult("态度收紧到“${to.label}”，${a.i("hours") ?: 24} 小时", "态度收紧：${to.label}")
    }

    private fun dossierText(): String {
        val d = env.dossier()
        val now = env.now()
        val sessions = env.hqSessions()
        return buildString {
            append(Boss.brief(d, sessions, now, env.zone, env.work().events))
            val items = Boss.ledger(d, sessions, now, env.work().events)
            if (items.isNotEmpty()) {
                append("信用明细（起点 ${Boss.BASE}，近 ${Boss.WINDOW_DAYS} 天）：\n")
                items.takeLast(20).forEach { append("· ${if (it.delta > 0) "+" else ""}${it.delta} ${it.why}\n") }
            }
            val closed = d.commitments.filter { it.status != CommitStatus.OPEN }.takeLast(8)
            if (closed.isNotEmpty()) {
                append("最近结了的约定：\n")
                closed.forEach { append("· [${it.id}] ${it.what}｜${it.status.label}｜${it.note.take(60)}\n") }
            }
        }.trimEnd()
    }

    // ---------- 信息收集部、统筹规划部 ----------

    private fun intakeStatus(): ToolResult {
        val w = env.work()
        val book = env.intake()
        val now = env.now()
        val items = com.zongkong.core.info.Intake.items(w, book, now).filter { it.state == com.zongkong.core.info.IntakeState.NEW }
        val text = buildString {
            append(com.zongkong.core.info.Intake.brief(w, book, now, env.zone))
            if (items.isNotEmpty()) {
                append("\n待提炼：\n")
                items.sortedBy { it.daysLeft }.take(10).forEach { append("· ${it.rec.key}｜${it.rec.noteType.label}｜${it.rec.title}｜${it.source}｜${if (it.daysLeft <= 0) "今天过期" else "还剩 ${it.daysLeft} 天"}\n") }
            }
        }.trimEnd()
        return ToolResult(text, "看了信息收集部的漏斗")
    }

    private fun distill(a: Args): ToolResult {
        val ref = a.s("note")
        val w = env.work()
        val n = w.rec(ref)?.takeIf { it.kind == Kind.NOTE } ?: w.notes().firstOrNull { it.title == ref } ?: w.notes().filter { it.title.contains(ref) && ref.isNotBlank() }.maxByOrNull { it.updatedAt }
            ?: return ToolResult.fail("没找到记录“$ref”。先 intake_status 看记录键")
        val gist = a.s("gist").ifBlank { return ToolResult.fail("gist 不能空") }
        env.updateIntake { com.zongkong.core.info.Intake.distill(it, n.key, gist, env.now()) }
        val t = a.opt("thread")?.let { findThread(it) ?: return ToolResult.fail("没找到事项“$it”") }
        if (t != null) {
            env.updateWork { WorkOps.update(it, n.key, mapOf(F.THREAD to t.key), env.now()) }
            env.updateIntake { com.zongkong.core.info.Intake.place(it, n.key, "thread:${t.key}", env.now()) }
            env.kick()
        }
        return ToolResult("提炼了「${n.title}」：$gist" + (t?.let { "，归位到 ${it[F.CODE]}「${it.title}」" } ?: ""), "提炼「${n.title.take(12)}」")
    }

    private fun findAim(ref: String): com.zongkong.core.plan.Aim? {
        val r = ref.trim()
        val open = com.zongkong.core.plan.Aims.open(env.plans())
        return open.firstOrNull { it.id == r } ?: open.firstOrNull { it.title == r } ?: open.firstOrNull { it.title.contains(r) || r.contains(it.title) }
    }

    private fun setMilestones(a: Args): ToolResult {
        val aim = findAim(a.s("aim")) ?: return ToolResult.fail("没有这个长期目标：${a.s("aim")}（先用 set_aim 挂上）")
        val now = env.now()
        val items = lines(a.s("items")).map { l ->
            val f = fields(l)
            val d = f.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { raw ->
                val p = TimeParse.parse(raw, now, env.zone) ?: return ToolResult.fail("日期认不出：$raw")
                java.time.Instant.ofEpochMilli(TimeParse.startMillis(p, env.zone) ?: return ToolResult.fail("日期认不出：$raw")).atZone(env.zone).toLocalDate()
            }
            f[0] to d
        }.filter { it.first.isNotBlank() }
        var err: String? = null
        val book = env.updatePlans { b -> com.zongkong.core.plan.Aims.setMilestones(b, aim.id, items, now).getOrElse { e -> err = e.message; b } }
        err?.let { return ToolResult.fail(it) }
        val ms = book.aims.first { it.id == aim.id }.milestones
        return ToolResult("「${aim.title}」的里程碑：\n" + ms.joinToString("\n") { "· [${it.id}] ${it.title}｜${it.due.ifBlank { "没日期" }}${if (it.done) "｜已做完" else ""}" }, "写了 ${items.size} 个里程碑：${aim.title}")
    }

    private fun milestoneDone(a: Args): ToolResult {
        val aim = findAim(a.s("aim")) ?: return ToolResult.fail("没有这个长期目标：${a.s("aim")}")
        if (a.s("evidence").length < 4) return ToolResult.fail("要写依据")
        var err: String? = null
        env.updatePlans { b -> com.zongkong.core.plan.Aims.milestoneDone(b, aim.id, a.s("milestone"), env.now()).getOrElse { e -> err = e.message; b } }
        err?.let { return ToolResult.fail(it) }
        return ToolResult("里程碑做完：${a.s("milestone")}（${a.s("evidence")}）", "里程碑完成：${a.s("milestone").take(16)}")
    }

    private fun pc(a: Args): ToolResult {
        val now = env.now()
        return when (a.s("action")) {
            "状态" -> env.pcSide()?.let { ToolResult(com.zongkong.core.device.DeviceLink.brief(it, now, env.zone), "看了电脑") } ?: ToolResult.fail("还没连电脑（我 → 电脑）")
            "锁机" -> {
                val m = (a.i("minutes") ?: 30).coerceIn(1, 600)
                ToolResult(env.pcCommand("lock", m, a.s("text").ifBlank { "手机上的总控锁的" }), "锁电脑 $m 分钟")
            }
            "解锁" -> ToolResult(env.pcCommand("unlock", 0, ""), "解开电脑")
            "说一句" -> a.s("text").ifBlank { return ToolResult.fail("text 不能空") }.let { ToolResult(env.pcCommand("say", 0, it), "对电脑说：${it.take(20)}") }
            "拦网站" -> a.s("text").ifBlank { return ToolResult.fail("text：网站名或域名") }.let { ToolResult(env.pcCommand("block", 0, it), "电脑拦 $it") }
            else -> ToolResult.fail("action 只能是 状态 / 锁机 / 解锁 / 说一句 / 拦网站")
        }
    }

    private fun setAim(a: Args): ToolResult {
        val now = env.now()
        val id = a.s("id")
        if (a.b("done") == true) {
            if (id.isBlank()) return ToolResult.fail("要写摘掉哪一个（id）")
            var err: String? = null
            env.updatePlans { b -> com.zongkong.core.plan.Aims.close(b, id, now).getOrElse { e -> err = e.message; b } }
            return err?.let { ToolResult.fail(it) } ?: ToolResult("摘掉了长期目标 $id", "放下长期目标")
        }
        val dl = a.s("deadline").takeIf { it.isNotBlank() }?.let { raw ->
            val p = TimeParse.parse(raw, now, env.zone) ?: return ToolResult.fail("截止日认不出：$raw")
            val ms = TimeParse.startMillis(p, env.zone) ?: return ToolResult.fail("截止日认不出：$raw")
            java.time.Instant.ofEpochMilli(ms).atZone(env.zone).toLocalDate()
        }
        val threads = a.s("threads").takeIf { it.isNotBlank() }?.split(',', '，', '、')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { ref ->
            (findThread(ref) ?: return noThread(ref)).key
        }
        var err: String? = null
        val book = env.updatePlans { b ->
            com.zongkong.core.plan.Aims.set(b, id, a.s("title"), a.s("why"), dl, threads, now) { newId("aim") }.getOrElse { e -> err = e.message; b }
        }
        err?.let { return ToolResult.fail(it) }
        val aim = (if (id.isBlank()) book.aims.lastOrNull() else book.aims.firstOrNull { it.id == id }) ?: return ToolResult.fail("没找到")
        val left = com.zongkong.core.plan.Aims.daysLeft(aim, now, env.zone)
        return ToolResult(
            "长期目标 [${aim.id}] ${aim.title}｜${left?.let { "还剩 $it 天" } ?: "没有期限"}｜关联 ${aim.threads.size} 件事项。已挂在首页上方。",
            if (id.isBlank()) "挂上长期目标：${aim.title}" else "改了长期目标：${aim.title}",
        )
    }

    private fun backwardPlan(a: Args): ToolResult {
        val t = findThread(a.s("thread")) ?: return noThread(a.s("thread"))
        val now = env.now()
        val plan = TimeParse.parse(a.s("deadline"), now, env.zone) ?: return ToolResult.fail("截止日认不出：${a.s("deadline")}")
        val deadline = (TimeParse.startMillis(plan, env.zone) ?: return ToolResult.fail("截止日认不出")).let { java.time.Instant.ofEpochMilli(it).atZone(env.zone).toLocalDate() }
        val today = env.today()
        if (!deadline.isAfter(today)) return ToolResult.fail("截止日要在今天之后")
        val steps = a.s("steps").lines().map { it.trim().trimStart('-', '·', ' ') }.filter { it.isNotBlank() }.map { line ->
            val parts = line.split('｜', '|').map { it.trim() }
            parts[0] to (parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 45)
        }.take(12)
        if (steps.isEmpty()) return ToolResult.fail("steps 至少一行：标题｜分钟")
        val samples = com.zongkong.core.plan.Calibrate.samples(env.work(), env.zone)
        val factor = com.zongkong.core.plan.Calibrate.factor(samples)
        val best = com.zongkong.core.plan.Calibrate.bestHours(com.zongkong.core.plan.Calibrate.energy(env.work(), env.zone, now), 1).firstOrNull()
        val (slots, crowded) = com.zongkong.core.plan.Calibrate.backward(steps, deadline, today, env.zone, factor, best)
        env.updateWork { w0 ->
            var w = w0
            slots.forEach { s -> w = WorkOps.create(w, Kind.ACTION, mapOf(F.TITLE to s.title, F.THREAD to t.key, F.PLAN to s.plan.encode()), now, env.rnd).first }
            w
        }
        env.kick()
        val lines = slots.joinToString("\n") { "· ${TimeParse.label(it.plan, now, env.zone)}｜${it.title}（${it.minutes} 分钟）" }
        return ToolResult(
            "倒推排了 ${slots.size} 步（${if (factor != null) "估时 ×%.1f".format(factor) else "估时样本不够，按原估计"}，${best?.let { "$it 点" } ?: "晚上 20 点"}）：\n$lines" +
                if (crowded) "\n注意：天数不够，一天一步也排到了截止前一天——要么砍步骤，要么推截止日。" else "",
            "倒推排了 ${slots.size} 步",
        )
    }

    // ---------- 脉络 ----------

    private fun findNode(ref: String): com.zongkong.core.vein.VNode? {
        val v = env.vein()
        return v.node(ref.trim()) ?: v.byTitle(ref)
    }

    private fun veinSearch(a: Args): ToolResult {
        val hits = com.zongkong.core.vein.Veins.search(env.vein(), a.s("query"))
        if (hits.isEmpty()) return ToolResult("脉络里没有相关节点", "脉络里没搜到")
        return ToolResult(hits.take(10).joinToString("\n") { "${it.id}｜${it.system} › ${it.parent.ifBlank { "-" }}｜${it.title}（${it.type}·${it.status}${it.confidence?.let { c -> "·$c%" } ?: ""}）：${it.claim}" }, "脉络里搜到 ${hits.size} 个")
    }

    private fun veinNode(a: Args): ToolResult {
        val n = findNode(a.s("node")) ?: return ToolResult.fail("脉络里没有“${a.s("node")}”")
        val v = env.vein()
        val text = buildString {
            append(com.zongkong.core.vein.VeinAi.pack(v, n.id, "（只看结构）", budget = 2500)?.text.orEmpty())
            if (n.checks.isNotEmpty()) append("\n回填记录：").append(n.checks.takeLast(4).joinToString("；") { "${it.date} ${it.result}（${it.status}）" })
        }
        return ToolResult(text, "看了脉络节点「${n.title}」")
    }

    private fun veinPropose(a: Args): ToolResult {
        val title = a.s("title").ifBlank { return ToolResult.fail("title 不能空") }
        val status = com.zongkong.core.vein.VStatus.of(a.s("status").ifBlank { "假设" })
        if (status == com.zongkong.core.vein.VStatus.HYPO && a.s("verify").isBlank()) return ToolResult.fail("假设要写验证方式（verify）")
        val tmp = "a" + (env.now() / 1000).toString(36) + env.rnd.nextInt(100)
        val target = a.opt("relate_to")?.let { findNode(it) ?: return ToolResult.fail("没有节点“$it”") }
        val c = com.zongkong.core.vein.Candidate(
            tmp = tmp, title = title.take(40), type = com.zongkong.core.vein.NodeType.of(a.s("type")).label, claim = a.s("claim").ifBlank { title },
            keywords = a.s("keywords").split(Regex("[、,，]")).map { it.trim() }.filter { it.isNotEmpty() }.take(5),
            status = status.label, confidence = (a.i("confidence") ?: 50).coerceIn(0, 100), verify = a.s("verify"),
            mergeInto = com.zongkong.core.vein.Veins.likelyDuplicate(env.vein(), title, emptyList())?.id ?: "",
            system = a.s("system").ifBlank { target?.system ?: "未分类" }, parent = a.s("parent").ifBlank { target?.parent ?: "" }, source = "总控",
        )
        val rel = a.opt("relation")?.takeIf { it in com.zongkong.core.vein.Rel.TYPES }
        env.updateVein { v ->
            v.copy(
                candidates = v.candidates + c,
                candLinks = v.candLinks + listOfNotNull(if (target != null && rel != null) com.zongkong.core.vein.CandLink(tmp, target.id, rel, "总控提议") else null),
            )
        }
        val dup = if (c.mergeInto.isNotBlank()) "（可能和 ${c.mergeInto} 重复，确认时会提示合并）" else ""
        return ToolResult("提议进了候选：「$title」$dup。用户要在“脉络 → 候选”里确认才写入", "提议脉络节点「$title」", route = "vein/cands")
    }

    private fun veinBackfill(a: Args): ToolResult {
        val n = findNode(a.s("node")) ?: return ToolResult.fail("脉络里没有“${a.s("node")}”")
        val st = com.zongkong.core.vein.VStatus.entries.firstOrNull { it.label == a.s("status") } ?: return ToolResult.fail("状态只能是 假设 / 已验证 / 已推翻")
        var err: String? = null
        env.updateVein { v -> com.zongkong.core.vein.Veins.backfill(v, n.id, a.s("result"), st, a.i("confidence"), env.today().toString()).getOrElse { e -> err = e.message; v } }
        err?.let { return ToolResult.fail(it) }
        return ToolResult("回填了「${n.title}」：${st.label}", "回填「${n.title}」：${st.label}")
    }

    private fun veinDue(): String {
        val v = env.vein()
        val today = env.today()
        val hypos = v.nodes.filter { it.status == com.zongkong.core.vein.VStatus.HYPO.label }
            .sortedByDescending { com.zongkong.core.vein.Veins.daysBetween(it.checks.lastOrNull()?.date ?: it.created, today) }
        val due = com.zongkong.core.vein.Veins.dueReviews(v, today, 5)
        val h = com.zongkong.core.vein.Veins.health(v, null, today)
        return buildString {
            append("脉络：${v.nodes.size} 个节点，体检 ${h.score} 分，候选 ${v.candidates.size} 张\n")
            if (hypos.isNotEmpty()) {
                append("等验证的假设：\n")
                hypos.take(5).forEach { append("· ${it.id} ${it.title}｜${com.zongkong.core.vein.Veins.daysBetween(it.checks.lastOrNull()?.date ?: it.created, today)} 天没回填｜验证：${it.verify.ifBlank { "（没写）" }}\n") }
            }
            if (due.isNotEmpty()) append("今天该温故：").append(due.joinToString("、") { it.title }).append('\n')
            h.issues.take(3).forEach { append("体检：${it.label}（${it.nodes.size}）\n") }
        }.trimEnd()
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
        if (met == WorkOps.Met.YES) {
            activePlan()?.let { p ->
                p.outcomes.firstOrNull { it.actionKey == x.key && !it.done }?.let { o ->
                    env.updatePlans { com.zongkong.core.plan.Plans.setOutcomeDone(it, p.id, o.id, true, env.now()) }
                }
            }
        }
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
        val msg = env.sendToYishou(x.key) + if (a.b("window") == true) "；" + env.startYishouWindow() else ""
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
        "vein_node" -> env.vein().let { v -> (v.node(target) ?: v.byTitle(target))?.let { "vein/node/${it.id}" } }
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

    // ---------- 计划 ----------

    private fun activePlan() = com.zongkong.core.plan.Plans.active(env.plans(), env.now(), env.zone)

    private fun getPlan(): ToolResult {
        val book = env.plans()
        val p = activePlan() ?: com.zongkong.core.plan.Plans.nextActive(book, env.now(), env.zone)
        val draft = com.zongkong.core.plan.Plans.draft(book, env.now(), env.zone)
        val text = buildString {
            if (p != null) append(com.zongkong.core.plan.Plans.describe(p, env.work(), book, env.now(), env.zone)) else append("本周还没有生效的计划。\n")
            if (draft != null) append("\n有一份等他立项的草稿（${draft.by}起草，${draft.goals.size} 个方向、${draft.outcomes.size} 样交出物）。\n")
        }
        return ToolResult(text, "看了本周计划")
    }

    private fun lines(s: String) = s.split('\n').map { it.trim().trimStart('-', '·', '•', '*', ' ') }.filter { it.isNotEmpty() }
    private fun fields(line: String) = line.split('｜', '|').map { it.trim() }

    private fun draftPlan(a: Args): ToolResult {
        val now = env.now()
        val ws0 = com.zongkong.core.plan.Plans.weekStart(now, env.zone)
        val ws = if (a.s("week") == "下周") ws0.plusWeeks(1) else ws0
        val goals = lines(a.s("goals")).take(com.zongkong.core.plan.Plans.MAX_GOALS).mapIndexed { i, l ->
            val f = fields(l)
            val title = f.getOrElse(0) { "" }
            val ref = f.getOrElse(2) { "" }
            val thread = (if (ref.isNotBlank()) findThread(ref) else null) ?: findThread(title)
            com.zongkong.core.plan.Goal(com.zongkong.core.plan.Plans.newId("g", now, i), title, f.getOrElse(1) { "" }, thread?.key.orEmpty())
        }.filter { it.title.isNotBlank() }
        if (goals.isEmpty()) return ToolResult.fail("至少要一个方向")
        val outcomes = lines(a.s("outcomes")).mapIndexedNotNull { i, l ->
            val f = fields(l)
            val g = goals.firstOrNull { it.title == f.getOrElse(0) { "" } } ?: goals.firstOrNull { f.getOrElse(0) { "" }.contains(it.title) || it.title.contains(f.getOrElse(0) { "" }) } ?: return@mapIndexedNotNull null
            val title = f.getOrElse(1) { "" }.ifBlank { return@mapIndexedNotNull null }
            com.zongkong.core.plan.Outcome(com.zongkong.core.plan.Plans.newId("o", now, i), g.id, title, f.getOrElse(2) { "" }, g.threadKey)
        }.groupBy { it.goalId }.flatMap { it.value.take(com.zongkong.core.plan.Plans.MAX_OUTCOMES_PER_GOAL) }
        if (outcomes.isEmpty()) return ToolResult.fail("至少要一样交出物，格式：方向标题｜交出什么｜做到什么算完（方向标题要和 goals 里的一致）")
        val rest = a.s("rest_days").let { r ->
            val chinese = "一二三四五六日"
            r.split(',', '，', '、', ' ').mapNotNull { x ->
                val t = x.trim().removePrefix("周").removePrefix("星期")
                t.toIntOrNull()?.takeIf { it in 1..7 } ?: chinese.indexOf(t).takeIf { it >= 0 && t.length == 1 }?.plus(1)
            }.toSet()
        }
        val plan = com.zongkong.core.plan.WeekPlan(
            id = com.zongkong.core.plan.Plans.newId("w", now, 0),
            weekStart = ws.toString(),
            goals = goals,
            outcomes = outcomes,
            dailyMinutes = (a.i("daily_minutes") ?: 120).coerceIn(30, 600),
            restDays = rest,
            ifThens = lines(a.s("if_then")).mapNotNull { l -> fields(l).let { f -> if (f.size >= 2 && f[0].isNotBlank() && f[1].isNotBlank()) com.zongkong.core.plan.IfThen(f[0].removePrefix("如果"), f[1].removePrefix("就")) else null } },
            by = "参谋",
            createdAt = now,
        )
        env.updatePlans { com.zongkong.core.plan.Plans.putDraft(it, plan) }
        val unmatched = goals.count { it.threadKey.isBlank() }
        return ToolResult(
            "草稿已放进计划页，等他点“立项”。${goals.size} 个方向、${outcomes.size} 样交出物" + (if (unmatched > 0) "；$unmatched 个方向没对上事项（立项时会新建事项）" else "") + "。会在回复后打开计划页。",
            "起草了${if (ws == ws0) "本周" else "下周"}计划（${outcomes.size} 样交出物）",
            route = "plan",
        )
    }

    private fun setDayMain(a: Args): ToolResult {
        val x = findAction(a.s("action")) ?: return noAction(a.s("action"))
        val now = env.now()
        val date = DayClock.dateKey(now, env.zone)
        val p = activePlan()
        val outcome = p?.outcomes?.firstOrNull { it.threadKey.isNotBlank() && it.threadKey == x.threadKey && !it.done }
        env.updatePlans { book ->
            val old = com.zongkong.core.plan.Plans.day(book, date)
            com.zongkong.core.plan.Plans.setDay(book, com.zongkong.core.plan.DayPlan(date, outcome?.id ?: old?.mainOutcomeId.orEmpty(), x.key, old?.extras.orEmpty(), now))
        }
        val inPlan = p != null && com.zongkong.core.plan.Plans.inPlan(p, x)
        return ToolResult("今天的主线定为「${x.title}」" + if (p != null && !inPlan) "（注意：它不在本周计划里）" else "", "今天主线：「${x.title}」")
    }

    private fun addExpense(a: Args): ToolResult {
        val lg = com.zongkong.core.ledger.Ledger
        val cents = lg.cents(a.s("amount")) ?: return ToolResult.fail("金额没认出来：${a.s("amount")}")
        val what = a.s("what").ifBlank { return ToolResult.fail("what 不能空") }
        val now = env.now()
        val at = a.opt("time")?.let { t -> TimeParse.parse(t, now, env.zone)?.let { TimeParse.startMillis(it, env.zone) } } ?: now
        val cat = a.s("category").takeIf { it in lg.CATS } ?: lg.guessCat(what)
        val e = com.zongkong.core.ledger.Expense("e" + now.toString(36) + env.rnd.nextInt(1000), at, cents, what, cat, a.s("source").ifBlank { "手记" })
        env.updateLedger { lg.add(it, listOf(e)).first }
        val today = lg.day(env.ledger(), DayClock.date(now, env.zone), env.zone)
        return ToolResult("记了：$what ${lg.yuan(cents)} 元（$cat）。今天合计 ${lg.yuan(lg.total(today))} 元。", "记账 ${lg.yuan(cents)} 元")
    }

    private fun setDayScript(a: Args): ToolResult {
        val pb = com.zongkong.core.plan.Playbook
        val now = env.now()
        val date = DayClock.dateKey(now, env.zone)
        val (blocks, bad) = pb.parse(a.s("script"), now)
        val old = pb.of(env.plans(), date)
        val (next, err) = pb.write(old, date, blocks, a.s("why"), "参谋", now)
        if (err != null || next == null) return ToolResult.fail(err ?: "没写成")
        val final = if (a.b("lock") == true) pb.lock(next, now) else next
        env.updatePlans { pb.put(it, final) }
        return ToolResult(
            "预案${if (final.locked) "已定稿" else "草稿"}：${final.blocks.size} 段" + (if (bad.isNotEmpty()) "；没认出来的行：" + bad.joinToString("；") else "") + "\n" + pb.brief(final, now, env.zone),
            "预案：${final.blocks.size} 段" + if (final.locked) "（定稿）" else "",
            route = "playbook",
        )
    }

    private fun scriptBlock(a: Args): ToolResult {
        val pb = com.zongkong.core.plan.Playbook
        val now = env.now()
        val s = pb.today(env.plans(), now, env.zone) ?: return ToolResult.fail("今天还没有预案")
        val ref = a.s("id")
        val b = s.block(ref) ?: s.blocks.firstOrNull { it.what == ref } ?: s.blocks.firstOrNull { it.what.contains(ref) || ref.contains(it.what) }
            ?: return ToolResult.fail("预案里没有“$ref”")
        val text = a.s("text")
        val (next, err) = when (a.s("act")) {
            "开始" -> pb.begin(s, b.id) to null
            "速记" -> pb.note(s, b.id, text) to null
            "做完" -> pb.finish(s, b.id, text) to null
            "走B方案" -> pb.planB(s, b.id, text, now)
            "跳过" -> pb.skip(s, b.id, text, now)
            else -> s to "act 只能是：开始、速记、做完、走B方案、跳过"
        }
        if (err != null) return ToolResult.fail(err)
        env.updatePlans { pb.put(it, next) }
        return ToolResult("「${b.what}」${a.s("act")}", "预案「${b.what.take(10)}」${a.s("act")}")
    }

    private fun findOutcome(ref: String): Pair<com.zongkong.core.plan.WeekPlan, com.zongkong.core.plan.Outcome>? {
        val p = activePlan() ?: return null
        val o = p.outcomes.firstOrNull { it.id == ref } ?: p.outcomes.firstOrNull { it.title == ref } ?: p.outcomes.firstOrNull { it.title.contains(ref) || ref.contains(it.title) }
        return o?.let { p to it }
    }

    private fun completeOutcome(a: Args): ToolResult {
        val (p, o) = findOutcome(a.s("outcome")) ?: return ToolResult.fail("本周计划里没有“${a.s("outcome")}”")
        env.updatePlans { com.zongkong.core.plan.Plans.setOutcomeDone(it, p.id, o.id, true, env.now()) }
        return ToolResult("「${o.title}」标成已交出", "交出：「${o.title}」")
    }

    private fun revisePlan(a: Args): ToolResult {
        val p = activePlan() ?: return ToolResult.fail("本周没有生效的计划。先起草（draft_week_plan），他立项后才能改")
        val why = a.s("why").ifBlank { return ToolResult.fail("改计划要写为什么") }
        val now = env.now()
        val done = mutableListOf<String>()
        a.opt("add")?.let { l ->
            val f = fields(l)
            val g = p.goals.firstOrNull { it.title == f.getOrElse(0) { "" } } ?: p.goals.firstOrNull { f.getOrElse(0) { "" }.contains(it.title) || it.title.contains(f.getOrElse(0) { "" }) }
                ?: return ToolResult.fail("方向“${f.getOrElse(0) { "" }}”不在计划里；现有方向：" + p.goals.joinToString("、") { it.title })
            val title = f.getOrElse(1) { "" }.ifBlank { return ToolResult.fail("add 格式：方向标题｜交出什么｜做到什么算完") }
            val o = com.zongkong.core.plan.Outcome(com.zongkong.core.plan.Plans.newId("o", now, p.outcomes.size), g.id, title, f.getOrElse(2) { "" }, g.threadKey)
            env.updatePlans { com.zongkong.core.plan.Plans.revise(it, p.id, "加：$title", why, now) { w -> w.copy(outcomes = w.outcomes + o) } }
            done += "加了「$title」"
        }
        a.opt("drop")?.let { ref ->
            val o = (activePlan() ?: p).outcomes.firstOrNull { it.id == ref || it.title == ref || it.title.contains(ref) } ?: return ToolResult.fail("计划里没有“$ref”")
            env.updatePlans { com.zongkong.core.plan.Plans.revise(it, p.id, "去掉：${o.title}", why, now) { w -> w.copy(outcomes = w.outcomes.filterNot { x -> x.id == o.id }) } }
            done += "去掉了「${o.title}」"
        }
        if (done.isEmpty()) return ToolResult.fail("add 和 drop 都没填")
        return ToolResult("改了本周计划：${done.joinToString("，")}（为什么：$why，已留痕）", "改计划：${done.joinToString("，")}")
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
            "week" to "本周计划（计划页）",
            "plan_mode" to "计划模式（定本周计划的向导）",
            "review" to "今天的清单（日终验收）",
            "playbook" to "今天的预案",
            "quick" to "记一下（收集箱）",
            "ledger" to "账本",
            "digest" to "贴一段讨论（让你消化）",
            "rhythm" to "今天的节律关卡",
            "gate" to "某道关卡",
            "session" to "正在做（计时页）",
            "hq" to "总控时间",
            "results" to "查看结果",
            "stuck" to "我卡住了",
            "settings" to "设置",
            "permissions" to "权限与运行状态",
            "block" to "拦截名单（含紧急放行）",
            "rules" to "规则",
            "gates" to "关卡设置",
            "notion" to "Notion 连接",
            "ai" to "AI 接口",
            "vein" to "脉络（谋划思考部的知识结构）",
            "vein_node" to "脉络里的某个节点",
            "vein_cands" to "脉络的候选卡片",
            "vein_review" to "脉络·温故",
            "vein_health" to "脉络·体检",
            "dossier" to "档案（信用、约定）",
            "voice" to "语音对讲",
            "directives" to "外接谋划（Gemini / GPT / Notion 交进来的）",
            "watch" to "低屏模式（限时 + 冷却 + 做题）",
            "diag" to "无障碍诊断",
        )

        private val ROUTES: Map<String, String> = mapOf(
            "today" to "home",
            "depts" to "depts",
            "threads" to "threads",
            "capture" to "capture",
            "plan" to "wizard/plan",
            "week" to "plan",
            "plan_mode" to "plan/new",
            "review" to "today",
            "playbook" to "playbook",
            "quick" to "quick",
            "ledger" to "ledger",
            "digest" to "quick/digest",
            "rhythm" to "rhythm",
            "session" to "session",
            "hq" to "hq",
            "results" to "results",
            "stuck" to "stuckpick",
            "settings" to "settings",
            "permissions" to "settings/perm",
            "block" to "settings/block",
            "rules" to "settings/rules",
            "gates" to "settings/gates",
            "notion" to "settings/notion",
            "ai" to "settings/ai",
            "vein" to "vein",
            "vein_cands" to "vein/cands",
            "vein_review" to "vein/review",
            "vein_health" to "vein/health",
            "dossier" to "dossier",
            "voice" to "voice",
            "directives" to "directives",
            "watch" to "settings/watch",
            "diag" to "settings/diag",
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
