package com.zongkong.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 字数和凑字数检查。 */
object TextCheck {
    /** 不计空白、不计模板原文的字数：照抄模板里的标签不算字。 */
    fun effectiveChars(text: String, template: String = ""): Int {
        var t = text
        template.lines().map { it.trim().trimEnd('：', ':').trim() }
            .filter { it.length >= 2 }
            .sortedByDescending { it.length }
            .forEach { t = t.replace(it, "") }
        return t.count { !it.isWhitespace() && it !in "：:·.。，,、;；-—()（）【】[]0123456789" }
    }

    /** 明显在凑字数：大段重复同几个字。 */
    fun looksPadded(text: String): Boolean {
        val chars = text.filterNot { it.isWhitespace() }
        if (chars.length < 20) return false
        return chars.toSet().size.toDouble() / chars.length < 0.2
    }
}

/** 验收的结果：有结论，或者 AI / Notion 暂时用不了。 */
sealed interface VerifyOutcome {
    data class Done(val verdict: Verdict) : VerifyOutcome

    /** 外部服务不可用。canDegrade 表示可以改用加倍字数的降级验收。 */
    data class Unavailable(val reason: String, val canDegrade: Boolean) : VerifyOutcome
}

class Verifier(private val llm: LlmApi, private val notion: NotionApi) {

    /**
     * @param facts 今天的实际数据，给 AI 对照（日终验收靠它识破“报喜不报忧”）。
     */
    suspend fun verify(
        gate: Gate,
        text: String,
        config: Config,
        date: LocalDate,
        zone: ZoneId,
        facts: String,
    ): VerifyOutcome {
        val needsText = gate.verify != VerifyMode.NOTION
        if (needsText) {
            val chars = TextCheck.effectiveChars(text, Defaults.fillTemplate(gate.template, date))
            if (TextCheck.looksPadded(text)) {
                return VerifyOutcome.Done(Verdict(false, "字数", "内容大量重复，像是在凑字数。认真写。"))
            }
            val need = if (gate.verify != VerifyMode.TEXT && !config.ai.ready) gate.minChars * 2 else gate.minChars
            if (chars < need) {
                val note = if (need > gate.minChars) "（还没配置 AI，字数要求翻倍）" else ""
                return VerifyOutcome.Done(Verdict(false, "字数", "去掉模板标签后只有 $chars 字，至少要 $need 字$note。"))
            }
            if (gate.verify == VerifyMode.TEXT) {
                return VerifyOutcome.Done(Verdict(true, "字数", "字数达标（$chars 字）。"))
            }
            if (gate.verify == VerifyMode.AI && !config.ai.ready) {
                return VerifyOutcome.Done(Verdict(true, "字数（未配置 AI）", "还没配置 AI，按加倍字数验收通过（$chars 字）。配置 AI 后会按标准逐条检查。"))
            }
        }

        var notionPart = ""
        if (gate.verify == VerifyMode.NOTION || gate.verify == VerifyMode.NOTION_AI) {
            if (gate.notionDb.isBlank()) {
                return VerifyOutcome.Unavailable("这道关卡还没设置 Notion 数据库", canDegrade = true)
            }
            val since = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                java.time.Instant.ofEpochMilli(DayClock.startOf(date, zone)).atZone(zone),
            )
            val pages = when (val r = notion.createdSince(gate.notionDb, since)) {
                is ApiResult.Err -> return VerifyOutcome.Unavailable(r.message, canDegrade = true)
                is ApiResult.Ok -> r.value
            }
            val titles = pages.joinToString("、") { "「${it.title}」" }
            if (pages.size < gate.notionMinPages) {
                val have = if (pages.isEmpty()) "今天还没有新建条目" else "今天新建了 ${pages.size} 条：$titles"
                return VerifyOutcome.Done(
                    Verdict(false, "Notion", "$have，还差 ${gate.notionMinPages - pages.size} 条（至少 ${gate.notionMinPages} 条）。"),
                )
            }
            if (gate.verify == VerifyMode.NOTION || !config.ai.ready) {
                return VerifyOutcome.Done(Verdict(true, "Notion", "今天新建了 ${pages.size} 条：$titles"))
            }
            notionPart = buildString {
                append("Notion 里今天新建的 ${pages.size} 条：\n")
                var budget = 3000
                for (p in pages.take(8)) {
                    append("— ${p.title}\n")
                    if (budget <= 0) continue
                    val body = (notion.pageText(p.id, budget) as? ApiResult.Ok)?.value.orEmpty()
                    if (body.isNotBlank()) {
                        append(body).append('\n')
                        budget -= body.length
                    }
                }
            }
        }

        val reply = when (val r = llm.complete(SYSTEM, userPrompt(gate, text, date, facts, notionPart))) {
            is ApiResult.Err -> return VerifyOutcome.Unavailable(r.message, canDegrade = true)
            is ApiResult.Ok -> r.value
        }
        val verdict = parseVerdict(reply)
            ?: return VerifyOutcome.Unavailable("AI 返回的不是约定格式：${reply.take(80)}", canDegrade = true)
        return VerifyOutcome.Done(verdict)
    }

    companion object {
        const val PASS_SCORE = 60

        val SYSTEM = """
            你是「总控」的验收官。用户把自己的事务分给四个部门（信息收集部、谋划思考部、统筹规划部、行为管理部），每天要向总控交汇报，你负责验收。
            规矩：
            1. 只依据给出的验收标准和事实判断。不客套，不放水，也不吹毛求疵；表达粗糙但内容实在的，算达标。
            2. 照抄模板、空话套话（“好好学习”“尽量”“争取”）、和今天的事实对不上的，不通过。
            3. 反馈要短、要具体：直接说哪一条没做到、怎么改一句就能过。用中文，称呼用“你”。
            只输出一个 JSON 对象，不要输出别的：
            {"pass": true 或 false, "score": 0-100 的整数, "feedback": "一两句话", "missing": ["没达到的标准，简述"]}
            所有标准基本做到且 score ≥ 60 才算 pass。
        """.trimIndent()

        fun userPrompt(gate: Gate, text: String, date: LocalDate, facts: String, notionPart: String): String = buildString {
            append("关卡：${gate.dept.label}「${gate.title}」\n")
            append("要求：${gate.instruction}\n")
            val rubric = gate.rubric.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rubric.isNotEmpty()) {
                append("验收标准：\n")
                rubric.forEachIndexed { i, r -> append("${i + 1}. $r\n") }
            } else {
                append("验收标准：内容具体、真实、能看出认真做了。\n")
            }
            if (gate.dept == Dept.THINK) {
                val m = Defaults.methodOf(date)
                append("今天的方法卡：${m.name}——${m.how}\n")
            }
            if (facts.isNotBlank()) append("\n今天的事实（系统记录，比用户的说法可靠）：\n$facts\n")
            if (notionPart.isNotBlank()) append("\n$notionPart\n")
            append("\n用户提交的汇报：\n<<<\n${text.trim().ifEmpty { "（没有文字说明）" }}\n>>>")
        }

        fun parseVerdict(reply: String): Verdict? {
            val obj = LlmClient.extractJson(reply) ?: return null
            val passRaw = obj["pass"]?.jsonPrimitive ?: return null
            val pass = passRaw.booleanOrNull ?: passRaw.contentOrNull?.let { it == "true" || it == "通过" } ?: return null
            val score = obj["score"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
            val feedback = obj["feedback"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val missing = (obj["missing"] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.filter { it.isNotBlank() }.orEmpty()
            val ok = pass && (score == null || score >= PASS_SCORE)
            val fb = feedback.ifBlank { if (ok) "通过。" else "没通过。" }
            return Verdict(ok, "AI", fb, score, missing)
        }

        /** AI / Notion 不可用时的降级验收：字数翻倍。 */
        fun degraded(gate: Gate, text: String, date: LocalDate, reason: String): Verdict {
            val chars = TextCheck.effectiveChars(text, Defaults.fillTemplate(gate.template, date))
            val need = maxOf(gate.minChars, 30) * 2
            return if (chars >= need && !TextCheck.looksPadded(text)) {
                Verdict(true, "降级验收", "（$reason）按加倍字数验收通过（$chars 字）。", degraded = true)
            } else {
                Verdict(false, "降级验收", "降级验收要求去掉模板后至少 $need 字，现在 $chars 字。", degraded = true)
            }
        }
    }
}

/** 给 AI 看的“今天的事实”。 */
object Facts {
    fun build(status: Status, day: DayLog, zone: ZoneId): String = buildString {
        fun t(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().toString().take(5)
        append("现在 ${t(status.now)}\n")
        status.gates.forEach { g ->
            append("· ${g.gate.dept.label}「${g.gate.title}」（${DayClock.hhmm(g.gate.deadline)} 截止）：${g.phase.label}")
            if (g.attempts > 0 && !g.done) append("，交过 ${g.attempts} 次没通过")
            append('\n')
        }
        day.passed("plan_morning")?.let { append("晨间部署原文：\n${it.text.trim().take(800)}\n") }
        append("娱乐应用用时 ${day.playMinutes} 分钟")
        if (status.quotaMin > 0) append("（上限 ${status.quotaMin}）")
        append("，被弹回总控 ${day.bounces} 次，紧急放行 ${day.emergencies.size} 次")
        val offlineMin = day.offline.sumOf { (it.to - it.from) / 60_000 }
        if (offlineMin > 0) append("，总控失联 $offlineMin 分钟")
        append('\n')
        val checkins = day.reports.filter { it.kind == ReportKind.CHECKIN }
        if (checkins.isNotEmpty()) {
            append("今天的报到：\n")
            checkins.takeLast(8).forEach { append("· ${t(it.at)} ${it.text.take(60)}\n") }
        }
    }
}
