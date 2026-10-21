package com.yishou.app.partner

import com.yishou.app.llm.LlmResult
import com.yishou.app.think.Words
import com.yishou.app.voice.SentenceSplitter
import com.yishou.app.voice.StreamingTextModel
import com.yishou.app.voice.TurnEnd
import com.yishou.app.voice.TurnGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 嘴：说一句，播完才返回；协程被取消时立刻停声。 */
fun interface Mouth {
    suspend fun say(text: String)
}

/** 这一场在哪一段：开场、陪着、等你回答、拉回、收线、结束 */
enum class Stage { OPEN, ACCOMPANY, PROBE, PULLBACK, CLOSE, ENDED }

/** 声音这一侧在干什么 */
enum class Phase { LISTENING, THINKING, SPEAKING }

data class LiveState(
    val stage: Stage = Stage.OPEN,
    val phase: Phase = Phase.LISTENING,
    val lines: List<Turn> = emptyList(),
    val gear: Gear = Gear.ACCOMPANY,
    val missed: Int = 0,
    val remainingMs: Long = 0,
    /** 用户说了“安静”：先不主动开口，直到他再说话 */
    val quiet: Boolean = false,
    val error: String? = null,
)

/** 节奏参数：都是待真机实测的初值 */
data class Pace(
    /** 问完多久没回算没接 */
    val probeWaitMs: Long = 20_000,
    /** 每几轮用户发言请一次导演 */
    val directorEveryTurns: Int = 3,
    /** 最长多久请一次导演 */
    val directorEveryMs: Long = 90_000,
    /** 陪着的时候多久没动静主动开口一次（按强度） */
    val idleAccompanyMs: Long = 240_000,
    val idleLureMs: Long = 120_000,
    val idleStrictMs: Long = 60_000,
)

/**
 * 语音伙伴的一场会话（纯逻辑：嘴、听写结果、模型、导演、时钟都从外面接进来）。
 *
 * 一轮 = 你说完一句（[heard]）→ 文字模型边想边出字 → 切句 → 一句一句念出来。念完是个问句就进“等你回答”，
 * 20 秒没开口按阶梯走：缩短重问 → 二选一 → 记一笔“没接”，回去陪着，不纠缠。
 * 导演每 3 轮或 90 秒更新一张卡，卡只进文字模型的上下文，永远不念出来。
 * 到点由本机时钟强制收线：两问（聊出了什么、下一步做什么），写进记录。
 */
class PartnerController(
    private val plan: PartnerPlan,
    private val talker: StreamingTextModel,
    private val director: Director,
    private val mouth: Mouth,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val lastClose: String? = null,
    private val lastNext: String? = null,
    private val memory: List<String> = emptyList(),
    private val pace: Pace = Pace(),
    /** 收线两问答完：收线句、下一步、没接次数 */
    private val onClosed: suspend (line: String, next: String, missed: Int) -> Unit = { _, _, _ -> },
) {
    private val _state = MutableStateFlow(LiveState(gear = plan.gear, remainingMs = plan.minutes * 60_000L))
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val guard = TurnGuard(sessionId = 1)
    private var startedAt = 0L
    private var replyJob: Job? = null
    private var ticker: Job? = null

    private var card: DirectorCard? = null
    private var directorJob: Job? = null
    private var directorAt = 0L
    private var turnsSinceDirector = 0

    /** 最近一次伙伴问出的问题（等回答时用来缩短重问） */
    private var question: String? = null
    private var askedAt: Long? = null
    private var ladder = 0
    private var lastActivity = 0L

    private var closeStep = 0
    private var closeLine = ""
    private var closeRetried = false

    fun start() {
        startedAt = clock()
        lastActivity = startedAt
        directorAt = startedAt
        val opening = buildString {
            append("开始吧。")
            if (plan.goal.isNotBlank()) append(if (plan.source.isNotBlank()) "今天一起看「${plan.goal.trim().take(20)}」。" else "今天弄懂「${plan.goal.trim().take(20)}」。")
            append(
                when (_state.value.gear) {
                    Gear.ACCOMPANY -> "我先安静陪着，关键处再问你。"
                    Gear.LURE -> "我会时不时跟你打个赌。"
                    Gear.STRICT -> "每一段你答了我们再往下。"
                },
            )
            val next = lastNext?.trim().orEmpty()
            if (next.isNotEmpty()) append("上次你说下一步是「${next.take(20)}」，做了吗？")
        }
        speak(opening, stageAfter = if (lastNext.isNullOrBlank()) Stage.ACCOMPANY else Stage.PROBE)
        requestDirector()
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                tick()
            }
        }
    }

    /** 听写给出你说完的一句 */
    fun heard(raw: String) {
        val s = _state.value
        if (s.stage == Stage.ENDED) return
        val text = TurnEnd.strip(raw)
        if (Words.substance(text) == 0) return
        lastActivity = clock()
        askedAt = null
        ladder = 0
        _state.update { it.copy(lines = it.lines + Turn(ME, text), quiet = false, error = null) }
        if (s.stage == Stage.CLOSE) return closing(text)
        if (command(text)) return
        turnsSinceDirector++
        if (turnsSinceDirector >= pace.directorEveryTurns) requestDirector()
        _state.update { it.copy(stage = Stage.ACCOMPANY) }
        reply(text)
    }

    /** 听到你在出声（音量起来了）：等回答的计时先停，免得说到一半被当成没接 */
    fun activity() {
        lastActivity = clock()
        if (askedAt != null) askedAt = clock()
    }

    /** 点了“停”：立刻闭嘴，在路上的回复作废，转去听 */
    fun interrupt() {
        guard.invalidate()
        replyJob?.cancel()
        replyJob = null
        _state.update { it.copy(phase = Phase.LISTENING) }
    }

    /** 点了“结束并收线”或说了“今天到这” */
    fun end() {
        if (_state.value.stage == Stage.CLOSE || _state.value.stage == Stage.ENDED) return
        beginClose("好，收一下。")
    }

    /** 不收线直接停掉（关服务时） */
    fun stop() {
        interrupt()
        ticker?.cancel()
        directorJob?.cancel()
        _state.update { it.copy(stage = Stage.ENDED) }
    }

    fun setGear(gear: Gear) = _state.update { it.copy(gear = gear) }

    private fun tick() {
        val now = clock()
        val s = _state.value
        if (s.stage == Stage.ENDED) return
        val remaining = plan.minutes * 60_000L - (now - startedAt)
        _state.update { it.copy(remainingMs = remaining.coerceAtLeast(0)) }
        if (remaining <= 0 && s.stage != Stage.CLOSE) return beginClose("到点了。")
        if (s.stage == Stage.CLOSE || s.phase != Phase.LISTENING) return
        val asked = askedAt
        if (s.stage == Stage.PROBE && asked != null && now - asked >= pace.probeWaitMs) return unanswered()
        if (now - directorAt >= pace.directorEveryMs) requestDirector()
        if (s.stage == Stage.ACCOMPANY && !s.quiet && now - lastActivity >= idleMs(s.gear)) {
            lastActivity = now
            speak(card?.hook ?: Directors.local(directorInput()).hook, Stage.PROBE)
        }
    }

    private fun idleMs(gear: Gear) = when (gear) {
        Gear.ACCOMPANY -> pace.idleAccompanyMs
        Gear.LURE -> pace.idleLureMs
        Gear.STRICT -> pace.idleStrictMs
    }

    /** 问了没人回：缩短重问 → 二选一 → 记一笔，回去陪着 */
    private fun unanswered() {
        ladder++
        askedAt = null
        when (ladder) {
            1 -> speak(shorten(question.orEmpty()), Stage.PROBE)
            2 -> speak(choice(), Stage.PROBE)
            else -> {
                ladder = 0
                lastActivity = clock()
                _state.update { it.copy(stage = Stage.ACCOMPANY, missed = it.missed + 1) }
            }
        }
    }

    private fun shorten(q: String): String {
        val core = q.trim().trimEnd('？', '?')
        val last = core.split('，', ',', '。', '；').lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        return (if (last.length in 4..18) last else core.take(18)).ifEmpty { "你怎么看" } + "？"
    }

    private fun choice(): String {
        val hook = card?.hook.orEmpty()
        return if ("还是" in hook) hook else Directors.local(directorInput()).hook.takeIf { "还是" in it } ?: "成立，还是不成立？说一个字就行。"
    }

    private fun command(text: String): Boolean {
        val t = text.trim().trimEnd('。', '！', '!')
        if (t.length > 12) return false
        val negated = t.startsWith("不要") || t.startsWith("别") && "别说话" !in t && "别问" !in t
        when {
            listOf("今天到这", "收线", "结束吧", "不聊了", "就到这").any { it in t } && !negated -> { end(); return true }
            listOf("逼紧一点", "严一点", "严格一点", "逼我").any { it in t } && !negated -> {
                setGear(Gear.STRICT); speak("好，每段你答了再往下。", Stage.ACCOMPANY); return true
            }
            listOf("放松点", "轻一点", "别逼", "松一点").any { it in t } -> {
                setGear(Gear.ACCOMPANY); speak("好，我少问点。", Stage.ACCOMPANY); return true
            }
            listOf("安静", "别说话", "先别问", "闭嘴").any { it in t } -> {
                _state.update { it.copy(quiet = true, stage = Stage.ACCOMPANY) }; speak("好。", Stage.ACCOMPANY); return true
            }
            listOf("来一锅", "海龟汤").any { it in t } && !negated -> {
                speak("语音海龟汤还没接上，先去首页的海龟汤玩。", Stage.ACCOMPANY); return true
            }
            listOf("看一下", "看看屏幕", "看一眼").any { it in t } && !negated -> {
                speak("看屏幕这一步还没接上，你先念给我听。", Stage.ACCOMPANY); return true
            }
        }
        return false
    }

    /** 文字模型边出字边切句，一句一句念；只有真念出来的才算伙伴说过的话 */
    private fun reply(userText: String) {
        replyJob?.cancel()
        val tag = guard.next()
        val history = _state.value.lines.dropLast(1).takeLast(16).map { (if (it.who == ME) "user" else "assistant") to it.text }
        val system = systemPrompt()
        replyJob = scope.launch {
            _state.update { it.copy(phase = Phase.THINKING) }
            val sentences = Channel<String>(capacity = 8)
            val spoken = StringBuilder()
            val speaker = launch {
                for (s in sentences) {
                    if (!guard.isCurrent(tag)) break
                    _state.update { it.copy(phase = Phase.SPEAKING) }
                    mouth.say(s)
                    spoken.append(s)
                }
            }
            val splitter = SentenceSplitter()
            val result = talker.stream(tag, system, history, userText) { delta ->
                if (guard.isCurrent(tag)) splitter.feed(delta).forEach { sentences.send(it) }
            }
            if (guard.isCurrent(tag)) splitter.flush()?.let { sentences.send(it) }
            sentences.close()
            speaker.join()
            if (!guard.isCurrent(tag)) return@launch
            val r = result.result
            if (r is LlmResult.Err && spoken.isEmpty()) {
                _state.update { it.copy(phase = Phase.LISTENING, error = r.error.message) }
                // 模型没回上来：用导演卡的钩子顶一句，这一场不断
                val fallback = card?.hook ?: Directors.local(directorInput()).hook
                speakNow(fallback)
                afterSpeech(fallback, Stage.PROBE)
                return@launch
            }
            afterSpeech(spoken.toString(), if (looksLikeQuestion(spoken.toString())) Stage.PROBE else Stage.ACCOMPANY)
        }
    }

    private fun looksLikeQuestion(s: String) = s.trimEnd().let { it.endsWith("？") || it.endsWith("?") || it.endsWith("吗") || it.endsWith("呢") }

    /** 不经过模型直接说一句（开场、阶梯、收线） */
    private fun speak(text: String, stageAfter: Stage) {
        replyJob?.cancel()
        val tag = guard.next()
        replyJob = scope.launch {
            speakNow(text)
            if (guard.isCurrent(tag)) afterSpeech(text, stageAfter)
        }
    }

    private suspend fun speakNow(text: String) {
        _state.update { it.copy(phase = Phase.SPEAKING) }
        mouth.say(text)
    }

    private fun afterSpeech(text: String, stage: Stage) {
        if (text.isNotBlank()) _state.update { it.copy(lines = it.lines + Turn(PARTNER, text)) }
        val closing = _state.value.stage == Stage.CLOSE || _state.value.stage == Stage.ENDED
        _state.update { it.copy(phase = Phase.LISTENING, stage = if (closing) it.stage else stage) }
        lastActivity = clock()
        if (!closing && stage == Stage.PROBE) {
            question = text
            askedAt = clock()
        }
    }

    private fun systemPrompt(): String = buildString {
        appendLine("你是坐在用户旁边一起看东西的同学，不是助手，也不是老师。口语，一次一两句，最多三句。")
        appendLine("不替他总结，不先给答案；先让他自己讲，讲完指出他漏掉的一处，一次只指一处。他说懂了，追一个小问题验一下。")
        appendLine("这次：${materialName(plan.material)}；目标：${plan.goal.trim()}；强度：${_state.value.gear.label}。")
        appendLine("你会收到【内部导演卡】，照卡推进，不复述卡，不说你收到了卡。只输出要说出口的话，不要列表、标题或括号说明。")
        if (plan.source.isNotBlank()) {
            appendLine("你们正在一起看下面这段【材料】。只就它说话，引用要和原文一致，不编材料里没有的东西；先让他自己讲，不要替他总结全文。")
            appendLine("【材料】")
            appendLine(plan.source.take(1_500))
        }
        card?.let { append(Directors.context(it)) }
    }

    private fun materialName(m: Material) = when (m) {
        Material.VIDEO -> "看网课"
        Material.ARTICLE -> "读文章"
        Material.DRILL -> "做题"
        Material.CHESS -> "下象棋"
        Material.OTHER -> "看材料"
    }

    private fun directorInput() = DirectorInput(
        plan = plan.copy(gear = _state.value.gear),
        elapsedMin = ((clock() - startedAt) / 60_000).toInt(),
        transcript = _state.value.lines,
        missed = _state.value.missed,
        lastClose = lastClose,
        memory = memory,
    )

    private fun requestDirector() {
        if (directorJob?.isActive == true) return
        directorAt = clock()
        turnsSinceDirector = 0
        val input = directorInput()
        directorJob = scope.launch {
            val r = director.card(input)
            card = (r as? LlmResult.Ok)?.value ?: Directors.local(input)
        }
    }

    private fun beginClose(lead: String) {
        interrupt()
        closeStep = 1
        closeRetried = false
        _state.update { it.copy(stage = Stage.CLOSE) }
        speak("${lead}一句话，今天聊出了什么？", Stage.CLOSE)
    }

    private fun closing(text: String) {
        when (closeStep) {
            1 -> if (Words.substance(text) < 6 && !closeRetried) {
                closeRetried = true
                speak("再具体一点：今天聊出了什么？", Stage.CLOSE)
            } else {
                closeLine = text
                closeStep = 2
                closeRetried = false
                speak("下一步做什么？", Stage.CLOSE)
            }
            2 -> if (Words.substance(text) < 4 && !closeRetried) {
                closeRetried = true
                speak("说具体一点：下一步做什么？", Stage.CLOSE)
            } else {
                closeStep = 3
                val missed = _state.value.missed
                scope.launch {
                    onClosed(closeLine, text, missed)
                    speakNow(if (missed > 0) "收好了。这场有 $missed 个问题没接上，下次接着。" else "收好了。")
                    _state.update { it.copy(stage = Stage.ENDED, phase = Phase.LISTENING) }
                    ticker?.cancel()
                }
            }
        }
    }

    companion object {
        const val ME = "我"
        const val PARTNER = "伙伴"
    }
}
