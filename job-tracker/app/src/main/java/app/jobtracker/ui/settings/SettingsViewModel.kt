package app.jobtracker.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jobtracker.R
import app.jobtracker.data.repo.ProfileRepository
import app.jobtracker.data.repo.SettingsRepository
import app.jobtracker.security.ApiKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loaded: Boolean = false,
    val resumeText: String = "",
    val direction: String = "",
    /** 输入框里新填的密钥；留空表示不修改已保存的密钥 */
    val apiKeyInput: String = "",
    val hasSavedKey: Boolean = false,
    val apiBaseUrl: String = "",
    val strongModel: String = "",
    val fastModel: String = "",
    @StringRes val modelError: Int? = null,
    /** 一次性提示，显示后调用 messageShown() 清掉 */
    @StringRes val message: Int? = null,
)

class SettingsViewModel(
    private val profile: ProfileRepository,
    private val settings: SettingsRepository,
    private val apiKeys: ApiKeyStore,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val p = profile.get()
            val s = settings.get()
            _state.update {
                it.copy(
                    loaded = true,
                    resumeText = p?.resumeText.orEmpty(),
                    direction = p?.direction.orEmpty(),
                    hasSavedKey = apiKeys.hasKey.value,
                    apiBaseUrl = s.apiBaseUrl,
                    strongModel = s.strongModel,
                    fastModel = s.fastModel,
                )
            }
        }
    }

    fun onResumeChange(value: String) = _state.update { it.copy(resumeText = value) }
    fun onDirectionChange(value: String) = _state.update { it.copy(direction = value) }
    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKeyInput = value) }
    fun onBaseUrlChange(value: String) = _state.update { it.copy(apiBaseUrl = value, modelError = null) }
    fun onStrongModelChange(value: String) = _state.update { it.copy(strongModel = value, modelError = null) }
    fun onFastModelChange(value: String) = _state.update { it.copy(fastModel = value, modelError = null) }
    fun messageShown() = _state.update { it.copy(message = null) }

    fun saveProfile() {
        val s = _state.value
        if (s.resumeText.isBlank()) {
            _state.update { it.copy(message = R.string.settings_error_resume_blank) }
            return
        }
        viewModelScope.launch {
            profile.save(s.resumeText, s.direction)
            _state.update { it.copy(message = R.string.settings_saved) }
        }
    }

    fun saveModelSettings() {
        val s = _state.value
        val error = validateModelSettings(s.apiBaseUrl, s.strongModel, s.fastModel)
        if (error != null) {
            _state.update { it.copy(modelError = error) }
            return
        }
        viewModelScope.launch {
            settings.update {
                it.copy(
                    apiBaseUrl = s.apiBaseUrl.trim().trimEnd('/'),
                    strongModel = s.strongModel.trim(),
                    fastModel = s.fastModel.trim(),
                )
            }
            val newKey = s.apiKeyInput.trim()
            if (newKey.isNotEmpty()) apiKeys.set(newKey)
            _state.update {
                it.copy(
                    apiKeyInput = "",
                    hasSavedKey = apiKeys.hasKey.value,
                    apiBaseUrl = s.apiBaseUrl.trim().trimEnd('/'),
                    message = R.string.settings_saved,
                )
            }
        }
    }

    fun clearApiKey() {
        apiKeys.clear()
        _state.update { it.copy(apiKeyInput = "", hasSavedKey = false, message = R.string.settings_key_cleared) }
    }
}
