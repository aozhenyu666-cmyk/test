package com.moling.core.talk

import com.moling.core.link.ZkCommand
import com.moling.core.mind.Actor
import com.moling.core.mind.Conclude
import kotlinx.serialization.Serializable

/*
 * 和墨灵说话：朋友式的交流，但不陪着说口水话。
 *
 * 规矩（写进提示词，也在本地兜底）：
 *  - 每次回复先接住他说的那一点（一句），再给一个推进：一个具体问题 / 一个判断 / 一件马上能做的事。
 *  - 他的话空了（“嗯”“随便”“不知道”），不陪着空，换成一个二选一。
 *  - 陪伴模式可以只是聊天，不要求推进。
 *  - 需要改变现实时，在回复最后写【动作】行；动作先过权柄（Charter），不够格的变成“要不要我……？”。
 */

@Serializable
data class Turn(val who: String, val text: String, val at: Long)

/** 对话里墨灵想做的事。 */
sealed interface TalkAction {
    data class Zk(val command: ZkCommand) : TalkAction
    data class Summon(val actor: Actor, val topic: String) : TalkAction
    data class AtlasNote(val topic: String, val claim: String) : TalkAction
    data class Reroute(val cause: String) : TalkAction
    /** 她给自己定一个唤醒：到 [at] 来找他，说 [note]。 */
    data class Wake(val at: Long, val note: String) : TalkAction
    /** 她想问 GPT 的（写到 Notion 黑板上，GPT / Notion AI 读到会回答）。 */
    data class AskGpt(val question: String) : TalkAction
}

data class TalkReply(val text: String, val actions: List<TalkAction>, val byModel: Boolean)

object Talk {
    private val filler = Regex("""^(嗯+|哦+|啊+|好吧?|行吧?|随便|都行|不知道|无所谓|没想法|还好|就那样|ok|OK|。|…+)[。！!？?，, …]*$""")

    fun isFiller(text: String): Boolean = text.trim().let { it.length <= 4 && filler.matches(it) || it.isBlank() }

    /** 陪伴模式：他明确说想聊聊、累了、心情不好。 */
    fun isCompany(text: String): Boolean = Regex("""(陪我|聊聊天|随便聊|好累|心情不好|烦死|难受|不想说正事)""").containsMatchIn(text)

    fun prompt(
        persona: String,
        now: String,
        flow: String,
        baton: String,
        atlas: String,
        tierText: String,
        history: List<Turn>,
        user: String,
        mode: TalkMode = TalkMode.WORK,
        about: String = "",
        numbers: String = "",
        thinking: List<String> = emptyList(),
        recall: List<String> = emptyList(),
    ) = buildString {
        appendLine("【你是】$persona")
        appendLine("【你的权柄】$tierText")
        if (thinking.isNotEmpty()) appendLine("【你自己推的判断（未必对，用来理解他，不要当结论甩给他）】\n" + thinking.joinToString("\n"))
        if (recall.isNotEmpty()) appendLine("【你的知识里和这句话相关的】\n" + recall.joinToString("\n") { "- $it" })
        appendLine("【这次的模式】${mode.label}：${mode.rules}")
        if (about.isNotBlank()) appendLine("【你对他的了解（他确认过的）】\n${about.take(1200)}")
        if (numbers.isNotBlank()) appendLine("【数字】$numbers（对不上时温和地把数字摆出来，不指责）")
        appendLine("【此刻】")
        appendLine(now)
        if (flow.isNotBlank()) appendLine("【今天的水道】\n$flow")
        if (baton.isNotBlank()) appendLine("【没想完的】$baton")
        if (atlas.isNotBlank()) appendLine("【相关的体系图】\n$atlas")
        appendLine("【最近的对话】")
        history.takeLast(8).forEach { appendLine("${it.who}：${it.text.take(200)}") }
        appendLine("他：$user")
        appendLine()
        appendLine("回复规矩：像朋友一样说话，口语，不超过 4 句。先接住他说的那一点，再给一个推进（一个具体问题 / 一个判断 / 一件马上能做的事）；")
        appendLine("他在说空话就点出来，换成一个二选一；他想单纯聊天或者累了，就陪着聊，不推进。不夸、不说教、不替他想完；猜测要说是猜测。")
        appendLine("需要改变现实时，最后另起一行写动作（最多 2 个），格式只能是下面几种：")
        appendLine("【动作】主线｜标题｜HH:MM")
        appendLine("【动作】行动｜标题｜HH:MM｜分钟")
        appendLine("【动作】专注｜分钟｜做什么")
        appendLine("【动作】总控时间｜分钟")
        appendLine("【动作】严管｜分钟（临时严管，到点自己解除）")
        appendLine("【动作】减娱乐｜分钟（只减今天的）")
        appendLine("【动作】约定｜他答应做什么｜HH:MM 前｜怎么检查")
        appendLine("【动作】提醒我｜HH:MM 或 N 分钟后｜到时候对他说什么（你自己到点会被叫醒）")
        appendLine("【动作】去聊｜gemini 或 chatgpt｜聊什么")
        appendLine("【动作】记下｜主题｜一句结论")
        appendLine("【动作】问GPT｜一个具体问题（他跟 GPT 聊过、你不知道的，写到黑板上让 GPT 回答）")
        append("【动作】改道｜卡住了 / 累了 / 晚了 / 情况变了")
    }

    /** 拆开回复正文和动作行。[dayStart] 用来把 HH:MM 换成时间戳。 */
    fun parse(reply: String, dayStart: Long, why: String = "对话里说好的", now: Long = 0): TalkReply {
        val actions = mutableListOf<TalkAction>()
        val text = reply.lines().filterNot { it.trim().startsWith("【动作】") }.joinToString("\n").trim()
        Regex("""【动作】([^\n]+)""").findAll(reply).take(2).forEach { m ->
            val f = m.groupValues[1].split('｜', '|').map { it.trim() }
            fun hm(s: String?): Long? = s?.let { Regex("""(\d{1,2})[:：](\d{2})""").find(it) }?.let { r ->
                val h = r.groupValues[1].toInt(); val mi = r.groupValues[2].toInt()
                if (h in 0..23 && mi in 0..59) dayStart + (h * 60 + mi) * 60_000L else null
            }
            when (f.getOrNull(0)) {
                "主线" -> f.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { actions += TalkAction.Zk(ZkCommand.SetMain(it, hm(f.getOrNull(2)) ?: 0L, "", why)) }
                "行动" -> {
                    val title = f.getOrNull(1).orEmpty(); val start = hm(f.getOrNull(2)); val min = f.getOrNull(3)?.filter { it.isDigit() }?.toIntOrNull()
                    if (title.isNotBlank() && start != null && min != null && min in 5..240) actions += TalkAction.Zk(ZkCommand.AddAction(title, start, start + min * 60_000L, "", why))
                }
                "专注" -> f.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 5..180 }?.let { actions += TalkAction.Zk(ZkCommand.StartFocus(it, f.getOrNull(2).orEmpty(), why)) }
                "总控时间" -> f.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 5..60 }?.let { actions += TalkAction.Zk(ZkCommand.EnterHq(it, why)) }
                "严管" -> f.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 5..720 }?.let { actions += TalkAction.Zk(ZkCommand.Tighten("strict", "$it", why)) }
                "减娱乐" -> f.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..240 }?.let { actions += TalkAction.Zk(ZkCommand.Tighten("cut_play", "$it", why)) }
                "约定" -> {
                    val what = f.getOrNull(1).orEmpty()
                    var due = hm(f.getOrNull(2)) ?: 0L
                    // “23:30 前”说的是今天；要是早于今天 4 点（例如 1:00），就是今晚过了零点
                    if (due in 1 until dayStart + 4 * 3_600_000L) due += 24 * 3_600_000L
                    if (what.isNotBlank() && due > 0) actions += TalkAction.Zk(ZkCommand.Commit(what, due, f.getOrNull(3).orEmpty().ifBlank { "看总控的记录" }, why))
                }
                "去聊" -> {
                    val actor = if (f.getOrNull(1)?.lowercase()?.contains("gpt") == true) Actor.CHATGPT else Actor.GEMINI_LIVE
                    actions += TalkAction.Summon(actor, f.getOrNull(2).orEmpty())
                }
                "记下" -> if (!f.getOrNull(2).isNullOrBlank()) actions += TalkAction.AtlasNote(f.getOrNull(1).orEmpty().ifBlank { "杂项" }, f[2])
                "改道" -> f.getOrNull(1)?.let { actions += TalkAction.Reroute(it) }
                "问GPT", "问 GPT" -> f.getOrNull(1)?.trim()?.takeIf { it.length >= 4 }?.let { actions += TalkAction.AskGpt(it.take(120)) }
                "提醒我" -> {
                    val w = f.getOrNull(1).orEmpty()
                    val inMin = Regex("""(\d+)\s*分钟后""").find(Conclude.cnDurations(w))?.groupValues?.get(1)?.toIntOrNull()
                    val at = when {
                        inMin != null && now > 0 -> now + inMin.coerceIn(1, 24 * 60) * 60_000L
                        else -> hm(w)?.let { t -> if (now > 0 && t <= now) t + 24 * 3_600_000L else t }
                    }
                    val note = f.getOrNull(2).orEmpty()
                    if (at != null && note.isNotBlank()) actions += TalkAction.Wake(at, note.take(80))
                }
            }
        }
        return TalkReply(text, actions, true)
    }

    /** 模型不可用时的本地回复：不聊空话，给一个能接的问题。 */
    fun local(user: String, baton: String, flowNow: String): TalkReply {
        val text = when {
            isFiller(user) -> "这句有点空。二选一：现在是想做点事，还是想歇一会儿？"
            isCompany(user) -> "嗯，我在。想说什么就说，不用有结论。"
            baton.isNotBlank() -> "我接着上次：$baton。这一小问你现在怎么看？"
            flowNow.isNotBlank() -> "现在水该在「$flowNow」。是在做，还是卡住了？"
            else -> "我听着。这件事你最想先弄清哪一点？"
        }
        return TalkReply(text, emptyList(), false)
    }
}
