package io.github.ccch1mneyyy.dsh_app.phone.a11y

/**
 * The watchdog's decision, separate from Android so it is unit-tested.
 *
 * [listed] is whether the service is in ENABLED_ACCESSIBILITY_SERVICES. [heartbeatAgeMs] is how
 * long ago the main process last wrote its health file (null = never). A fresh heartbeat means
 * the main process is alive even if the service is briefly rebinding; don't fight the system.
 */
class WatchdogPolicy(private val staleHeartbeatMs: Long = DEFAULT_STALE_MS) {
    enum class Action { NONE, CHECK }

    fun decide(switchedOn: Boolean, listed: Boolean, heartbeatAgeMs: Long?): Action {
        if (!switchedOn) return Action.NONE
        if (!listed) return Action.CHECK
        if (heartbeatAgeMs == null || heartbeatAgeMs > staleHeartbeatMs) return Action.CHECK
        return Action.NONE
    }

    companion object {
        const val DEFAULT_STALE_MS = 2 * 60_000L
    }
}
