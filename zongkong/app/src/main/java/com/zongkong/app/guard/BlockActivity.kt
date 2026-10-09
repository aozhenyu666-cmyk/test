package com.zongkong.app.guard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zongkong.app.ui.DeptSeal
import com.zongkong.app.ui.Hint
import com.zongkong.app.ui.LocalSignals
import com.zongkong.app.ui.MainActivity
import com.zongkong.app.ui.Mono
import com.zongkong.app.ui.Panel
import com.zongkong.app.ui.Seal
import com.zongkong.app.ui.ZkTheme
import com.zongkong.app.ui.clock
import com.zongkong.app.ui.rememberNow
import com.zongkong.app.zk
import com.zongkong.core.Engine
import com.zongkong.core.Reason
import kotlinx.coroutines.launch

/** 严管时打开拦截名单里的应用，就会被弹到这里。 */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent { ZkTheme { BlockScreen(blockedLabel(), ::goHome, ::openRoute, ::finish) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent { ZkTheme { BlockScreen(blockedLabel(), ::goHome, ::openRoute, ::finish) } }
    }

    private fun blockedLabel(): String {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return "娱乐应用"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun goHome() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        finish()
    }

    private fun openRoute(route: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, route),
        )
        finish()
    }

    companion object {
        const val EXTRA_PKG = "pkg"

        fun intent(context: Context, pkg: String): Intent = Intent(context, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_PKG, pkg)
    }
}

@Composable
private fun BlockScreen(appLabel: String, goHome: () -> Unit, openRoute: (String) -> Unit, release: () -> Unit) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val day by store.today.collectAsStateWithLifecycle()
    val now = rememberNow(5_000)
    val status = remember(config, day, now) { store.status(now) }
    val sig = LocalSignals.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Seal(if (status.strict) "严" else "放", if (status.strict) sig.strict else sig.free, size = 64.dp, filled = true)
            Column {
                Text(
                    if (status.strict) "严管中" else "已放行",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (status.strict) sig.strict else sig.free,
                )
                Text("「$appLabel」暂不放行", style = MaterialTheme.typography.bodyMedium, color = sig.muted)
            }
        }

        if (!status.strict) {
            Panel(accent = sig.free) {
                Text(Engine.summary(status), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = release, modifier = Modifier.fillMaxWidth()) { Text("继续") }
            }
        } else {
            StrictBody(status, config.emergencyMinutes, goHome, openRoute, release)
        }
    }
}

@Composable
private fun StrictBody(
    status: com.zongkong.core.Status,
    emergencyMinutes: Int,
    goHome: () -> Unit,
    openRoute: (String) -> Unit,
    release: () -> Unit,
) {
    val context = LocalContext.current
    val store = context.zk.store
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    var checkin by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var showEmergency by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Text("为什么", style = MaterialTheme.typography.titleSmall, color = sig.muted)
        status.reasons.forEach { r ->
            when (r) {
                is Reason.GateDue -> Panel(accent = sig.strict, onClick = { openRoute("gate/${r.status.gate.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DeptSeal(r.status.gate.dept)
                        Column(Modifier.weight(1f)) {
                            Text(r.status.gate.title, style = MaterialTheme.typography.titleMedium)
                            Text(r.text, style = MaterialTheme.typography.bodySmall, color = sig.muted)
                        }
                    }
                    Button(
                        onClick = { openRoute("gate/${r.status.gate.id}") },
                        colors = ButtonDefaults.buttonColors(containerColor = sig.strict),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("现在去交") }
                }
                is Reason.Silence -> Panel(accent = sig.warn) {
                    Text("太久没汇报", style = MaterialTheme.typography.titleMedium)
                    Hint("从 ${clock(r.sinceMillis)} 起没有任何汇报。报一句现在在做什么、接下来做什么，就放行。")
                    OutlinedTextField(
                        value = checkin, onValueChange = { checkin = it },
                        placeholder = { Text("例：在做行测资料分析，做完这套去吃饭") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                    Button(
                        onClick = { scope.launch { message = context.zk.actions.checkin(checkin) ?: "已报到".also { checkin = "" } } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("报到") }
                }
                is Reason.Focus -> Panel(accent = MaterialTheme.colorScheme.primary, onClick = { openRoute("session") }) {
                    Text("专注锁：${r.title}", style = MaterialTheme.typography.titleMedium)
                    Hint("你开始做这一步时开了专注锁，到 ${clock(r.until)} 为止娱乐应用会被弹回。做完或者记个断点，锁就解开。")
                    Button(onClick = { openRoute("session") }, modifier = Modifier.fillMaxWidth()) { Text("回去继续") }
                }
                is Reason.Quota -> Panel(accent = sig.strict) {
                    Text("今天的娱乐额度用完了", style = MaterialTheme.typography.titleMedium)
                    Text("已用 ${r.usedMin} 分钟，上限 ${r.limitMin} 分钟。明天凌晨 4 点重置。", style = MaterialTheme.typography.bodyMedium.merge(Mono))
                }
            }
        }

        message?.let { Text(it, color = sig.warn, style = MaterialTheme.typography.bodyMedium) }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = goHome, modifier = Modifier.fillMaxWidth()) { Text("回桌面") }

        if (status.emergencyLeft > 0) {
            if (!showEmergency) {
                TextButton(onClick = { showEmergency = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("紧急放行（今天还剩 ${status.emergencyLeft} 次，每次 $emergencyMinutes 分钟）", color = sig.muted)
                }
            } else {
                Panel {
                    Text("紧急放行", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Hint("写清楚为什么必须现在用（至少 20 字）。会记进今天的日终验收，AI 验收时会看到。")
                    OutlinedTextField(value = reason, onValueChange = { reason = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Button(
                        onClick = {
                            message = context.zk.actions.emergency(reason)
                            if (!store.status().strict) release()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("申请放行 $emergencyMinutes 分钟") }
                }
            }
        } else {
            Hint("今天的紧急放行已用完。", Modifier.fillMaxWidth())
        }
    }
}
