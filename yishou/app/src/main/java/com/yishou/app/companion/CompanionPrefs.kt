package com.yishou.app.companion

import android.content.Context
import com.yishou.app.core.CompanionConfig
import com.yishou.app.core.PersonaConfig
import com.yishou.app.core.QuestionLedger
import com.yishou.app.core.proactive.ProactiveMode
import com.yishou.app.core.proactive.ProactivePolicy
import com.yishou.app.core.proactive.ProactiveState
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
        activeStartHour = p.getInt("active_start", 8),
        activeEndHour = p.getInt("active_end", 23),
        maxPerDay = p.getInt("max_day", 24),
        restMinutes = p.getInt("rest_min", 30),
        deferMinutes = p.getInt("defer_min", 30),
        topicCooldownMinutes = p.getInt("topic_cool", 180),
        opportunityPercent = p.getInt("chance", 70),
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
            .putInt("active_start", s.activeStartHour)
            .putInt("active_end", s.activeEndHour)
            .putInt("max_day", s.maxPerDay)
            .putInt("rest_min", s.restMinutes)
            .putInt("defer_min", s.deferMinutes)
            .putInt("topic_cool", s.topicCooldownMinutes)
            .putInt("chance", s.opportunityPercent)
            .apply()
    }

    fun lastPromptAt(): Long = p.getLong("last_prompt", 0L)

    /** 上次尝试出题的时刻（不管成没成）：失败也要退避，不能每秒重试。 */
    fun lastAttemptAt(): Long = p.getLong("last_attempt", 0L)

    fun markAttempt(at: Long) {
        p.edit().putLong("last_attempt", at).apply()
    }

    /** 回答/收手之后重新起算间隔：避免“刚答完马上又问”。 */
    fun markAnswered(at: Long) {
        p.edit().putLong("last_prompt", at).putLong("last_attempt", at).apply()
    }

    /** 这次题目是不是本地提醒（没有配模型）。 */
    fun localMode(): Boolean = p.getBoolean("local_mode", false)

    fun setLocalMode(on: Boolean) {
        p.edit().putBoolean("local_mode", on).apply()
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
        p.edit()
            .remove("pending_q")
            .putLong("pending_at", 0L)
            .remove("pending_kind")
            .putLong("pending_topic", 0L)
            .apply()
    }

    private fun today(): String = LocalDate.now().toString()

    fun loadState(now: Long): ProactiveState {
        val hourKey = now / 3_600_000L
        val day = today()
        val lastHour = p.getLong("p_hour_key", 0L)
        val lastDay = p.getString("p_day_key", "") ?: ""
        val raw = ProactiveState(
            mode = runCatching {
                ProactiveMode.valueOf(p.getString("p_mode", "IDLE") ?: "IDLE")
            }.getOrDefault(ProactiveMode.IDLE),
            lastAskAt = p.getLong("p_last_ask", 0L),
            lastAnswerAt = p.getLong("p_last_answer", 0L),
            restUntil = p.getLong("p_rest_until", 0L),
            deferUntil = p.getLong("p_defer_until", 0L),
            unanswered = p.getInt("p_unanswered", 0),
            promptsThisHour = p.getInt("p_prompts_hour", 0),
            promptsToday = p.getInt("p_prompts_today", 0),
            lastTopicId = p.getLong("p_last_topic", 0L),
        )
        val reset = ProactivePolicy.resetCounters(raw, now, hourKey, day, lastHour, lastDay)
        if (hourKey != lastHour || day != lastDay) {
            p.edit()
                .putLong("p_hour_key", hourKey)
                .putString("p_day_key", day)
                .putInt("p_prompts_hour", reset.promptsThisHour)
                .putInt("p_prompts_today", reset.promptsToday)
                .apply()
        }
        return reset
    }

    fun saveState(s: ProactiveState) {
        p.edit()
            .putString("p_mode", s.mode.name)
            .putLong("p_last_ask", s.lastAskAt)
            .putLong("p_last_answer", s.lastAnswerAt)
            .putLong("p_rest_until", s.restUntil)
            .putLong("p_defer_until", s.deferUntil)
            .putInt("p_unanswered", s.unanswered)
            .putInt("p_prompts_hour", s.promptsThisHour)
            .putInt("p_prompts_today", s.promptsToday)
            .putLong("p_last_topic", s.lastTopicId)
            .apply()
    }

    fun pendingKind(): String = p.getString("pending_kind", "") ?: ""

    fun setPendingKind(kind: String) {
        p.edit().putString("pending_kind", kind).apply()
    }

    fun pendingTopicId(): Long = p.getLong("pending_topic", 0L)

    fun setPendingTopicId(id: Long) {
        p.edit().putLong("pending_topic", id).apply()
    }

    fun lastAnswerText(): String = p.getString("last_answer_text", "") ?: ""

    fun setLastAnswerText(text: String) {
        p.edit().putString("last_answer_text", text.take(120)).apply()
    }

    fun persona(): PersonaConfig = PersonaConfig(
        tone = runCatching {
            PersonaConfig.Tone.valueOf(p.getString("persona_tone", "BALANCED") ?: "BALANCED")
        }.getOrDefault(PersonaConfig.Tone.BALANCED),
        maxFollowUps = p.getInt("persona_follow", 2),
    ).sane()

    fun savePersona(persona: PersonaConfig) {
        val s = persona.sane()
        p.edit()
            .putString("persona_tone", s.tone.name)
            .putInt("persona_follow", s.maxFollowUps)
            .apply()
    }

    fun misses(): Int =
        if (p.getString("miss_day", "") == today()) p.getInt("misses", 0) else 0

    fun addMiss() {
        val day = today()
        val n = if (p.getString("miss_day", "") == day) p.getInt("misses", 0) else 0
        p.edit().putString("miss_day", day).putInt("misses", n + 1).apply()
    }
}
