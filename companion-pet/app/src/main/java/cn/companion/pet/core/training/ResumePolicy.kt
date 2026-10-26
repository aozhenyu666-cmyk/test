package cn.companion.pet.core.training

/**
 * 主动断点恢复：用户离开后，同桌在合适的时机**自己回来**接着原题原步骤问，而不是等用户点开。
 *
 * 规则（本地代码决定，模型无权提高频率）：
 * - 只接已有的未完成训练（同一 questionId / 槽位 / 帮助等级），不新开题；
 * - 必须是用户在场（亮屏解锁）、不在安静/暂停、没有别的会话占着、题目气泡当前没显示、不在核验中；
 * - 两次主动接续至少隔 [cooldownMillis]；每个会话最多 [maxPerSession] 次，用完就不再追，
 *   只在用户自己点开时继续（A29：多次没回应要减少打扰，而不是加码）；
 * - 计数随会话落盘，重启不会重新获得追问次数。
 */
data class ResumeContext(
    val now: Long,
    val userActive: Boolean,
    val quiet: Boolean,
    val paused: Boolean,
    val busy: Boolean,
    val bubbleShown: Boolean,
)

sealed interface ResumeDecision {
    data class Resume(val lead: String) : ResumeDecision
    data class Hold(val reason: String) : ResumeDecision
}

class ResumePolicy(
    private val cooldownMillis: Long = 10 * 60_000L,
    private val maxPerSession: Int = 3,
) {
    fun decide(s: TrainingSession, ctx: ResumeContext): ResumeDecision {
        if (!s.active) return ResumeDecision.Hold("没有未完成的训练")
        if (s.state == TrainingState.EVALUATING) return ResumeDecision.Hold("正在核验")
        if (ctx.bubbleShown) return ResumeDecision.Hold("题目已经在屏幕上")
        if (!ctx.userActive) return ResumeDecision.Hold("用户不在")
        if (ctx.paused) return ResumeDecision.Hold("暂停中")
        if (ctx.quiet) return ResumeDecision.Hold("安静时段")
        if (ctx.busy) return ResumeDecision.Hold("别的会话在进行")
        if (s.proactiveResumes >= maxPerSession) return ResumeDecision.Hold("本次已主动接续 " + maxPerSession + " 次，不再追问")
        val since = ctx.now - maxOf(s.lastProactiveResumeAt, s.updatedAt)
        if (since < cooldownMillis) return ResumeDecision.Hold("离上次不到 " + cooldownMillis / 60_000 + " 分钟")
        val left = maxPerSession - s.proactiveResumes - 1
        val lead = (if (s.state == TrainingState.RECOVERY) "你回来了。刚才那一步还留着：" else "接着刚才那一步：") +
            (if (left == 0) "（这是本次最后一次主动来问，之后点我再继续）" else "")
        return ResumeDecision.Resume(lead)
    }

    val limit: Int get() = maxPerSession
}
