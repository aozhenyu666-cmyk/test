package com.yishou.app.companion.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.yishou.app.log.RunLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * 自控测试播放器：一手自己放一段音量很小的提示音，并且挂一个自己的播放会话。
 *
 * 用途是验证「暂停/恢复」这条路径本身通不通（拿自己的会话当靶子，不用先拿用户的视频 App 试）。
 * 内录用它验不了：系统不会把一手自己的声音算进内录，这一点写在界面上，不装成验过了。
 */
class SelfTestPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var session: MediaSession? = null

    private val _note = MutableStateFlow("没开")
    val note: StateFlow<String> = _note.asStateFlow()

    fun start(): String? {
        stop()
        return try {
            val file = tone()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setDataSource(file.path)
            mp.isLooping = true
            mp.prepare()

            val s = MediaSession(context, TAG)
            s.setCallback(object : MediaSession.Callback() {
                override fun onPause() {
                    player?.let { if (it.isPlaying) it.pause() }
                    s.setPlaybackState(state(PlaybackState.STATE_PAUSED))
                    _note.value = "自控播放器：被外部暂停了"
                }

                override fun onPlay() {
                    player?.let { it.start() }
                    s.setPlaybackState(state(PlaybackState.STATE_PLAYING))
                    _note.value = "自控播放器：恢复了"
                }
            })
            s.isActive = true
            s.setPlaybackState(state(PlaybackState.STATE_PLAYING))
            mp.start()

            player = mp
            session = s
            _note.value = "自控播放器：在放（循环提示音）"
            null
        } catch (e: Exception) {
            RunLog.e("陪练探针", "自控播放器起不来", e)
            stop()
            "自控播放器起不来：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun stop() {
        try {
            player?.stop()
        } catch (e: IllegalStateException) { }
        player?.release()
        player = null
        session?.let {
            it.setPlaybackState(state(PlaybackState.STATE_STOPPED))
            it.isActive = false
            it.release()
        }
        session = null
        _note.value = "没开"
    }

    val sessionTag: String get() = TAG

    private fun state(code: Int): PlaybackState = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
        .setState(code, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
        .build()

    /** 生成一段 3 秒 220Hz 的提示音（音量压得很低，别突然把用户吓一跳） */
    private fun tone(): File {
        val file = File(context.cacheDir, "probe-tone.wav")
        if (file.exists() && file.length() > 1_000) return file
        val rate = 22_050
        val seconds = 3
        val samples = rate * seconds
        val amp = 3_000.0
        val data = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amp * sin(2 * PI * 220.0 * i / rate)).toInt()
            data[i * 2] = (v and 0xff).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        FileOutputStream(file).use { out ->
            out.write(wavHeader(data.size, rate, 1, 16))
            out.write(data)
        }
        return file
    }

    private fun wavHeader(dataSize: Int, rate: Int, channels: Int, bits: Int): ByteArray {
        val byteRate = rate * channels * bits / 8
        val h = ByteArray(44)
        fun putString(offset: Int, s: String) {
            s.forEachIndexed { i, c -> h[offset + i] = c.code.toByte() }
        }
        fun putInt(offset: Int, v: Int) {
            h[offset] = (v and 0xff).toByte()
            h[offset + 1] = ((v shr 8) and 0xff).toByte()
            h[offset + 2] = ((v shr 16) and 0xff).toByte()
            h[offset + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(offset: Int, v: Int) {
            h[offset] = (v and 0xff).toByte()
            h[offset + 1] = ((v shr 8) and 0xff).toByte()
        }
        putString(0, "RIFF")
        putInt(4, 36 + dataSize)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, channels)
        putInt(24, rate)
        putInt(28, byteRate)
        putShort(32, channels * bits / 8)
        putShort(34, bits)
        putString(36, "data")
        putInt(40, dataSize)
        return h
    }

    companion object {
        const val TAG = "yishou-probe"
    }
}
