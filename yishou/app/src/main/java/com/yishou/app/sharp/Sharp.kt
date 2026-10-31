package com.yishou.app.sharp

import com.yishou.app.think.Words

/**
 * 反向能耗：认真想一句话就过；用套话糊弄，反而要被追着答两三个更碎、更具体的小问题。
 * 算下来，糊弄比想透更累——偷懒的大脑自己会选想透。
 *
 * 全在本机判，不调模型：哪句是套话、每一刀交上来的东西算不算数，都是查字。
 * 不惩罚、不锁时间，只是让敷衍那条路更长。
 */
enum class Hollow(val label: String) {
    /** “太绝对了”“以偏概全”，却说不出哪几个字绝对 */
    ABSOLUTE("说它绝对"),
    /** “逻辑不严密”“有漏洞”，却说不出漏在哪 */
    LOGIC("说它有漏洞"),
    /** “不知道”“没想法” */
    BLANK("说不知道"),
    /** “有道理”“看情况”“因人而异”这类放之四海皆准的话 */
    EMPTY("说了句空话"),
}

/** 每一刀要交的东西 */
enum class Need {
    /** 从原文照抄几个字（本机核对真在原文里） */
    QUOTE,
    /** 一个具体例子：人、事、数字，至少十来个字 */
    EXAMPLE,
    /** 带因果的一句：因为……所以……、如果……就…… */
    BECAUSE,
    /** 改写或补一句，至少八个实在的字 */
    REWRITE,
}

data class Cut(val ask: String, val need: Need)

object Sharp {

    private val ABSOLUTE = listOf("太绝对", "绝对化", "过于绝对", "以偏概全", "一概而论", "太片面", "片面", "不全面", "太武断", "武断", "太笼统")
    private val LOGIC = listOf(
        "逻辑不严密", "不严密", "不严谨", "有漏洞", "逻辑有问题", "逻辑问题", "说不通", "不合理", "偷换概念", "因果倒置",
        "站不住", "不成立", "有问题", "有毛病", "推不出",
    )
    private val BLANK = listOf("不知道", "不清楚", "不会", "没想法", "想不出", "看不懂", "不懂", "随便", "都行", "不确定", "没看懂", "pass", "跳过")
    private val EMPTY = listOf(
        "有道理", "看情况", "因人而异", "具体问题具体分析", "见仁见智", "都有可能", "挺好", "还行", "一般般", "没问题",
        "说得对", "确实", "同意", "不同意", "有点道理", "看个人", "辩证看待", "要辩证", "两面性", "有利有弊",
    )

    /** 去掉套话以后还要剩这么多实在的字，才不算空 */
    const val MIN_LEFT = 6

    /**
     * 这句是不是套话。套话词去掉、口头禅去掉以后剩不到 [MIN_LEFT] 个字就算；
     * 一个套话词都没有、但整句不到 4 个实在字的，也算（多半是“嗯”“对”“A 吧”）。
     */
    fun hollow(answer: String): Hollow? {
        val t = answer.trim()
        val kind = when {
            ABSOLUTE.any { it in t } -> Hollow.ABSOLUTE
            LOGIC.any { it in t } -> Hollow.LOGIC
            BLANK.any { it in t.lowercase() } -> Hollow.BLANK
            EMPTY.any { it in t } -> Hollow.EMPTY
            else -> null
        }
        var left = t.lowercase()
        (ABSOLUTE + LOGIC + BLANK + EMPTY).sortedByDescending { it.length }.forEach { left = left.replace(it, "") }
        val rest = Words.substance(left)
        return when {
            kind != null && rest < MIN_LEFT -> kind
            kind == null && Words.substance(t) < 4 -> Hollow.BLANK
            else -> null
        }
    }

    /** 按糊弄的类型切成几刀。hasSource：有原文可抄（没有原文就不要求照抄） */
    fun cuts(h: Hollow, hasSource: Boolean): List<Cut> {
        val quote = { ask: String -> if (hasSource) Cut(ask, Need.QUOTE) else Cut(ask.replace("照抄出来", "说出来"), Need.REWRITE) }
        return when (h) {
            Hollow.ABSOLUTE -> listOf(
                quote("哪几个字最绝对？照抄出来。"),
                Cut("举一个它不成立的具体例子：谁、在哪、发生了什么。", Need.EXAMPLE),
                Cut("那句话改成什么样，才站得住？", Need.REWRITE),
            )
            Hollow.LOGIC -> listOf(
                quote("毛病出在哪一句？照抄出来。"),
                Cut("它从什么跳到了什么？用“因为……所以……”说。", Need.BECAUSE),
                Cut("补一句什么，它就说通了？", Need.REWRITE),
            )
            Hollow.BLANK -> listOf(
                quote("先只做一件事：它的结论是哪一句？照抄出来。"),
                Cut("它凭什么这么说？用“因为……所以……”说。", Need.BECAUSE),
                Cut("这个理由要成立，还得默认什么？", Need.REWRITE),
            )
            Hollow.EMPTY -> listOf(
                quote("你说的是哪一句？照抄出来。"),
                Cut("用“因为……所以……”把你的判断说完整。", Need.BECAUSE),
                Cut("举一个具体的例子撑住它。", Need.EXAMPLE),
            )
        }
    }

    /** 题卡：先别看选项，说正确答案长什么样。说空了就切这两刀，切完再亮选项。 */
    fun cardCuts(): List<Cut> = listOf(
        Cut("题目到底问什么？把题干里最关键的几个字照抄出来。", Need.QUOTE),
        Cut("正确答案必须满足什么条件？说成一句。", Need.REWRITE),
    )

    private val CAUSAL = listOf("因为", "所以", "导致", "因此", "如果", "就", "才", "于是", "由于", "使得", "→", "结果", "只要", "只有")

    /** 这一刀交上来的算不算数；不算就给一句提示（为什么不算、要什么） */
    fun accept(cut: Cut, reply: String, source: String): String? {
        val t = reply.trim()
        if (hollow(t) != null && cut.need != Need.QUOTE) return "这句还是套话。换成具体的：${cut.ask}"
        return when (cut.need) {
            Need.QUOTE -> when {
                Words.substance(t) < 2 -> "照抄原文里的几个字，至少两个字。"
                source.isNotBlank() && !Words.quoted(source, t) -> "原文里找不到这几个字。照抄，别改写。"
                else -> null
            }
            Need.EXAMPLE -> if (Words.substance(t) < 10) "例子太短了：说清谁、在哪、发生了什么（至少十来个字）。" else null
            Need.BECAUSE -> when {
                CAUSAL.none { it in t } -> "要带上因果：用“因为……所以……”或“如果……就……”。"
                Words.substance(t) < 8 -> "再说完整一点，至少八个实在的字。"
                else -> null
            }
            Need.REWRITE -> if (Words.substance(t) < 8) "再说完整一点，至少八个实在的字。" else null
        }
    }

    /**
     * 先说的那句有没有打中靶心：正确选项里独有的字（别的选项都没有的两字组），你说中了两组以上。
     * 选项还没给你看过，能说中这些字就是真想到了。
     */
    fun bullseye(said: String, options: List<String>, answer: Int): Boolean {
        if (answer !in options.indices || said.isBlank()) return false
        val mine = pairs(said)
        val right = pairs(options[answer]) - options.indices.filter { it != answer }.flatMap { pairs(options[it]) }.toSet()
        if (right.isEmpty()) return false
        val hit = (mine intersect right).size
        val wrongBest = options.indices.filter { it != answer }.maxOfOrNull { i ->
            (mine intersect (pairs(options[i]) - pairs(options[answer]))).size
        } ?: 0
        return hit >= minOf(2, right.size) && hit > wrongBest
    }

    /** 两字组（去掉标点和口头禅） */
    private fun pairs(s: String): Set<String> {
        var t = s
        Words.FILLERS.forEach { t = t.replace(it, "") }
        val n = Words.norm(t)
        return (0 until n.length - 1).map { n.substring(it, it + 2) }.toSet()
    }
}

/**
 * 一串追问：一刀一刀往下切，交上来不算数就停在这一刀并给提示，切完为止。
 * 整串记下来（先说的那句 + 每刀的问答），交给判卷看。
 */
class Drill(val first: String, val cuts: List<Cut>, private val source: String) {
    private val answers = mutableListOf<String>()
    var hint: String? = null
        private set
    var tries = 0
        private set

    val index: Int get() = answers.size
    val done: Boolean get() = answers.size >= cuts.size
    val current: Cut? get() = cuts.getOrNull(answers.size)

    /** 交这一刀；返回 true 表示收下了（可能还有下一刀） */
    fun reply(text: String): Boolean {
        val cut = current ?: return false
        tries++
        val why = Sharp.accept(cut, text, source)
        if (why != null) {
            hint = why
            return false
        }
        hint = null
        answers += text.trim()
        return true
    }

    /** 给判卷看的整串 */
    fun transcript(): String = buildString {
        append("先说：").append(first.trim())
        cuts.zip(answers).forEach { (c, a) -> append("\n追问：").append(c.ask).append("\n答：").append(a) }
    }

    /** 切出来的每刀回答，合在一起（题卡拿它去对靶心） */
    fun said(): String = answers.joinToString("；")

    /** 整串算下来交了几次（包括没收下的） */
    val cost: Int get() = tries
}
