package com.yishou.app.companion.android

import com.yishou.app.companion.core.AudioChunker
import com.yishou.app.log.RunLog
import com.yishou.app.speech.Vad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 把内录的音频块喂给听写接口。
 *
 * 现在能用的听写接口都是「上传一段、返回整段」，不是流式，所以这里做的是分块近实时：
 * 8 秒一块、块间重叠 1 秒，上一块没回来就不再发下一块——**排队只会越排越晚**，
 * 晚到的转写对着已经过去的内容也没法问，宁可丢掉并记成缺口。
 */
class TranscriptTap(
    private val scope: CoroutineScope,
    /** 传一段 WAV，返回文字；失败返回 null（由这里记缺口） */
    private val transcribe: suspend (wav: ByteArray) -> String?,
    /** 一块的转写结果，时间是「这一场开始以来」的毫秒 */
    private val onChunk: (startMs: Long, endMs: Long, text: String) -> Unit,
    /** 一块失败时回调，用来记缺口 */
    private val onFailed: (startMs: Long, endMs: Long, why: String) -> Unit = { _, _, _ -> },
    sampleRate: Int = PlaybackCaptureProbe.SAMPLE_RATE,
) {

    private val chunker = AudioChunker(
        sampleRate = sampleRate,
        chunkMs = CHUNK_MS,
        overlapMs = OVERLAP_MS,
    ) { start, end, pcm -> dispatch(start, end, pcm) }

    private var inFlight: Job? = null
    private var dropped = 0

    /** 内录每读到一块就喂进来 */
    fun feed(samples: ShortArray, count: Int, atMs: Long) {
        chunker.feed(samples, count, atMs)
    }

    /** 收尾：把尾巴也转一次，别丢掉最后一句 */
    fun flush() {
        chunker.flush()
    }

    private fun dispatch(startMs: Long, endMs: Long, pcm: ByteArray) {
        if (inFlight?.isActive == true) {
            dropped++
            onFailed(startMs, endMs, "上一块还在转，这一块跳过（排队只会越排越晚）")
            return
        }
        inFlight = scope.launch {
            val wav = Vad.wav(pcm, PlaybackCaptureProbe.SAMPLE_RATE)
            val text = try {
                transcribe(wav)
            } catch (e: Exception) {
                RunLog.e("陪练探针", "分块转写出错", e)
                null
            }
            if (!isActive) return@launch
            if (text == null) {
                onFailed(startMs, endMs, "听写没回上来")
            } else {
                onChunk(startMs, endMs, text)
            }
        }
    }

    fun stop() {
        inFlight?.cancel()
        inFlight = null
    }

    fun droppedCount(): Int = dropped

    companion object {
        const val CHUNK_MS = 8_000L
        const val OVERLAP_MS = 1_000L
    }
}
