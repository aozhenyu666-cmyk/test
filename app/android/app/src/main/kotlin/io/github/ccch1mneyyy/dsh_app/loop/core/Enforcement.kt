package io.github.ccch1mneyyy.dsh_app.loop.core

// P0 执法可靠性（2026-11-08）, pure half. Three small things the phone needs to enforce on its own:
//
//   [RuleBook]       the rules the plugin last handed down, each with its own expiry, kept on disk so
//                    a dead engine does not mean "no rules" (C2).
//   [LockPriority]   which of several live locks is the one the lock page talks about (C5). One table,
//                    so the order is easy to change.
//   [DefianceWindow] "closed the reminder and went on playing": 15 s in an entertainment app,
//                    cumulative, within 5 minutes (C4).
//
// None of these decide anything new. The plugin still decides *whether* there is a lock; the phone
// only keeps what it was told, lets it run out on the local clock, and picks which one to show.

/** One rule from the plugin's `/plan` snapshot. [until] is a wall time on this phone's clock. */
data class LockRule(
    /** One of the KIND_* values in [LockPriority]; an unknown kind still locks, at the lowest rank. */
    val kind: String,
    val until: Long,
    /** What the lock page says the person has to do to get out ("对完今天的账"). */
    val why: String? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("kind" to kind, "until" to until, "why" to why)

    companion object {
        fun fromMap(m: Map<*, *>): LockRule? {
            val kind = M.str(m, "kind")?.trim()?.takeIf { it.isNotEmpty() }?.take(32) ?: return null
            val until = M.long(m, "until")
            if (until <= 0) return null
            return LockRule(kind, until, M.str(m, "why")?.trim()?.takeIf { it.isNotEmpty() }?.take(200))
        }
    }
}

/** Everything the plugin said is in force, as of [at]. */
data class RuleSnapshot(
    /** When the phone received it (local clock); 0 = never. */
    val at: Long = 0,
    val rules: List<LockRule> = emptyList(),
    /** Temporary block list: package → until. */
    val block: Map<String, Long> = emptyMap(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "at" to at, "rules" to rules.map { it.toMap() }, "block" to block,
    )

    companion object {
        fun fromMap(m: Map<*, *>): RuleSnapshot = RuleSnapshot(
            at = M.long(m, "at"),
            rules = M.list(m, "rules").mapNotNull { (it as? Map<*, *>)?.let(LockRule::fromMap) },
            block = (M.map(m, "block") ?: emptyMap<String, Any?>()).entries
                .mapNotNull { (k, v) -> if (k is String && v is Number) k to v.toLong() else null }.toMap(),
        )
    }
}

/**
 * 锁的优先级仲裁表（C5）。排在前面的是主锁：同一时刻只有它的解锁要求出现在锁屏页上。
 * 改顺序就改 [ORDER]，别的地方都读这张表。
 */
object LockPriority {
    const val KIND_REVIEW = "review"     // 晚间对账
    const val KIND_TASK = "task"         // 她们立的任务模式
    const val KIND_MORNING = "morning"   // 早上对线
    const val KIND_NIGHT = "night"       // 深夜锁
    const val KIND_REFUSE = "refuse"     // 拒绝模式（他要的、违抗后开的、拆管控后开的）
    const val KIND_SESSION = "session"   // Gemini 互动舱
    const val KIND_DEFAULT = "default"   // 墙的默认锁（第二轮）：白天非吃饭时段
    const val KIND_EVENING = "evening"   // 第三轮：晚间对账（状态机 evening）
    const val KIND_FOLLOWUP = "followup" // 第三轮：追问锁（含晨间/验收/违抗/连麦后）
    const val KIND_PLAN = "plan"         // 第八轮 P1：计划模态（今天的局还没定下来）

    /**
     * 第二轮定的顺序：深夜锁 > 晚间对账 > 晨间对线 > 任务模式 > 拒绝模式 > Gemini 互动舱 > 默认锁 > 普通提醒。
     * 默认锁排在普通提醒之上、拒绝模式之下（互动舱之后）。改顺序只改这一行。
     *
     * 第三轮插在深夜锁之后：`evening`(90) > `followup`(80) > 任务(70) —— 见《接口约定》第 2 节优先级表。
     * 老版本的 `review`/`morning` 现在都由状态机按 `followup` 下发，留在这里只为兼容旧快照。
     */
    val ORDER: List<String> = listOf(
        KIND_NIGHT, KIND_PLAN, KIND_EVENING, KIND_FOLLOWUP, KIND_REVIEW, KIND_MORNING,
        KIND_TASK, KIND_REFUSE, KIND_SESSION, KIND_DEFAULT,
    )

    fun rank(kind: String): Int = ORDER.indexOf(kind).let { if (it < 0) ORDER.size else it }

    /** The default unlock line for a kind, used when the plugin sent no [LockRule.why]. */
    fun defaultWhy(kind: String): String = when (kind) {
        KIND_NIGHT -> "深夜锁：娱乐锁到早上 7 点"
        KIND_REVIEW -> "晚上对账：和鲸澜对完今天的账才解锁"
        KIND_MORNING -> "早上对线：先说出今天最重要的 3 件事"
        KIND_TASK -> "任务模式：任务验收合格才解锁"
        KIND_SESSION -> "Gemini 互动中，只许 Gemini"
        KIND_DEFAULT -> "默认严格：现在不是吃饭/放风时段，娱乐锁着；到点自动开放"
        KIND_EVENING -> "晚上对账：和鲸澜对完今天的账才解锁"
        KIND_FOLLOWUP -> "追问锁：把这一个问题说清楚才解锁"
        KIND_PLAN -> "计划模态：今天的局还没定下来，10 分钟不确认就自动排"
        else -> "拒绝模式：到点自动解锁"
    }

    /** The one lock to show: highest rank, and among equals the one that lasts longest. */
    fun primary(rules: List<LockRule>): LockRule? =
        rules.sortedWith(compareBy<LockRule> { rank(it.kind) }.thenByDescending { it.until }).firstOrNull()
}

/**
 * 本地规则缓存（C2）. The last snapshot the plugin sent, on disk, so that enforcement survives the
 * engine (and this process) dying. Rules only ever run out; the phone never adds one by itself.
 *
 *  * every rule carries its own [LockRule.until] and stops at that local time;
 *  * no rule outlives [MAX_RULE_MS] past the snapshot it came in, whatever its until says, so a
 *    long-dead engine (or a bad value) can never lock the phone for good;
 *  * [merge] is for the plugin's own answers to `/event` (违抗 / 拆管控 → 拒绝模式): still the plugin's
 *    rule, just not delivered through `/plan`.
 */
class RuleBook(private val store: SnapshotStore) {
    private val lock = Any()

    var snapshot = RuleSnapshot()
        private set

    @Volatile var storageError: String? = null
        private set

    fun recover(): RuleSnapshot = synchronized(lock) {
        when (val r = store.read()) {
            is SnapshotStore.Read.Loaded -> snapshot = RuleSnapshot.fromMap(r.map)
            else -> Unit
        }
        snapshot
    }

    /**
     * A fresh `/plan` snapshot replaces the old one entirely: a rule missing from it is over (the
     * plugin closed the gate, the task was accepted…). Null [raw] = the plugin sent none; nothing moves.
     */
    fun replace(raw: Map<*, *>?, now: Long): Boolean = synchronized(lock) {
        if (raw == null) return@synchronized false
        val parsed = RuleSnapshot.fromMap(raw)
        snapshot = RuleSnapshot(
            at = now,
            rules = parsed.rules.filter { it.until > now }.take(MAX_RULES),
            block = parsed.block.filter { (pkg, until) -> pkg.isNotBlank() && pkg.length <= 255 && until > now }
                .entries.take(MAX_BLOCK).associate { it.key to it.value },
        )
        persist()
        true
    }

    /** One rule from an `/event` answer, kept until the next `/plan` snapshot replaces the lot. */
    fun merge(rule: LockRule, now: Long) = synchronized(lock) {
        if (rule.until <= now) return@synchronized
        val kept = snapshot.rules.filter { it.until > now && !(it.kind == rule.kind && it.until <= rule.until) }
        val same = kept.any { it.kind == rule.kind && it.until >= rule.until }
        snapshot = snapshot.copy(at = if (snapshot.at > 0) snapshot.at else now, rules = if (same) kept else kept + rule)
        persist()
    }

    /** The rules in force at [now] (local clock), capped by [MAX_RULE_MS] from the snapshot. */
    fun active(now: Long): List<LockRule> = synchronized(lock) {
        val s = snapshot
        if (s.at <= 0) return@synchronized emptyList()
        val cap = s.at + MAX_RULE_MS
        s.rules.filter { now < minOf(it.until, cap) }
    }

    /** The lock the page should talk about, or null when nothing is in force. */
    fun primary(now: Long): LockRule? = LockPriority.primary(active(now))

    /** Until when *some* lock is in force (0 = none): the old single "refuse until" number. */
    fun lockedUntil(now: Long): Long {
        val cap = synchronized(lock) { snapshot.at + MAX_RULE_MS }
        return active(now).maxOfOrNull { minOf(it.until, cap) } ?: 0L
    }

    /** The temporary block list still in force. */
    fun blocked(now: Long): Set<String> = synchronized(lock) {
        val s = snapshot
        if (s.at <= 0) return@synchronized emptySet()
        val cap = s.at + MAX_RULE_MS
        s.block.filter { (_, until) -> now < minOf(until, cap) }.keys
    }

    private fun persist() {
        storageError = try {
            store.write(linkedMapOf<String, Any?>("version" to 1) + snapshot.toMap()); null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}".take(300)
        }
    }

    companion object {
        /** Longest a cached rule may run past the snapshot it came in (a night lock is ~7 h). */
        const val MAX_RULE_MS = 12 * 3_600_000L
        const val MAX_RULES = 16
        const val MAX_BLOCK = 50
    }
}

/**
 * D2 按快照执行: turn one `/plan` answer into the map [RuleBook.replace] takes.
 *
 * 第三轮起 `state.layers` 是唯一权威（接口约定 §2 第 1 条）；`rules` 只为没有 `state` 的老引擎兜底，
 * 两者冲突时以 `state` 为准。层和规则同形（`kind`/`until`/`why`），封禁名单仍取老快照里那一份。
 * 返回 null = 这次没有可用的快照，缓存保持不动。
 */
fun planSnapshot(plan: Map<*, *>, now: Long): Map<*, *>? {
    val layers = (plan["state"] as? Map<*, *>)?.get("layers") as? List<*>
        ?: return plan["rules"] as? Map<*, *>
    val block = (plan["rules"] as? Map<*, *>)?.get("block") ?: emptyMap<String, Any?>()
    return linkedMapOf<Any?, Any?>("v" to 1, "at" to now, "rules" to layers, "block" to block)
}

/**
 * 违抗复查（C4）. Opened when a reminder or a lock page closes. Within [windowMs] the person may be in
 * an entertainment app for less than [limitMs] in total; going back to the launcher in between does
 * **not** reset the count. Reaching the limit fires once, then the window is closed.
 *
 * Fed by samples ([sample]) rather than by events, so a missed window-change cannot make it forget
 * time already spent. A gap between samples longer than [maxStepMs] counts as [maxStepMs] at most
 * (the main thread may have been asleep).
 */
class DefianceWindow(
    val windowMs: Long = 5 * 60_000L,
    val limitMs: Long = 15_000L,
    private val maxStepMs: Long = 2_000L,
) {
    var openedAt: Long = -1L
        private set
    var spentMs: Long = 0L
        private set
    var source: String = ""
        private set
    private var lastSample: Long = -1L
    private var lastListed = false

    val isOpen: Boolean get() = openedAt >= 0

    /** (Re)open the window. A second close inside an open window starts a new one from zero. */
    fun open(now: Long, source: String) {
        openedAt = now
        spentMs = 0L
        lastSample = now
        lastListed = false
        this.source = source
    }

    fun close() {
        openedAt = -1L
        spentMs = 0L
        lastSample = -1L
        lastListed = false
    }

    /**
     * One look at the front app at [now]: [listed] = an entertainment app is in front (and no pass
     * allows it). True exactly once, when the cumulative time reaches the limit.
     */
    fun sample(now: Long, listed: Boolean): Boolean {
        if (!isOpen) return false
        val last = lastSample
        if (last >= 0 && lastListed && now > last) spentMs += minOf(now - last, maxStepMs)
        lastSample = now
        lastListed = listed
        if (spentMs >= limitMs) {
            close()
            return true
        }
        if (now - openedAt >= windowMs) close()
        return false
    }
}
