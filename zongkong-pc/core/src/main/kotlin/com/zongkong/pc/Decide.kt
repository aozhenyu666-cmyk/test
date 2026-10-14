package com.zongkong.pc

import com.zongkong.core.device.PhoneSide
import java.time.Instant
import java.time.ZoneId

/** 整台电脑为什么锁着。 */
enum class LockKind(val title: String) {
    LOCK("锁机中"),
    HQ("总控时间"),
    CURFEW("夜里了"),
    NO_SESSION("这次用电脑做什么？"),
    SESSION_END("时间到了"),
    TOTAL("今天的电脑时间用完了"),
}

sealed interface Verdict {
    data object Ok : Verdict

    /** 整台锁住：盖全屏锁屏。 */
    data class Machine(val kind: LockKind, val reason: String, val until: Long = 0) : Verdict

    /** 只拦这一样：把窗口收起来，盖拦截页。 */
    data class Gate(val target: Target, val reason: String, val used: Int, val limit: Int) : Verdict
}

/** 判断要用的一切。 */
data class Ctx(
    val rules: PcRules,
    val state: PcState,
    val today: UsageDay,
    val phone: PhoneSide?,
    val now: Long,
    val zone: ZoneId,
)

object Decide {
    /** 整台电脑现在能不能用。顺序：锁机 > 总控时间 > 夜里 > 总时长 > 会话。 */
    fun machine(c: Ctx): Verdict {
        val now = c.now
        c.state.activeLock(now)?.let { return Verdict.Machine(LockKind.LOCK, it.reason, it.until) }
        val hq = c.phone?.hqUntil ?: 0
        if (hq > now) return Verdict.Machine(LockKind.HQ, "手机上正在进行总控时间，电脑跟着锁。", hq)
        val session = c.state.activeSession(now)
        val boss = session?.byBoss == true
        if (c.rules.curfew && inCurfew(c.rules, now, c.zone) && !boss) {
            return Verdict.Machine(LockKind.CURFEW, "${hm(c.rules.curfewStart)}–${hm(c.rules.curfewEnd)} 电脑锁着。真有事向总控申请。", curfewEnd(c.rules, now, c.zone))
        }
        if (c.rules.totalMinutes > 0 && c.today.totalMinutes() >= c.rules.totalMinutes && !boss) {
            return Verdict.Machine(LockKind.TOTAL, "今天已经用了 ${c.today.totalMinutes()} 分钟（上限 ${c.rules.totalMinutes}）。还要用，向总控申请，说清楚做什么。")
        }
        if (c.rules.sessions && session == null) {
            val ended = c.state.session?.takeIf { it.until <= now && now - it.until < 6 * 3600_000L }
            return if (ended != null) Verdict.Machine(LockKind.SESSION_END, "说好的「${ended.purpose}」到点了。做完了吗？要继续就再说一次做什么。")
            else Verdict.Machine(LockKind.NO_SESSION, "先说这次开电脑做什么、用多久。")
        }
        return Verdict.Ok
    }

    /** 这一样东西能不能用（整台没锁时才问）。 */
    fun target(c: Ctx, t: Target): Verdict {
        c.state.activeBans(c.now).firstOrNull { it.key == t.key || it.key == "cat:${t.cat.name}" }?.let {
            return Verdict.Gate(t, "${it.label}现在点名拦着（${it.by}：${it.reason}）。", c.today.catMinutes(t.cat), 0)
        }
        val limit = c.rules.limit(t.cat) ?: return Verdict.Ok
        if (passFor(c, t) != null) return Verdict.Ok
        val used = c.today.catMinutes(t.cat)
        if (limit <= 0) return Verdict.Gate(t, "${t.cat.label}今天不给自己开（额度 0）。真要用，向总控申请。", used, 0)
        if (used >= limit) return Verdict.Gate(t, "今天的${t.cat.label}额度 $limit 分钟用完了（已用 $used）。", used, limit)
        return Verdict.Ok
    }

    fun passFor(c: Ctx, t: Target): Pass? = c.state.activePasses(c.now).firstOrNull { p ->
        p.key == t.key || p.key == "cat:${t.cat.name}" || (t.site != null && p.key == "site:${t.site.label}")
    }

    /**
     * 网络层要拦的域名：整台锁着时拦所有有额度的网站；平时拦额度用完的类里的网站；放行的除外。
     */
    fun blockedDomains(c: Ctx): Set<String> {
        if (!c.rules.netBlock) return emptySet()
        val locked = machine(c) !is Verdict.Ok
        return c.rules.sites.filter { s ->
            val t = Target("site:${s.label}", s.label, Cat.of(s.cat), s)
            if (c.state.activeBans(c.now).any { it.key == t.key }) return@filter true
            if (!t.cat.limited) return@filter false
            if (locked) return@filter c.state.activeLock(c.now) != null || passFor(c, t) == null
            target(c, t) !is Verdict.Ok
        }.flatMap { it.domains }.toSet()
    }

    /** 这一类今天还剩几分钟（没额度限制返回 null）。 */
    fun left(c: Ctx, cat: Cat): Int? = c.rules.limit(cat)?.let { (it - c.today.catMinutes(cat)).coerceAtLeast(0) }

    fun inCurfew(r: PcRules, now: Long, zone: ZoneId): Boolean {
        val m = Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
        val s = r.curfewStart
        val e = r.curfewEnd
        return if (s <= e) m in s until e else m >= s || m < e
    }

    private fun curfewEnd(r: PcRules, now: Long, zone: ZoneId): Long {
        val z = Instant.ofEpochMilli(now).atZone(zone)
        var end = z.toLocalDate().atTime(r.curfewEnd / 60, r.curfewEnd % 60).atZone(zone)
        if (!end.isAfter(z)) end = end.plusDays(1)
        return end.toInstant().toEpochMilli()
    }

    fun hm(min: Int) = "%02d:%02d".format(min / 60 % 24, min % 60)
}
