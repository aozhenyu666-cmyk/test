package com.yishou.app.card

/** 一张题卡的四步：下注 → 指依据 → 多查一步 → （改不改）→ 揭晓。 */
enum class Step { BET, EVIDENCE, CHECK, CHANGE, REVEAL }

/**
 * checks：多查几项（0 表示不查，入口思考页用）；allowChange：查完可以改答案。
 */
data class RoundConfig(val checks: Int, val allowChange: Boolean)

/**
 * 一张题卡上的作答过程。不可变，每一步返回新的状态，方便测试和存档。
 * 判对错全靠和题卡比对：选项、依据句、错因。
 */
data class Round(
    val card: Card,
    val cfg: RoundConfig,
    val step: Step = Step.BET,
    val bet: Int? = null,
    val conf: Conf? = null,
    val evidence: Set<Int> = emptySet(),
    /** 多查：查了哪一项 → 判的错因 */
    val checks: Map<Int, String> = emptyMap(),
    val final: Int? = null,
    /** 用到的帮助：0 没有，1 指区域，2 排除两项，3 看解析 */
    val help: Int = 0,
    /** 错了时自己点的错因（揭晓后可选） */
    val cause: String = "",
) {
    val finalAnswer: Int? get() = final ?: bet
    val firstCorrect: Boolean get() = bet == card.answer
    val finalCorrect: Boolean get() = finalAnswer == card.answer
    /** 没有依据句的题（数量、定义这类）不考依据，算过 */
    val evidenceOk: Boolean get() = !card.hasEvidence || (evidence.isNotEmpty() && evidence.any { it in card.evidence })
    val checksOk: Int get() = checks.count { (opt, flaw) -> checkRight(opt, flaw) }

    /** 多查判得对不对：正确项要判“没毛病”；有错因表就要判中错因，没有就只要看出它有毛病 */
    fun checkRight(opt: Int, flaw: String): Boolean = when {
        opt == card.answer -> flaw == Flaws.NONE
        card.hasFlaws -> card.flawOf(opt) == flaw
        else -> flaw != Flaws.NONE
    }
    /** 自己完成：没用帮助 */
    val independent: Boolean get() = help == 0
    val changedRight: Boolean get() = final != null && final != bet && finalCorrect
    val changedWrong: Boolean get() = final != null && final != bet && firstCorrect && !finalCorrect
    /** 还能多查哪些项：除了自己选的那项 */
    val checkable: List<Int> get() = (0..3).filter { it != bet && it !in checks }

    fun bet(option: Int, c: Conf): Round {
        if (step != Step.BET || option !in 0..3) return this
        return copy(bet = option, conf = c, step = if (card.hasEvidence) Step.EVIDENCE else afterEvidence())
    }

    fun evidence(sentences: Set<Int>): Round {
        if (step != Step.EVIDENCE) return this
        val valid = sentences.filter { it in 1..card.sentences.size }.toSet()
        if (valid.isEmpty()) return this
        return copy(evidence = valid, step = afterEvidence())
    }

    private fun afterEvidence() = if (cfg.checks > 0) Step.CHECK else Step.REVEAL

    fun check(option: Int, flaw: String): Round {
        if (step != Step.CHECK || option !in checkable || flaw !in Flaws.CHOICES) return this
        val next = checks + (option to flaw)
        val done = next.size >= cfg.checks.coerceAtMost(3)
        return copy(checks = next, step = if (!done) Step.CHECK else if (cfg.allowChange) Step.CHANGE else Step.REVEAL)
    }

    /** 查完：改成 option，或者 null 不改 */
    fun change(option: Int?): Round {
        if (step != Step.CHANGE) return this
        return copy(final = option?.takeIf { it in 0..3 && it != bet }, step = Step.REVEAL)
    }

    /** 卡住了：再加一级帮助。到第 3 级直接揭晓（这张不算自己完成）。 */
    fun hint(): Round {
        if (step == Step.REVEAL) return this
        val h = (help + 1).coerceAtMost(3)
        return if (h == 3) copy(help = 3, step = Step.REVEAL) else copy(help = h)
    }

    fun cause(c: String): Round = copy(cause = c)

    /** 帮助的内容：1 指区域，2 排除两项错误选项 */
    fun hintText(): String? = when (help) {
        1 -> if (card.hasEvidence) "看第 ${card.evidence.sorted().joinToString("、") { Card.mark(it) }} 句。"
        else "把题干里的条件一条条列出来，再拿每个选项去代。"
        2 -> {
            val wrong = (0..3).filter { it != card.answer }.take(2)
            "先排除 " + wrong.joinToString("、") { "${Card.letter(it)}（${card.flawOf(it)}）" } + "。"
        }
        else -> null
    }

    /** 每一手结束只显示一行记分 */
    fun scoreLine(): String = buildList {
        add("首答 ${if (firstCorrect) "✓" else "✗"}")
        conf?.let { c -> add("把握${c.label} ${if (firstCorrect == (c != Conf.LOW)) "✓" else "✗"}") }
        if (card.hasEvidence) add("依据 ${if (evidenceOk) "✓" else "✗"}")
        if (checks.isNotEmpty()) add("多查 $checksOk/${checks.size}")
        if (final != null && final != bet) add(if (finalCorrect) "改对" else "改错")
        if (help > 0) add("帮助 $help")
    }.joinToString("｜")

    companion object {
        /** 错了时可以点的错因 */
        val CAUSES = listOf("没回原文核对", "没拆开复合句", "看了表面", "被铺垫带走", "条件方向弄反", "粗心", "真不会")
    }
}
