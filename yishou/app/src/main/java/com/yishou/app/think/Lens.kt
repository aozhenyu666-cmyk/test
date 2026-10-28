package com.yishou.app.think

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * 练法：同一段素材，换一种思维动作来练。
 * 每种练法都是同一个骨架——锚（一段素材、一个问题）→ 押（你先说）→ 照（对照事先锁住的答案或现实）→ 收（一句话，结束）。
 * 判卷的标准在你作答之前就定好；模型说你“说到了”，必须拿得出你的原话。
 */
enum class Lens(val label: String, val short: String, val ask: String, val minSubstance: Int, val face: Int) {
    GIST("抓主干", "一句话说它到底在说什么", "用一句话说出这段的主干：它最想让人相信什么？", 8, 1),
    /** 精简记忆：看一会儿，一手把原文收起来，你复述，本机逐字对照 */
    RECITE("背下来", "看一会儿，原文收起，复述出来", "原文已经收起来了。把它复述出来：", 2, 1),
    PREDICT("押后果", "押一个过几天能核对的判断", "这件事接下来会怎样？押一个过几天能核对的判断。", 6, 2),
    ESTIMATE("估个数", "给一个你有九成把握的区间", "先别查，给一个你有九成把握的区间：", 0, 2),
    /** 联想：这像什么、相同在哪、不同在哪 */
    LINK("找相似", "它像什么？相同在哪，不同在哪", "它像你见过的什么？", 0, 3),
    ASSUME("挖前提", "说出它没写、但必须成立的那一条", "要让这个结论成立，必须有哪条它没写出来的前提？", 6, 4),
    STANCE("站立场", "站一边，给理由，替对方说话，说清什么时候改主意", "你站哪一边？", 0, 4),
    /** 体系：用几个概念和它们的关系给这段内容定位 */
    FRAME("搭关系", "用几个概念和它们之间的关系，把这段搭起来", "这段里最重要的几个概念，彼此是什么关系？一行写一条：A 和 B——什么关系。", 8, 5),
    /** 回响：隔了几天，不看原文，说出当时那段的主干 */
    ECHO("回想", "不看原文，说出以前练过的一段讲了什么", "不看原文：这段当时讲的是什么？主干和要点尽量说全。", 8, 6),
    /** 题库里的申论题：参考要点导入时就锁住，按采分点给分 */
    ESSAY("申论作答", "对着题目写，按参考要点的采分点给分", "按题目要求作答：", 10, 1),
    ;

    companion object {
        fun of(name: String?): Lens? = entries.firstOrNull { it.name == name }

        /** 拿一段素材就能练的（申论、回想要特定的题或旧素材，不在里面） */
        val MATERIAL_LENSES = listOf(GIST, RECITE, PREDICT, ESTIMATE, LINK, ASSUME, STANCE, FRAME)

        /** 六骰子每一面下面的练法 */
        fun ofFace(face: Int): List<Lens> = entries.filter { it.face == face && it != ESSAY }

        /** 本机粗看一眼素材，排一下哪几种练法最合适（第一个是推荐） */
        fun suggest(text: String): List<Lens> {
            val digits = Regex("\\d").findAll(text).count()
            val debate = listOf("该不该", "应不应该", "是否", "值不值", "要不要", "能不能", "？", "?").any { it in text }
            val news = listOf("将", "预计", "宣布", "计划", "发布", "上涨", "下跌", "据报道").count { it in text } >= 2
            val argue = listOf("因为", "所以", "因此", "可见", "说明", "必须", "只有", "才能").count { it in text } >= 2
            val first = when {
                debate -> STANCE
                news -> PREDICT
                argue -> ASSUME
                digits >= 4 -> ESTIMATE
                else -> GIST
            }
            return listOf(first) + MATERIAL_LENSES.filter { it != first }
        }
    }
}

/** 找相似（联想）的三项 */
enum class LinkItem(val label: String, val hint: String, val standard: String) {
    LIKE("像什么", "它像……（另一个领域里的具体例子）", "一个具体的、来自别的领域或生活里的例子；同一件事换个说法不算"),
    SAME("相同在哪", "相同在……（背后同一个机制或结构）", "说出两者背后相同的机制、结构或因果，不是表面相似（都很重要、都是问题不算）"),
    DIFF("不同在哪", "不同在……（哪里不能照搬）", "说出一处会让类比失效、不能照搬的具体差别"),
}

/** 站立场的四项，各填各的 */
enum class StanceItem(val label: String, val hint: String) {
    SIDE("立场", "我站……（可以带条件）"),
    WHY("理由", "因为……（针对这个问题的具体理由）"),
    AGAINST("反方最强的一条", "对我最不利的是……"),
    FLIP("什么情况我会改主意", "如果看到……，我就改"),
}

class BadKey(message: String) : Exception(message)

/**
 * 锁住的答案。你作答之前就定好、存下，作答后才给你看。
 * - 抓主干：points[0] 是主干，其余是要点；
 * - 挖前提：prompt 是要挖的结论，points 是认可的前提（说中一条就算）；
 * - 站立场：prompt 是问题，points 是正反各一条最强理由（只用来事后对照，不判分）；
 * - 估个数：prompt 是问题，value / unit 是参考答案，method 是怎么拆着估；
 * - 押后果：只有 prompt，没有答案，交给现实。
 */
data class DrillKey(
    val lens: Lens,
    val prompt: String,
    val points: List<String> = emptyList(),
    val value: Double? = null,
    val unit: String = "",
    val method: String = "",
    val explain: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("lens", lens.name)
        .put("prompt", prompt)
        .put("points", JSONArray(points))
        .put("value", value ?: JSONObject.NULL)
        .put("unit", unit)
        .put("method", method)
        .put("explain", explain)
        .toString()

    companion object {
        fun fromJson(s: String): DrillKey {
            val o = JSONObject(s)
            val lens = Lens.of(o.optString("lens")) ?: throw BadKey("lens")
            val pts = o.optJSONArray("points") ?: JSONArray()
            return DrillKey(
                lens = lens,
                prompt = o.optString("prompt"),
                points = (0 until pts.length()).map { pts.getString(it) },
                value = if (o.isNull("value")) null else o.optDouble("value").takeIf { !it.isNaN() },
                unit = o.optString("unit"),
                method = o.optString("method"),
                explain = o.optString("explain"),
            )
        }

        private fun JSONObject.text(k: String) = if (isNull(k)) "" else optString(k, "").trim()

        private fun JSONObject.list(k: String): List<String> {
            val a = optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
        }

        /** 把模型定的答案读成 [DrillKey]；缺东西就抛 [BadKey]，上层重试 */
        fun parse(lens: Lens, o: JSONObject): DrillKey = when (lens) {
            Lens.GIST -> {
                val main = o.text("main").ifEmpty { throw BadKey("缺 main") }
                val pts = o.list("points").take(3)
                if (pts.isEmpty()) throw BadKey("缺 points")
                DrillKey(lens, Lens.GIST.ask, listOf(main) + pts, explain = o.text("explain"))
            }
            Lens.ASSUME -> {
                val claim = o.text("claim").ifEmpty { throw BadKey("缺 claim") }
                val acc = o.list("accepted").take(4)
                if (acc.isEmpty()) throw BadKey("缺 accepted")
                DrillKey(lens, claim, acc, explain = o.text("explain"))
            }
            Lens.STANCE -> {
                val q = o.text("question").ifEmpty { throw BadKey("缺 question") }
                DrillKey(lens, q, listOf(o.text("pro"), o.text("con")).filter { it.isNotEmpty() }, explain = o.text("explain"))
            }
            Lens.ESTIMATE -> {
                val q = o.text("question").ifEmpty { throw BadKey("缺 question") }
                val v = o.optDouble("value")
                if (v.isNaN() || v <= 0) throw BadKey("value 不是正数")
                DrillKey(lens, q, value = v, unit = o.text("unit"), method = o.text("method"), explain = o.text("explain"))
            }
            Lens.PREDICT -> DrillKey(lens, o.text("question").ifEmpty { Lens.PREDICT.ask })
            Lens.ECHO -> {
                val main = o.text("main").ifEmpty { throw BadKey("缺 main") }
                DrillKey(lens, Lens.ECHO.ask, listOf(main) + o.list("points").take(3), explain = o.text("explain"))
            }
            Lens.LINK -> {
                val target = o.text("target").ifEmpty { o.text("question") }.ifEmpty { throw BadKey("缺 target") }
                DrillKey(lens, target, o.list("points").take(3), explain = o.text("explain"))
            }
            Lens.FRAME -> {
                val rel = o.list("points").take(6)
                if (rel.size < 2) throw BadKey("关系太少")
                DrillKey(lens, o.text("topic").ifEmpty { Lens.FRAME.ask }, rel, explain = o.text("explain"))
            }
            Lens.RECITE -> DrillKey(lens, o.text("title").ifEmpty { "一段" }, listOf(o.text("text").ifEmpty { throw BadKey("缺 text") }))
            Lens.ESSAY -> {
                val q = o.text("question").ifEmpty { throw BadKey("缺 question") }
                val pts = o.list("points")
                if (pts.isEmpty()) throw BadKey("缺 points")
                DrillKey(lens, q, pts, value = o.optDouble("limit").takeIf { !it.isNaN() }, explain = o.text("explain"))
            }
        }
    }
}

/** 一条判定：说中没有、原话、差在哪 */
data class Check(val label: String, val ok: Boolean, val quote: String = "", val note: String = "") {
    fun toJson(): JSONObject = JSONObject().put("label", label).put("ok", ok).put("quote", quote).put("note", note)

    companion object {
        fun fromJson(o: JSONObject) = Check(o.optString("label"), o.optBoolean("ok"), o.optString("quote"), o.optString("note"))

        fun listToJson(xs: List<Check>): String = JSONArray().apply { xs.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String): List<Check> = runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

/** 模型逐条给出的判断（还没核对原话） */
data class RawHit(val hit: Boolean, val quote: String, val note: String)

object Judge {

    /**
     * 核对原话：模型说“说中了”，原话却不在你的回答里，就改判为没说中。
     * 这是本机的硬规则，模型放水也过不去。
     */
    fun verify(label: String, raw: RawHit?, answer: String): Check {
        if (raw == null) return Check(label, false, note = "没判到这一条")
        val ok = raw.hit && Words.quoted(answer, raw.quote)
        val note = when {
            ok -> ""
            raw.hit -> "模型说你说到了，但引的话不在你的回答里，不算"
            else -> raw.note
        }
        return Check(label, ok, if (ok) raw.quote else "", note)
    }

    /** 抓主干：主干占 60 分，其余要点平分 40 分 */
    fun gistScore(checks: List<Check>): Int {
        if (checks.isEmpty()) return 0
        val main = if (checks[0].ok) 60 else 0
        val rest = checks.drop(1)
        val pts = if (rest.isEmpty()) 0 else 40 * rest.count { it.ok } / rest.size
        return main + pts
    }

    /** 申论：按采分点给分（说中几条占几成）；超字数扣 10 分 */
    fun essayScore(checks: List<Check>, over: Boolean): Int {
        val pts = checks.filter { it.label.startsWith("要点") }
        if (pts.isEmpty()) return 0
        return (100 * pts.count { it.ok } / pts.size - if (over) 10 else 0).coerceAtLeast(0)
    }

    /** 题目要求的字数（去掉空白标点后数） */
    fun overLimit(answer: String, limit: Int?): Boolean = limit != null && Words.norm(answer).length > limit

    /** 说中几条占几成（体系的关系、申论的要点） */
    fun coverScore(checks: List<Check>): Int = if (checks.isEmpty()) 0 else 100 * checks.count { it.ok } / checks.size

    /** 挖前提：说中任意一条认可的前提就算 */
    fun assumeScore(checks: List<Check>): Int = if (checks.any { it.ok }) 100 else 0

    /** 站立场：四项各 20，接住反驳再 20（跳过追问就没有这 20） */
    fun stanceScore(checks: List<Check>): Int = checks.count { it.ok } * 20

    /** 太短（去掉口头禅后的实在字数不够）时给一句提示，不判 */
    fun tooThin(lens: Lens, answer: String): String? {
        val n = Words.substance(answer)
        if (n >= lens.minSubstance) return null
        return when (lens) {
            Lens.GIST -> "太短了，去掉口头禅只剩 $n 个字。把“谁、怎样、所以什么”说完整。"
            Lens.ASSUME -> "太短了。说一句完整的话：“它默认了……”。"
            Lens.PREDICT -> "说具体一点：什么事，到什么时候，会怎样。"
            Lens.ESSAY -> "太短了，按题目要求把要点写出来。"
            Lens.FRAME -> "至少写两条：A 和 B——什么关系。"
            Lens.ECHO -> "想到多少写多少，至少一句完整的话。"
            Lens.RECITE -> "还什么都没写。"
            else -> "说具体一点。"
        }
    }

    /** 站立场每一项至少要有几个实在字，不够就提示补上哪一项 */
    fun stanceThin(parts: Map<StanceItem, String>): String? {
        // 立场可以很短（“该”“不该”），其余三项至少要有几个实在的字
        val empty = StanceItem.entries.filter { Words.substance(parts[it].orEmpty()) < if (it == StanceItem.SIDE) 1 else 4 }
        if (empty.isEmpty()) return null
        return "这几项还空着或太短：" + empty.joinToString("、") { it.label }
    }
}

/**
 * 估个数：给一个九成把握的区间。
 * 落在区间里才算中；区间越窄分越高。长期看，说“九成把握”的区间应该九成都中——中得太少是自信过头，全中是区间太宽。
 */
object Estimate {

    /** 区间：从一段话里取前两个数，小的在前；只有一个数时上下各放 10% */
    fun range(text: String): Pair<Double, Double>? {
        val xs = Words.numbers(text).filter { it > 0 }
        return when {
            xs.isEmpty() -> null
            xs.size == 1 -> xs[0] / 1.1 to xs[0] * 1.1
            else -> minOf(xs[0], xs[1]) to max(xs[0], xs[1])
        }
    }

    fun hit(low: Double, high: Double, truth: Double): Boolean = truth in low..high

    /**
     * 0–100。中了：高低比 ≤2 给 100，≤4 给 80，≤10 给 60，再宽给 40。
     * 没中：离区间最近一端差 2 倍以内给 20，否则 0。
     */
    fun score(low: Double, high: Double, truth: Double): Int {
        if (low <= 0 || high <= 0 || truth <= 0) return 0
        if (hit(low, high, truth)) {
            val ratio = high / low
            return when {
                ratio <= 2 -> 100
                ratio <= 4 -> 80
                ratio <= 10 -> 60
                else -> 40
            }
        }
        val off = if (truth < low) low / truth else truth / high
        return if (off <= 2) 20 else 0
    }

    /** 偏了几倍（对数距离，0 表示中了） */
    fun off(low: Double, high: Double, truth: Double): Double = when {
        truth < low -> ln(low / truth)
        truth > high -> ln(truth / high)
        else -> 0.0
    }

    /** 两次独立估的参考值差不多（1.5 倍以内）才用这道题 */
    fun agree(a: Double, b: Double): Boolean = a > 0 && b > 0 && max(a / b, b / a) <= 1.5

    /** 好读的数：12000 → 1.2万 */
    fun show(v: Double): String = when {
        abs(v) >= 1e8 -> trim(v / 1e8) + "亿"
        abs(v) >= 1e4 -> trim(v / 1e4) + "万"
        else -> trim(v)
    }

    private fun trim(v: Double): String {
        val s = if (abs(v) >= 100) "%.0f".format(v) else "%.2f".format(v)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    /** 区间命中率：说九成把握，实际中了几成 */
    fun calibration(hits: List<Boolean>): String {
        if (hits.size < 5) return "估过 ${hits.size} 次，攒够 5 次再看区间准不准。"
        val rate = hits.count { it } * 100 / hits.size
        return when {
            rate < 70 -> "九成把握的区间只中了 $rate%：区间给窄了，自信过头。下次先想想最低和最高能到哪。"
            rate > 97 -> "全都中了：区间可能给得太宽，试着收窄一点。"
            else -> "九成把握的区间中了 $rate%，挺准。"
        }
    }
}
