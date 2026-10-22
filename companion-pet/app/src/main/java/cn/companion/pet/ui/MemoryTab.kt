package cn.companion.pet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.companion.pet.PetApp
import cn.companion.pet.core.memory.ProfileRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class MemorySnapshot(
    val profile: List<String> = emptyList(),
    val today: List<String> = emptyList(),
    val diaries: List<Pair<LocalDate, String>> = emptyList(),
    val letters: List<Pair<LocalDate, String>> = emptyList(),
)

/** 记忆查看与编辑：档案（可纠正、删除推测）、今天的流水、日记、信。 */
@Composable
fun MemoryTab(resumeTick: Int) {
    val app = PetApp.instance
    val memory = app.memory
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var snap by remember { mutableStateOf(MemorySnapshot()) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(resumeTick, reload) {
        snap = withContext(Dispatchers.IO) {
            synchronized(app.memoryLock) {
                MemorySnapshot(
                    profile = memory.profileLines(),
                    today = memory.todayLines(),
                    diaries = memory.listDates(memory.diaryDir).take(7).map { it to memory.diary(it).orEmpty() },
                    letters = memory.listDates(memory.lettersDir).take(7).map { it to memory.letter(it).orEmpty() },
                )
            }
        }
    }

    fun io(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { synchronized(app.memoryLock) { block() } }
        reload++
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("它对你的了解", style = MaterialTheme.typography.titleMedium)
        Text("[他说过] 是你亲口说的，[我推测] 是它猜的。猜错了就删掉，删掉的不会被它再写回来。", style = MaterialTheme.typography.bodySmall)
        if (editing) {
            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { val d = draft; io { memory.saveProfileByUser(d) }; editing = false }) { Text("保存") }
                OutlinedButton(onClick = { editing = false }) { Text("取消") }
            }
        } else {
            if (snap.profile.isEmpty()) Text("还没有。每晚整理后会慢慢积累。", style = MaterialTheme.typography.bodyMedium)
            snap.profile.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (line.startsWith(ProfileRules.GUESS)) TextButton(onClick = { io { memory.confirmProfileLine(line) } }) { Text("对") }
                    TextButton(onClick = { io { memory.deleteProfileLine(line) } }) { Text("删") }
                }
            }
            OutlinedButton(onClick = { draft = snap.profile.joinToString("\n"); editing = true }) { Text("整段编辑") }
        }

        HorizontalDivider()
        Text("今天的交流（${snap.today.size}）", style = MaterialTheme.typography.titleMedium)
        snap.today.takeLast(30).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (snap.today.isNotEmpty()) TextButton(onClick = { io { memory.todayFile.delete() } }) { Text("清空今天的记录") }

        HorizontalDivider()
        Text("它写给你的信", style = MaterialTheme.typography.titleMedium)
        if (snap.letters.isEmpty()) Text("还没有。", style = MaterialTheme.typography.bodySmall)
        snap.letters.forEach { (d, t) -> Entry(d, t) { io { java.io.File(memory.lettersDir, "$d.md").delete() } } }

        HorizontalDivider()
        Text("它给自己的日记", style = MaterialTheme.typography.titleMedium)
        Text("不是写给你的，但你可以看。", style = MaterialTheme.typography.bodySmall)
        if (snap.diaries.isEmpty()) Text("还没有。", style = MaterialTheme.typography.bodySmall)
        snap.diaries.forEach { (d, t) -> Entry(d, t) { io { java.io.File(memory.diaryDir, "$d.md").delete() } } }
    }
}

@Composable
private fun Entry(date: LocalDate, text: String, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date.toString(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onDelete) { Text("删") }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
