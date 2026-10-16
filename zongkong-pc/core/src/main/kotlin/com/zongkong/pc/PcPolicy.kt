package com.zongkong.pc

import com.zongkong.core.AiConfig

/**
 * 改规则：收紧马上生效；放宽要写为什么，交给总控（AI）审——
 *   批了：24 小时后生效（冷静期照旧）；
 *   驳回：作废，6 小时内不能再申请；
 *   没接 AI：没人审，等 72 小时。
 * 免得在想玩的那一刻把规则改松，也免得“等 24 小时就自动松了”。
 */
object PcPolicy {
    const val DELAY = 24 * 3600_000L
    const val NO_AI_DELAY = 72 * 3600_000L
    const val RETRY_AFTER = 6 * 3600_000L
    const val MIN_WHY = 10

    data class Outcome(
        val config: PcConfig,
        val now: List<String>,
        val later: List<String>,
        /** 放宽没进审（没写为什么、刚被驳回）。 */
        val error: String? = null,
        /** 放宽的部分要交给总控审。 */
        val review: Boolean = false,
    ) {
        val message: String get() = buildString {
            if (now.isNotEmpty()) append("马上生效：").append(now.joinToString("、"))
            if (later.isNotEmpty()) {
                if (isNotEmpty()) append("。")
                append(if (review) "放宽的部分交给总控审（批了 24 小时后生效）：" else "24 小时后生效（放宽要冷静期）：").append(later.joinToString("、"))
            }
            error?.let { if (isNotEmpty()) append("。"); append(it) }
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

    /**
     * 改规则。收紧的部分立即生效；放宽的部分要写 [why]，进总控的审（之前排的作废，以最新的为准）。
     * 没写为什么、刚被驳回过：放宽不进审，收紧照样生效。
     */
    fun setRules(config: PcConfig, new: PcRules, now: Long, why: String = ""): Outcome {
        val old = config.rules
        val tight = stricter(old, new)
        val nowList = if (tighter(old, tight)) listOf("收紧的部分") else emptyList()
        if (tight == new) return Outcome(config.copy(rules = new, pending = null), nowList, emptyList())
        val later = looser(tight, new)
        val summary = later.joinToString("、").ifBlank { "规则调整" }
        val kept = config.copy(rules = tight)
        config.denied?.takeIf { now - it.at < RETRY_AFTER }?.let { d ->
            return Outcome(kept, nowList, emptyList(), error = "上一次放宽（${d.summary}）被总控驳回了：${d.reason}。${Decide.hm(minuteOf(d.at + RETRY_AFTER))} 之后才能再申请")
        }
        if (why.trim().length < MIN_WHY) {
            return Outcome(kept, nowList, emptyList(), error = "放宽（$summary）要写清楚为什么（至少 $MIN_WHY 个字）：总控会看理由和你今天的记录决定批不批")
        }
        return Outcome(kept.copy(pending = PcPending(now, 0, new, summary, why.trim(), reviewing = true)), nowList, listOf(summary), review = true)
    }

    private fun minuteOf(t: Long): Int = java.time.Instant.ofEpochMilli(t).atZone(com.zongkong.core.DayClock.BEIJING).let { it.hour * 60 + it.minute }

    /**
     * 总控审完：[approve] = true 批（24 小时后生效），false 驳回，null 没接 AI / 审不了（72 小时后生效）。
     */
    fun reviewed(config: PcConfig, approve: Boolean?, reason: String, now: Long): PcConfig {
        val p = config.pending?.takeIf { it.reviewing } ?: return config
        return when (approve) {
            true -> config.copy(pending = p.copy(reviewing = false, due = now + DELAY, verdict = reason.ifBlank { "批了" }))
            false -> config.copy(pending = null, denied = Denial(now, p.summary, p.why, reason.ifBlank { "理由不够" }))
            null -> config.copy(pending = p.copy(reviewing = false, due = now + NO_AI_DELAY, verdict = reason.ifBlank { "没有 AI 来审：等 72 小时" }))
        }
    }

    /** 审放宽的总控：只回 JSON。 */
    val REVIEW_SYSTEM = """
        你是「总控」在电脑上的规则审查官。用户清醒时给自己定了电脑规则，现在想放宽一部分。你代表清醒时的他判断：批，还是驳回。
        默认从严。只有同时满足这几条才批：
        1. 理由具体、站得住（为什么现在需要、要做什么），不是“想玩”“放松一下”“无聊”“反正也管不住”。
        2. 放宽的幅度和理由相称（为了一件事把整天的额度翻倍、关掉夜里锁机，不批）。
        3. 和他今天、最近的记录对得上：今天娱乐已经超额、信用低、态度严格时，更要从严。
        4. 能用一次性放行（找总控申请几十分钟）解决的，不该改规则——驳回并告诉他去申请放行。
        批了也要 24 小时后才生效。回复只要 JSON：{"approve": true 或 false, "reason": "一句话，直接对他说，为什么批 / 为什么不批"}
    """.trimIndent()

    fun reviewPrompt(p: PcPending, old: PcRules, status: String, denied: Denial?): String = buildString {
        append("他想放宽：").append(p.summary).append('\n')
        append("他写的理由：「").append(p.why).append("」\n")
        append("现在的规则：额度 ").append(old.limits.entries.joinToString("，") { "${Cat.of(it.key).label} ${it.value} 分" })
        append("；电脑总时长 ").append(if (old.totalMinutes > 0) "${old.totalMinutes} 分" else "不限")
        append("；夜里锁机 ").append(if (old.curfew) "${Decide.hm(old.curfewStart)}–${Decide.hm(old.curfewEnd)}" else "没开").append('\n')
        denied?.let { append("上一次被驳回的放宽：").append(it.summary).append("（理由「").append(it.why).append("」，驳回：").append(it.reason).append("）\n") }
        append("\n此刻的记录：\n").append(status)
    }

    /** 读审查结果；读不出来 = null。 */
    fun parseReview(text: String): Pair<Boolean, String>? {
        val o = com.zongkong.core.LlmClient.extractJson(text) ?: return null
        val a = (o["approve"] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.content == "true" } ?: return null
        val r = (o["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        return a to r
    }

    /** 到点的放宽生效（生效时再和当时的规则比一次：期间收紧过的保留）。还在审的不算。 */
    fun applyDue(config: PcConfig, now: Long): PcConfig {
        var c = config
        c.pending?.takeIf { !it.reviewing && it.due <= now }?.let { p -> c = c.copy(rules = p.rules, pending = null) }
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
