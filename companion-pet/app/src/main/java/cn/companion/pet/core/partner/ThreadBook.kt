package cn.companion.pet.core.partner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 话题线：伙伴“记得还没聊完的事”的最小单位。
 *
 * 和 [cn.companion.pet.core.companion.FactStore] 的分工：
 * - FactStore 存**事实**（他是谁、他说过什么），可确认/更正/忘记；
 * - ThreadBook 存**还在进行的事**（一个目标、一个烦心事、一篇没聊完的文章），
 *   决定“什么时候该主动回来问一句”。线只引用事实 id，不复制事实内容，避免两套记忆真相。
 *
 * 规则（纯函数，时间由调用方给）：
 * - 用户每回应一次，线就“活”一次：重排下次跟进时间，跟进计数清零；
 * - 没回应不等于不重要：按重要度退避，但不会因为沉默就永久停掉（避免“越不说越不问”的恶性循环）；
 * - 只有用户明确说“不聊这个了 / 解决了”才关闭；暂停有明确的恢复时间。
 */
@Serializable
enum class ThreadKind {
    /** 长期目标（例如“想把自律练起来”）。 */
    GOAL,
    /** 正在处理的问题 / 烦心事。 */
    OPEN_PROBLEM,
    /** 他表达过、值得继续讨论的观点。 */
    VIEW,
    /** 围绕一份材料（文章、视频、屏幕文字）的讨论。 */
    MATERIAL,
    /** 普通的“上次说到一半”。 */
    UNFINISHED_TALK,
}

@Serializable
enum class ThreadStatus { OPEN, PAUSED, CLOSED }

@Serializable
data class PartnerThread(
    val id: String,
    val kind: ThreadKind,
    /** 一句话标题，给提示词和记忆页看；不放原文全文。 */
    val title: String,
    /** 1 = 闲话，2 = 有意义，3 = 他明确在意的目标/问题。 */
    val importance: Int,
    val createdAt: Long,
    val status: ThreadStatus = ThreadStatus.OPEN,
    val lastUserTurnAt: Long = 0,
    val lastPetTurnAt: Long = 0,
    /** 下次可以主动提起的时刻；0 表示随时。 */
    val nextFollowUpAt: Long = 0,
    /** 连续主动提起却没得到回应的次数。 */
    val unansweredFollowUps: Int = 0,
    /** 引用的事实 / 事件 / 材料 id：线本身不复制内容。 */
    val sourceRefs: List<String> = emptyList(),
    /** 最近一次的进展摘要（一句话），用于“上次你说……现在怎么样了”。 */
    val lastProgress: String = "",
    val pausedUntil: Long = 0,
    val closedReason: String = "",
)

object ThreadRules {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    /** 用户回应后，多久再主动回来问进展。越重要越早。 */
    fun followUpAfterReply(importance: Int): Long = when (importance.coerceIn(1, 3)) {
        3 -> 6 * HOUR
        2 -> 24 * HOUR
        else -> 72 * HOUR
    }

    /**
     * 没回应时的退避：第 1 次没回 → 短间隔再试；之后逐级拉长，封顶后保持低频，不归零。
     * 重要度 3 的封顶是 1 天，1 的封顶是 1 周。
     */
    fun backoffAfterSilence(importance: Int, unanswered: Int): Long {
        val base = when (importance.coerceIn(1, 3)) { 3 -> 45 * MIN; 2 -> 3 * HOUR; else -> 12 * HOUR }
        val cap = when (importance.coerceIn(1, 3)) { 3 -> 24 * HOUR; 2 -> 72 * HOUR; else -> 7 * 24 * HOUR }
        val factor = 1L shl (unanswered - 1).coerceIn(0, 6)
        return (base * factor).coerceAtMost(cap)
    }
}

class ThreadBook(private var threads: List<PartnerThread> = emptyList()) {

    val all: List<PartnerThread> get() = threads
    fun remove(id: String) { threads = threads.filterNot { it.id == id } }

    fun open(now: Long): List<PartnerThread> = threads.filter {
        it.status == ThreadStatus.OPEN || (it.status == ThreadStatus.PAUSED && now >= it.pausedUntil)
    }

    fun get(id: String): PartnerThread? = threads.firstOrNull { it.id == id }

    /** 新开一条线；同标题的 OPEN 线不重复开，返回已有的。 */
    fun start(id: String, kind: ThreadKind, title: String, importance: Int, now: Long, refs: List<String> = emptyList()): PartnerThread {
        val t = title.trim().take(60)
        threads.firstOrNull { it.status != ThreadStatus.CLOSED && it.title == t }?.let { return it }
        val th = PartnerThread(id, kind, t, importance.coerceIn(1, 3), now, sourceRefs = refs.distinct().take(12),
            nextFollowUpAt = now + ThreadRules.followUpAfterReply(importance))
        threads = threads + th
        return th
    }

    fun replaceSources(id: String, refs: List<String>) { update(id) { it.copy(sourceRefs = refs.distinct().take(12)) } }

    /** 他就这条线说了话：活一次，重排跟进，未回应计数清零。 */
    fun onUserTurn(id: String, now: Long, progress: String = ""): PartnerThread? = update(id) {
        it.copy(
            status = if (it.status == ThreadStatus.PAUSED) ThreadStatus.OPEN else it.status,
            lastUserTurnAt = now, unansweredFollowUps = 0, pausedUntil = 0,
            lastProgress = progress.trim().take(80).ifBlank { it.lastProgress },
            nextFollowUpAt = now + ThreadRules.followUpAfterReply(it.importance),
        )
    }

    /** 它主动提了这条线并**确认显示/说出了**：记一次，等回应。 */
    fun onPetFollowUpShown(id: String, now: Long): PartnerThread? = update(id) {
        it.copy(lastPetTurnAt = now)
    }

    /** 提了但他没回应（由调用方在等待窗口结束时判定）：按重要度退避，不关闭。 */
    fun onFollowUpIgnored(id: String, now: Long): PartnerThread? = update(id) {
        val n = it.unansweredFollowUps + 1
        it.copy(unansweredFollowUps = n, nextFollowUpAt = now + ThreadRules.backoffAfterSilence(it.importance, n))
    }

    /** 他说“先别提这个”：暂停到指定时间，到点自动恢复。 */
    fun pause(id: String, until: Long): PartnerThread? = update(id) { it.copy(status = ThreadStatus.PAUSED, pausedUntil = until) }

    /** 只有他明确说解决了 / 不聊了才关闭。 */
    fun close(id: String, reason: String): PartnerThread? = update(id) { it.copy(status = ThreadStatus.CLOSED, closedReason = reason.take(40)) }

    /** 某条事实被用户忘记：引用它的线去掉这条引用；线标题若只来自它，也一起关掉。 */
    fun onFactForgotten(factId: String) {
        threads = threads.map { t ->
            if (factId !in t.sourceRefs) t
            else {
                val left = t.sourceRefs - factId
                if (left.isEmpty()) t.copy(sourceRefs = left, status = ThreadStatus.CLOSED, closedReason = "来源记忆已被删除")
                else t.copy(sourceRefs = left)
            }
        }
    }

    /** 现在到期、可以主动提起的线，按“重要度高、等得久”排序。 */
    fun due(now: Long): List<PartnerThread> = open(now)
        .filter { now >= it.nextFollowUpAt }
        .sortedWith(compareByDescending<PartnerThread> { it.importance }.thenBy { it.nextFollowUpAt })

    private fun update(id: String, f: (PartnerThread) -> PartnerThread): PartnerThread? {
        var out: PartnerThread? = null
        threads = threads.map { if (it.id == id && it.status != ThreadStatus.CLOSED) f(it).also { n -> out = n } else it }
        return out
    }
}

/** 原子落盘；读坏了另存备份并返回空，不让桌宠起不来。 */
class ThreadBookStore(root: File) {
    private val file = File(root, "partner/threads.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    @Volatile var lastError: String? = null
        private set

    fun load(): ThreadBook = synchronized(lock) {
        if (!file.exists()) return ThreadBook()
        runCatching { ThreadBook(json.decodeFromString(ListSerializer, file.readText())) }.getOrElse {
            lastError = "话题线读取失败：" + it.javaClass.simpleName
            runCatching { file.copyTo(File(file.parentFile, "threads.bad-" + System.currentTimeMillis() + ".json"), overwrite = true) }
            ThreadBook()
        }
    }

    fun save(book: ThreadBook): Boolean = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, ".threads.json.tmp")
            tmp.writeText(json.encodeToString(ListSerializer, book.all))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            lastError = null
            true
        } catch (t: Exception) {
            lastError = t.javaClass.simpleName + ":" + t.message
            false
        }
    }

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(PartnerThread.serializer())
    }
}
