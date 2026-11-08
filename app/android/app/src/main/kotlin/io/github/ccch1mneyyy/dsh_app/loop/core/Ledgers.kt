package io.github.ccch1mneyyy.dsh_app.loop.core

// Two accounts, two books, two files (M1, round 4).
//
//   A. 主动交流账 [ProactiveLedger]   — what the person did with proactive messages
//   B. 行为拦截账 [InterceptLedger]   — what happened when a distracting app was opened
//
// They are deliberately *not* one counter: "the person ignored two chats" and "we blocked
// Douyin three times" are different facts about different features, and a single ignoredCount
// driving both chains would let one feature's silence pause the other. Nothing in this file is
// shared between the two books except the tiny [LedgerIds] helper and the store interface.
//
// Both books are append-safe: every fact carries the eventId it came from and is applied at most
// once, so a repeated delivery (retried report, restart, double tap) never double-counts. Both
// survive a crash because each write is the whole book, through [SnapshotStore] (temp → fsync →
// atomic replace → .bak), and [recover] is the same code path as a first start.

/**
 * A. 主动交流账.
 *
 * @property delivered every proactive message that reached a surface (activity or notification).
 * @property userConfirmedSeen messages the person explicitly acknowledged with 「我看到了」.
 * @property replied messages the person actually answered.
 * @property consecutiveUnanswered acknowledged messages in a row that ran out of the reply window.
 * @property ignoredSeen lifetime count of the same (never reset by an answer).
 */
data class ProactiveLedger(
    val delivered: Long = 0,
    val userConfirmedSeen: Long = 0,
    val replied: Long = 0,
    val consecutiveUnanswered: Int = 0,
    val ignoredSeen: Long = 0,
    val lastRecordedAt: Long = 0,
) {
    /** Confirmed seen but not answered: the two are independent, never derived from one another. */
    val ackedNotReplied: Long get() = (userConfirmedSeen - replied).coerceAtLeast(0)

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "delivered" to delivered, "userConfirmedSeen" to userConfirmedSeen, "replied" to replied,
        "consecutiveUnanswered" to consecutiveUnanswered, "ignoredSeen" to ignoredSeen,
        "lastRecordedAt" to lastRecordedAt,
    )

    companion object {
        fun fromMap(m: Map<*, *>) = ProactiveLedger(
            delivered = M.long(m, "delivered"), userConfirmedSeen = M.long(m, "userConfirmedSeen"),
            replied = M.long(m, "replied"), consecutiveUnanswered = M.int(m, "consecutiveUnanswered"),
            ignoredSeen = M.long(m, "ignoredSeen"), lastRecordedAt = M.long(m, "lastRecordedAt"),
        )
    }
}

/**
 * Book A's owner. One file, one lock; the counters move only through [onAcked] and [onClosed],
 * and both take the eventId they belong to.
 */
class ProactiveLedgerBook(private val store: SnapshotStore) {
    private val lock = Any()
    private val applied = LinkedHashSet<String>()

    var state = ProactiveLedger()
        private set

    @Volatile var storageError: String? = null
        private set
    @Volatile var recoveryIssue: String? = null
        private set

    /** Process start: the whole book, or an honest complaint if the file was unusable. */
    fun recover(): ProactiveLedger = synchronized(lock) {
        when (val r = store.read()) {
            SnapshotStore.Read.Empty -> Unit
            is SnapshotStore.Read.Unreadable -> recoveryIssue = "ledger_unreadable: ${r.error}"
            is SnapshotStore.Read.Loaded -> {
                M.map(r.map, "ledger")?.let { state = ProactiveLedger.fromMap(it) }
                M.list(r.map, "applied").filterIsInstance<String>().let(applied::addAll)
            }
        }
        state
    }

    /**
     * The person pressed 「我看到了」. Counted once per event, whatever the event does afterwards
     * (an acknowledgement followed by an answer is still a confirmation).
     */
    fun onAcked(eventId: String, now: Long): Boolean = synchronized(lock) {
        if (!apply("ack:$eventId")) return@synchronized false
        state = state.copy(userConfirmedSeen = state.userConfirmedSeen + 1, lastRecordedAt = now)
        persist()
        true
    }

    /**
     * A proactive event closed. [engagement] is the kernel's own summary — this book only counts
     * what that summary says, with one exception: an acknowledged event counts as confirmed seen
     * even if the acknowledgement report itself was lost to a crash.
     */
    fun onClosed(eventId: String, engagement: Engagement, now: Long): Boolean = synchronized(lock) {
        if (!apply("close:$eventId")) return@synchronized false
        var next = state
        if (engagement != Engagement.NOT_DELIVERED) next = next.copy(delivered = next.delivered + 1)
        if (engagement == Engagement.ACKED || engagement == Engagement.IGNORED_SEEN) {
            // The acknowledgement happened even if its own report never arrived.
            if (apply("ack:$eventId")) next = next.copy(userConfirmedSeen = next.userConfirmedSeen + 1)
        }
        next = when (engagement) {
            Engagement.RESPONDED -> next.copy(replied = next.replied + 1, consecutiveUnanswered = 0)
            // Closed by the person: the unanswered run ends here, it was not silence.
            Engagement.DISMISSED -> next.copy(consecutiveUnanswered = 0)
            Engagement.IGNORED_SEEN -> next.copy(ignoredSeen = next.ignoredSeen + 1, consecutiveUnanswered = next.consecutiveUnanswered + 1)
            // Acknowledged, window still open / never acknowledged / never delivered: nothing.
            Engagement.ACKED, Engagement.UNCONFIRMED, Engagement.NOT_DELIVERED -> next
        }
        state = next.copy(lastRecordedAt = now)
        persist()
        true
    }

    /** Bookkeeping shared by both entry points; false when this fact was already applied. */
    private fun apply(key: String): Boolean {
        if (!applied.add(key)) return false
        while (applied.size > APPLIED_CAP) applied.remove(applied.first())
        return true
    }

    private fun persist() {
        storageError = try {
            store.write(linkedMapOf("version" to 1, "ledger" to state.toMap(), "applied" to applied.toList())); null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}".take(300)
        }
    }

    companion object {
        /** Enough event ids to cover a day of receipts; oldest are dropped first. */
        const val APPLIED_CAP = 1024
    }
}

/** How one intercept ended. Exactly one per intercept eventId. */
enum class InterceptOutcome {
    /** The page could not be shown: the interception never happened. */
    NOT_SHOWN,
    /** Shown and blocking; the person had not decided yet when it ended (crash, timeout). */
    BLOCKED,
    /** Let through by the person: training passed, authorization rule, or added to the whitelist. */
    RELEASED,
    /** The person took the emergency exit / left the app. */
    EXITED,
    /** The person paused all intercepts for a while. */
    PAUSED,
}

/** One receipt of the intercept book: what was triggered, and how it ended. */
data class InterceptReceipt(
    val eventId: String,
    val packageName: String,
    val triggeredAt: Long,
    val endedAt: Long,
    val outcome: InterceptOutcome,
    val reason: String? = null,
    /** For a release: the wall time passage was granted until (0 otherwise). */
    val releasedUntilWall: Long = 0,
    /** "training" / "rule" / "whitelist" for a release. */
    val releasedBy: String? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "eventId" to eventId, "packageName" to packageName, "triggeredAt" to triggeredAt, "endedAt" to endedAt,
        "outcome" to outcome.name, "reason" to reason, "releasedUntilWall" to releasedUntilWall, "releasedBy" to releasedBy,
    )

    companion object {
        fun fromMap(m: Map<*, *>): InterceptReceipt? = runCatching {
            InterceptReceipt(
                eventId = M.str(m, "eventId")!!, packageName = M.str(m, "packageName")!!,
                triggeredAt = M.long(m, "triggeredAt"), endedAt = M.long(m, "endedAt"),
                outcome = M.enum<InterceptOutcome>(m, "outcome")!!, reason = M.str(m, "reason"),
                releasedUntilWall = M.long(m, "releasedUntilWall"), releasedBy = M.str(m, "releasedBy"),
            )
        }.getOrNull()
    }
}

/**
 * B. 行为拦截账. Independent of [ProactiveLedger] in every way: its own file, its own counters,
 * its own receipts. Nothing here can pause or advance the proactive schedule.
 */
data class InterceptLedger(
    val triggerCount: Long = 0,
    val blockedCount: Long = 0,
    val userReleasedCount: Long = 0,
    val userExitedCount: Long = 0,
    val pausedCount: Long = 0,
    /** Newest last; bounded, the full receipt of every interception. */
    val receipts: List<InterceptReceipt> = emptyList(),
    val lastRecordedAt: Long = 0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "triggerCount" to triggerCount, "blockedCount" to blockedCount, "userReleasedCount" to userReleasedCount,
        "userExitedCount" to userExitedCount, "pausedCount" to pausedCount,
        "receipts" to receipts.map { it.toMap() }, "lastRecordedAt" to lastRecordedAt,
    )

    companion object {
        const val RECEIPT_CAP = 100

        fun fromMap(m: Map<*, *>) = InterceptLedger(
            triggerCount = M.long(m, "triggerCount"), blockedCount = M.long(m, "blockedCount"),
            userReleasedCount = M.long(m, "userReleasedCount"), userExitedCount = M.long(m, "userExitedCount"),
            pausedCount = M.long(m, "pausedCount"),
            receipts = M.list(m, "receipts").mapNotNull { (it as? Map<*, *>)?.let(InterceptReceipt::fromMap) },
            lastRecordedAt = M.long(m, "lastRecordedAt"),
        )
    }
}

/**
 * Book B's owner. Three moments per interception — triggered, blocked (page really shown),
 * ended — each keyed by the eventId and applied once, so a page that reports twice (double tap,
 * retried intent, restart) adds nothing.
 */
class InterceptLedgerBook(private val store: SnapshotStore) {
    private val lock = Any()
    private val applied = LinkedHashSet<String>()

    var state = InterceptLedger()
        private set

    @Volatile var storageError: String? = null
        private set
    @Volatile var recoveryIssue: String? = null
        private set

    fun recover(): InterceptLedger = synchronized(lock) {
        when (val r = store.read()) {
            SnapshotStore.Read.Empty -> Unit
            is SnapshotStore.Read.Unreadable -> recoveryIssue = "intercept_ledger_unreadable: ${r.error}"
            is SnapshotStore.Read.Loaded -> {
                M.map(r.map, "ledger")?.let { state = InterceptLedger.fromMap(it) }
                M.list(r.map, "applied").filterIsInstance<String>().let(applied::addAll)
            }
        }
        state
    }

    /** A rule matched: an interception is being attempted. */
    fun onTriggered(eventId: String, now: Long): Boolean = synchronized(lock) {
        if (!apply("trigger:$eventId")) return@synchronized false
        state = state.copy(triggerCount = state.triggerCount + 1, lastRecordedAt = now)
        persist()
        true
    }

    /** The blocking page is provably on screen. */
    fun onBlocked(eventId: String, now: Long): Boolean = synchronized(lock) {
        if (!apply("blocked:$eventId")) return@synchronized false
        state = state.copy(blockedCount = state.blockedCount + 1, lastRecordedAt = now)
        persist()
        true
    }

    /** How the interception ended. One terminal receipt per eventId. */
    fun onEnded(receipt: InterceptReceipt): Boolean = synchronized(lock) {
        if (!apply("end:${receipt.eventId}")) return@synchronized false
        var next = when (receipt.outcome) {
            InterceptOutcome.RELEASED -> state.copy(userReleasedCount = state.userReleasedCount + 1)
            InterceptOutcome.EXITED -> state.copy(userExitedCount = state.userExitedCount + 1)
            InterceptOutcome.PAUSED -> state.copy(pausedCount = state.pausedCount + 1)
            InterceptOutcome.BLOCKED, InterceptOutcome.NOT_SHOWN -> state
        }
        next = next.copy(
            receipts = (next.receipts + receipt).takeLast(InterceptLedger.RECEIPT_CAP),
            lastRecordedAt = receipt.endedAt,
        )
        state = next
        persist()
        true
    }

    private fun apply(key: String): Boolean {
        if (!applied.add(key)) return false
        while (applied.size > APPLIED_CAP) applied.remove(applied.first())
        return true
    }

    private fun persist() {
        storageError = try {
            store.write(linkedMapOf("version" to 1, "ledger" to state.toMap(), "applied" to applied.toList())); null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}".take(300)
        }
    }

    companion object {
        const val APPLIED_CAP = 1024
    }
}
