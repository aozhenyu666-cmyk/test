package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.BlockMode
import com.zongkong.core.ChangeOp
import com.zongkong.core.DayClock
import com.zongkong.core.Dept
import com.zongkong.core.Gate
import com.zongkong.core.Policy
import com.zongkong.core.VerifyMode

private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun GatesScreen(nav: NavHostController) {
    val context = LocalContext.current
    val config by context.zk.store.config.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    Column(Modifier.fillMaxSize()) {
        BackBar("关卡", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Hint("每道关卡是某个部门每天要交的一份东西。新增、收紧立即生效；删除、放宽 24 小时后生效。")
            config.gates.sortedBy { DayClock.rank(it.deadline) }.forEach { g ->
                val pending = config.pending.any { p ->
                    (p.op as? ChangeOp.PutGate)?.gate?.id == g.id || (p.op as? ChangeOp.RemoveGate)?.gateId == g.id
                }
                Panel(onClick = { nav.navigate("settings/gate/${g.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DeptSeal(g.dept)
                        Column(Modifier.weight(1f)) {
                            Text(g.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${DayClock.hhmm(g.openAt)}–${DayClock.hhmm(g.deadline)} · ${daysLabel(g.days)} · ${g.block.label} · ${g.verify.label}",
                                style = MaterialTheme.typography.bodySmall, color = sig.muted,
                            )
                        }
                        if (pending) Tag("有改动待生效", sig.warn)
                    }
                }
            }
            Button(onClick = { nav.navigate("settings/gate/new") }, modifier = Modifier.fillMaxWidth()) { Text("新增关卡") }
            var applied by remember { mutableStateOf<String?>(null) }
            OutlinedButton(onClick = {
                val store = context.zk.store
                val now = System.currentTimeMillis()
                val msgs = com.zongkong.core.Defaults.gates().map { g ->
                    val out = Policy.putGate(store.config.value, g, now)
                    store.updateConfig { out.config }
                    out.message
                }.filter { it != "没有改动" }
                applied = msgs.joinToString("\n").ifBlank { "已经是推荐节律" }
            }, modifier = Modifier.fillMaxWidth()) { Text("采用推荐节律（看事项证据）") }
            Hint("推荐节律：今日部署看“今天排了带完成依据的行动”，收集看“材料关联到事项”，谋划看“形成了判断”。放宽的部分照样等 24 小时。")
            applied?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

fun daysLabel(days: Set<Int>): String = when {
    days.size == 7 -> "每天"
    days == setOf(1, 2, 3, 4, 5) -> "工作日"
    days == setOf(6, 7) -> "周末"
    else -> "周" + days.sorted().joinToString("") { WEEK[it - 1] }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GateEditScreen(gateId: String, nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val isNew = gateId == "new"
    val original = remember(gateId) {
        config.gates.firstOrNull { it.id == gateId }
            ?: Gate(
                id = "g" + System.currentTimeMillis().toString(36),
                dept = Dept.INFO, title = "", instruction = "",
                openAt = 4 * 60, deadline = 21 * 60,
            )
    }
    var dept by remember { mutableStateOf(original.dept) }
    var title by remember { mutableStateOf(original.title) }
    var instruction by remember { mutableStateOf(original.instruction) }
    var template by remember { mutableStateOf(original.template) }
    var openAt by remember { mutableStateOf(DayClock.hhmm(original.openAt)) }
    var deadline by remember { mutableStateOf(DayClock.hhmm(original.deadline)) }
    var days by remember { mutableStateOf(original.days) }
    var block by remember { mutableStateOf(original.block) }
    var verify by remember { mutableStateOf(original.verify) }
    var minChars by remember { mutableStateOf(original.minChars.toString()) }
    var rubric by remember { mutableStateOf(original.rubric) }
    var notionDb by remember { mutableStateOf(original.notionDb) }
    var notionMin by remember { mutableStateOf(original.notionMinPages.toString()) }
    var launch by remember { mutableStateOf(original.launch) }
    var evidence by remember { mutableStateOf(original.evidence.ifBlank { com.zongkong.core.work.Focus.Evidence.PLANNED_TODAY.name }) }
    var evidenceMin by remember { mutableStateOf(original.evidenceMin.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar(if (isNew) "新增关卡" else "修改关卡", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("部门")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dept.entries.forEach { d -> FilterChip(selected = dept == d, onClick = { dept = d }, label = { Text(d.label) }) }
            }
            OutlinedTextField(title, { title = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(instruction, { instruction = it }, label = { Text("要求：做什么、交什么") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(template, { template = it }, label = { Text("格式（可选，一键填入）") }, minLines = 3, modifier = Modifier.fillMaxWidth())

            SectionLabel("时间")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(openAt, { openAt = it }, label = { Text("开放") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(deadline, { deadline = it }, label = { Text("截止") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Hint("凌晨 4 点换日：截止写 01:00 表示当天深夜 1 点。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..7).forEach { d ->
                    FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(WEEK[d - 1]) })
                }
            }

            SectionLabel("什么时候拦截")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BlockMode.entries.forEach { b -> FilterChip(selected = block == b, onClick = { block = b }, label = { Text(b.label) }) }
            }
            Hint(if (block == BlockMode.FROM_OPEN) "开放后一直拦截，交了才放行。" else "截止前随便用，过了截止还没交才拦截。")

            SectionLabel("怎么验收")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VerifyMode.entries.forEach { v -> FilterChip(selected = verify == v, onClick = { verify = v }, label = { Text(v.label) }) }
            }
            if (verify == VerifyMode.EVIDENCE) {
                Text("看哪种证据", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.zongkong.core.work.Focus.Evidence.entries.forEach { e ->
                        FilterChip(selected = evidence == e.name, onClick = { evidence = e.name }, label = { Text(e.label) })
                    }
                }
                Hint(com.zongkong.core.work.Focus.Evidence.entries.first { it.name == evidence }.describe)
                NumberField("至少几条", evidenceMin, { evidenceMin = it }, Modifier.fillMaxWidth(), "条")
            }
            if (verify != VerifyMode.NOTION && verify != VerifyMode.EVIDENCE) {
                NumberField("最少字数（不计模板标签）", minChars, { minChars = it }, Modifier.fillMaxWidth(), "字")
            }
            if (verify == VerifyMode.AI || verify == VerifyMode.NOTION_AI) {
                OutlinedTextField(rubric, { rubric = it }, label = { Text("AI 验收标准（一行一条）") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            }
            if (verify == VerifyMode.NOTION || verify == VerifyMode.NOTION_AI) {
                OutlinedTextField(notionDb, { notionDb = it }, label = { Text("Notion 数据库链接") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (notionDb.isBlank() && config.notion.inboxDb.isNotBlank()) {
                    OutlinedButton(onClick = { notionDb = config.notion.inboxDb }) { Text("用信息收集库") }
                }
                NumberField("当天至少新建几条", notionMin, { notionMin = it }, Modifier.fillMaxWidth(), "条")
            }

            SectionLabel("“去做”按钮（可选）")
            OutlinedTextField(launch, { launch = it }, label = { Text("应用包名或链接") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Hint("例：com.behaviordept.app（行为管理部）、notion.id（Notion）、com.openai.chatgpt，或者一个 Notion 页面链接。")

            Button(
                onClick = {
                    val o = DayClock.parse(openAt)
                    val d = DayClock.parse(deadline)
                    message = when {
                        title.isBlank() -> "写个名称"
                        o == null || d == null -> "时间格式不对，写成 09:30 这样"
                        DayClock.rank(d) <= DayClock.rank(o) -> "截止要晚于开放"
                        days.isEmpty() -> "至少选一天"
                        else -> {
                            val g = original.copy(
                                dept = dept, title = title.trim(), instruction = instruction.trim(), template = template.trimEnd(),
                                openAt = o, deadline = d, days = days, block = block, verify = verify,
                                minChars = minChars.toIntOrNull() ?: original.minChars, rubric = rubric.trim(),
                                notionDb = notionDb.trim(), notionMinPages = (notionMin.toIntOrNull() ?: 1).coerceAtLeast(1),
                                launch = launch.trim(),
                                evidence = if (verify == VerifyMode.EVIDENCE) evidence else original.evidence,
                                evidenceMin = (evidenceMin.toIntOrNull() ?: original.evidenceMin).coerceAtLeast(1),
                            )
                            val out = Policy.putGate(store.config.value, g, System.currentTimeMillis())
                            store.updateConfig { out.config }
                            done = true
                            out.message
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }
            if (!isNew) {
                OutlinedButton(
                    onClick = {
                        val out = Policy.removeGate(store.config.value, original.id, System.currentTimeMillis())
                        store.updateConfig { out.config }
                        done = true
                        message = out.message
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("删除这道关卡（24 小时后生效）") }
            }
        }
    }
    OutcomeDialog(message) {
        message = null
        if (done) nav.popBackStack()
    }
}
