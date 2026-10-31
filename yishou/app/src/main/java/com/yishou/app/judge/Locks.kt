package com.yishou.app.judge

import com.yishou.app.llm.Tagged
import com.yishou.app.think.Words

/**
 * 锁题从哪来：
 * - 题库题：答案和解析本来就定死了，解析拆成要点；
 * - 素材 / 思维线程：先让模型出一道锁题，本机检查合格、存好，才给用户看。
 * 出不来就是出不来，不拿一道没锁的题凑数。
 */
object Locks {

    /** 至少命中几个：2 个要点要全中，3 个中 2 个，4 个中 3 个 */
    fun need(n: Int): Int = (n * 2 + 2) / 3

    private val SPLIT = Regex("[。；;！!？?\\n]+")

    /**
     * 题库题转锁题。解析拆不出 2 个像样的要点（每个至少 6 个实在字）就返回 null，这道题不进入口。
     * 选项先藏着，所以题面要说清楚先说什么。
     */
    fun fromBank(
        id: String,
        passage: String,
        question: String,
        options: List<String>,
        answer: Int,
        explain: String,
        hint: String = "",
    ): LockedQ? {
        val parts = explain.split(SPLIT).map { it.trim() }.filter { Words.substance(it) >= 6 }.distinct().take(4)
        if (parts.size < 2) return null
        val points = parts.mapIndexed { i, c -> Point("P${i + 1}", c, hint) }
        val prompt = buildString {
            if (passage.isNotBlank()) appendLine(passage.trim()).appendLine()
            appendLine(question.trim())
            if (options.isNotEmpty()) {
                appendLine()
                append("先别看选项：说说正确答案必须长什么样、为什么。说中了再亮选项。")
            }
        }.trim()
        val letter = if (answer in options.indices) "正确答案 ${'A' + answer}。" else ""
        return LockedQ(id, prompt, options, answer, points, need(points.size), letter + explain.trim())
    }

    /** 让模型从一段素材出一道锁题 */
    val GEN_SYSTEM = """
你出一道思考题，用来逼人自己想，不是考记忆。

要求：
1. 题目不能用“是/否”回答，不给选项，一两句话说清楚。
2. 列 2 到 4 个要点：一个好答案必须说到的东西。每个要点写成一句判据（判卷时对照用），再写一句提示（没答到时给人看，只指方向，不能把判据说出来）。
3. 判据里的关键说法，不能在题目里直接出现——不然照抄题目就能过。
4. 最后写参考答案，三句以内。

只按这个格式回：
【题目】……
【要点】
1. 判据 | 提示
2. 判据 | 提示
【参考】……
""".trim()

    fun genUser(material: String): String = "素材：\n" + material.trim().take(1500)

    /** 解析并检查模型出的锁题。不合格返回 null */
    fun parseGen(id: String, text: String): LockedQ? {
        val s = Tagged.sections(text, setOf("题目", "要点", "参考"))
        val prompt = s["题目"]?.joinToString("")?.trim().orEmpty()
        if (Words.substance(prompt) < 8) return null
        val item = Regex("^\\s*(?:\\d{1,2}\\s*[.、．)）]|[-•·*])\\s*")
        val points = s["要点"].orEmpty().mapNotNull { line ->
            val body = line.replace(item, "").trim()
            val cut = body.indexOfAny(charArrayOf('|', '｜'))
            val criterion = (if (cut >= 0) body.substring(0, cut) else body).trim()
            val hint = if (cut >= 0) body.substring(cut + 1).trim() else ""
            criterion.takeIf { Words.substance(it) >= 6 }?.let { criterion to hint }
        }.take(4).mapIndexed { i, (c, h) -> Point("P${i + 1}", c, h) }
        if (points.size < 2) return null
        // 判据能从题目里抄出来的，这题作废
        if (points.any { Guard.novel(it.criterion, listOf(prompt)) < 4 }) return null
        // 提示把判据说出来了的，提示作废（题还能用）
        val safe = points.map { p -> if (p.hint.isNotBlank() && Guard.novel(p.hint, listOf(p.criterion)) < 4) p.copy(hint = "") else p }
        val ref = s["参考"]?.joinToString("")?.trim().orEmpty()
        return LockedQ(id, prompt, points = safe, need = need(safe.size), reference = ref)
    }
}
