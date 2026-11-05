package com.yishou.app.act

import com.yishou.app.judge.Guard
import com.yishou.app.think.Words

/**
 * 陪练流：学习时一直有个“对面的人”，每隔一两分钟抛一句具体的话，你用几个字或一句语音接住。
 * 它替代的是“一直开着 Gemini Live”——但要补上 Live 最大的缺口：它看不到你此刻在想什么，只能说空话。
 *
 * 所以每一拍都只用三样“此刻”的东西出题：
 *  1. 屏幕上现在是什么（截一帧，识图成文字；没变就不重截）；
 *  2. 你刚才嘴里说了什么（边做边念叨，离线听写成文字，只留最近一两分钟）；
 *  3. 你定的下一步动作是什么（专注流 / 总控的事项）。
 * 出的那句话必须引用前两样里的原词（本机核对），引不出来就不发——宁可不说，也不说空话。
 *
 * 这里是纯逻辑：这一拍要不要说、说哪一种、间隔怎么调。截屏、听写、出题请求由安卓那边做。
 */
enum class Beat(val label: String) {
    /** 不打扰：你正在说、正在动，或者刚接住上一句 */
    QUIET("不打扰"),
    /** 针对屏幕或你刚说的话，问一个具体问题 */
    ASK("追问"),
    /** 屏幕没动、你也没出声：问卡在哪一步 */
    NUDGE("推一下"),
    /** 切去了任务外的应用：拉回来，带上断点 */
    REFOCUS("拉回"),
    /** 每隔一段：用一句话说这一段得出了什么，存成断点 */
    CHECKPOINT("收一句"),
    /** 累了 / 你按了暂停：这一段不说话 */
    PAUSED("暂停"),
}

data class PulseState(
    val startedAt: Long,
    val lastPromptAt: Long = 0,
    val lastAnsweredAt: Long = 0,
    val lastCheckpointAt: Long = 0,
    /** 当前的出题间隔（会随你的状态伸缩） */
    val intervalMs: Long = Pulse.BASE_MS,
    /** 最近出过的几拍时间，用来限流 */
    val recentPrompts: List<Long> = emptyList(),
    /** 连着几拍没接 */
    val misses: Int = 0,
    val pausedUntil: Long = 0,
)

data class PulseSignals(
    val now: Long,
    /** 你现在是不是正在说话（静音检测） */
    val speaking: Boolean,
    /** 最近一次出声的时间 */
    val lastSpeechAt: Long,
    /** 屏幕最近一次变化的时间（截帧比对） */
    val lastScreenChangeAt: Long,
    /** 前台应用是不是任务里允许的 */
    val onTask: Boolean,
    /** 上一拍还没回（问题挂着） */
    val waitingAnswer: Boolean,
)

object Pulse {
    const val BASE_MS = 90_000L
    const val MIN_MS = 45_000L
    const val MAX_MS = 180_000L
    /** 十分钟里最多说几次：再多就成了噪音，人会开始“划掉” */
    const val MAX_PER_10_MIN = 6
    /** 说完话这么久之内不插嘴：给想到一半的人留口气 */
    const val SPEECH_GRACE_MS = 12_000L
    /** 屏幕不动、人不出声这么久，算卡住 */
    const val STILL_MS = 60_000L
    const val CHECKPOINT_MS = 10 * 60_000L

    fun decide(s: PulseState, x: PulseSignals): Beat {
        val now = x.now
        if (now < s.pausedUntil) return Beat.PAUSED
        // 跑题最优先：切去别的应用马上拉回（不受限流）
        if (!x.onTask) return Beat.REFOCUS
        // 正在说、刚说完：不插嘴
        if (x.speaking || now - x.lastSpeechAt < SPEECH_GRACE_MS) return Beat.QUIET
        // 上一句还挂着没回：不叠新问题（没回的处理交给 Stall）
        if (x.waitingAnswer) return Beat.QUIET
        if (s.recentPrompts.count { now - it < 10 * 60_000L } >= MAX_PER_10_MIN) return Beat.QUIET
        if (now - maxOf(s.lastCheckpointAt, s.startedAt) >= CHECKPOINT_MS) return Beat.CHECKPOINT
        val stillFor = now - maxOf(x.lastScreenChangeAt, x.lastSpeechAt)
        if (stillFor >= STILL_MS && now - s.lastPromptAt >= STILL_MS) return Beat.NUDGE
        if (now - maxOf(s.lastPromptAt, s.startedAt) >= s.intervalMs) return Beat.ASK
        return Beat.QUIET
    }

    /** 发出了一拍 */
    fun prompted(s: PulseState, beat: Beat, now: Long): PulseState = s.copy(
        lastPromptAt = now,
        recentPrompts = (s.recentPrompts + now).filter { now - it < 10 * 60_000L },
        lastCheckpointAt = if (beat == Beat.CHECKPOINT) now else s.lastCheckpointAt,
    )

    /**
     * 接住了：间隔拉长一点（顺的时候少打扰）；
     * 没接：间隔缩短一点，连着三拍没接就交给 [Stall] 判断是累了、卡了还是在躲。
     */
    fun answered(s: PulseState, now: Long): PulseState =
        s.copy(lastAnsweredAt = now, misses = 0, intervalMs = (s.intervalMs * 5 / 4).coerceAtMost(MAX_MS))

    fun missed(s: PulseState): PulseState =
        s.copy(misses = s.misses + 1, intervalMs = (s.intervalMs * 3 / 4).coerceAtLeast(MIN_MS))

    fun pause(s: PulseState, until: Long): PulseState = s.copy(pausedUntil = until, misses = 0, intervalMs = BASE_MS)

    /** 连着这么多拍没接，就该用 Stall 读一下原因了 */
    fun shouldReadStall(s: PulseState) = s.misses >= 3
}

/**
 * 每一拍的提示词，以及“这句话够不够具体”的本机核对。
 */
object PulsePrompt {

    val SYSTEM = """
你是坐在用户旁边陪他学习的人。你看到的是他屏幕上的文字和他刚才自言自语的话。
每次只说一句，不超过 30 个字。规则：
1. 必须引用屏幕上或他刚说的话里的原词（用「」括起来），问一个他马上能回答的具体问题。
2. 不讲道理、不鼓励、不总结、不说“加油”“注意”“认真”。
3. 他刚说了一个判断：问他凭什么（原文哪一句）。他在两个选项间犹豫：问其中一个错在哪几个字。
   他半天没出声：问他卡在哪一步，给两个可能让他选。他在读材料：让他先预测下一段或答案长什么样。
4. 跟他定的下一步动作无关的内容，不要接。
只回那一句话。
""".trim()

    fun user(beat: Beat, task: String, screenText: String, transcript: String, lastQuestion: String?): String = buildString {
        appendLine("他在做：$task")
        appendLine("这一拍：${beat.label}")
        if (screenText.isNotBlank()) appendLine("屏幕上：${screenText.take(600)}")
        if (transcript.isNotBlank()) appendLine("他刚才说：${transcript.takeLast(300)}")
        if (!lastQuestion.isNullOrBlank()) appendLine("你上一句问的是：$lastQuestion（别重复）")
    }

    private val QUOTE = Regex("「([^」]{2,40})」")

    /**
     * 这句话够不够具体：要有「」引用，且引用的东西真的在屏幕或他刚说的话里；不超过 40 字；不是空话。
     * 不合格返回 null（不发，换本机的兜底句）。
     */
    fun accept(line: String, screenText: String, transcript: String): String? {
        val t = line.trim().removeSurrounding("\"").lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (Words.norm(t).length > 40) return null
        val quotes = QUOTE.findAll(t).map { it.groupValues[1] }.toList()
        if (quotes.isEmpty()) return null
        val sources = listOf(screenText, transcript).filter { it.isNotBlank() }
        if (sources.isEmpty()) return null
        if (quotes.none { q -> sources.any { Words.quoted(it, q) } }) return null
        if (EMPTY.any { it in t }) return null
        return t
    }

    private val EMPTY = listOf("加油", "注意", "认真", "坚持", "不要分心", "专注", "继续努力")

    /**
     * 模型不在、或者给的话不合格时，本机兜底：从你刚说的话或屏幕里挑一个词来问。
     * 宁可朴素，也要具体。
     */
    fun fallback(beat: Beat, screenText: String, transcript: String, nextAction: String): String {
        val anchor = pickAnchor(transcript) ?: pickAnchor(screenText)
        return when (beat) {
            Beat.REFOCUS -> "回来：你定的是「${nextAction.take(20)}」，现在做到哪一步了？"
            Beat.CHECKPOINT -> "收一句：这十分钟你得出了什么？"
            Beat.NUDGE -> if (anchor != null) "卡在「$anchor」这里了吗？是看不懂，还是在两个答案之间犹豫？" else "卡在哪一步了？是看不懂，还是在犹豫？"
            else -> if (anchor != null) "「$anchor」——凭原文哪一句？" else "你现在的判断是什么？凭哪一句？"
        }
    }

    /** 挑一个最近出现的、像实词的片段（4–10 个字）；句子太长就取它最后一截（原文里连着的那几个字） */
    private fun pickAnchor(text: String): String? {
        val parts = text.split(Regex("[，。！？、；：,.!?;:\\s]+")).map { it.trim() }.filter { Words.substance(it) >= 4 }
            .map { if (Words.substance(it) > 10) it.takeLast(8) else it }
        return parts.lastOrNull { Guard.novel(it, emptyList()) >= 4 }
    }
}
