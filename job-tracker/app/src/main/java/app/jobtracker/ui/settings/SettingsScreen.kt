package app.jobtracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jobtracker.R

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(context.getString(it))
            viewModel.messageShown()
        }
    }

    if (!state.loaded) return

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(R.string.settings_section_profile)
        OutlinedTextField(
            value = state.resumeText,
            onValueChange = viewModel::onResumeChange,
            label = { Text(stringResource(R.string.settings_resume)) },
            placeholder = { Text(stringResource(R.string.settings_resume_hint)) },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.direction,
            onValueChange = viewModel::onDirectionChange,
            label = { Text(stringResource(R.string.settings_direction)) },
            placeholder = { Text(stringResource(R.string.settings_direction_hint)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = viewModel::saveProfile, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_save_profile))
        }

        SectionTitle(R.string.settings_section_model)
        ApiKeyField(
            value = state.apiKeyInput,
            hasSavedKey = state.hasSavedKey,
            onValueChange = viewModel::onApiKeyChange,
            onClear = viewModel::clearApiKey,
        )
        OutlinedTextField(
            value = state.apiBaseUrl,
            onValueChange = viewModel::onBaseUrlChange,
            label = { Text(stringResource(R.string.settings_base_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.strongModel,
            onValueChange = viewModel::onStrongModelChange,
            label = { Text(stringResource(R.string.settings_strong_model)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.fastModel,
            onValueChange = viewModel::onFastModelChange,
            label = { Text(stringResource(R.string.settings_fast_model)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.modelError?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = viewModel::saveModelSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_save_model))
        }
    }
}

@Composable
private fun SectionTitle(textRes: Int) {
    Text(
        stringResource(textRes),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ApiKeyField(
    value: String,
    hasSavedKey: Boolean,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.settings_api_key)) },
        placeholder = {
            Text(stringResource(if (hasSavedKey) R.string.settings_api_key_saved_hint else R.string.settings_api_key_hint))
        },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.settings_hide else R.string.settings_show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (hasSavedKey) {
        Row {
            Text(
                stringResource(R.string.settings_api_key_status_saved),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(top = 12.dp),
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.settings_clear_key)) }
        }
    }
}
