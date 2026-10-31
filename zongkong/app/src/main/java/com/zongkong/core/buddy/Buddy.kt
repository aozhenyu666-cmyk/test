package com.zongkong.core.buddy

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * 伙伴：住在总控里、但不是总控的那个会主动找你说话的 AI。
 *
 * 为什么要它：总控是水坝——定规矩、拦、验收，你对它天然有防备。伙伴是河道的弯：
 * 不管规则、不锁手机、不打分，只是隔一会儿来跟你说两句，顺手把话头往你自己想做的事上带一点。
 *
 * 什么时候开口，照着那几个「大肥鱼」插件的做法（dsh-speak-up / dafeiyu 主动联系协调 / bili-whale 的克制）：
 *  - 每次只掷一次本地骰子，掷中才花一次模型调用；
 *  - 每小时、每天都有上限；两次之间有最短间隔，你连着没回就把间隔翻倍（最多 8 倍），回了就恢复；
 *  - 你正在和总控说话、在总控时间里、屏幕灭着、在安静时段：都不开口（让路）；
 *  - 有事由时（刚解锁、连着刷了很久、一手刚有题没接住、早上、睡前）更容易开口，并且带着事由开口。
 *
 * 这里全是纯逻辑，不碰 Android、不联网，单元测试直接跑。
 */
@Serializable
data class BuddyConfig(
    val enabled: Boolean = true,
    val name: String = "小鲸",
    /** 每小时最多主动说几次 */
    val perHour: Int = 4,
    /** 每天最多主动说几次 */
    val dailyMax: Int = 40,
    /** 两次主动开口之间至少隔几分钟（你没回时按 2、4、8 倍拉长） */
    val minGapMin: Int = 8,
    /** 安静时段（HH:mm），跨午夜也行 */
    val quietStart: String = "23:30",
    val quietEnd: String = "07:30",
    /** 你想让它多知道的、说话方式的偏好（自由写） */
    val note: String = "",
)

@Serializable
data class BuddyMsg(
    val role: String,
    val text: String,
    val at: Long,
    /** user / speakup:<话题> / reply */
    val origin: String = "",
) {
    companion object {
        const val USER = "user"
        const val BUDDY = "buddy"
    }
}

@Serializable
data class BuddyBook(
    val config: BuddyConfig = BuddyConfig(),
    val messages: List<BuddyMsg> = emptyList(),
    /** 长期记住的事（它自己记的，你可以删） */
    val facts: List<String> = emptyList(),
    /** 它主动开口的时刻（留最近 24 小时） */
    val spokeAt: List<Long> = emptyList(),
    /** 它主动说了、你还没回的次数 */
    val unanswered: Int = 0,
    val lastUserAt: Long = 0,
    /** 已经聊过的一手失手（只聊一次） */
    val missSeenAt: Long = 0,
)

/** 此刻的情况：由安卓那边收集好再交进来。 */
data class Signals(
    val now: Long,
    val zone: ZoneId,
    /** 屏幕亮着（灭屏不说，免得在兜里震） */
    val interactive: Boolean = true,
    /** 总控时间进行中 */
    val hqActive: Boolean = false,
    /** 你最后一次跟总控说话的时刻（0 = 没有） */
    val lastZongkongChatAt: Long = 0,
    /** 最近一次解锁的时刻（0 = 不知道） */
    val unlockedAt: Long = 0,
    /** 前台应用的名字和连续用了几分钟 */
    val appName: String = "",
    val appMinutes: Int = 0,
    /** 这个前台应用是不是娱乐应用（在拦截名单里） */
    val appIsPlay: Boolean = false,
    /** 一手最近没接住的一题（时刻 + 一句描述），没有为 0 / 空 */
    val missAt: Long = 0,
    val miss: String = "",
)

enum class Topic(val key: String) {
    UNLOCK("unlock"),
    BINGE("binge"),
    MISS("miss"),
    MORNING("morning"),
    NIGHT("night"),
    FOLLOW("follow"),
    IDLE("idle"),
}

data class Decision(val topic: Topic, val why: String)

object Buddy {
    const val HISTORY = 16
    const val KEEP_MESSAGES = 200
    const val MAX_FACTS = 30
    private const val HOUR = 3_600_000L
    private const val MIN = 60_000L

    /** 现在要求的最短间隔（毫秒）：没回一次翻一倍，最多 8 倍。 */
    fun gapMs(book: BuddyBook): Long = book.config.minGapMin.coerceAtLeast(1) * MIN * (1L shl book.unanswered.coerceIn(0, 3))

    fun quiet(cfg: BuddyConfig, now: Long, zone: ZoneId): Boolean {
        val t = Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
        val s = minutes(cfg.quietStart) ?: return false
        val e = minutes(cfg.quietEnd) ?: return false
        if (s == e) return false
        return if (s < e) t in s until e else t >= s || t < e
    }

    private fun minutes(hhmm: String): Int? {
        val p = hhmm.trim().split(':')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** 不管骰子，先看能不能开口；不能就说为什么（给界面看）。null = 可以。 */
    fun blocked(book: BuddyBook, s: Signals): String? {
        val c = book.config
        val now = s.now
        return when {
            !c.enabled -> "关着"
            !s.interactive -> "屏幕没亮"
            quiet(c, now, s.zone) -> "安静时段"
            s.hqActive -> "总控时间里，让路"
            s.lastZongkongChatAt > 0 && now - s.lastZongkongChatAt < 5 * MIN -> "你正在和总控说话，让路"
            book.lastUserAt > 0 && now - book.lastUserAt < 3 * MIN -> "正在聊，不另起话头"
            book.spokeAt.count { now - it < HOUR } >= c.perHour.coerceAtLeast(1) -> "这一小时说够了"
            book.spokeAt.count { sameDay(it, now, s.zone) } >= c.dailyMax.coerceAtLeast(1) -> "今天说够了"
            (book.spokeAt.maxOrNull() ?: 0L).let { it > 0 && now - it < gapMs(book) } ->
                "离上次不到 ${gapMs(book) / MIN} 分钟" + if (book.unanswered > 0) "（你 ${book.unanswered} 次没回，间隔拉长了）" else ""
            else -> null
        }
    }

    /**
     * 这一刻开不开口、聊什么。[roll] 是 0..1 的随机数（测试里传固定值）。
     * 调用频率按每 5 分钟一次设计：没有事由时，掷中的概率让每小时大约说到上限的一半。
     */
    fun decide(book: BuddyBook, s: Signals, roll: Double): Decision? {
        if (blocked(book, s) != null) return null
        val now = s.now
        val hour = Instant.ofEpochMilli(now).atZone(s.zone).hour
        val saidToday = book.messages.any { it.role == BuddyMsg.BUDDY && sameDay(it.at, now, s.zone) }
        val nightSaid = book.messages.any { it.origin == "speakup:${Topic.NIGHT.key}" && sameDay(it.at, now, s.zone) }
        val lastUser = book.messages.lastOrNull { it.role == BuddyMsg.USER }
        val candidates = buildList {
            if (hour in 6..9 && !saidToday) add(Decision(Topic.MORNING, "今天第一次见") to 0.9)
            if (s.missAt > book.missSeenAt && now - s.missAt < 2 * HOUR && s.miss.isNotBlank()) add(Decision(Topic.MISS, "一手刚有一题没接住") to 0.5)
            if (s.unlockedAt > 0 && now - s.unlockedAt in 20_000L..(4 * MIN)) add(Decision(Topic.UNLOCK, "刚解锁") to 0.55)
            if (s.appIsPlay && s.appMinutes >= 15) add(Decision(Topic.BINGE, "在「${s.appName}」里 ${s.appMinutes} 分钟了") to 0.45)
            if (hour in 21..23 && !nightSaid) add(Decision(Topic.NIGHT, "睡前") to 0.5)
            if (lastUser != null && now - lastUser.at in (30 * MIN)..(6 * HOUR)) add(Decision(Topic.FOLLOW, "接着上次你说的") to 0.15)
            val idle = (book.config.perHour.coerceIn(1, 12) / 12.0 * 0.5).coerceAtMost(0.5)
            add(Decision(Topic.IDLE, "随便聊聊") to idle)
        }
        val (pick, p) = candidates.first()
        return if (roll < p) pick else null
    }

    /** 开口之后记一笔。 */
    fun spoke(book: BuddyBook, d: Decision, text: String, now: Long, missAt: Long = 0): BuddyBook = book.copy(
        messages = (book.messages + BuddyMsg(BuddyMsg.BUDDY, text, now, "speakup:${d.topic.key}")).takeLast(KEEP_MESSAGES),
        spokeAt = (book.spokeAt + now).filter { now - it < 24 * HOUR },
        unanswered = book.unanswered + 1,
        missSeenAt = if (d.topic == Topic.MISS) maxOf(book.missSeenAt, missAt) else book.missSeenAt,
    )

    /** 你说了一句：没回的清零。 */
    fun heard(book: BuddyBook, text: String, now: Long): BuddyBook = book.copy(
        messages = (book.messages + BuddyMsg(BuddyMsg.USER, text, now, "user")).takeLast(KEEP_MESSAGES),
        unanswered = 0,
        lastUserAt = now,
    )

    fun replied(book: BuddyBook, text: String, now: Long): BuddyBook = book.copy(
        messages = (book.messages + BuddyMsg(BuddyMsg.BUDDY, text, now, "reply")).takeLast(KEEP_MESSAGES),
    )

    fun remember(book: BuddyBook, facts: List<String>): BuddyBook {
        if (facts.isEmpty()) return book
        val all = (book.facts.filterNot { it in facts } + facts).takeLast(MAX_FACTS)
        return book.copy(facts = all)
    }

    // ---------- 说什么 ----------

    /** 伙伴的人设。关键是它「不是总控」：不审判、不下命令，只是一个了解你的朋友。 */
    fun system(cfg: BuddyConfig, facts: List<String>, context: String): String = buildString {
        append("你叫「${cfg.name}」，是用户的伙伴。你住在他手机上的「总控」App 里，但你不是总控。\n")
        append("总控负责规则、拦截、验收；你不管这些：你不下命令、不验收、不打分、不说教、不威胁，也不替总控执法。\n")
        append("你像一个了解他的朋友：好奇、温和、有时调皮。说话短，一到三句，口语，不用清单、小标题、表情包堆砌。\n")
        append("你可以顺着聊天，把话头轻轻往他自己想做的事上带一点——像河道拐个弯，不像水坝；他不想聊就收住，换个轻松的。\n")
        append("他在刷手机时不要批评他，可以问他刷到了什么有意思的，或者聊聊他今天想做的那件事卡在哪。\n")
        append("不知道的不要编。不要每句都提任务。一次只问一个问题。\n")
        append("需要长期记住的关于他的事实（喜好、近况、约定），单独一行写：【记住】……\n")
        append("他明确让你转告总控的事（比如他答应了什么），单独一行写：【告诉总控】……\n")
        if (cfg.note.isNotBlank()) append("他对你说话方式的要求：").append(cfg.note.trim()).append('\n')
        if (facts.isNotEmpty()) {
            append("\n你记得的关于他的事：\n")
            facts.forEach { append("- ").append(it).append('\n') }
        }
        if (context.isNotBlank()) append("\n此刻的情况（只供你参考，别照念）：\n").append(context.trim()).append('\n')
    }

    /** 主动开口时，替用户那一侧塞的一句说明（不显示给用户）。 */
    fun opener(d: Decision, s: Signals): String = "[你主动开口 · 事由：${d.why}] " + when (d.topic) {
        Topic.MORNING -> "早上第一次跟他打招呼。随口问问今天最想做成的一件事，别像点名。"
        Topic.MISS -> "一手（他刷题的 App）里他刚有一题没接住：${s.miss.take(120)}。像朋友一样提一句，问他当时卡在哪，别批评。"
        Topic.UNLOCK -> "他刚拿起手机。轻轻问一句他现在想干嘛，或者接着上次的话头。"
        Topic.BINGE -> "他在「${s.appName}」里已经 ${s.appMinutes} 分钟了。别催别批评：问问刷到了什么有意思的，顺带提一下他今天想做的事（如果知道）。"
        Topic.NIGHT -> "快睡了。问问今天哪件事最值得记一下，或者明天醒来先做什么。语气放松。"
        Topic.FOLLOW -> "接着他上次跟你说的话头，问一句后来怎么样了。"
        Topic.IDLE -> "没有特别的事由，像朋友随口找他说一句：可以分享一个小想法、问他此刻在想什么，或者聊他今天的那件事。"
    }

    data class Parsed(val text: String, val remember: List<String>, val toZongkong: List<String>)

    private val REMEMBER = Regex("""^\s*【记住】\s*(.+)$""")
    private val TELL = Regex("""^\s*【告诉总控】\s*(.+)$""")

    /** 把模型的回复拆开：给用户看的话、要记住的事、要转告总控的事。 */
    fun parse(reply: String): Parsed {
        val keep = mutableListOf<String>()
        val rem = mutableListOf<String>()
        val tell = mutableListOf<String>()
        reply.lines().forEach { line ->
            REMEMBER.find(line)?.let { rem += it.groupValues[1].trim(); return@forEach }
            TELL.find(line)?.let { tell += it.groupValues[1].trim(); return@forEach }
            keep += line
        }
        return Parsed(keep.joinToString("\n").trim(), rem.filter { it.isNotBlank() }, tell.filter { it.isNotBlank() })
    }

    /** 最近几条对话（发给模型的历史）。 */
    fun history(book: BuddyBook, n: Int = HISTORY): List<BuddyMsg> = book.messages.takeLast(n)

    private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
