package com.yishou.app.pool

import com.yishou.app.bank.BankParser
import com.yishou.app.think.MaterialText
import com.yishou.app.think.Words

/**
 * 统一入口：不管是粘贴、拍照、文件还是分享，进来的字先在这里分流——
 * 像题（有 A–D 选项或【要点】）就进题库；像文章就切成一段一段进素材库。
 * 之后海龟汤、残局、练思维、刷题、语音伙伴都从同一个池子里拿，不用每个练法各自再交一遍。
 */
object Intake {

    sealed interface Route {
        /** 当题库导入 */
        data class Bank(val text: String) : Route
        /** 当文章：每段一条素材，标题带上第几段 */
        data class Article(val pieces: List<Piece>) : Route
        /** 太短，练不了 */
        data object TooShort : Route
    }

    data class Piece(val title: String, val text: String)

    /** 一段素材多长：够练一次（一屏到两屏），太长了一次练不完 */
    const val PIECE = 1_200

    fun route(raw: String): Route {
        val text = raw.replace("\r", "").trim()
        // 两道以上像题，或者一道完整的题（选项 + 答案），就当题库
        val looks = BankParser.estimate(text)
        if (looks >= 2 || (looks == 1 && BankParser.parse(text).isNotEmpty())) return Route.Bank(text)
        val clean = clean(text)
        if (!MaterialText.usable(clean)) return Route.TooShort
        return Route.Article(split(clean))
    }

    /** 和 MaterialText.clean 一样去掉分享尾巴，但不截断（长文后面切段） */
    private fun clean(text: String): String {
        return MaterialText.cleanFull(text)
    }

    /** 按段落切，每块不超过 [PIECE] 字；一段本身太长就按句号再切 */
    fun split(text: String): List<Piece> {
        val paras = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { p -> if (p.length <= PIECE) listOf(p) else sentences(p) }
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        for (p in paras) {
            if (cur.isNotEmpty() && cur.length + p.length + 1 > PIECE) {
                chunks += cur.toString()
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(p)
        }
        if (cur.isNotEmpty()) chunks += cur.toString()
        // 最后一块太短就并回上一块
        if (chunks.size >= 2 && Words.norm(chunks.last()).length < 80) {
            val last = chunks.removeAt(chunks.lastIndex)
            chunks[chunks.lastIndex] = chunks.last() + "\n" + last
        }
        val title = MaterialText.title(text)
        return chunks.mapIndexed { i, c ->
            Piece(if (chunks.size == 1) title else "$title（${i + 1}/${chunks.size}）", c)
        }
    }

    private fun sentences(p: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in Regex("[^。！？!?]+[。！？!?]*").findAll(p).map { it.value }) {
            if (cur.isNotEmpty() && cur.length + s.length > PIECE) {
                out += cur.toString()
                cur.clear()
            }
            cur.append(s)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
