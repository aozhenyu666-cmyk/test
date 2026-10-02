package com.behaviordept.app.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.behaviordept.app.container
import com.behaviordept.app.data.EventType
import com.behaviordept.app.today.isReviewDue
import com.behaviordept.app.util.Time
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 自测到期提醒（WorkManager，每 2 小时左右检查一次）：
 * 晚上 7 点以后，今天到期的自测还没做，就提醒一次；每天最多一次。顺手确认每日闹钟还在。
 */
class ReviewDueWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val settings = c.settings.current()
        ReminderScheduler.reschedule(applicationContext, settings)

        val now = Time.now()
        if (LocalTime.now().hour < NUDGE_HOUR) return Result.success()
        val today = Time.today().toString()
        if (c.settings.lastReviewNotice() == today) return Result.success()
        val due = c.study.allUnits().filter { it.isReviewDue(now) }
        if (due.isEmpty()) return Result.success()

        val names = due.take(3).joinToString("、") { "「${it.title}」" }
        Notifications.showReviewDue(
            applicationContext,
            "还有 ${due.size} 个自测今天到期",
            "$names 等你来答。隔天再答，间隔会退回去。",
        )
        c.settings.setLastReviewNotice(today)
        c.events.log(EventType.REMINDER_SENT, note = "自测到期 ${due.size} 个")
        return Result.success()
    }

    companion object {
        private const val NAME = "review_due"
        private const val NUDGE_HOUR = 19

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReviewDueWorker>(2, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
