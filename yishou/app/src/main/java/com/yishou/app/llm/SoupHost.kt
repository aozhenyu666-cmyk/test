package com.yishou.app.llm

import com.yishou.app.soup.QA
import com.yishou.app.soup.Reply
import com.yishou.app.soup.SoupJudge
import com.yishou.app.soup.Soups

object SoupPrompts {
    val ASK = """
        你是海龟汤主持人。【汤面】是玩家看到的题目；【汤底】是只有你知道的正确答案和解析。
        玩家每次问一个问题。你只能回答下面五种之一，只输出这几个字，不加标点以外的任何内容：
        是
        不是
        部分是
        无关
        这不是是非题
        判断以汤底为准：问题说中了汤底里的事实、原因或机制，答“是”；和汤底相反，答“不是”；只对一半，答“部分是”；汤底完全没涉及，答“无关”；
        问的不是能用是或否回答的问题（比如“为什么”“是什么”“怎么办”），答“这不是是非题”。绝不透露汤底。
    """.trimIndent()

    val GUESS = """
        你是海龟汤主持人。【汤面】是玩家看到的题目；【汤底】是只有你知道的正确答案和解析。玩家在猜汤底。
        只输出下面三种之一：
        猜中
        接近
        不对
        猜中＝说出了正确答案的核心意思（措辞不同也算）；接近＝方向对但缺关键一环；不对＝其他。绝不透露汤底。
    """.trimIndent()

    fun askUser(surface: String, truth: String, log: List<QA>, q: String) = buildString {
        appendLine("【汤面】$surface")
        appendLine("【汤底】$truth")
        val asked = log.filter { !it.guess }.takeLast(12)
        if (asked.isNotEmpty()) appendLine("【已经问过】" + asked.joinToString("；") { "${it.q}→${it.reply.label}" })
        append("【这一问】$q")
    }

    fun guessUser(surface: String, truth: String, g: String) = "【汤面】$surface\n【汤底】$truth\n【玩家猜】$g"
}

class LlmSoupHost(private val chat: ChatClient) : SoupJudge {
    override suspend fun ask(surface: String, truth: String, log: List<QA>, q: String): LlmResult<Reply> =
        when (val r = chat.complete(SoupPrompts.ASK, SoupPrompts.askUser(surface, truth, log, q), "海龟汤", json = false)) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> Soups.parseAsk(r.value)?.let { LlmResult.Ok(it) } ?: LlmResult.Err(LlmError.Local("主持人没按规矩回答，再问一次"))
        }

    override suspend fun guess(surface: String, truth: String, g: String): LlmResult<Reply> =
        when (val r = chat.complete(SoupPrompts.GUESS, SoupPrompts.guessUser(surface, truth, g), "海龟汤", json = false)) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> Soups.parseGuess(r.value)?.let { LlmResult.Ok(it) } ?: LlmResult.Err(LlmError.Local("主持人没按规矩回答，再猜一次"))
        }
}
