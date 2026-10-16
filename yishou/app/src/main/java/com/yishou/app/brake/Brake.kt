package com.yishou.app.brake

import com.yishou.app.think.Words

/**
 * 急刹：冲动上头、深思熟虑已经掉线的那十几秒。不讲道理，只做三件事：
 * 1. 吐——把脑子里那句话原样倒出来（倒出来本身就是第一脚刹车）；
 * 2. 切——拆成“摄像机拍得到的”和“脑子里补的”两栏；
 * 3. 证 + 押——最大那条脑补要什么硬证据才成立、你手里有没有；顺着它去做，30 分钟后最坏会怎样。
 * 然后你自己决定做不做。不拦你；30 分钟后回来核对最坏后果出没出现——你的“现实棋谱”就是这么攒的。
 *
 * 这里是没网、没配模型时的本机拆法：按“脑补词”判。模型在的话用模型拆，更准。
 */
object Brake {

    /** 一句话里出现这些，多半是脑子补的：读心、断言、预言、全称、应该 */
    val FANTASY = listOf(
        "肯定", "一定", "必须", "应该", "就是", "故意", "看不起", "针对", "瞧不起", "要是", "万一", "会不会", "是不是",
        "觉得", "感觉", "好像", "估计", "想要", "必然", "永远", "从来", "所有人", "根本", "凭什么", "受不了", "完了", "毁了",
        "绝对", "不可能", "没救", "活该", "都怪", "我要", "马上", "立刻", "再也", "早就", "怎么能",
    )

    data class Split(val facts: List<String>, val fantasies: List<String>)

    fun clauses(text: String): List<String> =
        text.split(Regex("[，。；！？,;!?\\n]+")).map { it.trim() }.filter { Words.substance(it) >= 2 }

    fun localSplit(raw: String): Split {
        val cs = clauses(raw)
        val (fan, fact) = cs.partition { c -> FANTASY.any { it in c } }
        return Split(fact, fan)
    }

    /** 最大那条脑补：最长的那条（没有就整句） */
    fun biggest(s: Split, raw: String): String = s.fantasies.maxByOrNull { Words.substance(it) } ?: clauses(raw).maxByOrNull { Words.substance(it) } ?: raw.trim()

    fun localProof(fantasy: String) = "要证明「${fantasy.take(24)}」，得有一个摄像机拍得到的证据：谁、在什么时候、做了什么、说了什么原话。"

    /** 押的后果至少说出 4 个实字 */
    fun worstOk(text: String): Boolean = Words.substance(text) >= 4

    /** 押后果的倒计时 */
    const val WORST_SECONDS = 10

    /** 多久之后回来核对 */
    const val CHECK_AFTER_MS = 30 * 60_000L

    data class Tally(val total: Int, val held: Int, val went: Int, val checked: Int, val happened: Int)

    fun tally(xs: List<com.yishou.app.data.Impulse>): Tally = Tally(
        total = xs.size,
        held = xs.count { it.decision == com.yishou.app.data.ImpulseDecision.HOLD },
        went = xs.count { it.decision == com.yishou.app.data.ImpulseDecision.GO },
        checked = xs.count { it.happened != null },
        happened = xs.count { it.happened == true },
    )

    fun line(t: Tally): String {
        if (t.total == 0) return "还没刹过。冲动上头时点这里，十几秒。"
        return buildList {
            add("刹过 ${t.total} 次")
            if (t.held > 0) add("先没做 ${t.held} 次")
            if (t.checked > 0) add("押的最坏后果出现 ${t.happened}/${t.checked}")
        }.joinToString(" · ")
    }
}
