package com.yishou.app.speech

/**
 * 判断一句话说完了没有（端点检测），只看音量。
 * 先用开头一小段估计环境底噪，音量明显高过底噪算“在说话”；
 * 说过话之后安静超过 [silenceMs] 就算说完。一直没开口超过 [waitMs] 算没说。
 */
class Vad(
    private val silenceMs: Long = 1_600,
    private val waitMs: Long = 8_000,
    private val maxMs: Long = 90_000,
    private val calibrateMs: Long = 300,
) {
    enum class State { WAITING, SPEAKING, DONE, NO_SPEECH }

    var state = State.WAITING
        private set

    private var elapsed = 0L
    private var noise = 0.0
    private var noiseFrames = 0
    private var quietFor = 0L
    /** 说话时长（毫秒），太短的当作杂音 */
    var spokenMs = 0L
        private set

    /** 送进一帧的音量（RMS，0–32767）和这一帧的时长，返回当前状态。 */
    fun feed(rms: Double, frameMs: Long): State {
        if (state == State.DONE || state == State.NO_SPEECH) return state
        elapsed += frameMs
        if (elapsed <= calibrateMs) {
            noise = (noise * noiseFrames + rms) / (noiseFrames + 1)
            noiseFrames++
            return state
        }
        val loud = rms > threshold()
        when (state) {
            State.WAITING -> {
                if (loud) {
                    state = State.SPEAKING
                    spokenMs = frameMs
                    quietFor = 0
                } else {
                    // 慢慢跟上环境变化
                    noise = noise * 0.98 + rms * 0.02
                    if (elapsed >= waitMs) state = State.NO_SPEECH
                }
            }
            State.SPEAKING -> {
                if (loud) {
                    spokenMs += frameMs
                    quietFor = 0
                } else {
                    quietFor += frameMs
                    if (quietFor >= silenceMs) state = if (spokenMs >= MIN_SPEECH_MS) State.DONE else State.NO_SPEECH
                }
            }
            else -> {}
        }
        if (elapsed >= maxMs && state == State.SPEAKING) state = State.DONE
        return state
    }

    /** 给界面画音量用：0–1 */
    fun level(rms: Double): Float = ((rms - noise) / (threshold() * 4)).coerceIn(0.0, 1.0).toFloat()

    private fun threshold(): Double = maxOf(noise * 2.5, MIN_THRESHOLD)

    companion object {
        const val MIN_THRESHOLD = 450.0
        const val MIN_SPEECH_MS = 250L

        /** 16 位单声道 PCM 的 RMS */
        fun rms(buf: ShortArray, n: Int): Double {
            if (n <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            return Math.sqrt(sum / n)
        }

        /** 给 16 位单声道 PCM 加上 WAV 文件头 */
        fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(pcm.size + 44)
            fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
            out.write("data".toByteArray()); int(pcm.size)
            out.write(pcm)
            return out.toByteArray()
        }
    }
}
