package io.github.ccch1mneyyy.dsh_app.loop

/**
 * Why the loop stopped seeing things: the small, honest record the settings page shows.
 *
 * Before this existed, every failure on the accessibility path was swallowed by a `runCatching`
 * plus `Log.w` — so a phone where the window list always threw, or where every verification
 * failed, looked exactly like a phone where the loop was simply switched off. Nothing reached the
 * person, and nothing reached a bug report. This keeps the counts and the last few failures on the
 * process, and [LoopPlugin] exposes them.
 *
 * Deliberately platform-free (no Android imports) so the behaviour is covered by JVM tests.
 * Thread-safe: accessibility events arrive on the main thread, the plugin calls from its worker.
 */
class LoopDiagnostics(private val limit: Int = MAX_KEPT, private val clock: () -> Long = System::currentTimeMillis) {
    /** One failure, as the settings page shows it. */
    data class Fault(
        /** Event handlers that threw. */
        val kind: String,
        val detail: String,
        val at: Long,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf("kind" to kind, "detail" to detail, "at" to at)
    }

    private val lock = Any()
    private val kept = ArrayDeque<Fault>()
    private val counts = LinkedHashMap<String, Long>()

    /** Consecutive failures of the same kind; reset by a success of that kind. */
    private val streaks = HashMap<String, Long>()

    /** Last time each kind succeeded, so "no failures" can be told apart from "never ran". */
    private val lastSuccess = HashMap<String, Long>()

    fun fault(kind: String, error: Throwable?, detail: String? = null) = synchronized(lock) {
        val text = (detail ?: error?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown").take(300)
        counts[kind] = (counts[kind] ?: 0L) + 1
        streaks[kind] = (streaks[kind] ?: 0L) + 1
        kept.addLast(Fault(kind, text, clock()))
        while (kept.size > limit) kept.removeFirst()
        Unit
    }

    fun success(kind: String) = synchronized(lock) {
        streaks[kind] = 0L
        lastSuccess[kind] = clock()
        Unit
    }

    /** Total failures of [kind] since process start. */
    fun count(kind: String): Long = synchronized(lock) { counts[kind] ?: 0L }

    /** Failures in a row right now; 0 after a success. */
    fun streak(kind: String): Long = synchronized(lock) { streaks[kind] ?: 0L }

    fun lastSuccessAt(kind: String): Long? = synchronized(lock) { lastSuccess[kind] }

    /** Newest first, at most [limit]. */
    fun recent(): List<Fault> = synchronized(lock) { kept.reversed() }

    fun clear() = synchronized(lock) {
        kept.clear(); counts.clear(); streaks.clear(); lastSuccess.clear()
    }

    /** The map the plugin hands to Dart; every field is optional on the other side. */
    fun toMap(): Map<String, Any?> = synchronized(lock) {
        linkedMapOf(
            "faults" to counts.toMap(),
            "streaks" to streaks.filterValues { it > 0 }.toMap(),
            "recent" to kept.reversed().map { it.toMap() },
            "lastEventAt" to lastSuccess[KIND_EVENT],
            "lastVerifyAt" to lastSuccess[KIND_VERIFY],
            "lastReadAt" to lastSuccess[KIND_READ_FOREGROUND],
        )
    }

    companion object {
        const val MAX_KEPT = 20

        /** An accessibility event handler threw. */
        const val KIND_EVENT = "event"
        /** Reading the foreground (window list / TreeCapture) threw. */
        const val KIND_READ_FOREGROUND = "read_foreground"
        /** The engine rejected or threw on a verified reading. */
        const val KIND_VERIFY = "verify"
        /** Attaching the accessibility service to the loop threw. */
        const val KIND_ATTACH = "attach"
        /** Showing or answering through the interaction surface threw. */
        const val KIND_SURFACE = "surface"
    }
}
