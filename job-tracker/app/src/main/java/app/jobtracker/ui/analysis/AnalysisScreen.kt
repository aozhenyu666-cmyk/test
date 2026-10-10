package app.jobtracker.ui.analysis

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jobtracker.R
import app.jobtracker.analysis.Advice
import app.jobtracker.analysis.AnalysisResult
import app.jobtracker.data.model.MatchLevel
import app.jobtracker.ui.describe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel,
    onBack: () -> Unit,
    onGoToSettings: () -> Unit,
    onAdded: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.analysis_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val length = state.jd.trim().length
            OutlinedTextField(
                value = state.jd,
                onValueChange = viewModel::onJdChange,
                label = { Text(stringResource(R.string.analysis_jd)) },
                placeholder = { Text(stringResource(R.string.analysis_jd_hint)) },
                supportingText = { Text(stringResource(R.string.analysis_jd_count, length, viewModel.jdMinChars)) },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.hasResume == false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.analysis_need_resume),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onGoToSettings) { Text(stringResource(R.string.analysis_go_settings)) }
                }
            }

            Button(
                onClick = viewModel::analyze,
                enabled = viewModel.canAnalyze(state),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.analysis_analyze))
            }

            if (state.loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.analysis_loading))
                }
            }

            state.error?.let {
                Text(it.describe(context), color = MaterialTheme.colorScheme.error)
            }

            state.result?.let { result ->
                HorizontalDivider()
                ResultSection(
                    result = result,
                    company = state.company,
                    title = state.title,
                    onCompanyChange = viewModel::onCompanyChange,
                    onTitleChange = viewModel::onTitleChange,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.addToApplications { onAdded() } },
                        enabled = !state.added && !state.loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(if (state.added) R.string.analysis_added else R.string.analysis_add))
                    }
                    OutlinedButton(
                        onClick = viewModel::analyze,
                        enabled = viewModel.canAnalyze(state),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.analysis_reanalyze))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultSection(
    result: AnalysisResult,
    company: String,
    title: String,
    onCompanyChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
) {
    val a = result.analysis
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = company,
            onValueChange = onCompanyChange,
            label = { Text(stringResource(R.string.field_company)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text(stringResource(R.string.field_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.analysis_match_and_advice, stringResource(a.level.labelRes()), stringResource(a.adviceValue.labelRes())),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(a.reason)
        BulletList(R.string.analysis_hard_requirements, a.hardRequirements)
        BulletList(R.string.analysis_strengths, a.strengths)
        BulletList(R.string.analysis_gaps, a.gaps)
        BulletList(R.string.analysis_resume_tips, a.resumeTips)
    }
}

@Composable
private fun BulletList(@StringRes titleRes: Int, items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
        if (items.isEmpty()) {
            Text(stringResource(R.string.analysis_none), style = MaterialTheme.typography.bodyMedium)
        }
        items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

@StringRes
fun MatchLevel.labelRes(): Int = when (this) {
    MatchLevel.HIGH -> R.string.match_high
    MatchLevel.MEDIUM -> R.string.match_medium
    MatchLevel.LOW -> R.string.match_low
}

@StringRes
fun Advice.labelRes(): Int = when (this) {
    Advice.APPLY -> R.string.advice_apply
    Advice.MAYBE -> R.string.advice_maybe
    Advice.SKIP -> R.string.advice_skip
}
