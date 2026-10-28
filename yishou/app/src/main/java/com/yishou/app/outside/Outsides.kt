package com.yishou.app.outside

import com.yishou.app.data.Outside
import com.yishou.app.llm.Tagged
import com.yishou.app.think.Words

/** 从练习报告读出来的东西 */
data class Report(val subject: String, val questions: Int?, val correct: Int?, val seconds: Int?)

/**
 * 刷题 App 的练习报告：识图模型按标签回，本机读成数。也处理“正确率 75%”这种只给比例的。
 */
object Outsides {

    val NAMES = mapOf(
        "科目" to "subject", "题型" to "subject", "题数" to "questions", "做对" to "correct", "答对" to "correct",
        "用时" to "time", "正确率" to "rate",
    )

    const val PROMPT = "这是刷题 App（例如粉笔）的练习报告截图。只读图里写着的，不要估。按下面格式回答，每行一个，认不出的写“无”：\n" +
        "【科目】……\n【题数】数字\n【做对】数字\n【正确率】百分比\n【用时】例如 12分30秒 或 00:12:30"

    fun parse(text: String): Report {
        val o = Tagged.toJson(text, NAMES)
        val q = o.optString("questions").let { Words.numbers(it).firstOrNull()?.toInt() }
        var c = o.optString("correct").let { Words.numbers(it).firstOrNull()?.toInt() }
        if (c == null && q != null) {
            Words.numbers(o.optString("rate")).firstOrNull()?.let { r -> c = Math.round(q * r / 100.0).toInt() }
        }
        return Report(
            subject = o.optString("subject").trim().takeIf { it.isNotEmpty() && it != "无" }.orEmpty(),
            questions = q?.takeIf { it > 0 },
            correct = c?.takeIf { q == null || it in 0..q },
            seconds = seconds(o.optString("time")),
        )
    }

    private val CLOCK = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
    private val HMS = Regex("(?:(\\d+)\\s*(?:小时|时|h))?\\s*(?:(\\d+)\\s*(?:分钟|分|min|m))?\\s*(?:(\\d+)\\s*(?:秒|s))?")

    /** 12分30秒 / 1小时5分 / 00:12:30 / 12:30 / 750秒 → 秒 */
    fun seconds(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || t == "无") return null
        CLOCK.find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val c = m.groupValues[3].toIntOrNull()
            return if (c != null) a * 3600 + b * 60 + c else a * 60 + b
        }
        val m = HMS.findAll(t).firstOrNull { it.value.isNotBlank() }
        if (m != null) {
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val mi = m.groupValues[2].toIntOrNull() ?: 0
            val s = m.groupValues[3].toIntOrNull() ?: 0
            if (h + mi + s > 0) return h * 3600 + mi * 60 + s
        }
        return null
    }

    data class Summary(val sessions: Int, val questions: Int, val correct: Int, val graded: Int, val secondsPer: Int?)

    fun summary(xs: List<Outside>): Summary {
        val graded = xs.filter { it.correct != null }
        val timed = xs.filter { it.seconds != null && it.questions > 0 }
        return Summary(
            sessions = xs.size,
            questions = xs.sumOf { it.questions },
            correct = graded.sumOf { it.correct ?: 0 },
            graded = graded.sumOf { it.questions },
            secondsPer = if (timed.isEmpty()) null else timed.sumOf { it.seconds ?: 0 } / timed.sumOf { it.questions },
        )
    }

    fun line(s: Summary): String {
        if (s.sessions == 0) return "还没有记录。去刷题 App 做一组，回来截一张练习报告。"
        return buildList {
            add("${s.sessions} 次 · ${s.questions} 题")
            if (s.graded > 0) add("正确率 ${s.correct * 100 / s.graded}%")
            s.secondsPer?.let { add("平均 $it 秒/题") }
        }.joinToString(" · ")
    }
}

/**
 * 出题用的 AI App 和刷题 App：设置里每行“名字 包名 网址”（包名、网址有一个就行）。
 * 装了 App 就直接把出题要求发进 App；没装就打开网址。
 */
data class AiApp(val name: String, val pkg: String?, val url: String?)

object AiApps {
    private val PKG = Regex("^[a-zA-Z][\\w]*(\\.[\\w]+)+$")

    fun parse(text: String): List<AiApp> = text.lines().mapNotNull { line ->
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return@mapNotNull null
        val url = tokens.firstOrNull { it.startsWith("http") }
        val pkg = tokens.firstOrNull { !it.startsWith("http") && PKG.matches(it) }
        if (url == null && pkg == null) return@mapNotNull null
        val name = tokens.filter { it != url && it != pkg }.joinToString(" ").ifEmpty {
            url?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/') ?: pkg!!.substringAfterLast('.')
        }
        AiApp(name, pkg, url)
    }

    const val DEFAULT = "ChatGPT com.openai.chatgpt https://chatgpt.com/\n" +
        "Gemini com.google.android.apps.bard https://gemini.google.com/app\n" +
        "豆包 com.larus.nova https://www.doubao.com/chat/\n" +
        "DeepSeek com.deepseek.chat https://chat.deepseek.com/\n" +
        "Kimi com.moonshot.kimichat https://kimi.moonshot.cn/"

    /** 刷题 App 默认是粉笔 */
    const val DEFAULT_DRILL = "粉笔 com.fenbi.android.servant https://www.fenbi.com/"
}
