package com.zongkong.sentinel.watch

import com.zongkong.sentinel.health.HealthStatus
import java.io.File
import java.io.FileOutputStream

/**
 * a11y 进程写、`:watch` 读的事件进度（规范 §3.3，报告 §6「事件通道失灵要单独检测」，T03/T13）。
 *
 * 为什么用文件而不是 static：这是同一个 app 的两个进程。规范明令不许用 static 单例跨进程，
 * 也不许拿默认 SharedPreferences 的缓存当跨进程同步。所以走「原子写 + 版本号」的文件。
 *
 * 判断原则（T13）：只有**用户确实切换了可比对窗口**时才能比对。
 * 静止屏幕、息屏、锁屏、暂停、系统暂未调度造成的「没有事件」都不算故障。
 */
class EventProgressFile(dir: File?) {

    /** a11y 进程写入的一条窗口事件。`atMono` 用 SystemClock.elapsedRealtime()，跨进程可比。 */
    data class Event(val seq: Long, val pkg: String, val atMono: Long, val atWall: Long)

    data class State(
        /** 事件序号（a11y 进程内单调递增）。 */
        val seq: Long = 0,
        val atMono: Long = 0,
        val atWall: Long = 0,
        /** 最近若干条**可比对**的窗口切换（已去重、已剔除系统界面）。 */
        val recent: List<Event> = emptyList(),
        /** 写入者的连接实例，用来知道 a11y 是否重建过（重建后不能拿旧进度说事）。 */
        val instanceId: String? = null,
    )

    private val file: File? = dir?.let { File(it, FILE_NAME) }
    private val tmp: File? = dir?.let { File(it, "$FILE_NAME.tmp") }

    /** a11y 侧：来一条窗口事件就记一条。原子写，读的一方永远看不到半截。 */
    @Synchronized
    fun append(state: State, ev: Event, keep: Int = KEEP): Boolean {
        val f = file ?: return false
        val t = tmp ?: return false
        return try {
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val recent = (state.recent.filterNot { it.seq == ev.seq } + ev).takeLast(keep)
            val next = State(ev.seq, ev.atMono, ev.atWall, recent, state.instanceId)
            val text = encode(next)
            FileOutputStream(t).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!t.renameTo(f)) {
                f.writeText(text)
                t.delete()
            }
            true
        } catch (_: Throwable) {
            try { t.delete() } catch (_: Throwable) {}
            false
        }
    }

    @Synchronized
    fun read(): State? {
        val f = file ?: return null
        if (!f.exists()) return null
        return try {
            val root = MiniJson.map(MiniJson.parse(f.readText()))
            State(
                seq = MiniJson.lng(root, "seq"),
                atMono = MiniJson.lng(root, "atMono"),
                atWall = MiniJson.lng(root, "atWall"),
                recent = MiniJson.list(root["recent"]).map {
                    val m = MiniJson.map(it)
                    Event(
                        seq = MiniJson.lng(m, "seq"),
                        pkg = MiniJson.str(m, "pkg"),
                        atMono = MiniJson.lng(m, "atMono"),
                        atWall = MiniJson.lng(m, "atWall"),
                    )
                },
                instanceId = MiniJson.nullableStr(root, "instanceId"),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun encode(s: State): String = MiniJson.encode(
        MiniJson.obj(
            "schemaVersion" to 2,
            "seq" to s.seq,
            "atMono" to s.atMono,
            "atWall" to s.atWall,
            "instanceId" to s.instanceId,
            "recent" to s.recent.map {
                MiniJson.obj("seq" to it.seq, "pkg" to it.pkg, "atMono" to it.atMono, "atWall" to it.atWall)
            },
        ),
    )

    companion object {
        const val FILE_NAME = "events.json"
        const val KEEP = 16
    }
}

/**
 * 事件通道判定（T03 / T13）。纯函数，单测可覆盖，不猜、不说大话。
 *
 * 输入都是**可观测事实**：
 *  - 哨兵自己最后一次窗口事件是什么时候（能证明「系统还在投递事件」）；
 *  - 客户端自报的最后事件序号与时刻（来自真实 dispatcherAck）；
 *  - 屏幕/锁屏/暂停等上下文（这些情况下没事件是正常的）。
 *
 * 输出只有三种：已验证 / 待验证 / 异常。证据不足就说「待验证」。
 */
object EventChannel {

    enum class Verdict(val text: String) {
        VERIFIED(HealthStore.EVENT_VERIFIED),
        PENDING(HealthStore.EVENT_PENDING),
        ABNORMAL(HealthStore.EVENT_ABNORMAL),
    }

    data class Context(
        /** 哨兵自己在 `compareSawEventAfterMono` 之后是否收到过窗口事件。 */
        val sentinelSawEventAfter: Boolean,
        /** 客户端最后事件时刻（单调，客户端钟；同一次开机可比）。 */
        val clientEventAtMono: Long,
        /** 用于比对的参照事件时刻（哨兵看到的那次窗口切换，单调）。 */
        val compareRefMono: Long = 0,
        /** 息屏 / 锁屏 / 暂停 / 休眠刚醒：这些时候没事件是正常的。 */
        val suppressed: Boolean = false,
        /** 客户端的执行线程是否健康（dispatcherAck）。线程都不通时先报线程问题，别把两件事混在一起。 */
        val dispatcherHealthy: Boolean = true,
        /** 客户端是否已接入。 */
        val registered: Boolean = true,
        val permissionEnabled: Boolean = true,
        /** 距离上次成功比对已经过去多久。 */
        val sinceCompareMs: Long = 0,
        /** 允许的观察窗口：参照事件发生后，客户端有这么久来体现进度。 */
        val graceMs: Long = 20_000,
    )

    fun judge(ctx: Context): Pair<Verdict, String> {
        if (!ctx.registered) return Verdict.PENDING to "未接入，无事件可对"
        if (!ctx.permissionEnabled) return Verdict.PENDING to "无障碍未开启，事件不投递"
        if (ctx.suppressed) return Verdict.PENDING to "息屏/锁屏/暂停/刚唤醒，缺事件属正常"
        if (!ctx.dispatcherHealthy) return Verdict.PENDING to "执行线程本身未确认，先不解判断事件通道"
        if (ctx.compareRefMono <= 0) return Verdict.PENDING to "还没有可对照的窗口切换"
        if (!ctx.sentinelSawEventAfter) return Verdict.PENDING to "哨兵自己也没收到事件，无法区分是谁的问题"
        if (ctx.clientEventAtMono >= ctx.compareRefMono) {
            return Verdict.VERIFIED to "最近一次窗口切换已体现在客户端事件进度里"
        }
        if (ctx.sinceCompareMs < ctx.graceMs) {
            return Verdict.PENDING to "参照事件刚发生，还在 ${ctx.graceMs / 1000}s 观察窗口内"
        }
        return Verdict.ABNORMAL to
            "哨兵收到窗口事件，但客户端事件进度 ${ctx.sinceCompareMs / 1000}s 没有前进（执行线程仍在回话）"
    }

    /**
     * **事件通道判定的唯一入口**（F-01 修复后的收敛点）。
     *
     * 为什么要有这一层：F-01 的教训是「判据本身对，不代表接线上是对的」——
     * 之前 `WatchService` 和 `HealthScheduler` 各算一遍，调度器那份 `compareRefMono` 硬编码 0、
     * 又拿上次结论当输入，把好结论覆盖成「待验证」，T03 在真实链路上永远出不了「异常」。
     *
     * 现在全仓只有这一处把「文件事实」翻译成判定输入，`WatchService.refreshSystemFacts()`
     * 与单测**走同一条路径**，所以接线错误会被测试直接抓到。
     *
     * 输入全部来自 events.json 的真实事实，没有任何自我引用：
     *  - `sentinelSawEventAfter` ← 文件里确有事件（`seq > 0 && atMono > 0`），不是「上次的结论」；
     *  - `compareRefMono` ← 文件里最近那条窗口事件的单调时刻；
     *  - `sinceCompareMs` ← 现在 - 该时刻。
     */
    fun evaluate(
        progress: EventProgressFile.State?,
        clientEventAtMono: Long,
        nowMono: Long,
        registered: Boolean,
        permissionEnabled: Boolean,
        dispatcherHealthy: Boolean,
        suppressed: Boolean,
        graceMs: Long = DEFAULT_GRACE_MS,
    ): Pair<Verdict, String> {
        val hasOwnEvent = progress != null && progress.seq > 0 && progress.atMono > 0
        val refMono = if (hasOwnEvent) progress!!.atMono else 0L
        return judge(
            Context(
                sentinelSawEventAfter = hasOwnEvent,
                clientEventAtMono = clientEventAtMono,
                compareRefMono = refMono,
                suppressed = suppressed,
                dispatcherHealthy = dispatcherHealthy,
                registered = registered,
                permissionEnabled = permissionEnabled,
                sinceCompareMs = if (refMono > 0) (nowMono - refMono).coerceAtLeast(0) else 0,
                graceMs = graceMs,
            ),
        )
    }

    /** 观察窗口默认值：参照事件发生后，客户端有这么久来体现进度。 */
    const val DEFAULT_GRACE_MS = 20_000L

    /** 首页 / 统计页用的四态判断（报告 §10）。 */
    data class Row(
        val pkg: String,
        val label: String,
        val status: HealthStatus,
        val statusText: String,
        val installed: Boolean,
        val registered: Boolean,
        val permissionEnabled: Boolean,
        val lastAckAgoMs: Long?,
        val eventChannel: String,
        val windowText: String,
        val failureText: String,
        val lockText: String,
    )
}

/**
 * 事件通道的**唯一接线点**（F-01 修复）。
 *
 * `WatchService.refreshSystemFacts()` 每轮调它，单测也调它 —— 这样「读 events.json → 判定 → 落盘」
 * 走的是同一条代码路径。F-01 之所以漏测，就是因为旧单测直接调 `EventChannel.judge()`，
 * 绕开了真实接线：判据对、接线错，测试全绿而功能全坏。
 *
 * 纯 `java.io` + 纯逻辑，无 Android 依赖，可在 JVM 里用临时目录跑真文件。
 */
object EventChannelWiring {

    /**
     * 对三个业务客户端各判一次并落盘。
     *
     * @param progress 已读出的 events.json 内容（null = a11y 进程还没写过，本轮不判）
     * @return 包名 → 结论（含文案），便于调用方/测试直接断言
     */
    fun refresh(
        store: HealthStore,
        progress: EventProgressFile.State?,
        nowWall: Long,
        nowMono: Long,
        suppressed: Boolean,
        graceMs: Long = EventChannel.DEFAULT_GRACE_MS,
    ): Map<String, Pair<EventChannel.Verdict, String>> {
        val out = LinkedHashMap<String, Pair<EventChannel.Verdict, String>>()
        for (pkg in HealthStore.CLIENTS) {
            val rec = store.get(pkg, nowWall)
            if (!rec.registered) continue
            val result = EventChannel.evaluate(
                progress = progress,
                clientEventAtMono = rec.eventAtMono,
                nowMono = nowMono,
                registered = rec.registered,
                permissionEnabled = rec.permissionEnabled,
                // 执行线程没确认时先报线程问题，别把两件事混在一起（报告 §6）。
                dispatcherHealthy = rec.lastAckAtMono > 0 && rec.lastAckAtMono >= rec.recoveringSinceMono,
                suppressed = suppressed,
                graceMs = graceMs,
            )
            store.setEventChannel(pkg, result.first.text, nowWall)
            out[pkg] = result
        }
        return out
    }
}
