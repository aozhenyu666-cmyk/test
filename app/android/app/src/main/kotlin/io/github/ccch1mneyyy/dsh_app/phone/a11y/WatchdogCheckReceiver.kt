package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarm target only: not exported, so third-party apps cannot trigger watchdog checks. */
class WatchdogCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != A11yWatchdogService.ACTION_CHECK) return
        val pending = goAsync()
        Thread({
            try {
                A11yWatchdogService.checkNow(context)
            } finally {
                pending.finish()
            }
        }, "dsh-a11y-watchdog-check").start()
        A11yWatchdogService.schedule(context)
    }
}
