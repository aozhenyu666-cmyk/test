package com.yishou.app.companion.android

import com.yishou.app.YishouApp
import com.yishou.app.companion.core.AskPlan
import com.yishou.app.companion.core.CoachParse
import com.yishou.app.companion.core.CoachPorts
import com.yishou.app.companion.core.CoachReply
import com.yishou.app.companion.core.JudgeParse
import com.yishou.app.companion.core.JudgeReply
import com.yishou.app.companion.core.MediaReceipt
import com.yishou.app.companion.core.PauseOutcome
import com.yishou.app.companion.core.ThinkMove
import com.yishou.app.companion.core.Verdict
import com.yishou.app.llm.ChatClient
import com.yishou.app.llm.LlmResult
import com.yishou.app.log.RunLog
import com.yishou.app.window.Speaker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 协调器的真实端口：接一手里已有的文字模型、朗读、暂停探针和记录口。
 *
 * 这里只做「把现成能力接上去」，不新造协议：
 * 出题和判定都走用户自己配的文字模型；朗读走 Speaker（接口失败自动退回系统朗读）；
 * 暂停走 T1 的 MediaControlProbe，核实不了就如实返回没核实。
 */
class AndroidCoachPorts(
    private val app: YishouApp,
    private val chat: () -> ChatClient,
    private val speaker: Speaker,
    private val media: MediaControlProbe,
    private val store: CompanionStore,
    private val sessionId: Long,
    /** 这次在听谁：开局时可能还不知道，等媒体会话报出来再更新 */
    private val pkg: () -> String,
    private val epoch: () -> Int,
    private val now: () -> Long,
    /** 用户开了「保留训练卡图片」时，把这一问对应的那一屏存下来并返回路径 */
    private val image: (turnId: Long) -> String? = { null },
) : CoachPorts {

    /** 这一问的出处和原文，记账时用 */
    private var evidenceIds: List<Long> = emptyList()
    private var sourceText: String = ""
    private var askedTurn: Long = -1

    override suspend fun propose(plan: AskPlan.Ask): CoachParse {
        val r = chat().completeConversation(plan.system, emptyList(), plan.user, "视频陪练出题")
        return when (r) {
            is LlmResult.Err -> CoachParse.Reject("文字模型没回上来：${r.error.message}")
            is LlmResult.Ok -> CoachReply.parse(r.value, plan.evidenceIds.toSet(), plan.epoch, plan.expiresAtMs)
        }
    }

    override fun rememberContext(evidenceIds: List<Long>, sourceText: String) {
        this.evidenceIds = evidenceIds
        this.sourceText = sourceText
    }

    override suspend fun pause(): MediaReceipt {
        val target = pkg()
        // 认得目标就只停它；认不出才退回「不是自己」——宁可停不了，也不停到别的 App
        val (outcome, _) = media.pauseAndVerify { c ->
            c.packageName != app.packageName && (target.isBlank() || c.packageName == target)
        }
        return MediaReceipt("$target/$sessionId", outcome, now(), epoch())
    }

    override suspend fun speak(text: String): Boolean {
        // 朗读不可用时按字数给个上限，别卡死在这一句
        return withTimeoutOrNull(4_000L + text.length * 400L) {
            suspendCancellableCoroutine { cont ->
                speaker.speak(text) { if (cont.isActive) cont.resume(true) }
                cont.invokeOnCancellation { speaker.stop() }
            }
        } ?: run {
            speaker.stop()
            false
        }
    }

    override suspend fun judge(question: String, criteria: String, answer: String, material: String): JudgeParse {
        val r = chat().completeConversation(
            JudgeReply.systemPrompt(),
            emptyList(),
            JudgeReply.userPrompt(question, criteria, answer, material),
            "视频陪练判定",
        )
        return when (r) {
            is LlmResult.Err -> {
                RunLog.e("视频陪练", "判定没回上来：${r.error.message}")
                JudgeParse.Failed
            }
            is LlmResult.Ok -> JudgeReply.parse(r.value)
        }
    }

    override suspend fun record(turnId: Long, question: String, answer: String, delta: String, verdict: Verdict, move: ThinkMove) {
        askedTurn = turnId
        // 先把「问过」这一行补上（已经记过就忽略），再写答案；两处都是幂等的
        store.asked(
            sessionId = sessionId,
            turnId = turnId,
            move = move,
            question = question,
            criteria = "",
            evidenceIds = evidenceIds,
            sourceText = sourceText,
            epoch = epoch(),
            imagePath = image(turnId),
        )
        store.answered(sessionId, turnId, answer, verdict, delta)
    }

    /** 记账口已经落过的那一问，方便服务收尾时补场次统计 */
    fun lastTurn(): Long = askedTurn
}
