package com.yishou.app.voice

/**
 * 把模型流出来的字切成一句一句交给朗读：第一句一出来就能开口，不用等整段。
 * 句末标点优先切；一句太长还没句号时，在逗号处切。服务端给的片段不是句子，不能直接当一句念。
 */
class SentenceSplitter(private val maxLen: Int = 30, private val minLen: Int = 6) {
    private val buf = StringBuilder()

    /** 喂一段新字，返回已经完整的句子（按顺序）。 */
    fun feed(delta: String): List<String> {
        buf.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val cut = cutAt() ?: break
            val s = Speakable.clean(buf.substring(0, cut))
            buf.delete(0, cut)
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    /** 流结束：剩下的也是一句 */
    fun flush(): String? {
        val s = Speakable.clean(buf.toString())
        buf.clear()
        return s.ifEmpty { null }
    }

    private fun cutAt(): Int? {
        for (i in buf.indices) {
            if (buf[i] in STOPS) {
                var end = i + 1
                while (end < buf.length && (buf[end] in STOPS || buf[end] in CLOSERS)) end++
                // 标点后面还没来字时，可能还有右引号要跟上；先等一等，除非已经够长
                if (end == buf.length && i + 1 == buf.length && end < minLen) return null
                return end
            }
        }
        if (buf.length >= maxLen) {
            val comma = (buf.length - 1 downTo minLen).firstOrNull { buf[it] in COMMAS }
            if (comma != null) return comma + 1
        }
        return null
    }

    companion object {
        private const val STOPS = "。！？；!?;\n…"
        private const val CLOSERS = "”」』）)\""
        private const val COMMAS = "，,、："
    }
}

/** 念出来之前的清洗：内部标签、Markdown 记号不念 */
object Speakable {
    private val TAG = Regex("【[^】]{0,12}】")
    private val MARKS = Regex("[*#`>_~|]+")

    fun clean(text: String): String = text
        .replace(TAG, "")
        .replace(MARKS, "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('，', ',', '、')
        .let { if (it.any { c -> c.isLetterOrDigit() }) it else "" }
}
