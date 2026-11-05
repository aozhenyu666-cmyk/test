package com.zongkong.core.guard

/**
 * 强度引擎：回答够不够格、放多久、不回应怎么办。
 *
 * 设计前提：**假设用户毫无自律，而且会主动不回消息。**
 * 所以这里不依赖“他会配合”，只依赖三件能自己发生的事：
 *  1. 回答不达标就不放行；
 *  2. 不回应到时间就收回桌面并记一次没做到；
 *  3. 没做到累计到阈值，当天标准自动加严。
 *
 * 纯逻辑，不碰 Android，可单测。
 */
enum class IntensityLevel(
    /** 至少多少个有效字（去掉口头禅/标点）。 */
    val requireChars: Int,
    /** 通过后放行几分钟。 */
    val releaseMinutes: Int,
) {
    /** 状态好、连续做到：宽松一档。 */
    L1(12, 2),

    /** 默认档。 */
    L2(18, 1),

    /** 当天已经漏了两次：加严。 */
    L3(25, 1),
}

enum class AnswerProblem {
    EMPTY,
    TOO_SHORT,
    /** 只有口头禅、求情、情绪，没有动作。 */
    FILLER,
    /** 有字，但没有“先做什么 + 多久 / 什么时候停”。 */
    NO_NEXT_ACTION,
}

data class AnswerCheck(
    val pass: Boolean,
    val problem: AnswerProblem? = null,
    val hint: String = "",
    /** 还能再答一次；false = 这次直接记一次没做到，页面不放行。 */
    val reask: Boolean = false,
)

enum class NoResponse {
    /** 还在正常思考时间。 */
    WAIT,

    /** 提醒一次，并告诉他还剩多久。 */
    NUDGE,

    /** 收回桌面，记一次没做到。 */
    CLOSE_AND_MISS,
}

object IntensityRules {
    /** 不回应多少秒开始提醒 / 收回桌面。 */
    const val NUDGE_SECONDS = 45
    const val CLOSE_SECONDS = 150

    /** 当天漏几次升到 L3。 */
    const val MISS_FOR_L3 = 2

    /** 连续做到几次回到 L1。 */
    const val PASS_FOR_L1 = 3

    private val fillers = listOf(
        "我就看看", "看一下", "随便看看", "就一会", "一会儿", "马上", "等下", "等等",
        "求你了", "放过", "别拦", "让我玩", "不想", "算了", "不知道", "心情不好",
        "嗯", "啊", "哦", "好的", "知道了", "行吧", "可以吧",
    )

    private val actions = listOf(
        "打开", "做", "写", "查", "搜", "看", "读", "背", "改", "投", "发",
        "练", "算", "整理", "回", "定", "设", "列", "抄", "翻", "刷", "听",
    )

    private val timeMarker = Regex("""\d{1,3}\s*(分钟|分|秒)|半小时|一刻钟|一个小时|一小时""")
    private val stopMarkers = listOf("做完", "之后", "然后", "就停", "回来", "再看", "到点", "收工")

    /** 只留文字和数字，去掉空格与标点。 */
    fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }

    /** 去掉常见口头禅/求情之后剩几个字。 */
    fun substance(text: String): Int {
        var s = normalize(text)
        for (f in fillers) s = s.replace(normalize(f), "")
        return s.length
    }

    /**
     * 判断一次作答。
     * [attempt] = 这是同一道题的第几次提交（0 = 第一次）。
     * 第一次不达标还可以再答一次；第二次仍不达标就记没做到，不放行。
     */
    fun check(answer: String, level: IntensityLevel, attempt: Int = 0): AnswerCheck {
        val raw = normalize(answer)
        if (raw.isEmpty()) return fail(AnswerProblem.EMPTY, "先写一句：接下来先做什么。", attempt)

        val sub = substance(answer)
        if (sub < 6) {
            return fail(AnswerProblem.FILLER, "这句只有口头禅。写一个具体动作。", attempt)
        }
        if (sub < level.requireChars) {
            return fail(
                AnswerProblem.TOO_SHORT,
                "还差 ${level.requireChars - sub} 个字：${level.requireChars} 字起（先做什么、多久、什么时候停）。",
                attempt,
            )
        }
        val hasAction = actions.any { it in raw }
        val hasTime = timeMarker.containsMatchIn(raw)
        val hasStop = stopMarkers.any { it in raw }
        if (!hasAction) {
            return fail(AnswerProblem.NO_NEXT_ACTION, "没有动作。写清“先打开什么 / 先做什么”。", attempt)
        }
        if (!hasTime && !hasStop) {
            return fail(AnswerProblem.NO_NEXT_ACTION, "没有边界。加“15 分钟”或“做完就停”。", attempt)
        }
        // L2 起：时间和停止条件都要有，避免“做一下”混过去。
        if (level != IntensityLevel.L1 && (!hasTime || !hasStop)) {
            return fail(AnswerProblem.NO_NEXT_ACTION, "这一档要同时写清多久、什么时候停。", attempt)
        }
        return AnswerCheck(pass = true, hint = "记下了。")
    }

    /** 通过后放几分钟。 */
    fun releaseMinutes(level: IntensityLevel): Int = level.releaseMinutes

    /** 当天标准：漏得越多越严；连续做到才放宽。 */
    fun levelFor(misses: Int, passes: Int): IntensityLevel = when {
        misses >= MISS_FOR_L3 -> IntensityLevel.L3
        misses >= 1 -> IntensityLevel.L2
        passes >= PASS_FOR_L1 -> IntensityLevel.L1
        else -> IntensityLevel.L2
    }

    /** 不回应到第几秒该做什么。 */
    fun onNoResponse(seconds: Int): NoResponse = when {
        seconds < NUDGE_SECONDS -> NoResponse.WAIT
        seconds < CLOSE_SECONDS -> NoResponse.NUDGE
        else -> NoResponse.CLOSE_AND_MISS
    }

    private fun fail(problem: AnswerProblem, hint: String, attempt: Int): AnswerCheck =
        AnswerCheck(pass = false, problem = problem, hint = hint, reask = attempt <= 0)
}
