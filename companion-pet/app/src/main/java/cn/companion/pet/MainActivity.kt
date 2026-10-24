package cn.companion.pet

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import cn.companion.pet.ui.MemoryTab
import cn.companion.pet.ui.SettingsTab
import cn.companion.pet.ui.StartTab
import cn.companion.pet.ui.StatsTab
import cn.companion.pet.ui.components.Eyebrow
import cn.companion.pet.ui.theme.CompanionMotion
import cn.companion.pet.ui.theme.CompanionTheme
import cn.companion.pet.ui.theme.LocalCompanionColors

/**
 * 设置页：开始（权限与状态）、设置、记忆、统计。四页信息层次一致：
 * 眉标 + 标题 + 分组细线，不用卡片墙。
 */
class MainActivity : ComponentActivity() {

    /** 每次回到前台 +1，让各页重新读取权限状态和文件。 */
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
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val tabs = listOf("开始", "设置", "记忆", "统计")
        val colors = LocalCompanionColors.current
        // 让 testTag 出现在 uiautomator dump 的 resource-id 里，验收脚本按它找按钮，不靠坐标。
        Scaffold(containerColor = colors.paper, modifier = Modifier.semantics { testTagsAsResourceId = true }) { inner ->
            Column(Modifier.padding(inner).fillMaxSize().imePadding().background(colors.paper)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Eyebrow("COMPANION")
                    Text("桌宠", style = MaterialTheme.typography.headlineMedium, color = colors.ink)
                }
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    tabs.forEachIndexed { index, label ->
                        TabItem(label, selected = tab == index, modifier = Modifier.weight(1f).testTag("tab_" + index)) { tab = index }
                    }
                }
                HorizontalDivider(color = colors.rule)
                when (tab) {
                    0 -> StartTab(this@MainActivity, resumeTick)
                    1 -> SettingsTab(this@MainActivity)
                    2 -> MemoryTab(resumeTick)
                    else -> StatsTab(resumeTick)
                }
            }
        }
    }

    @Composable
    private fun TabItem(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val colors = LocalCompanionColors.current
        Column(
            Modifier
                .then(modifier)
                .height(48.dp)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.ink else colors.ink2,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (selected) 2.dp else 0.dp)
                    .background(colors.signal),
            )
        }
    }
}