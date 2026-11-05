package com.yishou.app.act

/**
 * 没回应时，先分清是哪一种，再决定怎么接。
 *
 * 沉默不等于逃避。可能是没看懂、卡住了、累了，也可能真是在躲。四种要的帮助完全不同：
 * 没看懂要换个问法，卡住要拆小，累了要停，躲了要把“回来的时间”说出口。
 * 用同一招对付四种情况（比如一律催、一律锁），只会把真困难的人推走、把躲的人练得更会躲。
 *
 * 判断靠两类信号：
 * 1. 一键自报（“没看懂 / 卡住了 / 太累 / 不想做”）——最准，所以要让它比沉默更省事：一下就行，不用解释，不受罚。
 * 2. 行为痕迹（多久没动、打了又删、是不是切去了娱乐应用、几点了、连续干了多久）——只在没自报时用，
 *    而且只给“可能是”，拿不准就直接问一句，不替人下结论。
 */
enum class StallKind(val label: String) {
    CONFUSED("没看懂"),
    STUCK("卡住了"),
    TIRED("太累了"),
    AVOIDING("不想做"),
    UNSEEN("可能没看到"),
    UNKNOWN("说不准"),
}

data class StallSignals(
    /** 用户自己点的原因；有就以它为准 */
    val tapped: StallKind? = null,
    /** 问题发出去多久了 */
    val msSinceAsk: Long,
    /** 问题界面有没有被打开过（通知点开、页面在前台过） */
    val opened: Boolean,
    /** 输入框里打过多少字（含删掉的） */
    val typedChars: Int = 0,
    val deletes: Int = 0,
    /** 问完之后切去了不在白名单里的应用（娱乐、闲聊） */
    val wentToDistraction: Boolean = false,
    /** 本地钟点 0–23 */
    val hour: Int,
    /** 已经连续干了多少分钟（专注流 / 陪练里） */
    val workedMinutes: Int = 0,
    /** 最近 7 天里同一类问题被跳过、敷衍了几次 */
    val recentDodges: Int = 0,
)

data class StallRead(val kind: StallKind, val sure: Boolean, val why: String)

/** 接下来怎么做 */
enum class StallMove(val say: String) {
    /** 再提醒一次，短一点 */
    REMIND("再问一次，只留一句"),
    /** 换个问法、给一个例子 */
    REPHRASE("换个说法，给一个例子"),
    /** 拆小：变成二选一，或者只要第一步 */
    SHRINK("拆小：二选一，或者只做第一步"),
    /** 先停：休息几分钟，记下停在哪 */
    REST("先停 5 分钟，断点记下"),
    /** 说定回来的时间（不追问原因，不罚） */
    COMMIT("说一个回来的时间，到点再叫你"),
    /** 拿不准：亮出四个一键原因 */
    ASK("问一句：是哪种情况？"),
}

object Stall {

    /** 没动静多久才算“没回应” */
    const val QUIET_MS = 90_000L
    /** 连续干了这么久，或者到了这个钟点，优先当“累” */
    const val TIRED_MINUTES = 90
    const val LATE_HOUR = 23

    fun read(s: StallSignals): StallRead? {
        s.tapped?.let { return StallRead(it, true, "自己说的") }
        if (s.msSinceAsk < QUIET_MS) return null
        return when {
            s.wentToDistraction -> StallRead(StallKind.AVOIDING, s.recentDodges >= 2, "问完切去了别的应用")
            !s.opened -> StallRead(StallKind.UNSEEN, false, "问题还没被点开过")
            s.typedChars >= 6 && s.deletes >= 3 -> StallRead(StallKind.STUCK, false, "打了又删了几次")
            s.hour >= LATE_HOUR || s.hour < 5 || s.workedMinutes >= TIRED_MINUTES ->
                StallRead(StallKind.TIRED, false, if (s.workedMinutes >= TIRED_MINUTES) "已经连续干了 ${s.workedMinutes} 分钟" else "太晚了")
            s.recentDodges >= 3 -> StallRead(StallKind.AVOIDING, false, "这类问题最近跳过了 ${s.recentDodges} 次")
            else -> StallRead(StallKind.UNKNOWN, false, "看了，但一个字没写")
        }
    }

    /**
     * 怎么接。拿不准的一律先问（亮一键原因），不猜着罚。
     * 同一个问题最多接三次：第三次不管什么情况都改成“说定回来的时间”，不纠缠。
     */
    fun move(r: StallRead, tries: Int): StallMove {
        if (tries >= 2) return StallMove.COMMIT
        if (!r.sure) return when (r.kind) {
            StallKind.UNSEEN -> StallMove.REMIND
            else -> StallMove.ASK
        }
        return when (r.kind) {
            StallKind.CONFUSED -> StallMove.REPHRASE
            StallKind.STUCK -> StallMove.SHRINK
            StallKind.TIRED -> StallMove.REST
            StallKind.AVOIDING -> StallMove.COMMIT
            StallKind.UNSEEN -> StallMove.REMIND
            StallKind.UNKNOWN -> StallMove.ASK
        }
    }
}
