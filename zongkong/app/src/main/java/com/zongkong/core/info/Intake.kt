package com.zongkong.core.info

import com.zongkong.core.work.F
import com.zongkong.core.work.NoteType
import com.zongkong.core.work.Rec
import com.zongkong.core.work.Work
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * 信息收集部的“漏斗”：收得进，更要消化得了。
 *
 *  1. 三问：这是什么（一句话收获）、给谁用（挂到哪件事 / 脉络哪个节点）、什么时候过期（保鲜期）
 *  2. 状态：新收 → 已提炼（写了一句话收获）→ 已归位（挂到事项或脉络）；过了保鲜期还没处理 → 过期
 *  3. 节食：每天新收上限、积压上限；超了先消化再收
 *  4. 来源账本：每个来源收了多少、用上多少——有用率低的来源该断
 */
@Serializable
data class IntakeMeta(
    val gist: String = "",
    /** 归位到哪：thread:键 / vein:节点 id。 */
    val placedTo: String = "",
    val distilledAt: Long = 0,
    val placedAt: Long = 0,
    /** 主动丢弃（不算过期）。 */
    val dropped: Boolean = false,
)

@Serializable
data class IntakeBook(
    val metas: Map<String, IntakeMeta> = emptyMap(),
    val dailyCap: Int = 10,
    val backlogCap: Int = 15,
)

enum class IntakeState(val label: String) { NEW("新收"), DISTILLED("已提炼"), PLACED("已归位"), EXPIRED("过期"), DROPPED("丢了") }

data class IntakeItem(val rec: Rec, val state: IntakeState, val gist: String, val source: String, val daysLeft: Int)

data class IntakeStats(
    val todayIn: Int,
    val backlog: Int,
    val distilled: Int,
    val placed: Int,
    val expired: Int,
    val dailyCap: Int,
    val backlogCap: Int,
) {
    val overDaily: Boolean get() = todayIn > dailyCap
    val overBacklog: Boolean get() = backlog > backlogCap
    /** 潮位：积压 / 上限。 */
    val level: Float get() = (backlog.toFloat() / backlogCap.coerceAtLeast(1)).coerceIn(0f, 1.2f)
}

data class SourceStat(val source: String, val total: Int, val distilled: Int, val placed: Int) {
    /** 有用率：提炼或归位的占比。 */
    val useful: Int get() = if (total == 0) 0 else (placed * 100 + (distilled - placed).coerceAtLeast(0) * 50) / total
}

object Intake {
    private val KINDS = setOf(NoteType.MATERIAL, NoteType.IDEA, NoteType.QUESTION)

    /** 保鲜期（天）：念头 3 天、材料 7 天、问题 14 天。 */
    fun freshDays(t: NoteType): Int = when (t) {
        NoteType.IDEA -> 3
        NoteType.QUESTION -> 14
        else -> 7
    }

    private val HOSTS = listOf(
        "weixin.qq.com" to "公众号", "bilibili.com" to "B站", "b23.tv" to "B站", "zhihu.com" to "知乎", "xiaohongshu.com" to "小红书",
        "xhslink.com" to "小红书", "douyin.com" to "抖音", "weibo" to "微博", "chatgpt.com" to "ChatGPT", "openai.com" to "ChatGPT",
        "claude.ai" to "Claude", "youtube.com" to "YouTube", "youtu.be" to "YouTube", "github.com" to "GitHub", "douban.com" to "豆瓣",
        "toutiao.com" to "头条", "kimi" to "Kimi", "doubao" to "豆包",
    )

    /** 来源：看链接的网站；没有链接就看是谁写的（GPT / 总控 / 自己）。 */
    fun sourceOf(r: Rec): String {
        val url = r[F.URL].lowercase()
        if (url.isNotBlank()) {
            HOSTS.firstOrNull { url.contains(it.first) }?.let { return it.second }
            return Regex("""https?://(?:www\.)?([^/]+)""").find(url)?.groupValues?.get(1) ?: "链接"
        }
        val raw = r[F.RAW].lowercase()
        HOSTS.firstOrNull { raw.contains(it.first) }?.let { return it.second }
        return when (r[F.SOURCE]) {
            "GPT" -> "ChatGPT"
            "" , "总控" -> "自己"
            else -> r[F.SOURCE]
        }
    }

    fun items(work: Work, book: IntakeBook, now: Long): List<IntakeItem> = work.notes().filter { it.noteType in KINDS }.map { r ->
        val m = book.metas[r.key] ?: IntakeMeta()
        val days = freshDays(r.noteType)
        val left = days - ((now - r.createdAt) / 86_400_000L).toInt()
        val state = when {
            m.dropped -> IntakeState.DROPPED
            r.threadKey.isNotBlank() || m.placedTo.isNotBlank() -> IntakeState.PLACED
            m.gist.isNotBlank() -> IntakeState.DISTILLED
            left < 0 -> IntakeState.EXPIRED
            else -> IntakeState.NEW
        }
        IntakeItem(r, state, m.gist, sourceOf(r), left)
    }.sortedByDescending { it.rec.createdAt }

    fun stats(work: Work, book: IntakeBook, now: Long, zone: ZoneId): IntakeStats {
        val all = items(work, book, now)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return IntakeStats(
            todayIn = all.count { Instant.ofEpochMilli(it.rec.createdAt).atZone(zone).toLocalDate() == today },
            backlog = all.count { it.state == IntakeState.NEW },
            distilled = all.count { it.state == IntakeState.DISTILLED },
            placed = all.count { it.state == IntakeState.PLACED },
            expired = all.count { it.state == IntakeState.EXPIRED },
            dailyCap = book.dailyCap, backlogCap = book.backlogCap,
        )
    }

    fun sources(work: Work, book: IntakeBook, now: Long): List<SourceStat> = items(work, book, now)
        .groupBy { it.source }.map { (s, list) ->
            SourceStat(s, list.size, list.count { it.state == IntakeState.DISTILLED || it.state == IntakeState.PLACED }, list.count { it.state == IntakeState.PLACED })
        }.sortedByDescending { it.total }

    fun distill(book: IntakeBook, key: String, gist: String, now: Long): IntakeBook {
        val m = book.metas[key] ?: IntakeMeta()
        return book.copy(metas = book.metas + (key to m.copy(gist = gist.trim(), distilledAt = now)))
    }

    fun place(book: IntakeBook, key: String, to: String, now: Long): IntakeBook {
        val m = book.metas[key] ?: IntakeMeta()
        return book.copy(metas = book.metas + (key to m.copy(placedTo = to, placedAt = now)))
    }

    fun drop(book: IntakeBook, key: String): IntakeBook {
        val m = book.metas[key] ?: IntakeMeta()
        return book.copy(metas = book.metas + (key to m.copy(dropped = true)))
    }

    /** 给总控看的一段：漏斗现在怎样、该断哪个来源。 */
    fun brief(work: Work, book: IntakeBook, now: Long, zone: ZoneId): String {
        val s = stats(work, book, now, zone)
        return buildString {
            append("今天新收 ${s.todayIn}/${s.dailyCap}，积压没提炼的 ${s.backlog}/${s.backlogCap}，已提炼 ${s.distilled}，已归位 ${s.placed}，过期 ${s.expired}。")
            if (s.overDaily) append("今天收太多了，先消化再收。")
            if (s.overBacklog) append("积压超了上限。")
            val bad = sources(work, book, now).filter { it.total >= 5 && it.useful < 20 }
            if (bad.isNotEmpty()) append("有用率很低的来源：" + bad.joinToString("、") { "${it.source}（收 ${it.total} 用 ${it.placed}）" } + "。")
        }
    }
}
