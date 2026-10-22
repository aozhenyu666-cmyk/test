package cn.companion.pet.core.text

/**
 * 屏幕文字清洗：把一次读屏得到的候选文字整理成一段可交给模型的正文。
 *
 * 清洗顺序固定为：
 * 1. 每条文字折叠连续空白为一个普通空格，并去掉首尾空白；
 * 2. 丢弃清理后长度 ≤ 3 码点的条目（按钮、标签之类的短词）；
 * 3. 按清理后的完整字符串去重，同一文字只要任一来源在可滚动容器里，合并后就标记为在可滚动容器里；
 * 4. 可滚动容器条目优先，组内按码点数降序，同长度按首次出现位置排序；
 * 5. 用一个换行符连接，最后按码点截断到 [maxChars]（换行符也计入额度）。
 *
 * 这个模块不判断场景、不读节点树、不做网络请求，也不落盘。
 * “忽略之前的指令”这类文字同样只是素材：清洗器不执行它、不把它改写成指令，
 * 也不声称靠删关键词就能防注入，提示词隔离交给上层探针。
 */
object ScreenTextCleaner {

    /** 探针调用方使用的额度。 */
    const val PROBE_MAX_CHARS: Int = 1200

    /** 桥接（Gemini 文本）调用方使用的额度。 */
    const val BRIDGE_MAX_CHARS: Int = 600

    /**
     * 清洗 [candidates] 并拼接成一段文字。
     *
     * 输入为空或全部被过滤时返回空字符串；[maxChars] 为 0 返回空字符串，
     * 为负数抛出 [IllegalArgumentException]。
     */
    fun clean(candidates: List<TextCandidate>, maxChars: Int): String {
        require(maxChars >= 0) { "maxChars must be >= 0, was $maxChars" }
        if (candidates.isEmpty() || maxChars == 0) {
            return ""
        }

        val merged = LinkedHashMap<String, MergedEntry>()
        for (candidate in candidates) {
            val folded = foldWhitespace(candidate.text)
            if (UnicodeText.count(folded) <= 3) {
                continue
            }
            val existing = merged[folded]
            if (existing == null) {
                merged[folded] = MergedEntry(
                    text = folded,
                    inScrollableContainer = candidate.inScrollableContainer,
                    firstIndex = merged.size
                )
            } else if (candidate.inScrollableContainer) {
                existing.inScrollableContainer = true
            }
        }
        if (merged.isEmpty()) {
            return ""
        }

        val ordered = merged.values
            .sortedWith(
                compareByDescending<MergedEntry> { it.inScrollableContainer }
                    .thenByDescending { UnicodeText.count(it.text) }
                    .thenBy { it.firstIndex }
            )
            .joinToString("\n") { it.text }

        return UnicodeText.truncate(ordered, maxChars)
    }

    /**
     * 把连续空白折叠成一个普通空格，并去掉首尾空白。
     *
     * 空白判定为 [Character.isWhitespace] 或 [Character.isSpaceChar] 为真的字符，
     * 因此不间断空格（U+00A0）、全角空格（U+3000）等也按空白处理。
     */
    private fun foldWhitespace(input: String): String {
        val builder = StringBuilder(input.length)
        var pendingSpace = false
        var index = 0
        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isBlank(codePoint)) {
                pendingSpace = true
                continue
            }
            if (pendingSpace && builder.isNotEmpty()) {
                builder.append(' ')
            }
            pendingSpace = false
            builder.appendCodePoint(codePoint)
        }
        return builder.toString()
    }

    private fun isBlank(codePoint: Int): Boolean {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
    }

    private class MergedEntry(
        val text: String,
        var inScrollableContainer: Boolean,
        val firstIndex: Int
    )
}
