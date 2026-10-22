package com.zongkong.app.guard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 严管时打开拦截名单里的应用，就会被弹到这里。 */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent { ZkTheme { BlockScreen(blockedLabel(), intent.getStringExtra(EXTRA_PKG).orEmpty(), ::goHome, ::openRoute, ::finish) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent { ZkTheme { BlockScreen(blockedLabel(), intent.getStringExtra(EXTRA_PKG).orEmpty(), ::goHome, ::openRoute, ::finish) } }
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
private fun BlockScreen(appLabel: String, blockedPkg: String, goHome: () -> Unit, openRoute: (String) -> Unit, release: () -> Unit) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val day by store.today.collectAsStateWithLifecycle()
    // 训练回执会改今天的娱乐额度：把账本也作为 key，交卷后立刻按新额度重判
    val book by store.training.collectAsStateWithLifecycle()
    val now = rememberNow(5_000)
    val status = remember(config, day, now, book) { store.status(now) }
    val sig = LocalSignals.current
    // 交卷回执要留到“已放行”那一屏也看得见
    var trainingReceipt by remember { mutableStateOf<String?>(null) }

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
        if (status.strict) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(90.dp)) {
            com.zongkong.app.ui.Lightning(0, sig.strict, Modifier.fillMaxSize(), ambient = true)
        } else Spacer(Modifier.height(12.dp))
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
                trainingReceipt?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Text(Engine.summary(status), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = release, modifier = Modifier.fillMaxWidth()) { Text("继续") }
            }
        } else {
            StrictBody(status, config.emergencyMinutes, config.training, blockedPkg, { trainingReceipt = it }, goHome, openRoute, release)
        }
    }
}

@Composable
private fun StrictBody(
    status: com.zongkong.core.Status,
    emergencyMinutes: Int,
    trainingPolicy: com.zongkong.core.moling.TrainingPolicy,
    blockedPkg: String,
    onTrainingMessage: (String) -> Unit,
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
                is Reason.Session -> Panel(accent = sig.strict, onClick = { openRoute("hq") }) {
                    Text("总控时间", style = MaterialTheme.typography.titleMedium)
                    Hint("到 ${clock(r.endsAt)} 之前，只有总控和白名单里的应用能用。")
                    Button(onClick = { openRoute("hq") }, modifier = Modifier.fillMaxWidth()) { Text("回到总控时间") }
                }
                is Reason.Absent -> Panel(accent = sig.strict, onClick = { openRoute("chat") }) {
                    Text("已经 ${r.days} 天没和总控交流了", style = MaterialTheme.typography.titleMedium)
                    Hint("跟总控说几句、或者进一次总控时间，就放行。")
                    Button(onClick = { openRoute("hq") }, modifier = Modifier.fillMaxWidth()) { Text("进入总控时间") }
                    OutlinedButton(onClick = { openRoute("chat") }, modifier = Modifier.fillMaxWidth()) { Text("跟总控说几句") }
                }
                is Reason.Budget -> Panel(accent = sig.strict, onClick = { openRoute("home") }) {
                    Text("今天挣到的娱乐用完了", style = MaterialTheme.typography.titleMedium)
                    Text("已用 ${r.usedMin} 分钟，今天挣到 ${r.allowedMin} 分钟。", style = MaterialTheme.typography.bodyMedium.merge(Mono))
                    Hint("做完一步 +20 分钟，实打实做事每半小时 +10。不做事，就只有每天的底数。")
                    Button(onClick = { openRoute("home") }, modifier = Modifier.fillMaxWidth()) { Text("去做下一步") }
                }
                is Reason.Debt -> Panel(accent = sig.strict, onClick = { openRoute("start/${r.actionKey}") }) {
                    Text("「${r.title}」过了点还没开始", style = MaterialTheme.typography.titleMedium)
                    Hint("排好的事先动起来，娱乐才开。真做不了：改个时间，或者标卡住、写一句为什么——都算交代了。")
                    Button(onClick = { openRoute("start/${r.actionKey}") }, colors = ButtonDefaults.buttonColors(containerColor = sig.strict), modifier = Modifier.fillMaxWidth()) { Text("现在开始") }
                    OutlinedButton(onClick = { openRoute("action/${r.actionKey}") }, modifier = Modifier.fillMaxWidth()) { Text("改时间 / 标卡住") }
                }
                is Reason.Quota -> Panel(accent = sig.strict) {
                    Text("今天的娱乐额度用完了", style = MaterialTheme.typography.titleMedium)
                    Text("已用 ${r.usedMin} 分钟，上限 ${r.limitMin} 分钟。明天凌晨 4 点重置。", style = MaterialTheme.typography.bodyMedium.merge(Mono))
                }
                is Reason.Hold -> Panel(accent = sig.strict) {
                    Text(r.text, style = MaterialTheme.typography.titleMedium)
                    Hint("这是你和墨灵约好的（你同意过，或在授权书里授权过），到点自己解除。真有急事用下面的紧急放行。")
                }
            }
        }

        // 以思代锁：只在“策略开着 + 这个应用在名单里 + 现在只因为挣来的娱乐用完而拦”时出现按钮。
        val canTrain = trainingPolicy.enabled && blockedPkg.isNotBlank() &&
            blockedPkg in trainingPolicy.packages &&
            com.zongkong.core.moling.Training.blocker(status) == null
        if (canTrain) TrainingPanel(trainingPolicy, blockedPkg, onTrainingMessage)

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

/**
 * 拦截页的「做一局训练」卡片（A2）。题目由总控出、总控判；界面只拿题干和选项——
 * [com.zongkong.core.moling.TrainingChallenge.questions] 里带 answer 字段，这里绝不显示、也不记录。
 */
@Composable
private fun TrainingPanel(
    policy: com.zongkong.core.moling.TrainingPolicy,
    blockedPkg: String,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val store = context.zk.store
    val sig = LocalSignals.current
    var challenge by remember { mutableStateOf<com.zongkong.core.moling.TrainingChallenge?>(null) }
    var answers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var remaining by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val c = challenge
    LaunchedEffect(c?.id) {
        val active = c ?: return@LaunchedEffect
        while (true) {
            remaining = ((active.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (remaining <= 0) break
            delay(1_000)
        }
        remaining = 0
    }

    Panel(accent = sig.strict) {
        Text("做一局训练，换 ${policy.minutesPerPass} 分钟", style = MaterialTheme.typography.titleMedium)
        Hint("4 道单选，对 3 道才过。5 分钟内交卷；换来的是今天的娱乐分钟，一小时内有效，用完照旧拦。")
        note?.let { Text(it, color = sig.warn, style = MaterialTheme.typography.bodyMedium) }
        if (c == null) {
            Button(
                onClick = {
                    if (!busy) {
                        busy = true
                        val out = store.trainingIssue(blockedPkg)
                        busy = false
                        val value = out.value
                        if (out.ok && value != null) {
                            challenge = value
                            remaining = ((value.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                            answers = emptyMap()
                            note = null
                        } else {
                            note = out.message
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("做一局训练（换 ${policy.minutesPerPass} 分钟）") }
        } else if (remaining <= 0) {
            Text("这一局超时了（5 分钟没交卷）。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { challenge = null; note = "超时了；想换就再开一局。" }, modifier = Modifier.fillMaxWidth()) { Text("知道了") }
        } else {
            c.questions.forEach { q ->
                Text(q.stem, style = MaterialTheme.typography.bodyLarge)
                q.options.forEach { o ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { answers = answers + (q.id to o.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = answers[q.id] == o.id, onClick = { answers = answers + (q.id to o.id) })
                        Text(o.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(
                "还剩 ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodyMedium.merge(Mono),
                color = if (remaining <= 60) sig.warn else sig.muted,
            )
            Button(
                onClick = {
                    if (!busy) {
                        busy = true
                        val receipt = store.trainingSubmit(c.id, c.nonce, answers)
                        busy = false
                        challenge = null
                        answers = emptyMap()
                        note = receipt.message
                        onMessage(receipt.message)
                    }
                },
                enabled = !busy && c.questions.all { answers.containsKey(it.id) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("交卷") }
            TextButton(onClick = { challenge = null; note = "这一局先不交了；开过就算一次尝试。" }) { Text("先不做") }
        }
    }
}

