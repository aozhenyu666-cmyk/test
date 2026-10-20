package com.zongkong.core.director

import kotlinx.serialization.Serializable

/** 导演的设置。数字都是起步值，用户以后在设置里改（施工队 A 做界面时接上）。 */
@Serializable
data class DirectorConfig(
    /** 人格设定：开场白和导演提示词都会带上。 */
    val persona: String = DEFAULT_PERSONA,
    /** 她怎么称呼用户。 */
    val callUser: String = "你",
    /** 每天主动弹卡的上限（她自己的节律，不是给用户定的指标）。 */
    val pushesPerDay: Int = 12,
    /** 同一件事两次打扰之间至少隔多久（分钟）。 */
    val topicGapMin: Int = 20,
    /** 同一件事连续被无视几次就当天不再提。 */
    val topicPatience: Int = 3,
    /** 收线后多久回头看下一步开始没有（分钟）。 */
    val followUpMin: Int = 45,
    /** 主线过点多少分钟算“没开始”。 */
    val lateMin: Int = 15,
    /** 每天给导演的字数预算（输入 + 输出）。v4 提到 120 万：先让她算得动，再看真实花费。 */
    val dailyChars: Long = 1_200_000,
    /** 去演员那里聊的默认时长（分钟）。 */
    val talkMin: Int = 15,
)

const val DEFAULT_PERSONA =
    "你是墨灵，一个和用户并肩的伙伴，女生，说话直接、温和但不迁就。说“我们”，一次一两句，口语。" +
        "不夸奖、不说教、不替他想完；数字对不上就把数字摆出来。他累了可以陪着，他想逃的时候你会坚持约好的事。"

/** 从 [Facts] 读出来的局面：几个派生信号，加一段给模型看的文字。 */
data class Situation(
    val facts: Facts,
    /** 主线过点多少分钟（没排时间 / 已开始 / 已做完为 0）。 */
    val mainLateMin: Int,
    val playing: Boolean,
    val shopping: Boolean,
    val idle: Boolean,
    /** 能不能打扰：静默、总控时间、专注中都不行。 */
    val canInterrupt: Boolean,
    /** 看不清的地方。 */
    val blind: List<String>,
) {
    companion object {
        fun read(f: Facts, cfg: DirectorConfig = DirectorConfig()): Situation {
            val late = if (f.mainPlannedAt > 0 && !f.mainStarted && !f.mainDone && f.now > f.mainPlannedAt) {
                ((f.now - f.mainPlannedAt) / 60_000).toInt()
            } else {
                0
            }
            val blind = f.unknown.toMutableList()
            val h = f.health
            if (h != null && (!h.permission || !h.connected)) blind += "前台应用（无障碍不在线）"
            return Situation(
                facts = f,
                mainLateMin = late,
                playing = f.foregroundCategory == "ENTERTAINMENT",
                shopping = f.foregroundCategory == "SHOPPING",
                idle = !f.inFocus && f.upcoming.isEmpty() && (f.mainTitle.isBlank() || f.mainDone),
                canInterrupt = !f.quiet && !f.hqActive && !f.inFocus,
                blind = blind.distinct(),
            )
        }
    }

    /** 给模型看的局面，固定顺序、尽量短。 */
    fun lines(baton: Baton?): String = buildString {
        val f = facts
        appendLine("时间：${"%02d:%02d".format(f.minute / 60, f.minute % 60)}（北京）")
        appendLine("主线：${f.mainTitle.ifBlank { "没定" }}" + when {
            f.mainDone -> "（做完了）"
            f.mainStarted -> "（在做）"
            mainLateMin > 0 -> "（过点 $mainLateMin 分钟没开始）"
            else -> ""
        })
        if (f.upcoming.isNotEmpty()) appendLine("接下来：${f.upcoming.take(3).joinToString("；")}")
        if (f.foregroundLabel.isNotBlank()) appendLine("现在在用：${f.foregroundLabel}（${f.foregroundMinutes} 分钟）")
        appendLine("今天：做事 ${f.workMinutesToday} 分钟，娱乐 ${f.playMinutesToday} 分钟" + if (f.playLeftMin >= 0) "，还能玩 ${f.playLeftMin} 分钟" else "")
        if (f.spendTodayCents > 0) appendLine("今天花了：¥${"%.2f".format(f.spendTodayCents / 100.0)}")
        if (f.credit >= 0) appendLine("信用：${f.credit}（${f.stance.ifBlank { "未知" }}）")
        if (f.aims.isNotEmpty()) appendLine("长期目标：${f.aims.joinToString("、")}")
        if (f.weekDeliverables.isNotEmpty()) appendLine("本周要交：${f.weekDeliverables.joinToString("、")}")
        if (baton != null) {
            appendLine("接力棒：${baton.question}")
            if (baton.known.isNotBlank()) appendLine("  已知：${baton.known}")
            if (baton.stuck.isNotBlank()) appendLine("  卡在：${baton.stuck}")
            if (baton.nextAsk.isNotBlank()) appendLine("  下一问：${baton.nextAsk}")
        }
        if (blind.isNotEmpty()) appendLine("看不见：${blind.joinToString("、")}")
    }.trim()
}
