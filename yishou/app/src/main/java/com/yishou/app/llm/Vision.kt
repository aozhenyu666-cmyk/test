package com.yishou.app.llm

/**
 * 识图：把用户拍下的作答痕迹（手写演算、做过的题）转写成文字，
 * 再和文字回答一起交给陪练判定。用设置里单独填写的识图模型。
 */
class Vision(private val chat: ChatClient) {

    suspend fun transcribe(taskTitle: String?, coachMove: String?, imageDataUrl: String): LlmResult<String> {
        val text = buildString {
            if (taskTitle != null) appendLine("学习任务：$taskTitle")
            if (coachMove != null) appendLine("陪练刚才的问题：$coachMove")
            append("这是用户回答这个问题时拍下的图片。请转写。")
        }
        return when (val r = chat.completeWithImage(Prompts.VISION, text, imageDataUrl, "识图")) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> LlmResult.Ok(r.value.trim().take(MAX_CHARS))
        }
    }

    /** 拍一道题：完整转写材料、问题和全部选项，不作答。 */
    suspend fun transcribeQuestion(imageDataUrl: String): LlmResult<String> {
        val system = "你负责把一道题目的图片完整转写成文字：材料、问题、全部选项，保持原来的顺序和编号。" +
            "看不清的地方写“（看不清）”。不要作答，不要解析，不要加任何开场白。"
        return when (val r = chat.completeWithImage(system, "请转写这道题。", imageDataUrl, "拍题识图")) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> LlmResult.Ok(r.value.trim().take(MAX_CHARS * 2))
        }
    }

    /** “看一眼”：客观描述屏幕上用户正在做什么。 */
    suspend fun describeScreen(taskTitle: String?, imageDataUrl: String): LlmResult<String> {
        val text = (if (taskTitle != null) "用户的学习任务：$taskTitle\n" else "") + "这是他此刻的手机屏幕。请描述。"
        return when (val r = chat.completeWithImage(Prompts.SCREEN, text, imageDataUrl, "看屏识图")) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> LlmResult.Ok(r.value.trim().take(MAX_CHARS))
        }
    }

    /** 素材：把截图、照片里的正文转写出来（文章、帖子、书页），去掉界面上的按钮和广告。 */
    suspend fun transcribeText(imageDataUrl: String): LlmResult<String> {
        val system = "你负责把图片里的正文完整转写成文字：文章、帖子、书页、聊天里的长段落。" +
            "去掉界面按钮、广告、点赞数、时间戳这类东西；看不清的地方写“（看不清）”。不要总结，不要评论，不要加开场白。"
        return when (val r = chat.completeWithImage(system, "请转写图里的正文。", imageDataUrl, "素材识图")) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> LlmResult.Ok(r.value.trim().take(MAX_CHARS * 2))
        }
    }

    /** 比价：认出图里每件商品的名字、价格、规格原文。规格由本机折算，模型不算单价。 */
    suspend fun readGoods(imageDataUrl: String): LlmResult<List<com.yishou.app.deed.Goods>> {
        val system = "你负责从购物截图或价签照片里认商品。只认图里写着的，看不清的不要编。" +
            "只返回 JSON：{\"items\": [{\"name\": \"商品名\", \"price\": 数字（元）, \"spec\": \"规格原文，如 500g、2.5kg、250ml×4、6个装、1斤\"}]}"
        return when (val r = chat.completeWithImage(system, "列出图里的商品。", imageDataUrl, "比价识图")) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> try {
                val a = CoachParser.extractJsonObject(r.value).optJSONArray("items") ?: org.json.JSONArray()
                val goods = (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val price = o.optDouble("price")
                    if (price.isNaN() || price <= 0) null
                    else com.yishou.app.deed.Goods(o.optString("name").trim().take(30), price, o.optString("spec").trim())
                }
                LlmResult.Ok(goods)
            } catch (e: BadJsonException) {
                LlmResult.Err(LlmError.BadFormat(e.message ?: ""))
            }
        }
    }

    /** 实操对照：从小票、订单、计时截图里读出那个实际的数。返回 数（认不出为 null） to 图里这个数是什么 */
    suspend fun readAmount(title: String, unit: String, imageDataUrl: String): LlmResult<Pair<Double?, String>> {
        val system = "你负责从小票、订单、账单、计时器、体重秤这类截图里读出一个数。只读图里写着的，不要估。" +
            "只返回 JSON：{\"value\": 数字或 null, \"what\": \"这个数在图里是什么（如：实付金额）\"}"
        val text = "用户做的事：$title。要找的是实际的量，单位：$unit。"
        return when (val r = chat.completeWithImage(system, text, imageDataUrl, "对照识图")) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> try {
                val o = CoachParser.extractJsonObject(r.value)
                val v = if (o.isNull("value")) null else o.optDouble("value").takeIf { !it.isNaN() }
                LlmResult.Ok(v to o.optString("what").trim())
            } catch (e: BadJsonException) {
                LlmResult.Err(LlmError.BadFormat(e.message ?: ""))
            }
        }
    }

    /** 刷题 App 的练习报告：按标签读出科目、题数、做对、用时 */
    suspend fun readReport(imageDataUrl: String): LlmResult<com.yishou.app.outside.Report> =
        when (val r = chat.completeWithImage(com.yishou.app.outside.Outsides.PROMPT, "读这张练习报告。", imageDataUrl, "报告识图")) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> LlmResult.Ok(com.yishou.app.outside.Outsides.parse(r.value))
        }

    companion object {
        /** 转写太长时截断，避免判定请求过大 */
        const val MAX_CHARS = 1500
    }
}
