package app.jobtracker.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jobtracker.R
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.repo.ApplicationRepository

/** 临时首页：只列出记录，方便验证"加入投递"。完整列表在模块 4 实现 */
@Composable
fun HomeScreen(
    applications: ApplicationRepository,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flow = remember(applications) { applications.observeAll() }
    val items by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    Box(modifier = modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Text(stringResource(R.string.home_empty), modifier = Modifier.align(Alignment.Center).padding(24.dp))
        } else {
            LazyColumn {
                items(items, key = { it.id }) { app ->
                    ListItem(
                        headlineContent = { Text("${app.company} · ${app.title}") },
                        supportingContent = { Text(stringResource(app.status.labelRes())) },
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add))
        }
    }
}

fun ApplicationStatus.labelRes(): Int = when (this) {
    ApplicationStatus.PENDING -> R.string.status_pending
    ApplicationStatus.APPLIED -> R.string.status_applied
    ApplicationStatus.INTERVIEWING -> R.string.status_interviewing
    ApplicationStatus.CLOSED -> R.string.status_closed
}
