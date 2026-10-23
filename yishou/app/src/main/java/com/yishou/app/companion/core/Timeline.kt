package com.yishou.app.companion.core

/** 这条观察进了没有 */
enum class AddResult { ADDED, DUPLICATE, STALE, WRONG_SESSION }

/**
 * 共用时间轴：把内录转写、画面观察、用户说话按时间对齐，只留最近一段。
 *
 * 三件事在这里做掉，别的地方就不用重复判断：
 * 1. **去重**：分块识别有重叠，同一句话会来两遍，只留一遍；
 * 2. **作废**：换视频、跳播就把 epoch 加一，旧段的东西全部不许用（不能在新视频里念旧问题）；
 * 3. **有界**：只留最近 180 秒，超期淘汰，长时间开也不会越攒越大。
 *
 * 全部是纯逻辑，时钟和上限都从外面传，测试里用假时钟。
 */
class ContextTimeline(
    private val sessionId: Long,
    private val windowMs: Long = 180_000,
    private val dedupeWindowMs: Long = 4_000,
    /** 两段话之间停顿超过这个数就切成两段 */
    private val segmentGapMs: Long = 1_200,
    /** 进度倒退超过这个数算跳播（回看） */
    private val seekBackMs: Long = 5_000,
    /** 进度一次往前跳超过这个数、而实际只过了很短时间，算跳播（快进） */
    private val seekForwardMs: Long = 30_000,
) {

    private val kept = ArrayList<Observation>()
    private var epoch = 1
    private var gaps = 0L
    private var lastPositionMs: Long? = null
    private var lastPositionAtElapsed = 0L

    fun epoch(): Int = epoch

    /** 换了视频、换了目标：开新的一段 */
    fun bumpEpoch(): Int {
        epoch++
        lastPositionMs = null
        return epoch
    }

    /** 喂一条观察。旧段的东西直接丢，重复的也直接丢。 */
    fun add(o: Observation): AddResult {
        if (o.sessionId != sessionId) return AddResult.WRONG_SESSION
        if (o.elapsedEndMs < o.elapsedStartMs) return AddResult.STALE
        if (o.sourceEpoch < epoch) return AddResult.STALE
        if (o.sourceEpoch > epoch) {
            epoch = o.sourceEpoch
            lastPositionMs = null
        }
        val dup = kept.lastOrNull {
            it.channel == o.channel &&
                it.text == o.text &&
                kotlin.math.abs(it.elapsedStartMs - o.elapsedStartMs) <= dedupeWindowMs
        }
        if (dup != null) return AddResult.DUPLICATE
        kept.add(o)
        if (o.gapsBeforeMs > 0) gaps += o.gapsBeforeMs
        return AddResult.ADDED
    }

    /**
     * 喂一次播放进度，判断有没有跳播。返回 true 表示已经开了新 epoch。
     * 只看得到进度的时候才判断；看不到进度就不乱猜（只会在界面上说「本次观察第几分钟」）。
     */
    fun observePosition(p: MediaPosition, elapsedMs: Long): Boolean {
        val now = p.positionMs ?: return false
        val prev = lastPositionMs
        lastPositionMs = now
        if (prev == null) {
            lastPositionAtElapsed = elapsedMs
            return false
        }
        val back = prev - now
        val forward = now - prev
        val realGap = (elapsedMs - lastPositionAtElapsed).coerceAtLeast(0)
        lastPositionAtElapsed = elapsedMs
        val jumped = back > seekBackMs || (forward > seekForwardMs && forward > realGap + 5_000)
        if (jumped) bumpEpoch()
        return jumped
    }

    fun gapsMs(): Long = gaps

    /** 超期淘汰，保证内存有界 */
    fun prune(nowElapsedMs: Long) {
        val cutoff = nowElapsedMs - windowMs
        kept.removeAll { it.elapsedEndMs < cutoff }
    }

    /**
     * 当前窗口的切片。[expiresAtMs] 由调用方按「还能不能出声」定，
     * 一般给 now + 十几秒：过期就别再问了。
     */
    fun slice(nowElapsedMs: Long, expiresAtMs: Long): ContextSlice {
        prune(nowElapsedMs)
        val inWindow = kept.filter { it.sourceEpoch == epoch }
        return ContextSlice(
            epoch = epoch,
            evidenceIds = inWindow.map { it.id },
            fromMs = inWindow.minOfOrNull { it.elapsedStartMs } ?: 0,
            toMs = inWindow.maxOfOrNull { it.elapsedEndMs } ?: 0,
            text = inWindow.joinToString("\n") { speakable(it) },
            segments = segmentsOf(inWindow),
            gapsMs = gaps,
            expiresAtMs = expiresAtMs,
        )
    }

    /** 按停顿切段：段是以后做摘要和「点一下能看见当时那段材料」的最小单位 */
    fun segmentsOf(list: List<Observation>): List<Segment> {
        val out = ArrayList<Segment>()
        var current = ArrayList<Observation>()
        fun flush() {
            if (current.isEmpty()) return
            out += Segment(
                id = current.first().id,
                channel = current.first().channel,
                fromMs = current.first().elapsedStartMs,
                toMs = current.last().elapsedEndMs,
                text = current.joinToString("") { it.text },
                evidenceIds = current.map { it.id },
            )
            current = ArrayList()
        }
        list.sortedBy { it.elapsedStartMs }.forEach { o ->
            val prev = current.lastOrNull()
            if (prev != null && (o.elapsedStartMs - prev.elapsedEndMs > segmentGapMs || o.channel != prev.channel)) flush()
            current.add(o)
        }
        flush()
        return out
    }

    /** 画面观察要标出来源，别让模型把它当原文事实 */
    private fun speakable(o: Observation): String = when (o.channel) {
        Channel.VIDEO_AUDIO -> o.text
        Channel.USER_VOICE -> "（我说的）" + o.text
        Channel.SCREEN -> "（画面）" + o.text
        Channel.MEMO -> "（目标）" + o.text
    }

    fun size(): Int = kept.size
}
