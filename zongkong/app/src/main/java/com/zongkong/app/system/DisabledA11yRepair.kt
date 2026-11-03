package com.zongkong.app.system

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.zongkong.app.guard.GuardService

/** 只补回被删掉的总控组件。写回不代表服务已响应；健康仍由原来的真 ack 判定。 */
object DisabledA11yRepair {
    private const val JOB = 8329
    private fun prefs(c: Context) = c.getSharedPreferences("a11y-repair", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun last(c: Context) = prefs(c).getLong("last", 0)
    fun setEnabled(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("enabled", value).apply()
        schedule(c)
        ensure(c)
    }
    @Synchronized fun ensure(c: Context): Boolean {
        if (!enabled(c) || !SelfHeal.canWrite(c)) return false
        return runCatching {
            val cr = c.contentResolver
            val me = ComponentName(c, GuardService::class.java)
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val on = cur.split(':').any { ComponentName.unflattenFromString(it) == me }
            if (on && Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1) return@runCatching true
            val next = EnabledServiceList.append(cur, me.flattenToString())
            if (!Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)) return@runCatching false
            if (!Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)) return@runCatching false
            val verified = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it) == me }
            if (verified) prefs(c).edit().putLong("last", System.currentTimeMillis()).apply()
            verified
        }.getOrDefault(false)
    }
    fun schedule(c: Context) {
        val jobs = c.getSystemService(JobScheduler::class.java)
        if (!enabled(c)) { jobs.cancel(JOB); return }
        if (jobs.getPendingJob(JOB) == null) jobs.schedule(JobInfo.Builder(JOB, ComponentName(c, DisabledA11yJob::class.java))
            .setPeriodic(15 * 60_000L).setPersisted(true).build())
    }
}

class DisabledA11yJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean { DisabledA11yRepair.ensure(this); return false }
    override fun onStopJob(params: JobParameters?) = false
}
