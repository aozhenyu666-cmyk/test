package com.zongkong.core.control

import com.zongkong.core.Gate
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class TakeoverConfig(
    val enabled: Boolean = false,
    val waitMin: Int = 30,
    val waitMax: Int = 60,
    val durationMin: Int = 15,
    val durationMax: Int = 30,
) {
    fun valid(): Boolean = waitMin in 15..120 && waitMax in waitMin..120 &&
        durationMin in 15..30 && durationMax in durationMin..30
}

@Serializable
enum class DeferralKind { GATE, HQ, ACTION }

/** 缓行不是通过：保留原任务、原日期和补交状态，跨日也不删除。 */
@Serializable
data class Deferral(
    val id: String,
    val date: String,
    val kind: DeferralKind,
    val target: String,
    val title: String,
    val at: Long,
    val until: Long,
    val reason: String,
    val gate: Gate? = null,
    val resolvedAt: Long = 0,
)

@Serializable
data class TakeoverEpisode(
    val firstAt: Long,
    val dueAt: Long,
    val minutes: Int,
    val reason: String,
    val warnedAt: Long = 0,
    val unavailableNotified: Boolean = false,
)

@Serializable
data class ControlBook(
    val episode: TakeoverEpisode? = null,
    val deferrals: List<Deferral> = emptyList(),
)

data class ControlSignal(
    val now: Long,
    val lastContact: Long,
    val active: Boolean = true,
    val quiet: Boolean = false,
    val paused: Boolean = false,
    val hqActive: Boolean = false,
    val guardHealthy: Boolean = true,
    val canNotify: Boolean = true,
    val roundsToday: Int = 0,
    val cooldownUntil: Long = 0,
)

enum class ControlDecision { NONE, CLEAR, WARN, UNAVAILABLE, START }

/** 时间只采样一次；临时缓行只有一个共享的每日额度，模型没有发券工具。 */
object Control {
    const val GRACE_MINUTES = 30
    const val ROUNDS_PER_DAY = 2
    const val COOLDOWN_MS = 60 * 60_000L

    fun passesLeft(book: ControlBook, date: String): Int =
        if (book.deferrals.any { it.date == date }) 0 else 1

    fun graceUntil(book: ControlBook, now: Long): Long? = book.deferrals
        .filter { it.resolvedAt == 0L && it.until > now }.maxOfOrNull { it.until }

    fun gateUntil(book: ControlBook, date: String, gateId: String, now: Long): Long? = book.deferrals
        .filter { it.kind == DeferralKind.GATE && it.date == date && it.target == gateId && it.resolvedAt == 0L && it.until > now }
        .maxOfOrNull { it.until }

    fun defer(
        book: ControlBook, date: String, kind: DeferralKind, target: String, title: String,
        now: Long, reason: String, gate: Gate? = null,
    ): Result<ControlBook> {
        if (passesLeft(book, date) == 0) return Result.failure(IllegalArgumentException("今天的一次缓行已用完，到期后恢复，明天重新获得一次"))
        if (target.isBlank() || title.isBlank()) return Result.failure(IllegalArgumentException("没有可延后的任务"))
        val pass = Deferral("d$now", date, kind, target, title, now, now + GRACE_MINUTES * 60_000L,
            reason.trim().ifBlank { "暂时缓一缓，稍后补交" }, gate)
        return Result.success(book.copy(
            episode = book.episode?.let { it.copy(dueAt = maxOf(it.dueAt, pass.until), warnedAt = 0) },
            deferrals = book.deferrals + pass,
        ))
    }

    fun offer(book: ControlBook, cfg: TakeoverConfig, now: Long, reason: String, rnd: Random): ControlBook {
        if (!cfg.enabled || !cfg.valid() || book.episode != null || graceUntil(book, now) != null) return book
        return book.copy(episode = TakeoverEpisode(
            now, now + rnd.nextInt(cfg.waitMin, cfg.waitMax + 1) * 60_000L,
            rnd.nextInt(cfg.durationMin, cfg.durationMax + 1), reason,
        ))
    }

    fun decide(book: ControlBook, cfg: TakeoverConfig, s: ControlSignal): ControlDecision {
        val e = book.episode ?: return ControlDecision.NONE
        if (!cfg.enabled || !cfg.valid() || s.lastContact > e.firstAt || s.quiet || s.paused || !s.active || s.hqActive ||
            s.roundsToday >= ROUNDS_PER_DAY || s.now < s.cooldownUntil) return ControlDecision.CLEAR
        if (graceUntil(book, s.now) != null || !s.canNotify) return ControlDecision.NONE
        if (s.now >= e.dueAt - 5 * 60_000L && e.warnedAt == 0L) return ControlDecision.WARN
        if (s.now < e.dueAt || s.now - e.warnedAt < 60_000L) return ControlDecision.NONE
        if (!s.guardHealthy) return if (e.unavailableNotified) ControlDecision.NONE else ControlDecision.UNAVAILABLE
        return ControlDecision.START
    }

    fun resolve(book: ControlBook, ids: Set<String>, now: Long): ControlBook = book.copy(
        deferrals = book.deferrals.map { if (it.resolvedAt == 0L && it.id in ids) it.copy(resolvedAt = now) else it },
    )
}
