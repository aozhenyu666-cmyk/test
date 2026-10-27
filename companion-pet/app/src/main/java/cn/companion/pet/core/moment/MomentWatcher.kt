package cn.companion.pet.core.moment

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** 可以开口的「时刻」。这些是**事件**，不是定时器：没有事件就不该有性格表演。 */
enum class Moment {
    /** 刚解锁屏幕。 */
    JUST_UNLOCKED,

    /** 刚做完一件事（任务结束、提交完成）。 */
    JUST_FINISHED_TASK,

    /** 从别的应用回到桌宠所在的应用。 */
    RETURNED_TO_APP,

    /** 深夜仍在用（23:00 之后）。 */
    LATE_NIGHT,

    /** 周末。 */
    WEEKEND,

    /** 它自己手里刚攒出一句想说的话 —— 最像活人的时刻。 */
    OWN_THOUGHT_READY,

    /** 已经很久没有任何交互。 */
    LONG_SILENCE,
}

/**
 * 判定时刻用的信号。全部带默认值，方便调用方只填关心的字段。
 *
 * [zone] 带默认值是为了让纯逻辑可测；真实调用应传入设备时区。
 */
data class MomentSignals(
    val now: Long,
    val screenJustOn: Boolean = false,
    val taskJustFinished: Boolean = false,
    val returnedToApp: Boolean = false,
    val ownThoughtReady: Boolean = false,
    val sinceInteractionMillis: Long? = null,
    val typing: Boolean = false,
    val fullscreenVideo: Boolean = false,
    val minutesSinceLastSpoken: Long = Long.MAX_VALUE,
    val zone: ZoneId = ZoneOffset.UTC,
)

/**
 * 时刻识别与闭嘴规则。纯函数，无 I/O、无随机。
 *
 * 两条职责分开：
 * - [detect] 认出「现在是个可以开口的时刻」；
 * - [mustStaySilent] 判断「无论如何都不许说话」。
 *
 * 后者优先级更高：哪怕刚从手里攒出一句想说的话，正在打字也闭嘴。
 */
object MomentWatcher {

    /**
     * 认出现在有哪些时刻。
     *
     * **没有任何信号时返回空集** —— 绝不返回常量时刻，否则就退化成一个定时器，
     * 到点就开口，那正是这一轮要摆脱的「规则触发」。
     */
    fun detect(s: MomentSignals): Set<Moment> {
        val found = mutableSetOf<Moment>()
        if (s.screenJustOn) found += Moment.JUST_UNLOCKED
        if (s.taskJustFinished) found += Moment.JUST_FINISHED_TASK
        if (s.returnedToApp) found += Moment.RETURNED_TO_APP
        if (s.ownThoughtReady) found += Moment.OWN_THOUGHT_READY
        if (s.sinceInteractionMillis != null && s.sinceInteractionMillis >= LONG_SILENCE_MILLIS) {
            found += Moment.LONG_SILENCE
        }

        val local = localTime(s)
        val hour = local.hour
        val dayOfWeek = local.dayOfWeek
        // 深夜：23 点之后，或凌晨 1 点之前。00:00 之后是否安静由 ActivityBudget 决定，
        // 这里只负责「认出现在是深夜」这件事。
        if (hour >= 23 || hour < 1) found += Moment.LATE_NIGHT
        if (dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            found += Moment.WEEKEND
        }
        return found
    }

    /**
     * 必须闭嘴的时刻。
     *
     * 三条硬规则：用户在打字、在看全屏视频、三分钟内刚说过话。
     * 任何自动逻辑都不得绕过这三条。
     */
    fun mustStaySilent(s: MomentSignals): Boolean =
        s.typing || s.fullscreenVideo || s.minutesSinceLastSpoken < SILENCE_AFTER_SPOKEN_MINUTES

    private fun localTime(s: MomentSignals): ZonedDateTime =
        Instant.ofEpochMilli(s.now).atZone(s.zone)

    /** 两小时没有交互才算「很久没动静」。 */
    const val LONG_SILENCE_MILLIS: Long = 2 * 60 * 60_000L

    /** 说完话后至少安静三分钟，否则会变成连珠炮。 */
    const val SILENCE_AFTER_SPOKEN_MINUTES: Long = 3
}
