package com.yishou.app.companion

import android.content.Context
import com.yishou.app.core.CompanionConfig
import com.yishou.app.core.QuestionLedger
import org.json.JSONArray
import java.time.LocalDate

/**
 * 伴读的配置和当天账。所有时间都能在设置里调。
 *
 * 只存配置、时间戳、题目文本和最近问过的问题；截图不落盘。
 */
class CompanionPrefs(context: Context) {

    private val p = context.applicationContext.getSharedPreferences("companion", Context.MODE_PRIVATE)

    fun load(): CompanionConfig = CompanionConfig(
        enabled = p.getBoolean("enabled", false),
        triggerSeconds = p.getInt("trigger", 45),
        gapSeconds = p.getInt("gap", 180),
        maxPerHalfHour = p.getInt("cap", 6),
        nudgeSeconds = p.getInt("nudge", 90),
        closeSeconds = p.getInt("close", 150),
        stopAfterMisses = p.getInt("stop", 3),
    ).sane()

    fun save(c: CompanionConfig) {
        val s = c.sane()
        p.edit()
            .putBoolean("enabled", s.enabled)
            .putInt("trigger", s.triggerSeconds)
            .putInt("gap", s.gapSeconds)
            .putInt("cap", s.maxPerHalfHour)
            .putInt("nudge", s.nudgeSeconds)
            .putInt("close", s.closeSeconds)
            .putInt("stop", s.stopAfterMisses)
            .apply()
    }

    fun lastPromptAt(): Long = p.getLong("last_prompt", 0L)

    /** 上次尝试出题的时刻（不管成没成）：失败也要退避，不能每秒重试。 */
    fun lastAttemptAt(): Long = p.getLong("last_attempt", 0L)

    fun markAttempt(at: Long) {
        p.edit().putLong("last_attempt", at).apply()
    }

    fun addPrompt(at: Long) {
        val recent = p.getString("prompts", "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { at - it <= 30 * 60_000L } + at
        p.edit()
            .putString("prompts", recent.joinToString(","))
            .putLong("last_prompt", at)
            .apply()
    }

    fun promptsInHalfHour(now: Long): Int = p.getString("prompts", "").orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
        .count { now - it <= 30 * 60_000L }

    /** 最近问过的问题，用来去重。 */
    fun recentQuestions(): List<String> {
        val raw = p.getString("recent_q", "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun addQuestion(question: String) {
        val next = QuestionLedger.remember(recentQuestions(), question)
        p.edit().putString("recent_q", JSONArray(next).toString()).apply()
    }

    fun pendingQuestion(): String? = p.getString("pending_q", null)?.takeIf { it.isNotBlank() }

    fun pendingAt(): Long = p.getLong("pending_at", 0L)

    fun setPending(question: String, at: Long) {
        p.edit().putString("pending_q", question).putLong("pending_at", at).apply()
    }

    fun clearPending() {
        p.edit().remove("pending_q").putLong("pending_at", 0L).apply()
    }

    private fun today(): String = LocalDate.now().toString()

    fun misses(): Int =
        if (p.getString("miss_day", "") == today()) p.getInt("misses", 0) else 0

    fun addMiss() {
        val day = today()
        val n = if (p.getString("miss_day", "") == day) p.getInt("misses", 0) else 0
        p.edit().putString("miss_day", day).putInt("misses", n + 1).apply()
    }
}
