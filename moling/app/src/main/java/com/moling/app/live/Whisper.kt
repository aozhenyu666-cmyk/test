package com.moling.app.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.moling.app.data.SettingsStore
import com.moling.app.ui.Speaker
import com.moling.core.flow.Flow
import com.moling.core.live.Mood

/*
 * Z3 耳语：戴着耳机的时候，把别的 App 的声音压低，在耳机里说一句。
 *
 * 不自己开麦克风（底线 4）：说完只在屏幕边上亮 8 秒“回她”的气泡，他点了才打开卡片回话。
 * 判断能不能用：有有线 / 蓝牙耳机、不在 23:30–07:00、设置里“她可以在耳机里说话”开着。
 */
object Whisper {
    /** 耳机类型（输出设备）。BLE 耳机只有 API 31 起有，分开加，旧系统上不碰这个常量。 */
    private fun headsetTypes(): Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= 31) runCatching { add(AudioDeviceInfo.TYPE_BLE_HEADSET) }
    }

    fun can(context: Context): Boolean = runCatching {
        val store = SettingsStore(context.applicationContext)
        if (!store.whisperOn()) return false
        val minute = Flow.minuteOf(System.currentTimeMillis())
        if (minute >= 23 * 60 + 30 || minute < 7 * 60) return false
        hasHeadset(context)
    }.getOrDefault(false)

    fun hasHeadset(context: Context): Boolean = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes() }
    }.getOrDefault(false)

    /**
     * 压低别人的声音，念一句；念完（或念不出来）在屏幕边上亮 8 秒「回她」。
     * 调用方只在 [can] 为 true 时调它。
     */
    fun say(context: Context, mood: Mood, line: String) {
        val app = context.applicationContext
        Speaker.prepare(app)
        val am = app.getSystemService(AudioManager::class.java)
        var focus: AudioFocusRequest? = null
        runCatching {
            if (am != null) {
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                am.requestAudioFocus(focus!!)
            }
        }
        Speaker.speak(line) {
            // 念完了：把别的 App 的声音放回去，再亮 8 秒气泡（亮完报 IGNORED 由 PetOverlay 负责）。
            runCatching { focus?.let { am?.abandonAudioFocusRequest(it) } }
            // 气泡上写「回她」；点开卡片时用原来那句（PetOverlay 在 whisper 模式下按这个约定处理）。
            PetOverlay.show(app, mood, line, whisper = true)
        }
    }
}