package com.zongkong.sentinel.watch

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * 哨兵 → 客户端 的一次性调用（显式 authority，不用广播）。
 *
 * 规范 §3.2/§6 要求：
 *  - 只走显式 authority；
 *  - **每个客户端独立超时**：一个不回不能拖住其他客户端（T04/T16）；
 *  - 不为无法取消的阻塞调用无限新建线程。
 *
 * 这里只做「发出去 + 拿回包」，判断一律交给上层与共享内核。
 */
object ClientCall {

    private const val TAG = "SentinelClientCall"

    /** 紧急暂停发出去的结果：true = 客户端回了确认。 */
    fun notifyEmergency(c: Context, pkg: String, reason: String, timeoutMs: Long = 2_000): Boolean = try {
        val args = Bundle().apply {
            putInt("v", HealthStore.PROTOCOL_VERSION)
            putString("reason", reason)
            putString("from", "com.zongkong.sentinel")
        }
        call(c, pkg, "emergencyStop", args, timeoutMs)?.getBoolean("ok", false) == true
    } catch (_: Throwable) {
        false
    }

    /**
     * 取消紧急暂停发出去的结果：true = 客户端回了确认。
     *
     * P0-6c：`WatchService.cancelEmergency` 以前只改自己的策略文件，**不通知客户端**，
     * 客户端那条 STOPPED 就永远留着 —— 用户以为恢复了监管，本地兜底其实一直是停的。
     */
    fun notifyResume(c: Context, pkg: String, reason: String, timeoutMs: Long = 2_000): Boolean = try {
        val args = Bundle().apply {
            putInt("v", HealthStore.PROTOCOL_VERSION)
            putString("reason", reason)
            putString("from", "com.zongkong.sentinel")
        }
        call(c, pkg, "resumeEmergency", args, timeoutMs)?.getBoolean("ok", false) == true
    } catch (_: Throwable) {
        false
    }

    /** 客户端是否可达（有 provider 就试一次 ping）。 */
    fun ping(c: Context, pkg: String, timeoutMs: Long = 1_500): Boolean = try {
        val args = Bundle().apply { putInt("v", HealthStore.PROTOCOL_VERSION) }
        call(c, pkg, "ping", args, timeoutMs)?.getBoolean("ok", false) == true
    } catch (_: Throwable) {
        false
    }

    /** 带超时的显式 provider 调用；超时返回 null（调用方据此记「暂停未确认」，不谎称已停止）。 */
    fun call(c: Context, pkg: String, method: String, args: Bundle, timeoutMs: Long): Bundle? {
        val authority = ClientProbe.AUTHORITIES[pkg] ?: return null
        val lock = java.lang.Object()
        var out: Bundle? = null
        var done = false
        val t = Thread {
            var r: Bundle? = null
            try {
                r = c.contentResolver.call(Uri.parse("content://$authority"), method, null, args)
            } catch (e: Throwable) {
                Log.w(TAG, "$pkg.$method 调不通", e)
            }
            synchronized(lock) {
                out = r
                done = true
                lock.notifyAll()
            }
        }
        t.isDaemon = true
        t.start()
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!done) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try { lock.wait(left) } catch (_: InterruptedException) { break }
            }
        }
        return out
    }
}
