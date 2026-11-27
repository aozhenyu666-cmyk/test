package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Gemini Live 三合一+硬件/系统特征快照（退后台、全屏看视频、开摄像头时仍可感知）。 */
data class LiveSignalSnapshot(
    val audioInComm: Boolean = false,
    val micRecording: Boolean = false,
    val virtualDisplayActive: Boolean = false,
    val screenShareText: Boolean = false,
    val uiLiveActive: Boolean = false,
    val voicePlayback: Boolean = false,
    val cameraInUse: Boolean = false,
    val voiceCommMic: Boolean = false,
    val summary: String = "未检测到 Live 活动",
) {
    /**
     * 严防误报，同时覆盖 OriginOS 不切 MODE_IN_COMMUNICATION 与 Gemini 视频/摄像头模式：
     * - 仅 AudioManager 通话/语音态 + (真实麦克风采集 或 VoIP/助手语音播放流)
     * - 或 语音通话麦源 + (VoIP语音流 / 摄像头占用 / 虚拟副屏)
     * - 或 摄像头占用 + (真实麦克风 / 语音流 / Gemini Live 真通话文案)
     * - 或 明确的投影副屏 / Live 屏幕文本 / Gemini Live 真通话文案
     *   才判定 Live 在线；
     * - 绝不把后台零星麦克风采集、普通媒体视频声音或 Gemini 普通聊天页底部 "Live" 按钮误判为 Live 在线。
     */
    val liveOnline: Boolean
        get() = (audioInComm && (micRecording || voicePlayback)) ||
            (voiceCommMic && (voicePlayback || cameraInUse || virtualDisplayActive)) ||
            (cameraInUse && (micRecording || voicePlayback || uiLiveActive)) ||
            virtualDisplayActive ||
            screenShareText ||
            uiLiveActive

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "liveOnline" to liveOnline,
        "audioInComm" to audioInComm,
        "micRecording" to micRecording,
        "voicePlayback" to voicePlayback,
        "virtualDisplayActive" to virtualDisplayActive,
        "screenShareText" to screenShareText,
        "uiLiveActive" to uiLiveActive,
        "cameraInUse" to cameraInUse,
        "voiceCommMic" to voiceCommMic,
        "summary" to summary,
    )
}

/**
 * Gemini Live 开启与共享屏幕/摄像头状态探测器：
 * 1. AudioManager 通话态 / 语音麦源 / VoIP 语音播放流（兼容 OriginOS 等 ROM 不切换 AudioMode）
 * 2. CameraManager 摄像头不可用回调（零 CAMERA 权限感知 Gemini Live 视频通话）
 * 3. DisplayManager 虚拟投影屏探测（系统把共享屏幕胶囊收进状态栏后仍能探测到 MediaProjection 副屏）
 * 4. 无障碍真通话文案兜底（严格区分 Gemini 普通文字页底部的 "Live" 入口按钮与真正通话界面）
 */
object LiveSignalMonitor {
    private val GEMINI_PACKAGES = setOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox",
    )

    private val DIRECT_LIVE_CALL_MARKERS = listOf(
        "正在聆听",
        "轻触可打断",
        "结束语音",
        "结束 live",
        "结束live",
        "end live",
        "tap to interrupt",
        "正在连接",
    )

    private val LIVE_CALL_CONTROL_MARKERS = listOf(
        "静音",
        "取消静音",
        "挂断",
        "共享屏幕",
        "停止共享",
        "mute",
        "unmute",
        "hang up",
        "video",
        "摄像头",
    )

    private val cameraWatched = AtomicBoolean(false)
    private val activeBusyCameras = ConcurrentHashMap.newKeySet<String>()

    private fun ensureCameraWatch(context: Context) {
        if (!cameraWatched.compareAndSet(false, true)) return
        runCatching {
            val cm = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return@runCatching
            cm.registerAvailabilityCallback(
                object : CameraManager.AvailabilityCallback() {
                    override fun onCameraUnavailable(cameraId: String) {
                        activeBusyCameras.add(cameraId)
                    }

                    override fun onCameraAvailable(cameraId: String) {
                        activeBusyCameras.remove(cameraId)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }.onFailure {
            cameraWatched.set(false)
        }
    }

    /** 严防把 Gemini 文字页底部的 "Live" 按钮误判为已在通话中。 */
    internal fun isActiveLiveUiText(frontPkgNow: String?, screenText: String?): Boolean {
        if (frontPkgNow == null || frontPkgNow !in GEMINI_PACKAGES) return false
        val raw = screenText?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val low = raw.lowercase()
        if (DIRECT_LIVE_CALL_MARKERS.any { low.contains(it) }) return true
        val hasLiveWord = low.contains("live")
        val hasCallControl = LIVE_CALL_CONTROL_MARKERS.any { low.contains(it) }
        return hasLiveWord && hasCallControl
    }

    fun evaluateSignals(
        audioInComm: Boolean,
        micRecording: Boolean,
        virtualDisplayActive: Boolean,
        screenShareText: Boolean,
        uiLiveActive: Boolean,
        voicePlayback: Boolean = false,
        cameraInUse: Boolean = false,
        voiceCommMic: Boolean = false,
    ): LiveSignalSnapshot {
        val parts = ArrayList<String>(7)
        if (audioInComm) parts.add("音频通话态占用")
        if (micRecording) parts.add("麦克风采集激活")
        if (voiceCommMic) parts.add("语音通话麦活跃")
        if (voicePlayback) parts.add("VoIP/助手语音流在线")
        if (cameraInUse) parts.add("摄像头共享占用")
        if (virtualDisplayActive) parts.add("虚拟共享副屏在线")
        if (screenShareText) parts.add("屏幕共享文案在线")
        if (uiLiveActive) parts.add("Gemini Live 界面活跃")
        val summary = if (parts.isEmpty()) "未检测到 Live 活动" else parts.joinToString(" · ")
        return LiveSignalSnapshot(
            audioInComm = audioInComm,
            micRecording = micRecording,
            virtualDisplayActive = virtualDisplayActive,
            screenShareText = screenShareText,
            uiLiveActive = uiLiveActive,
            voicePlayback = voicePlayback,
            cameraInUse = cameraInUse,
            voiceCommMic = voiceCommMic,
            summary = summary,
        )
    }

    fun probe(context: Context, frontPkgNow: String?, screenText: String?): LiveSignalSnapshot {
        ensureCameraWatch(context)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val audioInComm = runCatching {
            am != null && (am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL)
        }.getOrDefault(false)

        val recordings = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && am != null) {
                am.activeRecordingConfigurations
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
        val micRecording = recordings.isNotEmpty()
        val voiceCommMic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recordings.any { cfg ->
                val src = cfg.clientAudioSource
                src == MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
                    src == MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
        } else {
            false
        }

        val voicePlayback = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && am != null) {
                am.activePlaybackConfigurations.any { cfg ->
                    val usage = cfg.audioAttributes.usage
                    usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                        usage == AudioAttributes.USAGE_ASSISTANT
                }
            } else {
                false
            }
        }.getOrDefault(false)

        val cameraInUse = activeBusyCameras.isNotEmpty()

        val virtualDisplayActive = runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return@runCatching false
            dm.displays.any { d ->
                if (d.displayId == Display.DEFAULT_DISPLAY) return@any false
                if (d.state == Display.STATE_OFF) return@any false
                val n = d.name.orEmpty().lowercase()
                n.contains("screen") || n.contains("projection") || n.contains("cast") ||
                    n.contains("gemini") || n.contains("media_projection") ||
                    (d.flags and Display.FLAG_PRESENTATION) != 0
            }
        }.getOrDefault(false)

        val shareText = LiveKeeperPolicy.isScreenShareText(screenText)
        val uiLive = isActiveLiveUiText(frontPkgNow, screenText)

        return evaluateSignals(
            audioInComm = audioInComm,
            micRecording = micRecording,
            virtualDisplayActive = virtualDisplayActive,
            screenShareText = shareText,
            uiLiveActive = uiLive,
            voicePlayback = voicePlayback,
            cameraInUse = cameraInUse,
            voiceCommMic = voiceCommMic,
        )
    }
}
