package cn.companion.pet.core.memory

/** 一次交流的结局。 */
enum class GeminiExit(val label: String) { NONE("否"), TEXT("文字深聊"), LIVE("Live 共看") }

data class Exchange(
    val timeText: String,
    val sceneLabel: String,
    val hook: String,
    /** 用户的回应；null 表示被忽略。 */
    val userReply: String?,
    val petReply: String? = null,
    val gemini: GeminiExit = GeminiExit.NONE,
    /** 用户明确点了 × / 收起（区别于没理）。 */
    val dismissed: Boolean = false,
)

object TodayLog {
    private val forgetPhrases = listOf("别记这个", "别记了", "不要记", "别记下", "忘了这个", "别记住")

    /** 用户说了“别记这个”之类的话：这次交流不写入任何文件。 */
    fun wantsForget(reply: String?): Boolean = reply != null && forgetPhrases.any { reply.contains(it) }

    fun format(e: Exchange): String {
        val reply = e.userReply?.let { "回应：${oneLine(it)}" } ?: if (e.dismissed) "（他关掉了）" else "（被忽略）"
        val pet = e.petReply?.let { " | 我接：${oneLine(it)}" } ?: ""
        return "- ${e.timeText} | ${e.sceneLabel} | 我说：${oneLine(e.hook)} | $reply$pet | Gemini：${e.gemini.label}"
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").replace("|", "／").trim()
}
