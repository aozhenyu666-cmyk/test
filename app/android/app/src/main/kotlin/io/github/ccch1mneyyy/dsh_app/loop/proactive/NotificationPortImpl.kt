package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings

/**
 * D2: the notification surface of the proactive kernel ([NotificationPort]).
 *
 * Same discipline as `NotificationGate.showPrompt`, and one rule that is *stricter* than it:
 * **a notification never reports [io.github.ccch1mneyyy.dsh_app.loop.core.SurfaceReport.Shown]**.
 *
 * Posting proves only that the system accepted the notification — the person may never look at it,
 * or may look and swipe it away. So:
 *
 *  - after [post] succeeds, the port reports nothing on its own; [ProactiveRuntime] counts the
 *    delivery from the kernel's own `Posted` report;
 *  - the 回应 button reports `Answered("reply")`, i.e. the person really did respond;
 *  - swiping it away reports `Dismissed`, which is *not* evidence of having read it;
 *  - the deadline reports nothing: the kernel's own timer closes the event as TIMEOUT.
 *
 * Everything is per-token: a fresh random action per presentation, a receiver registered only for
 * that action and only for as long as the notification lives, and `RECEIVER_NOT_EXPORTED` on 33+.
 * A button from an older token therefore cannot answer a newer event.
 */
class NotificationPortImpl(private val app: Context) : NotificationPort {
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val ids = java.util.concurrent.atomic.AtomicInteger(7100)

    /** One live presentation: the token, its receiver, its pending intents and its notification id. */
    private inner class Live(val token: String, val id: Int) {
        val intents = mutableListOf<PendingIntent>()
        var receiver: BroadcastReceiver? = null
        var cleaned = false

        fun cleanup() {
            if (cleaned) return
            cleaned = true
            intents.forEach { runCatching { it.cancel() } }
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            receiver = null
            runCatching { nm?.cancel(TAG, id) }
        }
    }

    private val live = java.util.HashMap<String, Live>()

    override fun post(token: String, text: String, deadline: Long): Boolean {
        val manager = nm ?: return false
        if (!available(manager)) {
            Log.w(TAG, "notifications unavailable for $token")
            return false
        }
        cancel(token) // never two live notifications for one token
        val l = Live(token, ids.incrementAndGet())
        val action = "${app.packageName}.DSH_PROACTIVE.$token.${nonce()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action) return
                when (intent.getStringExtra(EXTRA_CHOICE)) {
                    CHOICE_REPLY -> ProactiveRuntime.onSurface(token, io.github.ccch1mneyyy.dsh_app.loop.core.SurfaceReport.Answered("reply"))
                    CHOICE_DISMISS -> ProactiveRuntime.onSurface(token, io.github.ccch1mneyyy.dsh_app.loop.core.SurfaceReport.Dismissed)
                    else -> return
                }
                cancel(token)
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, IntentFilter(action))
            }
            l.receiver = receiver

            fun pending(choice: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
                app, l.id * 8 + code,
                Intent(action).setPackage(app.packageName).putExtra(EXTRA_CHOICE, choice),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            ).also { l.intents += it }

            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(app).setPriority(Notification.PRIORITY_HIGH)
            builder.setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(AppStrings.APP_NAME)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(false)
                .setDeleteIntent(pending(CHOICE_DISMISS, 1))
                .addAction(
                    Notification.Action.Builder(null, AppStrings.get(app, R.string.proactive_reply), pending(CHOICE_REPLY, 0)).build(),
                )
            // setTimeoutAfter only hides it; the kernel's own deadline decides the outcome.
            val remaining = (deadline - System.currentTimeMillis()).coerceIn(1_000L, 10 * 60_000L)
            if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(remaining)
            manager.notify(TAG, l.id, builder.build())
            live[token] = l
            true
        } catch (t: Throwable) {
            Log.w(TAG, "post failed for $token", t)
            l.cleanup()
            false
        }
    }

    override fun cancel(token: String) {
        live.remove(token)?.cleanup()
    }

    override fun cancelAll() {
        val all = live.values.toList()
        live.clear()
        all.forEach { it.cleanup() }
        runCatching { nm?.cancel(TAG, 0) }
    }

    private fun available(manager: NotificationManager): Boolean = runCatching {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 26) return true
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, AppStrings.get(app, R.string.proactive_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = AppStrings.get(app, R.string.proactive_channel_description)
                },
            )
        }
        manager.getNotificationChannel(CHANNEL)?.importance?.let { it >= NotificationManager.IMPORTANCE_HIGH } ?: false
    }.getOrDefault(false)

    private fun nonce(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    companion object {
        private const val TAG = "dsh-proactive-prompt"

        /** Its own channel: muting proactive messages must not mute DSH's agent prompts. */
        const val CHANNEL = "dsh-proactive"
        private const val EXTRA_CHOICE = "proactive_choice"
        private const val CHOICE_REPLY = "reply"
        private const val CHOICE_DISMISS = "dismiss"
    }
}
