package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.view.Display

/** Gemini Live 三合一硬件/系统特征快照（退后台、全屏看视频时仍可感知）。 */
data class LiveSignalSnapshot(
    val audioInComm: Boolean = false,
    val micRecording: Boolean = false,
    val virtualDisplayActive: Boolean = false,
    val screenShareText: Boolean = false,
    val uiLiveActive: Boolean = false,
    val summary: String = "未检测到 Live 活动",
) {
    val liveOnline: Boolean
        get() = audioInComm || micRecording || virtualDisplayActive || screenShareText || uiLiveActive

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "liveOnline" to liveOnline,
        "audioInComm" to audioInComm,
        "micRecording" to micRecording,
        "virtualDisplayActive" to virtualDisplayActive,
        "screenShareText" to screenShareText,
        "uiLiveActive" to uiLiveActive,
        "summary" to summary,
    )
}

/**
 * Gemini Live 开启与共享屏幕状态探测器：
 * 1. AudioManager 通话态 / 麦克风录音配置（哪怕退后台看 B 站视频也能抓到）
 * 2. DisplayManager 虚拟投影屏探测（系统把共享屏幕胶囊收进状态栏后仍能探测到 MediaProjection 副屏）
 * 3. 无障碍屏幕关键词兜底（Live / 正在聆听 / 共享屏幕）
 */
object LiveSignalMonitor {
    private val GEMINI_PACKAGES = setOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox",
    )

    fun evaluateSignals(
        audioInComm: Boolean,
        micRecording: Boolean,
        virtualDisplayActive: Boolean,
        screenShareText: Boolean,
        uiLiveActive: Boolean,
    ): LiveSignalSnapshot {
        val parts = ArrayList<String>(4)
        if (audioInComm) parts.add("音频通话态占用")
        if (micRecording) parts.add("麦克风采集激活")
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
            summary = summary,
        )
    }

    fun probe(context: Context, frontPkgNow: String?, screenText: String?): LiveSignalSnapshot {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val audioInComm = runCatching {
            am != null && (am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL)
        }.getOrDefault(false)

        val micRecording = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && am != null) {
                am.activeRecordingConfigurations.isNotEmpty()
            } else {
                false
            }
        }.getOrDefault(false)

        val virtualDisplayActive = runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return@runCatching false
            dm.displays.any { d ->
                if (d.displayId == Display.DEFAULT_DISPLAY) return@any false
                if (d.state == Display.STATE_OFF) return@any false
                val n = d.name.orEmpty().lowercase()
                n.contains("screen") || n.contains("projection") || n.contains("cast") ||
                    n.contains("gemini") || (d.flags and Display.FLAG_PRESENTATION) != 0 ||
                    (d.flags and Display.FLAG_PRIVATE) != 0
            }
        }.getOrDefault(false)

        val shareText = LiveKeeperPolicy.isScreenShareText(screenText)
        val uiLive = frontPkgNow != null && frontPkgNow in GEMINI_PACKAGES && !screenText.isNullOrBlank() &&
            (screenText.contains("Live") || screenText.contains("正在聆听") ||
                screenText.contains("静音") || screenText.contains("结束语音"))

        return evaluateSignals(
            audioInComm = audioInComm,
            micRecording = micRecording,
            virtualDisplayActive = virtualDisplayActive,
            screenShareText = shareText,
            uiLiveActive = uiLive,
        )
    }
}
