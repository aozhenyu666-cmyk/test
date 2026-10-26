package cn.companion.pet.core.training

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * S1：到点主动开始训练（不用用户先打开同桌）。纯逻辑，时间由调用方给。
 *
 * 处理链：计划时间点到期 → 本地规则检查（暂停、安静时段、其他会话、是否亮屏、能否显示悬浮窗）
 * → 有未完成训练就接着它，否则出一道本地题 → 记下实际结果和调度延迟。
 *
 * 诚实记账（A18 / A25）：
 * - 每个时间点只处理一次（按 slotId 去重），重启、重复 tick 不会重复开题；
 * - 记录“计划时间 → 实际处理时间”的延迟，不伪报准时；
 * - 发通知只记 NOTIFIED，**不等于用户看见了**；之后亮屏才真正展示并记 SHOWN；
 * - 超过宽限还没能展示的记 MISSED 并写明原因，不补发一堆旧题。
 */
@Serializable
enum class PlanOutcome {
    /** 题目气泡真的显示了。 */
    SHOWN,
    /** 有未完成的训练，接着原题原步骤显示了。 */
    RESUMED,
    /** 当时不能展示，已提交通知（不代表用户看见）。 */
    NOTIFIED,
    /** 系统不允许展示，也发不了通知。 */
    BLOCKED_BY_OS,
    /** 本地规则决定这次不打扰（暂停、安静时段）。 */
    SKIPPED,
    /** 暂时不合适（例如另一个会话在进行），宽限内再试。 */
    DEFERRED,
    /** 超过宽限仍没能展示。 */
    MISSED,
}

@Serializable
data class PlanRecord(
    val slotId: String,
    val dueAt: Long,
    val decidedAt: Long,
    /** 实际处理时间 - 计划时间。 */
    val delayMillis: Long,
    val outcome: PlanOutcome,
    val reason: String = "",
    val attempts: Int = 1,
) {
    /** 终态：这个时间点不再处理。NOTIFIED/BLOCKED/DEFERRED 在宽限内还会再试着真正展示。 */
    val terminal: Boolean get() = outcome in setOf(PlanOutcome.SHOWN, PlanOutcome.RESUMED, PlanOutcome.SKIPPED, PlanOutcome.MISSED)
}

@Serializable
data class PlanLedger(val records: List<PlanRecord> = emptyList()) {
    fun get(slotId: String): PlanRecord? = records.lastOrNull { it.slotId == slotId }
}

data class PlanSlot(val slotId: String, val dueAt: Long)

/** 做决定时的本地事实。每一项都来自本机可靠信号，没有的就不假装知道。 */
data class PlanContext(
    val now: Long,
    val paused: Boolean,
    val quiet: Boolean,
    /** 亮屏且已解锁。 */
    val userActive: Boolean,
    /** 悬浮窗能显示（桌宠服务在跑、悬浮窗权限在）。 */
    val canShowOverlay: Boolean,
    /** 已有未完成训练：直接接着它，这就是“现有任务的下一步”。 */
    val trainingActive: Boolean,
    /** 其他会话（严格陪想/外部 AI）正在进行时的原因；null 表示没有。 */
    val busyReason: String?,
    /** 计划开启的时刻：之前的时间点不算错过。 */
    val since: Long = 0,
)

sealed interface PlanDecision {
    data object None : PlanDecision
    data class Start(val slot: PlanSlot, val delayMillis: Long, val resumeExisting: Boolean) : PlanDecision
    data class Notify(val slot: PlanSlot, val delayMillis: Long, val reason: String) : PlanDecision
    /** 只记账，不做任何展示动作。 */
    data class Record(val record: PlanRecord) : PlanDecision
}

class TrainingPlanner(
    /** 到点后多久内还算“这一次”；超过就记 MISSED。 */
    private val graceMillis: Long = 30 * 60_000L,
) {
    /** 解析 "08:30, 20:00" 这样的设置；非法项忽略，最多 6 个。 */
    fun parseTimes(text: String): List<LocalTime> = text.split(',', '，', ' ', ';', '；')
        .mapNotNull { raw ->
            val t = raw.trim()
            if (t.isEmpty()) null else runCatching {
                val (h, m) = t.split(':', '：').map { it.trim().toInt() }
                LocalTime.of(h, m)
            }.getOrNull()
        }
        .distinct().sorted().take(6)

    /** 昨天和今天的全部时间点（覆盖跨午夜的宽限）。 */
    fun slots(now: Long, times: List<LocalTime>, zone: ZoneId): List<PlanSlot> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return listOf(today.minusDays(1), today).flatMap { day -> times.map { slot(day, it, zone) } }
            .sortedBy { it.dueAt }
    }

    /** 下一个还没到的时间点（给闹钟用）；没有计划返回 null。 */
    fun nextDue(now: Long, times: List<LocalTime>, zone: ZoneId): Long? {
        if (times.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return listOf(today, today.plusDays(1)).flatMap { day -> times.map { slot(day, it, zone).dueAt } }
            .filter { it > now }.minOrNull()
    }

    private fun slot(day: LocalDate, t: LocalTime, zone: ZoneId) =
        PlanSlot(day.toString() + "@" + t.toString(), day.atTime(t).atZone(zone).toInstant().toEpochMilli())

    /**
     * 一次只决定一件事。先把过了宽限的旧时间点记成 MISSED，再处理最近一个还在宽限内的。
     */
    fun decide(ledger: PlanLedger, slots: List<PlanSlot>, ctx: PlanContext): PlanDecision {
        val now = ctx.now
        val open = slots.filter { it.dueAt <= now && it.dueAt >= ctx.since && ledger.get(it.slotId)?.terminal != true }
        open.firstOrNull { now - it.dueAt > graceMillis }?.let { old ->
            val prev = ledger.get(old.slotId)
            val why = when (prev?.outcome) {
                PlanOutcome.NOTIFIED -> "发过通知，但宽限内一直没能在屏幕上展示"
                PlanOutcome.BLOCKED_BY_OS -> "系统一直不允许展示：" + prev.reason
                PlanOutcome.DEFERRED -> "一直有别的事占着：" + prev.reason
                else -> "到点时桌宠没在运行，或系统推迟太久"
            }
            return PlanDecision.Record(PlanRecord(old.slotId, old.dueAt, now, now - old.dueAt, PlanOutcome.MISSED,
                "超过宽限 " + graceMillis / 60_000 + " 分钟：" + why, (prev?.attempts ?: 0) + 1))
        }
        val slot = open.lastOrNull() ?: return PlanDecision.None
        // 宽限内有两个时间点都没处理：只做最近的一个，早的合并掉，不连发两题。
        open.firstOrNull { it != slot }?.let { early ->
            return PlanDecision.Record(PlanRecord(early.slotId, early.dueAt, now, now - early.dueAt, PlanOutcome.SKIPPED,
                "与 " + slot.slotId.substringAfter('@') + " 的计划合并", (ledger.get(early.slotId)?.attempts ?: 0) + 1))
        }
        val prev = ledger.get(slot.slotId)
        val delay = now - slot.dueAt
        fun record(outcome: PlanOutcome, reason: String) =
            PlanDecision.Record(PlanRecord(slot.slotId, slot.dueAt, now, delay, outcome, reason, (prev?.attempts ?: 0) + 1))
        return when {
            ctx.paused -> record(PlanOutcome.SKIPPED, "桌宠暂停中")
            ctx.quiet -> record(PlanOutcome.SKIPPED, "安静时段")
            ctx.busyReason != null ->
                // 同一原因重复 DEFERRED 不重复写账。
                if (prev?.outcome == PlanOutcome.DEFERRED && prev.reason == ctx.busyReason) PlanDecision.None
                else record(PlanOutcome.DEFERRED, ctx.busyReason)
            !ctx.userActive || !ctx.canShowOverlay -> {
                val why = if (!ctx.userActive) "屏幕关着或没解锁" else "悬浮窗现在不能显示"
                // 通知只发一次；之后等亮屏/恢复再真正展示。
                if (prev?.outcome == PlanOutcome.NOTIFIED || prev?.outcome == PlanOutcome.BLOCKED_BY_OS) PlanDecision.None
                else PlanDecision.Notify(slot, delay, why)
            }
            // 上次没能展示：两分钟内不反复重试，避免一直弹。
            prev != null && prev.outcome in setOf(PlanOutcome.BLOCKED_BY_OS, PlanOutcome.DEFERRED) &&
                now - prev.decidedAt < RETRY_MILLIS -> PlanDecision.None
            else -> PlanDecision.Start(slot, delay, resumeExisting = ctx.trainingActive)
        }
    }

    companion object {
        const val RETRY_MILLIS = 120_000L
    }

    fun record(ledger: PlanLedger, r: PlanRecord): PlanLedger =
        PlanLedger((ledger.records.filter { it.slotId != r.slotId } + r).takeLast(60))

    /** 诊断用：最近几次的真实结果。 */
    fun describe(ledger: PlanLedger, zone: ZoneId, limit: Int = 5): List<String> =
        ledger.records.takeLast(limit).reversed().map { r ->
            val label = when (r.outcome) {
                PlanOutcome.SHOWN -> "已显示题目"
                PlanOutcome.RESUMED -> "接着原题显示了"
                PlanOutcome.NOTIFIED -> "发了通知（不代表看见）"
                PlanOutcome.BLOCKED_BY_OS -> "系统不允许展示"
                PlanOutcome.SKIPPED -> "没打扰"
                PlanOutcome.DEFERRED -> "推迟"
                PlanOutcome.MISSED -> "错过"
            }
            r.slotId.substringAfter('@') + " " + label + "，延迟 " + (r.delayMillis / 1000) + " 秒" +
                (if (r.reason.isBlank()) "" else "（" + r.reason + "）")
        }
}

class PlanLedgerStore(root: File) {
    private val file = File(root, "training/plan-ledger.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var lastError: String? = null
        private set

    fun load(): PlanLedger = synchronized(lock) {
        if (!file.exists()) return PlanLedger()
        runCatching { json.decodeFromString(PlanLedger.serializer(), file.readText()) }
            .onFailure { lastError = "读取失败：" + it.javaClass.simpleName }
            .getOrDefault(PlanLedger())
    }

    fun save(l: PlanLedger): Boolean = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(PlanLedger.serializer(), l))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            lastError = null
            true
        } catch (t: Exception) {
            lastError = t.javaClass.simpleName + ":" + t.message
            false
        }
    }
}
