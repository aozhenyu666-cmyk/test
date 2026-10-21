package com.moling.core.talk

import com.moling.core.flow.Channel
import com.moling.core.flow.ChannelKind
import com.moling.core.mind.Tags
import kotlinx.serialization.Serializable

/*
 * 说话的模式（v4 C6）。不同模式成功的标准不同（蓝图 README 第 7 节）：
 * 工作 = 推一步；训练 = 一个真实的推理步骤；启动 = 连上、知道卡在哪；休息 = 按时回来；陪伴 = 他觉得愉快。
 */
@Serializable
enum class TalkMode(val label: String, val rules: String) {
    WORK("工作", "推一步：一个具体问题、一个判断或一件马上能做的事；该收束时收束。"),
    TRAIN("训练", "一手式追问：依据是哪一句？接下来会怎样？有没有反例？每次只指出他推理里的一处漏洞，不给答案；他答完一轮，最后一行写【分】0–3。"),
    START("启动", "他动不起来：只要他回一个词、点一下或一句话就算赢。给二选一，问卡在哪，不讲道理、不列清单。"),
    REST("休息", "陪他歇着，不推进；只约好什么时候回来（具体时间）。"),
    COMPANY("陪伴", "就是聊天，不考核、不推进、不提计划；他想说什么就接什么。"),
}

object Modes {
    private val tired = Regex("""(好累|累了|累死|困了|好困|头疼|不想动|歇会|休息一下|躺会)""")

    /** 根据这句话和此刻的局面猜模式。他手动选的模式优先（由大脑记着）。 */
    fun guess(text: String, mainLateMin: Int, mainStarted: Boolean, flowNow: Channel?): TalkMode = when {
        tired.containsMatchIn(text) -> TalkMode.REST
        Talk.isCompany(text) -> TalkMode.COMPANY
        Talk.isFiller(text) || (mainLateMin > 0 && !mainStarted) -> TalkMode.START
        flowNow?.kind == ChannelKind.SPRING -> TalkMode.TRAIN
        else -> TalkMode.WORK
    }

    /** 训练模式里她给的分（0–3）；没有返回 -1。 */
    fun score(reply: String): Int = Tags.parse(reply)["分"]?.firstOrNull { it.isDigit() }?.digitToInt()?.takeIf { it in 0..3 } ?: -1

    // ------------------------------------------------------------------ 反驳我

    fun devilPrompt(persona: String, claim: String, history: List<Turn>) = buildString {
        appendLine("【你是】$persona")
        appendLine("【最近的对话】")
        history.takeLast(6).forEach { appendLine("${it.who}：${it.text.take(200)}") }
        appendLine("【他要你反驳的】$claim")
        appendLine()
        append("现在只做一件事：找他这个判断的漏洞，最多三条，每条一句话，按“最可能让它站不住”排。不给结论、不替他改、不安慰。")
    }

    // ------------------------------------------------------------------ 疯想—冷判

    fun wildPrompt(persona: String, topic: String) = buildString {
        appendLine("【你是】$persona")
        appendLine("【题目】$topic")
        appendLine()
        appendLine("两步。先疯想：给五条最离谱、但真的能做的路线，每条一句。再冷判：逐条标“事实 / 假设 / 可以试”，")
        appendLine("最后从“可以试”里挑一个今天就能做、最便宜的小实验（15–30 分钟）。只输出：")
        appendLine("【疯想】1. …… ｜ 假设")
        appendLine("（共五行【疯想】）")
        append("【小实验】做什么｜分钟")
    }

    /** 解析出疯想五行（原样）和小实验（标题、分钟）；小实验解析不出返回 null。 */
    fun parseWild(reply: String): Pair<List<String>, Pair<String, Int>?> {
        val lines = Regex("""【疯想】([^\n]+)""").findAll(reply).map { it.groupValues[1].trim() }.take(5).toList()
        val exp = Regex("""【小实验】([^\n]+)""").find(reply)?.groupValues?.get(1)?.split('｜', '|')?.map { it.trim() }
        val title = exp?.getOrNull(0)?.takeIf { it.isNotBlank() }?.take(40)
        val min = exp?.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(10, 60)
        return lines to if (title != null) title to (min ?: 20) else null
    }
}
