package cn.companion.pet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cn.companion.pet.core.action.*
import cn.companion.pet.ui.CapabilityPanel
import cn.companion.pet.ui.theme.CompanionTheme
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/** Retains ownership across rotation; leaving the activity cancels only this panel's work. */
class CapabilityActivity : ComponentActivity() {
    private lateinit var owner: CapabilityOwner
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CapabilityOwner(PetApp.instance) as T
        })[CapabilityOwner::class.java]
        if (owner.session.state.value.phase == ActionPhase.IDLE) {
            val id = savedInstanceState?.getString("actionId")
            val kind = savedInstanceState?.getString("actionKind")?.let { name -> ActionKind.entries.find { it.name == name } }
            if (id != null && kind != null) owner.session.check(ActionRequest(id, kind))
        }
        setContent {
            val state by owner.session.state.collectAsState()
            val history by owner.history.collectAsState()
            val historyError by owner.historyError.collectAsState()
            LaunchedEffect(state.phase, state.request?.id) {
                if (state.phase == ActionPhase.FINISHED) owner.refreshHistory()
            }
            CompanionTheme {
                CapabilityPanel(state, owner.session::submit, owner.session::cancel,
                    onCheck = { state.request?.let(owner.session::check) }, onBack = { finish() },
                    history = history, historyError = historyError,
                    onHistoryCheck = { owner.session.check(ActionRequest(it.id, it.kind)) })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        owner.session.state.value.request?.let {
            // Identity only; checking a restored operation must not recreate its side effect.
            outState.putString("actionId", it.id)
            outState.putString("actionKind", it.kind.name)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (!isChangingConfigurations) owner.session.cancel()
        super.onStop()
    }
}

class CapabilityOwner(private val app: PetApp) : ViewModel() {
    private val executor = Executors.newSingleThreadExecutor()
    val session = ActionSession(app.actions, executor, stopSpeech = { id -> app.speech.stop(id) })
    val history = MutableStateFlow<List<ActionHistoryEntry>>(emptyList())
    val historyError = MutableStateFlow<String?>(null)
    init { refreshHistory() }
    fun refreshHistory() {
        runCatching { executor.execute {
            try { history.value = app.actions.history(); historyError.value = null }
            catch (_: Exception) { history.value = emptyList(); historyError.value = "执行记录暂不可读，请先核对已有操作，避免重复保存" }
        } }
    }
    override fun onCleared() { session.close(); executor.shutdownNow() }
}
