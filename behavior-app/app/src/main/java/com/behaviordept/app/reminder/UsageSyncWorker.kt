package com.behaviordept.app.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.behaviordept.app.container
import com.behaviordept.app.util.Time
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 防线后台（每小时左右一次）：冷静期到了的规则生效；从系统读用时；超限时提醒一次；
 * 周日晚上 8 点后提醒做周复盘。用时每天自动生成，不需要手动输入（PRD M5 验收）。
 */
class UsageSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val guard = c.guard
        guard.applyDue()
        guard.sync()

        val today = Time.today().toString()
        if (guard.hasUsagePermission) {
            val over = guard.today().filter { it.over }
            if (over.isNotEmpty() && c.settings.mark("over_limit") != today) {
                val text = over.joinToString("；") { "${it.rule.name.ifBlank { "规则" }} ${it.minutes}/${it.rule.dailyLimitMin} 分钟" }
                Notifications.showGuard(applicationContext, "今天的娱乐用时超限了", "$text。放下手机，回去做今日一件事。")
                c.settings.setMark("over_limit", today)
            }
        }

        val now = Time.today()
        if (now.dayOfWeek == DayOfWeek.SUNDAY && LocalTime.now().hour >= 20) {
            val week = Time.weekStart(now).toString()
            if (c.settings.mark("weekly_nudge") != week && c.weekly.saved(Time.weekStart(now)) == null) {
                Notifications.showWeekly(applicationContext, "该做周复盘了", "数据已经整理好：看看这周断在哪，定下周唯一的重点。")
                c.settings.setMark("weekly_nudge", week)
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "usage_sync"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
