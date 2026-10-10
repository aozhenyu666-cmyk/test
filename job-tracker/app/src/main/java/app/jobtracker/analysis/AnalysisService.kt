package app.jobtracker.analysis

import app.jobtracker.ai.ModelClient
import app.jobtracker.ai.ModelError
import app.jobtracker.ai.ModelException
import app.jobtracker.ai.ModelRole
import app.jobtracker.ai.PromptStore
import app.jobtracker.config.AppConfig
import app.jobtracker.data.repo.ProfileRepository

data class AnalysisResult(
    val analysis: MatchAnalysis,
    /** 模型返回的 JSON 原文，原样存进记录 */
    val rawJson: String,
)

class AnalysisService(
    private val model: ModelClient,
    private val prompts: PromptStore,
    private val profile: ProfileRepository,
    private val config: AppConfig,
) {
    /**
     * 对照当前简历分析 JD。输出不合格时自动重试 [AppConfig.invalidJsonRetries] 次，
     * 仍失败抛出 [ModelError.InvalidOutput]；其他错误（无网络、超时等）直接抛出，不重试。
     */
    suspend fun analyze(jd: String): AnalysisResult {
        val p = profile.get()
        require(p != null && p.resumeText.isNotBlank()) { "没有简历" }

        val system = prompts.text(SYSTEM)
        val user = prompts.render(
            USER,
            mapOf("resume" to p.resumeText, "direction" to p.direction.ifBlank { "（未填写）" }, "jd" to jd.trim()),
        )
        val schema = prompts.schema(SCHEMA)

        repeat(config.invalidJsonRetries + 1) {
            val text = try {
                model.callModel(ModelRole.ANALYSIS, system, user, schema)
            } catch (e: ModelException) {
                if (e.error != ModelError.InvalidOutput) throw e
                return@repeat
            }
            MatchAnalysis.parse(text, config.maxResumeTips)?.let {
                return AnalysisResult(it, MatchAnalysis.stripCodeFence(text))
            }
        }
        throw ModelException(ModelError.InvalidOutput)
    }

    companion object {
        const val SYSTEM = "analysis_system.md"
        const val USER = "analysis_user.md"
        const val SCHEMA = "analysis_schema.json"
    }
}
