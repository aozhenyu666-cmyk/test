package com.zongkong.pc

import com.zongkong.core.AiConfig

/**
 * 改规则：收紧马上生效，放宽 24 小时后才生效（和手机一样——免得在想玩的那一刻把规则改松）。
 */
object PcPolicy {
    const val DELAY = 24 * 3600_000L

    data class Outcome(val config: PcConfig, val now: List<String>, val later: List<String>) {
        val message: String get() = buildString {
            if (now.isNotEmpty()) append("马上生效：").append(now.joinToString("、"))
            if (later.isNotEmpty()) {
                if (isNotEmpty()) append("。")
                append("24 小时后生效（放宽要冷静期）：").append(later.joinToString("、"))
            }
            if (isEmpty()) append("没有改动")
        }
    }

    private fun lim(v: Int) = if (v <= 0) Int.MAX_VALUE else v

    /** 两份规则取更严的那份。 */
    fun stricter(old: PcRules, new: PcRules): PcRules {
        val apps = (new.apps + old.apps).groupBy { it.exe.lowercase() }.map { (_, v) -> v.maxBy { Cat.of(it.cat).rank } }
        val sites = (new.sites + old.sites).groupBy { it.label }.map { (_, v) ->
            val best = v.maxBy { Cat.of(it.cat).rank }
            best.copy(domains = v.flatMap { it.domains }.distinct(), titleWords = v.flatMap { it.titleWords }.distinct())
        }
        val limits = (old.limits.keys + new.limits.keys).associateWith { k ->
            val a = old.limits[k]
            val b = new.limits[k]
            when {
                a == null -> b!!
                b == null -> a
                else -> minOf(a, b)
            }
        }
        val curfew = when {
            !old.curfew -> Triple(new.curfew, new.curfewStart, new.curfewEnd)
            !new.curfew -> Triple(true, old.curfewStart, old.curfewEnd)
            covers(new, old) -> Triple(true, new.curfewStart, new.curfewEnd)
            else -> Triple(true, old.curfewStart, old.curfewEnd)
        }
        return new.copy(
            apps = apps,
            sites = sites,
            browsers = old.browsers + new.browsers,
            limits = limits,
            totalMinutes = if (lim(new.totalMinutes) <= lim(old.totalMinutes)) new.totalMinutes else old.totalMinutes,
            maxPass = minOf(old.maxPass, new.maxPass),
            sessions = old.sessions || new.sessions,
            sessionMax = minOf(old.sessionMax, new.sessionMax),
            curfew = curfew.first,
            curfewStart = curfew.second,
            curfewEnd = curfew.third,
            netBlock = old.netBlock || new.netBlock,
            screenLook = old.screenLook || new.screenLook,
            checkEvery = if (lim(new.checkEvery) <= lim(old.checkEvery)) new.checkEvery else old.checkEvery,
            autostart = old.autostart || new.autostart,
            modelLock = old.modelLock || new.modelLock,
        )
    }

    /** [a] 的夜间时段是否包含 [b] 的。 */
    fun covers(a: PcRules, b: PcRules): Boolean = minutes(b).all { it in minutes(a) }

    private fun minutes(r: PcRules): Set<Int> {
        val s = r.curfewStart
        val e = r.curfewEnd
        return if (s <= e) (s until e).toSet() else ((s until 24 * 60) + (0 until e)).toSet()
    }

    /** 放宽了哪些（给人看）。 */
    fun looser(old: PcRules, new: PcRules): List<String> = buildList {
        val newApps = new.apps.associateBy { it.exe.lowercase() }
        old.apps.forEach { a ->
            val n = newApps[a.exe.lowercase()]
            if (n == null) add("不再管 ${a.label.ifBlank { a.exe }}")
            else if (Cat.of(n.cat).rank < Cat.of(a.cat).rank) add("${a.label.ifBlank { a.exe }} 改成${Cat.of(n.cat).label}")
        }
        val newSites = new.sites.associateBy { it.label }
        old.sites.forEach { s ->
            val n = newSites[s.label]
            if (n == null) add("不再管 ${s.label}")
            else if (Cat.of(n.cat).rank < Cat.of(s.cat).rank) add("${s.label} 改成${Cat.of(n.cat).label}")
            else if (!n.domains.containsAll(s.domains)) add("${s.label} 少拦几个域名")
        }
        (old.browsers - new.browsers).forEach { add("不再看 $it 的标题") }
        old.limits.forEach { (k, v) ->
            val n = new.limits[k]
            if (n == null || n > v) add("${Cat.of(k).label}额度 ${if (n == null) "不限" else "$n 分钟"}")
        }
        if (lim(new.totalMinutes) > lim(old.totalMinutes)) add(if (new.totalMinutes <= 0) "电脑总时长不限" else "电脑总时长 ${new.totalMinutes} 分钟")
        if (new.maxPass > old.maxPass) add("一次最多放行 ${new.maxPass} 分钟")
        if (old.sessions && !new.sessions) add("不用先说用途")
        if (new.sessionMax > old.sessionMax) add("一次会话最多 ${new.sessionMax} 分钟")
        if (old.curfew && !new.curfew) add("夜里不锁机")
        else if (old.curfew && new.curfew && !covers(new, old)) add("夜间锁机改成 ${Decide.hm(new.curfewStart)}–${Decide.hm(new.curfewEnd)}")
        if (old.netBlock && !new.netBlock) add("不在网络层拦网站")
        if (old.screenLook && !new.screenLook) add("不让总控看屏幕")
        if (lim(new.checkEvery) > lim(old.checkEvery)) add(if (new.checkEvery <= 0) "不再主动问" else "每 ${new.checkEvery} 分钟才问一次")
        if (old.autostart && !new.autostart) add("关掉开机自启")
        if (old.modelLock && !new.modelLock) add("解开模型锁")
    }

    /** 收紧了哪些。 */
    private fun tighter(old: PcRules, tight: PcRules): Boolean = tight != old

    /** 改规则。马上收紧的部分立即生效；放宽的部分排进冷静期（之前排的作废，以最新的为准）。 */
    fun setRules(config: PcConfig, new: PcRules, now: Long): Outcome {
        val old = config.rules
        val tight = stricter(old, new)
        val nowList = if (tighter(old, tight)) listOf("收紧的部分") else emptyList()
        if (tight == new) return Outcome(config.copy(rules = new, pending = null), nowList, emptyList())
        val later = looser(tight, new)
        val summary = later.joinToString("、").ifBlank { "规则调整" }
        return Outcome(config.copy(rules = tight, pending = PcPending(now, now + DELAY, new, summary)), nowList, listOf(summary))
    }

    /** 到点的放宽生效（生效时再和当时的规则比一次：期间收紧过的保留）。 */
    fun applyDue(config: PcConfig, now: Long): PcConfig {
        var c = config
        c.pending?.takeIf { it.due <= now }?.let { p -> c = c.copy(rules = p.rules, pending = null) }
        c.pendingAi?.takeIf { it.due <= now }?.let { p -> c = c.copy(ai = p.ai, pendingAi = null) }
        return c
    }

    /** 换 AI 接口：模型锁开着、现在的能用时，24 小时后生效；第一次填、只改看图模型，立即生效。 */
    fun setAi(config: PcConfig, ai: AiConfig, now: Long): Outcome {
        val old = config.ai
        val core = old.baseUrl.trim() != ai.baseUrl.trim() || old.apiKey.trim() != ai.apiKey.trim() || old.model.trim() != ai.model.trim()
        if (!core || !config.rules.modelLock || !old.ready) return Outcome(config.copy(ai = ai, pendingAi = null), listOf("AI 接口"), emptyList())
        val s = "AI 接口换成 ${ai.model.ifBlank { "（空）" }}"
        return Outcome(config.copy(pendingAi = PendingAi(now, now + DELAY, ai)), emptyList(), listOf(s))
    }
}
