package com.behaviordept.app.guard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.today.NextAction
import com.behaviordept.app.today.computeNextAction
import com.behaviordept.app.today.description
import com.behaviordept.app.today.headline
import com.behaviordept.app.today.kindLabel
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.ChoiceChip
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.QuietButton
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UrgeViewModel(private val c: AppContainer) : ViewModel() {
    var urgeId by mutableStateOf<Long?>(null)
        private set
    var startedAt by mutableLongStateOf(0L)
        private set
    var next by mutableStateOf<NextAction?>(null)
        private set

    init {
        viewModelScope.launch {
            val now = Time.now()
            next = computeNextAction(c.study.allUnits(), now, c.training.plans(now))
        }
    }

    fun choose(reason: String) {
        if (urgeId != null) return
        startedAt = Time.now()
        viewModelScope.launch { urgeId = c.guard.logUrge(reason) }
    }

    fun startTraining() {
        val id = urgeId ?: return
        viewModelScope.launch { c.guard.markUrgeStarted(id) }
    }
}

/** 冲动登记：记下时间和原因，倒数 3 分钟，同时给出今日一件事（PRD M5）。 */
@Composable
fun UrgeScreen(onBack: () -> Unit, onStart: (NextAction) -> Unit) {
    val vm = appViewModel { UrgeViewModel(it) }
    val p = Paper.colors
    var now by remember { mutableLongStateOf(Time.now()) }
    LaunchedEffect(vm.startedAt) {
        while (vm.startedAt > 0) {
            now = Time.now()
            delay(250)
        }
    }
    val remaining = if (vm.startedAt == 0L) WAIT_MS else (WAIT_MS - (now - vm.startedAt)).coerceAtLeast(0)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        QuietButton("‹ 算了，不刷了", onBack)
        if (vm.startedAt == 0L) {
            Text("先说为什么想刷", style = MaterialTheme.typography.headlineLarge, color = p.ink)
            Hint("不评判，只记录。一周后复盘看看自己在什么情况下最容易失守。")
            listOf("无聊", "逃避", "累").forEach { r ->
                ChoiceChip(r, selected = false, modifier = Modifier.fillMaxWidth(), height = 64.dp) { vm.choose(r) }
            }
            return@Column
        }

        SectionLabel("等 3 分钟")
        Text(
            Time.clock(remaining),
            style = MaterialTheme.typography.displayLarge,
            color = if (remaining == 0L) p.ink2 else p.red,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (remaining > 0) "冲动一般几分钟就会退下去。这 3 分钟里，先看一眼今天这一件事。"
            else "3 分钟到了。还想刷就去，但会算进今天的用时，周复盘也会看到这一次。",
            style = MaterialTheme.typography.bodyLarge,
            color = p.ink,
        )
        vm.next?.let { n ->
            PaperCard {
                SectionLabel("今日一件事 · ${n.kindLabel()}")
                Spacer(Modifier.height(8.dp))
                Text(n.headline(), style = MaterialTheme.typography.headlineSmall, color = p.ink)
                Spacer(Modifier.height(6.dp))
                Text(n.description(), style = MaterialTheme.typography.bodyMedium, color = p.ink2)
                Spacer(Modifier.height(16.dp))
                InkButton("去做这一件", onClick = {
                    vm.startTraining()
                    onStart(n)
                })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private const val WAIT_MS = 3 * 60 * 1000L
