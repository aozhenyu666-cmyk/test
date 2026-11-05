package com.yishou.app.act

import com.yishou.app.judge.Guard
import com.yishou.app.sharp.Sharp
import com.yishou.app.think.Words

/**
 * 终审：选项题落笔之前的最后一步，专门对付“第一反应太快、没核对就选”。
 *
 * 复盘里反复出现的路径是：看到显眼的词 → 马上有了解释 → 提前选定 → 没看别的选项。
 * 补救不靠多讲方法名，而是每次落笔前固定做两个动作，并且这两个动作本机就能核对：
 *  1. 证据：从原文抄一句支撑你选的那项（必须真在原文里）；
 *  2. 亚军：说出你最犹豫的另一项，以及它错在哪（要提到那一项里的说法，不能只写“不全面”）。
 * 做久了，这两个动作会变成脱离软件也会做的习惯——这才是要迁移出去的东西。
 *
 * 不判对错（对错由答案核对），只判这两步有没有真做。
 */
data class FinalCheck(
    val pick: Int,
    val evidence: String,
    val runnerUp: Int,
    val whyNot: String,
)

object FinalReview {

    /**
     * 返回没做到的地方（空表示两步都做了）。
     * passage：材料原文；options：选项文字。
     */
    fun problems(passage: String, options: List<String>, c: FinalCheck): List<String> {
        val out = mutableListOf<String>()
        if (c.pick !in options.indices) out += "先选一项"
        if (Words.substance(c.evidence) < 4 || !Words.quoted(passage, c.evidence)) out += "证据要从原文里照抄一句（至少四个字）"
        if (c.runnerUp !in options.indices || c.runnerUp == c.pick) out += "再选一个你最犹豫的另一项"
        else {
            val why = c.whyNot.trim()
            val other = options[c.runnerUp]
            when {
                Words.substance(why) < 6 -> out += "说说它错在哪，六个字以上"
                Sharp.hollow(why) != null -> out += "“不全面”“太绝对”这种话放哪都对：指出它哪几个字和原文对不上"
                // 要点到亚军选项里的说法：和那一项至少有连续三个字重合
                Guard.novel(why, listOf(other)) == Guard.novel(why, emptyList()) -> out += "说它错，要点出它里面的哪个说法"
            }
        }
        return out
    }

    /**
     * 第一反应是不是太快：从看到选项到落笔，短于这个时间又没写证据，就记一笔“抢答”。
     * 只记、不罚；周报里看这类次数有没有下降。
     */
    const val RUSH_MS = 8_000L

    fun rushed(msToPick: Long, c: FinalCheck?): Boolean = msToPick < RUSH_MS && (c == null || Words.substance(c.evidence) < 4)
}
