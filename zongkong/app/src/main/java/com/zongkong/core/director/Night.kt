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

/** 开场白和这一场的问题（本地版）。 */
object Openings {
    fun text(cfg: DirectorConfig, actor: Actor, s: Situation, baton: Baton?, goal: String, minutes: Int, diary: DiaryEntry?): String = buildString {
        append("现在开始。请你扮演墨灵：").append(cfg.persona.removePrefix("你是墨灵，")).append("\n")
        val f = s.facts
        append("我现在的情况：")
        append(if (f.mainTitle.isNotBlank()) "今天的主线是「${f.mainTitle}」" else "今天还没定主线")
        if (s.mainLateMin > 0) append("，过点 ${s.mainLateMin} 分钟还没开始")
        append("；今天做事 ${f.workMinutesToday} 分钟、娱乐 ${f.playMinutesToday} 分钟。\n")
        append("这次只聊一件事：$goal。")
        if (baton != null && baton.nextAsk.isNotBlank()) append("上次停在：${baton.nextAsk}。")
        if (baton != null && baton.stuck.isNotBlank()) append("卡在：${baton.stuck}。")
        append("\n规矩：别替我总结，先让我讲；我讲完你只指出一处漏掉的地方；一次只问一个问题；我开始说废话就把我拉回来。")
        append("$minutes 分钟后问我：一句话聊出了什么、下一步几点做。")
        if (diary != null) append("\n（你昨天的日记：${diary.text.take(80)}）")
        if (actor == Actor.CHATGPT) append("\n收线时用这个格式输出一行：收线｜结论｜下一步｜时间。如果你能写我的 Notion，把它加进“墨灵·收件箱”。")
    }

    fun goal(card: Card?, baton: Baton?, f: Facts): String = when {
        baton != null && (card == null || card.batonId == baton.id) && baton.question.isNotBlank() -> baton.question
        f.mainTitle.isNotBlank() && !f.mainDone -> "「${f.mainTitle}」怎么开始"
        else -> "接下来这段时间怎么用"
    }

    fun localCards(sessionId: String, actor: Actor, goal: String, baton: Baton?, minutes: Int, now: Long): List<Card> {
        val first = baton?.nextAsk?.takeIf { it.isNotBlank() } ?: "$goal，卡在哪一步？"
        val say = listOf("先别给我答案，问我卡在哪。", "我说完你只指出一处我漏掉的地方。")
        return listOf(
            Card("$sessionId-1", Mode.WORK, "第一问", first, say, minutes = minutes, actor = actor, batonId = baton?.id.orEmpty(), createdAt = now),
            Card("$sessionId-2", Mode.WORK, "第二问", "最小的第一步是什么？多久能做完？", listOf("帮我把这一步再拆小一点。"), actor = actor, batonId = baton?.id.orEmpty(), createdAt = now),
            Card("$sessionId-3", Mode.WORK, "第三问", "做完这一步，怎么知道做对了？", listOf("你来扮演检查的人，挑我的毛病。"), actor = actor, batonId = baton?.id.orEmpty(), createdAt = now),
            Card("$sessionId-close", Mode.WORK, "收线", "一句话，聊出了什么？下一步几点做？", actor = actor, batonId = baton?.id.orEmpty(), createdAt = now),
        )
    }
}
