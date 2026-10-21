package com.moling.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.moling.core.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 后台心跳（M9）：让墨灵离开 App 也在。
 *
 * 前台服务 + 一条低优先级常驻通知；亮屏时每分钟 tick 一次，灭屏改成每 5 分钟一次（省电）。
 * 这一批只做心跳；M10 起 tick 会带上前台类别和总控健康，M12 起在心跳里做早晚自动。
 * 任何一次 tick 出错都只记日志，服务不能挂。
 */
class HeartService : Service() {
    companion object {
        private const val TAG = "HeartService"
        const val ACTION_STOP = "com.moling.app.heart.STOP"

        @Volatile
        var running = false
            private set

        /** 起服务；没通知权限、被系统限制都只记日志，不影响 App。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, HeartService::class.java))
            }.onFailure { Log.w(TAG, "后台心跳起不来", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, HeartService::class.java)) }
        }

        /** 服务在不在跑（MainActivity 用它决定要不要自己再 tick）。 */
        fun isRunning(): Boolean = running
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Notify.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 5 秒内必须进前台；通知内容用当前正在流的水道。
        goForeground()
        if (loop?.isActive != true) loop = scope.launch { heartbeat() }
        return START_STICKY
    }

    private fun goForeground() {
        val n = Notify.heart(this, heartText())
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notify.ID_HEART, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notify.ID_HEART, n)
            }
        }.onFailure { Log.w(TAG, "进前台失败", it) }
    }

    private suspend fun heartbeat() {
        while (true) {
            val now = System.currentTimeMillis()
            runCatching { BrainHost.tickNow(now) }.onFailure { Log.w(TAG, "心跳失败", it) }
            Notify.updateHeart(this, heartText())
            delay(if (interactive()) 60_000L else 300_000L)
        }
    }

    /** 亮屏着就勤一点，灭屏省电。 */
    private fun interactive(): Boolean = runCatching {
        getSystemService(PowerManager::class.java)?.isInteractive ?: true
    }.getOrDefault(true)

    /** 常驻通知正文：今天水道里此刻该在的那条；没有就写今天还没排。 */
    private fun heartText(): String {
        val plan = BrainHost.state.value.flow ?: return "今天还没排水道"
        val minute = Flow.minuteOf(System.currentTimeMillis())
        return Flow.current(plan, minute)?.title?.ifBlank { "今天还没排水道" } ?: "今天还没排水道"
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }
}
