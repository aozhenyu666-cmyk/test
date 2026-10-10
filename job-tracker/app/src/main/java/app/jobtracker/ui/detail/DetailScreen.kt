package app.jobtracker.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jobtracker.R
import app.jobtracker.data.db.ApplicationEntity
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.model.EndResult
import app.jobtracker.domain.DueState
import app.jobtracker.ui.analysis.labelRes
import app.jobtracker.ui.display
import app.jobtracker.ui.dueText
import app.jobtracker.ui.home.labelRes
import app.jobtracker.ui.labelRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    today: LocalDate,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var choosingResult by rememberSaveable { mutableStateOf(false) }
    val app = state.app

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(app?.company?.ifBlank { null } ?: stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (app != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.detail_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        if (app == null) {
            Text(stringResource(R.string.detail_missing), modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val due = DueState.of(app, today)
            Text(
                stringResource(R.string.detail_next_follow_up, dueText(app.nextFollowUpAt, due)),
                style = MaterialTheme.typography.titleMedium,
                color = if (due is DueState.Overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )

            StatusSection(
                app = app,
                onStatusChange = { status ->
                    if (status == ApplicationStatus.CLOSED) choosingResult = true else viewModel.onStatusChange(status)
                },
            )

            DateRow(R.string.field_applied_at, app.appliedAt, viewModel::onAppliedAtChange)
            DateRow(R.string.field_interview_at, app.interviewAt, viewModel::onInterviewAtChange)
            if (app.status == ApplicationStatus.INTERVIEWING && app.interviewAt == null) {
                Text(stringResource(R.string.detail_interview_hint), style = MaterialTheme.typography.bodySmall)
            }
            DateRow(R.string.field_next_follow_up, app.nextFollowUpAt, viewModel::onNextFollowUpChange)
            Text(
                stringResource(R.string.detail_follow_up_count, app.followUpCount),
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            OutlinedTextField(
                value = app.company,
                onValueChange = viewModel::onCompanyChange,
                label = { Text(stringResource(R.string.field_company)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = app.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text(stringResource(R.string.field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = app.channel,
                onValueChange = viewModel::onChannelChange,
                label = { Text(stringResource(R.string.field_channel)) },
                placeholder = { Text(stringResource(R.string.field_channel_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val suggestions = channels.filter { it != app.channel }
            if (suggestions.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    suggestions.forEach { channel ->
                        AssistChip(onClick = { viewModel.onChannelChange(channel) }, label = { Text(channel) })
                    }
                }
            }
            OutlinedTextField(
                value = app.note.orEmpty(),
                onValueChange = viewModel::onNoteChange,
                label = { Text(stringResource(R.string.field_note)) },
                placeholder = { Text(stringResource(R.string.field_note_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Text(
                stringResource(R.string.detail_match_level, stringResource(app.matchLevel.labelRes())),
                style = MaterialTheme.typography.bodyMedium,
            )
            JdSection(app.jdText)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                    onBack()
                }) { Text(stringResource(R.string.detail_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (choosingResult) {
        EndResultDialog(
            initial = app?.endResult,
            onDismiss = { choosingResult = false },
            onConfirm = {
                choosingResult = false
                viewModel.onClose(it)
            },
        )
    }
}

@Composable
private fun StatusSection(app: ApplicationEntity, onStatusChange: (ApplicationStatus) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.field_status), style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            ApplicationStatus.entries.forEach { status ->
                FilterChip(
                    selected = app.status == status,
                    onClick = { onStatusChange(status) },
                    label = { Text(stringResource(status.labelRes())) },
                )
            }
        }
        app.endResult?.let {
            Text(stringResource(R.string.detail_end_result, stringResource(it.labelRes())), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(labelRes: Int, date: LocalDate?, onChange: (LocalDate?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { picking = true }
                .padding(vertical = 4.dp),
        ) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
            Text(date?.display() ?: stringResource(R.string.date_not_set), style = MaterialTheme.typography.bodyLarge)
        }
        if (date != null) {
            TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.date_clear)) }
        }
        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.date_pick)) }
    }
    if (picking) {
        // DatePicker 以 UTC 零点的毫秒数表示日期
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    pickerState.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun EndResultDialog(initial: EndResult?, onDismiss: () -> Unit, onConfirm: (EndResult) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_choose_result)) },
        text = {
            Column {
                EndResult.entries.forEach { result ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == result, onClick = { selected = result }),
                    ) {
                        RadioButton(selected = selected == result, onClick = null)
                        Text(stringResource(result.labelRes()), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun JdSection(jd: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.detail_jd_collapse else R.string.detail_jd_expand))
        }
        if (expanded) Text(jd, style = MaterialTheme.typography.bodyMedium)
    }
}
