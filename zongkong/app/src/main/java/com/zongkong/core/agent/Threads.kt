package com.zongkong.core.agent

import com.zongkong.core.DayClock
import kotlinx.serialization.Serializable
import java.time.ZoneId
import kotlin.random.Random

/**
 * 一段对话。
 *
 * 用户说“上下文很繁琐、没有新建对话”：以前只有一条无限堆下去的对话，模型每一轮都被塞满旧的；
 * 现在按段分开，每段封顶 [ChatThreads.MAX_MESSAGES] 条，收尾时压成一份 [carry]（交接摘要）带进下一段。
 * [closed] 的段还能打开看、也能接着聊（打开即重新激活），不是删除。
 */
@Serializable
data class ChatThread(
    val id: String,
    val title: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val messages: List<ChatMsg> = emptyList(),
    /** 这一段收尾后压出来的交接：结论、做到哪、下一步、没解决的。带进下一段。 */
    val carry: String = "",
    val closed: Boolean = false,
    /**
     * 这一段属于哪种对话：[ChatMode.NOW]（当下，一天内的事）还是 [ChatMode.OVERALL]（整体，长期与取舍）。
     * 老版本 chat.json 里没有这个字段，读进来一律算「当下」。
     */
    val mode: String = ChatMode.NOW.key,
    /** 这一段在哪种战役里开着（[Campaign] 的枚举名）：战役跟着段走，不跨模式串台。 */
    val campaign: String = "",
    val campaignTopic: String = "",
    val campaignAt: Long = 0,
) {
    /** 这段一共多少条（界面显示用）。 */
    val count: Int get() = messages.size

    val chatMode: ChatMode get() = ChatMode.of(mode)
}

/**
 * 两种对话。
 *
 * - [NOW] 当下：今天做什么、执行与反馈；
 * - [OVERALL] 整体：长期目标、本周交付、重要判断、规划取舍。
 *
 * 两种对话的消息历史、当前段、交接摘要、每日段数和战役上下文完全隔离，只靠提示词区分是不够的。
 */
@Serializable
enum class ChatMode(val key: String, val label: String, val hint: String) {
    NOW("now", "当下", "今天的事、正在推的一步、执行与反馈"),
    OVERALL("overall", "整体", "长期目标、本周交付、判断与取舍、整体复盘"),
    ;

    companion object {
        /** 冷启动 / 认不出来时的默认模式（用户拍板：每次进总控对话默认「当下」）。 */
        val DEFAULT = NOW

        fun of(key: String): ChatMode = when (key.trim().lowercase()) {
            OVERALL.key, "overall", "全部", "整体" -> OVERALL
            else -> NOW
        }
    }
}

/**
 * 多段对话的纯逻辑：编号、标题、交接摘要、今天的段数、每日上限。
 *
 * 全是纯函数，不碰 Android、不写文件，方便单测；真正读写文件和调 AI 摘要在 `ChatRepo` 里。
 */
object ChatThreads {

    /** 一段最多留多少条；到顶再开口就收尾换段。 */
    const val MAX_MESSAGES = 60

    /** 交接摘要最多多少字。 */
    const val CARRY_MAX = 600

    /** 标题取第一条用户消息的前多少个字。 */
    const val TITLE_MAX = 30

    /** 本地兜底摘要最多取最后几条 assistant 的话。 */
    const val CARRY_SOURCE = 6

    /** 没有用户消息时给一段起个名。 */
    const val FALLBACK_TITLE = "新的对话"

    private val SPACES = Regex("""\s+""")
    private val OPTIONS_LINE = Regex("""^\s*【选项】.*$""", RegexOption.MULTILINE)

    // ---------- 编号与标题 ----------

    /** 新段编号：两个随机数拼成，短、可读、够用（2^40 种）。纯函数，测试里用固定种子就能复现。 */
    fun newId(rnd: Random): String =
        "t" + rnd.nextInt(1 shl 20).toString(36).padStart(4, '0') +
            rnd.nextInt(1 shl 20).toString(36).padStart(4, '0')

    /** 标题：第一条用户消息的前 [TITLE_MAX] 个字；没有就用车上的交接摘要，再没有就叫“新的对话”。 */
    fun titleOf(thread: ChatThread): String {
        val first = thread.messages.firstOrNull { it.role == ChatMsg.USER && !it.hidden && it.content.isNotBlank() }
            ?: thread.messages.firstOrNull { it.role == ChatMsg.USER && it.content.isNotBlank() }
        val text = first?.content?.replace(SPACES, " ")?.trim().orEmpty()
        if (text.isNotBlank()) return text.take(TITLE_MAX)
        return thread.carry.trim().take(TITLE_MAX).ifBlank { FALLBACK_TITLE }
    }

    /** 标题空着就补上（别改已有的名字）。 */
    fun titled(thread: ChatThread): ChatThread =
        if (thread.title.isBlank()) thread.copy(title = titleOf(thread)) else thread

    // ---------- 交接摘要 ----------

    /** 本地兜底摘要：最后几条 assistant 的结论拼起来，≤ [CARRY_MAX] 字。不调 AI，绝不阻塞。 */
    fun carryOf(thread: ChatThread): String {
        val said = thread.messages.asSequence()
            .filter { it.role == ChatMsg.ASSISTANT && !it.error && !it.hidden && it.content.isNotBlank() }
            .map { clean(it.content) }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(CARRY_SOURCE)
            .distinct()
        if (said.isEmpty()) return ""
        return clip(said.joinToString("；"))
    }

    /**
     * 把“更早的交接”和这一段自己的结论合成一份 ≤ [CARRY_MAX] 字的交接。
     * 太长就砍掉开头、留最近的（旧细节本来也该丢）。
     */
    fun mergeCarry(previous: String, own: String, max: Int = CARRY_MAX): String {
        val parts = listOf(previous.trim(), own.trim()).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        return clip(parts.joinToString("；"), max)
    }

    /** 这一段给模型看的上下文说明（标题 + 已经压出来的交接）。 */
    fun context(thread: ChatThread): String = buildString {
        val title = thread.title.ifBlank { titleOf(thread) }
        if (title.isNotBlank()) append("这一段：").append(title).append('\n')
        val carry = thread.carry.trim()
        if (carry.isNotBlank()) append("已压缩的交接：").append(carry)
    }.trim()

    /**
     * 写进系统提示的交接信息：`当前是今天第 N 段对话。`
     * 有上一段压出来的交接时，再补一行 `上一段交接：…`。
     */
    fun contextLine(threads: List<ChatThread>, activeId: String, now: Long, zone: ZoneId = DayClock.BEIJING): String {
        if (threads.isEmpty()) return ""
        val active = threads.firstOrNull { it.id == activeId }
            ?: threads.lastOrNull { !it.closed }
            ?: threads.lastOrNull()
            ?: return ""
        // 交接摘要只在同一种对话里传递：整体的结论不会被当下当作「上一段」
        val mine = inMode(threads, active.chatMode)
        val n = indexToday(mine, active, now, zone)
        val previous = mine.asSequence()
            .filter { it.id != active.id && it.carry.isNotBlank() }
            .maxByOrNull { it.updatedAt }
        return buildString {
            // 说清楚这是哪一条线（当下 / 整体），否则模型会把整体的结论当成今天的
            append("当前是").append(active.chatMode.label).append("对话，今天第 ").append(n).append(" 段。")
            if (previous != null) append('\n').append("上一段交接：").append(previous.carry.trim().take(CARRY_MAX))
        }
    }

    // ---------- 按模式隔离 ----------

    /** 只看 [mode] 这一种对话的段。 */
    fun inMode(threads: List<ChatThread>, mode: ChatMode): List<ChatThread> = threads.filter { it.chatMode == mode }

    /** [mode] 上次动过的那一段，优先没收尾的（切换模式时回到这里）；没有就是 null。 */
    fun activeOf(threads: List<ChatThread>, mode: ChatMode): ChatThread? {
        val mine = inMode(threads, mode)
        return mine.filterNot { it.closed }.maxByOrNull { it.updatedAt }
            ?: mine.maxByOrNull { it.updatedAt }
    }

    /**
     * 本次运行里该让哪一种模式出场：用户刚切过就用他选的；否则回到 [ChatMode.DEFAULT]（当下）。
     * 冷启动取不到上次选的那个，所以一律是「当下」。
     */
    fun startMode(picked: ChatMode?, threads: List<ChatThread>): ChatMode = picked ?: ChatMode.DEFAULT

    /** 打开某一段时该切到的模式。 */
    fun modeOf(threads: List<ChatThread>, id: String): ChatMode? =
        threads.firstOrNull { it.id == id }?.chatMode

    // ---------- 今天的段数 / 每日上限 ----------

    /** 逻辑日（凌晨 4 点换日）；跨天归零靠它。 */
    fun dayKey(at: Long, zone: ZoneId = DayClock.BEIJING): String = DayClock.dateKey(at, zone)

    /**
     * 今天新建了几段（按 createdAt）。[mode] 不为空时只数这一种对话的：
     * 一边的新建不能把另一边的额度用掉。
     */
    fun todayCount(threads: List<ChatThread>, now: Long, zone: ZoneId = DayClock.BEIJING, mode: ChatMode? = null): Int {
        val key = dayKey(now, zone)
        return threads.count { it.createdAt > 0 && dayKey(it.createdAt, zone) == key && (mode == null || it.chatMode == mode) }
    }

    /**
     * 今天这一种对话用掉了几段额度。
     *
     * 取记账数和「今天真正有内容的段数」的较大者：删过一段也不能把额度还回来，
     * 但刚切模式时自动建出来的**空白段**不占额度——否则切过去第一次说话就会被每日上限拦住。
     */
    fun openedToday(threads: List<ChatThread>, now: Long, zone: ZoneId = DayClock.BEIJING, mode: ChatMode, recorded: Int): Int =
        maxOf(recorded, usedCount(threads, now, zone, mode))

    /** 今天这一种对话里有内容的段数：有消息、有交接、或者开过战役才算。 */
    fun usedCount(threads: List<ChatThread>, now: Long, zone: ZoneId = DayClock.BEIJING, mode: ChatMode): Int {
        val key = dayKey(now, zone)
        return threads.count { t ->
            t.chatMode == mode && t.createdAt > 0 && dayKey(t.createdAt, zone) == key && material(t)
        }
    }

    /** 这一段是不是「有内容」：空段（刚建、还没说话）不算用掉额度。 */
    fun material(t: ChatThread): Boolean = t.messages.isNotEmpty() || t.carry.isNotBlank() || t.campaign.isNotBlank()

    /** 当前这段是今天第几段（从 1 开始；不是今天的段也算第 1 段）。只算同一种对话的段。 */
    fun indexToday(threads: List<ChatThread>, thread: ChatThread, now: Long, zone: ZoneId = DayClock.BEIJING): Int {
        val key = dayKey(now, zone)
        val today = inMode(threads, thread.chatMode).filter { it.createdAt > 0 && dayKey(it.createdAt, zone) == key }.sortedBy { it.createdAt }
        val i = today.indexOfFirst { it.id == thread.id }
        return if (i >= 0) i + 1 else today.size.coerceAtLeast(1)
    }

    /** 今天还能新开几段；-1 = 不限。 */
    fun remaining(perDay: Int, opened: Int): Int =
        if (perDay <= 0) -1 else (perDay - opened).coerceAtLeast(0)

    /** 今天还能不能新开一段。 */
    fun canOpen(perDay: Int, opened: Int): Boolean = perDay <= 0 || opened < perDay

    /** 给界面/失败提示用的一句话。 */
    fun limitText(perDay: Int, opened: Int): String = when {
        perDay <= 0 -> "今天不限新建段数"
        remaining(perDay, opened) > 0 -> "今天还能新开 ${perDay - opened} 段（每天最多 $perDay 段）"
        else -> "今天已经新开 $perDay 段，到上限了；把旧的一段收尾、带着交接摘要接着用"
    }

    /** 同上，但说清楚是哪一种对话的额度（当下 / 整体各算各的）。 */
    fun limitText(perDay: Int, opened: Int, mode: ChatMode): String = when {
        perDay <= 0 -> "今天的${mode.label}对话不限新建段数"
        remaining(perDay, opened) > 0 -> "今天的${mode.label}对话还能新开 ${perDay - opened} 段（每天最多 $perDay 段）"
        else -> "今天的${mode.label}对话已经新开 $perDay 段，到上限了；把${mode.label}里旧的一段收尾、带着交接摘要接着用"
    }

    // ---------- 段的收尾 / 换段 ----------

    /** 这一段加上 [added] 会不会超过 [max] 条。 */
    fun overflows(thread: ChatThread, added: List<ChatMsg>, max: Int = MAX_MESSAGES): Boolean =
        thread.messages.size + added.size > max

    /** 收尾：带上交接，标记已收。 */
    fun closed(thread: ChatThread, carry: String, now: Long): ChatThread =
        thread.copy(carry = carry.trim().take(CARRY_MAX), closed = true, updatedAt = now)

    /** 只换交接（到每日上限、不能新开时用）。 */
    fun withCarry(thread: ChatThread, carry: String, now: Long): ChatThread =
        thread.copy(carry = carry.trim().take(CARRY_MAX), updatedAt = now)

    /** 裁到最近 [max] 条。 */
    fun trimmed(thread: ChatThread, max: Int = MAX_MESSAGES): ChatThread =
        if (thread.messages.size <= max) thread else thread.copy(messages = thread.messages.takeLast(max))

    /** 打开一段（收尾过的也能接着聊）。 */
    fun opened(thread: ChatThread): ChatThread = if (thread.closed) thread.copy(closed = false) else thread

    /**
     * 换段：旧段带着交接收尾，新段接手刚到的消息（新段也带着交接，方便接着往下压）。
     * 返回（旧段，新段）。
     */
    fun rollover(
        thread: ChatThread,
        added: List<ChatMsg>,
        newId: String,
        now: Long,
        max: Int = MAX_MESSAGES,
    ): Pair<ChatThread, ChatThread> {
        val carry = mergeCarry(thread.carry, carryOf(thread))
        val old = closed(thread, carry, now)
        val fresh = titled(
            ChatThread(
                id = newId,
                createdAt = now,
                updatedAt = now,
                messages = added.takeLast(max),
                carry = carry,
                // 新段跟旧段是同一种对话；进行中的战役也跟着段走，不会串到另一种对话里
                mode = thread.mode,
                campaign = thread.campaign,
                campaignTopic = thread.campaignTopic,
                campaignAt = thread.campaignAt,
            ),
        )
        return old to fresh
    }

    /** 最近的在前面（界面用）。 */
    fun recentFirst(threads: List<ChatThread>): List<ChatThread> =
        threads.sortedByDescending { if (it.updatedAt > 0) it.updatedAt else it.createdAt }

    // ---------- 小工具 ----------

    private fun clean(text: String): String =
        text.replace(OPTIONS_LINE, "").replace(SPACES, " ").trim()

    private fun clip(text: String, max: Int = CARRY_MAX): String {
        val t = text.trim()
        return if (t.length <= max) t else "…" + t.takeLast(max - 1)
    }
}
