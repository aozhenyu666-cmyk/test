package com.yishou.app.core.proactive

/**
 * 高频陪练的一场会话：用户手动开始，到点或手动结束，结束后回到平时的低频节奏。
 *
 * 两条节拍互相独立：
 *  - **看**：每 [HighFreqConfig.lookEverySec] 秒取一帧（默认 30 秒，可调）；
 *  - **说**：仍然只有 [ProactiveEngine.decide] 能决定开不开口，这里只把间隔换成
 *    [HighFreqConfig.minGapSec]（默认 90 秒，可调），不新增第二套调度。
 *
 * 画面没变时既不必识图，也不必开口。纯逻辑，不碰 Android，可单测。
 */
data class HighFreqConfig(
    val enabled: Boolean = false,
    /** 每隔多少秒看一次屏幕。 */
    val lookEverySec: Int = 30,
    /** 两次开口至少隔多少秒。 */
    val minGapSec: Int = 90,
    /** 一场会话多长；到点自动收线。 */
    val sessionMinutes: Int = 30,
) {
    /** 界面上可能把参数调反，读出来先夹一遍。 */
    fun sane(): HighFreqConfig = copy(
        lookEverySec = lookEverySec.coerceIn(10, 300),
        minGapSec = minGapSec.coerceIn(30, 1_800),
        sessionMinutes = sessionMinutes.coerceIn(5, 180),
    )
}

data class HighFreqSession(
    val startedAt: Long = 0,
    val endsAt: Long = 0,
    val lastLookAt: Long = 0,
    val lastSpeakAt: Long = 0,
    val looks: Int = 0,
    val speaks: Int = 0,
) {
    val active: Boolean get() = startedAt > 0 && endsAt > startedAt

    fun remainingMs(now: Long): Long = (endsAt - now).coerceAtLeast(0)
}

object HighFreq {
    fun start(cfg: HighFreqConfig, now: Long): HighFreqSession {
        val c = cfg.sane()
        return HighFreqSession(startedAt = now, endsAt = now + c.sessionMinutes * 60_000L)
    }

    /** 会话还在跑：必须显式开始过、且没到结束时间。 */
    fun running(cfg: HighFreqConfig, s: HighFreqSession, now: Long): Boolean =
        cfg.enabled && s.active && now >= s.startedAt && now < s.endsAt

    /** 到点该看一帧了吗。 */
    fun dueLook(cfg: HighFreqConfig, s: HighFreqSession, now: Long): Boolean {
        if (!running(cfg, s, now)) return false
        if (s.lastLookAt <= 0) return true
        return now - s.lastLookAt >= cfg.sane().lookEverySec * 1_000L
    }

    /** 记一次看。 */
    fun onLook(s: HighFreqSession, now: Long): HighFreqSession = s.copy(lastLookAt = now, looks = s.looks + 1)

    /** 记一次开口。 */
    fun onSpeak(s: HighFreqSession, now: Long): HighFreqSession = s.copy(lastSpeakAt = now, speaks = s.speaks + 1)

    /**
     * 高频会话里交给唯一调度器的配置：只改开口间隔和每小时上限，其余沿用用户平时的设置。
     * 会话结束后调用方回到原来的配置，不需要另存一份。
     */
    fun toProactive(cfg: HighFreqConfig, base: ProactiveConfig): ProactiveConfig {
        val c = cfg.sane()
        val perHour = (3_600 / c.minGapSec).coerceAtLeast(2)
        // 会话里已经手动确认过「就在这一场」，不用再等满平时的 5 分钟在场时间。
        return base.copy(minGapSeconds = c.minGapSec, maxPerHour = perHour, presenceMinutes = 1).sane()
    }
}

/**
 * 画面变没变：只比读屏文字，不在这里碰像素。
 *
 * 读不到文字（空串）时视为「可能变了」——宁可多看一眼，也不因为读不到而漏掉。
 */
object ScreenDelta {
    fun changed(before: String?, after: String?): Boolean {
        val a = before?.trim().orEmpty()
        val b = after?.trim().orEmpty()
        if (b.isEmpty()) return true
        return a != b
    }
}
