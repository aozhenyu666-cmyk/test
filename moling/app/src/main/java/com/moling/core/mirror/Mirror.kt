package com.moling.core.mirror

import com.moling.core.mind.Tags
import kotlinx.serialization.Serializable

/*
 * 贯通（v6）：墨灵和 GPT / Notion AI 通过 Notion 互相看得见。
 *
 * 为什么走 Notion：ChatGPT 和 Notion AI 都不让外面的程序直接跟它们聊，也读不到它们的聊天记录；
 * 但它们都能读写 Notion。所以墨灵在 Notion 里放一块“黑板”（她知道的、她想问的），
 * GPT / Notion AI 读黑板、把回答写进“收件箱”，墨灵每 15 分钟来收一次。
 *
 * 规矩（和体系图一样）：别的 AI 写来的只进收件箱；墨灵检查过才收进体系图；
 * 要改“对你的了解”一律变成提议，他点“好”才算数。墨灵自己从不往收件箱里写。
 */

/** 她想问 GPT（或 Notion AI）的一个问题。 */
@Serializable
data class Question(
    val id: String,
    val text: String,
    val why: String = "",
    val at: Long = 0,
    val answer: String = "",
    val answeredAt: Long = 0,
    /** 谁提的：她 / 你。 */
    val by: String = "她",
)

/**
 * 收件箱（或他放在根页面下的笔记）里的一条。[id] 用来记“收过了”；
 * 几个连着的块合成一条时，其余块的 id 放在 [also]（一起记成收过）。
 */
data class InboxItem(val id: String, val text: String, val source: String, val also: List<String> = emptyList())

/** 黑板上的一段。 */
data class Section(val title: String, val lines: List<String>)

/** 收一批收件箱的结果。 */
data class Absorbed(
    val answered: List<String> = emptyList(),
    val added: Int = 0,
    val aboutProposal: Boolean = false,
    val notes: List<String> = emptyList(),
)

object Mirror {
    const val BOARD_TITLE = "墨灵黑板"
    const val INBOX_TITLE = "给墨灵的收件箱"
    const val OPEN_MAX = 5

    /** 给 GPT / Notion AI 的说明：黑板第一段，也是设置页里让他复制去说的那句话。 */
    fun guide(callUser: String) =
        "这是墨灵（$callUser 手机上的 AI 伙伴）的黑板：她知道的、她这几天的判断、她想问你的问题。" +
            "请读完后回答「墨灵想问你的」里的问题（每个问题一段，开头写编号，比如 Q3：……），" +
            "也可以写你对$callUser 的观察、对体系框架的修正。写到《$INBOX_TITLE》页面里。" +
            "只写你有把握的；猜测请标“猜测”。不要修改这块黑板，它会被墨灵整块重写。"

    /** 一句话：他在 ChatGPT / Notion AI 里说这句，对方就知道去哪读、往哪写。 */
    fun askLine(callUser: String) =
        "请在 Notion 里打开《$BOARD_TITLE》，按第一段的说明回答墨灵的问题，把回答写进《$INBOX_TITLE》。"

    fun open(qs: List<Question>) = qs.filter { it.answer.isBlank() }

    /** 黑板（纯数据，App 再翻成 Notion 的块）。每段最多 12 行，每行最多 300 字。 */
    fun board(
        callUser: String,
        now: String,
        questions: List<Question>,
        about: String,
        today: String,
        misjudged: List<String>,
        days: List<String>,
        atlas: List<String>,
        said: List<String>,
        thoughts: List<String> = emptyList(),
    ): List<Section> {
        fun cap(l: List<String>, n: Int = 12) = l.filter { it.isNotBlank() }.takeLast(n).map { it.take(300) }
        val open = open(questions)
        val done = questions.filter { it.answer.isNotBlank() }.sortedBy { it.answeredAt }
        return listOf(
            Section("给 GPT / Notion AI 的说明", listOf(guide(callUser), "更新于 $now")),
            Section("墨灵想问你的", cap(open.map { "${it.id}：${it.text}" + if (it.why.isNotBlank()) "（为什么问：${it.why}）" else "" }).ifEmpty { listOf("（现在没有）") }),
            Section("她对$callUser 的了解", cap(about.lines(), 20).ifEmpty { listOf("（还没写）") }),
            Section("今天", cap(today.lines()).ifEmpty { listOf("（今天还没排）") }),
            Section("这几天判断上的错", cap(misjudged).ifEmpty { listOf("（没有记下的）") }),
            Section("最近几天", cap(days, 7)),
            Section("体系图（论断 · 把握）", cap(atlas, 16)),
            Section("她最近自己推的判断（未必对，欢迎指正）", cap(thoughts, 6)),
            Section("$callUser 最近说的要紧话", cap(said, 8)),
            Section("问过的和收到的回答", cap(done.map { "${it.id}：${it.text.take(60)} → ${it.answer.take(160)}" }, 6)),
        ).filter { it.lines.isNotEmpty() }
    }

    /** 黑板内容的指纹：没变就不重写（Notion 有频率限制）。 */
    fun hash(sections: List<Section>): String =
        sections.joinToString("\n") { s -> s.title + "\n" + s.lines.filterNot { it.startsWith("更新于") }.joinToString("\n") }
            .hashCode().toString(16)

    /** 还没收过的、有内容的条目（一次最多 [max] 条，免得一下花太多）。 */
    fun unseen(items: List<InboxItem>, seen: Collection<String>, max: Int = 6): List<InboxItem> =
        items.filter { it.id !in seen && it.text.isNotBlank() }.take(max)

    /** 收件箱一批 → 让模型分拣：回答了哪个问题、拆出的论断、要不要改“了解”、提醒他的事。 */
    fun absorbPrompt(
        callUser: String,
        topic: String,
        existing: List<String>,
        open: List<Question>,
        about: String,
        items: List<InboxItem>,
    ) = buildString {
        appendLine("下面是 GPT / Notion AI / $callUser 写进收件箱（或 Notion 笔记）的东西。你是墨灵，替$callUser 把关后再收。")
        if (open.isNotEmpty()) appendLine("【你问过还没回答的】\n" + open.joinToString("\n") { "${it.id}：${it.text}" })
        if (about.isNotBlank()) appendLine("【你对他的了解】\n${about.take(800)}")
        appendLine("【体系图当前主题】$topic")
        if (existing.isNotEmpty()) appendLine("【已有论断】（引用时写编号）\n" + existing.takeLast(20).joinToString("\n"))
        items.forEach { appendLine("【材料·${it.source}】\n${it.text.take(2500)}") }
        appendLine()
        appendLine("要求：别人说的不当事实，你要判断；空话、客套、重复的不收。只输出需要的行：")
        appendLine("【回答】Q编号｜一句话概括回答（材料回答了你的问题时写）")
        appendLine("【论断】n1｜判断｜内容｜把握（高/中/低）｜验证方式（没有写无）——类型只用 事实/判断/假设/问题/方法，最多 8 条")
        appendLine("【关系】n1｜导致｜n2｜一句说明——关系只用 属于/导致/支撑/反例/对立/前置/类似")
        appendLine("【了解】一句应该加进“对他的了解”的话（真的重要才写，他确认后才生效）")
        append("【提醒】一句要马上告诉他的话（没有就不写）")
    }

    data class Sorted(val answers: Map<String, String>, val about: String, val notes: List<String>)

    fun parseAbsorb(reply: String): Sorted {
        val answers = Regex("""【回答】\s*(Q\d+)\s*[｜|]\s*([^\n]+)""").findAll(reply)
            .associate { it.groupValues[1] to it.groupValues[2].trim().take(300) }
        val t = Tags.parse(reply)
        val notes = Regex("""【提醒】([^\n]+)""").findAll(reply).map { it.groupValues[1].trim().take(120) }.filter { it.isNotBlank() }.toList()
        return Sorted(answers, t["了解"]?.trim()?.take(120).orEmpty(), notes)
    }

    /** 她自己想问 GPT 的（夜里或他点“想想要问什么”时）：从缺口、错判、了解里挑最值得问的。 */
    fun questionsPrompt(callUser: String, about: String, misjudged: List<String>, gaps: List<String>, asked: List<String>, n: Int) = buildString {
        appendLine("你是墨灵，$callUser 的伙伴。GPT 和 $callUser 聊得多、知道很多你看不到的；你可以通过 Notion 黑板问它。")
        if (about.isNotBlank()) appendLine("【你对他的了解】\n${about.take(800)}")
        if (misjudged.isNotEmpty()) appendLine("【这几天判断上的错】\n" + misjudged.joinToString("\n") { "- $it" })
        if (gaps.isNotEmpty()) appendLine("【体系图的缺口】\n" + gaps.joinToString("\n") { "- $it" })
        if (asked.isNotEmpty()) appendLine("【已经问过的】\n" + asked.joinToString("\n") { "- $it" })
        appendLine()
        appendLine("想 $n 个最值得问 GPT 的问题：能补上你看不到的（他跟 GPT 聊过的想法、计划、卡点），或能检验你的判断。")
        appendLine("一个问题一句话，具体、能回答，不要问“你怎么看”。只输出：")
        append("【问题】问题｜为什么问")
    }

    fun parseQuestions(reply: String): List<Pair<String, String>> =
        Regex("""【问题】([^\n]+)""").findAll(reply).mapNotNull { m ->
            val f = m.groupValues[1].split('｜', '|').map { it.trim() }
            val q = f.getOrNull(0).orEmpty().take(120)
            if (q.length < 4) null else q to f.getOrNull(1).orEmpty().take(60)
        }.toList()

    /** 从 Notion 的页面链接（或直接贴的 id）里取出页面 id（32 位十六进制，带不带横线都行）。 */
    fun pageId(link: String): String? {
        val t = link.trim().substringBefore('?').substringBefore('#')
        // 链接里标题和 id 连在一起（“标题-32位id”），去掉横线后取最后一段十六进制的末尾 32 位
        val hex = Regex("""[0-9a-fA-F]{32,}""").findAll(t.replace("-", "")).lastOrNull()?.value?.takeLast(32) ?: return null
        return hex.lowercase().let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
    }
}
