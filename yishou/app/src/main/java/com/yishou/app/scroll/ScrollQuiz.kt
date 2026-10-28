package com.yishou.app.scroll

import com.yishou.app.llm.Tagged

/**
 * 刷着练：刷抖音、贴吧时，截下这一屏（连拍三帧看清字幕），问一个跟这条内容有关的小问题，
 * 在通知栏里直接回一句，判一下，接着刷。
 *
 * 不读整段视频（拿不到，也太贵）：画面 + 标题 + 字幕 + 露出来的评论，足够问一个好问题。
 * 这里是纯逻辑：角度轮换、提示词、解析、自动出题的节奏。
 */
enum class Lens(val label: String, val hint: String) {
    CLAIM("主张", "它想让你相信的那一句是什么？用自己的话说成一句"),
    GAP("缺口", "这个说法要成立，偷偷靠着哪个没说出口的前提"),
    PREDICT("预判", "先别往下划：评论区最多的反应会是什么、为什么（答完可以去评论区对一下）"),
    MOTIVE("动机", "发这条的人想从看的人那里拿走什么：时间、情绪、钱、站队"),
    COUNTER("反例", "举一个它的说法不成立的情况"),
    EVIDENCE("证据", "要信它，得先看到什么证据；它给了没有"),
    ;

    companion object {
        fun of(label: String?): Lens? = entries.firstOrNull { label != null && label.contains(it.label) }
        fun at(index: Int): Lens = entries[Math.floorMod(index, entries.size)]
    }
}

/** 这一屏出的一问。seen：屏幕上看到了什么（给你认出是哪条）；text：屏幕上的原文，收进素材池用。 */
data class Quiz(
    val seen: String,
    val question: String,
    val lens: Lens,
    val reference: String,
    val text: String = "",
)

sealed interface Look {
    data class Ask(val quiz: Quiz) : Look
    data class Skip(val reason: String) : Look
}

enum class Verdict(val label: String) {
    HIT("接住了"),
    NEAR("差一点"),
    MISS("没接住"),
}

data class Judged(val verdict: Verdict, val line: String)

object ScrollQuiz {

    const val SEEN_MAX = 60
    const val QUESTION_MAX = 40
    const val REFERENCE_MAX = 80
    const val TEXT_MAX = 600

    private val NAMES = setOf("看到", "原文", "角度", "问题", "参考", "跳过")

    /** 识图提示词：三帧是同一屏幕隔一秒左右连拍的，用来看清字幕和滚动的字。 */
    fun lookSystem(lens: Lens): String = """
你是一个陪人刷手机时练思维的教练。图里是用户手机屏幕同一时刻前后连拍的几帧（从左到右，隔一秒左右），用来看清字幕和标题。
他正在刷短视频、帖子或评论。你只根据屏幕上真的能看到的字和画面出题，不要编屏幕上没有的内容。

这次的角度：${lens.label}——${lens.hint}。
这个角度明显不合适时，可以换成下面一个：${Lens.entries.joinToString("、") { it.label }}。

照这个格式回，不要别的话：
【看到】一句话说这一屏是什么（谁、在说什么），${SEEN_MAX}字以内，让他一眼认出是哪条
【原文】屏幕上的标题、文案、字幕、露出来的评论，照抄，去掉按钮、点赞数、广告；没有就写“无”
【角度】用的角度名
【问题】问他的一句话，${QUESTION_MAX}字以内；要具体到这一条，不要泛泛的“你怎么看”；不要把答案带进问题里
【参考】你心里的参考答案，${REFERENCE_MAX}字以内，先不给他看

屏幕上没什么可想的（桌面、设置、聊天列表、纯广告、加载中、看不清）就只回：
【跳过】原因，十个字以内
""".trim()

    const val LOOK_USER = "这是我此刻的手机屏幕，按格式出一问。"

    fun parseLook(text: String, asked: Lens): Look? {
        val s = Tagged.sections(text, NAMES)
        s["跳过"]?.let { return Look.Skip(it.joinToString("").trim().ifEmpty { "这屏没什么可问的" }.take(20)) }
        val question = s["问题"]?.joinToString("")?.trim().orEmpty()
        if (question.length < 4) return null
        val seen = s["看到"]?.joinToString("")?.trim().orEmpty()
        val reference = s["参考"]?.joinToString("")?.trim().orEmpty()
        val raw = s["原文"]?.joinToString("\n")?.trim().orEmpty()
        val lens = Lens.of(s["角度"]?.joinToString("")) ?: asked
        return Look.Ask(
            Quiz(
                seen = seen.take(SEEN_MAX),
                question = question.take(QUESTION_MAX + 20),
                lens = lens,
                reference = reference.take(REFERENCE_MAX + 40),
                text = if (raw == "无" || raw == "没有") "" else raw.take(TEXT_MAX),
            ),
        )
    }

    private val GIVE_UP = Regex("^(不知道|不会|不清楚|没想法|想不出|不懂|跳过|算了|不想|pass|\\?|？)+$", RegexOption.IGNORE_CASE)

    /** 不用问模型就能判的：空着、说不知道 */
    fun quick(answer: String): Judged? {
        val a = answer.trim().trimEnd('。', '.', '!', '！')
        if (a.isEmpty() || GIVE_UP.matches(a)) return Judged(Verdict.MISS, "说不出也算一手。看看参考，下回先想这个角度。")
        if (a.length < 3) return Judged(Verdict.MISS, "太短了，说成一句完整的话。")
        return null
    }

    fun judgeSystem(): String = """你在陪人刷手机时练思维。他刚刷到一条内容，你问了一句，他在通知栏里回了一句。
判他这一句：抓住了要点就是“接住了”；方向对但太泛、少了关键一层就是“差一点”；答偏了、只复述了内容就是“没接住”。
不要求和参考答案一样，说得通、有自己的判断就算。

照这个格式回，不要别的话：
【判】接住了 / 差一点 / 没接住
【一句】40字以内：接住了就点出他好在哪；否则说缺的那一层是什么，别讲大道理"""

    fun judgeUser(q: Quiz, answer: String): String = buildString {
        if (q.seen.isNotBlank()) appendLine("他刷到的：${q.seen}")
        if (q.text.isNotBlank()) appendLine("屏幕上的字：${q.text.take(300)}")
        appendLine("角度：${q.lens.label}")
        appendLine("问他的：${q.question}")
        if (q.reference.isNotBlank()) appendLine("参考：${q.reference}")
        append("他的回答：").append(answer.trim())
    }

    fun parseJudge(text: String): Judged? {
        val s = Tagged.sections(text, setOf("判", "一句"))
        val v = s["判"]?.joinToString("")?.trim() ?: text.lineSequence().firstOrNull().orEmpty()
        val verdict = when {
            v.contains("没接住") || v.contains("没接") -> Verdict.MISS
            v.contains("差一点") || v.contains("差点") -> Verdict.NEAR
            v.contains("接住") -> Verdict.HIT
            else -> return null
        }
        val line = s["一句"]?.joinToString("")?.trim().orEmpty()
        return Judged(verdict, line.take(60))
    }

    /** 收进素材池的那段：看到 + 原文。字太少就不收。 */
    fun material(q: Quiz): String? {
        if (q.text.length < 40) return null
        return buildString {
            if (q.seen.isNotBlank()) appendLine("【刷到】${q.seen}")
            append(q.text)
        }
    }

    /** 回答通知里那一行：判定 + 一句 + 参考 */
    fun feedback(q: Quiz, j: Judged): String = buildString {
        append(j.verdict.label)
        if (j.line.isNotBlank()) append("：").append(j.line)
        if (j.verdict != Verdict.HIT && q.reference.isNotBlank()) append("\n参考：").append(q.reference)
    }

    /** 拿到 Gemini / ChatGPT 里接着聊的一段话 */
    fun deepPrompt(q: Quiz, answer: String?, j: Judged?): String = buildString {
        appendLine("我刚刷到一条内容，想借它练一下思维。你当一个会追问的教练：一次只问一个问题，等我回答再追，不要一上来给结论。")
        if (q.seen.isNotBlank()) appendLine("内容：${q.seen}")
        if (q.text.isNotBlank()) appendLine("屏幕上的字：${q.text.take(400)}")
        appendLine("问我的（${q.lens.label}）：${q.question}")
        if (!answer.isNullOrBlank()) appendLine("我的回答：$answer")
        if (j != null) appendLine("初判：${j.verdict.label}${if (j.line.isNotBlank()) "，${j.line}" else ""}")
        append("从我回答里最弱的一环开始追问。")
    }
}

/**
 * 自动出题的节奏：只在你刷关注的应用（抖音、贴吧这类）时计时，刷满 interval 分钟问一次。
 * 上一问还没回、又没过 10 分钟，就先不问；连着两问没理，间隔翻倍，免得变成骚扰。
 */
class AutoPace(intervalMinutes: Int) {
    var intervalMinutes: Int = intervalMinutes
        private set
    private var watchedMs = 0L
    private var lastTick: Long? = null
    private var pendingSince: Long? = null
    private var ignored = 0

    val backoff: Int get() = if (ignored >= 2) 2 else 1

    fun setInterval(minutes: Int) {
        intervalMinutes = minutes
        watchedMs = 0
    }

    /** 每隔一会儿调一次；返回 true 表示该问了 */
    fun tick(now: Long, watching: Boolean): Boolean {
        val last = lastTick
        lastTick = now
        if (intervalMinutes <= 0 || !watching || last == null) return false
        // 两次 tick 之间隔得太久（手机睡了）不算在刷
        // 上一问还挂着：这段时间不计；挂满 10 分钟算没理，重新计时
        pendingSince?.let {
            if (now - it < PENDING_MS) return false
            pendingSince = null
            ignored++
            watchedMs = 0
        }
        val gap = now - last
        if (gap in 1..MAX_GAP_MS) watchedMs += gap
        return watchedMs >= intervalMinutes * 60_000L * backoff
    }

    /** 出了一问（自动或手动都算），计时清零 */
    fun asked(now: Long) {
        watchedMs = 0
        pendingSince = now
    }

    /** 你回了（或主动点了看参考、跳过） */
    fun answered() {
        pendingSince = null
        ignored = 0
    }

    companion object {
        const val PENDING_MS = 10 * 60_000L
        const val MAX_GAP_MS = 60_000L
    }
}
