package com.yishou.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishou.app.YishouApp
import com.yishou.app.schulte.Schulte
import com.yishou.app.schulte.TapResult
import com.yishou.app.system.Haptics
import kotlinx.coroutines.delay

/**
 * 舒尔特表：25 格乱序，按 1 到 25 依次点，用时越短越好。
 *
 * 练的是“一眼看一整片”，不是一个个找。所以：
 * - 点错不重来，进度留着，只记一次漏点——罚重来会让人盯着单个格子找，反而练成反面。
 * - 不放大格子、不给提示，靠时间自己说话。
 */
@Composable
fun SchulteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = (context.applicationContext as YishouApp).settings
    var board by remember { mutableStateOf(Schulte.deal(5) { it.shuffle() }) }
    var tapped by remember { mutableStateOf(0) }
    var misses by remember { mutableStateOf(0) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    var finished by remember { mutableStateOf(false) }
    var best by remember { mutableStateOf(prefs.app.value.schulteBestMs) }

    // 计时只在开始以后走，做完就停
    LaunchedEffect(startedAt, finished) {
        val start = startedAt ?: return@LaunchedEffect
        while (!finished) {
            elapsed = System.currentTimeMillis() - start
            delay(100)
        }
    }

    fun restart() {
        board = Schulte.deal(5) { it.shuffle() }
        tapped = 0; misses = 0; startedAt = null; elapsed = 0L; finished = false
    }

    fun onTap(value: Int) {
        if (finished) return
        val start = startedAt ?: System.currentTimeMillis().also { startedAt = it }
        when (val r = Schulte.tap(board, tapped, value)) {
            is TapResult.Hit -> {
                tapped = r.next
                Haptics.play(context, Haptics.Kind.NEW_MOVE)
                if (r.finished) {
                    finished = true
                    elapsed = System.currentTimeMillis() - start
                    Haptics.play(context, Haptics.Kind.PASS)
                    if (best == 0L || elapsed < best) {
                        best = elapsed
                        prefs.updateApp { it.copy(schulteBestMs = elapsed) }
                    }
                }
            }
            is TapResult.Miss -> { misses += 1; Haptics.play(context, Haptics.Kind.REDO) }
            TapResult.Ignored -> Unit
        }
    }

    Scaffold(topBar = { BackTopBar("舒尔特表", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        if (finished) "完成" else "找 " + (tapped + 1),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(fmt(elapsed) + (if (misses > 0) " · 漏 " + misses else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (best > 0) Text("最好 " + fmt(best), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary)
            }

            if (finished) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(Schulte.score(elapsed, misses).toString() + " 分", style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Light)
                        Text(Schulte.verdict(elapsed, misses), style = MaterialTheme.typography.bodyLarge)
                        Text("用时 " + fmt(elapsed) + "，漏 " + misses + " 次。" + (if (best == elapsed) "这是目前最好的一次。" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onClick = ::restart, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("再来一局") }
            } else {
                Text("按 1 到 " + board.total + " 依次点。看中间那一片，别一格一格扫。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(board.size),
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(board.order) { n ->
                    val done = n <= tapped
                    Button(
                        onClick = { onTap(n) },
                        enabled = !finished && !done,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.aspectRatio(1f),
                    ) { Text(n.toString(), fontSize = 20.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

/** 12.3 秒 */
private fun fmt(ms: Long): String =
    if (ms <= 0) "0.0 秒" else String.format("%.1f 秒", ms / 1000.0)
