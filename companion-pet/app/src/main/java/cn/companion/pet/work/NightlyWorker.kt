package cn.companion.pet.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.companion.pet.PetApp
import cn.companion.pet.core.memory.Nightly
import cn.companion.pet.data.Stats
import cn.companion.pet.net.ChatRequest
import cn.companion.pet.net.ChatResult
import cn.companion.pet.service.PetService
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 每晚整理：凌晨 3 点左右跑一次（WorkManager 尽力而为），把今天的交流、旧档案、昨天的日记发给 DeepSeek，
 * 一次产出新档案、今天的日记、给用户的信。三样都校验通过才一起提交，否则保留原文件、稍后重试。
 */
class NightlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PetApp
        val retry = run(app) == RETRY
        // 成功（含“今天不用整理”）后接上下一晚；重试时由 WorkManager 自己退避重跑
        if (retry) return Result.retry()
        scheduleNext(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    private suspend fun run(app: PetApp): Int {
        val key = app.apiKey() ?: return DONE
        if (app.stats.get(Stats.Kind.API_CALLS) >= app.settings.dailyLimit) return DONE

        val memory = app.memory
        val prepared = synchronized(app.memoryLock) {
            val day = memory.todayDate() ?: LocalDate.now().minusDays(1)
            // 这天没有任何交流、也已经写过日记：不用再花一次调用
            if (memory.todayLines().isEmpty() && memory.diary(day) != null) return DONE
            Triple(
                day,
                Nightly.prompt(memory.persona(), memory.profile(), memory.todayLines(), memory.diary(day.minusDays(1)).orEmpty(), memory.tombstones()),
                memory.profileLines(),
            )
        }
        val (day, messages, oldProfile) = prepared

        app.stats.add(Stats.Kind.API_CALLS)
        val result = app.client.complete(
            ChatRequest(app.settings.endpoint, app.settings.model, key, messages, maxTokens = Nightly.MAX_TOKENS, timeoutMillis = Nightly.TIMEOUT_MILLIS),
        )
        val output = (result as? ChatResult.Success)?.let { Nightly.parse(it.content, oldProfile) }
            ?: return if (runAttemptCount < 2) RETRY else DONE

        synchronized(app.memoryLock) {
            memory.commitNightly(day, output)
            memory.pruneArchive(LocalDate.now())
        }
        return DONE
    }

    companion object {
        private const val DONE = 0
        private const val RETRY = 1
        private const val NIGHTLY = "nightly"
        private const val WATCHDOG = "watchdog"

        fun schedule(context: Context) {
            scheduleNext(context, ExistingWorkPolicy.KEEP)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WATCHDOG, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        fun scheduleNext(context: Context, policy: ExistingWorkPolicy) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(3, 0)
            if (!next.isAfter(now.plusMinutes(5))) next = next.plusDays(1)
            val request = OneTimeWorkRequestBuilder<NightlyWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NIGHTLY, policy, request)
        }

        /** 设置页的“现在整理一次”，方便测试。 */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NIGHTLY, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<NightlyWorker>().build(),
            )
        }
    }
}

/**
 * 看门狗：每 15 分钟检查一次服务还在不在。系统回收后尝试拉起；
 * 用户主动停止过则不拉起。系统不允许后台启动时只是失败，不重试。
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!PetService.running) PetService.startIfAllowed(applicationContext)
        return Result.success()
    }
}
