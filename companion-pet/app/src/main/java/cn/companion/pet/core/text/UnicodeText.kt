package cn.companion.pet.core.text

/**
 * 按 Unicode 码点计数的文字工具。
 *
 * 全模块统一按码点（code point）计数：一个补充平面字符（如 U+1F600）算 1 个，
 * 不算 2 个 UTF-16 char。这不等于完整的字素簇（grapheme cluster）计数，
 * 也不引入 ICU：带变体选择符的组合 emoji 会被算成多个码点，这是已知取舍。
 */
object UnicodeText {

    /**
     * 统计 [text] 的码点数。
     */
    fun count(text: String): Int {
        return text.codePointCount(0, text.length)
    }

    /**
     * 把 [text] 截断到最多 [maxChars] 个码点，不追加省略号。
     *
     * [maxChars] 为 0 返回空字符串；为负数抛出 [IllegalArgumentException]。
     * 截断点按码点对齐，不会切断代理对。
     */
    fun truncate(text: String, maxChars: Int): String {
        require(maxChars >= 0) { "maxChars must be >= 0, was $maxChars" }
        if (maxChars == 0 || text.isEmpty()) {
            return ""
        }
        if (count(text) <= maxChars) {
            return text
        }
        var consumed = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (consumed + 1 > maxChars) {
                break
            }
            consumed += 1
            index += Character.charCount(codePoint)
        }
        return text.substring(0, index)
    }
}
