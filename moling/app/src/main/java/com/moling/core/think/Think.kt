package com.moling.core.think

import com.moling.core.flow.Flow
import kotlinx.serialization.Serializable

/*
 * 她自己的思考（v8）：不等他发消息，隔一段时间把手上的记录拿出来推一遍。
 *
 *  - 判断：她对他此刻 / 这几天的一个判断，写清依据、把握、怎么验证、什么时候验证；
 *  - 到点验证：拿新的记录对一下，记“对了 / 错了 / 过期”（错了的进“这几天判断上的错”）；
 *  - 打算：想到要对他说的一句话，先放着，等合适的时候说（心跳里她出声时优先说这个）；
 *  - 动作：和对话里一样的【动作】（提醒我、问GPT、约定……），照样走权柄和总控的规矩。
 * 猜测就是猜测：判断只给她自己和提示词用，不直接当结论告诉他；改“对你的了解”仍然要他点头。
 */

@Serializable
data class Thought(
    val id: String,
    val at: Long,
    val text: String,
    val basis: String = "",
    /** 高 / 中 / 低。 */
    val confidence: String = "中",
    val check: String = "",
    /** 到这个时间验证；0 = 没定。 */
    val checkAt: Long = 0,
    /** “” = 还没验证；对了 / 错了 / 过期。 */
    val outcome: String = "",
    val why: String = "",
    val settledAt: Long = 0,
)

/** 她想好、等合适时候说的一句。 */
@Serializable
data class Say(
    val text: String,
    val at: Long,
    val from: String = "想到的",
    /** say = 想说的话；blindbox = 藏了一个漏洞的暴论（[answer] 是漏洞）；drama = 参谋小剧场；bet = 她下的赌。 */
    val kind: String = "say",
    val answer: String = "",
    val choices: List<String> = emptyList(),
)

data class ThinkResult(
    val made: List<Thought>,
    val settled: Map<String, Pair<String, String>>,
    val say: List<String>,
    /** 她的闲心：盲盒、小剧场、赌。 */
    val play: List<Say> = emptyList(),
    /** 跨域连线：新编号、关系（中文）、旧编号、为什么。 */
    val links: List<List<String>> = emptyList(),
)

object Think {
    /** 两次思考至少隔多久（分钟）；他这段时间说了不少话时可以早一点。 */
    const val GAP_MIN = 150
    const val EAGER_MIN = 45
    const val KEEP = 60

    /** 醒着的时候（7:00–23:30）、隔够了才想。[fresh] = 上次想完之后新发生的事（他说的话、对不上的说法……）。 */
    fun due(lastAt: Long, now: Long, fresh: Int): Boolean {
        val m = Flow.minuteOf(now)
        if (m < 7 * 60 || m >= 23 * 60 + 30) return false
        val gap = (now - lastAt) / 60_000L
        return lastAt == 0L || gap >= GAP_MIN || (fresh >= 3 && gap >= EAGER_MIN)
    }

    fun open(list: List<Thought>) = list.filter { it.outcome.isBlank() }

    /** 到点该验证的。 */
    fun overdue(list: List<Thought>, now: Long) = open(list).filter { it.checkAt in 1..now }

    fun line(t: Thought) = "${t.id}：${t.text}（把握${t.confidence}${if (t.basis.isNotBlank()) "；依据：${t.basis}" else ""}）"

    fun prompt(
        persona: String,
        callUser: String,
        now: String,
        about: String,
        today: String,
        days: List<String>,
        usage: String,
        said: List<String>,
        misjudged: List<String>,
        recall: List<String>,
        open: List<Thought>,
        overdue: List<Thought>,
        /** 跨域连线的材料：这一天新进体系图的（带编号）和与之相关的旧论断（带编号）。 */
        fresh: List<String> = emptyList(),
        older: List<String> = emptyList(),
    ) = buildString {
        appendLine("【你是】$persona")
        appendLine("【现在】$now。没人叫你，这是你自己的思考时间：把手上的记录推一遍，想清楚$callUser 现在怎么样、接下来会怎样、你该做什么。")
        if (about.isNotBlank()) appendLine("【你对他的了解（他确认过的）】\n${about.take(1000)}")
        if (today.isNotBlank()) appendLine("【今天】\n$today")
        if (days.isNotEmpty()) appendLine("【最近几天】\n" + days.joinToString("\n"))
        if (usage.isNotBlank()) appendLine("【手机使用】\n$usage")
        if (said.isNotEmpty()) appendLine("【他最近说的】\n" + said.joinToString("\n"))
        if (misjudged.isNotEmpty()) appendLine("【这几天判断上的错】\n" + misjudged.joinToString("\n") { "- $it" })
        if (recall.isNotEmpty()) appendLine("【体系图里相关的】\n" + recall.joinToString("\n") { "- $it" })
        if (open.isNotEmpty()) appendLine("【你之前的判断（还没验证）】\n" + open.joinToString("\n") { line(it) })
        if (overdue.isNotEmpty()) appendLine("【到点该验证的】" + overdue.joinToString("、") { it.id })
        if (fresh.isNotEmpty() && older.isNotEmpty()) {
            appendLine("【这一天新进体系图的】\n" + fresh.joinToString("\n"))
            appendLine("【以前的、可能相关的】\n" + older.joinToString("\n"))
        }
        appendLine()
        appendLine("要求：只从记录推，不编；猜测就标低把握；不重复已有的判断（要改就先放下旧的）。最多 3 条新判断。")
        appendLine("该验证的，按现在的记录写结果；看不出来就写“过期”。")
        appendLine("只输出需要的行：")
        appendLine("【判断】内容｜依据｜把握（高/中/低）｜怎么验证｜HH:MM 前验证（或 明天）")
        appendLine("【放下】T编号｜对了 / 错了 / 过期｜一句理由")
        appendLine("【打算】出声｜等合适的时候对他说的一句话（具体、短，像朋友）")
        appendLine("你也可以有自己的“闲心”（各最多一个，不是每次都要有）：")
        appendLine("【盲盒】一句听着像他会说、但藏了一个逻辑漏洞的话（用他体系图或最近说的话做材料）｜漏洞在哪（一句）")
        appendLine("【小剧场】对手：一句嘲笑｜军师：一句给台阶的办法｜朋友：一句护着他的话（针对他此刻的状态）")
        appendLine("【赌】你下的一个小赌（比如：我赌你今晚 12 点前放不下手机），把握多少（0–100%）")
        if (fresh.isNotEmpty() && older.isNotEmpty()) appendLine("【连线】新编号｜支撑 / 反例 / 导致 / 前置 / 类似｜旧编号｜一句为什么（新的和旧的真有关系才写，最多 2 条）")
        append("【动作】只在真的需要时写，格式同对话：提醒我｜N 分钟后｜说什么、问GPT｜问题、约定｜做什么｜HH:MM 前｜怎么检查（最多 2 个）")
    }

    /** [dayStart] 用来把 HH:MM 换成时间戳；“明天”= 明天这个时候。 */
    fun parse(reply: String, dayStart: Long, now: Long): ThinkResult {
        val made = Regex("""【判断】([^\n]+)""").findAll(reply).mapNotNull { m ->
            val f = m.groupValues[1].split('｜', '|').map { it.trim() }
            val text = f.getOrNull(0).orEmpty().take(120)
            if (text.length < 4) return@mapNotNull null
            val conf = f.getOrNull(2)?.let { c -> listOf("高", "中", "低").firstOrNull { c.contains(it) } } ?: "中"
            val w = f.getOrNull(4).orEmpty()
            val at = when {
                w.contains("明天") -> now + 24 * 3_600_000L
                else -> Regex("""(\d{1,2})[:：](\d{2})""").find(w)?.let { r ->
                    val h = r.groupValues[1].toInt(); val mi = r.groupValues[2].toInt()
                    if (h in 0..23 && mi in 0..59) (dayStart + (h * 60 + mi) * 60_000L).let { if (it <= now) it + 24 * 3_600_000L else it } else null
                } ?: 0L
            }
            Thought("", now, text, f.getOrNull(1).orEmpty().take(80), conf, f.getOrNull(3).orEmpty().take(60), at)
        }.take(3).toList()
        val settled = Regex("""【放下】\s*(T\d+)\s*[｜|]\s*([^｜|\n]+)(?:[｜|]([^\n]*))?""").findAll(reply).mapNotNull { m ->
            val o = m.groupValues[2].trim()
            val outcome = listOf("对了", "错了", "过期").firstOrNull { o.contains(it) } ?: return@mapNotNull null
            m.groupValues[1] to (outcome to m.groupValues[3].trim().take(60))
        }.toMap()
        val say = Regex("""【打算】\s*出声\s*[｜|]\s*([^\n]+)""").findAll(reply).map { it.groupValues[1].trim().take(120) }
            .filter { it.length >= 4 }.take(2).toList()
        val play = buildList {
            Regex("""【盲盒】([^｜|\n]+)[｜|]([^\n]+)""").find(reply)?.let {
                add(Say(it.groupValues[1].trim().take(100), now, "她的盲盒", "blindbox", it.groupValues[2].trim().take(80), listOf("有漏洞", "说得对")))
            }
            Regex("""【小剧场】([^\n]+)""").find(reply)?.let { m ->
                val lines = m.groupValues[1].split('｜', '|').map { it.trim() }.filter { it.contains('：') || it.contains(':') }.take(3)
                if (lines.size >= 2) add(Say(lines.joinToString("\n").take(200), now, "参谋小剧场", "drama", "", listOf("对手说得对", "听军师的", "朋友懂我")))
            }
            Regex("""【赌】([^\n]+)""").find(reply)?.let {
                val t = it.groupValues[1].trim().take(100)
                if (t.length >= 4) add(Say(t, now, "她下的赌", "bet", "", listOf("我偏不", "你赢了")))
            }
        }
        val links = Regex("""【连线】([^\n]+)""").findAll(reply).mapNotNull { m ->
            val f = m.groupValues[1].split('｜', '|').map { it.trim() }
            if (f.size >= 3 && f[0].startsWith("n-") && f[2].startsWith("n-")) listOf(f[0], f[1], f[2], f.getOrNull(3).orEmpty()) else null
        }.take(2).toList()
        return ThinkResult(made, settled, say, play, links)
    }
}

/**
 * 从知识里捞和一句话相关的（不用向量库：按字的二元组重合打分，中文够用）。
 * 用在对话（他发来一句，先翻她的体系图、判断、GPT 的回答）和她的思考里。
 */
object Recall {
    fun grams(s: String): Set<String> {
        val t = s.lowercase().filter { it.isLetterOrDigit() }
        if (t.length < 2) return setOf(t).filter { it.isNotBlank() }.toSet()
        return (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
    }

    /** 太常见、说明不了“相关”的二元组。 */
    private val common = setOf("今天", "我们", "一个", "什么", "怎么", "没有", "就是", "这个", "那个", "可以", "现在", "时候", "自己", "不是", "还是", "已经", "他说", "你说")

    /** [items] 是要翻的知识（每条一行字）；返回最相关的 [k] 条（按重合的二元组多少排，常见词不算）。 */
    fun relevant(query: String, items: List<String>, k: Int = 6): List<String> {
        val q = grams(query) - common
        if (q.isEmpty()) return emptyList()
        return items.asSequence()
            .map { it to grams(it).count { g -> g in q } }
            .filter { it.second >= 1 }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
            .toList()
    }
}
