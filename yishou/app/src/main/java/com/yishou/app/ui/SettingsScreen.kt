package com.yishou.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.llm.LlmConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEditTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    onAbout: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val saved by vm.llm.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()

    var baseUrl by rememberSaveable { mutableStateOf(saved.baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(saved.apiKey) }
    var model by rememberSaveable { mutableStateOf(saved.model) }
    var jsonMode by rememberSaveable { mutableStateOf(saved.jsonMode) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(topBar = { BackTopBar("设置", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("当前任务")
            val current = tasks.firstOrNull { it.isCurrent }
            if (current == null) {
                Text("还没有当前任务。")
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(current.title, fontWeight = FontWeight.Bold)
                        Text(current.goal, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onEditTask(current.id) }) { Text("编辑任务和断点") }
                    }
                }
            }
            OutlinedButton(onClick = onNewTask) { Text("新建任务并切换过去") }

            val others = tasks.filter { !it.isCurrent }
            if (others.isNotEmpty()) {
                Text("其他任务（点一下切换为当前任务，断点都保留着）", style = MaterialTheme.typography.bodySmall)
                others.forEach { t ->
                    Text(
                        t.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.switchTo(t.id) }
                            .padding(vertical = 10.dp),
                    )
                }
            }

            HorizontalDivider()
            SectionTitle("大模型接口")
            Text(
                "OpenAI 兼容接口。DeepSeek 示例：地址 https://api.deepseek.com，模型 deepseek-chat。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; message = null },
                label = { Text("接口地址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; message = null },
                label = { Text("密钥") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; message = null },
                label = { Text("模型名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("JSON 模式")
                    Text(
                        "要求接口只返回 JSON。DeepSeek 支持；如果报 400 错误再关掉。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = jsonMode, onCheckedChange = { jsonMode = it; message = null })
            }
            Button(onClick = {
                message = vm.saveLlm(LlmConfig(baseUrl, apiKey, model, jsonMode)) ?: "已保存"
            }) { Text("保存接口设置") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            HorizontalDivider()
            TextButton(onClick = onAbout) { Text("关于「一手」") }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}
