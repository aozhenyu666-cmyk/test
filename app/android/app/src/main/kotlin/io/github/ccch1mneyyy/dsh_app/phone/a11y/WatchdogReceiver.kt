package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot / update / unlock entry points for the watchdog. Runs in `:watchdog`. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT -> {
                A11yWatchdogService.start(context)
                A11yWatchdogService.schedule(context)
            }
        }
    }
}
