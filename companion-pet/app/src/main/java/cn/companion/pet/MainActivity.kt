package cn.companion.pet

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import cn.companion.pet.ui.MINE_MEMORY
import cn.companion.pet.ui.MineTab
import cn.companion.pet.ui.SETTINGS_PERMISSIONS
import cn.companion.pet.ui.SettingsScreen
import cn.companion.pet.ui.StartTab
import cn.companion.pet.ui.components.Eyebrow
import cn.companion.pet.ui.theme.CompanionMotion
import cn.companion.pet.ui.theme.CompanionTheme
import cn.companion.pet.ui.theme.LocalCompanionColors

class MainActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompanionTheme {
                Surface(color = MaterialTheme.colorScheme.background) { Root() }
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

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Root() {
        // 主导航只有“今天 / 我的”；设置放在右上角，打开后是单独一页，返回键回来。
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var mineSection by rememberSaveable { mutableIntStateOf(MINE_MEMORY) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var settingsGroup by rememberSaveable { mutableIntStateOf(SETTINGS_PERMISSIONS) }
        val openSettings: (Int) -> Unit = { g -> settingsGroup = g; settingsOpen = true }
        val openMine: (Int) -> Unit = { m -> mineSection = m; tab = 1 }
        BackHandler(enabled = settingsOpen) { settingsOpen = false }
        val tabs = listOf("今天", "我的")
        val colors = LocalCompanionColors.current
        // 让 testTag 出现在 uiautomator dump 的 resource-id 里，验收脚本按它找按钮，不靠坐标。
        Scaffold(containerColor = colors.paper, modifier = Modifier.semantics { testTagsAsResourceId = true }) { inner ->
            Column(Modifier.padding(inner).fillMaxSize().imePadding().background(colors.paper)) {
                if (settingsOpen) {
                    SettingsScreen(this@MainActivity, resumeTick, settingsGroup, onGroup = { settingsGroup = it }, onBack = { settingsOpen = false })
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("COMPANION")
                            Text("同桌", style = MaterialTheme.typography.headlineMedium, color = colors.ink)
                        }
                        TextButton(onClick = { openSettings(settingsGroup) }, modifier = Modifier.testTag("open_settings")) {
                            Text("⚙ 设置", style = MaterialTheme.typography.labelLarge, color = colors.ink2)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(colors.chip, RoundedCornerShape(18.dp))
                            .padding(4.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().selectableGroup()) {
                            tabs.forEachIndexed { index, label ->
                                TabItem(label, selected = tab == index, modifier = Modifier.weight(1f).testTag("tab_" + index)) { tab = index }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.rule, modifier = Modifier.padding(top = 12.dp))
                    when (tab) {
                        0 -> StartTab(this@MainActivity, resumeTick, openSettings, openMine)
                        else -> MineTab(this@MainActivity, resumeTick, mineSection) { mineSection = it }
                    }
                }
            }
        }
    }

    @Composable
    private fun TabItem(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val colors = LocalCompanionColors.current
        Box(
            Modifier
                .then(modifier)
                .height(44.dp)
                .background(if (selected) colors.panel else colors.chip, RoundedCornerShape(14.dp))
                .selectable(selected = selected, role = Role.Tab, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.ink else colors.ink2,
            )
        }
    }
}
