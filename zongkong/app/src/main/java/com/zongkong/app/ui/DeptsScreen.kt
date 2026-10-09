package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.Defaults
import com.zongkong.core.Dept
import java.time.LocalDate

/** 四个部门是干什么的、怎么协作；谋划思考部的七张方法卡。 */
@Composable
fun DeptsScreen() {
    val context = LocalContext.current
    val store = context.zk.store
    val sig = LocalSignals.current
    val today = LocalDate.parse(DayClock.dateKey(System.currentTimeMillis(), store.zone))
    val method = Defaults.methodOf(today)
    var open by remember { mutableStateOf<Dept?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("四个部门", style = MaterialTheme.typography.headlineSmall)
        Panel {
            Text("一天怎么流转", style = MaterialTheme.typography.titleSmall)
            Flow(
                listOf(
                    Dept.INFO to "收：信息、问题、待办入库",
                    Dept.THINK to "谋：挑一个问题想透，产出下一步",
                    Dept.PLAN to "筹：把下一步和待办排进时间",
                    Dept.ACT to "行：照着做、练分解动作",
                    Dept.HQ to "控：每一步都要交、要验收",
                ),
            )
            Hint("总控不替你做事，只做一件事：没交、交了不合格、太久不汇报，就不让你去娱乐。")
        }

        Defaults.guides.forEach { g ->
            val expanded = open == g.dept
            Panel(accent = if (expanded) sig.dept(g.dept) else null, onClick = { open = if (expanded) null else g.dept }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeptSeal(g.dept, size = 40.dp, filled = expanded)
                    Column(Modifier.weight(1f)) {
                        Text(g.dept.label, style = MaterialTheme.typography.titleMedium)
                        Text(g.duty, style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = if (expanded) 10 else 2)
                    }
                }
                if (expanded) {
                    Field("输入", g.input)
                    Field("产出", g.output)
                    Field("节奏", g.rhythm)
                    Field("工具", g.tools)
                    Field("最常见的坑", g.pitfall)
                }
            }
        }

        SectionLabel("谋划思考部 · 方法卡")
        Panel(accent = sig.think) {
            Text("今天：${method.name}", style = MaterialTheme.typography.titleMedium, color = sig.think)
            Text(method.how, style = MaterialTheme.typography.bodyMedium)
        }
        Defaults.methods.filter { it != method }.forEach { m ->
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleSmall)
                Text(m.how, style = MaterialTheme.typography.bodySmall, color = sig.muted)
            }
        }
        Hint("一周七张轮一遍。想不出好结论没关系，每天交一张完整的谋划单就是胜利；周日回看：哪些结论应验了，置信度准不准。")
    }
}

@Composable
private fun Flow(steps: List<Pair<Dept, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, (d, t) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeptSeal(d, size = 26.dp, filled = true)
                Text(t, style = MaterialTheme.typography.bodyMedium)
            }
            if (i < steps.lastIndex) Text("  ↓", color = LocalSignals.current.muted)
        }
    }
}

@Composable
private fun Field(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = LocalSignals.current.muted, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
