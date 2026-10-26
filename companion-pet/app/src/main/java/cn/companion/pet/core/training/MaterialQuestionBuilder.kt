package cn.companion.pet.core.training

/**
 * 从绑定材料的**真实正文**生成一道可核验的问题。
 *
 * 规则是确定性的，不用模型，也不假装“读懂了文章”：
 * 1. 把正文切成句子；
 * 2. 给每句打分：明确的全称范围词（任何人/所有/凡是…）> 强因果词（所以/因此…）> 带结论词的“都”；
 *    单独一个“都”“说明”太常见，不算线索；
 * 3. 选分最高的一句作为待指认的证据；
 * 4. 干扰项只用**不含任何线索**的句子，保证“哪一句”只有一个正确答案；
 * 5. 所有句子按原文顺序排列，正确句不总在第一个。
 *
 * 如果正文里找不到任何可核查线索，返回 null —— 宁可不问，也不编一道题。
 */
object MaterialQuestionBuilder {

    /** 明确的全称范围词：把结论推到所有人。 */
    private val STRONG_SCOPE = listOf("任何人", "所有人", "每一个", "每个人", "人人", "凡是", "一律", "全部", "所有")
    private val CAUSE_CUES = listOf("所以", "因此", "由此可见", "由此", "可见", "证明了")
    /** “都”只有在下结论的句子里才算范围线索。 */
    private const val WEAK_SCOPE = "都"
    private val CONCLUSION_MARKERS = listOf("所以", "因此", "可见", "说明", "证明", "应该", "一定")

    private val SENTENCE_SPLIT = Regex("[。！？；\\n]+")

    /** 句子是否带有任何线索（用来排除干扰项）。 */
    private fun hasAnyCue(s: String): Boolean =
        (STRONG_SCOPE + CAUSE_CUES).any { s.contains(it) } || s.contains(WEAK_SCOPE)

    /** 返回 (分数, 命中的线索词)；0 分表示不能出题。 */
    private fun score(s: String): Pair<Int, String> {
        // 取句中最先出现的范围词；同一位置取更长的（“所有人”优先于“所有”）。
        val strong = STRONG_SCOPE.filter { s.contains(it) }
            .minWithOrNull(compareBy<String>({ s.indexOf(it) }, { -it.length }))
        val concluding = CONCLUSION_MARKERS.any { s.contains(it) }
        if (strong != null) return (if (concluding) 4 else 3) to strong
        val cause = CAUSE_CUES.filter { s.contains(it) }
            .minWithOrNull(compareBy<String>({ s.indexOf(it) }, { -it.length }))
        if (cause != null) return 2 to cause
        if (s.contains(WEAK_SCOPE) && concluding) return 1 to WEAK_SCOPE
        return 0 to ""
    }

    fun isScopeCue(cue: String): Boolean = cue in STRONG_SCOPE || cue == WEAK_SCOPE

    fun build(material: BoundMaterial): MaterialQuestionPlan? {
        // 只有链接、没有正文：明确不能出理解题。
        if (!material.hasReadableBody) return null

        val sentences = material.body
            .split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.length in 8..120 }
        if (sentences.isEmpty()) return null

        val scored = sentences.mapIndexed { idx, raw -> Triple(idx, raw, score(raw)) }
        val best = scored.filter { it.third.first > 0 }.maxByOrNull { it.third.first } ?: return null
        // 同分取最早的一句（maxBy 取第一个最大值）。
        val target = EvidenceSpan(index = best.first, text = best.second, cue = best.third.second)
        val isScope = isScopeCue(target.cue)

        // 干扰项：不含任何线索的句子，取离目标最近的两句；按原文顺序排列。
        val distractors = scored
            .filter { it.first != target.index && !hasAnyCue(it.second) }
            .sortedBy { kotlin.math.abs(it.first - target.index) }
            .take(2)
            .map { EvidenceSpan(index = it.first, text = it.second, cue = "") }
        val all = (distractors + target).sortedBy { it.index }

        val question = if (isScope) {
            "材料里有一句把结论推到了很大的范围。请指出是哪一句，并说明它用了哪个范围词。"
        } else {
            "材料里有一句下了因果判断。请指出是哪一句，并说明它用了哪个因果词。"
        }

        val rubric = if (isScope) {
            listOf(
                "指出含「" + target.cue + "」的那一句",
                "说明这个范围词把结论推到了所有人都适用",
            )
        } else {
            listOf(
                "指出含「" + target.cue + "」的那一句",
                "说明这是从结果倒推原因的判断",
            )
        }

        val next = if (isScope) {
            "下一步：这句话只凭材料里的证据够不够支持「所有人都适用」？给一句依据。"
        } else {
            "下一步：有没有别的解释也能说明这个结果？给一个。"
        }

        return MaterialQuestionPlan(
            materialId = material.materialId,
            materialVersion = material.version,
            // 题号绑定材料内容哈希 + 句子序号：材料变了题号就变，旧成绩不会串。
            questionId = "mat-" + material.bodyHash.take(8) + "-s" + target.index,
            question = question,
            span = target,
            allSpans = all,
            rubricPoints = rubric,
            nextStep = next,
        )
    }
}
