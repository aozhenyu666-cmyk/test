package com.behaviordept.app.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.behaviordept.app.data.AiCall
import com.behaviordept.app.data.AiCallDao
import com.behaviordept.app.data.EventLog
import com.behaviordept.app.data.EventType
import com.behaviordept.app.data.SecretStore
import com.behaviordept.app.data.SettingsStore
import com.behaviordept.app.data.StudyUnit
import com.behaviordept.app.study.preQuestionList
import com.behaviordept.app.util.Time

/** AI 是否可用。不可用时学习模块切换为“对照资料自查”。 */
enum class AiAvailability { READY, NO_KEY, OFFLINE }

/**
 * AI 教练服务：只在学习流程的节点上被调用。
 * 每次调用都写一条 AiCall（任务类型带提示词版本、模型、token），失败也记一条事件。
 */
class AiCoach(
    private val context: Context,
    private val client: AiClient,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val calls: AiCallDao,
    private val events: EventLog,
) {
    suspend fun availability(): AiAvailability {
        val s = settings.current()
        if (secrets.apiKey.value.isBlank() || s.baseUrl.isBlank() || s.cheapModel.isBlank()) return AiAvailability.NO_KEY
        if (!isOnline()) return AiAvailability.OFFLINE
        return AiAvailability.READY
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun endpoint(tier: Tier): AiEndpoint {
        val s = settings.current()
        return AiEndpoint(
            provider = s.provider,
            baseUrl = s.baseUrl,
            apiKey = secrets.apiKey.value,
            model = if (tier == Tier.CHEAP) s.cheapModel else s.flagshipModel,
        )
    }

    /** 调一次模型并用 parse 校验格式。格式不对抛 AiException.BadFormat。 */
    private suspend fun <T> ask(template: PromptTemplate, input: String, parse: (String) -> T): T {
        if (!isOnline()) throw AiException.Offline()
        val ep = endpoint(template.tier)
        val reply = try {
            client.complete(ep, template.system, input)
        } catch (e: AiException) {
            events.log(EventType.AI_FAILED, note = "${template.tag} ${e.message.orEmpty()}")
            throw e
        }
        val parsed = runCatching { parse(reply.text) }
        calls.insert(
            AiCall(
                time = Time.now(),
                taskType = template.tag,
                model = reply.model,
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                ok = parsed.isSuccess,
            ),
        )
        events.log(EventType.AI_CALL, value = (reply.inputTokens + reply.outputTokens).toDouble(), note = template.tag)
        return parsed.getOrElse { e ->
            events.log(EventType.AI_FAILED, note = "${template.tag} 格式不对")
            throw AiException.BadFormat(reply.text, e.message ?: "格式不对")
        }
    }

    /** 合上讲一遍 → 红笔批改。返回规范化后的批改文本。 */
    suspend fun critiqueExplanation(unit: StudyUnit, explanation: String): String =
        ask(Prompts.CRITIQUE, Prompts.critiqueInput(unit.material, unit.preQuestionList(), explanation)) {
            Parsers.sectionsToText(Parsers.critique(it))
        }

    suspend fun generateQuestions(unit: StudyUnit, intervalDays: Int, previous: List<String>): List<String> =
        ask(Prompts.QUESTIONS, Prompts.questionsInput(unit.material, intervalDays, previous)) { Parsers.questions(it) }

    suspend fun gradeReview(unit: StudyUnit, questions: List<String>, answers: List<String>): Grade =
        ask(Prompts.GRADE, Prompts.gradeInput(unit.material, questions, answers)) { Parsers.grade(it, questions.size) }

    suspend fun critiqueTransfer(unit: StudyUnit, example: String): String =
        ask(Prompts.TRANSFER, Prompts.transferInput(unit.title, unit.material, example)) {
            Parsers.sectionsToText(Parsers.transfer(it))
        }

    /** 设置页“测试连接”。 */
    suspend fun testConnection(): String {
        val ep = endpoint(Tier.CHEAP)
        val reply = client.complete(ep, "你是连接测试。", "只回复两个字：收到", maxTokens = 64)
        return "${reply.model} 回复：${reply.text.trim().take(40)}"
    }
}
