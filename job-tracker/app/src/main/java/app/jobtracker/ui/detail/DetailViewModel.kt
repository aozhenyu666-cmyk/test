package app.jobtracker.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jobtracker.data.db.ApplicationEntity
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.model.EndResult
import app.jobtracker.data.repo.ApplicationRepository
import app.jobtracker.domain.FollowUpRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

data class DetailUiState(
    val loaded: Boolean = false,
    /** 加载完成后为 null 表示记录不存在（已被删除） */
    val app: ApplicationEntity? = null,
    val deleted: Boolean = false,
)

/**
 * 详情页的修改自动保存：状态和日期立即保存，文字输入停顿片刻后保存，离开页面时补存一次。
 * 写入放在 [appScope] 里，页面销毁也不会丢最后一次修改。
 */
class DetailViewModel(
    private val id: Long,
    private val applications: ApplicationRepository,
    private val rules: FollowUpRules,
    private val clock: Clock,
    private val appScope: CoroutineScope,
    private val textSaveDelayMillis: Long = 500,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    val channels: StateFlow<List<String>> = applications.observeChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var saveJob: Job? = null
    @Volatile private var dirty = false

    init {
        viewModelScope.launch {
            val app = applications.get(id)
            _state.update { it.copy(loaded = true, app = app) }
        }
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun edit(immediate: Boolean, transform: (ApplicationEntity) -> ApplicationEntity) {
        val current = _state.value.app ?: return
        if (_state.value.deleted) return
        val next = transform(current)
        if (next == current) return
        _state.update { it.copy(app = next) }
        dirty = true
        saveJob?.cancel()
        saveJob = appScope.launch {
            if (!immediate) delay(textSaveDelayMillis)
            save()
        }
    }

    private suspend fun save() {
        val app = _state.value.app ?: return
        if (!dirty || _state.value.deleted) return
        dirty = false
        applications.update(app)
    }

    fun onCompanyChange(value: String) = edit(false) { it.copy(company = value) }
    fun onTitleChange(value: String) = edit(false) { it.copy(title = value) }
    fun onChannelChange(value: String) = edit(false) { it.copy(channel = value) }
    fun onNoteChange(value: String) = edit(false) { it.copy(note = value.ifEmpty { null }) }

    /** "已结束"要先选结果，走 [onClose] */
    fun onStatusChange(status: ApplicationStatus) {
        if (status == ApplicationStatus.CLOSED) return
        edit(true) { rules.changeStatus(it, status, today()) }
    }

    fun onClose(result: EndResult) = edit(true) { rules.changeStatus(it, ApplicationStatus.CLOSED, today(), result) }

    fun onAppliedAtChange(date: LocalDate?) = edit(true) { rules.changeAppliedAt(it, date) }
    fun onInterviewAtChange(date: LocalDate?) = edit(true) { rules.changeInterviewAt(it, date) }

    /** 下次跟进日期始终可以手改 */
    fun onNextFollowUpChange(date: LocalDate?) = edit(true) { it.copy(nextFollowUpAt = date) }

    /** 离开页面前调用，把还没存的文字立即存下 */
    fun flush() {
        if (!dirty) return
        saveJob?.cancel()
        saveJob = appScope.launch { save() }
    }

    /** 页面随即关闭；删除放在 appScope 里，页面销毁也会完成 */
    fun delete() {
        saveJob?.cancel()
        dirty = false
        _state.update { it.copy(deleted = true) }
        appScope.launch { applications.delete(id) }
    }

    override fun onCleared() {
        flush()
    }
}
