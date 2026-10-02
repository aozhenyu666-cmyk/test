package com.behaviordept.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.ui.theme.SerifSC
import com.behaviordept.app.util.Time
import kotlinx.coroutines.delay

/** 专注模式顶栏：当前任务、计时、结束按钮。学习和专项练共用。 */
@Composable
fun FocusBar(kind: String, title: String, startedAt: Long, onEnd: () -> Unit) {
    val p = Paper.colors
    var now by remember { mutableLongStateOf(Time.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Time.now()
            delay(1000)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.page)
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(kind, style = MaterialTheme.typography.labelMedium, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = MaterialTheme.typography.titleLarge, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            Time.clock(now - startedAt),
            fontFamily = SerifSC,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.headlineMedium,
            color = p.ink,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        TextButton(onClick = onEnd) {
            Text("结束", style = MaterialTheme.typography.labelLarge, color = p.red)
        }
    }
}
