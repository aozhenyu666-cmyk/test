package com.dshdriver.app

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.provider.Settings

/** Opt-in repair. A settings write is not proof that Android has bound the service. */
object Protection {
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    private const val JOB = 3103
    private fun prefs(c: Context) = c.getSharedPreferences("protection", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun canWrite(c: Context) = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    fun component(c: Context) = ComponentName(c, WatchService::class.java)
    fun isEnabled(c: Context): Boolean {
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return Settings.Secure.getInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            list.split(':').any { ComponentName.unflattenFromString(it) == component(c) }
    }
    fun lastRepair(c: Context) = prefs(c).getLong("lastRepair", 0)
    fun heartbeat(c: Context) { prefs(c).edit().putLong("heartbeat", System.currentTimeMillis()).apply(); ensure(c) }
    fun lastHeartbeat(c: Context) = prefs(c).getLong("heartbeat", 0)
    fun setEnabled(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("enabled", value).apply()
        schedule(c)
        if (value) ensure(c)
        startIfNeeded(c)
    }
    @Synchronized fun ensure(c: Context): Boolean {
        if (!enabled(c) || !canWrite(c)) return false
        return runCatching {
            if (isEnabled(c)) return@runCatching true
            val cr = c.contentResolver
            val me = component(c)
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val values = cur.split(':').filter(String::isNotBlank).toMutableList()
            if (values.none { ComponentName.unflattenFromString(it) == me }) values += me.flattenToString()
            if (!Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, values.joinToString(":"))) return@runCatching false
            if (!Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)) return@runCatching false
            val written = isEnabled(c)
            if (written) prefs(c).edit().putLong("lastRepair", System.currentTimeMillis()).apply()
            written
        }.getOrDefault(false)
    }
    fun schedule(c: Context) {
        val jobs = c.getSystemService(JobScheduler::class.java)
        if (!enabled(c)) { jobs.cancel(JOB); return }
        if (jobs.getPendingJob(JOB) == null) jobs.schedule(JobInfo.Builder(JOB, ComponentName(c, ProtectionJob::class.java))
            .setPeriodic(15 * 60_000L).setPersisted(true).build())
    }
    fun startIfNeeded(c: Context) {
        if (enabled(c) || Store(c).blocking.enabled) runCatching { c.startForegroundService(Intent(c, ProtectionService::class.java)) }
        else c.stopService(Intent(c, ProtectionService::class.java))
    }
}

class ProtectionJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean { Protection.ensure(this); return false }
    override fun onStopJob(params: JobParameters?) = false
}

class ProtectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        Protection.ensure(context)
        Protection.schedule(context)
        Protection.startIfNeeded(context)
    }
}

/** Separate from accessibility binding so a disconnected accessibility service can be repaired. */
class ProtectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!Protection.enabled(this@ProtectionService) && !Engine.store.blocking.enabled) { stopSelf(); return }
            Protection.heartbeat(this@ProtectionService)
            main.postDelayed(this, 30_000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("protection", "应用拦截与无障碍看护", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "protection").setSmallIcon(R.drawable.ic_driver)
            .setContentTitle("思维驾驶员正在看护").setContentText("拦截所选应用；每 30 秒检查无障碍（开启自动修复后写回）")
            .setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(3, n)
        main.post(tick)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
}
