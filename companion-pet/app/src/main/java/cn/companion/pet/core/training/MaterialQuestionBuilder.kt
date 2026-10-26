package cn.companion.pet.core.training

/**
 * 从绑定材料的**真实正文**生成一道可核验的问题。
 *
 * 规则是确定性的，不用模型，也不假装“读懂了文章”：
 * 1. 把正文切成句子；
 * 2. 找出带范围词（任何人/所有/全部/都…）或因果词（所以/因此/因为…）的句子；
 * 3. 选第一句作为待指认的证据，其余作为干扰项；
 * 4. 让用户指出这句里的可核查特征，并给出一句依据。
 *
 * 如果正文里找不到任何可核查线索，返回 null —— 宁可不问，也不编一道题。
 */
object MaterialQuestionBuilder {

    private val SCOPE_CUES = listOf("任何人", "所有人", "全部", "凡是", "都", "每一个", "一律")
    private val CAUSE_CUES = listOf("所以", "因此", "由此", "可见", "说明", "证明了")

    private val SENTENCE_SPLIT = Regex("[。！？；\\n]+")

    fun build(material: BoundMaterial): MaterialQuestionPlan? {
        // 只有链接、没有正文：明确不能出理解题。
        if (!material.hasReadableBody) return null

        val sentences = material.body
            .split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.length in 8..120 }
        if (sentences.isEmpty()) return null

        val spans = sentences.mapIndexedNotNull { idx, raw ->
            val cue = (SCOPE_CUES + CAUSE_CUES).firstOrNull { raw.contains(it) } ?: return@mapIndexedNotNull null
            EvidenceSpan(index = idx, text = raw, cue = cue)
        }
        if (spans.isEmpty()) return null

        val target = spans.first()
        val isScope = SCOPE_CUES.any { target.cue == it }
        val others = spans.drop(1).take(2)

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
            allSpans = listOf(target) + others,
            rubricPoints = rubric,
            nextStep = next,
        )
    }
}
