package io.github.ccch1mneyyy.dsh_app.loop.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知上的三个动作；不导出，只由本应用的 PendingIntent 触发。 */
class FocusActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FocusRuntime.ensure(context)
        when (intent?.action) {
            ACTION_PAUSE -> FocusRuntime.pause(5)
            ACTION_RESUME -> FocusRuntime.resume()
            ACTION_STOP -> FocusRuntime.stop("notification")
        }
    }

    companion object {
        const val ACTION_PAUSE = "io.github.ccch1mneyyy.dsh_app.focus.PAUSE"
        const val ACTION_RESUME = "io.github.ccch1mneyyy.dsh_app.focus.RESUME"
        const val ACTION_STOP = "io.github.ccch1mneyyy.dsh_app.focus.STOP"
    }
}
