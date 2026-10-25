package com.zongkong.sentinel

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.zongkong.sentinel.watch.HealthStore
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * 哨兵的内部小工具：`:watch` 自己的记录、串行的「恢复请求」、客户端无障碍组件表。
 *
 * 记录为什么要单独一份文件：默认进程（无障碍）与 `:watch` 是两个进程，
 * 共用 `log.txt` 会因为「整文件重写」互相覆盖。所以 `:watch` 写自己的 `watch-log.txt`。
 *
 * 恢复要点（报告 §8 / 规范 §8）：
 *  - **串行**：同一时刻只有一个恢复流程在改无障碍列表，避免总控和哨兵同时改同一配置（T12）；
 *  - 写回前重新读取当前列表（由 [Sys.rebind] 保证），不用旧快照覆盖别的变更；
 *  - 写入成功只记「恢复请求已发出」——要等新实例 + 真 ack 才算恢复（T11）；
 *  - 最多两次，超预算停止重试（由共享内核 `HealthEvaluator` 的恢复预算决定）。
 */
object Arc {

    private const val TAG = "SentinelArc"
    private const val LOG_NAME = "watch-log.txt"
    private const val KEEP = 80
    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** `:watch` 侧记录（独立文件，不与默认进程抢 log.txt）。 */
    @Synchronized
    fun log(c: Context, text: String) {
        try {
            val t = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZONE)
            val line = "%02d-%02d %02d:%02d  %s".format(t.monthValue, t.dayOfMonth, t.hour, t.minute, text)
            val f = File(c.filesDir, LOG_NAME)
            val lines = (if (f.exists()) f.readLines() else emptyList()) + line
            f.writeText(lines.takeLast(KEEP).joinToString("\n"))
            Log.i(TAG, text)
        } catch (_: Throwable) {
        }
    }

    fun recent(c: Context, n: Int): List<String> = try {
        val f = File(c.filesDir, LOG_NAME)
        if (f.exists()) f.readLines().takeLast(n).reversed() else emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** 客户端包名 → 它的无障碍服务组件（有限自动恢复的目标）。 */
    val A11Y_COMPONENTS: Map<String, String> = mapOf(
        HealthStore.ZONGKONG to "com.zongkong.app/com.zongkong.app.guard.GuardService",
        HealthStore.YISHOU to "com.yishou.app/com.yishou.app.gate.GateService",
        HealthStore.PET to "cn.companion.pet/cn.companion.pet.a11y.PerceptionService",
    )

    private val lock = Object()

    /**
     * 发一次「重新挂上无障碍」的恢复请求。串行执行；没有写安全设置权限时返回 false（不冒充修好了）。
     */
    fun requestRebind(c: Context, pkg: String): Boolean {
        val flat = A11Y_COMPONENTS[pkg] ?: return false
        val comp = ComponentName.unflattenFromString(flat) ?: return false
        if (!Sys.canWrite(c)) return false
        synchronized(lock) {
            return try {
                val ok = Sys.rebind(c, comp)
                if (ok) log(c, "$pkg：恢复请求已发出（等新实例 + 真 ack）")
                ok
            } catch (e: Throwable) {
                Log.w(TAG, "恢复请求失败", e)
                false
            }
        }
    }

    /** 客户端也要能停自己的自动锁屏：紧急暂停时逐个通知，各自独立超时。 */
    fun notifyEmergency(c: Context, pkg: String, reason: String): Boolean =
        runCatching { com.zongkong.sentinel.watch.ClientCall.notifyEmergency(c, pkg, reason) }
            .getOrDefault(false)
}
