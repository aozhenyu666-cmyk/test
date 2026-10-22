package com.moling.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.core.live.CognitiveProfile
import com.moling.core.live.PatternState
import kotlinx.coroutines.launch

/**
 * 认知画像（v13 W10 T8）：她怎么找你、你怎么回应——只从真实出现记录里数。
 * 不足 10 次就写“样本不足”；记录里没有的情境写“未知”；规律要你点“对”才算，点“停用”就不提。
 * 这一页只给你看，不改她的出现方式、不写进关于你的长期判断。
 */
@Composable
fun ProfileSettings() {
    val brain by BrainHost.state.collectAsState()
    val p = remember(brain.habits, brain.profileRules) { BrainHost.profile() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("她怎么找你", style = MaterialTheme.typography.titleSmall)
        Text(
            "只数她真的出现过的记录：用的哪种方式、几点、你是回了、敷衍、跳过、明确说不聊，还是点了急事。" +
                "没被看到的（浮层被拦、没戴耳机）单列，不算你不理她。少于 ${CognitiveProfile.MIN_SAMPLES} 次不下结论。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (p == null) {
            Text("她还没醒（大脑没加载），稍后再看。", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val t = p.total
        Text(
            if (t.enough) "一共 ${t.samples} 次有效样本。" else "一共 ${t.samples} 次有效样本：样本不足，先不下结论。",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (p.byModality.isNotEmpty()) {
            Text("按方式", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            p.byModality.forEach { Text(it.line(), style = MaterialTheme.typography.bodySmall) }
        }
        if (p.byTime.isNotEmpty()) {
            Text("按时段", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            p.byTime.forEach { Text(it.line(), style = MaterialTheme.typography.bodySmall) }
        }
        Text("记录里没有的", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        p.unknown.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        Text("她看出的规律", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        if (p.patterns.isEmpty()) {
            Text("还没有：哪一种方式或时段够 ${CognitiveProfile.MIN_SAMPLES} 次、又特别明显，才会提出来给你看。", style = MaterialTheme.typography.bodySmall)
        }
        p.patterns.forEach { pat ->
            val tag = when (pat.state) {
                PatternState.PROPOSED -> "她这么觉得，你还没说"
                PatternState.ACCEPTED -> "你说对"
                PatternState.DISABLED -> "你停用了"
            }
            Text("${pat.text}（$tag）", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pat.state != PatternState.ACCEPTED) TextButton(onClick = { scope.launch { BrainHost.setProfileRule(pat.id, PatternState.ACCEPTED) } }) { Text("对") }
                if (pat.state != PatternState.DISABLED) TextButton(onClick = { scope.launch { BrainHost.setProfileRule(pat.id, PatternState.DISABLED) } }) { Text("停用") }
                if (pat.state != PatternState.PROPOSED) TextButton(onClick = { scope.launch { BrainHost.setProfileRule(pat.id, null) } }) { Text("撤回") }
            }
        }
        Text(
            "这一页只给你看：规律你点了“对”也不会马上改她的出现方式，下一批才接。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
