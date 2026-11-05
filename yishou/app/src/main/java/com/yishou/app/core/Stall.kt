package com.yishou.app.core

/**
 * 没接话的时候，先分清是哪种情况，再决定怎么接。
 *
 * 原则：
 *  - 沉默默认按“状态”处理，不按对抗处理；
 *  - 四个按钮（没看懂 / 卡住了 / 太累 / 不想做）一律不罚，让说实话比沉默更省事；
 *  - 同一个问题最多追 [Stall.MAX_FOLLOW_UPS] 次，之后只要一个回来的时间。
 *
 * 纯函数，不碰 Android，可以单测。
 */
enum class StallKind {
    /** 正常回答了，不算卡住。 */
    ANSWERED,

    /** 一个字都没回。 */
    SILENT,

    /** 回了，但只是“嗯 / 好 / 随便 / 不知道”这类，等于没答。 */
    PERFUNCTORY,

    /** 没看懂。 */
    CONFUSED,

    /** 卡住了：知道在问什么，但做不下去。 */
    STUCK,

    /** 太累。 */
    TIRED,

    /** 不想做。 */
    REFUSE,
}

data class StallContext(
    val kind: StallKind,
    /** 同一个问题已经追过几次。 */
    val followUps: Int = 0,
    /** 从最后一次问到现在过了多少分钟。 */
    val silentMinutes: Int = 0,
)

sealed interface StallReply {
    /** 还能再追一次。 */
    data class FollowUp(val text: String, val followUps: Int) : StallReply

    /** 追不动了，只要一个回来的时间。 */
    data class AskReturnTime(val text: String) : StallReply

    /** 今天到此为止，不再追。 */
    data class Close(val text: String) : StallReply
}

object Stall {
    /** 同一个问题最多追三次。 */
    const val MAX_FOLLOW_UPS = 3

    /** 沉默超过这么久，先当成“人不在”：直接要时间，不再追问。 */
    const val AWAY_MINUTES = 30

    /** 少于等于这么多字（去掉口头禅后）算敷衍。 */
    private const val PERFUNCTORY_CHARS = 4

    /** 超过这么多字就不按“按钮词”识别，避免把解释误判成拒绝。 */
    private const val BUTTON_MAX_CHARS = 12

    private val buttons = listOf(
        StallKind.CONFUSED to listOf("没看懂", "看不懂", "不明白", "不理解", "没明白"),
        StallKind.STUCK to listOf("卡住了", "卡住", "卡了", "不会", "做不下去", "没思路"),
        StallKind.TIRED to listOf("太累", "累了", "困了", "没精神", "想睡"),
        StallKind.REFUSE to listOf("不想做", "不想", "算了", "不做了", "不学"),
    )

    private val fillers = listOf(
        "嗯", "啊", "呃", "哦", "唔", "唉", "哈",
        "好的", "知道", "随便", "行吧", "行", "好", "可以",
    )

    /** 去掉空白、标点和口头禅之后还剩几个字。 */
    fun substance(text: String): Int {
        var s = text.filter { it.isLetterOrDigit() }
        for (f in fillers) s = s.replace(f, "")
        return s.length
    }

    /**
     * 判断这次没接话属于哪种。
     * [tapped] 是用户主动按的按钮；[answer] 是打出来的字，可以为空。
     */
    fun classify(
        answer: String?,
        tapped: StallKind? = null,
        silentMinutes: Int = 0,
    ): StallKind {
        if (tapped != null && tapped != StallKind.ANSWERED &&
            tapped != StallKind.SILENT && tapped != StallKind.PERFUNCTORY
        ) {
            return tapped
        }
        if (answer.isNullOrBlank()) return StallKind.SILENT
        val text = answer.trim()
        val size = substance(text)
        if (size <= BUTTON_MAX_CHARS) {
            buttons.firstOrNull { (_, words) -> words.any { text.contains(it) } }?.let { return it.first }
        }
        return if (size <= PERFUNCTORY_CHARS) StallKind.PERFUNCTORY else StallKind.ANSWERED
    }

    /** 决定这一次怎么接。 */
    fun respond(ctx: StallContext): StallReply {
        if (ctx.kind == StallKind.ANSWERED) return StallReply.Close("")
        val n = ctx.followUps.coerceAtLeast(0)
        if (ctx.kind == StallKind.SILENT && ctx.silentMinutes >= AWAY_MINUTES) return askTime()
        if (n >= MAX_FOLLOW_UPS) return askTime()

        return when (ctx.kind) {
            StallKind.SILENT -> follow(
                if (n == 0) "不着急。回一个字就行：在，还是先放一放？"
                else "还在吗？回个“1”就行，我看到就当你收到了。",
                n,
            )

            StallKind.PERFUNCTORY -> if (n == 0) {
                follow("我换一句能直接答的：刚才那道题，你现在会选哪个？只回字母。", n)
            } else {
                askTime()
            }

            StallKind.CONFUSED -> follow(
                "是我说大了。只看这一步：把题干最后一句念给我，或者回“哪句”。",
                n,
            )

            StallKind.STUCK -> follow(
                "卡在哪一句？把那句抄给我；不想抄就回“第几个字之后”。",
                n,
            )

            StallKind.TIRED -> StallReply.Close("那就停。今天到这儿，明早我再来问一句。")

            StallKind.REFUSE -> StallReply.Close("行，今天不做。原因不用解释。要不要挪到明天？回“挪”就行。")

            StallKind.ANSWERED -> StallReply.Close("")
        }
    }

    private fun follow(text: String, n: Int) = StallReply.FollowUp(text, n + 1)

    private fun askTime() = StallReply.AskReturnTime(
        "先不追了。你下一次能坐下来的时间是什么时候？回一个时间就行，比如 20:30。",
    )
}
