package com.moling.core.mind

/**
 * 替身导演：施工队 A 在真导演（[DirectorEngine]）接上之前用它把小窗、接力、收线跑通。
 * 行为固定、不调用模型：过点或连续娱乐就问一个点选题，选“卡住了”就给一张去 Gemini 的卡。
 * 换成真导演只需要换掉构造的那一行，接口完全一样。
 */
class FakeDirector : Director {
    private var current: Card? = null
    private var baton = Baton(id = "b-demo", question = "今天的主线", nextAsk = "第一步做什么？")

    override suspend fun onSense(sense: Sense): Card? {
        val trigger = sense.kind == SenseKind.PLAN_DUE || sense.kind == SenseKind.LONG_ENTERTAINMENT
        if (!trigger) return null
        return Card(
            id = "fake-${sense.at}",
            mode = Mode.START,
            title = "墨灵 · 问一下",
            ask = "你现在是在？",
            choices = listOf(
                Choice("work", "在做事"),
                Choice("rest", "在休息"),
                Choice("stuck", "卡住了"),
                Choice("eat", "在吃饭"),
            ),
            speak = "问一下，你现在是在？",
            createdAt = sense.at,
        ).also { current = it }
    }

    override fun currentCard(now: Long): Card? = current

    override suspend fun onReply(reply: Reply): Card? {
        val next = when (reply) {
            is Reply.Pick -> if (reply.key == "stuck") talkCard(reply.at) else null
            is Reply.Stuck -> talkCard(reply.at)
            is Reply.Conclusion -> {
                baton = baton.copy(known = reply.text, updatedAt = reply.at, version = baton.version + 1, source = "用户")
                null
            }
            else -> null
        }
        current = next
        return next
    }

    override suspend fun opening(actor: Actor, cardId: String?, now: Long): Opening {
        val cards = listOf(
            Card(
                id = "fake-o1-$now", mode = Mode.WORK, title = "拆事 · 10 分钟",
                ask = "${baton.question}卡在哪一步？", actor = actor, minutes = 10,
                sayLines = listOf("先别给我答案，问我卡在哪。", "我说完你只指出一处漏掉的地方。"),
                batonId = baton.id, createdAt = now,
            ),
            Card(
                id = "fake-o2-$now", mode = Mode.WORK, title = "收线",
                ask = "一句话，聊出了什么？下一步几点做？", actor = actor, batonId = baton.id, createdAt = now,
            ),
        )
        val text = "现在开始。你是坐我旁边的同学，不是助手。别替我总结，先让我自己讲；我讲完你指出我漏了什么，一次一处。" +
            "今天要聊的是：${baton.question}。上次停在：${baton.nextAsk}。10 分钟后问我一句话聊出了什么、下一步几点做。"
        return Opening("s-$now", actor, text, cards, 10, Mode.WORK)
    }

    override suspend fun onSession(log: SessionLog): Card? {
        if (log.conclusion.isNotBlank()) {
            baton = baton.copy(known = log.conclusion, updatedAt = log.endedAt, version = baton.version + 1, source = log.actor.label)
        }
        current = null
        return null
    }

    override fun nowText(now: Long): String = "接力棒：${baton.question}｜已知：${baton.known}｜下一问：${baton.nextAsk}"

    private fun talkCard(at: Long) = Card(
        id = "fake-t-$at", mode = Mode.WORK, title = "拆事 · 10 分钟",
        ask = "卡住了就去和 Gemini 聊 10 分钟，第一问我准备好了。",
        actor = Actor.GEMINI_LIVE, minutes = 10, batonId = baton.id, createdAt = at,
        choices = listOf(Choice("go", "去聊"), Choice("later", "20 分钟后")),
    )
}
