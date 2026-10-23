package com.yishou.app.companion.android

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.yishou.app.companion.core.MediaPosition
import com.yishou.app.companion.core.PauseOutcome
import com.yishou.app.companion.core.PauseProof
import com.yishou.app.companion.core.PlayState
import com.yishou.app.companion.core.PositionSource
import com.yishou.app.companion.core.ResumePlan
import com.yishou.app.log.RunLog
import kotlinx.coroutines.delay

/**
 * 只是为了让系统允许一手读「当前有哪些播放会话」。
 * 它不读通知内容、不弹东西、不做任何事；用户在系统设置里关掉它，暂停能力就跟着失效。
 */
class MediaNotificationListener : NotificationListenerService()

/** 恢复尝试的结果：恢复了 / 本来就不用 / 没成功（没成功要如实说，不能报「已继续」） */
sealed interface ResumeResult {
    data object Resumed : ResumeResult
    data object NotNeeded : ResumeResult
    data class Failed(val why: String) : ResumeResult
}

/** 暂停/恢复探针：发指令，然后再看一眼前状态，两者一致才算数。 */
class MediaControlProbe(private val context: Context) {

    /** 上一次确实由自己暂停的目标；恢复只认它 */
    private var pausedPkg: String? = null
    private var pausedOutcome: PauseOutcome? = null

    /** 通知使用权开了没有（没开就读不到别的 App 的播放会话） */
    fun hasAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)

    private fun sessions(): List<MediaController> = try {
        val mgr = context.getSystemService(MediaSessionManager::class.java)
        mgr.getActiveSessions(ComponentName(context, MediaNotificationListener::class.java))
    } catch (e: SecurityException) {
        RunLog.e("陪练探针", "没有通知使用权，读不到播放会话", e)
        emptyList()
    } catch (e: Exception) {
        RunLog.e("陪练探针", "读播放会话出错", e)
        emptyList()
    }

    /** 给界面看：现在有哪些能控制的会话 */
    fun describe(): String {
        if (!hasAccess()) return "没有通知使用权，读不到别的 App 的播放会话"
        val list = sessions()
        if (list.isEmpty()) return "现在没有活动的播放会话"
        return list.joinToString("、") { c ->
            val pkg = c.packageName ?: "未知"
            "$pkg（${c.playbackState?.let { stateName(it.state) } ?: "状态未知"}）"
        }
    }

    /**
     * 暂停并核实。[match] 用来挑目标会话；挑不到就是「没有可控制的播放会话」，不是「失败」。
     * 返回结论 + 一句证据文本，界面照抄。
     */
    suspend fun pauseAndVerify(match: (MediaController) -> Boolean): Pair<PauseOutcome, String> {
        if (!hasAccess()) {
            return PauseOutcome.UNSUPPORTED to "没有通知使用权，普通 APK 读不到别的 App 的播放会话"
        }
        val target = sessions().firstOrNull(match)
            ?: return PauseOutcome.UNSUPPORTED to "没有找到可控制的播放会话：${describe()}"
        val before = PlayState.of(target.playbackState?.state ?: -1)
        val sent = try {
            target.transportControls.pause()
            true
        } catch (e: Exception) {
            RunLog.e("陪练探针", "发暂停指令失败", e)
            false
        }
        // 给播放器一点反应时间再看：立刻读多半还是 PLAYING
        delay(700)
        val after = PlayState.of(target.playbackState?.state ?: -1)
        val outcome = PauseProof.judge(sent, before, after)
        if (outcome == PauseOutcome.VERIFIED) {
            pausedPkg = target.packageName
            pausedOutcome = outcome
        } else {
            pausedPkg = null
            pausedOutcome = null
        }
        val who = target.packageName ?: "目标"
        val evidence = "$who：发指令${if (sent) "成功" else "失败"}，状态 ${stateName(before)} → ${stateName(after)}"
        return outcome to evidence
    }

    /** 恢复：只在「刚才是自己停的」前提下调用 */
    suspend fun resume(match: (MediaController) -> Boolean): Pair<Boolean, String> {
        val target = sessions().firstOrNull(match) ?: return false to "会话已经不在了"
        return try {
            target.transportControls.play()
            delay(500)
            val now = PlayState.of(target.playbackState?.state ?: -1)
            (now == PlayState.PLAYING) to "状态现在是 ${stateName(now)}"
        } catch (e: Exception) {
            RunLog.e("陪练探针", "发恢复指令失败", e)
            false to "恢复指令失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 现在真正在放音的 App（不含一手自己）。
     *
     * 用它来认「这一场到底在听谁」：开局时用户还停在一手，拿不到目标应用；
     * 等目标 App 开始出声再认，比让用户手填包名可靠。认不出就返回 null，不乱猜。
     */
    fun playingPackages(): List<String> {
        if (!hasAccess()) return emptyList()
        return sessions()
            .filter { c ->
                val pkg = c.packageName
                pkg != null && pkg != context.packageName && PlayState.of(c.playbackState?.state ?: -1) == PlayState.PLAYING
            }
            .mapNotNull { it.packageName }
            .distinct()
    }

    fun playingPackage(): String? = playingPackages().firstOrNull()

    /**
     * 目标 App 的播放进度。只在它确实在放的时候给：
     * 暂停时播放器会把位置冻住，拿它当跳播证据会误判。
     */
    fun positionOf(pkg: String): MediaPosition? {
        val c = sessions().firstOrNull { it.packageName == pkg } ?: return null
        val st = c.playbackState ?: return null
        if (PlayState.of(st.state) != PlayState.PLAYING) return null
        return MediaPosition(st.position.takeIf { it >= 0 }, PositionSource.MEDIA_SESSION)
    }

    /** 换段、停了、恢复过了：忘掉这次暂停，别拿旧的去恢复新视频 */
    fun forgetPause() {
        pausedPkg = null
        pausedOutcome = null
    }

    /**
     * 恢复「这一次确实由自己暂停」的同一个 App。
     *
     * 只认包名，且当前确实还是暂停：用户已经自己按了继续就不重复发指令，
     * 换了 App 或者状态不明一律不动，留给用户。
     */
    suspend fun resumeOwn(): ResumeResult {
        val outcome = pausedOutcome ?: return ResumeResult.NotNeeded
        val pkg = pausedPkg ?: return ResumeResult.NotNeeded
        val c = sessions().firstOrNull { it.packageName == pkg }
        if (c == null) {
            forgetPause()
            return ResumeResult.NotNeeded
        }
        val state = PlayState.of(c.playbackState?.state ?: -1)
        when (PauseProof.resumePlan(outcome, stillSameSession = true, userTouched = false, state = state)) {
            ResumePlan.ALREADY_PLAYING -> {
                forgetPause()
                return ResumeResult.NotNeeded
            }
            ResumePlan.LEAVE -> {
                forgetPause()
                return ResumeResult.NotNeeded
            }
            ResumePlan.PLAY -> Unit
        }
        val sent = try {
            c.transportControls.play()
            true
        } catch (e: Exception) {
            RunLog.e("视频陪练", "发恢复指令失败", e)
            false
        }
        delay(500)
        val after = PlayState.of(c.playbackState?.state ?: -1)
        forgetPause()
        return when {
            !sent -> ResumeResult.Failed("恢复指令没发出去")
            after == PlayState.PLAYING -> ResumeResult.Resumed
            else -> ResumeResult.Failed("发了指令但没接着放")
        }
    }

    /** 找自己那个自控播放器用的标签 */
    fun selfSession(): MediaController? = sessions().firstOrNull { it.sessionToken != null && it.packageName == context.packageName }

    companion object {
        fun stateName(s: PlayState): String = when (s) {
            PlayState.PLAYING -> "在放"
            PlayState.PAUSED -> "暂停"
            PlayState.STOPPED -> "没在放"
            PlayState.UNKNOWN -> "状态不明"
        }

        fun stateName(code: Int): String = stateName(PlayState.of(code))
    }
}
