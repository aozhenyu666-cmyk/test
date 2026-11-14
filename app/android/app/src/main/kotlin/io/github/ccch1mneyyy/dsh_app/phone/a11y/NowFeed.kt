package io.github.ccch1mneyyy.dsh_app.phone.a11y

/**
 * "Now" for the relationship engine: which app is in front, how long, screen, charging.
 *
 * Fed by the accessibility service (window events + screen/charging broadcasts), not by a
 * continuous screen read. This is the minimum context `relationship_check` needs; it does not
 * contain screen text. If the service is not connected the snapshot is empty, and callers must
 * treat that as unknown rather than "not using the phone".
 *
 * Pure Kotlin (no Android imports) so the time arithmetic is unit-tested on the JVM.
 */
object NowFeed {
    data class Snapshot(
        val app: String?,
        val appMinutes: Int,
        val screenOn: Boolean,
        val charging: Boolean,
        val since: Long,
        val lastEventAgeMs: Long,
    )

    private var app: String? = null
    private var since = 0L
    private var lastEventAt = 0L
    private var screenOn = true
    private var charging = false

    /** Today's time per app (ms), for 「今天刷了多久」. Counted on the same events, no polling. */
    private val totals = HashMap<String, Long>()
    private var totalsDay = Long.MIN_VALUE
    /** Where the current app's running segment started counting toward [totals]; -1 = not counting. */
    private var countedFrom = -1L

    private fun closeSegment(now: Long) {
        val a = app ?: return
        if (countedFrom >= 0L) totals[a] = (totals[a] ?: 0L) + (now - countedFrom).coerceAtLeast(0L)
        countedFrom = now
    }

    @Synchronized
    fun onApp(packageName: String?, now: Long) {
        if (packageName.isNullOrBlank()) return
        if (isNoise(packageName)) return
        if (packageName != app) {
            closeSegment(now)
            app = packageName
            since = now
            countedFrom = now
        }
        lastEventAt = now
    }

    /**
     * IME / system UI / permission dialogs are layers over the app, not a new app (same list as the
     * 连麦舱 lock, R4-0). DSH itself is NOT a layer here: time on the lock page or in a DSH chat is
     * not time in the app he was stopped from.
     */
    private fun isNoise(packageName: String): Boolean = FrontChange.isOverlay(packageName)

    @Synchronized
    fun onScreen(on: Boolean, now: Long) {
        screenOn = on
        if (!on) {
            closeSegment(now)
            app = null
            since = 0L
            countedFrom = -1L
        }
        lastEventAt = now
    }

    @Synchronized
    fun onCharging(on: Boolean, now: Long) {
        charging = on
        lastEventAt = now
    }

    @Synchronized
    fun snapshot(now: Long): Snapshot {
        val minutes = if (app == null || since <= 0L) {
            0
        } else {
            ((now - since).coerceAtLeast(0L) / 60_000L).toInt()
        }
        return Snapshot(
            app = app,
            appMinutes = minutes,
            screenOn = screenOn,
            charging = charging,
            since = since,
            lastEventAgeMs = if (lastEventAt <= 0L) -1L else (now - lastEventAt).coerceAtLeast(0L),
        )
    }

    /**
     * Today's top apps by time in front, in whole minutes (running segment included), most first.
     * [day] is the caller's local day number; a new day clears the totals. Elapsed time cannot
     * tell midnight, so the day comes from the caller's wall clock.
     */
    @Synchronized
    fun today(now: Long, day: Long, limit: Int = 5): List<Pair<String, Int>> {
        // The first ask only learns the day: what was counted before it is today's.
        if (totalsDay == Long.MIN_VALUE) totalsDay = day
        if (day != totalsDay) {
            totals.clear()
            totalsDay = day
            if (app != null) countedFrom = now
        }
        val all = HashMap(totals)
        val a = app
        if (a != null && countedFrom >= 0L) all[a] = (all[a] ?: 0L) + (now - countedFrom).coerceAtLeast(0L)
        return all.entries
            .map { it.key to (it.value / 60_000L).toInt() }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit.coerceAtLeast(0))
    }

    @Synchronized
    fun reset() {
        totals.clear()
        totalsDay = Long.MIN_VALUE
        countedFrom = -1L
        app = null
        since = 0L
        lastEventAt = 0L
        screenOn = true
        charging = false
    }
}
