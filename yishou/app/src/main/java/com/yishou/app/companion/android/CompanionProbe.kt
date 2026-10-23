package com.yishou.app.companion.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yishou.app.YishouApp
import com.yishou.app.companion.core.Capability
import com.yishou.app.companion.core.CaptureHolder
import com.yishou.app.companion.core.PauseOutcome
import com.yishou.app.companion.core.ProbeReport
import com.yishou.app.companion.core.ProbeResult
import com.yishou.app.companion.core.ProbeVerdict
import com.yishou.app.log.RunLog
import com.yishou.app.look.ScreenLookService
import com.yishou.app.scroll.ScrollService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 探针跑起来时的三个状态：声音、画面、已经观察了多久 */
data class ProbeStatus(
    val running: Boolean = false,
    val sound: String = "声音：没开",
    val frames: String = "画面：没开",
    val elapsedSec: Long = 0,
)

/**
 * T1 探针的调度器：一次只开一路投屏，音画同时观察一段时间，然后把结论落到 [ProbeReport]。
 *
 * 三条不走样的规矩：
 * 1. 拿不到投屏就直说谁占着，不去抢；
 * 2. 读到的全是静音只报「没有有效声音」，不报「不支持」；
 * 3. 暂停要看到状态变成暂停才算数，发过指令不算。
 */
class CompanionProbe(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = context.applicationContext as YishouApp

    private val audio = PlaybackCaptureProbe(context)
    private val frames = ScreenFrameProbe(context)
    private val media = MediaControlProbe(context)
    private val selfPlayer = SelfTestPlayer(context)

    private var projection: MediaProjection? = null
    private var ticker: Job? = null
    private var startedAt = 0L
    private var windowMs = 20_000L
    private var audioError: String? = null
    private var frameError: String? = null
    private var revoked = false

    private val _report = MutableStateFlow(ProbeReport())
    val report: StateFlow<ProbeReport> = _report.asStateFlow()

    private val _status = MutableStateFlow(ProbeStatus())
    val status: StateFlow<ProbeStatus> = _status.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)
    /** 最近一次动作的结果，界面上直接显示 */
    val note: StateFlow<String?> = _note.asStateFlow()

    val audioLevel: StateFlow<Float> = audio.level
    val audioSummary: StateFlow<String> = audio.summary
    val frameSummary: StateFlow<String> = frames.stats
    val playerNote: StateFlow<String> = selfPlayer.note

    /** 用户在系统弹窗里同意之后调用；成功返回 null */
    @Suppress("DEPRECATION")
    fun startCapture(resultCode: Int, data: Intent, windowSec: Long = 20): String? {
        if (projection != null) return "已经在观察了"
        // 现有两个看屏服务也用投屏，先问它们
        if (ScreenLookService.running.value) return "看屏正开着，先到通知栏停掉看屏"
        if (ScrollService.running.value) return "刷着练正开着，先到通知栏停掉刷着练"
        CaptureArbiter.acquire(CaptureHolder.COMPANION)?.let { return it }
        if (app.partnerVoice.active) {
            _note.value = "语音伙伴正在听：这次同时开内录，播报可能会被自己录进去，先只做观察。"
        }

        val p = try {
            context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            // 以前这里把异常吞了，只回一句「没拿到授权」，查不出为什么。
            // 常见原因：Android 14 起要求先有 mediaProjection 类型的前台服务，否则直接拒绝。
            RunLog.e("陪练探针", "拿不到投屏授权", e)
            CaptureArbiter.release(CaptureHolder.COMPANION)
            return "系统拒绝给投屏：${e.message ?: e.javaClass.simpleName}"
        }
        if (p == null || resultCode != Activity.RESULT_OK) {
            CaptureArbiter.release(CaptureHolder.COMPANION)
            return "没有拿到投屏授权（系统回执无效，回执码 $resultCode）"
        }
        projection = p
        revoked = false
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 系统收回（下拉点停止、投屏被别的抢占）：立刻收摊并记账
                revoked = true
                finishCapture()
            }
        }, Handler(Looper.getMainLooper()))

        audioError = audio.start(p, scope)
        frameError = frames.start(p)

        startedAt = System.currentTimeMillis()
        windowMs = windowSec * 1_000L
        _status.value = ProbeStatus(running = true)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                val elapsed = System.currentTimeMillis() - startedAt
                _status.value = ProbeStatus(
                    running = true,
                    sound = "声音：" + (audioError ?: if (audio.heard()) "收到" else "在读") + " · 峰值 ${audio.peak().toInt()}",
                    frames = "画面：" + (frameError ?: "${frames.seen} 帧 / 留 ${frames.kept}"),
                    elapsedSec = elapsed / 1_000,
                )
                if (elapsed >= windowMs) break
            }
            finishCapture()
        }
        return null
    }

    /** 收摊并出结论。可以提前点，也可以在窗口结束时自动调。 */
    fun finishCapture() {
        if (projection == null && !_status.value.running) return
        ticker?.cancel()
        ticker = null
        val observed = ((System.currentTimeMillis() - startedAt).coerceAtLeast(0)) / 1_000
        val audioSeconds = audio.totalMs() / 1_000.0
        val kept = frames.kept
        val seen = frames.seen

        audio.stop()
        frames.stop()
        val p = projection
        projection = null
        try {
            p?.stop()
        } catch (e: Exception) {
            RunLog.e("陪练探针", "停投屏出错", e)
        }
        CaptureArbiter.release(CaptureHolder.COMPANION)
        _status.value = ProbeStatus(running = false, sound = "声音：已停", frames = "画面：已停", elapsedSec = observed)

        val at = System.currentTimeMillis()
        _report.value = _report.value
            .with(
                ProbeResult.of(
                    Capability.EXCLUSIVE_OWNER,
                    if (revoked) ProbeVerdict.DENIED else ProbeVerdict.OK,
                    if (revoked) "系统收回了投屏授权，资源已释放" else "整场只有一手持有投屏，看屏和刷着练都没开",
                    at,
                ),
            )
            .with(audioResult(audioSeconds, observed, at))
            .with(frameResult(seen, kept, observed, at))
    }

    private fun audioResult(seconds: Double, observed: Long, at: Long): ProbeResult {
        val cap = Capability.CAPTURE_AUDIO
        val err = audioError
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ProbeResult.of(cap, ProbeVerdict.UNSUPPORTED, "系统低于 Android 10，没有内录接口", at)
        }
        if (err != null) {
            val unsupported = err.contains("权限") || err.contains("低于 Android 10")
            return ProbeResult.of(cap, if (unsupported) ProbeVerdict.DENIED else ProbeVerdict.FAILED, err, at)
        }
        return if (audio.heard()) {
            ProbeResult.of(
                cap, ProbeVerdict.OK,
                "内录读到 ${seconds} 秒声音，峰值 ${audio.peak().toInt()}（观察 $observed 秒）", at,
            )
        } else {
            ProbeResult.of(
                cap, ProbeVerdict.SILENT,
                "读了 ${seconds} 秒，峰值 ${audio.peak().toInt()}：这段时间没有声音（目标 App 可能静音、没在播，或禁止被采集）", at,
            )
        }
    }

    private fun frameResult(seen: Int, kept: Int, observed: Long, at: Long): ProbeResult {
        val cap = Capability.CAPTURE_FRAME
        val err = frameError
        if (err != null) return ProbeResult.of(cap, ProbeVerdict.FAILED, err, at)
        if (revoked) return ProbeResult.of(cap, ProbeVerdict.DENIED, "中途被系统收回授权，只看到 $seen 帧", at)
        return when {
            seen == 0 -> ProbeResult.of(cap, ProbeVerdict.UNKNOWN, "观察 $observed 秒，一帧都没送过来", at)
            kept == 0 -> ProbeResult.of(cap, ProbeVerdict.OK, "看到 $seen 帧，画面一直没变，按重复丢掉（这算通路正常）", at)
            else -> ProbeResult.of(cap, ProbeVerdict.OK, "观察 $observed 秒：看到 $seen 帧，留下 $kept 帧", at)
        }
    }

    // ---- 暂停/恢复 ----

    /** 先用自控播放器验这条路径，不牵扯用户的视频 App */
    fun startSelfPlayer(): String? {
        val err = selfPlayer.start()
        if (err == null) _note.value = "自控播放器开了。点“测暂停”，一手会让它停一下再核实。"
        return err
    }

    fun stopSelfPlayer() {
        selfPlayer.stop()
        _note.value = "自控播放器关了"
    }

    fun pauseSelfPlayer() {
        scope.launch {
            val outcome = pauseAndVerify { it.packageName == context.packageName }
            _note.value = "暂停测试：${outcome.first.label} · ${outcome.second}"
        }
    }

    fun pauseTargetApp() {
        scope.launch {
            val outcome = pauseAndVerify { it.packageName != context.packageName }
            _note.value = "暂停目标 App：${outcome.first.label} · ${outcome.second}"
        }
    }

    private suspend fun pauseAndVerify(match: (android.media.session.MediaController) -> Boolean): Pair<PauseOutcome, String> {
        val at = System.currentTimeMillis()
        val (outcome, evidence) = media.pauseAndVerify(match)
        _report.value = _report.value.with(
            ProbeResult.of(Capability.PLAYBACK_CONTROL, outcome.verdict, evidence, at),
        )
        return outcome to evidence
    }

    /** 通知使用权开没开 */
    fun notificationAccess(): Boolean = media.hasAccess()

    fun sessionDescription(): String = media.describe()

    /** 界面上要一句话说明时调这个（比如授权失败、被别的服务挡住） */
    fun say(text: String) {
        _note.value = text
    }

    fun stopAll() {
        ticker?.cancel()
        ticker = null
        audio.stop()
        frames.stop()
        selfPlayer.stop()
        val p = projection
        projection = null
        try {
            p?.stop()
        } catch (e: Exception) {
            RunLog.e("陪练探针", "停投屏出错", e)
        }
        CaptureArbiter.release(CaptureHolder.COMPANION)
        _status.value = ProbeStatus()
    }

    fun shutdown() {
        stopAll()
        scope.cancel()
    }
}
