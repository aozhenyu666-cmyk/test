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
}

object DrillPrompts {
    val SYSTEM = """
        你是思维训练的出题人和判卷人。
        出题时：答案要在用户作答之前定死，写得具体、可核对。
        判卷时：只看用户写出来的字，不替他补全意思；只说出相关的词、没说出关系的，不算；口水话、套话不算。
        说“算”的时候，必须引用用户回答里的一段原话（连续的一段，不改字）；引不出原话，就是不算。
        只返回一个 JSON 对象，不要任何其他文字，不要用代码块包裹。
    """.trimIndent()

    private fun withMaterial(material: String?) = if (material == null) "" else "材料：\n$material\n\n"

    fun material(topic: String?, avoid: List<String>) = buildString {
        appendLine("写一段供思维训练用的中文材料（180–320 字），像真实世界里会读到的东西：评论、新闻解读、广告说法、政策解读、读书笔记、朋友圈长文、职场经验帖，换着来。")
        appendLine("要求：有一个明确的结论；有两三条理由；至少藏着一个没说出来的前提；可以有一两个具体数字；不要写成教科书，不要在文中点评自己，不要标出哪里是前提。")
        appendLine(if (topic.isNullOrBlank()) "话题自选，日常、消费、工作、学习、科技、健康、城市生活都可以。" else "话题：$topic")
        if (avoid.isNotEmpty()) appendLine("最近用过这些标题，换个话题：" + avoid.take(10).joinToString("、"))
        appendLine()
        append("""只返回 JSON：{"title": "不超过 12 个字的标题", "text": "正文"}""")
    }

    fun key(lens: Lens, material: String?): String = withMaterial(material) + when (lens) {
        Lens.GIST -> """
            为这段材料定一份“抓主干”的答案，用户还没作答，答案先锁住。
            main：这段最想让读者相信或接受的那一句（不超过 30 字，用你的话说，要包含它的结论和关键关系）。
            points：撑起这个结论的两到三个关键点（每条不超过 20 字），例子和铺垫不算。
            explain：两句话说明为什么主干是这个（哪里是转折、哪些只是例子）。
            只返回 JSON：{"main": "……", "points": ["……", "……"], "explain": "……"}
        """.trimIndent()
        Lens.ASSUME -> """
            从材料里挑一个最关键的结论（claim，贴近原文，不超过 40 字），
            定出它没写出来、但必须成立才推得出这个结论的前提，两到四条（accepted，每条不超过 25 字）。
            材料里已经写出来的理由不是前提；前提是作者默认读者会接受、但其实可以质疑的那一步。
            explain：一两句说明最关键的那条前提为什么可疑。
            只返回 JSON：{"claim": "……", "accepted": ["……", "……"], "explain": "……"}
        """.trimIndent()
        Lens.STANCE -> """
            从材料里提炼一个可以正反两面讨论的问题（question，不超过 30 字，以“该不该”“是否”“值不值”这类问法），
            如果材料本身就是个问题，直接用它。再写正反各一条最有力的理由（pro、con，各不超过 40 字），事后给用户对照。
            只返回 JSON：{"question": "……", "pro": "……", "con": "……", "explain": ""}
        """.trimIndent()
        Lens.ESTIMATE -> (if (material != null) {
            "出一道估算题：用材料里给的数字作为线索，问一个材料没有直接给出、但能一步两步推出来的量。题目里不要把答案写出来。\n"
        } else {
            "出一道估算题（费米估算）：问一个有公认数值的量（人口、距离、产量、时长、价格这类常识量级），不要问冷门数据，不要问会随时间大变的数。\n"
        }) + """
            value：你的参考答案（一个数），unit：单位，method：怎么拆着估（两三步），explain：参考答案的依据。
            只返回 JSON：{"question": "……", "value": 123, "unit": "……", "method": "……", "explain": "……"}
        """.trimIndent()
        Lens.ESSAY -> """
            根据材料出一道申论归纳概括题，并按采分点给出参考要点。
            只返回 JSON：{"question": "……（不超过 150 字）", "points": ["……", "……"], "limit": 150, "explain": "……"}
        """.trimIndent()
        Lens.PREDICT -> """
            根据材料，问一个“接下来会怎样”的问题，要能在几天到几个月后用公开信息核对（question，不超过 40 字）。
            只返回 JSON：{"question": "……"}
        """.trimIndent()
    }

    fun reestimate(question: String, material: String?) = withMaterial(material) +
        "请独立估算：$question\n只返回 JSON：{\"value\": 一个数}"

    fun judgePoints(key: DrillKey, answer: String) = buildString {
        if (key.lens == Lens.ASSUME) appendLine("要挖前提的结论：${key.prompt}")
        if (key.lens == Lens.ESSAY) appendLine("申论题目：${key.prompt}")
        appendLine(
            when (key.lens) {
                Lens.GIST -> "锁住的答案（第 1 条是主干，其余是要点）："
                Lens.ESSAY -> "参考要点（按采分点逐条判，意思到了就算，只写了词没写出内容的不算）："
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

class LlmDrillGen(private val chat: ChatClient) : DrillGen {

    override suspend fun material(topic: String?, avoid: List<String>): LlmResult<Pair<String, String>> =
        request(DrillPrompts.material(topic, avoid), "写素材") { o ->
            val text = o.optString("text").trim()
            if (text.length < 60) throw BadKey("素材太短")
            o.optString("title").trim().ifEmpty { text.take(12) } to text
        }

    override suspend fun key(lens: Lens, material: String?): LlmResult<DrillKey> =
        request(DrillPrompts.key(lens, material?.take(MAX_MATERIAL)), "锁答案") { DrillKey.parse(lens, it) }

    override suspend fun reestimate(question: String, material: String?): LlmResult<Double> =
        request(DrillPrompts.reestimate(question, material?.take(MAX_MATERIAL)), "核对估算") { o ->
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
