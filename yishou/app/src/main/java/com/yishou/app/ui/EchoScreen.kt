package com.yishou.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.recall.EchoItem
import com.yishou.app.recall.EchoRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EchoViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = EchoRepo(app as YishouApp)

    private val _items = MutableStateFlow<List<EchoItem>?>(null)
    /** null 表示还在读 */
    val items: StateFlow<List<EchoItem>?> = _items.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch { _items.value = repo.due() }
    }

    fun record(item: EchoItem, said: String, result: Int) {
        viewModelScope.launch {
            repo.record(item, said, result)
            _items.value = repo.due()
        }
    }
}

/**
 * 回响：有效的一手在第 1、3、7 天回来找你。先不看原话，凭记忆把当时的结论说出来，
 * 再对照原话，给自己记一笔：记得、模糊、忘了。不调大模型，不花钱。
 */
@Composable
fun EchoScreen(onBack: () -> Unit, vm: EchoViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("回响", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val list = items
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> {
                    Text("今天没有要回响的。", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "有效的一手会在第 1、3、7 天回来找你：先凭记忆说出当时的结论，再对照原话。" +
                            "隔几天还能说出来的，才是真的留下了。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    Text("今天 ${list.size} 条。先别看原话。", style = MaterialTheme.typography.bodyMedium)
                    // 一次只做一条，做完下一条才出来
                    EchoCard(list.first()) { said, result -> vm.record(list.first(), said, result) }
                }
            }
        }
    }
}

@Composable
private fun EchoCard(item: EchoItem, onDone: (String, Int) -> Unit) {
    var said by rememberSaveable(item.round.id, item.stage) { mutableStateOf("") }
    var revealed by rememberSaveable(item.round.id, item.stage) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("第 ${item.stage} 天的回响（${item.ageDays} 天前）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            Text("当时陪练问你", style = MaterialTheme.typography.labelMedium)
            Text(item.round.coachMove, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif))
            VoiceTextField(said, { said = it }, "凭记忆说：你当时得出了什么、依据是什么", Modifier.fillMaxWidth(), minLines = 2, enabled = !revealed)
            if (!revealed) {
                Button(onClick = { revealed = true }) { Text(if (said.isBlank()) "想不起来，看原话" else "对照原话") }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("你当时说", style = MaterialTheme.typography.labelMedium)
                        Text(item.round.userAnswer, style = MaterialTheme.typography.bodyLarge)
                        if (item.round.feedback.isNotBlank()) {
                            Text("陪练：${item.round.feedback}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("对上了吗？", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDone(said, 1) }) { Text("记得") }
                    OutlinedButton(onClick = { onDone(said, 0) }) { Text("模糊") }
                    OutlinedButton(onClick = { onDone(said, -1) }) { Text("忘了") }
                }
            }
        }
    }
}
