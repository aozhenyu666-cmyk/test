package io.github.ccch1mneyyy.dsh_app.phone.a11y

/**
 * Accessibility health for the "listed, bound, but silently dead" case.
 *
 * The keeper already re-adds the component when the system drops it and re-binds it when the
 * setting lists it but the process is not connected. What it could not see: the service is
 * bound, `instance` is non-null, but events stop arriving and the tree is gone (some ROMs do
 * this after a force-stop/restart, or after the app was frozen). This plan decides from event
 * freshness plus a cheap `rootInActiveWindow`/`windows` probe.
 *
 * Pure Kotlin (no Android imports) so it can be unit-tested on the JVM.
 */
class A11yHealthPlan(
    private val staleEventMs: Long = 5 * 60_000L,
    private val probeFailLimit: Int = 3,
) {
    enum class Verdict { UNKNOWN, HEALTHY, STALE, DEAD }

    data class Result(val verdict: Verdict, val failures: Int)

    /**
     * One evaluation. [connected] — the service instance is alive; [screenOn] — a probe is only
     * meaningful while the screen is on; [lastEventAt] — elapsedRealtime of the last accessibility
     * event (0 = never); [probeOk] — the most recent tree probe returned a root or any window;
     * [previousFailures] — consecutive failed probes before this one.
     */
    fun evaluate(
        now: Long,
        connected: Boolean,
        screenOn: Boolean,
        lastEventAt: Long,
        probeOk: Boolean,
        previousFailures: Int,
    ): Result {
        if (!connected) return Result(Verdict.DEAD, 0)
        if (!screenOn) return Result(Verdict.HEALTHY, previousFailures)
        if (lastEventAt <= 0L) return Result(Verdict.UNKNOWN, previousFailures)
        if (now - lastEventAt <= staleEventMs) return Result(Verdict.HEALTHY, 0)
        if (probeOk) return Result(Verdict.HEALTHY, 0)

        val failures = (previousFailures + 1).coerceAtLeast(1)
        return Result(
            if (failures >= probeFailLimit) Verdict.DEAD else Verdict.STALE,
            failures,
        )
    }

    companion object {
        const val DEFAULT_STALE_EVENT_MS = 5 * 60_000L
        const val DEFAULT_PROBE_FAIL_LIMIT = 3
    }
}

/**
 * Process-wide, in-memory health for the currently connected service. The service feeds it
 * events and probe results; the keeper and the settings page read snapshots. No storage here:
 * if the process dies, the service is gone anyway and the keeper starts from UNKNOWN.
 */
object A11yHealth {
    private val plan = A11yHealthPlan()

    @Volatile private var connected = false
    @Volatile private var lastEventAt = 0L
    @Volatile private var lastProbeAt = 0L
    @Volatile private var lastProbeOk = false
    @Volatile private var lastPackage: String? = null
    @Volatile private var lastReason: String? = null
    @Volatile private var failures = 0
    @Volatile private var verdict = A11yHealthPlan.Verdict.UNKNOWN

    data class Snapshot(
        val connected: Boolean,
        val verdict: A11yHealthPlan.Verdict,
        val lastEventAt: Long,
        val lastProbeAt: Long,
        val lastProbeOk: Boolean,
        val failures: Int,
        val lastPackage: String?,
        val lastReason: String?,
    ) {
        val alive: Boolean get() = verdict != A11yHealthPlan.Verdict.DEAD
        val healthy: Boolean
            get() = verdict == A11yHealthPlan.Verdict.HEALTHY || verdict == A11yHealthPlan.Verdict.UNKNOWN

        fun lastEventAgeMs(now: Long): Long = if (lastEventAt <= 0L) -1L else (now - lastEventAt).coerceAtLeast(0L)
        fun lastProbeAgeMs(now: Long): Long = if (lastProbeAt <= 0L) -1L else (now - lastProbeAt).coerceAtLeast(0L)
    }

    @Synchronized
    fun onConnected(now: Long) {
        connected = true
        lastEventAt = now
        lastProbeAt = 0L
        lastProbeOk = false
        lastPackage = null
        lastReason = null
        failures = 0
        verdict = A11yHealthPlan.Verdict.UNKNOWN
    }

    @Synchronized
    fun onEvent(now: Long, packageName: CharSequence?) {
        connected = true
        lastEventAt = now
        if (packageName != null) lastPackage = packageName.toString()
        failures = 0
        verdict = A11yHealthPlan.Verdict.HEALTHY
    }

    @Synchronized
    fun onProbe(now: Long, ok: Boolean) {
        lastProbeAt = now
        lastProbeOk = ok
        val r = plan.evaluate(now, connected, true, lastEventAt, ok, failures)
        failures = r.failures
        verdict = r.verdict
    }

    @Synchronized
    fun onInterrupt(now: Long) {
        lastProbeAt = now
        lastProbeOk = false
        if (connected && verdict != A11yHealthPlan.Verdict.DEAD) {
            verdict = A11yHealthPlan.Verdict.STALE
        }
    }

    @Synchronized
    fun onUnbound(now: Long, reason: String) {
        connected = false
        lastReason = reason
        failures = 0
        verdict = A11yHealthPlan.Verdict.DEAD
    }

    @Synchronized
    fun snapshot(now: Long, screenOn: Boolean): Snapshot {
        val effective = when {
            !connected -> A11yHealthPlan.Verdict.DEAD
            !screenOn -> A11yHealthPlan.Verdict.HEALTHY
            lastEventAt <= 0L -> A11yHealthPlan.Verdict.UNKNOWN
            else -> verdict
        }
        return Snapshot(
            connected = connected,
            verdict = effective,
            lastEventAt = lastEventAt,
            lastProbeAt = lastProbeAt,
            lastProbeOk = lastProbeOk,
            failures = failures,
            lastPackage = lastPackage,
            lastReason = lastReason,
        )
    }

    /** The keeper's question: is it worth asking the system to bind again? */
    fun isAlive(now: Long, screenOn: Boolean): Boolean = snapshot(now, screenOn).alive

    /** The settings page's question: is it connected and answering right now? */
    fun isHealthy(now: Long, screenOn: Boolean): Boolean = snapshot(now, screenOn).healthy

    @Synchronized
    fun reset() {
        connected = false
        lastEventAt = 0L
        lastProbeAt = 0L
        lastProbeOk = false
        lastPackage = null
        lastReason = null
        failures = 0
        verdict = A11yHealthPlan.Verdict.UNKNOWN
    }
}
