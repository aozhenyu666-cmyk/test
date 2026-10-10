package app.jobtracker.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jobtracker.ai.ModelError
import app.jobtracker.ai.ModelException
import app.jobtracker.analysis.AnalysisResult
import app.jobtracker.analysis.AnalysisService
import app.jobtracker.config.AppConfig
import app.jobtracker.data.repo.ApplicationRepository
import app.jobtracker.data.repo.NewApplication
import app.jobtracker.data.repo.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalysisUiState(
    val jd: String = "",
    /** null 表示还没读到 */
    val hasResume: Boolean? = null,
    val loading: Boolean = false,
    val error: ModelError? = null,
    val result: AnalysisResult? = null,
    /** 可手改的公司名和岗位名 */
    val company: String = "",
    val title: String = "",
    /** 当前这份结果是否已加入投递，防止重复点 */
    val added: Boolean = false,
)

class AnalysisViewModel(
    private val service: AnalysisService,
    private val applications: ApplicationRepository,
    profile: ProfileRepository,
    private val config: AppConfig,
) : ViewModel() {
    private val _state = MutableStateFlow(AnalysisUiState())
    val state: StateFlow<AnalysisUiState> = _state.asStateFlow()

    val jdMinChars: Int get() = config.jdMinChars

    init {
        viewModelScope.launch {
            profile.observeHasResume().collect { has -> _state.update { it.copy(hasResume = has) } }
        }
    }

    fun canAnalyze(s: AnalysisUiState = _state.value): Boolean =
        s.hasResume == true && s.jd.trim().length >= config.jdMinChars && !s.loading

    fun onJdChange(value: String) = _state.update { it.copy(jd = value) }
    fun onCompanyChange(value: String) = _state.update { it.copy(company = value) }
    fun onTitleChange(value: String) = _state.update { it.copy(title = value) }

    /** 分析和重新分析都走这里；失败时 JD 原文保留在输入框里 */
    fun analyze() {
        val s = _state.value
        if (!canAnalyze(s)) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val result = service.analyze(s.jd)
                _state.update {
                    it.copy(
                        result = result,
                        company = result.analysis.company,
                        title = result.analysis.title,
                        added = false,
                    )
                }
            } catch (e: ModelException) {
                _state.update { it.copy(error = e.error) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** 生成一条"待投递"记录，JD 原文和分析结果原样保存 */
    fun addToApplications(onAdded: (Long) -> Unit) {
        val s = _state.value
        val result = s.result ?: return
        if (s.added) return
        _state.update { it.copy(added = true) }
        viewModelScope.launch {
            val id = applications.create(
                NewApplication(
                    company = s.company,
                    title = s.title,
                    jdText = s.jd.trim(),
                    matchLevel = result.analysis.level,
                    analysisJson = result.rawJson,
                ),
            )
            onAdded(id)
        }
    }

    /** 从首页重新进入分析页时清空上一次的内容 */
    fun reset() = _state.update { AnalysisUiState(hasResume = it.hasResume) }
}
