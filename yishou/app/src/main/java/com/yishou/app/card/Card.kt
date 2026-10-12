package com.yishou.app.card

import org.json.JSONArray
import org.json.JSONObject

/**
 * 小技能：把“思考能力”拆成能判对错的小动作。每一种对应一类题，六骰子是它们上面的大类。
 * hint 是出题时给模型的题型说明。
 */
enum class Skill(val label: String, val face: Int, val hint: String) {
    DETAIL("回原文核对", 1, "细节判断：问“下列说法与原文相符的是”。干扰项在细节上和原文不同（偷换、扩大范围、绝对化、无中生有），正确项必须能在某一句里逐字找到依据。"),
    SPLIT("拆复合句", 1, "拆复合句：段落里有一个含两三层关系（转折、因果、条件并存）的长句，问“对第 N 句理解正确的是”。正确项把几层关系都说对，干扰项漏掉或弄反其中一层。"),
    MAIN("定位结论与转折", 1, "主旨题：段落里有“但”“然而”“因此”“可见”等转折或总结，前面是铺垫或被推翻的看法。问“这段文字意在说明”。干扰项抓住铺垫、例子或被推翻的看法。"),
    COND("条件推理方向", 5, "条件推理：用“如果……就……”“只有……才……”“除非”给两三个条件和一个已知事实，问“由此可以推出”。干扰项是肯定后件、否定前件这类方向错误。"),
    CHAIN("串成链", 1, "排序推理：四五个人或事物的先后、高矮、位置条件，问“由此可以推出”或“正确的顺序是”。必须能唯一确定答案。"),
    TWIN("区分结构和表面", 3, "结构类比：给一段推理，问“下列哪项与上述推理结构最相似”。至少一个干扰项话题和题干一样但推理结构不同，正确项话题不同但结构相同。"),
    GAP("说出缺口", 4, "前提题：给一段论证，问“要得出上述结论，需要补充的前提是”。正确项补上论证缺的那一环，干扰项和话题相关但补不上。"),
    ;

    companion object {
        fun of(name: String): Skill? = entries.firstOrNull { it.name == name || it.label == name }
    }
}

/** 错误类型：多查一步时给没选的选项判一个错因。固定一张表，能判对错。 */
object Flaws {
    const val NONE = "没毛病"
    val ALL = listOf("与原文相反", "无中生有", "偷换概念", "范围扩大", "绝对化", "因果倒置", "推理方向错", "结构不同", "答非所问")
    val CHOICES = ALL + NONE
}

enum class Conf(val label: String) { HIGH("高"), MID("中"), LOW("低") }

/**
 * 一张题卡。答案、依据句、每个干扰项的错因都在出题时就定死，判对错只做比对，
 * 说得再漂亮也改变不了。
 */
data class Card(
    val skill: Skill,
    val difficulty: Int,
    /** 材料，按句分开（不带编号，显示时加①②③） */
    val sentences: List<String>,
    val question: String,
    /** 四个选项，下标 0–3 对应 A–D */
    val options: List<String>,
    val answer: Int,
    /** 依据句，从 1 开始 */
    val evidence: Set<Int>,
    /** 每个错误选项的错因（取自 [Flaws.ALL]） */
    val flaws: Map<Int, String>,
    val explain: String,
) {
    fun flawOf(option: Int): String = if (option == answer) Flaws.NONE else flaws[option] ?: "答非所问"

    fun toJson(): String = JSONObject()
        .put("skill", skill.name)
        .put("difficulty", difficulty)
        .put("passage", JSONArray(sentences))
        .put("question", question)
        .put("options", JSONObject().apply { options.forEachIndexed { i, o -> put(letter(i), o) } })
        .put("answer", letter(answer))
        .put("evidence", JSONArray(evidence.sorted()))
        .put("flaws", JSONObject().apply { flaws.forEach { (k, v) -> put(letter(k), v) } })
        .put("explain", explain)
        .toString()

    companion object {
        fun letter(i: Int): String = "ABCD"[i].toString()
        fun index(letter: String): Int? = "ABCD".indexOf(letter.trim().uppercase().take(1)).takeIf { it >= 0 && letter.isNotBlank() }

        private val CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩"
        fun mark(n: Int): String = if (n in 1..10) CIRCLED[n - 1].toString() else "($n)"

        /**
         * 从模型返回的 JSON 读一张题卡，并检查它自洽：四个选项、答案是 A–D、依据句在范围内、
         * 每个错误选项都有固定表里的错因。不自洽就抛 [BadCard]，让调用方重出。
         */
        fun parse(json: JSONObject, fallbackSkill: Skill? = null): Card {
            val skill = Skill.of(json.optString("skill")) ?: fallbackSkill ?: throw BadCard("skill 不对")
            val passage = json.optJSONArray("passage") ?: throw BadCard("缺 passage")
            val sentences = (0 until passage.length()).map { passage.optString(it).trim().trimStart(*"①②③④⑤⑥⑦⑧⑨⑩".toCharArray()).trim() }
                .filter { it.isNotEmpty() }
            if (sentences.size !in 2..10) throw BadCard("材料要 2–10 句")
            val question = json.optString("question").trim().ifEmpty { throw BadCard("缺 question") }
            val opts = json.optJSONObject("options") ?: throw BadCard("缺 options")
            val options = (0..3).map { opts.optString(letter(it)).trim() }
            if (options.any { it.isEmpty() }) throw BadCard("选项要 A–D 四个")
            if (options.toSet().size < 4) throw BadCard("选项有重复")
            val answer = index(json.optString("answer")) ?: throw BadCard("answer 不是 A–D")
            val evArr = json.optJSONArray("evidence") ?: throw BadCard("缺 evidence")
            val evidence = (0 until evArr.length()).map { evArr.optInt(it, -1) }.filter { it in 1..sentences.size }.toSet()
            if (evidence.isEmpty()) throw BadCard("依据句不在材料范围里")
            val fl = json.optJSONObject("flaws") ?: JSONObject()
            val flaws = (0..3).filter { it != answer }.associateWith { i ->
                val f = fl.optString(letter(i)).trim()
                if (f in Flaws.ALL) f else throw BadCard("${letter(i)} 的错因不在表里：$f")
            }
            return Card(
                skill = skill,
                difficulty = json.optInt("difficulty", 1).coerceIn(1, 3),
                sentences = sentences,
                question = question,
                options = options,
                answer = answer,
                evidence = evidence,
                flaws = flaws,
                explain = json.optString("explain").trim(),
            )
        }

        fun fromJson(text: String): Card = parse(JSONObject(text))
    }
}

class BadCard(message: String) : Exception(message)
