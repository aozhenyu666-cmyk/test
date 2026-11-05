package com.yishou.app.core

/**
 * “下一步动作”必须同时具备三样东西：什么时候开始、第一个物理动作、做多久。
 *
 * 退回的三类写法：
 *  - “复习行测”：太大，今天做不完；
 *  - “认真学习”：太空，不是动作；
 *  - “有空的时候……”：没有开始时刻。
 *
 * 纯函数，不碰 Android，可以单测。
 */
data class NextAction(
    /** 什么时候 / 什么之后开始，例如“吃完饭”“19:30”。 */
    val trigger: String,
    /** 第一个物理动作，例如“打开粉笔做言语第 3 套前 5 题”。 */
    val firstMove: String,
    /** 做多久。 */
    val minutes: Int,
)

enum class NextActionProblem {
    EMPTY,
    NO_START_TIME,
    TOO_BIG,
    TOO_VAGUE,
    NO_DURATION,
    NO_FIRST_MOVE,
}

data class NextActionVerdict(
    val action: NextAction? = null,
    val problem: NextActionProblem? = null,
    val hint: String = "",
) {
    val ok: Boolean get() = action != null
}

object NextActionRules {
    private val vagueTimes = listOf(
        "有空", "有时间", "抽空", "回头", "改天", "晚点", "以后", "看情况",
        "随时", "尽快", "待会", "一会", "找时间", "闲下来", "有空闲",
    )

    private val anchors = listOf(
        "起床", "吃完饭", "吃完", "洗完", "洗完澡", "到公司", "到学校",
        "坐下", "午休", "下班", "睡前", "刷完牙", "到家", "进图书馆",
    )

    private val bigVerbs = listOf(
        "复习", "学习", "准备", "刷题", "做题", "整理", "提升", "搞", "背", "练", "看",
    )

    private val vagueWords = listOf(
        "认真", "努力", "好好", "尽量", "系统", "全面", "深入", "多", "一些", "一下",
    )

    private val actionVerbs = listOf(
        "打开", "做", "写", "抄", "读", "背", "看", "听", "算", "整理",
        "投", "发", "填", "改", "练", "查", "列", "刷", "过", "翻",
    )

    private val minuteRe = Regex("""(\d{1,3})\s*分钟""")
    private val clockRe = Regex("""\d{1,2}\s*[:：]\s*\d{2}""")
    private val pointRe = Regex("""[0-9一二三四五六七八九十两]{1,3}\s*点""")
    private val scopeRe = Regex(
        """第\s*[0-9一二三四五六七八九十]+\s*(套|页|章|节|题|课|单元)|[0-9]+\s*(套|页|章|节|题|课|个|道)""",
    )

    fun check(raw: String): NextActionVerdict {
        val text = raw.trim()
        if (text.isEmpty()) {
            return reject(NextActionProblem.EMPTY, "先写一句：什么事之后、在哪、第一个动作做什么、做多久。")
        }
        // “有空”“晚点”不是时刻，先单独点名，避免被后面的内容蒙混过去。
        if (vagueTimes.any { text.contains(it) }) {
            return reject(
                NextActionProblem.NO_START_TIME,
                "“有空”“晚点”不是时间。换成“吃完饭”“19:30”这种能对上钟点的说法。",
            )
        }
        if (isTooBig(text)) {
            return reject(NextActionProblem.TOO_BIG, "太大了，今天做不完。缩到一套、一页或 5 道题。")
        }
        if (isTooVague(text)) {
            return reject(NextActionProblem.TOO_VAGUE, "这还是愿望，不是动作。写清打开哪个、做哪几题。")
        }
        if (!hasTime(text)) {
            return reject(NextActionProblem.NO_START_TIME, "缺开始时刻。补上“几点”或“什么事之后”。")
        }
        val minutes = minutesOf(text)
        if (minutes <= 0) {
            return reject(NextActionProblem.NO_DURATION, "缺时长。补一句“15 分钟”，到点就停。")
        }
        if (!hasFirstMove(text)) {
            return reject(NextActionProblem.NO_FIRST_MOVE, "缺第一个动作。写清手先碰什么：打开哪个 App、翻到哪一页。")
        }
        return NextActionVerdict(
            action = NextAction(trigger = triggerOf(text), firstMove = text, minutes = minutes),
        )
    }

    /** 只有大类、没有具体范围。 */
    fun isTooBig(text: String): Boolean =
        text.length <= 14 &&
            bigVerbs.any { text.startsWith(it) } &&
            !scopeRe.containsMatchIn(text) &&
            text.none { it.isDigit() }

    /** 只有形容词、没有可执行对象。 */
    fun isTooVague(text: String): Boolean =
        vagueWords.any { text.contains(it) } &&
            !scopeRe.containsMatchIn(text) &&
            text.none { it.isDigit() }

    fun hasTime(text: String): Boolean =
        clockRe.containsMatchIn(text) || pointRe.containsMatchIn(text) || anchors.any { text.contains(it) }

    fun minutesOf(text: String): Int {
        minuteRe.find(text)?.let { return it.groupValues[1].toInt() }
        if (text.contains("半小时") || text.contains("一刻钟")) return 30
        if (text.contains("一个小时") || text.contains("一小时")) return 60
        if (text.contains("两个小时") || text.contains("两小时")) return 120
        return 0
    }

    fun hasFirstMove(text: String): Boolean = actionVerbs.any { text.contains(it) }

    /** 取开始时刻：钟点优先，其次锚点事件，最后取第一个短句。 */
    fun triggerOf(text: String): String {
        clockRe.find(text)?.let { return it.value }
        pointRe.find(text)?.let { return it.value }
        anchors.firstOrNull { text.contains(it) }?.let { return it }
        return text.split('，', ',', '。').firstOrNull()?.trim().orEmpty()
    }

    private fun reject(problem: NextActionProblem, hint: String) =
        NextActionVerdict(problem = problem, hint = hint)
}
