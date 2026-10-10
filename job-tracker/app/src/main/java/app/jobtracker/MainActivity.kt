package app.jobtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.jobtracker.ui.home.HomeScreen
import app.jobtracker.ui.settings.SettingsScreen
import app.jobtracker.ui.settings.SettingsViewModel
import app.jobtracker.ui.theme.JobTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as JobTrackerApp).container
        setContent {
            JobTrackerTheme {
                AppRoot(container)
            }
        }
    }
}

private enum class Tab { HOME, SETTINGS }

@Composable
private fun AppRoot(container: AppContainer) {
    val hasResume by container.profile.observeHasResume().collectAsStateWithLifecycle(initialValue = null)
    var tab by rememberSaveable { mutableStateOf<Tab?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // 首次使用还没有简历时，直接进设置页去粘贴简历
    LaunchedEffect(hasResume) {
        val known = hasResume ?: return@LaunchedEffect
        if (tab == null) tab = if (known) Tab.HOME else Tab.SETTINGS
    }
    val current = tab ?: return

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = current == Tab.HOME,
                    onClick = { tab = Tab.HOME },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_home)) },
                )
                NavigationBarItem(
                    selected = current == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        when (current) {
            Tab.HOME -> HomeScreen(Modifier.padding(padding))
            Tab.SETTINGS -> {
                val vm: SettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { SettingsViewModel(container.profile, container.settings, container.apiKeys) }
                    },
                )
                SettingsScreen(vm, snackbar, Modifier.padding(padding))
            }
        }
    }
}
