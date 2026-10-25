package cn.companion.pet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.companion.pet.MainActivity
import cn.companion.pet.ui.theme.CompanionShapes
import cn.companion.pet.ui.theme.CompanionSpacing

/**
 * 我的：它自己的东西放在一起——记忆、目标（含待确认）、技能、人设、统计。
 * 记忆和统计沿用原来的页面；目标/技能/人设从设置里搬过来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MineTab(activity: MainActivity, resumeTick: Int, section: Int, onSection: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.padding(horizontal = CompanionSpacing.page, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MINE_GROUPS.forEachIndexed { i, label ->
                val tag = Modifier.testTag("mine_group_" + i)
                if (i == section) Button(onClick = {}, modifier = tag, shape = CompanionShapes.button) { Text(label) }
                else OutlinedButton(onClick = { onSection(i) }, modifier = tag, shape = CompanionShapes.button) { Text(label) }
            }
        }
        key(section) {
            when (section) {
                MINE_MEMORY -> MemoryTab(resumeTick)
                MINE_STATS -> StatsTab(resumeTick)
                else -> Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = CompanionSpacing.page, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (section) {
                        MINE_GOALS -> { AutonomySettings(); GoalSection(); PendingActionSection() }
                        MINE_SKILLS -> SkillsSection(activity)
                        else -> PersonaSection(activity)
                    }
                }
            }
        }
    }
}
