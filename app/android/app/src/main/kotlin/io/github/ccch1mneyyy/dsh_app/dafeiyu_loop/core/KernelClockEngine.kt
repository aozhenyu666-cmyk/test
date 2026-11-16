package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core

import java.util.Calendar
import java.util.PriorityQueue
import java.util.TimeZone

/**
 * 大肥鱼时钟内核（纯 JVM 零 Android 依赖）：
 * 支持高精度单次闹钟（ONE_SHOT）、漂移补偿固定频率循环计时（FIXED_RATE）、固定间隔（FIXED_DELAY）、
 * 多阶段循环计时器（CYCLE_PHASE：如专注 25m -> 休息 5m x N 轮，支持暂停/继续/跳阶段）、
 * 以及 5/6 段 Cron 循环闹钟（基于 [BeijingClock.ZONE] Asia/Shanghai）。
 *
 * 设计要点：
 * 1. 使用小顶堆优先队列按 `nextFireWall` 调度，O(1) 取最近唤醒点、O(log N) 重排；
 * 2. `FIXED_RATE` 基于 `anchorWall + k * intervalMs` 计算下一拍，彻底消除高频轮询/计时累计漂移；
 * 3. `intervalMs <= 15_000L` 或 `CYCLE_PHASE` 处于运行中且剩余 `<= 30_000L` 时提供高频率 `highFreqIntervalMs`，
 *    供原生侧用 `Handler + elapsedRealtime` 亚秒级盯防，后台/息屏交由 `AlarmManager` 自续重排唤醒；
 * 4. 持久化走 [SnapshotStore]，重启与改表后按失火策略（SKIP_TO_NEXT / FIRE_ONCE_CATCH_UP / COALESCE）恢复。
 */
enum class ClockTaskKind {
    ONE_SHOT,
    FIXED_RATE,
    FIXED_DELAY,
    CRON,
    CYCLE_PHASE,
}

enum class MisfirePolicy {
    SKIP_TO_NEXT,
    FIRE_ONCE_CATCH_UP,
    COALESCE,
}

data class ClockPhase(
    val name: String,
    val durationMs: Long,
    val strictLock: Boolean = false,
    val allowApps: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "durationMs" to durationMs,
        "strictLock" to strictLock,
        "allowApps" to allowApps,
    )

    companion object {
        fun fromMap(m: Map<*, *>): ClockPhase? {
            val name = (m["name"] as? String)?.trim().orEmpty().ifEmpty { "阶段" }
            val durationMs = ((m["durationMs"] as? Number)?.toLong() ?: 0L).coerceIn(1_000L, 12 * 3_600_000L)
            val strictLock = m["strictLock"] == true
            val allowApps = (m["allowApps"] as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() }.orEmpty()
            return ClockPhase(name = name, durationMs = durationMs, strictLock = strictLock, allowApps = allowApps)
        }
    }
}

data class ClockTask(
    val id: String,
    val label: String,
    val kind: ClockTaskKind,
    val enabled: Boolean = true,
    val paused: Boolean = false,
    val urgent: Boolean = false,
    val source: String = "user",
    val action: String = "notify",
    val payload: Map<String, Any?> = emptyMap(),
    val misfirePolicy: MisfirePolicy = MisfirePolicy.SKIP_TO_NEXT,
    val anchorWall: Long = 0L,
    val nextFireWall: Long = 0L,
    val nextFireElapsed: Long = 0L,
    val intervalMs: Long = 0L,
    val cronExpr: String = "",
    val phases: List<ClockPhase> = emptyList(),
    val phaseIndex: Int = 0,
    val cycleIndex: Int = 1,
    /** 0 = 无限循环；>0 表示总循环次数或总触发次数上限。 */
    val maxRuns: Int = 0,
    val runCount: Int = 0,
    val pausedRemainingMs: Long = 0L,
    val lastFiredWall: Long = 0L,
    val createdAtWall: Long = 0L,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "label" to label,
        "kind" to kind.name,
        "enabled" to enabled,
        "paused" to paused,
        "urgent" to urgent,
        "source" to source,
        "action" to action,
        "payload" to payload,
        "misfirePolicy" to misfirePolicy.name,
        "anchorWall" to anchorWall,
        "nextFireWall" to nextFireWall,
        "nextFireElapsed" to nextFireElapsed,
        "intervalMs" to intervalMs,
        "cronExpr" to cronExpr,
        "phases" to phases.map { it.toMap() },
        "phaseIndex" to phaseIndex,
        "cycleIndex" to cycleIndex,
        "maxRuns" to maxRuns,
        "runCount" to runCount,
        "pausedRemainingMs" to pausedRemainingMs,
        "lastFiredWall" to lastFiredWall,
        "createdAtWall" to createdAtWall,
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<*, *>?): ClockTask? {
            if (m == null) return null
            val id = (m["id"] as? String)?.trim().orEmpty()
            if (id.isEmpty()) return null
            val kind = runCatching { ClockTaskKind.valueOf((m["kind"] as? String).orEmpty()) }.getOrNull() ?: return null
            val misfire = runCatching { MisfirePolicy.valueOf((m["misfirePolicy"] as? String).orEmpty()) }.getOrDefault(MisfirePolicy.SKIP_TO_NEXT)
            val rawPayload = m["payload"] as? Map<*, *>
            val payload = LinkedHashMap<String, Any?>()
            rawPayload?.forEach { (k, v) -> if (k != null) payload[k.toString()] = v }
            val phases = (m["phases"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(ClockPhase::fromMap) }.orEmpty()
            return ClockTask(
                id = id,
                label = (m["label"] as? String)?.trim().orEmpty().ifEmpty { id },
                kind = kind,
                enabled = m["enabled"] != false,
                paused = m["paused"] == true,
                urgent = m["urgent"] == true,
                source = (m["source"] as? String).orEmpty().ifEmpty { "user" },
                action = (m["action"] as? String).orEmpty().ifEmpty { "notify" },
                payload = payload,
                misfirePolicy = misfire,
                anchorWall = (m["anchorWall"] as? Number)?.toLong() ?: 0L,
                nextFireWall = (m["nextFireWall"] as? Number)?.toLong() ?: 0L,
                nextFireElapsed = (m["nextFireElapsed"] as? Number)?.toLong() ?: 0L,
                intervalMs = (m["intervalMs"] as? Number)?.toLong() ?: 0L,
                cronExpr = (m["cronExpr"] as? String).orEmpty(),
                phases = phases,
                phaseIndex = ((m["phaseIndex"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                cycleIndex = ((m["cycleIndex"] as? Number)?.toInt() ?: 1).coerceAtLeast(1),
                maxRuns = ((m["maxRuns"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                runCount = ((m["runCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                pausedRemainingMs = ((m["pausedRemainingMs"] as? Number)?.toLong() ?: 0L).coerceAtLeast(0L),
                lastFiredWall = (m["lastFiredWall"] as? Number)?.toLong() ?: 0L,
                createdAtWall = (m["createdAtWall"] as? Number)?.toLong() ?: 0L,
            )
        }
    }
}

data class ClockFireEvent(
    val taskId: String,
    val label: String,
    val kind: ClockTaskKind,
    val action: String,
    val scheduledAtWall: Long,
    val firedAtWall: Long,
    val runCount: Int,
    val cycleIndex: Int,
    val phaseIndex: Int,
    val phaseName: String?,
    val completedPhase: ClockPhase? = null,
    val nextPhase: ClockPhase? = null,
    val finished: Boolean = false,
    val payload: Map<String, Any?> = emptyMap(),
)

data class ClockTickResult(
    val fires: List<ClockFireEvent>,
    val nextWakeWall: Long?,
    val nextUrgentWakeWall: Long?,
    /** 当存在高频任务或临近阶段切换时，建议主线程高频巡检步长（毫秒），null 表示无需高频轮询。 */
    val highFreqIntervalMs: Long?,
)

/**
 * 标准 5/6 段 Cron 表达式解析器（Asia/Shanghai）。
 * 支持：
 * - 5 段：`分 时 日 月 周`（秒固定 0）
 * - 6 段：`秒 分 时 日 月 周`
 * - 语法：`*`、`*/N`、`A-B`、`A-B/N`、`A,B,C`，周日支持 `0` 与 `7`。
 */
class BeijingCronExpression private constructor(
    private val seconds: Set<Int>,
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val domWildcard: Boolean,
    private val dowWildcard: Boolean,
) {
    fun nextAfter(afterWall: Long, zone: TimeZone = BeijingClock.ZONE): Long? {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = afterWall - (afterWall % 1_000L) + 1_000L
            set(Calendar.MILLISECOND, 0)
        }
        // 最多向前搜索 4 年（足够跨越闰年与稀有日期组合），避免坏表达式死循环。
        val limitCal = Calendar.getInstance(zone).apply {
            timeInMillis = cal.timeInMillis
            add(Calendar.YEAR, 4)
        }
        val limitMs = limitCal.timeInMillis
        var guard = 0
        while (cal.timeInMillis <= limitMs && guard++ < 250_000) {
            val mon = cal.get(Calendar.MONTH) + 1
            if (mon !in months) {
                cal.add(Calendar.MONTH, 1)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                continue
            }
            val dom = cal.get(Calendar.DAY_OF_MONTH)
            val dow = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY) // 0=Sun..6=Sat
            val domMatch = dom in daysOfMonth
            val dowMatch = dow in daysOfWeek
            val dayOk = when {
                domWildcard && dowWildcard -> true
                domWildcard -> dowMatch
                dowWildcard -> domMatch
                else -> domMatch || dowMatch
            }
            if (!dayOk) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                continue
            }
            val hr = cal.get(Calendar.HOUR_OF_DAY)
            if (hr !in hours) {
                cal.add(Calendar.HOUR_OF_DAY, 1)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                continue
            }
            val min = cal.get(Calendar.MINUTE)
            if (min !in minutes) {
                cal.add(Calendar.MINUTE, 1)
                cal.set(Calendar.SECOND, 0)
                continue
            }
            val sec = cal.get(Calendar.SECOND)
            if (sec !in seconds) {
                val nextSec = seconds.firstOrNull { it > sec }
                if (nextSec != null) {
                    cal.set(Calendar.SECOND, nextSec)
                } else {
                    cal.add(Calendar.MINUTE, 1)
                    cal.set(Calendar.SECOND, seconds.min())
                }
                continue
            }
            return cal.timeInMillis
        }
        return null
    }

    companion object {
        fun parse(expr: String): BeijingCronExpression? {
            val parts = expr.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size != 5 && parts.size != 6) return null
            val offset = if (parts.size == 6) 1 else 0
            val secSet = if (offset == 1) parseField(parts[0], 0, 59)?.values else setOf(0)
            val minSet = parseField(parts[offset], 0, 59)?.values
            val hrSet = parseField(parts[offset + 1], 0, 23)?.values
            val domParsed = parseField(parts[offset + 2], 1, 31)
            val monSet = parseField(parts[offset + 3], 1, 12)?.values
            val dowParsed = parseField(parts[offset + 4], 0, 7) { v -> if (v == 7) 0 else v }
            if (secSet.isNullOrEmpty() || minSet.isNullOrEmpty() || hrSet.isNullOrEmpty() ||
                domParsed == null || monSet.isNullOrEmpty() || dowParsed == null
            ) return null
            return BeijingCronExpression(
                seconds = secSet,
                minutes = minSet,
                hours = hrSet,
                daysOfMonth = domParsed.values,
                months = monSet,
                daysOfWeek = dowParsed.values,
                domWildcard = domParsed.isWildcard,
                dowWildcard = dowParsed.isWildcard,
            )
        }

        private data class ParsedField(val values: Set<Int>, val isWildcard: Boolean)

        private fun parseField(
            token: String,
            min: Int,
            max: Int,
            mapVal: (Int) -> Int = { it },
        ): ParsedField? {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) return null
            val isWildcard = trimmed == "*" || trimmed == "?"
            if (isWildcard) {
                return ParsedField((min..max).map(mapVal).toSet(), true)
            }
            val out = LinkedHashSet<Int>()
            for (part in trimmed.split(',')) {
                val p = part.trim()
                if (p.isEmpty()) return null
                val slashIdx = p.indexOf('/')
                val rangeStr = if (slashIdx >= 0) p.substring(0, slashIdx) else p
                val step = if (slashIdx >= 0) p.substring(slashIdx + 1).toIntOrNull()?.takeIf { it > 0 } ?: return null else 1
                val (start, end) = when {
                    rangeStr == "*" || rangeStr == "?" -> min to max
                    '-' in rangeStr -> {
                        val b = rangeStr.split('-')
                        if (b.size != 2) return null
                        val s = b[0].toIntOrNull() ?: return null
                        val e = b[1].toIntOrNull() ?: return null
                        if (s > e || s < min || e > max) return null
                        s to e
                    }
                    else -> {
                        val single = rangeStr.toIntOrNull() ?: return null
                        if (single < min || single > max) return null
                        val upper = if (slashIdx >= 0) max else single
                        single to upper
                    }
                }
                var v = start
                while (v <= end) {
                    out += mapVal(v)
                    v += step
                }
            }
            return if (out.isEmpty()) null else ParsedField(out, false)
        }
    }
}

class KernelClockEngine(
    private val store: SnapshotStore? = null,
    private val zone: TimeZone = BeijingClock.ZONE,
) {
    private val lock = Any()
    private val tasks = LinkedHashMap<String, ClockTask>()
    private val heap = PriorityQueue<ClockTask>(compareBy<ClockTask> { it.nextFireWall }.thenBy { it.id })
    private val cronCache = HashMap<String, BeijingCronExpression>()
    private var bootId: Int = 0
    private var lastTickWall: Long = 0L

    fun recover(now: Clocks): List<ClockTask> = synchronized(lock) {
        bootId = now.boot
        tasks.clear()
        val read = store?.read()
        if (read is SnapshotStore.Read.Loaded) {
            lastTickWall = M.long(read.map, "lastTickWall")
            val rawList = read.map["tasks"] as? List<*>
            rawList?.forEach { raw ->
                val t = ClockTask.fromMap(raw as? Map<*, *>) ?: return@forEach
                tasks[t.id] = reconcileTaskAfterBoot(t, now)
            }
        }
        rebuildHeap()
        persistLocked()
        tasks.values.toList()
    }

    fun upsert(task: ClockTask, now: Clocks): ClockTask = synchronized(lock) {
        val normalized = initializeTask(task, now)
        tasks[normalized.id] = normalized
        rebuildHeap()
        persistLocked()
        normalized
    }

    /** 批量从插件 /plan `clockTasks` 增量同步（保留本机已有更高频或未结束状态）。 */
    fun syncFromPlugin(remoteTasks: List<ClockTask>, now: Clocks): Boolean = synchronized(lock) {
        var changed = false
        val remoteIds = remoteTasks.map { it.id }.toSet()
        // 只移除同源且已被远端删除的插件任务
        val toRemove = tasks.values.filter { it.source == "plugin" && it.id !in remoteIds }.map { it.id }
        for (id in toRemove) {
            tasks.remove(id)
            changed = true
        }
        for (rt in remoteTasks) {
            val cur = tasks[rt.id]
            if (cur == null) {
                tasks[rt.id] = initializeTask(rt.copy(source = "plugin"), now)
                changed = true
            } else if (cur.paused != rt.paused || cur.enabled != rt.enabled || cur.cronExpr != rt.cronExpr || cur.intervalMs != rt.intervalMs) {
                val merged = initializeTask(
                    rt.copy(
                        source = "plugin",
                        runCount = maxOf(cur.runCount, rt.runCount),
                        lastFiredWall = maxOf(cur.lastFiredWall, rt.lastFiredWall),
                    ),
                    now,
                )
                tasks[rt.id] = merged
                changed = true
            }
        }
        if (changed) {
            rebuildHeap()
            persistLocked()
        }
        changed
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val removed = tasks.remove(id) != null
        if (removed) {
            rebuildHeap()
            persistLocked()
        }
        removed
    }

    fun pause(id: String, now: Clocks): ClockTask? = synchronized(lock) {
        val t = tasks[id] ?: return@synchronized null
        if (t.paused) return@synchronized t
        val rem = (dueWall(t, now) - now.wall).coerceAtLeast(1_000L)
        val updated = t.copy(paused = true, pausedRemainingMs = rem)
        tasks[id] = updated
        rebuildHeap()
        persistLocked()
        updated
    }

    fun resume(id: String, now: Clocks): ClockTask? = synchronized(lock) {
        val t = tasks[id] ?: return@synchronized null
        if (!t.paused) return@synchronized t
        val nextWall = if (t.kind == ClockTaskKind.CRON) {
            parseCron(t.cronExpr)?.nextAfter(now.wall, zone) ?: (now.wall + 60_000L)
        } else {
            val rem = t.pausedRemainingMs.coerceAtLeast(1_000L)
            now.wall + rem
        }
        val updated = t.copy(
            paused = false,
            pausedRemainingMs = 0L,
            nextFireWall = nextWall,
            nextFireElapsed = now.elapsed + (nextWall - now.wall).coerceAtLeast(0L),
        )
        tasks[id] = updated
        rebuildHeap()
        persistLocked()
        updated
    }

    fun skipCyclePhase(id: String, now: Clocks): ClockFireEvent? = synchronized(lock) {
        val t = tasks[id] ?: return@synchronized null
        if (t.kind != ClockTaskKind.CYCLE_PHASE || t.phases.isEmpty()) return@synchronized null
        val curPhase = t.phases[t.phaseIndex.coerceIn(0, t.phases.lastIndex)]
        val next = advanceCycle(t, curPhase, now, now.wall)
        tasks[id] = next.updated
        rebuildHeap()
        persistLocked()
        next.event
    }

    fun list(): List<ClockTask> = synchronized(lock) {
        tasks.values.sortedBy { if (it.enabled && !it.paused) it.nextFireWall else Long.MAX_VALUE }
    }

    fun get(id: String): ClockTask? = synchronized(lock) { tasks[id] }

    /**
     * 核心滴答：在 [now] 时刻弹出所有到期任务，按策略精确推进下一拍并更新小顶堆。
     */
    fun tick(now: Clocks): ClockTickResult = synchronized(lock) {
        if (lastTickWall > 0L && now.wall + 30_000L < lastTickWall) {
            onClockJumpLocked(now)
        }
        lastTickWall = now.wall
        val fires = ArrayList<ClockFireEvent>()
        var changed = false
        var safety = 0

        while (heap.isNotEmpty() && safety++ < MAX_FIRES_PER_TICK) {
            val head = heap.peek() ?: break
            if (!head.enabled || head.paused) {
                heap.poll()
                continue
            }
            val due = dueWall(head, now)
            if (due > now.wall) break
            heap.poll()

            val current = tasks[head.id] ?: continue
            if (!current.enabled || current.paused) continue

            val fire = processDueTask(current, due, now)
            fires += fire.events
            tasks[current.id] = fire.updated
            changed = true
            if (fire.updated.enabled && !fire.updated.paused && fire.updated.nextFireWall > 0L) {
                heap.add(fire.updated)
            }
        }

        if (changed) persistLocked()

        var nextWake: Long? = null
        var nextUrgent: Long? = null
        var minHighFreq: Long? = null

        for (t in tasks.values) {
            if (!t.enabled || t.paused || t.nextFireWall <= 0L) continue
            val d = dueWall(t, now)
            if (nextWake == null || d < nextWake) nextWake = d
            if (t.urgent && (nextUrgent == null || d < nextUrgent)) nextUrgent = d
            val rem = d - now.wall
            if ((t.kind == ClockTaskKind.FIXED_RATE || t.kind == ClockTaskKind.FIXED_DELAY) && t.intervalMs in 1_000L..15_000L) {
                val hint = (t.intervalMs / 2L).coerceIn(500L, 2_000L)
                if (minHighFreq == null || hint < minHighFreq) minHighFreq = hint
            } else if (rem in 0L..30_000L) {
                if (minHighFreq == null || 1_000L < minHighFreq) minHighFreq = 1_000L
            }
        }

        ClockTickResult(
            fires = fires,
            nextWakeWall = nextWake,
            nextUrgentWakeWall = nextUrgent,
            highFreqIntervalMs = minHighFreq,
        )
    }

    fun onClockJump(now: Clocks) = synchronized(lock) {
        onClockJumpLocked(now)
        rebuildHeap()
        persistLocked()
    }

    private fun onClockJumpLocked(now: Clocks) {
        for ((id, t) in tasks) {
            if (!t.enabled || t.paused) continue
            tasks[id] = when (t.kind) {
                ClockTaskKind.CRON -> {
                    val next = parseCron(t.cronExpr)?.nextAfter(now.wall, zone) ?: (now.wall + 60_000L)
                    t.copy(nextFireWall = next, nextFireElapsed = now.elapsed + (next - now.wall).coerceAtLeast(0L))
                }
                else -> {
                    val rem = (t.nextFireElapsed - now.elapsed).coerceAtLeast(1_000L)
                    if (t.nextFireElapsed > 0L && now.boot == bootId) {
                        t.copy(nextFireWall = now.wall + rem)
                    } else {
                        t
                    }
                }
            }
        }
    }

    private fun dueWall(t: ClockTask, now: Clocks): Long {
        if (t.kind != ClockTaskKind.CRON && t.nextFireElapsed > 0L && now.boot == bootId && t.intervalMs in 1L..86_400_000L) {
            return now.wall + (t.nextFireElapsed - now.elapsed)
        }
        return t.nextFireWall
    }

    private fun initializeTask(t: ClockTask, now: Clocks): ClockTask {
        val anchor = if (t.anchorWall > 0L) t.anchorWall else now.wall
        val created = if (t.createdAtWall > 0L) t.createdAtWall else now.wall
        return when (t.kind) {
            ClockTaskKind.ONE_SHOT -> {
                val fw = if (t.nextFireWall > 0L) t.nextFireWall else now.wall + t.intervalMs.coerceAtLeast(1_000L)
                t.copy(
                    anchorWall = anchor,
                    createdAtWall = created,
                    nextFireWall = fw,
                    nextFireElapsed = now.elapsed + (fw - now.wall).coerceAtLeast(0L),
                )
            }
            ClockTaskKind.FIXED_RATE, ClockTaskKind.FIXED_DELAY -> {
                val interval = t.intervalMs.coerceIn(1_000L, 30 * 86_400_000L)
                val fw = if (t.nextFireWall > now.wall) t.nextFireWall else now.wall + interval
                t.copy(
                    anchorWall = anchor,
                    createdAtWall = created,
                    intervalMs = interval,
                    nextFireWall = fw,
                    nextFireElapsed = now.elapsed + (fw - now.wall).coerceAtLeast(0L),
                )
            }
            ClockTaskKind.CRON -> {
                val cron = parseCron(t.cronExpr)
                val fw = if (t.nextFireWall > now.wall) t.nextFireWall else (cron?.nextAfter(now.wall, zone) ?: 0L)
                t.copy(
                    anchorWall = anchor,
                    createdAtWall = created,
                    enabled = t.enabled && fw > 0L,
                    nextFireWall = fw,
                    nextFireElapsed = 0L,
                )
            }
            ClockTaskKind.CYCLE_PHASE -> {
                if (t.phases.isEmpty()) return t.copy(enabled = false)
                val idx = t.phaseIndex.coerceIn(0, t.phases.lastIndex)
                val dur = t.phases[idx].durationMs.coerceAtLeast(1_000L)
                val fw = if (t.nextFireWall > now.wall) t.nextFireWall else now.wall + dur
                t.copy(
                    anchorWall = anchor,
                    createdAtWall = created,
                    phaseIndex = idx,
                    nextFireWall = fw,
                    nextFireElapsed = now.elapsed + (fw - now.wall).coerceAtLeast(0L),
                )
            }
        }
    }

    private fun reconcileTaskAfterBoot(t: ClockTask, now: Clocks): ClockTask {
        if (!t.enabled || t.paused) return t
        val nextElapsed = (t.nextFireWall - now.wall).coerceAtLeast(0L) + now.elapsed
        return t.copy(nextFireElapsed = if (t.kind == ClockTaskKind.CRON) 0L else nextElapsed)
    }

    private data class ProcessResult(val updated: ClockTask, val events: List<ClockFireEvent>)

    private fun processDueTask(t: ClockTask, dueAtWall: Long, now: Clocks): ProcessResult {
        val nextRunCount = t.runCount + 1
        return when (t.kind) {
            ClockTaskKind.ONE_SHOT -> {
                val shouldSkip = (now.wall - dueAtWall > STALE_MS) && t.misfirePolicy == MisfirePolicy.SKIP_TO_NEXT
                val updated = t.copy(enabled = false, runCount = nextRunCount, lastFiredWall = now.wall, nextFireWall = 0L, nextFireElapsed = 0L)
                if (shouldSkip) ProcessResult(updated, emptyList())
                else ProcessResult(updated, listOf(fireEventOf(t, dueAtWall, now.wall, nextRunCount, finished = true)))
            }
            ClockTaskKind.FIXED_RATE -> {
                val interval = t.intervalMs.coerceAtLeast(1_000L)
                val catchUp = t.misfirePolicy == MisfirePolicy.FIRE_ONCE_CATCH_UP
                val lag = now.wall - dueAtWall
                // 漂移补偿：严格对齐 anchor + k * interval
                val anchor = if (t.anchorWall > 0L) t.anchorWall else dueAtWall
                val steps = ((now.wall - anchor) / interval).coerceAtLeast(0L) + 1L
                val nextWall = anchor + steps * interval
                val hitMax = t.maxRuns > 0 && nextRunCount >= t.maxRuns
                val shouldFire = !(lag > interval * 2L && !catchUp && t.misfirePolicy == MisfirePolicy.SKIP_TO_NEXT)
                val updated = t.copy(
                    enabled = !hitMax,
                    runCount = nextRunCount,
                    lastFiredWall = now.wall,
                    nextFireWall = if (hitMax) 0L else nextWall,
                    nextFireElapsed = if (hitMax) 0L else now.elapsed + (nextWall - now.wall).coerceAtLeast(0L),
                )
                val events = if (shouldFire || catchUp || t.misfirePolicy == MisfirePolicy.COALESCE) {
                    listOf(fireEventOf(t, dueAtWall, now.wall, nextRunCount, finished = hitMax))
                } else emptyList()
                ProcessResult(updated, events)
            }
            ClockTaskKind.FIXED_DELAY -> {
                val interval = t.intervalMs.coerceAtLeast(1_000L)
                val nextWall = now.wall + interval
                val hitMax = t.maxRuns > 0 && nextRunCount >= t.maxRuns
                val updated = t.copy(
                    enabled = !hitMax,
                    runCount = nextRunCount,
                    lastFiredWall = now.wall,
                    nextFireWall = if (hitMax) 0L else nextWall,
                    nextFireElapsed = if (hitMax) 0L else now.elapsed + interval,
                )
                ProcessResult(updated, listOf(fireEventOf(t, dueAtWall, now.wall, nextRunCount, finished = hitMax)))
            }
            ClockTaskKind.CRON -> {
                val nextWall = parseCron(t.cronExpr)?.nextAfter(now.wall, zone) ?: 0L
                val hitMax = (t.maxRuns > 0 && nextRunCount >= t.maxRuns) || nextWall <= 0L
                val tooLate = (now.wall - dueAtWall > 10 * 60_000L) && t.misfirePolicy == MisfirePolicy.SKIP_TO_NEXT
                val updated = t.copy(
                    enabled = !hitMax,
                    runCount = nextRunCount,
                    lastFiredWall = now.wall,
                    nextFireWall = if (hitMax) 0L else nextWall,
                    nextFireElapsed = 0L,
                )
                val events = if (tooLate) emptyList() else listOf(fireEventOf(t, dueAtWall, now.wall, nextRunCount, finished = hitMax))
                ProcessResult(updated, events)
            }
            ClockTaskKind.CYCLE_PHASE -> {
                if (t.phases.isEmpty()) {
                    return ProcessResult(t.copy(enabled = false), emptyList())
                }
                val curIdx = t.phaseIndex.coerceIn(0, t.phases.lastIndex)
                val curPhase = t.phases[curIdx]
                val adv = advanceCycle(t, curPhase, now, dueAtWall)
                ProcessResult(adv.updated, listOf(adv.event))
            }
        }
    }

    private data class CycleAdvance(val updated: ClockTask, val event: ClockFireEvent)

    private fun advanceCycle(t: ClockTask, completedPhase: ClockPhase, now: Clocks, scheduledAt: Long): CycleAdvance {
        val nextRunCount = t.runCount + 1
        val lastIdx = t.phases.lastIndex
        val nextPhaseIdx = if (t.phaseIndex >= lastIdx) 0 else t.phaseIndex + 1
        val nextCycleIdx = if (t.phaseIndex >= lastIdx) t.cycleIndex + 1 else t.cycleIndex
        val completedAll = t.phaseIndex >= lastIdx && t.maxRuns > 0 && t.cycleIndex >= t.maxRuns
        val nextPhase = if (completedAll) null else t.phases[nextPhaseIdx]
        val nextWall = if (nextPhase == null) 0L else now.wall + nextPhase.durationMs.coerceAtLeast(1_000L)
        val updated = t.copy(
            enabled = !completedAll,
            phaseIndex = if (completedAll) t.phaseIndex else nextPhaseIdx,
            cycleIndex = if (completedAll) t.cycleIndex else nextCycleIdx,
            runCount = nextRunCount,
            lastFiredWall = now.wall,
            nextFireWall = nextWall,
            nextFireElapsed = if (nextPhase == null) 0L else now.elapsed + nextPhase.durationMs.coerceAtLeast(1_000L),
        )
        val ev = ClockFireEvent(
            taskId = t.id,
            label = t.label,
            kind = t.kind,
            action = t.action,
            scheduledAtWall = scheduledAt,
            firedAtWall = now.wall,
            runCount = nextRunCount,
            cycleIndex = t.cycleIndex,
            phaseIndex = t.phaseIndex,
            phaseName = completedPhase.name,
            completedPhase = completedPhase,
            nextPhase = nextPhase,
            finished = completedAll,
            payload = t.payload,
        )
        return CycleAdvance(updated, ev)
    }

    private fun fireEventOf(
        t: ClockTask,
        scheduledAt: Long,
        firedAt: Long,
        runCount: Int,
        finished: Boolean,
    ) = ClockFireEvent(
        taskId = t.id,
        label = t.label,
        kind = t.kind,
        action = t.action,
        scheduledAtWall = scheduledAt,
        firedAtWall = firedAt,
        runCount = runCount,
        cycleIndex = t.cycleIndex,
        phaseIndex = t.phaseIndex,
        phaseName = t.phases.getOrNull(t.phaseIndex)?.name,
        finished = finished,
        payload = t.payload,
    )

    private fun parseCron(expr: String): BeijingCronExpression? {
        if (expr.isBlank()) return null
        return cronCache.getOrPut(expr) { BeijingCronExpression.parse(expr) ?: return null }
    }

    private fun rebuildHeap() {
        heap.clear()
        for (t in tasks.values) {
            if (t.enabled && !t.paused && t.nextFireWall > 0L) heap.add(t)
        }
    }

    private fun persistLocked() {
        store?.write(
            linkedMapOf(
                "version" to 1,
                "lastTickWall" to lastTickWall,
                "tasks" to tasks.values.map { it.toMap() },
            ),
        )
    }

    companion object {
        private const val MAX_FIRES_PER_TICK = 32
        private const val STALE_MS = 24 * 3_600_000L
    }
}
