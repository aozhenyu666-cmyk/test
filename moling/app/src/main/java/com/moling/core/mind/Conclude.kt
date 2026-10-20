package com.moling.core.mind

import java.time.Instant
import java.time.LocalDateTime

/** 一次收线的理解结果。 */
data class Closing(
    val conclusion: String,
    val nextStep: String,
    /** 下一步的时间（毫秒），没说就是 0。 */
    val at: Long,
    val open: String = "",
    /** 有没有实质内容（“好的”“聊完了”不算）。 */
    val substantial: Boolean,
)

/** 模型用的“【标签】内容”格式，和一手的 Tagged 一样宽松。 */
object Tags {
    private val re = Regex("""【([^】]{1,8})】\s*([^【]*)""")

    fun parse(text: String): Map<String, String> =
        re.findAll(text).associate { it.groupValues[1].trim() to it.groupValues[2].trim().trimEnd('。', '\n', ' ') }
}

object Conclude {
    private val filler = Regex("""^(好的?|嗯+|哦+|行|可以|聊完了|没了|就这样|ok|OK|收到|完成了?)[。！!，, ]*$""")
    private val nextMark = Regex("""(下一步|接下来|然后|之后|待会儿?|明天|今晚|晚上|下午|上午|明早)""")
    private val timeRe = Regex("""(今天|今晚|明天|明早|后天)?\s*(早上|上午|中午|下午|晚上)?\s*(\d{1,2})\s*[点:：时]\s*(半|\d{1,2})?""")

    /** 本地理解：不调模型。 */
    fun local(text: String, now: Long): Closing {
        val t = text.trim()
        val substantial = t.length >= 6 && !filler.matches(t)
        val parts = t.split('，', ',', '；', ';', '。').map { it.trim() }.filter { it.isNotEmpty() }
        val nextIdx = parts.indexOfFirst { nextMark.containsMatchIn(it) || timeRe.containsMatchIn(cnDigits(it)) }
        val (concl, next) = when {
            parts.size >= 2 && nextIdx > 0 -> parts.take(nextIdx).joinToString("，") to parts.drop(nextIdx).joinToString("，")
            else -> t to ""
        }
        return Closing(concl, cleanNext(next), timeOf(next.ifBlank { t }, now), substantial = substantial)
    }

    /** 模型返回的标签格式；缺字段时用本地结果补。 */
    fun fromModel(reply: String, text: String, now: Long): Closing {
        val base = local(text, now)
        val m = Tags.parse(reply)
        if (m.isEmpty()) return base
        val next = m["下一步"].orEmpty().ifBlank { base.nextStep }
        val at = m["时间"]?.let { timeOf(it, now) }?.takeIf { it > 0 } ?: base.at
        return base.copy(
            conclusion = m["结论"].orEmpty().ifBlank { base.conclusion },
            nextStep = next,
            at = at,
            open = m["未解决"].orEmpty().takeUnless { it == "无" || it == "没有" }.orEmpty(),
        )
    }

    private fun cleanNext(s: String) = s.replace(Regex("""^(下一步|接下来|然后|之后)[是：:，, ]*"""), "").trim()

    /** 从一句话里找时间，换算成毫秒；找不到返回 0。时间已过且没说哪天，就算明天。 */
    fun timeOf(s: String, now: Long): Long {
        val m = timeRe.find(cnDigits(s)) ?: return 0
        val day = m.groupValues[1]
        val part = m.groupValues[2]
        var h = m.groupValues[3].toIntOrNull() ?: return 0
        val min = when (val x = m.groupValues[4]) { "半" -> 30; "" -> 0; else -> x.toIntOrNull() ?: 0 }
        if (h > 23 || min > 59) return 0
        if ((part == "下午" || part == "晚上" || day == "今晚") && h in 1..11) h += 12
        if (part == "中午" && h in 1..2) h += 12
        val base = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), BEIJING)
        var target = base.toLocalDate().atTime(h, min)
        when (day) {
            "明天", "明早" -> target = target.plusDays(1)
            "后天" -> target = target.plusDays(2)
            "" -> if (target.isBefore(base)) target = target.plusDays(1)
        }
        return target.atZone(BEIJING).toInstant().toEpochMilli()
    }

    /** 只换出现在句首、标点后或“明天 / 下午”这类时间词后面的，避免把“好一点”读成 1 点。 */
    private val cnRe = Regex("""(?:(?<=今天|今晚|明天|明早|后天|早上|上午|中午|下午|晚上)|(?<![\u4e00-\u9fff]))([零一二两三四五六七八九十]{1,3})(?=\s*[点:：时])""")

    /** 把“九点”“十一点半”“二十点”里的中文数字换成阿拉伯数字（语音听写常这样写）。 */
    fun cnDigits(s: String): String = cnRe.replace(s) { m -> cnNumber(m.value)?.toString() ?: m.value }

    private fun cnNumber(t: String): Int? {
        val d = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (!t.contains('十')) return if (t.length == 1) d[t[0]] else null
        val (a, b) = t.split('十').let { it[0] to it.getOrElse(1) { "" } }
        val tens = if (a.isEmpty()) 1 else d[a.singleOrNull() ?: return null] ?: return null
        val ones = if (b.isEmpty()) 0 else d[b.singleOrNull() ?: return null] ?: return null
        return tens * 10 + ones
    }
}
