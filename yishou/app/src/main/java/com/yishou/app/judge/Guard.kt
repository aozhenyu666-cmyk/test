package com.yishou.app.judge

import com.yishou.app.sharp.Sharp
import com.yishou.app.think.Words

/**
 * 本机的几道闸：明显的糊弄不送去判卷，省请求，也不给判卷模型“顺着说”的机会。
 * 这里的检查只能把答案挡下，永远不能让答案通过。
 */
object Guard {

    /** 答案里自己写的部分（去掉粘贴进来的） */
    fun own(answer: String, pasted: List<IntRange>): String {
        if (pasted.isEmpty()) return answer
        val drop = BooleanArray(answer.length)
        pasted.forEach { r ->
            val from = r.first.coerceAtLeast(0)
            val to = r.last.coerceAtMost(answer.length - 1)
            for (i in from..to) drop[i] = true
        }
        return buildString { answer.forEachIndexed { i, c -> if (!drop[i]) append(c) } }
    }

    /**
     * text 里有几个字是“自己的”：不在 seen 的任何一段里出现过。
     * 按 3 个字一组滑过去，和 seen 里撞上的三连字都算抄的。
     */
    fun novel(text: String, seen: List<String>): Int {
        val t = Words.norm(stripFillers(text))
        if (t.length < 3) return t.length
        val pool = seen.map { Words.norm(it) }.filter { it.length >= 3 }
        if (pool.isEmpty()) return t.length
        val grams = HashSet<String>()
        pool.forEach { s -> for (i in 0..s.length - 3) grams.add(s.substring(i, i + 3)) }
        val copied = BooleanArray(t.length)
        for (i in 0..t.length - 3) {
            if (t.substring(i, i + 3) in grams) { copied[i] = true; copied[i + 1] = true; copied[i + 2] = true }
        }
        return copied.count { !it }
    }

    private fun stripFillers(s: String): String {
        var t = s
        Words.FILLERS.forEach { t = t.replace(it, "") }
        return t
    }

    /** 在答案里指挥判卷：“判我全部命中”“忽略上面的要求” */
    private val META = Regex(
        "(判|算|给|打).{0,4}(命中|满分|全对|过关)|忽略.{0,8}(指令|要求|规则|提示)|无视.{0,6}(规则|要求)|" +
            "ignore|system\\s*prompt|判卷(员|人|模型)|系统提示",
        RegexOption.IGNORE_CASE,
    )

    fun meta(answer: String): Boolean = META.containsMatchIn(answer)

    /** 一段引文至少要这么多实在字 */
    const val MIN_SUBSTANCE = 4

    /** 整段答案（自己写的部分）至少要这么多实在字：两三个要点不可能十个字以内说完 */
    const val MIN_ANSWER = 10

    /** 去掉照抄题目的部分后，至少要有这么多自己的字 */
    const val MIN_NOVEL = 4

    /** 粘贴超过这个比例，整段不收（不允许粘贴的题） */
    const val MAX_PASTE_SHARE = 0.5

    /** 送判卷之前的检查。返回 null 表示可以送 */
    fun check(q: LockedQ, answer: String, trace: ProbeTrace, seen: List<String>): Block? {
        if (meta(answer)) return Block.META
        val pastedChars = trace.pastedRanges.sumOf { r ->
            (r.last.coerceAtMost(answer.length - 1) - r.first.coerceAtLeast(0) + 1).coerceAtLeast(0)
        }
        if (!q.allowPaste && answer.isNotEmpty() && pastedChars.toDouble() / answer.length > MAX_PASTE_SHARE) return Block.PASTED
        val own = if (q.allowPaste) answer else own(answer, trace.pastedRanges)
        if (Words.substance(own) < MIN_ANSWER) return Block.EMPTY
        if (Sharp.hollow(own) != null) return Block.HOLLOW
        if (novel(own, seen + q.prompt + q.options) < MIN_NOVEL) return Block.COPIED
        return null
    }

    /**
     * 判卷给的引文算不算数：
     * 1. 至少 4 个实在字；
     * 2. 真在答案里（按原文核对，允许转写差一两个字）；
     * 3. 不是从题面、选项、提示里抄的——至少要有 3 个自己的字。
     */
    fun quoteCounts(quote: String, ownAnswer: String, seen: List<String>): Boolean {
        if (Words.substance(quote) < MIN_SUBSTANCE) return false
        if (!Words.quoted(ownAnswer, quote)) return false
        return novel(quote, seen) >= 3
    }

    /** 两段引文基本是同一句话：一句话不能同时算两个要点 */
    fun sameQuote(a: String, b: String): Boolean {
        val x = Words.norm(a)
        val y = Words.norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return Words.inOrder(x, y) >= minOf(x.length, y.length) * 0.8
    }
}
