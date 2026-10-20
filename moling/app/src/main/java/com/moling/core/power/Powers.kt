package com.moling.core.power

import kotlinx.serialization.Serializable

/*
 * 墨灵的权柄。
 *
 * 用户要的是“尽可能高的权柄”。这里的做法：权柄分三级，墨灵靠“判断准不准、计划被接受多少”自己挣上去，
 * 用户设一个上限（默认最高）。实际等级 = min(用户上限, 挣到的等级)。
 * 凡是要改动总控的（定主线、排行动、专注锁、总控时间、收紧），在任何等级都是“建议”：
 * 由用户点一下、在总控里确认后才执行。等级高低决定的是墨灵多主动地提建议、能不能自己排水道和召唤会话。
 * 不管哪一级，铁律都不变（见 [Charter.forbidden]）：放宽只能走总控的 24 小时冷静期，
 * 电话、短信、支付、地图、紧急退出永远不碰，不替用户花钱、不替用户给别人发消息。
 */

/** 墨灵能做的事，按“对现实的影响”从小到大。 */
@Serializable
enum class Power(val label: String) {
    OBSERVE("看：使用情况、总控状态、记忆"),
    SPEAK("说：通知、小窗、朗读"),
    PLAN("排：生成水道、给总控定主线、加行动"),
    SUMMON("召：打开 GPT / Gemini / 总控，带上准备好的话"),
    TIGHTEN("收：让总控开专注锁、进总控时间、加拦截、缩短娱乐"),
    REPAIR("修：总控无障碍掉了就重新打开（不由墨灵做，交给总控自愈和哨兵）"),
    FREEZE("冻：冻结 App（不由墨灵做，交给总控和哨兵）"),
    LOOSEN("放：申请放宽（只能交给总控走冷静期）"),
}

/** 权柄等级。 */
@Serializable
enum class Tier(val label: String, val level: Int) {
    ADVISOR("参谋：只看、只说、只提建议", 1),
    PLANNER("规划者：可以自己排水道、给总控定主线，收紧要先问", 2),
    STEWARD("管家：可以自己排水道、召唤会话、给总控的建议更主动", 3),
}

/** 一件事在当前等级下怎么处理。 */
@Serializable
enum class Mode { AUTO, ASK, OFF }

data class Verdict(val mode: Mode, val reason: String) {
    val allowed: Boolean get() = mode != Mode.OFF
}

/** 挣权柄用的统计（由 Brain 每晚更新）。 */
@Serializable
data class TrustStats(
    /** 最近 14 天：计划 / 建议被接受的次数和总数。 */
    val accepted: Int = 0,
    val proposed: Int = 0,
    /** 最近 14 天：猜测（假设账本）对了 / 已判定的次数。 */
    val guessRight: Int = 0,
    val guessJudged: Int = 0,
    /** 预言的平均 Brier 分（0 最好，0.25 等于瞎猜）；-1 = 还没有。 */
    val brier: Double = -1.0,
    /** 连续使用的天数。 */
    val days: Int = 0,
)

object Charter {
    /** 无论哪一级都不能做的事（写在代码里，模型说了不算）。 */
    val forbidden = listOf(
        "直接放宽任何规则（只能向总控申请，走 24 小时冷静期）",
        "拦截或冻结电话、短信、支付、地图、系统设置、总控、墨灵自己",
        "关掉或绕过紧急退出",
        "替用户花钱、下单、转账",
        "以用户的名义给别人发消息",
        "读取别的 App 里的私人聊天内容",
        "扩大自己的监控范围（新增权限必须用户在系统里手动授予）",
    )

    /** 永不冻结 / 拦截的包。 */
    val neverTouch = setOf(
        "com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.android.mms",
        "com.google.android.apps.messaging", "com.android.settings", "com.tencent.mm", "com.eg.android.AlipayGphone",
        "com.autonavi.minimap", "com.baidu.BaiduMap", "com.google.android.apps.maps",
        "com.zongkong.app", "com.moling.app",
    )

    /** 每一级每件事的处理方式。 */
    private val table: Map<Tier, Map<Power, Mode>> = mapOf(
        Tier.ADVISOR to mapOf(
            Power.OBSERVE to Mode.AUTO, Power.SPEAK to Mode.AUTO, Power.PLAN to Mode.ASK, Power.SUMMON to Mode.ASK,
            Power.TIGHTEN to Mode.ASK, Power.REPAIR to Mode.OFF, Power.FREEZE to Mode.OFF, Power.LOOSEN to Mode.ASK,
        ),
        Tier.PLANNER to mapOf(
            Power.OBSERVE to Mode.AUTO, Power.SPEAK to Mode.AUTO, Power.PLAN to Mode.AUTO, Power.SUMMON to Mode.AUTO,
            Power.TIGHTEN to Mode.ASK, Power.REPAIR to Mode.OFF, Power.FREEZE to Mode.OFF, Power.LOOSEN to Mode.ASK,
        ),
        Tier.STEWARD to mapOf(
            Power.OBSERVE to Mode.AUTO, Power.SPEAK to Mode.AUTO, Power.PLAN to Mode.AUTO, Power.SUMMON to Mode.AUTO,
            Power.TIGHTEN to Mode.ASK, Power.REPAIR to Mode.OFF, Power.FREEZE to Mode.OFF, Power.LOOSEN to Mode.ASK,
        ),
    )

    /**
     * 挣到的等级：
     *  - 起步是规划者（用户要高权柄，不从最低开始）。
     *  - 用满 7 天，建议接受率 ≥ 60%、猜测准确率 ≥ 55%（且预言 Brier ≤ 0.2，如果有）→ 管家。
     *  - 已判定的猜测 ≥ 10 次但准确率 < 35%，或接受率 < 25% → 降成参谋：常常错的时候，少替人做主。
     */
    fun earned(t: TrustStats): Tier {
        val acceptRate = if (t.proposed > 0) t.accepted.toDouble() / t.proposed else -1.0
        val guessRate = if (t.guessJudged > 0) t.guessRight.toDouble() / t.guessJudged else -1.0
        if ((t.guessJudged >= 10 && guessRate < 0.35) || (t.proposed >= 10 && acceptRate < 0.25)) return Tier.ADVISOR
        val brierOk = t.brier < 0 || t.brier <= 0.2
        if (t.days >= 7 && acceptRate >= 0.6 && guessRate >= 0.55 && brierOk) return Tier.STEWARD
        return Tier.PLANNER
    }

    fun effective(userMax: Tier, t: TrustStats): Tier = if (earned(t).level <= userMax.level) earned(t) else userMax

    fun check(power: Power, tier: Tier): Verdict {
        val mode = table.getValue(tier).getValue(power)
        val why = when (mode) {
            Mode.AUTO -> "${tier.label}：可以直接做"
            Mode.ASK -> "${tier.label}：先问用户"
            Mode.OFF -> "${tier.label}：不能做"
        }
        return Verdict(mode, why)
    }

    /** 冻结 / 拦截前过滤掉永不碰的包。 */
    fun freezable(pkgs: Collection<String>): List<String> = pkgs.filter { it.isNotBlank() && it !in neverTouch }

    /** 给人看的说明（设置页、开场白里用）。 */
    fun describe(tier: Tier): String = buildString {
        appendLine("现在是「${tier.label}」")
        Power.entries.forEach { p ->
            val m = check(p, tier).mode
            appendLine("· ${p.label}：" + when (m) { Mode.AUTO -> "自己做"; Mode.ASK -> "先问你"; Mode.OFF -> "不做" })
        }
        appendLine("永远不做：")
        forbidden.forEach { appendLine("· $it") }
    }.trim()
}
