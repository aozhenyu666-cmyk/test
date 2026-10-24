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
import cn.companion.pet.core.memory.NightlyCommitOutcome
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
        // 上次提交如果在任意一步中断，先把 journal 做完；这一步不花预算。
        app.nightly.recover()
        // 批次只取“准备那一刻”的待处理事件；请求期间新增的事件不进批次。
        val prepared = app.nightly.prepare(LocalDate.now()) ?: return DONE
        val today = LocalDate.now().toString()
        // 所有调用入口共用同一本预算：先预留，再发请求。
        if (!app.budget.reserve(app.settings.dailyLimit, today)) return DONE
        app.stats.add(Stats.Kind.API_CALLS)

        val oldProfile = synchronized(app.memoryLock) { app.memory.profileLines() }
        val result = app.client.complete(
            ChatRequest(app.settings.endpoint, app.settings.model, key, prepared.messages, maxTokens = Nightly.MAX_TOKENS, timeoutMillis = Nightly.TIMEOUT_MILLIS),
        )
        val output = (result as? ChatResult.Success)?.let { Nightly.parse(it.content, oldProfile) }
            ?: return if (runAttemptCount < 2) RETRY else DONE

        return when (app.nightly.commit(prepared.batch, output)) {
            // 提交成功（或这次 job 已经提交过）：本轮结束，不重复归档。
            NightlyCommitOutcome.COMMITTED, NightlyCommitOutcome.ALREADY_COMMITTED -> {
                // 日记里值得接着聊的一句进入话题队列；来源是这一批真实事件。
                val diary = synchronized(app.memoryLock) { app.memory.diary(java.time.LocalDate.parse(prepared.batch.day)).orEmpty() }
                if (diary.isNotBlank() && prepared.batch.eventIds.isNotEmpty()) {
                    app.topics.enqueue(diary.take(80), prepared.batch.eventIds)
                }
                synchronized(app.memoryLock) { app.memory.pruneArchive(LocalDate.now()) }
                DONE
            }
            // 档案在请求期间被用户改过、或批次状态不对：下一次用新快照重算。
            NightlyCommitOutcome.CONFLICT, NightlyCommitOutcome.MISSING -> if (runAttemptCount < 2) RETRY else DONE
        }
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
