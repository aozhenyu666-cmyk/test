package cn.companion.pet

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cn.companion.pet.ui.MemoryTab
import cn.companion.pet.ui.SettingsTab
import cn.companion.pet.ui.StartTab
import cn.companion.pet.ui.StatsTab

/**
 * 设置页：开始（权限引导）、设置、记忆、统计。
 */
class MainActivity : ComponentActivity() {

    /** 每次回到前台 +1，让各页重新读取权限状态和文件。 */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFFF2B67C)) else lightColorScheme(primary = Color(0xFFB8682A)),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) { Root() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick++
    }

    fun requestMic() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val tabs = listOf("开始", "设置", "记忆", "统计")
        Scaffold { inner ->
            Column(Modifier.padding(inner)) {
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
                when (tab) {
                    0 -> StartTab(this@MainActivity, resumeTick)
                    1 -> SettingsTab(this@MainActivity)
                    2 -> MemoryTab(resumeTick)
                    else -> StatsTab(resumeTick)
                }
            }
        }
    }
}
