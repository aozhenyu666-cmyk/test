package cn.companion.pet.core.appointment

/**
 * 从用户原话里解析出「明确时间约定」。
 *
 * 设计原则（对应阶段 3 要求 3.3-2）：
 * **时间或话题不明确时，只询问必要信息，绝不悄悄猜一个重要约定。**
 *
 * 所以解析结果是三态：
 * - [ParseResult.Ready]：时间话题都清楚，可以建；
 * - [ParseResult.NeedTime]：看起来像约定但没说时间，要问"多久之后"；
 * - [ParseResult.NeedTopic]：有时间但不知道问什么，要问"要我问你什么"；
 * - [ParseResult.NotAppointment]：根本不是约定。
 *
 * 纯函数，无 I/O、无随机。
 */
sealed interface ParseResult {
    /** 可以直接创建。 */
    data class Ready(
        val topic: String,
        val dueInMillis: Long,
        val basis: String,
    ) : ParseResult

    /** 像约定，但缺时间。 */
    data class NeedTime(val basis: String) : ParseResult

    /** 有时间，但缺话题。 */
    data class NeedTopic(val dueInMillis: Long, val basis: String) : ParseResult

    /** 不是约定。 */
    data object NotAppointment : ParseResult
}

object AppointmentParser {

    /** 约定意图的关键词：出现这些才可能是约定。 */
    private val INTENT_WORDS = listOf(
        "提醒我", "问问我", "问我", "提醒", "到点", "别忘了",
        "待会", "待会儿", "等会", "等会儿", "过会", "过会儿",
    )

    /**
     * 相对时间：把「十分钟后」「半小时后」「两小时后」解析成毫秒。
     * 返回 null 表示没识别到时间。
     */
    fun parseRelativeMillis(text: String): Long? {
        val t = text.trim()
        // 阿拉伯数字：10分钟 / 30 分钟 / 2小时 / 1天
        Regex("(\\d+)\\s*(秒钟|秒|分钟|分|小时|钟头|天|个?小时)").find(t)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@let
            return unitToMillis(n, m.groupValues[2])
        }
        // 中文数字：十分钟 / 半小时 / 两小时 / 一个小时
        Regex("([零一二两三四五六七八九十半]+)\\s*(秒钟|秒|分钟|分|小时|钟头|天|个?小时)").find(t)?.let { m ->
            val n = chineseNumber(m.groupValues[1]) ?: return@let
            return unitToMillis(n, m.groupValues[2])
        }
        return null
    }

    /**
     * 从原话解析约定。
     *
     * @param text 用户这句话
     * @param contextTopic 上下文里已有的话题（例如他正在聊"项目进度"）
     */
    fun parse(text: String, contextTopic: String = ""): ParseResult {
        val t = text.trim()
        if (t.isEmpty()) return ParseResult.NotAppointment

        val hasIntent = INTENT_WORDS.any { t.contains(it) }
        val millis = parseRelativeMillis(t)

        // 没有约定意图，也没说时间 → 不是约定
        if (!hasIntent && millis == null) return ParseResult.NotAppointment

        // 有时间但没意图词（例如"十分钟后"单独一句）：仍然算约定意图，但需要话题
        if (millis == null) {
            // 有意图但没时间 → 问时间
            return ParseResult.NeedTime(t.take(60))
        }

        val topic = extractTopic(t) ?: contextTopic.takeIf { it.isNotBlank() }
        if (topic.isNullOrBlank()) {
            // 有时间但不知道问什么 → 问话题
            return ParseResult.NeedTopic(millis, t.take(60))
        }
        return ParseResult.Ready(topic, millis, t.take(120))
    }

    /**
     * 抽取"要问什么"。
     *
     * 只用保守规则：**抽不出来就返回 null**，让调用方去问，
     * 不要用一个模糊的默认值（"进度"）替用户决定。
     */
    fun extractTopic(text: String): String? {
        // 「问问我 X」「问问 X 进度」「提醒我 X」
        val patterns = listOf(
            Regex("(?:问问|问我|提醒我)\\s*(?:一下)?\\s*([^，。,.！!？?]{2,20})"),
            Regex("(?:关于|说说)\\s*([^，。,.！!？?]{2,20})"),
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val raw = m.groupValues[1].trim()
                // 去掉时间词，避免把"十分钟后"当成话题
                .replace(Regex("[零一二两三四五六七八九十百\\d]+\\s*(秒钟|秒|分钟|分|小时|钟头|天|个?小时)(后|之后|以后)?"), "")
                .trim()
            if (raw.length >= 2 && raw.none { it.isDigit() }) return raw.take(30)
        }
        return null
    }

    /** 中文数字 → 数值。只支持到九十九，够用。 */
    internal fun chineseNumber(s: String): Long? {
        val t = s.trim()
        // 「半」= 0.5 个单位。用哨兵返回，由调用方按毫秒折算。
        if (t == "半") return HALF
        val digits: Map<String, Int> = mapOf(
            "零" to 0, "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9,
        )
        // 「十」「十五」「二十」「二十一」
        if (t.contains('十')) {
            val parts = t.split('十')
            val tens = if (parts[0].isEmpty()) 1 else digits[parts[0].last().toString()] ?: return null
            val ones = if (parts.size > 1 && parts[1].isNotEmpty()) digits[parts[1].last().toString()] ?: 0 else 0
            return (tens * 10 + ones).toLong()
        }
        // 单个数字
        if (t.length == 1) return digits[t]?.toLong()
        return null
    }

    private fun unitToMillis(n: Long, unit: String): Long {
        val per = when {
            unit.startsWith("秒") -> 1_000L
            unit.startsWith("分") -> 60_000L
            unit.contains("小时") || unit == "钟头" -> 3_600_000L
            unit == "天" -> 86_400_000L
            else -> 60_000L
        }
        // 「半」不是整数倍：半小时 = 0.5 × 3_600_000 = 30 分钟。
        // 之前把 HALF 当成 5，算成了 5 个 6 分钟——测试抓到的就是这个错。
        if (n == HALF) return per / 2
        return n * per
    }

    /** 「半」表示 0.5 个单位，需要单独按毫秒算，不能当作整数倍。 */
    private const val HALF = -1L

    /**
     * 把 ParseResult 的三态翻译成人话，供界面显示。
     * 不显示内部枚举。
     */
    fun describe(result: ParseResult): String = when (result) {
        is ParseResult.Ready -> "记下了：" + formatDelay(result.dueInMillis) + "问你「" + result.topic + "」"
        is ParseResult.NeedTime -> "好，什么时候提醒你？比如「十分钟后」"
        is ParseResult.NeedTopic -> "好，" + formatDelay(result.dueInMillis) + "提醒你。要我问你什么？"
        ParseResult.NotAppointment -> ""
    }

    private fun formatDelay(millis: Long): String {
        val minutes = millis / 60_000L
        return when {
            minutes < 1L -> "马上"
            minutes < 60L -> minutes.toString() + " 分钟后"
            minutes < 24 * 60L -> (minutes / 60L).toString() + " 小时后"
            else -> (minutes / (24L * 60L)).toString() + " 天后"
        }
    }
}
