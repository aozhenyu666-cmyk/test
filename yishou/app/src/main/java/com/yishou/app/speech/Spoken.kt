package com.yishou.app.speech

/** 一手的朗读版：有模型给的口语问题就用它，没有就从原文里取最后一个问句。 */
object Spoken {
    private const val SHORT = 60

    fun of(move: String, say: String?): String {
        say?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val clean = move.replace(Regex("[*#`>_]"), "").trim()
        if (clean.length <= SHORT) return clean
        val sentences = clean.split(Regex("(?<=[。！？?!；;\\n])")).map { it.trim() }.filter { it.isNotEmpty() }
        return sentences.lastOrNull { it.endsWith("？") || it.endsWith("?") } ?: sentences.lastOrNull() ?: clean
    }

    /** 判定之后先说一句结果，再说下一手。反馈只取第一句。 */
    fun verdict(effective: Boolean, feedback: String): String {
        val first = feedback.split(Regex("(?<=[。！？?!])")).firstOrNull()?.trim().orEmpty()
        val head = if (effective) "这手有效。" else "这手还不算。"
        return if (first.isEmpty()) head else head + first
    }
}
