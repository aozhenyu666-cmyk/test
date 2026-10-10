package app.jobtracker.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jobtracker.R
import app.jobtracker.data.db.ApplicationEntity
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.domain.DueState
import app.jobtracker.ui.dueText
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshToday()
        onPauseOrDispose { }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                HomeSegment.entries.forEachIndexed { index, seg ->
                    SegmentedButton(
                        selected = state.segment == seg,
                        onClick = { viewModel.selectSegment(seg) },
                        shape = SegmentedButtonDefaults.itemShape(index, HomeSegment.entries.size),
                    ) {
                        Text(stringResource(if (seg == HomeSegment.DUE) R.string.home_segment_due else R.string.home_segment_all))
                    }
                }
            }
            if (state.segment == HomeSegment.ALL) {
                StatusFilterRow(selected = state.statusFilter, onSelect = viewModel::selectStatus)
            }
            if (state.loaded && state.items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (state.segment == HomeSegment.DUE) R.string.home_due_empty else R.string.home_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = { it.id }) { app ->
                        ApplicationRow(app, state.today, onClick = { onOpen(app.id) })
                        HorizontalDivider()
                    }
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

@Composable
private fun StatusFilterRow(selected: ApplicationStatus?, onSelect: (ApplicationStatus?) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.home_filter_all)) },
        )
        ApplicationStatus.entries.forEach { status ->
            FilterChip(
                selected = selected == status,
                onClick = { onSelect(status) },
                label = { Text(stringResource(status.labelRes())) },
            )
        }
    }
}

@Composable
private fun ApplicationRow(app: ApplicationEntity, today: LocalDate, onClick: () -> Unit) {
    val due = DueState.of(app, today)
    val overdue = due is DueState.Overdue
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text("${app.company.ifBlank { "—" }} · ${app.title.ifBlank { "—" }}") },
        supportingContent = {
            Text(
                stringResource(R.string.home_row_status, stringResource(app.status.labelRes()), dueText(app.nextFollowUpAt, due)),
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

fun ApplicationStatus.labelRes(): Int = when (this) {
    ApplicationStatus.PENDING -> R.string.status_pending
    ApplicationStatus.APPLIED -> R.string.status_applied
    ApplicationStatus.INTERVIEWING -> R.string.status_interviewing
    ApplicationStatus.CLOSED -> R.string.status_closed
}
