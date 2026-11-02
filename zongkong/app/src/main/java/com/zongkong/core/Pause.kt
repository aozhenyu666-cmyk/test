package com.zongkong.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「总控停了一段时间」到底是什么：被冻结、正常熄屏休眠，还是进程被清掉却没留下死因。
 *
 * 为什么要它：眼睛日志里常见的情况是——无障碍显示开着、其实不干活，日志在某个时刻之后一条都没有，
 * 系统的死因记录（ApplicationExitInfo）里也什么都没有。没有死因记录说明进程**没死**，而是被系统/厂商
 * 冻结（挂起）了；或者厂商用了不留记录的方式清掉。总控自己在被冻结时什么也记不了，只能在醒来那一刻
 * 回头看：心跳断了多久、这段时间你有没有在用手机（系统的使用记录，总控被冻结时系统照样在记）。
 *
 * 纯逻辑，单元测试直接跑。
 */
object Pause {

    /** 停顿期间系统使用记录里看到的东西。[known] = false：没开「使用情况访问」，看不到。 */
    data class Usage(val screenOnMs: Long = 0, val appSwitches: Int = 0, val known: Boolean = false)

    enum class Kind {
        /** 不算停顿 */
        NONE,
        /** 熄屏时的系统休眠：正常，不用管 */
        DOZE,
        /** 进程没死但被冻结：你在用手机，总控却一动不动 */
        FROZEN,
        /** 分不清是熄屏休眠还是被冻结（没开使用情况访问） */
        UNSURE,
        /** 进程换了一个（被清掉过），系统却没有死因记录 */
        KILLED_SILENT,
    }

    /** 心跳本该多久一次（常驻服务每分钟一次），断了多久才算停顿。 */
    const val MIN_GAP_MS = 5 * 60_000L

    /** 停顿期间亮屏超过这么久、或切换应用超过这么多次，就算「你在用手机」。 */
    const val USED_SCREEN_MS = 2 * 60_000L
    const val USED_SWITCHES = 3

    fun classify(gapMs: Long, sameProcess: Boolean, exitRecorded: Boolean, usage: Usage): Kind {
        if (gapMs < MIN_GAP_MS) return Kind.NONE
        val used = usage.known && (usage.screenOnMs >= USED_SCREEN_MS || usage.appSwitches >= USED_SWITCHES)
        if (!sameProcess) return if (exitRecorded) Kind.NONE else Kind.KILLED_SILENT
        return when {
            used -> Kind.FROZEN
            usage.known -> Kind.DOZE
            else -> Kind.UNSURE
        }
    }

    /** 写进眼睛日志的一句话。DOZE / NONE 返回 null（不记，免得刷屏）。 */
    fun text(kind: Kind, from: Long, to: Long, usage: Usage, zone: ZoneId): String? {
        val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val span = "${hm.format(Instant.ofEpochMilli(from))}–${hm.format(Instant.ofEpochMilli(to))}（${minutes(to - from)}）"
        val use = if (usage.known) "这段时间亮屏 ${minutes(usage.screenOnMs)}、切换应用 ${usage.appSwitches} 次" else ""
        return when (kind) {
            Kind.NONE, Kind.DOZE -> null
            Kind.FROZEN -> "进程没死，但 ${span}一动不动：被系统冻结了。$use，总控却看不到也拦不住。" +
                "系统不会给冻结留死因记录，所以「死因」那栏是空的。修法：vivo 设置里关掉对总控的后台冻结 / 智能后台管理，允许后台高耗电。"
            Kind.UNSURE -> "进程没死，但 ${span}没有执行：可能是熄屏休眠（正常），也可能被冻结。打开「使用情况访问」就能分清。"
            Kind.KILLED_SILENT -> "总控在 ${span}之间被清掉了，系统却没有留下死因记录（厂商直接清理常见这样）。" +
                (if (use.isNotEmpty()) "$use。" else "") + "修法：自启动、允许后台高耗电、最近任务里锁定。"
        }
    }

    fun minutes(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟"
    }
}
