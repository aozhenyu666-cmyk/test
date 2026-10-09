@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yishou.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.bet.Calibration
import com.yishou.app.bet.CalibrationReport
import com.yishou.app.data.Bet
import com.yishou.app.data.BetKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BetViewModel(app: Application) : AndroidViewModel(app) {
    private val practice = (app as YishouApp).database.practice()

    val bets: StateFlow<List<Bet>> = practice.observeBets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val calibration: StateFlow<CalibrationReport> = practice.observeBets()
        .map { list ->
            Calibration.of(list.filter { it.kind == BetKind.BET && it.outcome != 0 }.map { it.confidence to (it.outcome > 0) })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Calibration.of(emptyList()))

    fun place(kind: String, title: String, claim: String, basis: String, confidence: Int) {
        viewModelScope.launch {
            practice.insertBet(
                Bet(
                    kind = kind,
                    title = title.trim(),
                    claim = claim.trim(),
                    basis = basis.trim(),
                    confidence = if (kind == BetKind.BET) confidence else 0,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun resolve(bet: Bet, hit: Boolean, note: String) {
        viewModelScope.launch {
            practice.updateBet(bet.copy(outcome = if (hit) 1 else -1, note = note.trim(), resolvedAt = System.currentTimeMillis()))
        }
    }
}

/**
 * 预判本。两种用法，都是“先在脑子里走一遍，再拿现实对照”：
 * - 下注：看答案之前，先押一个判断和把握（50%–100%），做完回来点中没中，日子久了看把握准不准；
 * - 预演：动手之前（做饭、出门办事、做一套题），先说三步和一个关键量，做完对照哪里不一样。
 */
@Composable
fun BetScreen(onBack: () -> Unit, vm: BetViewModel = viewModel()) {
    val bets by vm.bets.collectAsStateWithLifecycle()
    val report by vm.calibration.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf(BetKind.BET) }

    Scaffold(topBar = { BackTopBar("预判本", onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CalibrationCard(report) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == BetKind.BET, onClick = { kind = BetKind.BET }, label = { Text("下注") })
                    FilterChip(selected = kind == BetKind.REHEARSE, onClick = { kind = BetKind.REHEARSE }, label = { Text("预演") })
                }
            }
            item {
                if (kind == BetKind.BET) BetComposer { c, b, conf -> vm.place(BetKind.BET, "", c, b, conf) }
                else RehearseComposer { t, steps, key -> vm.place(BetKind.REHEARSE, t, steps, key, 0) }
            }
            val open = bets.filter { it.outcome == 0 }
            if (open.isNotEmpty()) {
                item { Text("等对照", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(open, key = { it.id }) { b -> OpenBet(b, onResolve = { hit, note -> vm.resolve(b, hit, note) }) }
            }
            val done = bets.filter { it.outcome != 0 }
            if (done.isNotEmpty()) {
                item { Text("对照过的", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(done, key = { it.id }) { b -> DoneBet(b) }
            }
        }
    }
}

@Composable
private fun CalibrationCard(r: CalibrationReport) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("把握准不准", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (r.buckets.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    r.buckets.forEach { b ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("说 ${b.confidence}%", style = MaterialTheme.typography.labelMedium)
                            Text("${b.rate}%", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("${b.hits}/${b.n}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(Calibration.verdict(r), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BetComposer(onPlace: (String, String, Int) -> Unit) {
    var claim by rememberSaveable { mutableStateOf("") }
    var basis by rememberSaveable { mutableStateOf("") }
    var confidence by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("看答案之前，先押。押了就不能改，回来对照。", style = MaterialTheme.typography.bodySmall)
        VoiceTextField(claim, { claim = it }, "我预计……", Modifier.fillMaxWidth(), minLines = 2)
        VoiceTextField(basis, { basis = it }, "依据是……", Modifier.fillMaxWidth())
        Text("把握", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(50, 60, 70, 80, 90, 100).forEach { c ->
                FilterChip(selected = confidence == c, onClick = { confidence = c }, label = { Text("$c") })
            }
        }
        Button(
            onClick = {
                onPlace(claim, basis, confidence)
                claim = ""; basis = ""; confidence = 0
            },
            enabled = claim.isNotBlank() && basis.isNotBlank() && confidence > 0,
        ) { Text("押下") }
    }
}

@Composable
private fun RehearseComposer(onPlace: (String, String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("动手之前，先在脑子里走一遍：三步，加一个关键量。说出来比写快。", style = MaterialTheme.typography.bodySmall)
        VoiceTextField(title, { title = it }, "要做的事（例如：番茄炒蛋）", Modifier.fillMaxWidth())
        VoiceTextField(steps, { steps = it }, "三步：先……再……最后……", Modifier.fillMaxWidth(), minLines = 3)
        VoiceTextField(key, { key = it }, "关键量（例如：盐一小勺、中火 3 分钟）", Modifier.fillMaxWidth())
        Button(
            onClick = {
                onPlace(title, steps, key)
                title = ""; steps = ""; key = ""
            },
            enabled = title.isNotBlank() && steps.isNotBlank() && key.isNotBlank(),
        ) { Text("演完了，去做") }
    }
}

@Composable
private fun OpenBet(b: Bet, onResolve: (Boolean, String) -> Unit) {
    var note by rememberSaveable(b.id) { mutableStateOf("") }
    val rehearse = b.kind == BetKind.REHEARSE
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BetBody(b)
            VoiceTextField(note, { note = it }, if (rehearse) "实际哪里不一样" else "实际是什么（可不填）", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onResolve(true, note) }) { Text(if (rehearse) "顺利" else "中了") }
                OutlinedButton(onClick = { onResolve(false, note) }) { Text(if (rehearse) "有偏差" else "没中") }
            }
        }
    }
}

@Composable
private fun DoneBet(b: Bet) {
    val hit = b.outcome > 0
    val rehearse = b.kind == BetKind.REHEARSE
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    rehearse && hit -> "顺利"
                    rehearse -> "有偏差"
                    hit -> "中了"
                    else -> "没中"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (hit) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
            BetBody(b)
            if (b.note.isNotBlank()) Text("对照：${b.note}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BetBody(b: Bet) {
    val time = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(b.createdAt))
    if (b.kind == BetKind.REHEARSE) {
        Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(b.claim, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif))
        Text("关键量：${b.basis}", style = MaterialTheme.typography.bodyMedium)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${b.confidence}%", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("  的把握", style = MaterialTheme.typography.labelMedium)
        }
        Text(b.claim, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif))
        Text("依据：${b.basis}", style = MaterialTheme.typography.bodyMedium)
    }
    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

