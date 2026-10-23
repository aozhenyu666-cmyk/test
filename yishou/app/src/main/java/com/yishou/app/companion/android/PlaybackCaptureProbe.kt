package com.yishou.app.companion.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import com.yishou.app.companion.core.AudioSignal
import com.yishou.app.companion.core.Pcm
import com.yishou.app.log.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统内录探针：用用户刚同意的 MediaProjection 去抓「别的 App 放出来的声音」。
 *
 * 三个前提缺一不可：系统版本 ≥ 29、有录音权限、同一次投屏授权。
 * 目标 App 自己可以禁止被采集（allowAudioPlaybackCapture=false），这时读到的全是静音——
 * 界面只能说「当前没有有效声音」，不能断言这个 App 不支持。
 *
 * 注意：系统不会把一手自己的声音算进内录，所以这项只能拿真实的目标 App 验，
 * 用自控播放器验证不了（自控播放器验的是暂停那条路径）。
 */
class PlaybackCaptureProbe(private val context: Context) {

    private val _level = MutableStateFlow(0f)
    /** 0–1 的音量，界面画条用 */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _summary = MutableStateFlow("还没开始读音频")
    val summary: StateFlow<String> = _summary.asStateFlow()

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var signal = AudioSignal()

    /** 开始读；成功返回 null，失败返回能直接显示的原因 */
    @SuppressLint("MissingPermission")
    fun start(projection: MediaProjection, scope: CoroutineScope, targetUid: Int? = null): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "这台系统低于 Android 10，没有内录接口"
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "没有录音权限，内录开不了"
        }
        if (record != null) return null

        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return "这台设备不支持 ${SAMPLE_RATE}Hz 单声道录音"

        val rec = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .apply { if (targetUid != null && targetUid > 0) addMatchingUid(targetUid) }
                .build()
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(min * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            RunLog.e("陪练探针", "内录建不起来", e)
            return "内录建不起来：${e.message ?: e.javaClass.simpleName}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "内录初始化失败（可能别的 App 正占着采集）"
        }

        signal = AudioSignal()
        record = rec
        job = scope.launch {
            val frame = ShortArray(SAMPLE_RATE / 20)
            try {
                rec.startRecording()
            } catch (e: Exception) {
                RunLog.e("陪练探针", "内录开不了", e)
                _summary.value = "内录开不了：${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            withContext(Dispatchers.Default) {
                while (isActive) {
                    val n = try {
                        rec.read(frame, 0, frame.size)
                    } catch (e: Exception) {
                        -1
                    }
                    if (n <= 0) break
                    val rms = Pcm.rmsOf(frame, n)
                    signal.feed(rms, Pcm.durationMs(n, SAMPLE_RATE))
                    _level.value = (rms / 4000.0).coerceIn(0.0, 1.0).toFloat()
                    _summary.value = signal.summary()
                }
            }
            delay(50)
        }
        return null
    }

    fun stop() {
        job?.cancel()
        job = null
        val rec = record
        record = null
        if (rec != null) {
            try { rec.stop() } catch (e: IllegalStateException) { }
            rec.release()
        }
        _level.value = 0f
    }

    fun heard(): Boolean = signal.heard()
    fun summaryText(): String = signal.summary()
    fun peak(): Double = signal.maxRms
    fun totalMs(): Long = signal.totalMs

    companion object {
        const val SAMPLE_RATE = 44_100

        /** 探针界面用的长句说明，跟实际能力一致 */
        fun limitationNote(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "内录只能抓别的 App 的声音；一手自己放的声音系统不算在内。"
            } else {
                "这台系统低于 Android 10，没有内录接口。"
            }
    }
}
