package com.zongkong.app.system

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zongkong.app.guard.Diag

/**
 * 崩溃护盾（0.11）：总控的无障碍服务和整个 App 在同一个进程里，App 里任何一处崩溃都会连带把它杀掉，
 * 系统随即把服务标成“出现故障”，要你手动关开。这里尽量让进程别死：
 *   - 后台线程的异常：记下来，吞掉（那个线程结束，进程还活着）
 *   - 主线程的异常：记下来，关掉出错的那个页面，主线程接着跑；30 秒里连着崩 5 次才放手，免得卡在坏状态里
 * 每一次都写进“眼睛日志”（诊断），能看到是哪里崩的。
 */
object CrashShield {
    private const val TAG = "CrashShield"
    private val recent = ArrayDeque<Long>()
    private val activities = mutableListOf<Activity>()

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            record(app, thread.name, e)
            // 主线程会走下面的循环接住；能走到这里的主线程异常是循环放手以后的，交还系统
            if (thread === Looper.getMainLooper().thread) previous?.uncaughtException(thread, e)
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { synchronized(activities) { activities += a } }
            override fun onActivityDestroyed(a: Activity) { synchronized(activities) { activities -= a } }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        })
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                } catch (e: Throwable) {
                    record(app, "main", e)
                    val now = System.currentTimeMillis()
                    recent.addLast(now)
                    while (recent.isNotEmpty() && now - recent.first() > 30_000) recent.removeFirst()
                    if (recent.size >= 5) throw e
                    // 出错的多半是最上面那个页面：关掉它，回到上一页或桌面
                    synchronized(activities) { activities.lastOrNull() }?.let { runCatching { it.finish() } }
                }
            }
        }
    }

    private fun record(app: Application, where: String, e: Throwable) {
        Log.e(TAG, "接住了一个崩溃（$where）", e)
        runCatching {
            val at = e.stackTrace.firstOrNull { it.className.startsWith("com.zongkong") }?.let { "${it.fileName}:${it.lineNumber}" } ?: ""
            Diag.log(app, "crash", "$where 线程：${e.javaClass.simpleName}：${e.message.orEmpty().take(120)} $at")
        }
    }
}
