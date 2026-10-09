package com.zongkong.core.work

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * 和 ChatGPT 之间的交接。
 *
 * 去：[contextPrompt] 把一件事的前情（为什么、当前判断、断点、做过什么、结果、没解决的问题）
 *     整理成一段话，复制给 ChatGPT，不用重新讲一遍。
 * 回：ChatGPT 在讨论结束时输出一个【总控交接】块（格式见 [GPT_INSTRUCTIONS]），
 *     复制或分享给总控，[parse] 读出来，[plan] 先给你看会改什么，确认后 [apply] 写入并同步到 Notion。
 * 如果 ChatGPT 能直接写 Notion，它写完只回一行【总控交接·已写入】，总控去 Notion 拉取即可。
 */
object Handoff {
    const val START = "【总控交接】"
    const val WRITTEN = "【总控交接·已写入】"
    const val END = "【交接结束】"

    data class ActionItem(val title: String, val time: String = "", val criteria: String = "")
    data class Item(val title: String, val url: String = "")

    data class Block(
        val thread: String = "",
        val code: String = "",
        val why: String = "",
        val judge: String = "",
        val unsure: String = "",
        val breakpoint: String = "",
        val next: String = "",
        val criteria: String = "",
        val status: String = "",
        val actions: List<ActionItem> = emptyList(),
        val materials: List<Item> = emptyList(),
        val questions: List<String> = emptyList(),
        /** GPT 说它已经直接写进了 Notion。 */
        val alreadyWritten: Boolean = false,
    ) {
        val empty: Boolean get() = thread.isBlank() && code.isBlank() && actions.isEmpty() && judge.isBlank()
    }

    fun contains(text: String) = text.contains("总控交接")

    private val KEYS = mapOf(
        "事项" to "thread", "标题" to "thread", "主题" to "thread",
        "编号" to "code",
        "为什么" to "why", "目标" to "why",
        "当前判断" to "judge", "判断" to "judge", "结论" to "judge",
        "不确定" to "unsure", "关键不确定" to "unsure", "待查" to "unsure", "未解决" to "unsure",
        "断点" to "breakpoint", "停在" to "breakpoint",
        "下一步" to "next",
        "完成依据" to "criteria", "完成标准" to "criteria",
        "状态" to "status",
        "行动" to "actions", "行动清单" to "actions", "待办" to "actions",
        "材料" to "materials", "相关材料" to "materials",
        "问题" to "questions", "待查问题" to "questions",
    )
    private val LIST_KEYS = setOf("actions", "materials", "questions")
    private val CODE_RE = Regex("ZK-[0-9A-Za-z]{4}")
    private val BULLET = Regex("^\\s*(?:[-*•·]\\s*)?(?:\\[[ xX]?]\\s*)?(?:\\d+[.、)）]\\s*)?")

    fun parse(text: String): Block? {
        val startIdx = text.indexOf("总控交接")
        if (startIdx < 0) return null
        val written = text.substring(startIdx).startsWith("总控交接·已写入") || text.contains(WRITTEN)
        var body = text.substring(startIdx).substringAfter('\n', "")
        val endIdx = body.indexOf("交接结束")
        if (endIdx >= 0) body = body.substring(0, endIdx).substringBeforeLast('\n', body.substring(0, endIdx))
        val single = mutableMapOf<String, StringBuilder>()
        val lists = mutableMapOf<String, MutableList<String>>()
        var current: String? = null
        for (raw in body.lines()) {
            val line = raw.trim().removePrefix("```").trim().replace("**", "").replace("__", "")
            if (line.isEmpty() || line.startsWith("```")) continue
            val m = Regex("^(?:#+\\s*)?([\\u4e00-\\u9fa5]{1,6})\\s*[:：]\\s*(.*)$").find(line)
            val key = m?.groupValues?.get(1)?.let { KEYS[it] }
            if (m != null && key != null) {
                current = key
                val v = m.groupValues[2].trim()
                if (key in LIST_KEYS) {
                    if (v.isNotEmpty()) lists.getOrPut(key) { mutableListOf() } += v
                } else {
                    single[key] = StringBuilder(v)
                }
                continue
            }
            val cur = current ?: continue
            if (cur in LIST_KEYS) {
                lists.getOrPut(cur) { mutableListOf() } += line.replace(BULLET, "").trim()
            } else {
                single.getOrPut(cur) { StringBuilder() }.let { if (it.isNotEmpty()) it.append('\n'); it.append(line) }
            }
        }
        fun s(k: String) = single[k]?.toString()?.trim().orEmpty().let { if (it == "无" || it == "（无）" || it == "-") "" else it }
        var thread = s("thread")
        var code = s("code")
        CODE_RE.find(thread)?.let { m ->
            if (code.isBlank()) code = m.value
            thread = thread.replace(m.value, "").replace("（）", "").replace("()", "").trim().trimEnd('（', '(').trim()
        }
        if (code.isBlank()) CODE_RE.find(text)?.let { code = it.value }
        val actions = lists["actions"].orEmpty().filter { it.isNotBlank() && it != "无" }.map(::actionItem)
        val materials = lists["materials"].orEmpty().filter { it.isNotBlank() && it != "无" }.map { line ->
            val url = Regex("https?://\\S+").find(line)?.value.orEmpty()
            Item(line.replace(url, "").split('|', '｜').first().trim().trim('-', ' ').ifBlank { url }, url)
        }
        val questions = lists["questions"].orEmpty().filter { it.isNotBlank() && it != "无" }
        return Block(
            thread = thread, code = code.uppercase(), why = s("why"), judge = s("judge"), unsure = s("unsure"),
            breakpoint = s("breakpoint"), next = s("next"), criteria = s("criteria"), status = s("status"),
            actions = actions, materials = materials, questions = questions, alreadyWritten = written,
        )
    }

    private fun actionItem(line: String): ActionItem {
        val parts = line.split('|', '｜').map { it.trim() }.filter { it.isNotEmpty() }
        var title = parts.firstOrNull().orEmpty()
        var time = ""
        var criteria = ""
        for ((i, p) in parts.withIndex().drop(1)) {
            val v = p.substringAfter('：', p.substringAfter(':', p)).trim()
            when {
                p.startsWith("完成依据") || p.startsWith("完成标准") || p.startsWith("依据") -> criteria = v
                p.startsWith("时间") || p.startsWith("计划") -> time = v
                time.isEmpty() && Regex("\\d|今|明|后天|周|星期").containsMatchIn(p) -> time = p
                // 约定格式“标题 | 时间 | 完成依据”：三段时第二段就是时间（哪怕写得不规范）
                time.isEmpty() && i == 1 && parts.size >= 3 -> time = p
                criteria.isEmpty() -> criteria = p
            }
        }
        // “标题（明天 9:00）”这种写法
        if (time.isEmpty()) {
            Regex("[（(]([^）)]*(?:\\d|今|明|周)[^）)]*)[）)]\\s*$").find(title)?.let { m ->
                time = m.groupValues[1]
                title = title.removeRange(m.range).trim()
            }
        }
        return ActionItem(title.trim(), time, criteria)
    }

    // ---------- 导入前先给你看 ----------

    data class Plan(
        val threadKey: String?,
        val threadTitle: String,
        val threadChanges: Map<String, String>,
        val newActions: List<Pair<ActionItem, com.zongkong.core.work.Plan?>>,
        val updatedActions: List<Triple<String, ActionItem, com.zongkong.core.work.Plan?>>,
        val notes: List<Map<String, String>>,
        val warnings: List<String>,
    ) {
        val lines: List<String>
            get() = buildList {
                add(if (threadKey == null) "新建事项「$threadTitle」" else "更新事项「$threadTitle」")
                threadChanges.forEach { (k, v) -> if (k != F.TITLE) add("· $k：${v.take(60)}") }
                newActions.forEach { (a, p) -> add("＋ 行动：${a.title}${if (p != null) "（${p.start.take(16).replace('T', ' ')}）" else "（没有时间）"}") }
                updatedActions.forEach { (_, a, p) -> add("↻ 行动：${a.title}${if (p != null) "（改到 ${p.start.take(16).replace('T', ' ')}）" else ""}") }
                notes.forEach { add("＋ 记录·${it[F.TYPE]}：${it[F.TITLE]?.take(40)}") }
                warnings.forEach { add("⚠ $it") }
            }
    }

    fun plan(work: Work, b: Block, now: Long, zone: ZoneId): Plan {
        val existing = work.threadByCode(b.code)
            ?: work.threads().firstOrNull { norm(it.title) == norm(b.thread) && b.thread.isNotBlank() }
        val warnings = mutableListOf<String>()
        if (b.code.isNotBlank() && work.threadByCode(b.code) == null) warnings += "编号 ${b.code} 在本机没找到，按名称处理"
        val changes = buildMap {
            if (existing == null) put(F.TITLE, b.thread.ifBlank { b.actions.firstOrNull()?.title ?: "未命名事项" })
            fun maybe(k: String, v: String) { if (v.isNotBlank() && existing?.get(k) != v) put(k, v) }
            maybe(F.WHY, b.why); maybe(F.JUDGE, b.judge); maybe(F.UNSURE, b.unsure)
            maybe(F.BREAK, b.breakpoint); maybe(F.NEXT, b.next); maybe(F.CRITERIA, b.criteria)
            val st = ThreadStatus.entries.firstOrNull { it.label == b.status.trim() }
                ?: if (b.actions.isNotEmpty()) ThreadStatus.READY else if (b.judge.isNotBlank()) ThreadStatus.THINKING else null
            st?.let { maybe(F.STATUS, it.label) }
        }
        val olds = existing?.let { work.actionsOf(it.key) }.orEmpty()
        val newActions = mutableListOf<Pair<ActionItem, com.zongkong.core.work.Plan?>>()
        val updated = mutableListOf<Triple<String, ActionItem, com.zongkong.core.work.Plan?>>()
        for (a in b.actions) {
            if (a.title.isBlank()) continue
            val p = TimeParse.parse(a.time, now, zone)
            if (a.time.isNotBlank() && p == null) warnings += "「${a.title}」的时间“${a.time}”没认出来，先不排时间"
            val same = olds.firstOrNull { norm(it.title) == norm(a.title) }
            if (same != null) updated += Triple(same.key, a, p) else newActions += a to p
        }
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val notes = buildList {
            if (b.judge.isNotBlank()) add(mapOf(
                F.TITLE to "判断：${b.judge.lineSequence().first().take(60)}", F.TYPE to NoteType.CONCLUSION.label,
                F.RAW to buildString {
                    append("当前判断：${b.judge}")
                    if (b.unsure.isNotBlank()) append("\n不确定：${b.unsure}")
                    if (b.next.isNotBlank()) append("\n下一步：${b.next}")
                },
                F.DATE to day,
            ))
            b.questions.forEach { q -> add(mapOf(F.TITLE to q.take(80), F.TYPE to NoteType.QUESTION.label, F.RAW to q, F.DATE to day)) }
            b.materials.forEach { m -> add(mapOf(F.TITLE to m.title.take(80), F.TYPE to NoteType.MATERIAL.label, F.URL to m.url, F.RAW to m.title, F.DATE to day)) }
        }
        return Plan(existing?.key, existing?.title ?: changes[F.TITLE].orEmpty(), changes, newActions, updated, notes, warnings)
    }

    /** 按计划写入本机（标记待同步）。返回新的 Work 和事项 key。 */
    fun apply(work: Work, p: Plan, now: Long, rnd: Random): Pair<Work, String> {
        var w = work
        val threadKey = if (p.threadKey == null) {
            val (w2, t) = WorkOps.create(w, Kind.THREAD, p.threadChanges, now, rnd)
            w = w2
            t.key
        } else {
            w = WorkOps.update(w, p.threadKey, p.threadChanges, now)
            p.threadKey
        }
        for ((a, plan) in p.newActions) {
            w = WorkOps.create(w, Kind.ACTION, buildMap {
                put(F.TITLE, a.title); put(F.THREAD, threadKey)
                if (a.criteria.isNotBlank()) put(F.CRITERIA, a.criteria)
                plan?.let { put(F.PLAN, it.encode()) }
            }, now, rnd).first
        }
        for ((key, a, plan) in p.updatedActions) {
            w = WorkOps.update(w, key, buildMap {
                if (a.criteria.isNotBlank()) put(F.CRITERIA, a.criteria)
                plan?.let { put(F.PLAN, it.encode()) }
                val cur = w.rec(key)
                if (cur != null && !cur.actionStatus.open) put(F.STATUS, ActionStatus.TODO.label)
            }, now)
        }
        for (n in p.notes) w = WorkOps.create(w, Kind.NOTE, n + (F.THREAD to threadKey), now, rnd).first
        w = WorkOps.event(w, Event(now, "import", threadKey, p.lines.size.toString()))
        return w to threadKey
    }

    private fun norm(s: String) = s.replace(Regex("[\\s\\p{Punct}，。、；：“”‘’（）【】《》！？]"), "").lowercase()

    // ---------- 去 ChatGPT：把前情带过去 ----------

    fun contextPrompt(work: Work, threadKey: String, now: Long, zone: ZoneId, ask: String = ""): String {
        val t = work.rec(threadKey) ?: return ask
        val actions = work.actionsOf(threadKey).sortedBy { it.createdAt }
        val notes = work.notesOf(threadKey).sortedByDescending { it.createdAt }
        return buildString {
            append("继续推进这件事：「${t.title}」（${t[F.CODE]}）\n")
            fun line(label: String, v: String) { if (v.isNotBlank()) append("$label：${v.trim()}\n") }
            line("为什么做", t[F.WHY])
            line("完成依据", t[F.CRITERIA])
            line("当前判断", t[F.JUDGE])
            line("关键不确定", t[F.UNSURE])
            line("上次停在", t[F.BREAK])
            line("原定下一步", t[F.NEXT])
            if (actions.isNotEmpty()) {
                append("\n做过 / 要做的行动：\n")
                actions.takeLast(8).forEach { a ->
                    val time = a.plan?.let { " · ${TimeParse.label(it, now, zone)}" }.orEmpty()
                    append("- ${a.title}（${a[F.STATUS]}$time）")
                    if (a[F.RESULT].isNotBlank()) append(" 结果：${a[F.RESULT].take(120)}")
                    if (a[F.STUCK].isNotBlank() && a.actionStatus == ActionStatus.STUCK) append(" 卡点：${a[F.STUCK]}")
                    append('\n')
                }
            }
            val recent = notes.filter { it.noteType in setOf(NoteType.MATERIAL, NoteType.QUESTION, NoteType.IDEA, NoteType.CONCLUSION) }.take(6)
            if (recent.isNotEmpty()) {
                append("\n相关记录：\n")
                recent.forEach { n -> append("- [${n[F.TYPE]}] ${n.title.take(60)}${if (n[F.URL].isNotBlank()) " ${n[F.URL]}" else ""}\n") }
            }
            if (t.url.isNotBlank()) append("\nNotion 页面：${t.url}\n")
            append("\n")
            append(if (ask.isNotBlank()) ask.trim() else "请先读一下 Notion 里这个事项的最新记录（如果你能访问），然后帮我判断现在最该做的下一步。")
            append("\n讨论结束时，按「总控交接」格式给我交接块（事项写 ${t[F.CODE]}）。")
        }
    }

    /** 不会做 / 不清楚时，拿去问 GPT 的话。 */
    fun stuckPrompt(work: Work, actionKey: String, reason: StuckReason, detail: String, now: Long, zone: ZoneId): String {
        val a = work.rec(actionKey) ?: return ""
        val ask = when (reason) {
            StuckReason.CANT -> "我卡在「${a.title}」：不知道怎么下手${if (detail.isNotBlank()) "（$detail）" else ""}。请把它拆成 3–5 个小步骤，第一步要小到 5 分钟能开始，每步写完成依据。"
            StuckReason.UNCLEAR -> "「${a.title}」这一步我不清楚到底要做什么、做到什么程度算完${if (detail.isNotBlank()) "（$detail）" else ""}。请帮我把它改写成一个明确的行动和可检查的完成依据，信息不够就告诉我先查什么。"
            else -> "我在「${a.title}」上没推进，原因是：${reason.label}${if (detail.isNotBlank()) "，$detail" else ""}。帮我调整安排。"
        }
        return if (a.threadKey.isNotBlank()) contextPrompt(work, a.threadKey, now, zone, ask) else ask + "\n讨论结束时按「总控交接」格式给我交接块。"
    }

    /** 放进 ChatGPT「项目」的说明。告诉 GPT 怎么配合总控。 */
    val GPT_INSTRUCTIONS = """
        你在帮我推进我的事情。我有一个手机 App 叫「总控」，负责提醒、续接和约束；我的共享记录在 Notion 里，有三个数据库：

        1. 总控·事项：一件要持续推进的事。列：标题、编号（ZK-XXXX）、状态（收集中/思考中/待行动/推进中/卡住/已完成/搁置/取消）、为什么、当前判断、不确定、断点、下一步、完成依据、优先级、更新来源。
        2. 总控·行动：排进时间的具体一步。列：标题、事项（关联到事项）、事项编号、状态（待做/进行中/待确认/完成/卡住/改期/取消）、计划时间、完成依据、结果、断点、卡点、更新来源。
        3. 总控·记录：材料、问题、念头、结论、结果、断点、卡点。列：标题、事项（关联）、事项编号、类型、来源（链接）、原文、日期、更新来源。

        规则：
        - 判断要有依据；信息不够时，结论可以是“还需要补充信息”，并在“不确定”里写清缺什么、去哪查。
        - 每个行动必须写“完成依据”：做到什么程度算完成、拿什么证明（一个数字、一个文件、一张截图、一次投递记录）。
        - 行动要小：第一步应该能在 30 分钟内开始并完成。
        - 不要替我宣布“已完成”。结果以我在总控里记录的为准。
        - 如果你能写入 Notion：直接在上面三个库里新建或更新（更新来源填 GPT；行动要填“事项”关联或“事项编号”），写完在回复最后单独一行写：【总控交接·已写入】 ZK-XXXX
        - 如果你不能写入 Notion：在讨论结束时，原样输出下面的交接块，我会复制给总控：

        【总控交接】
        事项：（事项名称）（ZK-XXXX，新事项不写编号）
        为什么：
        当前判断：
        不确定：
        断点：（这次讨论停在哪）
        下一步：
        完成依据：（整件事做到什么算完成）
        状态：（收集中/思考中/待行动/推进中/卡住/已完成/搁置/取消）
        行动：
        - 行动标题 | 时间（如：明天 20:00-21:00、周六 10:00）| 完成依据：……
        材料：
        - 材料标题 | 链接
        问题：
        - 还没解决的问题
        【交接结束】

        没有的项可以省略。时间用“今天/明天/周几 + 几点”或“10-12 20:00”这种写法。
    """.trimIndent()
}
