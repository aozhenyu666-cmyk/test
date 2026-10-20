package com.moling.core.talk

import com.moling.core.link.ZkCommand
import com.moling.core.mind.Actor
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
    ) = buildString {
        appendLine("【你是】$persona")
        appendLine("【你的权柄】$tierText")
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
        appendLine("【动作】去聊｜gemini 或 chatgpt｜聊什么")
        appendLine("【动作】记下｜主题｜一句结论")
        append("【动作】改道｜卡住了 / 累了 / 晚了 / 情况变了")
    }

    /** 拆开回复正文和动作行。[dayStart] 用来把 HH:MM 换成时间戳。 */
    fun parse(reply: String, dayStart: Long, why: String = "对话里说好的"): TalkReply {
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
                "去聊" -> {
                    val actor = if (f.getOrNull(1)?.lowercase()?.contains("gpt") == true) Actor.CHATGPT else Actor.GEMINI_LIVE
                    actions += TalkAction.Summon(actor, f.getOrNull(2).orEmpty())
                }
                "记下" -> if (!f.getOrNull(2).isNullOrBlank()) actions += TalkAction.AtlasNote(f.getOrNull(1).orEmpty().ifBlank { "杂项" }, f[2])
                "改道" -> f.getOrNull(1)?.let { actions += TalkAction.Reroute(it) }
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
