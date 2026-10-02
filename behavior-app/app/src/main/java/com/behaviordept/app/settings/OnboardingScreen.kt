package com.behaviordept.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.components.TianZiGeCell
import com.behaviordept.app.ui.theme.Paper

/** 首次打开：一页说明 + 逐个开启权限。拒绝也能用，只是对应提醒不出现。 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val p = Paper.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(p.paper)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TianZiGeCell(done = true, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Text("行为管理部", style = MaterialTheme.typography.headlineLarge, color = p.ink)
        }
        Text(
            "替你决定下一步练什么，记下你实际做了什么。",
            style = MaterialTheme.typography.titleMedium,
            color = p.ink2,
        )
        PaperCard {
            SectionLabel("怎么用")
            Spacer(Modifier.height(8.dp))
            listOf(
                "打开只看到一件事，点“开始”就进入专注。",
                "学习按四步走：预习提问 → 读资料 → 合上讲一遍 → 举一反三。",
                "讲完之后按 1/3/7/14/30/60 天间隔自测，到期自动排到今日。",
                "AI 只在批改和出题时出现；没网或没配置时改为对照资料自查。",
            ).forEachIndexed { i, line ->
                Row(Modifier.padding(top = 8.dp)) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = p.red, modifier = Modifier.width(22.dp))
                    Text(line, style = MaterialTheme.typography.bodyLarge, color = p.ink)
                }
            }
        }
        PaperCard {
            SectionLabel("开启提醒（可跳过）")
            Spacer(Modifier.height(12.dp))
            PermissionRows()
        }
        Hint("API Key 之后在“设置”里填。没填也能先用，学习环节会改为对照资料自查。")
        InkButton("开始使用", onClick = onDone)
    }
}
