package com.yishou.app.line

import com.yishou.app.think.Words

/** 这一问是怎么问的：正常的一问、降阶的小问题、回主线 */
enum class Ask { NORMAL, SMALLER, CHOOSE, GUESS, ANCHOR }

/**
 * 抗拒思考（“不知道”“随便”、不说话、乱答）的对付办法：不放过，也不硬逼，一级一级把门槛放低——
 * 1 只说一个词 → 2 二选一，再说个理由 → 3 先猜一个，错了也算 → 4 这一手先放着，记下来，换个不用想的或者过会儿再来。
 * 只要中间接上了一手，就回到正常的问题。
 */
object Resist {

    val PHRASES = listOf(
        "不知道", "不清楚", "不晓得", "随便", "都行", "无所谓", "不想", "算了", "没想法", "想不出", "不会", "懒得",
        "跳过", "下一个", "pass", "不懂", "没什么", "没啥", "不想说", "不说了", "看不懂", "说不出",
    )

    /** 拒绝：几乎没说东西，或者很短又是“不知道/随便”这类话 */
    fun refused(text: String): Boolean {
        val s = Words.substance(text)
        if (s < 2) return true
        val t = text.lowercase()
        return s <= 10 && PHRASES.any { it in t }
    }

    /** 第几次连着拒绝 → 用哪一级 */
    fun rung(refusals: Int): Ask? = when (refusals) {
        1 -> Ask.SMALLER
        2 -> Ask.CHOOSE
        3 -> Ask.GUESS
        else -> null
    }

    /** 降阶的问题。按六面给不同的小问题，都围着这一局的主线 */
    fun prompt(ask: Ask, anchor: String, face: Int): String = when (ask) {
        Ask.SMALLER -> when (face) {
            2 -> "不用想全。只押一个字：「$anchor」会成，还是不会成？"
            3 -> "不用想全。说一个你见过的、跟「$anchor」有点像的东西，一个词就行。"
            4 -> "不用想全。「$anchor」里，哪一个词你最拿不准？说那个词。"
            5 -> "不用想全。「$anchor」属于哪一类事？一个词。"
            6 -> "不用想全。上次关于「$anchor」，你记得的一个词也行。"
            else -> "不用想全。只说一个词：「$anchor」最关键的是什么？"
        }
        Ask.CHOOSE -> "二选一：关于「$anchor」，你更倾向“是”还是“不是”？选一个，再说一个理由。"
        Ask.GUESS -> "那就猜。错了也算一手：关于「$anchor」，你的第一反应是什么？"
        else -> ""
    }

    const val PARK = "这一手先放着，记下了。可以换成背一段（不用想），或者先停，过会儿再来接着下。"
}
