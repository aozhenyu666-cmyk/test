package com.zongkong.core.director

/**
 * 本地模板卡：不调用模型也能用的那一版。模型可用时，导演会让模型把 ask / sayLines 改写得更贴近局面，
 * 改写失败就用这里的原样。话术参考蓝图 README 第 14 节。
 */
object Cards {
    fun stall(id: String, title: String, lateMin: Int, now: Long) = Card(
        id = id,
        mode = Mode.START,
        title = "墨灵 · 问一下",
        ask = "「$title」过点 $lateMin 分钟了。你现在是在？",
        choices = listOf(
            Choice("work", "在做事"),
            Choice("rest", "在休息"),
            Choice("stuck", "卡住了"),
            Choice("busy", "在忙别的 / 吃饭"),
        ),
        confidence = "中",
        speak = "「$title」还没开始，你现在是在？",
        createdAt = now,
    )

    fun idle(id: String, now: Long, talkMin: Int) = Card(
        id = id,
        mode = Mode.START,
        title = "墨灵 · 空档",
        ask = "现在空着。接下来这一段怎么用？",
        choices = listOf(
            Choice(CHOICE_GO, "去聊 $talkMin 分钟"),
            Choice("work", "做点正事"),
            Choice("rest", "正式休息 20 分钟"),
            Choice("play", "就想玩会儿"),
        ),
        actor = Actor.GEMINI_LIVE,
        minutes = talkMin,
        createdAt = now,
    )

    fun resume(id: String, b: Baton, now: Long) = Card(
        id = id,
        mode = Mode.START,
        title = "墨灵 · 接着上次",
        ask = "上次停在：${b.nextAsk.ifBlank { b.question }}。现在只回答这一小问？",
        choices = listOf(Choice("go_on", "现在接着想"), Choice(CHOICE_GO, "去和 Gemini 聊"), Choice("later", "晚点")),
        actor = Actor.GEMINI_LIVE,
        batonId = b.id,
        createdAt = now,
    )

    fun health(id: String, what: String, now: Long) = Card(
        id = id,
        mode = Mode.DEGRADED,
        title = "墨灵 · 我看不见了",
        ask = "我看不见了（$what）。帮我开一下？",
        choices = listOf(Choice(CHOICE_FIX, "去打开"), Choice("later", "等会儿")),
        speak = "我看不见了，帮我开一下？",
        createdAt = now,
    )

    fun followUp(id: String, what: String, now: Long) = Card(
        id = id,
        mode = Mode.WORK,
        title = "墨灵 · 回头看看",
        ask = "说好的「$what」开始了吗？",
        choices = listOf(
            Choice("started", "开始了"),
            Choice("notyet", "还没"),
            Choice("stuck", "卡住了"),
            Choice("moved", "改时间了"),
        ),
        speak = "说好的$what，开始了吗？",
        createdAt = now,
    )

    /** 卡住了：一个更小的问题，外加去演员那里聊。 */
    fun smaller(id: String, b: Baton?, now: Long, talkMin: Int) = Card(
        id = id,
        mode = Mode.START,
        title = "墨灵 · 拆小一点",
        ask = if (b != null && b.question.isNotBlank()) "「${b.question}」不用想完，只说一个词：卡在哪？" else "不用想完，只说一个词：卡在哪？",
        choices = listOf(
            Choice("s_material", "材料"),
            Choice("s_idea", "思路"),
            Choice("s_unwilling", "不想做"),
            Choice("s_tired", "累"),
            Choice(CHOICE_GO, "去聊 $talkMin 分钟"),
        ),
        actor = Actor.GEMINI_LIVE,
        minutes = talkMin,
        batonId = b?.id ?: "",
        createdAt = now,
    )

    fun confirm(id: String, old: String, new: String, now: Long) = Card(
        id = id,
        mode = Mode.WORK,
        title = "墨灵 · 对一下",
        ask = "我记得“$old”，最近又看到“$new”。哪个对？",
        choices = listOf(Choice("c_old", "前一个"), Choice("c_new", "后一个"), Choice("c_none", "都不对")),
        createdAt = now,
    )

    fun rest(id: String, minutes: Int, now: Long) = Card(
        id = id,
        mode = Mode.REST,
        title = "墨灵 · 休息",
        ask = "好，歇 $minutes 分钟。到点我叫你。",
        choices = listOf(Choice("rest_more", "再歇 10 分钟")),
        minutes = minutes,
        speak = "好，歇 $minutes 分钟，到点我叫你。",
        createdAt = now,
    )

    fun wrongGuess(id: String, now: Long) = Card(
        id = id,
        mode = Mode.START,
        title = "墨灵 · 我猜错了",
        ask = "我猜错了，记下来了。那你刚才是在？",
        choices = listOf(Choice("w_work", "在做正事"), Choice("w_rest", "在休息"), Choice("w_life", "在处理生活的事"), Choice("w_other", "别的")),
        createdAt = now,
    )
}
