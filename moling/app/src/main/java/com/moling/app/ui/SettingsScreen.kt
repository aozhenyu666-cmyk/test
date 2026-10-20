package com.moling.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.moling.app.data.LlmResult
import com.moling.app.data.ModelSettings
import com.moling.app.data.MolingAsk
import com.moling.app.data.SettingsStore
import com.moling.core.mind.DEFAULT_PERSONA
import com.moling.core.power.Charter
import com.moling.core.power.Tier
import kotlinx.coroutines.launch

/**
 * 设置页：接口、密钥、模型、人格、称呼、朗读。
 * “测试连接”只发一句“你好”，失败把原因写在按钮下面，界面不会停。
 * 权柄说明只读：等级怎么算是核心库 Charter 的事，这里只展示。
 */
@Composable
fun SettingsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val saved = remember { store.load() }

    var url by remember { mutableStateOf(saved.baseUrl) }
    var key by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var persona by remember { mutableStateOf(saved.persona) }
    var callUser by remember { mutableStateOf(saved.callUser) }
    var speak by remember { mutableStateOf(saved.speak) }

    var testing by remember { mutableStateOf(false) }
    var testText by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(store.usageToday()) }
    val scope = rememberCoroutineScope()

    /** 屏幕上的值整理成一份配置：空的人格和称呼用默认的，免得提示词里出现空段。 */
    fun draft() = ModelSettings(
        baseUrl = url.trim(),
        apiKey = key.trim(),
        model = model.trim(),
        persona = persona.trim().ifBlank { DEFAULT_PERSONA },
        callUser = callUser.trim().ifBlank { "你" },
        speak = speak,
    )

    Column(Modifier.fillMaxSize()) {
        // 顶部返回：设置页不套 Scaffold，直接给标题栏，少一层壳。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDone) { Text("← 返回") }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("AI 接口", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("接口地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("填到 /v1 也行；没写 /chat/completions 会自动补上。") },
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("密钥") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("只存在这台手机上（加密保存），不会传到别处。") },
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("DeepSeek 填 deepseek-chat。") },
            )

            Text("她是谁", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it },
                label = { Text("人格设定") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("导演和对话都会带上这段；不动就用墨灵默认的口吻。") },
            )
            OutlinedTextField(
                value = callUser,
                onValueChange = { callUser = it },
                label = { Text("称呼") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("她怎么叫你。") },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("朗读回复")
                    Text(
                        "对话页把她的回复念出来（用系统语音）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = speak, onCheckedChange = { speak = it })
            }

            // 先存下屏幕上的值再测：测的应该是正在填的这一份，不是上次保存的。
            OutlinedButton(
                onClick = {
                    if (!testing) {
                        store.save(draft())
                        testing = true
                        testText = null
                        scope.launch {
                            val r = MolingAsk(store).test()
                            testOk = r is LlmResult.Ok
                            testText = when (r) {
                                is LlmResult.Ok -> "连接成功：${r.value.trim().take(30)}"
                                is LlmResult.Err -> r.message
                            }
                            usage = store.usageToday()
                            testing = false
                        }
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (testing) "测试中…" else "测试连接")
            }
            testText?.let { text ->
                Surface(
                    color = if (testOk) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "今天调用 ${usage.calls} 次、${usage.chars} 字",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("她能做什么（只读）", style = MaterialTheme.typography.titleSmall)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    Charter.describe(Tier.PLANNER),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "等级由她在核心库里的统计决定；这里只展示，改不了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(tonalElevation = 2.dp) {
            Button(
                onClick = {
                    store.save(draft())
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text("保存") }
        }
    }
}
