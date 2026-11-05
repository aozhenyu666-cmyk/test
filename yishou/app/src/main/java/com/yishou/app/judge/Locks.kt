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

    /** 解析里专门讲干扰项的那一句：“A项：……排除”“B项：……”。拿它当判据，等于用错误选项的理由判人 */
    private val DISTRACTOR = Regex("^\\s*[A-DＡ-Ｄ]\\s*[项、.．：:）)]")

    /** 只交代答案是哪个字母的句子：“故正确答案为D”“故选B”，没有可判的内容 */
    private val ANSWER_ONLY = Regex("^\\s*(故)?\\s*(正确答案|答案|应选|选)\\s*[是为]?\\s*[A-DＡ-Ｄ]")

    /** 题库常见的来源标注：“〔原书定位：…〕”“题本PDF第16页” */
    private val META = Regex("原书定位|题本PDF|解析PDF|本题选自|资料来源")

    /** 解析常常一整段只用一个句号收尾，句内全是逗号；这时按逗号再切才能拿到几条 */
    private val CLAUSE = Regex("[，,]+")

    /** 句尾“，对应D项”“，故答案为A”这类交代：判据里带着答案字母没意义，去掉再存 */
    private fun stripAnswerRef(s: String): String = s
        .replace(Regex("[，,、]?\\s*对应\\s*[A-DＡ-Ｄ]\\s*项?\\s*$"), "")
        .replace(Regex("[，,、]?\\s*(故)?\\s*(正确)?答案[是为]?\\s*[A-DＡ-Ｄ]\\s*项?\\s*$"), "")
        .trim()

    /** 把小句均匀攒成最多 max 条：按顺序分，不丢内容 */
    private fun pack(clauses: List<String>, max: Int): List<String> {
        val groups = minOf(max, clauses.size)
        if (groups <= 1) return clauses
        return (0 until groups).map { i ->
            clauses.subList(i * clauses.size / groups, (i + 1) * clauses.size / groups).joinToString("，")
        }
    }

    /**
     * 把解析拆成判据。干扰项分析、答案交代、出处标注都不算判据——
     * 剔掉它们之后还够 2 条，这道题才进得了入口。
     */
    fun pointsFromExplain(explain: String): List<String> {
        val sentences = explain.split(SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !DISTRACTOR.containsMatchIn(it) && !ANSWER_ONLY.containsMatchIn(it) && !META.containsMatchIn(it) }
            .map(::stripAnswerRef)
            .filter { Words.substance(it) >= 6 }
            .distinct()
        if (sentences.size >= 2) return sentences.take(4)
        // 整段只有一句（主旨题解析常这样）：按逗号切成小句，再攒回 2–4 条
        val clauses = sentences.flatMap { it.split(CLAUSE) }
            .map { it.trim() }
            .filter { Words.substance(it) >= 4 }
        return if (clauses.size >= 2) pack(clauses, 4) else sentences.take(4)
    }

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
        val parts = pointsFromExplain(explain)
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
