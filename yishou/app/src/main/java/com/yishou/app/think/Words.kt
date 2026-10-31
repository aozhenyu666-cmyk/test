package com.yishou.app.think

/**
 * 判卷用的几样文字工具，全在本机算，不经过模型：
 * - 口水：去掉“嗯、那个、就是、我觉得”之后还剩多少实在的字；
 * - 引文：模型说“你说到了”，必须给出你回答里的原话，这里核对那段话是不是真的在你回答里；
 * - 数字：从“三千到五千”“1.2万”这类说法里取出数来。
 */
object Words {

    /** 口头禅和语气词：去掉后再数字数 */
    val FILLERS = listOf(
        "我觉得", "我认为", "我感觉", "怎么说呢", "怎么说", "的话", "然后", "就是说", "就是", "那个", "这个",
        "其实", "反正", "可能", "大概", "应该", "差不多", "一些", "什么的", "之类的", "嗯", "啊", "呃", "额", "哦", "吧", "呢", "嘛", "吗",
    )

    private val PUNCT = Regex("[\\s\\p{P}\\p{S}，。、；：？！“”‘’（）《》【】…—·～]+")

    /** 去掉空白和标点，英文转小写 */
    fun norm(s: String): String = s.replace(PUNCT, "").lowercase()

    /** 去掉口头禅之后的实在字数 */
    fun substance(s: String): Int {
        var t = s
        FILLERS.forEach { t = t.replace(it, "") }
        return norm(t).length
    }

    /**
     * 引文核对：quote 是不是 answer 里的一段原话。
     * 去掉标点空白后整段包含就算；转写差一两个字的（应该 / 应当），
     * 在回答里找一段差不多长的，按顺序对上引文八成以上的字也算（引文至少 4 个字）。
     */
    fun quoted(answer: String, quote: String): Boolean {
        val a = norm(answer)
        val q = norm(quote)
        if (q.length < 2 || a.isEmpty()) return false
        if (a.contains(q)) return true
        if (q.length < 4) return false
        val w = minOf(a.length, q.length + 2)
        val need = q.length * 0.8
        for (start in 0..a.length - w) {
            if (inOrder(a.substring(start, start + w), q) >= need) return true
        }
        return false
    }

    /** 两段字按顺序最多能对上几个（最长公共子序列） */
    fun inOrder(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return dp[a.length][b.length]
    }

    private val DIGITS = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    private val SMALL = mapOf('十' to 10, '百' to 100, '千' to 1000)
    private val BIG = mapOf('万' to 1e4, '亿' to 1e8)
    private val NUMBER = Regex("(\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百千]+(?:点[零〇一二三四五六七八九]+)?)\\s*([万亿]?)\\s*([万亿]?)|半")

    /**
     * 取出文字里的数，按出现顺序。支持“3000”“1.2万”“三千五”“两百”“五万”“3亿”“一点五”“半”。
     * “三千五”按口语理解为 3500。
     */
    fun numbers(text: String): List<Double> = NUMBER.findAll(text).mapNotNull { m ->
        if (m.value == "半") return@mapNotNull 0.5
        val head = m.groupValues[1]
        var v = head.toDoubleOrNull() ?: chinese(head) ?: return@mapNotNull null
        listOf(m.groupValues[2], m.groupValues[3]).forEach { u -> if (u.isNotEmpty()) v *= BIG.getValue(u[0]) }
        v
    }.toList()

    /** 汉字数（不含万、亿）：三千五 → 3500，十二 → 12，一点五 → 1.5 */
    fun chinese(s: String): Double? {
        if (s.isEmpty()) return null
        val dot = s.indexOf('点')
        if (dot >= 0) {
            val whole = chinese(s.substring(0, dot)) ?: return null
            val frac = s.substring(dot + 1).map { DIGITS[it] ?: return null }.joinToString("")
            return "${whole.toLong()}.$frac".toDoubleOrNull()
        }
        var total = 0
        var digit = -1
        var lastUnit = 0
        var zero = false
        for (c in s) {
            val d = DIGITS[c]
            val u = SMALL[c]
            when {
                d == 0 -> zero = true
                d != null -> digit = if (digit > 0 && lastUnit == 0) digit * 10 + d else d
                u != null -> {
                    total += (if (digit < 0) 1 else digit) * u
                    digit = -1
                    lastUnit = u
                    zero = false
                }
                else -> return null
            }
        }
        if (digit >= 0) {
            // “三千五”的“五”跟在千后面，是 500；“十二”的“二”跟在十后面，就是 2
            total += if (lastUnit >= 100 && !zero) digit * (lastUnit / 10) else digit
        }
        return total.toDouble()
    }
}
