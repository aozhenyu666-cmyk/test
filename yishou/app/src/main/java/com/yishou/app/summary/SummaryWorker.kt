package com.yishou.app.summary

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yishou.app.YishouApp
import com.yishou.app.data.DailySummary
import com.yishou.app.window.WindowClock
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** 用 WorkManager 每天到点跑一次每晚总结。 */
object SummaryScheduler {
    private const val WORK = "nightly-summary"

    /**
     * replace = true：设置改了时间，按新时间重新排；false：已经排好就不动（应用每次启动时调用）。
     */
    fun schedule(context: Context, replace: Boolean) {
        val app = context.applicationContext as YishouApp
        val p = app.settings.app.value
        val wm = WorkManager.getInstance(context)
        if (!p.summaryEnabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val now = System.currentTimeMillis()
        val next = WindowClock.nextDaily(now, p.summaryHour, p.summaryMinute, ZoneId.systemDefault())
        val request = PeriodicWorkRequestBuilder<SummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(next - now, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK,
            if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as YishouApp
        val date = WindowClock.summaryDate(System.currentTimeMillis(), app.settings.app.value, ZoneId.systemDefault())
        return when (val r = app.summaryEngine.run(date)) {
            is SummaryEngine.Outcome.Saved, is SummaryEngine.Outcome.NoRecord -> Result.success()
            is SummaryEngine.Outcome.Failed -> {
                Log.w(TAG, "总结失败（第 ${runAttemptCount + 1} 次）：${r.error.message}")
                if (runAttemptCount < 2) {
                    Result.retry()
                } else {
                    // 连续失败就记下原因（不覆盖已有的总结），总结页能看到，可以手动“立即总结”重试
                    val dao = app.database.dao()
                    if (dao.getSummary(date.toString()) == null) {
                        dao.upsertSummary(DailySummary(date.toString(), "", "", "总结失败：${r.error.message}", 0, 0, 0))
                    }
                    Result.success()
                }
            }
        }
    }

    companion object {
        private const val TAG = "SummaryWorker"
    }
}
