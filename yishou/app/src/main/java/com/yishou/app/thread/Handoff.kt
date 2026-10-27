package com.yishou.app.thread

import com.yishou.app.judge.LockedQ
import com.yishou.app.judge.Point
import com.yishou.app.llm.Tagged

/**
 * 去外部 AI（Gemini / ChatGPT / Claude）深聊：一手给一段交接文字，带上目标、已定的、卡在哪、对方的规矩。
 * 聊完把三行总结贴回来，按当前待定项判；过了也不直接算定，还要用自己的话下一次结论。
 */
object Handoff {

    fun text(s: Strand): String = buildString {
        val item = s.current
        appendLine("我在想清楚一件事，请按下面的规矩陪我想。")
        appendLine()
        appendLine("目标：${s.goal}")
        appendLine("算定了的标准：${s.doneWhen}")
        if (s.settled.isNotEmpty()) {
            appendLine("已经定下的：")
            s.settled.forEach { appendLine("- ${it.question}：${it.conclusion}") }
        }
        if (item != null) appendLine("现在卡在：${item.question}")
        appendLine()
        appendLine("规矩：")
        appendLine("1. 每次只问我一个问题，问具体的（事实、数字、取舍），不要替我回答。")
        appendLine("2. 不要顺着我跑题。我说的和“${item?.question ?: s.goal}”无关时，直接拉我回来。")
        appendLine("3. 我说得含糊（“看情况”“都行”），追问到具体为止。")
        appendLine("4. 我说“收”的时候，只用三行总结，不要别的：")
        appendLine("结论：……")
        appendLine("依据：……")
        append("还缺：……")
    }

    data class Summary(val conclusion: String, val basis: String, val missing: String)

    fun parse(text: String): Summary? {
        val s = Tagged.sections(text, setOf("结论", "依据", "还缺"))
        val c = s["结论"]?.joinToString("")?.trim().orEmpty()
        if (c.isEmpty()) return null
        return Summary(c, s["依据"]?.joinToString("")?.trim().orEmpty(), s["还缺"]?.joinToString("")?.trim().orEmpty())
    }

    fun returnQ(s: Strand, item: Item, now: Long) = LockedQ(
        id = "s${s.id}-ext-$now",
        prompt = "把外部 AI 聊出来的三行总结贴回来（结论 / 依据 / 还缺）。\n现在要定的是：${item.question}",
        points = listOf(
            Point("P1", "对「${item.question}」给出了明确的结论，不是“看情况”“都可以”"),
            Point("P2", "给出了支撑结论的具体依据：事实、数字或例子"),
        ),
        need = 2,
        reference = "",
        allowPaste = true,
    )
}
