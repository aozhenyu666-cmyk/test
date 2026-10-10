package app.jobtracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jobtracker.data.db.ApplicationEntity
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.repo.ApplicationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate

/** 首页顶部分段：待跟进 / 全部 */
enum class HomeSegment { DUE, ALL }

data class HomeUiState(
    val segment: HomeSegment = HomeSegment.DUE,
    /** 只在"全部"里生效；null 表示不筛选 */
    val statusFilter: ApplicationStatus? = null,
    val today: LocalDate,
    val items: List<ApplicationEntity> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val applications: ApplicationRepository,
    private val clock: Clock,
) : ViewModel() {
    private val segment = MutableStateFlow(HomeSegment.DUE)
    private val statusFilter = MutableStateFlow<ApplicationStatus?>(null)
    private val today = MutableStateFlow(LocalDate.now(clock))

    val state: StateFlow<HomeUiState> = combine(segment, statusFilter, today) { seg, filter, day -> Triple(seg, filter, day) }
        .flatMapLatest { (seg, filter, day) ->
            val source = when {
                seg == HomeSegment.DUE -> applications.observeDue(day)
                filter == null -> applications.observeAll()
                else -> applications.observeByStatus(filter)
            }
            source.map { HomeUiState(seg, filter, day, it, loaded = true) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(today = today.value))

    fun selectSegment(value: HomeSegment) {
        segment.value = value
    }

    fun selectStatus(value: ApplicationStatus?) {
        statusFilter.value = value
    }

    /** 回到前台时调用，跨过零点后"今天"要跟着变 */
    fun refreshToday() {
        today.value = LocalDate.now(clock)
    }
}
