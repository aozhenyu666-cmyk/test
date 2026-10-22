package com.moling.app.live

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.moling.app.BrainHost
import com.moling.core.LiveTurn
import com.moling.core.live.Can
import com.moling.core.live.Modality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * 她出现的方式（v9 反免疫）：大脑每次挑一种（[LiveTurn.modality]），这里照着弹。
 *
 * 现在接好的：GATE（拦人页）、PEEK（底部卡片）、PET（桌宠）、STARE（盯着你）、
 * WHISPER（耳机里说一句）、COUNCIL（参谋弹幕）。QUIZ（捉虫题）由 B 组接。
 * 没悬浮窗权限 / 没耳机时自动退回底部卡片，功能不缺，只是样子变了。
 *
 * 他怎么回应，各个界面调 [BrainHost.react] 报回来（反免疫账本靠它）。
 */
object Appear {
    private const val TAG = "Appear"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 此刻能用哪些方式。 */
    fun can(context: Context): Can = Can(
        overlay = Settings.canDrawOverlays(context),
        whisper = canWhisper(context),
        quiz = true,
    )

    /** 耳语能不能用：戴着耳机、不在安静时段、设置里开着（Z3）。 */
    fun canWhisper(context: Context): Boolean = Whisper.can(context)

    fun show(context: Context, turn: LiveTurn, label: String) {
        // B4：他在设置里关掉的方式一律退回底部卡片（拦人不受影响，它有自己的开关）。
        val disabled = runCatching { BrainHost.settingsStore.disabledModalities() }.getOrDefault(emptySet())
        if (turn.modality in disabled && turn.modality != Modality.GATE) {
            unobserved(turn.modality)
            LiveScreens.peek(context, turn.mood, turn.line, turn.modality)
            return
        }
        val overlay = Settings.canDrawOverlays(context)
        when (turn.modality) {
            Modality.GATE -> LiveScreens.gate(context, turn.mood, turn.line, label)
            Modality.PET ->
                if (!overlay || PetOverlay.petMuted(context)) {
                    unobserved(turn.modality)
                    LiveScreens.peek(context, turn.mood, turn.line, turn.modality)
                } else {
                    PetOverlay.show(context, turn.mood, turn.line)
                }
            Modality.STARE ->
                if (!overlay) {
                    unobserved(turn.modality)
                    LiveScreens.peek(context, turn.mood, turn.line, turn.modality)
                } else {
                    PetOverlay.stare(context, turn.mood, turn.line)
                }
            Modality.WHISPER ->
                if (canWhisper(context)) Whisper.say(context, turn.mood, turn.line)
                else {
                    unobserved(turn.modality)
                    LiveScreens.peek(context, turn.mood, turn.line, turn.modality)
                }
            Modality.COUNCIL ->
                if (!overlay) {
                    unobserved(turn.modality)
                    LiveScreens.peek(context, turn.mood, turn.line, turn.modality)
                } else {
                    council(context, turn, label)
                }
            Modality.QUIZ ->
                turn.quiz?.let { QuizActivity.open(context, turn.mood, turn.line, it) }
                    ?: run {
                        unobserved(turn.modality)
                        LiveScreens.peek(context, turn.mood, turn.line, turn.modality)
                    }
            else -> LiveScreens.peek(context, turn.mood, turn.line, turn.modality)
        }
    }

    /**
     * F11-5：这一次其实没按选定的方式出现（浮层被系统拦 / 没戴耳机 / 设置里关了 / 没材料出题），
     * 只能退回底部卡片或通知——他可能根本没看见，记 UNOBSERVED，之后不算“他不理我”。
     * PEEK 本身就是那张卡片，不记。
     */
    private fun unobserved(m: Modality) {
        if (m == Modality.PEEK) return
        runCatching { BrainHost.unobserved(m) }
            .onFailure { Log.w(TAG, "曝光没报上：${it.javaClass.simpleName}") }
    }

    /** Z4 参谋弹幕：先问参谋（要调模型），拿不到就退回桌宠；拿到就飘四句，最后桌宠带着“合起来”的话。 */
    private fun council(context: Context, turn: LiveTurn, label: String) {
        val app = context.applicationContext
        scope.launch {
            val c = runCatching { BrainHost.council("他现在在「$label」里。接下来十分钟他该怎么办？") }.getOrNull()
            if (c == null) {
                PetOverlay.show(app, turn.mood, turn.line)
            } else {
                PetOverlay.council(
                    app,
                    listOf("教练：${c.coach}", "军师：${c.strategist}", "对手：${c.critic}", "朋友：${c.friend}"),
                    turn.mood,
                    c.together,
                )
            }
        }
    }
}