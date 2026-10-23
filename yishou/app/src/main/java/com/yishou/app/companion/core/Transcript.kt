package com.yishou.app.companion.core

/**
 * 内录分块。真实可用的听写接口是「上传一段音频、返回整段文字」，
 * 不是流式，所以按块走：块与块之间留一点重叠，免得正好切在一句话中间。
 */
class AudioChunker(
    private val sampleRate: Int,
    /** 每块多长 */
    private val chunkMs: Long = 8_000,
    /** 相邻两块重叠多久 */
    private val overlapMs: Long = 1_000,
    /** 每块交出来：起点、终点（相对这一场开始）、PCM16 小端字节 */
    private val onChunk: (startMs: Long, endMs: Long, pcm: ByteArray) -> Unit,
) {

    private val chunkSamples = (sampleRate * chunkMs / 1_000).toInt()
    private val overlapSamples = (sampleRate * overlapMs / 1_000).toInt()
    private val buffer = ShortArray(chunkSamples * 2)

    private var filled = 0
    /** 缓冲区第 0 个样本在这一场的哪个毫秒 */
    private var bufferStartMs = 0L
    /** 已经交出去到哪一毫秒 */
    private var emittedUntilMs = 0L

    fun feed(samples: ShortArray, count: Int, atMs: Long) {
        if (count <= 0) return
        if (filled == 0) bufferStartMs = atMs
        var offset = 0
        while (offset < count) {
            val room = chunkSamples - filled
            val take = minOf(room, count - offset)
            System.arraycopy(samples, offset, buffer, filled, take)
            filled += take
            offset += take
            if (filled == chunkSamples) emit()
        }
    }

    private fun emit() {
        val endMs = bufferStartMs + filled * 1_000L / sampleRate
        onChunk(bufferStartMs, endMs, toPcm16(buffer, filled))
        emittedUntilMs = endMs
        // 留一段尾巴当下一块的开头：切在句中也能对上
        val keep = minOf(overlapSamples, filled)
        if (keep > 0) System.arraycopy(buffer, filled - keep, buffer, 0, keep)
        filled = keep
        bufferStartMs = endMs - keep * 1_000L / sampleRate
    }

    /** 收尾：把不够一块的尾巴也交出去（短一点没关系，总比丢掉强） */
    fun flush() {
        if (filled <= 0) return
        val endMs = bufferStartMs + filled * 1_000L / sampleRate
        if (endMs > emittedUntilMs) onChunk(bufferStartMs, endMs, toPcm16(buffer, filled))
        filled = 0
    }

    fun pendingMs(): Long = filled * 1_000L / sampleRate

    private fun toPcm16(data: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = data[i].toInt()
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}

/** 一块转写回来 */
data class Chunk(val startMs: Long, val endMs: Long, val text: String, val quality: Quality = Quality.GOOD)

/**
 * 把一块块转写拼成时间轴上的观察。
 *
 * 重叠的部分要真的去掉：上一块的尾巴常常和这一块的开头一样，直接拼会让同一句出现两遍，
 * 模型就会以为用户听重了。识别失败或整块没声音，记成缺口，不静默吞掉。
 */
class TranscriptAssembler(
    private val sessionId: Long,
    private val appPackage: String,
    private val epoch: Int,
    private val nextId: () -> Long,
    /** 块之间超过这个间隔就算缺口 */
    private val gapThresholdMs: Long = 1_500,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {

    private var lastEndMs: Long = -1
    private var lastText: String = ""
    /** 识别失败累计了多少毫秒：要并进下一段的缺口里，不能当没发生过 */
    private var failedMs: Long = 0

    /** 接一块。返回 null 表示这一块没有可用文字（纯静音/识别为空），缺口照常记。 */
    fun accept(chunk: Chunk): Observation? {
        val gap = if (lastEndMs < 0) 0 else (chunk.startMs - lastEndMs).coerceAtLeast(0)
        var gaps = if (gap > gapThresholdMs) gap else 0
        if (failedMs > 0) {
            gaps += failedMs
            failedMs = 0
        }
        if (chunk.text.isBlank()) {
            lastEndMs = if (lastEndMs < 0) chunk.endMs else maxOf(lastEndMs, chunk.endMs)
            return null
        }
        val cut = overlapLength(lastText, chunk.text)
        val text = chunk.text.substring(cut).trim()
        lastEndMs = if (lastEndMs < 0) chunk.endMs else maxOf(lastEndMs, chunk.endMs)
        if (text.isEmpty()) return null
        lastText = chunk.text
        return Observation(
            id = nextId(),
            sessionId = sessionId,
            sourceEpoch = epoch,
            appPackage = appPackage,
            channel = Channel.VIDEO_AUDIO,
            elapsedStartMs = chunk.startMs,
            elapsedEndMs = chunk.endMs,
            wallTimeMs = wallClock(),
            text = text,
            quality = chunk.quality,
            gapsBeforeMs = gaps,
        )
    }

    /** 这一块整个没识别出来：记成缺口，别让时间轴看起来是连续的 */
    fun fail(startMs: Long, endMs: Long) {
        failedMs += (endMs - startMs).coerceAtLeast(0)
        lastEndMs = if (lastEndMs < 0) endMs else maxOf(lastEndMs, endMs)
        lastText = ""
    }

    fun failedMs(): Long = failedMs

    /** 上一块的尾巴有多长同时是这一块的开头 */
    fun overlapLength(prev: String, next: String): Int {
        val max = minOf(prev.length, next.length)
        for (len in max downTo MIN_OVERLAP) {
            if (prev.regionMatches(prev.length - len, next, 0, len)) return len
        }
        return 0
    }

    companion object {
        /** 太短的重叠是巧合（比如「的」「了」），不算 */
        const val MIN_OVERLAP = 4
    }
}
