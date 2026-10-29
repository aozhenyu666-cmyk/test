package cn.companion.pet.core.appointment

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 约定的账本：增删改查 + 状态迁移。纯逻辑（除落盘）。
 *
 * 关键规则：
 * - **改期/取消都 +1 版本**，旧版本对应的异步结果必须作废；
 * - 到期后必须有终态，不允许永远停在 PENDING；
 * - 过期（超过迟到容忍）直接 EXPIRED，**不补发**。
 */
@Serializable
data class AppointmentState(
    val items: List<Appointment> = emptyList(),
)

class AppointmentBook(
    private val store: AppointmentStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var state: AppointmentState = store.load()

    fun all(): List<Appointment> = state.items

    /** 还活着的约定（等待中/投递中/等回答）。 */
    fun active(): List<Appointment> = state.items.filter { it.status.active }

    /** 用户能看到的列表：按时间排序，只显示还活着的。 */
    fun upcoming(): List<Appointment> = active().sortedBy { it.dueAtMillis }

    fun find(id: String): Appointment? = state.items.firstOrNull { it.id == id }

    /**
     * 新建约定。
     *
     * @return null 表示参数不合格（没有话题/没有时间），调用方应去问用户补信息
     */
    fun create(topic: String, dueInMillis: Long, basis: String): Appointment? {
        val cleanTopic = topic.trim()
        if (cleanTopic.isEmpty() || dueInMillis <= 0L) return null
        val t = now()
        val item = Appointment(
            id = "ap-" + t.toString(36) + "-" + (state.items.size + 1),
            topic = cleanTopic.take(30),
            dueAtMillis = t + dueInMillis,
            basis = basis.trim().take(120),
            createdAt = t,
        )
        persist(state.copy(items = state.items + item))
        return item
    }

    /**
     * 改期。版本 +1，**旧的生成结果凭版本失效**。
     * @return 改期后的条目；null 表示找不到或已终结
     */
    fun reschedule(id: String, dueInMillis: Long): Appointment? {
        val item = find(id) ?: return null
        if (!item.status.active) return null
        if (dueInMillis <= 0L) return null
        val t = now()
        val next = item.copy(
            dueAtMillis = t + dueInMillis,
            status = AppointmentStatus.PENDING,
            version = item.version + 1,
            reschedules = item.reschedules + 1,
            updatedAt = t,
        )
        persist(state.copy(items = state.items.map { if (it.id == id) next else it }))
        return next
    }

    /** 取消。版本 +1。 */
    fun cancel(id: String): Appointment? {
        val item = find(id) ?: return null
        if (!item.status.active) return null
        val t = now()
        val next = item.copy(
            status = AppointmentStatus.CANCELLED,
            version = item.version + 1,
            updatedAt = t,
        )
        persist(state.copy(items = state.items.map { if (it.id == id) next else it }))
        return next
    }

    /**
     * 标记开始投递（防重复）。
     * @param expectedVersion 调用方持有的版本；不匹配说明已被改期/取消，返回 null
     */
    fun beginDelivery(id: String, expectedVersion: Int): Appointment? {
        val item = find(id) ?: return null
        if (item.version != expectedVersion) return null   // 已被改动，这次作废
        if (item.status != AppointmentStatus.PENDING) return null
        val next = item.copy(status = AppointmentStatus.DELIVERING, updatedAt = now())
        persist(state.copy(items = state.items.map { if (it.id == id) next else it }))
        return next
    }

    /** 投递成功：进入等回答。 */
    fun markDelivered(id: String, expectedVersion: Int): Appointment? {
        val item = find(id) ?: return null
        if (item.version != expectedVersion) return null
        val next = item.copy(status = AppointmentStatus.AWAITING, updatedAt = now())
        persist(state.copy(items = state.items.map { if (it.id == id) next else it }))
        return next
    }

    /** 投递失败：退回 PENDING，等下一次。 */
    fun markDeliveryFailed(id: String, expectedVersion: Int): Appointment? {
        val item = find(id) ?: return null
        if (item.version != expectedVersion) return null
        val next = item.copy(status = AppointmentStatus.PENDING, updatedAt = now())
        persist(state.copy(items = state.items.map { if (it.id == id) next else it }))
        return next
    }

    /**
     * 用户回答了：只结算**相关的**那一件。
     *
     * @param id 要结算的事项；null 表示调用方无法判断对应哪件 → **不动任何事项**
     */
    fun complete(id: String?): Appointment? {
        if (id == null) return null   // 无法判断就保留，绝不批量结束
        val item = find(id) ?: return null
        if (!item.status.active) return null
        val next = item.copy(status = AppointmentStatus.DONE, version = item.version + 1, updatedAt = now())
        persist(state.copy(items = state.items.map { if (it.id == id) next else it }))
        return next
    }

    /**
     * 清理：把过期太久还没发出去的标 EXPIRED（**不补发**）。
     * 应用重启/长时间后台后调用。
     */
    fun expireOvertime(): List<Appointment> {
        val t = now()
        val changed = mutableListOf<Appointment>()
        val next = state.items.map { item ->
            if (item.status == AppointmentStatus.PENDING && item.overtime(t)) {
                item.copy(status = AppointmentStatus.EXPIRED, updatedAt = t).also { changed += it }
            } else item
        }
        if (changed.isNotEmpty()) persist(state.copy(items = next))
        return changed
    }

    /**
     * 到点该问的那些。
     *
     * @return 按到期时间排序，最早的在前面
     */
    fun dueNow(): List<Appointment> {
        val t = now()
        return state.items.filter { it.due(t) }.sortedBy { it.dueAtMillis }
    }

    private fun persist(next: AppointmentState) {
        state = next
        store.save(next)
    }
}

/** 约定的落盘。原子替换，坏文件另存。 */
class AppointmentStore(
    root: File,
    private val replace: (File, File) -> Boolean = ::atomicReplace,
) {
    private val file = File(root, "appointments.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    var lastError: String? = null
        private set

    fun load(): AppointmentState = runCatching {
        if (!file.exists()) AppointmentState() else json.decodeFromString(AppointmentState.serializer(), file.readText())
    }.getOrElse {
        lastError = "约定文件读取失败，按空处理"
        AppointmentState()
    }

    fun save(state: AppointmentState): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "." + file.name + ".tmp")
        tmp.writeText(json.encodeToString(AppointmentState.serializer(), state))
        if (replace(tmp, file)) { lastError = null; true }
        else { tmp.delete(); lastError = "约定保存失败"; false }
    } catch (e: Exception) {
        lastError = "约定保存失败"
        false
    }

    private companion object {
        fun atomicReplace(from: File, to: File): Boolean = runCatching {
            java.nio.file.Files.move(
                from.toPath(), to.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrElse {
            runCatching {
                java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
        }
    }
}
