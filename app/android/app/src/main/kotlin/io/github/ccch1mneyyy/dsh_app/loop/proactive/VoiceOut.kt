package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.ccch1mneyyy.dsh_app.loop.core.VoicePlan
import java.util.Locale

/**
 * B5 语音：系统 TTS（zh-CN），不联网、不花钱、今天就能用。
 *
 * 分工是这条工单的要点：**能算的在插件、能看的在手机**。
 *   · 念不念（总开关 / 安静时段 / 渠道）和什么调子，插件从情绪池算好塞进 `extra.voice`；
 *   · 手机只判它才知道的事——媒体音量为 0、勿扰、连麦舱里（会和 Gemini 抢麦）。
 * 所以 [gate] 是个吃三个布尔的纯函数，每种降级都能在单测里走一遍；真正碰 Android 的只有
 * [speak] 之后的几行。[gate] 里刻意**没有**"总开关关着"这一项：关着插件根本不下发计划，
 * 手机再存一份开关就多了一个会和插件对不上的状态。
 *
 * 念没念出来要回账本（`/event` 的 `voice-out`，见 ProactiveRuntime.reportVoice）：
 * 只报"引擎收下了"没用，所以这里等到 utterance 真的 [onDone] 才算念出来。
 *
 * shortcut: 同一时刻只跟一条——新的那条用 QUEUE_FLUSH 念出来时会把上一条掐掉，
 * 被掐的那条就没有回报了（回调只有一个槽位）。要每条都精确记账，得按 utteranceId
 * 存一张回调表；现在一球一个 token、正常间隔 20 分钟以上，掐掉只发生在连发那几秒。
 */
object VoiceOut {

    /** 降级原因（写进账本的 `why`）。念出来了就没有原因。 */
    const val WHY_SESSION = "session"
    const val WHY_DND = "dnd"
    const val WHY_MUTED = "muted"
    const val WHY_NO_TTS = "no-tts"

    private const val TRIAL_TEXT = "能听见吗？我在。"

    /** 手机这一侧能看到的、决定念不念的三件事。 */
    data class Gate(val inSession: Boolean, val dnd: Boolean, val mediaMuted: Boolean)

    /**
     * null = 可以念；否则是降级原因。
     * 顺序不是随便排的：连麦舱最优先——那里出声是真的会打断 Gemini 的对话。
     */
    fun gate(g: Gate): String? = when {
        g.inSession -> WHY_SESSION
        g.dnd -> WHY_DND
        g.mediaMuted -> WHY_MUTED
        else -> null
    }

    // by lazy：这个 object 会被单测直接引用（gate 是纯函数），字段初始化时就去碰 Looper
    // 会在 JVM 单测里直接炸，连纯函数都测不了。真机上第一次用它的线程本来就是主线程。
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var app: Context? = null
    private var engine: TextToSpeech? = null
    private var ready = false

    /** 还没念的那一条。 */
    private var queued: VoicePlan? = null

    /** 这一条的回报，只会有一次。 */
    private var after: ((String?) -> Unit)? = null

    fun install(context: Context) {
        if (app == null) app = context.applicationContext
    }

    /**
     * 念一条。[done] 收的是降级原因（null = 真的念完了）。
     * 调用方永远拿得到一次回话：要么现在（闸没过），要么引擎那边出结果之后。
     */
    fun speak(plan: VoicePlan, done: (why: String?) -> Unit) {
        val why = gate(currentGate())
        if (why != null) {
            done(why)
            return
        }
        queued = plan
        after = done
        // 引擎第一次要绑定：绑好之前先留着，回调里接上（不把这一条算成降级）。
        if (ensureEngine() == null) finish(WHY_NO_TTS) else if (ready) flush()
    }

    /** 设置页的试听：绕开三道闸（关了也能听），引擎状态照认。空着没装东西就什么都不做。 */
    fun trySay(done: (why: String?) -> Unit) {
        queued = VoicePlan(TRIAL_TEXT, VoicePlan.DEFAULT_RATE, VoicePlan.DEFAULT_PITCH)
        after = done
        if (ensureEngine() == null) finish(WHY_NO_TTS) else if (ready) flush()
    }

    private fun ensureEngine(): TextToSpeech? {
        engine?.let { return it }
        val context = app ?: return null
        engine = TextToSpeech(context) { status -> onInit(status) }
        return engine
    }

    private fun onInit(status: Int) {
        val tts = engine
        // zh-CN 固定：插件写的字全是中文，跟着系统语言走会在英文机上念成字母。
        val lang = if (status == TextToSpeech.SUCCESS && tts != null) {
            runCatching { tts.setLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        ready = lang >= TextToSpeech.LANG_AVAILABLE
        if (!ready) {
            finish(WHY_NO_TTS)
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // 只认 onDone：speak() 返回 SUCCESS 只是"收下了"，那时还没出声。
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post { finish(null) }
            }

            // 老引擎只调这一个；两个都实现，免得某个引擎的失败悄无声息。
            override fun onError(utteranceId: String?) {
                main.post { finish(WHY_NO_TTS) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { finish(WHY_NO_TTS) }
            }
        })
        flush()
    }

    private fun flush() {
        val plan = queued ?: return
        val tts = engine ?: run {
            finish(WHY_NO_TTS)
            return
        }
        queued = null
        tts.setSpeechRate(plan.rate)
        tts.setPitch(plan.pitch)
        val ok = runCatching {
            tts.speak(plan.text, TextToSpeech.QUEUE_FLUSH, null, "dsh-voice-" + System.nanoTime())
        }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS
        // 收下了就等 onDone / onError；这里不提前报"念出来了"。
        if (!ok) finish(WHY_NO_TTS)
    }

    /** 回报一次，之后这个槽位空着——onDone 和 onError 不会各报一次。 */
    private fun finish(why: String?) {
        val done = after ?: return
        after = null
        done(why)
    }

    private fun currentGate(): Gate {
        val context = app ?: return Gate(inSession = false, dnd = true, mediaMuted = true)
        val audio = runCatching {
            context.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull()
        val filter = runCatching {
            context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        }.getOrNull()
        return Gate(
            inSession = ProactiveRuntime.sessionActive(),
            // 读不到（服务没起、没权限）就当勿扰：宁可退回文字，也不在她开会时突然出声。
            dnd = filter == null || filter != NotificationManager.INTERRUPTION_FILTER_ALL,
            mediaMuted = audio == null || audio <= 0,
        )
    }
}
