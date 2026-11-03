package com.dshdriver.core

import kotlin.random.Random

/** Interaction type is separate from card UI kind and from the low-bandwidth six dice. */
object Interaction {
    val ALL = listOf("recall", "challenge", "teach", "task", "connect")
    fun pick(recent: List<String>, rng: () -> Double = { Random.nextDouble() }): String {
        val choices = ALL.filter { it !in recent.takeLast(3) }
        return choices[(rng().coerceIn(0.0, 0.999999) * choices.size).toInt()]
    }
    fun questions(kind: String): List<String> = when (kind) {
        "recall" -> listOf("用自己的话复述这条记录，纠正一处你现在不同意的地方。", "回想记录形成时的情境：当时看到了哪个具体条件？", "合上材料回忆其中两点，再打开核对漏掉了哪点。")
        "challenge" -> listOf("有人反驳：这条记录里的判断还不能成立。举个反例回应他。", "假设记录中的关键条件反过来，原来的结论会怎样变？", "找出这段判断最薄弱的一环，用一个具体例子攻击它。")
        "teach" -> listOf("把这点讲给 Gemini，要求它挑一个漏洞；在这里写下你要讲的内容。", "用初学者能听懂的例子教 Gemini 这点，让它复述来验收。", "让 Gemini 扮演不信你的人，先写下你准备怎样说服它。")
        "task" -> listOf("花 30 秒找出这段内容里一个关键条件，解释漏掉它会怎样。", "用 30 秒画出两个条件的关系，描述箭头从哪里指向哪里。", "现在对照原材料检查一个细节，写下核对前后有何变化。")
        else -> listOf("把这点和你的主线目标连起来，描述一个能用上它的具体场景。", "对照自己的主线，举个这段材料能帮你排除的错误做法。", "选一个当前真实任务，说明这条记录能替你解决哪个小障碍。")
    }
    fun question(kind: String): String = questions(kind).first()
    fun card(kind: String, material: String, anchor: String? = null, variant: Int = 0) =
        Draft("open", "$material\n${questions(kind)[Math.floorMod(variant, questions(kind).size)]}", "evidence", anchor = anchor)

    val BLACKLIST = listOf("依据是什么", "下一步是什么", "说一个词")
    /** Compare the question clause, so a long material prefix cannot hide a template. Literal only. */
    fun formulaic(text: String): Boolean {
        val clauses = text.substringAfter('\n', text).split(Regex("[\\n。！？?!]" )).filter { it.isNotBlank() }
        return clauses.any { clause -> BLACKLIST.any { template ->
            clause.contains(template) || Similar.jaccard(clause, template) >= 0.6
        } }
    }
    fun withMaterial(d: Draft, material: String): Draft =
        if (d.text.contains('\n')) d else d.copy(text = "$material\n${d.text}")
}
