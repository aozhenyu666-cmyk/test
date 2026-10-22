package com.moling.core.live

import com.moling.core.workspace.Focus
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.WorkbenchState
import kotlinx.serialization.Serializable

/*
 * 只听电台（v13 第十一批 R1）。
 *
 * 她拿着案板/来源池里**已有的**材料讲一小段，他不回也不追问：
 *  - 一次默认最多 5 分钟，一段 30–90 秒；先“接上上次”，再讲一个有意思的点，最后可以留个悬念；
 *  - 只播已有材料 / 稿子正文（[RadioContent]），播放时不联网、不再抓新东西；
 *  - 引用的事实和她的猜想分开标（[SegmentKind]），不假装在看不存在的视频；
 *  - 随时可以暂停 / 继续 / 跳过 / 停止。停止立即清空待播，迟到回调带 [RadioSession.generation]，
 *    对不上就丢掉，不能自己又开播。
 *
 * 这里全是纯数据和纯函数；音频、TTS、界面在 App 层（见 RadioPorts.kt 的接口约定）。
 */

@Serializable
enum class RadioState(val label: String) {
    PREPARING("准备中"),
    PLAYING("在讲"),
    PAUSED("暂停"),
    FINISHED("讲完了"),
    STOPPED("已停止"),
}

/** 这一段是什么。事实和猜想必须分开措辞，不能把猜的讲成看到的。 */
@Serializable
enum class SegmentKind(val label: String) {
    /** 用户给的材料 / 稿子里的原文。 */
    QUOTE("材料原文"),
    /** 她自己的整理或推演（可能错）。 */
    IDEA("她的整理"),
    /** 留个悬念 / 收尾。 */
    QUESTION("留个悬念"),
}

@Serializable
data class RadioSegment(
    val id: String,
    val kind: SegmentKind,
    val text: String,
    /** 来源材料 / 稿子的 id，方便回看。 */
    val sourceIds: List<String> = emptyList(),
    val ms: Int = Radio.msOf(text),
)

/**
 * 一次电台会话。[generation] 每次新建会话 +1：TTS / 模型 / 定时器拿旧的 generation 回来时一律丢掉。
 * [segmentDeadline] / [endsAt] 都是绝对时间（毫秒），暂停时 [segmentDeadline] 清 0，[pausedRemainingMs] 记住还剩多久。
 */
@Serializable
data class RadioSession(
    val id: String,
    val focusId: String,
    val sourceIds: List<String>,
    val state: RadioState,
    val segments: List<RadioSegment> = emptyList(),
    val index: Int = 0,
    val generation: Long = 0,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val segmentDeadline: Long = 0,
    val endsAt: Long = 0,
    val pausedRemainingMs: Long = 0,
    val pausedAt: Long = 0,
    val stopReason: String = "",
    val updatedAt: Long = 0,
) {
    val current: RadioSegment? get() = segments.getOrNull(index)
    val segmentId: String get() = current?.id.orEmpty()
    val active: Boolean get() = state == RadioState.PREPARING || state == RadioState.PLAYING || state == RadioState.PAUSED
    val totalMs: Long get() = segments.sumOf { it.ms.toLong() }.coerceAtLeast(0)
}

object Radio {
    /** 一段最短 / 最长（30–90 秒）。 */
    const val MIN_SEGMENT_MS = 30_000
    const val MAX_SEGMENT_MS = 90_000

    /** 一次最多讲 5 分钟。 */
    const val MAX_SESSION_MS = 5 * 60_000L

    /** 准备阶段的兜底：15 秒还没准备好就结束，不无限“等会再试”。 */
    const val PREPARE_TIMEOUT_MS = 15_000L

    /** 一次会话最多几段（再多的留给下一次）。 */
    const val MAX_SEGMENTS = 8

    /** 中文按大约每秒 4.5 个字算，夹在 30–90 秒之间。 */
    fun msOf(text: String): Int {
        val chars = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        return (chars * 220L).coerceIn(MIN_SEGMENT_MS.toLong(), MAX_SEGMENT_MS.toLong()).toInt()
    }

    fun newSession(id: String, focusId: String, sourceIds: List<String>, generation: Long, now: Long): RadioSession =
        RadioSession(
            id = id,
            focusId = focusId,
            sourceIds = sourceIds.distinct(),
            state = RadioState.PREPARING,
            generation = generation,
            startedAt = now,
            segmentDeadline = now + PREPARE_TIMEOUT_MS,
            updatedAt = now,
        )

    /** 内容准备好了：PREPARING → PLAYING。迟到的（已经停止 / 换了会话）返回 null，由调用方丢掉。 */
    fun prepared(s: RadioSession, segments: List<RadioSegment>, now: Long): RadioSession? {
        if (s.state != RadioState.PREPARING) return null
        val usable = segments.filter { it.text.isNotBlank() }.take(MAX_SEGMENTS)
        if (usable.isEmpty()) return s.copy(state = RadioState.FINISHED, endedAt = now, stopReason = "没有可讲的材料", updatedAt = now)
        val first = usable.first().copy(ms = usable.first().ms.coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS))
        return s.copy(
            state = RadioState.PLAYING,
            segments = listOf(first) + usable.drop(1).map { it.copy(ms = it.ms.coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)) },
            index = 0,
            segmentDeadline = now + first.ms,
            endsAt = now + MAX_SESSION_MS,
            updatedAt = now,
        )
    }

    /** 开始播放（内容已在手里）。等价于 prepared。 */
    fun start(s: RadioSession, segments: List<RadioSegment>, now: Long): RadioSession? = prepared(s, segments, now)

    fun paused(s: RadioSession, now: Long): RadioSession {
        if (s.state != RadioState.PLAYING) return s
        val left = (s.segmentDeadline - now).coerceAtLeast(0)
        return s.copy(state = RadioState.PAUSED, pausedRemainingMs = left, pausedAt = now, segmentDeadline = 0, updatedAt = now)
    }

    fun resumed(s: RadioSession, now: Long): RadioSession {
        if (s.state != RadioState.PAUSED) return s
        if (s.pausedAt <= 0) return s.copy(state = RadioState.PLAYING, segmentDeadline = now + s.pausedRemainingMs, updatedAt = now)
        return s.copy(
            state = RadioState.PLAYING,
            segmentDeadline = now + s.pausedRemainingMs,
            // 暂停的时间不算在 5 分钟里
            endsAt = if (s.endsAt > 0) s.endsAt + (now - s.pausedAt) else s.endsAt,
            pausedRemainingMs = 0,
            pausedAt = 0,
            updatedAt = now,
        )
    }

    /** 跳过这一段。最后一段被跳过就是讲完了。 */
    fun skipped(s: RadioSession, now: Long): RadioSession = when (s.state) {
        RadioState.PLAYING, RadioState.PAUSED -> advance(s.copy(state = RadioState.PLAYING), now)
        else -> s
    }

    /** 停止：立即清掉待播（只留已经讲过的），并让这个会话的所有迟到回调作废。 */
    fun stopped(s: RadioSession, now: Long, reason: String = "他点停止"): RadioSession {
        if (!s.active) return s
        return s.copy(
            state = RadioState.STOPPED,
            segments = s.segments.take((s.index + 1).coerceIn(0, s.segments.size)),
            segmentDeadline = 0,
            pausedRemainingMs = 0,
            pausedAt = 0,
            endedAt = now,
            stopReason = reason,
            updatedAt = now,
        )
    }

    fun finished(s: RadioSession, now: Long, reason: String = "讲完了"): RadioSession =
        if (!s.active) s else s.copy(state = RadioState.FINISHED, segmentDeadline = 0, endedAt = now, stopReason = reason, updatedAt = now)

    /** 过了这段的 deadline 就自然往下走；到 5 分钟 / 最后一段就结束。暂停时不动。 */
    fun tick(s: RadioSession, now: Long): RadioSession {
        if (!s.active) return s
        if (s.state == RadioState.PREPARING) {
            return if (now >= s.segmentDeadline) s.copy(state = RadioState.FINISHED, endedAt = now, stopReason = "准备超时", updatedAt = now) else s
        }
        if (s.state == RadioState.PAUSED) return s
        if (s.endsAt > 0 && now >= s.endsAt) return finished(s, now, "到 5 分钟了")
        var t = s
        var guard = 0
        while (t.state == RadioState.PLAYING && t.segmentDeadline > 0 && now >= t.segmentDeadline && guard++ < MAX_SEGMENTS) {
            t = advance(t, now)
        }
        if (t.state == RadioState.PLAYING && t.endsAt > 0 && now >= t.endsAt) t = finished(t, now, "到 5 分钟了")
        return t
    }

    private fun advance(s: RadioSession, now: Long): RadioSession {
        val next = s.index + 1
        if (next >= s.segments.size) return finished(s, now, "讲完了")
        val seg = s.segments[next]
        val deadline = if (s.endsAt > 0) minOf(now + seg.ms, s.endsAt) else now + seg.ms
        return s.copy(index = next, segmentDeadline = deadline, pausedRemainingMs = 0, pausedAt = 0, updatedAt = now)
    }

    /** 0..1 的进度（按每段秒数算，暂停不增长）。 */
    fun progress(s: RadioSession, now: Long): Float {
        if (s.segments.isEmpty()) return if (s.state == RadioState.FINISHED) 1f else 0f
        val played = s.segments.take(s.index).sumOf { it.ms }.toFloat()
        val current = s.current ?: return 1f
        val inSeg = when (s.state) {
            RadioState.PAUSED -> (current.ms - s.pausedRemainingMs).coerceIn(0, current.ms.toLong())
            RadioState.PLAYING -> (current.ms - (s.segmentDeadline - now).coerceIn(0, current.ms.toLong())).coerceIn(0, current.ms.toLong())
            RadioState.FINISHED, RadioState.STOPPED -> current.ms.toLong()
            RadioState.PREPARING -> 0L
        }
        return ((played + inSeg) / s.totalMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /** 还剩多少毫秒（这一段或整场，取小的）。 */
    fun remainingMs(s: RadioSession, now: Long): Long = when (s.state) {
        RadioState.PAUSED -> s.pausedRemainingMs
        RadioState.PLAYING -> if (s.endsAt > 0) minOf((s.segmentDeadline - now).coerceAtLeast(0), (s.endsAt - now).coerceAtLeast(0)) else (s.segmentDeadline - now).coerceAtLeast(0)
        else -> 0
    }

    /** 这个回调还认不认（停止 / 换会话 / 换段之后一律不认）。 */
    fun accepts(s: RadioSession, sessionId: String, segmentId: String, generation: Long): Boolean =
        s.state == RadioState.PLAYING && s.id == sessionId && s.segmentId == segmentId && s.generation == generation
}

/**
 * 电台状态机 + 幂等入口。App 只有这一份会话；换焦点 / 重复 start 不会叠出第二个电台。
 * 纯内存、不加锁（App 在主线程调；测试直接调）。
 */
class RadioEngine {
    var current: RadioSession? = null
        private set

    private var seq = 0L
    private var generation = 0L

    /** 开始。同一焦点 + 同一来源再点一次返回原来那个会话（不会重新开播）；只播已有材料。 */
    fun start(
        focusId: String,
        sourceIds: List<String>,
        segments: List<RadioSegment>,
        now: Long,
        explicit: Boolean = true,
        quiet: Boolean = false,
    ): PortResult<RadioSession> {
        if (!explicit && quiet) return PortResult.Failed(PortError("QUIET", "现在是安静期，只有你点了才开始"))
        val cur = current
        if (cur != null && cur.active && cur.focusId == focusId && cur.sourceIds.toSet() == sourceIds.toSet()) {
            return PortResult.Ok(cur)
        }
        if (cur != null && cur.active) current = Radio.stopped(cur, now, "换了一件")
        val s = Radio.newSession("radio-$now-${seq++}", focusId, sourceIds, ++generation, now)
        // 内容还没到（要准备）时留在 PREPARING；到了走 onPrepared；空内容不能直接判“讲完了”。
        val ready = if (segments.isEmpty()) s else (Radio.start(s, segments, now) ?: s)
        current = ready
        return PortResult.Ok(ready)
    }

    /** 内容晚到（模型 / 抓取准备完）。generation 对不上、已经停止 / 换会话一律丢掉。 */
    fun onPrepared(sessionId: String, generation: Long, segments: List<RadioSegment>, now: Long): Boolean {
        val s = current ?: return false
        if (s.id != sessionId || s.generation != generation || s.state != RadioState.PREPARING) return false
        current = Radio.prepared(s, segments, now) ?: return false
        return true
    }

    fun pause(sessionId: String, now: Long): PortResult<RadioSession> = on(sessionId) { Radio.paused(it, now) }

    fun resume(sessionId: String, now: Long): PortResult<RadioSession> = on(sessionId) { Radio.resumed(it, now) }

    fun skip(sessionId: String, now: Long): PortResult<RadioSession> {
        val s = current
        if (s == null || s.id != sessionId) return PortResult.Failed(PortError("NOT_FOUND", "这个电台已经不在了"))
        if (s.state != RadioState.PLAYING && s.state != RadioState.PAUSED) return PortResult.Failed(PortError("BAD_ACTION", "现在不能跳过"))
        current = Radio.skipped(s, now)
        return PortResult.Ok(current!!)
    }

    fun stop(sessionId: String, now: Long, reason: String = "他点停止"): PortResult<RadioSession> {
        val s = current
        if (s == null || s.id != sessionId) return PortResult.Failed(PortError("NOT_FOUND", "这个电台已经不在了"))
        if (!s.active) return PortResult.Ok(s)
        current = Radio.stopped(s, now, reason)
        return PortResult.Ok(current!!)
    }

    /** 定时推进；到点 / 到 5 分钟 / 准备超时会自己结束。 */
    fun tick(now: Long): RadioSession? {
        val s = current ?: return null
        val n = Radio.tick(s, now)
        if (n != s) current = n
        return n
    }

    /**
     * TTS / 播放器报告“这一段念完了”。只有还是这一场、这一段、这个 generation 且正在播才认；
     * 停止后迟到的回调返回 false，不会再次开播。
     */
    fun onSegmentDone(sessionId: String, segmentId: String, generation: Long, now: Long): Boolean {
        val s = current ?: return false
        if (!Radio.accepts(s, sessionId, segmentId, generation)) return false
        current = Radio.tick(s.copy(segmentDeadline = minOf(s.segmentDeadline, now)), now)
        return true
    }

    /** 这段的音频开始播放前再看一眼：停止 / 跳过 / 换会话后旧回调不能出声。 */
    fun accepts(sessionId: String, segmentId: String, generation: Long): Boolean {
        val s = current ?: return false
        return Radio.accepts(s, sessionId, segmentId, generation)
    }

    fun reset() {
        current = null
    }

    private fun on(sessionId: String, f: (RadioSession) -> RadioSession): PortResult<RadioSession> {
        val s = current
        if (s == null || s.id != sessionId) return PortResult.Failed(PortError("NOT_FOUND", "这个电台已经不在了"))
        val n = f(s)
        current = n
        return PortResult.Ok(n)
    }
}

/** 从案板的材料 / 稿子正文拆出可讲的段。不联网、不调模型。 */
object RadioContent {
    /** 每段最多多少字（按 30–90 秒夹过的实际时长在 Radio.msOf 里）。 */
    const val MAX_CHARS = 380

    fun fromWorkbench(ws: WorkbenchState, focus: Focus, now: Long, idPrefix: String = "rs-$now"): List<RadioSegment> {
        val out = mutableListOf<RadioSegment>()
        var n = 0
        fun add(kind: SegmentKind, text: String, sourceIds: List<String>) {
            out += chunk(kind, text, sourceIds, "$idPrefix-${n++}")
        }
        // 接上上次：只说他写过的问题，不编造材料里没有的东西。
        if (focus.question.isNotBlank()) add(SegmentKind.IDEA, "上次我们在弄：${focus.question.take(80)}。你不用回，我先念一段。", emptyList())
        ws.materialsOf(focus).forEach { m ->
            val body = m.text.trim()
            if (body.isNotEmpty()) add(SegmentKind.QUOTE, if (m.title.isBlank()) body else "${m.title}：$body", listOf(m.id))
        }
        ws.artifactsOf(focus.id).sortedByDescending { it.createdAt }.take(2).forEach { a ->
            a.blocks.filter { it.kind == com.moling.core.workspace.BlockKind.MARKDOWN || it.kind == com.moling.core.workspace.BlockKind.QUOTE || it.kind == com.moling.core.workspace.BlockKind.ASSUMPTION }
                .filterNot { it.exerciseOnly }
                .forEach { b ->
                    val body = b.markdown.trim()
                    if (body.length >= 8) add(SegmentKind.IDEA, body, (a.sourceIds + b.sourceIds).distinct())
                }
        }
        // 收个尾：只在这一场还有余量时说一句，不硬留悬念。
        if (out.isNotEmpty()) out += RadioSegment("$idPrefix-tail", SegmentKind.QUESTION, "这一段先到这。想接着听就点继续，不想听就停在这儿。", out.flatMap { it.sourceIds }.distinct())
        return out.take(Radio.MAX_SEGMENTS)
    }

    /** 把一段长文按句子切成 ≤ [MAX_CHARS] 的段。 */
    fun chunk(kind: SegmentKind, text: String, sourceIds: List<String>, idPrefix: String): List<RadioSegment> {
        val clean = text.replace("\r", "").replace(Regex("\n{3,}"), "\n\n").trim()
        if (clean.isEmpty()) return emptyList()
        val pieces = mutableListOf<String>()
        val sentences = clean.split(Regex("(?<=[。！？!?；;])|(?<=\n)"))
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) pieces += buf.toString().trim()
            buf.clear()
        }
        sentences.forEach { raw ->
            val s = raw.trim()
            if (s.isEmpty()) return@forEach
            if (s.length > MAX_CHARS) {
                flush()
                s.chunked(MAX_CHARS).forEach { pieces += it }
            } else {
                if (buf.length + s.length > MAX_CHARS) flush()
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(s)
            }
        }
        flush()
        return pieces.filter { it.isNotBlank() }.mapIndexed { i, p -> RadioSegment("$idPrefix-$i", kind, p, sourceIds) }
    }
}
