package com.yishou.app.think

/** 素材文字的整理：去掉分享时带的链接尾巴、多余空行，起个短标题。 */
object MaterialText {

    const val MAX = 4000
    private val URL = Regex("https?://\\S+")
    private val TAILS = listOf("复制这条信息", "打开App", "打开APP", "点击链接", "长按复制", "来自：", "分享自")

    fun clean(raw: String): String {
        var t = raw.replace("\r", "")
        TAILS.forEach { tail ->
            val i = t.indexOf(tail)
            // 只截掉末尾那一截，正文中间出现的不动
            if (i > 0 && i > t.length * 0.6) t = t.substring(0, i)
        }
        t = t.replace(URL, "").replace(Regex("[（(]\\s*字数[:：]?\\s*\\d+\\s*字?\\s*[)）]"), "")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return t.take(MAX).trim()
    }

    /** 标题：第一句（到标点为止），最多 16 个字 */
    fun title(text: String): String {
        val first = text.trim().split(Regex("[。！？!?\\n]")).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return (if (first.length <= 16) first else first.take(15) + "…").ifEmpty { "一段素材" }
    }

    /** 太短的东西练不了 */
    fun usable(text: String): Boolean = Words.norm(text).length >= 20
}
