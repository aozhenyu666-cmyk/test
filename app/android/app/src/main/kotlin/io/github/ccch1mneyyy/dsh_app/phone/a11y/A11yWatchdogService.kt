package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import java.io.File
import java.util.concurrent.Executors

/**
 * The watchdog lives in its own `:watchdog` process. The accessibility keeper normally runs with
 * the Flutter app; when an OEM kills that process, the keeper dies with it. This process stays
 * small (no Flutter, no engine), keeps a low-priority foreground notification, and checks:
 *
 *   1. is the accessibility service still listed / recently heartbeating?
 *   2. if not, can A11yKeeper add or rebind it (needs WRITE_SECURE_SETTINGS)?
 *
 * It is a best-effort recovery for OEM process kills. A user/system force-stop kills this process
 * too and blocks alarms; no app can auto-restart after that.
 */
class A11yWatchdogService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-a11y-watchdog") }
    private val policy = WatchdogPolicy()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCheck()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground(getString(R.string.watchdog_ok))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "screen receiver", e)
        }
        schedule(this)
        runCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        schedule(this)
        runCheck()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun runCheck() {
        worker.execute {
            try {
                val switched = PhoneControl.switchedOn(this)
                if (!switched) {
                    updateNotification(getString(R.string.watchdog_off))
                    return@execute
                }
                val listed = PhoneA11yService.isEnabled(this)
                val age = A11yHealthFile.ageMs(this)
                if (policy.decide(true, listed, age) == WatchdogPolicy.Action.NONE) {
                    A11yWatchdogLog.note(this, listed, age, null, null, "ok")
                    updateNotification(getString(R.string.watchdog_ok))
                    return@execute
                }

                val granted = A11yKeeper.granted(this)
                checkNow(this)
                val nowListed = PhoneA11yService.isEnabled(this)
                val text = when {
                    nowListed && granted -> getString(R.string.watchdog_restored)
                    granted -> getString(R.string.watchdog_needs_manual)
                    else -> getString(R.string.watchdog_needs_grant)
                }
                A11yWatchdogLog.note(this, listed, age, granted, nowListed, "check")
                updateNotification(text)
                Log.i(TAG, "check listed=$listed age=$age granted=$granted nowListed=$nowListed")
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.watchdog_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun startInForeground(text: String) {
        val notification = build(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, build(text))
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(getString(R.string.watchdog_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "DshA11yWatchdog"
        private const val CHANNEL_ID = "dsh_a11y_watchdog"
        private const val NOTIF_ID = 0x0D5A11
        const val ACTION_CHECK = "io.github.ccch1mneyyy.dsh_app.A11Y_WATCHDOG_CHECK"

        fun start(context: Context) {
            val intent = Intent(context, A11yWatchdogService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
                schedule(context)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, A11yWatchdogService::class.java))
        }

        /** Run one check without starting the service (alarm/boot receivers use this). */
        fun checkNow(context: Context) {
            try {
                if (!PhoneControl.switchedOn(context)) return
                val listed = PhoneA11yService.isEnabled(context)
                val age = A11yHealthFile.ageMs(context)
                if (WatchdogPolicy().decide(true, listed, age) == WatchdogPolicy.Action.NONE) return
                if (age == null || age > WatchdogPolicy.DEFAULT_STALE_MS) {
                    killStaleMain(context)
                }
                A11yKeeper.check(context)
            } catch (e: Exception) {
                Log.w(TAG, "checkNow failed", e)
            }
        }

        /**
         * A frozen main process keeps the accessibility bind in "Binding services" forever and
         * never writes a new heartbeat. Kill only the pid recorded in that heartbeat, and only
         * after confirming /proc/<pid>/cmdline is our main process (pids get reused).
         */
        private fun killStaleMain(context: Context) {
            val pid = A11yHealthFile.pid(context) ?: return
            if (pid <= 0 || pid == Process.myPid()) return
            val cmdline = try {
                File("/proc/$pid/cmdline").readText().trim('\u0000')
            } catch (_: Exception) {
                return
            }
            if (cmdline != context.packageName) return
            Log.i(TAG, "killing stale main process pid=$pid")
            Process.killProcess(pid)
        }

        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, WatchdogCheckReceiver::class.java).setAction(ACTION_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val interval = if (debug) 60_000L else 15 * 60_000L
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + interval,
                    pending,
                )
            } catch (e: Exception) {
                Log.w(TAG, "schedule failed", e)
            }
        }
    }
}
