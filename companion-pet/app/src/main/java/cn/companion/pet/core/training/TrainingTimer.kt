package cn.companion.pet.core.training

/**
 * 训练计时：只在“题目已显示、输入可用”时计时。
 *
 * 必须暂停计时的情形（不追责用户）：
 * - 正在录音 / 转写；
 * - 模型请求中；
 * - 系统权限弹窗、来电等外部打断；
 * - 用户明确说“我在想/还在读”。
 *
 * 一律用单调时钟（elapsedRealtime），不跟随系统时间调整。
 */
class TrainingTimer(
    private val firstSilenceMillis: Long = 60_000,
    private val afterScaffoldMillis: Long = 60_000,
    private val afterVoiceMillis: Long = 60_000,
    /** 长题干额外读题时间：按字符数线性增加，封顶。 */
    private val readMillisPerChar: Long = 12,
    private val maxReadBonusMillis: Long = 20_000,
) {
    enum class Stage { IDLE, WAITING_FIRST, WAITING_AFTER_SCAFFOLD, WAITING_AFTER_VOICE, RECOVERY }

    private var armedAt = 0L
    private var pausedAt: Long? = null
    private var pausedTotal = 0L
    private var stage = Stage.IDLE
    private var readBonus = 0L

    val currentStage: Stage get() = stage

    /** 题目显示完成、可以开始回答时调用。[stemLength] 用于给长题加读题时间。 */
    fun arm(now: Long, stemLength: Int = 0) {
        armedAt = now
        pausedAt = null
        pausedTotal = 0
        stage = Stage.WAITING_FIRST
        readBonus = (stemLength.coerceAtLeast(0) * readMillisPerChar).coerceAtMost(maxReadBonusMillis)
    }

    fun disarm() {
        stage = Stage.IDLE
        armedAt = 0
        pausedAt = null
        pausedTotal = 0
        readBonus = 0
    }

    /** 暂停计时（录音、模型请求、外部打断、用户说在想）。可重复调用。 */
    fun pause(now: Long) {
        if (stage == Stage.IDLE || pausedAt != null) return
        pausedAt = now
    }

    fun resume(now: Long) {
        val since = pausedAt ?: return
        pausedTotal += (now - since).coerceAtLeast(0)
        pausedAt = null
    }

    val paused: Boolean get() = pausedAt != null

    /** 已等待的有效毫秒数；暂停期间不增加。 */
    fun waited(now: Long): Long {
        if (stage == Stage.IDLE) return 0
        val effectiveNow = pausedAt ?: now
        return (effectiveNow - armedAt - pausedTotal).coerceAtLeast(0)
    }

    fun threshold(): Long = when (stage) {
        Stage.WAITING_FIRST -> firstSilenceMillis + readBonus
        Stage.WAITING_AFTER_SCAFFOLD -> afterScaffoldMillis
        Stage.WAITING_AFTER_VOICE -> afterVoiceMillis
        else -> Long.MAX_VALUE
    }

    /** 是否到了该给支架/提示的时刻。暂停中永远返回 false。 */
    fun shouldNudge(now: Long): Boolean {
        if (stage == Stage.IDLE || paused) return false
        return waited(now) >= threshold()
    }

    /** 给出支架后进入下一阶段，重新开始计时。 */
    fun afterScaffold(now: Long) {
        armedAt = now
        pausedTotal = 0
        pausedAt = null
        readBonus = 0
        stage = Stage.WAITING_AFTER_SCAFFOLD
    }

    /** 语音提示过之后。 */
    fun afterVoice(now: Long) {
        armedAt = now
        pausedTotal = 0
        pausedAt = null
        readBonus = 0
        stage = Stage.WAITING_AFTER_VOICE
    }

    /** 进入恢复：停止计时，不再催促。 */
    fun toRecovery() {
        stage = Stage.RECOVERY
    }

    fun isRecovery(): Boolean = stage == Stage.RECOVERY

    fun restore(now: Long, session: TrainingSession, stemLength: Int = 0) {
        arm(now, stemLength)
        if (session.remindersUsed >= 2) afterVoice(now)
        else if (session.remindersUsed == 1) afterScaffold(now)
        if (session.state == TrainingState.RECOVERY) toRecovery()
    }
}
