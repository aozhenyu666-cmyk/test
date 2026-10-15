package com.yishou.app.llm

import com.yishou.app.log.RunLog
import com.yishou.app.think.BadKey
import com.yishou.app.think.DrillKey
import com.yishou.app.think.Lens
import com.yishou.app.think.RawHit
import com.yishou.app.think.StanceItem
import org.json.JSONObject

/** 写素材、锁答案、判卷。判卷只逐条说“说到没有”和你的原话，分数在本机算。 */
interface DrillGen {
    /** 写一段练习用的素材（没东西可练时用）；返回 标题 to 正文 */
    suspend fun material(topic: String?, avoid: List<String>): LlmResult<Pair<String, String>>

    /** 作答之前锁住答案。material 为 null 时只有估个数能出（常识量） */
    suspend fun key(lens: Lens, material: String?): LlmResult<DrillKey>

    /** 估个数：另一次不看答案独立估，用来核对参考值 */
    suspend fun reestimate(question: String, material: String?): LlmResult<Double>

    /** 抓主干 / 挖前提：逐条判 */
    suspend fun judgePoints(key: DrillKey, answer: String): LlmResult<List<RawHit?>>

    /** 站立场：四项逐项判，外加一条最有力的反驳 */
    suspend fun judgeStance(question: String, parts: Map<StanceItem, String>): LlmResult<Pair<List<RawHit?>, String>>

    /** 站立场：回应有没有接住反驳 */
    suspend fun judgeReply(question: String, counter: String, reply: String): LlmResult<RawHit>

    /** 按固定的几项标准逐项判（找相似）：items 是 标签 to 标准，parts 是每项的回答 */
    suspend fun judgeRubric(question: String, items: List<Pair<String, String>>, parts: List<String>): LlmResult<List<RawHit?>>
}

object DrillPrompts {
    /** 出题、写素材：带标签的纯文本，不要 JSON */
    val MAKER = """
        你是思维训练的出题人。答案要在用户作答之前定死，写得具体、可核对。
        严格按要求的【标签】格式输出纯文本，每个标签单独起一行，不要 JSON，不要代码块，不要开场白和结尾。
    """.trimIndent()

    /** 判卷：只看用户写出来的字，返回 JSON */
    val SYSTEM = """
        你是思维训练的判卷人。
        只看用户写出来的字，不替他补全意思；只说出相关的词、没说出关系的，不算；口水话、套话不算。
        说“算”的时候，必须引用用户回答里的一段原话（连续的一段，不改字）；引不出原话，就是不算。
        只返回一个 JSON 对象，不要任何其他文字，不要用代码块包裹。
    """.trimIndent()

    /** 标签名 → 读出来的键 */
    val NAMES = mapOf(
        "标题" to "title", "正文" to "text",
        "主干" to "main", "要点" to "points", "解析" to "explain", "说明" to "explain",
        "结论" to "claim", "前提" to "accepted",
        "问题" to "question", "题目" to "question", "正方" to "pro", "反方" to "con",
        "参考值" to "value", "单位" to "unit", "拆法" to "method", "字数" to "limit",
        "对象" to "target", "参考" to "points", "关系" to "points", "主题" to "topic",
    )
    val LISTS = setOf("points", "accepted")
    val NUMBERS = setOf("value", "limit")

    private fun withMaterial(material: String?) = if (material == null) "" else "材料：\n$material\n\n"

    fun material(topic: String?, avoid: List<String>) = buildString {
        appendLine("写一段供思维训练用的中文材料（180–320 字），像真实世界里会读到的东西：评论、新闻解读、广告说法、政策解读、读书笔记、职场经验帖、申论给定资料，换着来。")
        appendLine("要求：有一个明确的结论；有两三条理由；至少藏着一个没说出来的前提；可以有一两个具体数字；不要写成教科书，不要在文中点评自己。")
        appendLine("不要在正文里出现“前提”“假设”“我认为最关键的是”这类字眼，不要替读者总结，不要写字数、标题号或任何说明。")
        appendLine(if (topic.isNullOrBlank()) "话题自选，社会治理、基层工作、经济、科技、教育、健康、城市生活都可以。" else "话题：$topic")
        if (avoid.isNotEmpty()) appendLine("最近用过这些标题，换个话题：" + avoid.take(10).joinToString("、"))
        appendLine()
        appendLine("格式：")
        appendLine("【标题】不超过 12 个字")
        append("【正文】……")
    }

    fun key(lens: Lens, material: String?): String = withMaterial(material) + when (lens) {
        Lens.GIST, Lens.ECHO -> """
            为这段材料定一份“抓主干”的答案，用户还没作答，答案先锁住。
            格式：
            【主干】这段最想让读者相信或接受的那一句（不超过 30 字，用你的话，要包含结论和关键关系）
            【要点】
            1. 撑起结论的关键点（每条不超过 20 字，两到三条，例子和铺垫不算）
            2. ……
            【解析】两句话说明为什么主干是这个
        """.trimIndent()
        Lens.ASSUME -> """
            从材料里挑一个最关键的结论，定出它没写出来、但必须成立才推得出的前提。
            材料里已经写出来的理由不是前提；前提是作者默认读者会接受、但其实可以质疑的那一步。
            格式：
            【结论】贴近原文，不超过 40 字
            【前提】
            1. 不超过 25 字（两到四条）
            2. ……
            【解析】一两句说明最关键的那条前提为什么可疑
        """.trimIndent()
        Lens.STANCE -> """
            从材料里提炼一个可以正反两面讨论的问题；材料本身就是个问题就直接用它。
            格式：
            【问题】不超过 30 字，用“该不该”“是否”“值不值”这类问法
            【正方】最有力的一条理由，不超过 40 字
            【反方】最有力的一条理由，不超过 40 字
        """.trimIndent()
        Lens.ESTIMATE -> (if (material != null) {
            "出一道估算题：用材料里给的数字作为线索，问一个材料没有直接给出、但能一步两步推出来的量。题目里不要把答案写出来。\n"
        } else {
            "出一道估算题（费米估算）：问一个有公认数值的量（人口、距离、产量、时长、价格这类常识量级），不要问冷门数据，不要问会随时间大变的数。\n"
        }) + """
            格式：
            【题目】……
            【参考值】一个数
            【单位】……
            【拆法】怎么拆着估，两三步
            【解析】参考值的依据
        """.trimIndent()
        Lens.LINK -> """
            从材料里挑出最值得“找相似”的那个机制或道理（一句话），再写两个好的类比做参考（事后给用户对照，不先给他看）。
            格式：
            【对象】不超过 30 字
            【参考】
            1. 像……：相同在……，不同在……
            2. ……
        """.trimIndent()
        Lens.FRAME -> """
            把这段材料里最重要的三到五个概念找出来，写出它们之间的关系（属于、导致、制约、前提、对立、手段与目的……）。
            格式：
            【主题】这段在讲什么，不超过 15 字
            【关系】
            1. A —— 关系 —— B（每条不超过 25 字，三到五条）
            2. ……
            【解析】一句话说明这几条怎么连成一张图
        """.trimIndent()
        Lens.ESSAY -> """
            根据材料出一道申论归纳概括题，并按采分点给出参考要点。
            格式：
            【题目】……（不超过 150 字）
            【字数】150
            【要点】
            1. ……
            2. ……
            【解析】……
        """.trimIndent()
        Lens.PREDICT -> """
            根据材料，问一个“接下来会怎样”的问题，要能在几天到几个月后用公开信息核对。
            格式：
            【问题】不超过 40 字
        """.trimIndent()
        Lens.RECITE -> "【标题】一段\n【正文】" + (material ?: "")
    }

    fun reestimate(question: String, material: String?) = withMaterial(material) +
        "请独立估算：$question\n只回答一行：【参考值】一个数"

    fun judgePoints(key: DrillKey, answer: String) = buildString {
        if (key.lens == Lens.ASSUME) appendLine("要挖前提的结论：${key.prompt}")
        if (key.lens == Lens.ESSAY) appendLine("申论题目：${key.prompt}")
        appendLine(
            when (key.lens) {
                Lens.GIST, Lens.ECHO -> "锁住的答案（第 1 条是主干，其余是要点）："
                Lens.ESSAY -> "参考要点（按采分点逐条判，意思到了就算，只写了词没写出内容的不算）："
                Lens.FRAME -> "锁住的概念关系（逐条判：两个概念都提到、关系也说对才算；只列概念不算）："
                else -> "认可的前提（说中任意一条就算）："
            },
        )
        key.points.forEachIndexed { i, p -> appendLine("${i + 1}. $p") }
        appendLine()
        appendLine("用户的回答：「$answer」")
        appendLine()
        appendLine("逐条判断用户有没有说出这一条的意思。换个说法可以，意思要到；只沾到词、没说出关系，不算。")
        appendLine("算的话 quote 填用户回答里对应的原话（连续一段，一个字都不改）；不算的话 quote 留空，note 用一句话说差在哪（不超过 25 字）。")
        append("""只返回 JSON：{"items": [{"n": 1, "hit": true, "quote": "……", "note": ""}]}""")
    }

    fun judgeStance(question: String, parts: Map<StanceItem, String>) = buildString {
        appendLine("问题：$question")
        appendLine("用户的四项回答：")
        StanceItem.entries.forEachIndexed { i, it -> appendLine("${i + 1}. ${it.label}：${parts[it].orEmpty()}") }
        appendLine()
        appendLine("逐项判：")
        appendLine("1. 立场：明确站一边（可以带条件）；“都有道理”“看情况”不算。")
        appendLine("2. 理由：针对这个问题的具体理由；“对社会好”“很重要”这类空话不算。")
        appendLine("3. 反方最强的一条：真正对他的立场不利，不是稻草人，也不能和他同向。")
        appendLine("4. 什么情况我会改主意：一个看得见、能核对的条件（什么证据、数字、情况出现）；“如果我错了”不算。")
        appendLine("每项 quote 只能引这一项里的原话（连续一段，不改字）；不合格时 note 一句话说缺什么（不超过 25 字）。")
        appendLine("另外写 counter：针对他的立场，最有力的一条反驳（不超过 60 字），要具体，用来追问他。")
        append("""只返回 JSON：{"items": [{"n": 1, "hit": true, "quote": "……", "note": ""}], "counter": "……"}""")
    }

    fun judgeReply(question: String, counter: String, reply: String) = buildString {
        appendLine("问题：$question")
        appendLine("对他的反驳：$counter")
        appendLine("他的回应：「$reply」")
        appendLine("回应有没有正面接住反驳？承认并加限定、给出反证、修正立场，都算；转移话题、重复原话、只表态，不算。")
        append("""只返回 JSON：{"hit": true, "quote": "回应里的原话", "note": "不算时一句话说缺什么"}""")
    }
}

object RubricPrompt {
    fun build(question: String, items: List<Pair<String, String>>, parts: List<String>) = buildString {
        appendLine("对象：$question")
        appendLine("用户的回答：")
        items.forEachIndexed { i, (label, _) -> appendLine("${i + 1}. $label：${parts.getOrNull(i).orEmpty()}") }
        appendLine()
        appendLine("逐项判，标准：")
        items.forEachIndexed { i, (label, standard) -> appendLine("${i + 1}. $label：$standard") }
        appendLine("每项 quote 只能引这一项里的原话（连续一段，不改字）；不合格时 note 一句话说缺什么（不超过 25 字）。")
        append("""只返回 JSON：{"items": [{"n": 1, "hit": true, "quote": "……", "note": ""}]}""")
    }
}

class LlmDrillGen(private val chat: ChatClient) : DrillGen {

    override suspend fun material(topic: String?, avoid: List<String>): LlmResult<Pair<String, String>> =
        make(DrillPrompts.material(topic, avoid), "写素材") { o ->
            val text = o.optString("text").trim()
            if (text.length < 60) throw BadKey("素材太短")
            o.optString("title").trim().ifEmpty { text.take(12) } to text
        }

    override suspend fun key(lens: Lens, material: String?): LlmResult<DrillKey> =
        make(DrillPrompts.key(lens, material?.take(MAX_MATERIAL)), "锁答案") { DrillKey.parse(lens, it) }

    override suspend fun reestimate(question: String, material: String?): LlmResult<Double> =
        make(DrillPrompts.reestimate(question, material?.take(MAX_MATERIAL)), "核对估算") { o ->
            o.optDouble("value").takeIf { !it.isNaN() && it > 0 } ?: throw BadKey("value 不是正数")
        }

    override suspend fun judgePoints(key: DrillKey, answer: String): LlmResult<List<RawHit?>> =
        request(DrillPrompts.judgePoints(key, answer), "判卷") { o -> items(o, key.points.size) }

    override suspend fun judgeStance(question: String, parts: Map<StanceItem, String>): LlmResult<Pair<List<RawHit?>, String>> =
        request(DrillPrompts.judgeStance(question, parts), "判卷") { o ->
            items(o, StanceItem.entries.size) to o.optString("counter").trim().ifEmpty { throw BadKey("缺 counter") }
        }

    override suspend fun judgeReply(question: String, counter: String, reply: String): LlmResult<RawHit> =
        request(DrillPrompts.judgeReply(question, counter, reply), "判卷") { o ->
            RawHit(o.optBoolean("hit"), o.optString("quote"), o.optString("note"))
        }

    override suspend fun judgeRubric(question: String, items: List<Pair<String, String>>, parts: List<String>): LlmResult<List<RawHit?>> =
        request(RubricPrompt.build(question, items, parts), "判卷") { o -> items(o, items.size) }

    /** 出题、写素材：要带标签的纯文本（不开 JSON 模式），读的时候 JSON 也认 */
    private suspend fun <T> make(user: String, kind: String, parse: (JSONObject) -> T): LlmResult<T> {
        var last: LlmError? = null
        repeat(2) {
            when (val r = chat.complete(DrillPrompts.MAKER, user, kind, json = false)) {
                is LlmResult.Err -> return r
                is LlmResult.Ok -> try {
                    return LlmResult.Ok(parse(Tagged.toJson(r.value, DrillPrompts.NAMES, DrillPrompts.LISTS, DrillPrompts.NUMBERS)))
                } catch (e: BadKey) {
                    last = LlmError.Local("模型这次写的缺了一块（${e.message}），再试一次")
                } catch (e: org.json.JSONException) {
                    last = LlmError.BadFormat(e.message ?: "")
                }
            }
        }
        val error = last ?: LlmError.BadFormat("")
        RunLog.e(kind, "两次都没得到能用的结果：${error.message}")
        return LlmResult.Err(error)
    }

    /** items 按 n（从 1 起）对回各条；缺的那条为 null，本机判为没说中 */
    private fun items(o: JSONObject, size: Int): List<RawHit?> {
        val a = o.optJSONArray("items") ?: throw BadKey("缺 items")
        val out = arrayOfNulls<RawHit>(size)
        for (i in 0 until a.length()) {
            val it = a.optJSONObject(i) ?: continue
            val n = it.optInt("n", i + 1) - 1
            if (n in 0 until size) out[n] = RawHit(it.optBoolean("hit"), it.optString("quote"), it.optString("note"))
        }
        return out.toList()
    }

    private suspend fun <T> request(user: String, kind: String, parse: (JSONObject) -> T): LlmResult<T> {
        var last: LlmError? = null
        repeat(2) {
            when (val r = chat.complete(DrillPrompts.SYSTEM, user, kind)) {
                is LlmResult.Err -> return r
                is LlmResult.Ok -> try {
                    return LlmResult.Ok(parse(CoachParser.extractJsonObject(r.value)))
                } catch (e: BadKey) {
                    last = LlmError.BadFormat(e.message ?: "")
                } catch (e: BadJsonException) {
                    last = LlmError.BadFormat(e.message ?: "")
                } catch (e: org.json.JSONException) {
                    last = LlmError.BadFormat(e.message ?: "")
                }
            }
        }
        val error = last ?: LlmError.BadFormat("")
        RunLog.e(kind, "两次都没得到能用的结果：${error.message}")
        return LlmResult.Err(error)
    }

    companion object {
        const val MAX_MATERIAL = 2400
    }
}
