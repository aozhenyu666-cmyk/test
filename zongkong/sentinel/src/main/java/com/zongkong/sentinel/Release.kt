package com.zongkong.sentinel

/*
 * 解除闸门（0.15）：要停用总控 / 把规则改松 / 紧急退出，必须走这一条，哨兵守着。
 *
 * 为什么要它：哨兵是整套自律体系的底。总控崩了、被清理了，只剩哨兵拦着。
 * 如果哨兵能被随手关掉、随手卸载，那这套东西冲动一上来就没了。所以解除要很麻烦、要冷静、要本人：
 *
 *   1. 预约（第一关）：手写一段分析（约 100 字，去掉空白和重复才算数），写清为什么要解除。只能手打，不能粘贴。
 *   2. 冷静期：提交后等一段提前量（默认 2 小时）。用开机计时和墙上时间两头算，
 *      改系统时间往前跳没用——跳一次只会更久。绕不过去。
 *   3. 二次核验（第二关）：冷静期到了之后开一个限时窗口（默认 30 分钟）。要再手写一段确认
 *      （默认 40 字，且不能跟第一段一样）。过了窗口没做，整个作废，重头来。
 *   4. 两关都过，才真正解除，而且只解除一段限定时间（默认 30 分钟），到点自动恢复。
 *
 * 全是纯逻辑，单元测试覆盖。时间同时看两种钟：
 *   - wall：墙上时间（System.currentTimeMillis），能被用户改；
 *   - mono：开机以来的毫秒（SystemClock.elapsedRealtime），用户改不了，但重启会清零。
 * 冷静期要求 wall 和 mono 都走够——改钟缩不了，重启只会重算（更保守，安全）。
 */
object Release {

    /** 闸门的参数。越严越难解除。 */
    data class Rule(
        /** 第一关手写分析的最少有效字数。 */
        val stage1MinChars: Int = 100,
        /** 冷静期（提前量）：第一关提交后要等这么久，第二关才开。 */
        val coolingMs: Long = 2 * 3_600_000L,
        /** 第二关的窗口：冷静期到了之后，这段时间内要完成二次核验，过期作废。 */
        val stage2WindowMs: Long = 30 * 60_000L,
        /** 第二关手写确认的最少有效字数。 */
        val stage2MinChars: Int = 40,
        /** 解除一次管多久。到点自动恢复。 */
        val grantMs: Long = 30 * 60_000L,
    )

    /** 解除什么。 */
    enum class Kind(val label: String) {
        RELAX("把规则改松"),
        EXIT("紧急退出（这段时间不拦）"),
        UNINSTALL("准备卸载 / 停用"),
    }

    /**
     * 一次解除申请。两种钟都记下来，用来算冷静期、识破改钟。
     * analysis / confirm 留着当记录（也用来查“两段不能一样”）。
     */
    data class Request(
        val kind: Kind,
        /** 第一关：手写分析。 */
        val analysis: String,
        val stage1Wall: Long,
        val stage1Mono: Long,
        /** 第二关做完的时刻（没做 = 0）。 */
        val stage2Wall: Long = 0,
        /** 两关都过之后，解除到这个墙上时间。 */
        val grantedUntil: Long = 0,
    )

    enum class Phase {
        /** 冷静中：第二关还没开。 */
        COOLING,
        /** 第二关窗口开着，等手写确认。 */
        VERIFY,
        /** 解除生效中。 */
        GRANTED,
        /** 窗口过了没核验，作废。 */
        EXPIRED,
        /** 解除时间用完了，恢复。 */
        ENDED,
    }

    // ---------- 手写文字的检查 ----------

    /** 去掉空白、标点、数字后的有效字数（照抄一句、全是标点都不算）。 */
    fun effectiveChars(text: String): Int =
        text.count { !it.isWhitespace() && it !in "：:·.。，,、;；-—!！?？()（）【】[]\"'“”‘’0123456789" }

    /** 明显在凑字数：翻来覆去就那几个字。 */
    fun looksPadded(text: String): Boolean {
        val chars = text.filterNot { it.isWhitespace() }
        if (chars.length < 20) return false
        return chars.toSet().size.toDouble() / chars.length < 0.34
    }

    /** 两段核验不能几乎一样（照抄第一段混过第二关）。 */
    fun tooSimilar(a: String, b: String): Boolean {
        val x = a.filterNot { it.isWhitespace() }
        val y = b.filterNot { it.isWhitespace() }
        if (x.isEmpty() || y.isEmpty()) return false
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        // 短的那段里有多少字，在长的那段里连续出现过；粗略但够用
        val common = shorter.windowed(6, 1, false).count { longer.contains(it) }
        val ratio = common.toDouble() / (shorter.length - 5).coerceAtLeast(1)
        return ratio > 0.6
    }

    sealed interface Check {
        data object Ok : Check
        data class No(val why: String) : Check
    }

    /** 第一关提交前检查手写分析。 */
    fun checkStage1(text: String, rule: Rule): Check = checkText(text, rule.stage1MinChars)

    /** 第二关提交前检查手写确认（还要跟第一段比）。 */
    fun checkStage2(text: String, analysis: String, rule: Rule): Check {
        val base = checkText(text, rule.stage2MinChars)
        if (base is Check.No) return base
        if (tooSimilar(text, analysis)) return Check.No("这一段不能照抄第一段，重新写一句你现在的判断")
        return Check.Ok
    }

    private fun checkText(text: String, min: Int): Check {
        val n = effectiveChars(text)
        if (n < min) return Check.No("还差 ${min - n} 字（要手写，不能粘贴）")
        if (looksPadded(text)) return Check.No("别凑字数，认真写")
        return Check.Ok
    }

    // ---------- 状态机 ----------

    /** 提交第一关，开始冷静期。文字不合格返回 null（UI 先 checkStage1）。 */
    fun file(kind: Kind, analysis: String, wall: Long, mono: Long, rule: Rule): Request? {
        if (checkStage1(analysis, rule) !is Check.Ok) return null
        return Request(kind, analysis.trim(), wall, mono)
    }

    /**
     * 冷静期还剩多久（毫秒）。wall 和 mono 两头都要走够，取还剩更多的那个。
     * 改钟往前只会缩短 wall 的部分，mono 照样卡着；重启后 mono 清零，只会算得更久。
     */
    fun coolingLeft(req: Request, wall: Long, mono: Long, rule: Rule): Long {
        val byWall = rule.coolingMs - (wall - req.stage1Wall)
        val byMono = rule.coolingMs - (mono - req.stage1Mono).coerceAtLeast(0)
        return maxOf(byWall, byMono).coerceAtLeast(0)
    }

    /** 第二关窗口的起点（墙上时间，近似给界面显示用）。 */
    fun verifyOpensWall(req: Request, rule: Rule): Long = req.stage1Wall + rule.coolingMs

    fun phase(req: Request, wall: Long, mono: Long, rule: Rule): Phase {
        if (req.stage2Wall > 0) {
            return if (wall < req.grantedUntil) Phase.GRANTED else Phase.ENDED
        }
        if (coolingLeft(req, wall, mono, rule) > 0) return Phase.COOLING
        // 冷静期到了：窗口开着吗
        val opened = req.stage1Wall + rule.coolingMs
        return if (wall <= opened + rule.stage2WindowMs) Phase.VERIFY else Phase.EXPIRED
    }

    /**
     * 做第二关核验。只有正处在 VERIFY 窗口、且文字合格才通过，返回带 grant 的新 Request。
     * 其它情况返回 null（界面按 phase 提示）。
     */
    fun verify(req: Request, confirm: String, wall: Long, mono: Long, rule: Rule): Request? {
        if (phase(req, wall, mono, rule) != Phase.VERIFY) return null
        if (checkStage2(confirm, req.analysis, rule) !is Check.Ok) return null
        return req.copy(stage2Wall = wall, grantedUntil = wall + rule.grantMs)
    }

    /** 现在是不是真的解除了（哨兵据此放开、总控据此让路）。 */
    fun granted(req: Request?, wall: Long, mono: Long, rule: Rule): Boolean =
        req != null && phase(req, wall, mono, rule) == Phase.GRANTED

    /** 这个申请已经走完了（作废或用完），可以清掉。 */
    fun done(req: Request?, wall: Long, mono: Long, rule: Rule): Boolean =
        req != null && phase(req, wall, mono, rule).let { it == Phase.EXPIRED || it == Phase.ENDED }
}
