package com.zongkong.core.plan

import com.zongkong.core.work.Work
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 远方：周计划之外的长期目标（求职、考试……）。不占界面、不排日程，
 * 只是挂在首页上方的一弯月亮——月相 = 时间走了多少，提醒你它还在。
 * 和事项关联后，总控看得到“这周有没有一步落在它上面”。
 */
@Serializable
data class Aim(
    val id: String,
    val title: String,
    val why: String = "",
    /** 截止日 yyyy-MM-dd；空 = 没有期限。 */
    val deadline: String = "",
    val created: Long = 0,
    /** 关联的事项 key。 */
    val threads: List<String> = emptyList(),
    val done: Boolean = false,
    val closedAt: Long = 0,
)

object Aims {
    /** 同时最多挂几个：多了就不是远方，是噪音。 */
    const val MAX = 4

    fun open(book: PlanBook): List<Aim> = book.aims.filter { !it.done }

    fun deadline(a: Aim): LocalDate? = a.deadline.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** 离截止还有几天（今天截止 = 0，过了是负数）；没期限 null。 */
    fun daysLeft(a: Aim, now: Long, zone: ZoneId): Long? {
        val d = deadline(a) ?: return null
        return ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), d)
    }

    /** 时间走了多少：0（刚立）… 1（到期）。没期限的按一年算一圈。 */
    fun phase(a: Aim, now: Long, zone: ZoneId): Float {
        val end = deadline(a)?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: (a.created + 365 * 86_400_000L)
        val span = (end - a.created).coerceAtLeast(86_400_000L)
        return ((now - a.created).toFloat() / span).coerceIn(0f, 1f)
    }

    /** 最近 7 天落在这个目标上的步数（关联事项下做完 / 推进的行动）。 */
    fun recentSteps(a: Aim, work: Work, now: Long): Int {
        if (a.threads.isEmpty()) return 0
        val keys = work.actions().filter { it.threadKey in a.threads }.map { it.key }.toSet()
        return work.events.count { it.actionKey in keys && it.at > now - 7 * 86_400_000L && (it.type.startsWith("done:") || it.type == "start") }
    }

    /** 新建或修改。[id] 空 = 新建。 */
    fun set(book: PlanBook, id: String, title: String, why: String, deadline: LocalDate?, threads: List<String>?, now: Long, newId: () -> String): Result<PlanBook> {
        val t = title.trim()
        val old = book.aims.firstOrNull { it.id == id && id.isNotBlank() }
        if (old == null) {
            if (t.isEmpty()) return Result.failure(IllegalArgumentException("目标要有个名字"))
            if (open(book).size >= MAX) return Result.failure(IllegalArgumentException("已经挂了 $MAX 个长期目标——先放下一个（done）再加"))
            val a = Aim(newId(), t, why.trim(), deadline?.toString().orEmpty(), now, threads.orEmpty())
            return Result.success(book.copy(aims = (book.aims + a).takeLast(30)))
        }
        val next = old.copy(
            title = t.ifEmpty { old.title },
            why = why.trim().ifEmpty { old.why },
            deadline = deadline?.toString() ?: old.deadline,
            threads = threads ?: old.threads,
        )
        return Result.success(book.copy(aims = book.aims.map { if (it.id == old.id) next else it }))
    }

    fun close(book: PlanBook, id: String, now: Long): Result<PlanBook> {
        val a = book.aims.firstOrNull { it.id == id } ?: return Result.failure(IllegalArgumentException("没有这个目标：$id"))
        if (a.done) return Result.failure(IllegalArgumentException("已经放下了"))
        return Result.success(book.copy(aims = book.aims.map { if (it.id == id) it.copy(done = true, closedAt = now) else it }))
    }

    fun remove(book: PlanBook, id: String): PlanBook = book.copy(aims = book.aims.filterNot { it.id == id })

    /** 给模型看的一段。 */
    fun brief(book: PlanBook, work: Work, now: Long, zone: ZoneId): String {
        val list = open(book)
        if (list.isEmpty()) return "还没有长期目标（求职、考试这类跨好几周的事）。用户提到时用 set_aim 挂上去。"
        return buildString {
            append("长期目标（挂在首页上方，月相 = 时间走了多少）：\n")
            list.forEach { a ->
                val left = daysLeft(a, now, zone)
                val steps = recentSteps(a, work, now)
                append("· [${a.id}] ${a.title}｜${left?.let { if (it >= 0) "还剩 $it 天" else "已过期 ${-it} 天" } ?: "没有期限"}")
                if (a.why.isNotBlank()) append("｜为什么：${a.why.take(40)}")
                if (a.threads.isEmpty()) append("｜没关联事项")
                else append("｜近 7 天落在它上面 $steps 步")
                append('\n')
            }
            val idle = list.filter { it.threads.isNotEmpty() && recentSteps(it, work, now) == 0 }
            if (idle.isNotEmpty()) append("一周没有一步落在：${idle.joinToString("、") { it.title }}。排周计划时提醒用户给它留一样能检查的产出。\n")
        }
    }
}
