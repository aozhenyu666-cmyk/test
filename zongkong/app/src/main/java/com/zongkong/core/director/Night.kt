package com.zongkong.core.director

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 睡前整理（MIND.md 1.3）：把一天压成日记，从重复出现的事里找模式，给旧记忆降把握，
 * 发现矛盾就排进第二天的议程。纯函数部分在这里，调用模型的部分在 [DirectorEngine]。
 */
object Night {
    private const val DAY = 24 * 3_600_000L

    /** 一天的摘要：给模型写日记用，也作为本地兜底的情景记忆。 */
    fun digest(m: MindState, day: String): String = buildString {
        val senses = m.recent.filter { dayKey(it.at) == day }
        val byKind = senses.groupingBy { it.kind }.eachCount()
        if (byKind.isNotEmpty()) appendLine("事件：" + byKind.entries.joinToString("，") { "${it.key.name.lowercase()} ${it.value}" })
        val pushes = if (m.pushesDay == day) m.pushesToday else 0
        appendLine("我主动找他：$pushes 次")
        val sessions = m.sessions.filter { dayKey(it.startedAt) == day }
        if (sessions.isNotEmpty()) {
            appendLine("去演员那里聊：${sessions.size} 次，收线 ${sessions.count { it.conclusion.isNotBlank() }} 次")
            sessions.filter { it.conclusion.isNotBlank() }.forEach { appendLine("  结论：${it.conclusion.take(80)}") }
        }
        val batons = m.batons.filter { dayKey(it.updatedAt) == day }
        batons.forEach { appendLine("接力棒：${it.question}｜已知：${it.known.take(60)}｜下一问：${it.nextAsk.take(40)}") }
        val hs = m.hypotheses.filter { dayKey(it.at) == day }
        hs.forEach { appendLine("猜测：${it.guess} → ${it.outcome}${if (it.outcomeNote.isNotBlank()) "（${it.outcomeNote}）" else ""}") }
        val inc = m.incidents.filter { dayKey(it.at) == day }
        if (inc.isNotEmpty()) appendLine("系统的失败：" + inc.groupingBy { it.kind }.eachCount().entries.joinToString("，") { "${it.key} ${it.value}" })
        val fus = m.followUps.filter { dayKey(it.dueAt) == day }
        fus.forEach { appendLine("约好的：${it.what} → ${if (it.done) "有结果" else "还没回头看"}") }
    }.trim().ifBlank { "今天没有记录。" }

    /** 本地兜底日记（模型不可用时）。 */
    fun localDiary(m: MindState, day: String): String {
        val pushes = if (m.pushesDay == day) m.pushesToday else 0
        val sessions = m.sessions.filter { dayKey(it.startedAt) == day }
        val closed = sessions.count { it.conclusion.isNotBlank() }
        val wrong = m.hypotheses.count { dayKey(it.at) == day && it.outcome == "错了" }
        val b = m.baton
        return buildString {
            append("今天我找了你 $pushes 次")
            if (sessions.isNotEmpty()) append("，我们去聊了 ${sessions.size} 次，收线 $closed 次")
            append("。")
            if (wrong > 0) append("我猜错了 $wrong 次，记下了。")
            if (b != null) append("明天想从「${b.nextAsk.ifBlank { b.question }}」接着来。")
        }
    }

    /** 更新累计：今天出现过的“现象”各记一笔日期。 */
    fun tally(m: MindState, day: String): Map<String, List<String>> {
        val keys = mutableSetOf<String>()
        m.hypotheses.filter { dayKey(it.at) == day && it.outcome == "对了" }.forEach { keys += "卡住@${bucket(hourOf(it.at))}" }
        m.incidents.filter { dayKey(it.at) == day }.forEach { keys += "事故:${it.kind}" }
        m.recent.filter { dayKey(it.at) == day && it.kind == SenseKind.LONG_ENTERTAINMENT }.forEach { keys += "长时间娱乐@${bucket(hourOf(it.at))}" }
        val out = m.tallies.toMutableMap()
        keys.forEach { k -> out[k] = ((out[k] ?: emptyList()) + day).distinct().takeLast(14) }
        return out
    }

    /** 14 天里出现 3 天以上的现象升级成“模式”记忆（把握：3–4 天中，5 天以上高）。 */
    fun patterns(m: MindState, tallies: Map<String, List<String>>, today: String, now: Long): List<MemoryItem> {
        val since = LocalDate.parse(today).minusDays(13).toString()
        val out = m.memories.toMutableList()
        tallies.forEach { (k, days) ->
            val n = days.count { it >= since }
            if (n < 3) return@forEach
            val key = "pattern:$k"
            val text = describe(k, n)
            val conf = if (n >= 5) "高" else "中"
            val i = out.indexOfFirst { it.key == key }
            if (i >= 0) {
                out[i] = out[i].copy(text = text, confidence = conf, lastSeenAt = now, hits = n)
            } else {
                out += MemoryItem("m-$now-${out.size}", MemoryKind.PATTERN, key, text, "导演", conf, false, now, now, n)
            }
        }
        return out
    }

    /** 很久没被用到、也没被确认的记忆降一级把握；低把握又过了 60 天就忘掉。用户确认过的不动。 */
    fun decay(items: List<MemoryItem>, now: Long): List<MemoryItem> = items.mapNotNull { it ->
        if (it.confirmed) return@mapNotNull it
        val idle = now - it.lastSeenAt
        when {
            it.confidence == "低" && idle > 60 * DAY -> null
            idle > 30 * DAY -> it.copy(confidence = if (it.confidence == "高") "中" else "低", lastSeenAt = now - 25 * DAY)
            else -> it
        }
    }

    /** 同一个 key 有两条说法不一样、都没被确认的记忆 → 需要问用户。 */
    fun conflicts(items: List<MemoryItem>): List<Pair<MemoryItem, MemoryItem>> =
        items.filter { !it.confirmed && it.kind != MemoryKind.PATTERN }
            .groupBy { it.key }
            .values
            .filter { g -> g.map { it.text }.distinct().size >= 2 }
            .map { g -> g.sortedBy { it.createdAt }.let { it.first() to it.last() } }

    fun isWeeklyReviewDay(day: String): Boolean = LocalDate.parse(day).dayOfWeek == DayOfWeek.SUNDAY

    /** 一周的系统失败摘要（给每周复盘用）。 */
    fun weekDigest(m: MindState, day: String): String {
        val since = LocalDate.parse(day).minusDays(6).toString()
        val inc = m.incidents.filter { dayKey(it.at) >= since }
        val hs = m.hypotheses.filter { dayKey(it.at) >= since }
        val dismissed = inc.count { it.kind == "被划掉" }
        return buildString {
            appendLine("事故：" + inc.groupingBy { it.kind }.eachCount().entries.joinToString("，") { "${it.key} ${it.value} 次" }.ifBlank { "无" })
            appendLine("被划掉 / 没回应：$dismissed 次，时段：" + inc.filter { it.kind == "被划掉" }.groupingBy { bucket(hourOf(it.at)) }.eachCount().entries.joinToString("，") { "${it.key} ${it.value}" }.ifBlank { "无" })
            appendLine("猜测：对了 ${hs.count { it.outcome == "对了" }}，错了 ${hs.count { it.outcome == "错了" }}，待验证 ${hs.count { it.outcome == "待验证" }}")
            hs.filter { it.outcome == "错了" && it.outcomeNote.isNotBlank() }.take(5).forEach { appendLine("  猜错：${it.guess} → 实际：${it.outcomeNote}") }
            val sessions = m.sessions.filter { dayKey(it.startedAt) >= since }
            appendLine("去聊 ${sessions.size} 次，收线 ${sessions.count { it.conclusion.isNotBlank() }} 次，交接失败 ${sessions.count { !it.handoffOk }} 次")
            val fus = m.followUps.filter { dayKey(it.dueAt) >= since }
            appendLine("约好的下一步 ${fus.size} 个，回头看过 ${fus.count { it.done }} 个")
        }.trim()
    }

    fun bucket(h: Int) = when (h) {
        in 5..11 -> "上午"
        in 12..17 -> "下午"
        in 18..22 -> "晚上"
        else -> "深夜"
    }

    private fun describe(k: String, n: Int): String {
        val (what, whenText) = k.split('@').let { it[0] to it.getOrNull(1) }
        return when {
            what == "卡住" -> "${whenText}容易卡住（14 天里 $n 天）"
            what == "长时间娱乐" -> "${whenText}容易长时间娱乐（14 天里 $n 天）"
            what.startsWith("事故:") -> "系统的“${what.removePrefix("事故:")}”反复出现（14 天里 $n 天）"
            else -> "$k（14 天里 $n 天）"
        }
    }
}

/** 一场会话的准备：目标、三问、可以直接念的话、收线要带回什么。 */
data class SessionPlan(
    val goal: String,
    val asks: List<String>,
    val says: List<String>,
    val closing: String = "一句话结论 / 下一步 / 几点做",
)

/**
 * 开场白和这一场的问题。
 *
 * v4 的规矩：**没有三问就不许打开演员**。三问从知识库（还说不清的那条 + 管用的问法）+ 接力棒 + 这一场的目标里长出来；
 * 本地就有，模型只负责润色。之前那版只会说“去和 Gemini 聊十分钟，第一问我准备好了”，是第一问根本没有内容。
 */
object Openings {
    fun plan(kb: TopicNode?, baton: Baton?, goal: String, talkMin: Int): SessionPlan {
        val asks = mutableListOf<String>()
        // ① 定位：先找知识库里“还说不清”的那条，其次是上次的卡点
        val open = kb?.open?.firstOrNull { it.isNotBlank() }
        val stuck = baton?.stuck?.takeIf { it.isNotBlank() }
        asks += when {
            !open.isNullOrBlank() -> "「${open.take(40)}」这句里，你最没把握的是哪一处？先说那一处。"
            stuck != null -> "上次卡在「${stuck.take(30)}」。先把这一层拆开：它具体难在哪一步？"
            else -> "$goal：你是不知道从哪下手，还是做了一半卡住？只答这一句。"
        }
        // ② 动手：优先复用知识库里被证明管用的问法
        asks += kb?.goodAsks?.firstOrNull { it.isNotBlank() && it.length in 6..60 }
            ?: "把它拆成 ${talkMin.coerceAtMost(10)} 分钟能做完的一步。那一步是什么？只说动作。"
        // ③ 检验
        asks += if (kb != null && kb.title.isNotBlank()) {
            "用「${kb.title}」的标准反过来检查：刚才那一步，判卷的人会挑哪一处？"
        } else {
            "做完怎么知道对了？给我一个能检查的证据。"
        }

        val says = mutableListOf("先别给我答案，也别总结我；一次只问我一个问题。")
        val known = kb?.known?.lastOrNull { it.isNotBlank() }
        says += if (known != null) "我上次已经弄明白了：${known.take(30)}，从这里往上接着问。" else "我讲完你只指出一处我漏掉的，别替我补全。"
        return SessionPlan(goal = goal, asks = asks, says = says)
    }

    fun text(
        cfg: DirectorConfig,
        actor: Actor,
        s: Situation,
        baton: Baton?,
        plan: SessionPlan,
        minutes: Int,
        diary: DiaryEntry?,
        kb: TopicNode? = null,
    ): String = buildString {
        append("现在开始。请你扮演墨灵：").append(cfg.persona.removePrefix("你是墨灵，")).append("\n")
        val f = s.facts
        append("我现在的情况：")
        append(if (f.mainTitle.isNotBlank()) "今天的主线是「${f.mainTitle}」" else "今天还没定主线")
        if (s.mainLateMin > 0) append("，过点 ${s.mainLateMin} 分钟还没开始")
        append("；今天做事 ${f.workMinutesToday} 分钟、娱乐 ${f.playMinutesToday} 分钟。\n")
        append("这次只聊一件事：${plan.goal}。")
        if (kb != null) {
            append("\n我正在攻的是「${kb.title}」")
            if (kb.goal.isNotBlank()) append("，要做到：${kb.goal}")
            append("。")
            if (kb.known.isNotEmpty()) append("已经弄明白的：${kb.known.takeLast(2).joinToString("；")}。")
            if (kb.open.isNotEmpty()) append("还没说清的：${kb.open.takeLast(2).joinToString("；")}。")
        }
        if (baton != null && baton.nextAsk.isNotBlank()) append("\n上次停在：${baton.nextAsk}。")
        if (baton != null && baton.stuck.isNotBlank()) append("卡在：${baton.stuck}。")
        append("\n请你按这三问的顺序带我走，一次只问一个：")
        plan.asks.forEachIndexed { i, q -> append("\n${i + 1}. $q") }
        append("\n规矩：别替我总结、别一次问两个；我讲完你只指出一处漏掉的地方；我说废话就把我拉回来。")
        append("$minutes 分钟后问我：一句话聊出了什么、下一步几点做。")
        if (diary != null) append("\n（你昨天的日记：${diary.text.take(80)}）")
        if (actor == Actor.CHATGPT) append("\n收线时用这个格式输出一行：收线｜结论｜下一步｜时间。如果你能写我的 Notion，把它加进“墨灵·收件箱”。")
    }

    fun goal(card: Card?, baton: Baton?, f: Facts): String = when {
        baton != null && (card == null || card.batonId == baton.id) && baton.question.isNotBlank() -> baton.question
        f.mainTitle.isNotBlank() && !f.mainDone -> "「${f.mainTitle}」怎么开始"
        else -> "接下来这段时间怎么用"
    }

    fun localCards(
        sessionId: String,
        actor: Actor,
        plan: SessionPlan,
        minutes: Int,
        now: Long,
        batonId: String = "",
    ): List<Card> {
        val asks = plan.asks.take(3)
        val titles = listOf("第一问 · 定位", "第二问 · 动手", "第三问 · 检验")
        val cards = asks.mapIndexed { i, q ->
            Card(
                id = "$sessionId-${i + 1}",
                mode = Mode.WORK,
                title = titles.getOrElse(i) { "第 ${i + 1} 问" },
                ask = q,
                sayLines = if (i == 0) plan.says else listOf(plan.says.getOrNull(1) ?: "接着刚才那一步，再往下问一层。"),
                minutes = if (i == 0) minutes else 0,
                actor = actor,
                batonId = batonId,
                createdAt = now,
            )
        }.toMutableList()
        cards += Card(
            "$sessionId-close", Mode.WORK, "收线",
            "一句话，聊出了什么？下一步几点做？", actor = actor, batonId = batonId, createdAt = now,
        )
        return cards
    }
}

/**
 * 夜里把当天的东西收进知识库（v4）。
 *
 * 只靠“收线”太慢：白天聊出来的结论、没说清的卡点、还没验证的猜测，夜里都该变成知识。
 * 这里全用本地判断，不花模型的钱；模型只负责写日记。
 */
fun harvestKnowledge(mind: MindState, day: String, now: Long, titleHint: String): List<TopicNode> {
    var list = mind.topicsKB
    fun put(n: TopicNode) { list = Knowledge.upsert(list, n) }
    fun base(): TopicNode {
        val cur = list.firstOrNull { it.id == mind.activeTopicId } ?: list.maxByOrNull { it.lastAt }
        if (cur != null) return cur
        val title = titleHint.trim().ifBlank { mind.baton?.question.orEmpty().trim() }.ifBlank { "今天在攻的事" }
        val n = Knowledge.ensure(list, title, mind.baton?.question.orEmpty(), now)
        put(n)
        return n
    }
    // 今天收过线的会话：结论进“已知”
    mind.sessions.filter { dayKey(it.endedAt) == day }.forEach { s ->
        if (s.conclusion.isNotBlank()) {
            put(Knowledge.learn(base(), s.conclusion, "", "", s.endedAt))
        }
    }
    // 接力棒上的卡点、还没验证的猜测：进“还说不清”
    val b = mind.baton
    if (b != null && b.stuck.isNotBlank()) put(Knowledge.learn(base(), "", b.stuck, "", now))
    mind.hypotheses.filter { it.outcome == "待验证" && it.guess.isNotBlank() }.forEach { h ->
        put(Knowledge.learn(base(), "", h.guess.take(60), "", h.at))
    }
    return list
}
