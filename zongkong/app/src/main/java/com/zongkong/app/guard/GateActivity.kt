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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zongkong.app.data.AppNames
import com.zongkong.app.ui.BigButton
import com.zongkong.app.ui.Hint
import com.zongkong.app.ui.LocalSignals
import com.zongkong.app.ui.Mono
import com.zongkong.app.ui.Panel
import com.zongkong.app.ui.Seal
import com.zongkong.app.ui.ZkTheme
import com.zongkong.app.watch.BankStore
import com.zongkong.app.zk
import com.zongkong.core.ApiResult
import com.zongkong.core.WatchRule
import com.zongkong.core.agent.ChatClient
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.agent.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 低屏模式的做题页：连续用某个应用到了时限，被 [Watch] 弹到这里。
 *
 * 流程：显示已经连续用了多久 → 从题库随机抽一道题（没题库就用内置兜底题）→
 * 冷却 cooldownSeconds 秒（先想一想）→ 交卷 → AI 判或本地宽松判 →
 * 通过就写放行（再给 maxMinutes 分钟）并 finish；不通过就显示反馈，再等一轮冷却。
 *
 * 普通 Activity + Compose setContent，不接 Compose Navigation：它不在主任务里。
 * 返回键直接回桌面，绕过不了。按“不去了”也是回桌面。
 */
class GateActivity : ComponentActivity() {

    private val pkgState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pkgState.value = intent?.getStringExtra(EXTRA_PKG).orEmpty()
        // 返回键 = 回桌面：不能靠返回绕过做题
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent {
            ZkTheme {
                GateScreen(
                    pkg = pkgState.value,
                    onHome = ::goHome,
                    onPass = ::pass,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pkgState.value = intent.getStringExtra(EXTRA_PKG).orEmpty()
    }

    /** 不去了 / 返回键：直接回桌面，不做任何放行。 */
    private fun goHome() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
        finish()
    }

    /** 判分通过：写放行（再给 maxMinutes 分钟），关掉做题页。 */
    private fun pass(pkg: String) {
        val minutes = zk.store.config.value.let { it.watch.ruleFor(pkg, it.blocked) }?.maxMinutes ?: 2
        Watch.grant(this, pkg, System.currentTimeMillis() + minutes * 60_000L)
        finish()
    }

    companion object {
        const val EXTRA_PKG = "pkg"

        fun intent(context: Context, pkg: String): Intent =
            Intent(context, GateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_PKG, pkg)
    }
}

/** 题库为空时的内置兜底题：不考试，只逼自己想一步。 */
private val FALLBACK_STEMS = listOf(
    "现在打开它要做什么？回来第一步是什么？",
    "这一趟是为了哪件具体的事？做完怎么判断可以停？",
    "打算看多久？看完之后先做哪一件事？",
    "如果只看两分钟，你最想先确认的是什么？",
)

@Composable
private fun GateScreen(pkg: String, onHome: () -> Unit, onPass: (String) -> Unit) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    val rule = config.watch.ruleFor(pkg, config.blocked) ?: WatchRule(pkg = pkg)
    val label = remember(pkg) { AppNames.label(context, pkg) }
    val minutes = remember(pkg) { Watch.usedMinutes(pkg).takeIf { it > 0 } ?: rule.maxMinutes }

    // 题库很小（本机一个 JSON），同步读一下没关系；同一次弹出里题目不变，免得答到一半换题
    val question = remember(pkg) { BankStore.randomQuestion(context, Random) }
    val stem = remember(pkg) { question?.stem?.takeIf { it.isNotBlank() } ?: FALLBACK_STEMS.random() }

    var answer by remember(pkg) { mutableStateOf("") }
    var cooldown by remember(pkg) { mutableIntStateOf(rule.cooldownSeconds.coerceIn(0, 300)) }
    var busy by remember(pkg) { mutableStateOf(false) }
    var feedback by remember(pkg) { mutableStateOf<String?>(null) }
    var passed by remember(pkg) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 每秒刷新一次冷却倒计时；冷却结束前“提交”不可用
    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1_000)
            cooldown -= 1
        }
    }

    fun submit() {
        if (busy || passed || cooldown > 0) return
        val text = answer.trim()
        if (text.isBlank()) {
            feedback = "先写一句再交。"
            return
        }
        busy = true
        feedback = null
        scope.launch {
            val verdict = judge(context, stem, text)
            busy = false
            if (verdict.ok) {
                passed = true
                feedback = verdict.feedback.ifBlank { "通过。" }
                delay(900)
                onPass(pkg)
            } else {
                feedback = verdict.feedback.ifBlank { "再具体一点：接下来先做什么？" }
                answer = ""
                cooldown = rule.cooldownSeconds.coerceAtLeast(1)
            }
        }
    }

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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Seal("屏", sig.warn, size = 64.dp, filled = true)
            Column(Modifier.weight(1f)) {
                Text(if (rule.onOpen) "打开「$label」之前" else "「$label」已经连续用了 $minutes 分钟", style = MaterialTheme.typography.headlineSmall)
                Text("不是不准用，是先做一道题。", style = MaterialTheme.typography.bodyMedium, color = sig.muted)
            }
        }

        Text("规则：做完这道题再放行 ${rule.maxMinutes} 分钟。", style = MaterialTheme.typography.bodyMedium)
        // 挣来的娱乐：今天还剩多少
        val status = remember(pkg) { store.status() }
        if (config.earn.enabled && status.quotaMin > 0) {
            Hint("今天能玩 ${status.quotaMin} 分钟（底数 ${config.earn.baseMinutes} + 做事挣的），已用 ${status.playMin}。做完一步 +${config.earn.perAction}。")
        }

        Panel(accent = MaterialTheme.colorScheme.primary) {
            Text("题目", style = MaterialTheme.typography.titleSmall, color = sig.muted)
            Text(stem, style = MaterialTheme.typography.bodyLarge)
            if (passed && question != null && question.answer.isNotBlank()) {
                Hint("参考答案：${question.answer}")
            }
        }

        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            placeholder = { Text("写一句：接下来先做什么，大概多久，做完怎么停") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            enabled = !busy && !passed,
        )

        feedback?.let {
            Text(
                it,
                color = if (passed) sig.free else sig.warn,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when {
            busy -> Hint("判分中…")
            passed -> Hint("正在放行，回到刚才的应用。")
            cooldown > 0 -> Text(
                "冷却中：$cooldown 秒后才能交（先想一下）",
                style = MaterialTheme.typography.bodyMedium.merge(Mono),
                color = sig.muted,
            )
            else -> Text("可以交了。", style = MaterialTheme.typography.bodyMedium, color = sig.free)
        }

        if (!passed) {
            BigButton(
                "提交",
                onClick = { submit() },
                enabled = !busy && cooldown <= 0,
            )
        }

        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("不去了") }

        Hint("冷却 ${rule.cooldownSeconds} 秒不是罚站：先想清楚这一趟要做什么，再回去。")
    }
}

// ---------- 判分 ----------

private data class Verdict(val ok: Boolean, val feedback: String = "")

/**
 * 判分：配了 AI 就让模型判“这段回答里有没有一个具体、有效的思考或计划”（宽松）；
 * 没配、模型出错、返回看不懂 → 用本地宽松判。判分永远不阻塞放行以外的事。
 */
private suspend fun judge(context: Context, stem: String, answer: String): Verdict {
    if (!context.zk.store.config.value.ai.ready) return localVerdict(answer)
    val system = """
        你是总控的守门员。用户连续用娱乐应用超时，被弹回来做一道题，正在作答。
        题目：$stem
        只看一件事：这段回答里有没有一个具体、有效的思考或计划（不是空话，不是“我就看看”，不是求情）。
        宽松判：只要写了具体的“接下来做什么 / 为什么 / 第一步 / 大概多久”，就算通过。
        只输出两行，不要别的话：
        第一行：通过 或 不通过
        第二行：一句不超过 20 字的反馈
    """.trimIndent()
    val client = ChatClient({ context.zk.store.config.value.ai })
    return when (val r = client.chat(system, listOf(ChatMsg(ChatMsg.USER, answer.take(600))), emptyList<ToolSpec>())) {
        is ApiResult.Ok -> {
            val v = parseVerdict(r.value.content)
            if (v == null) {
                // 模型没按格式回：按宽松本地判，别把人挡死
                localVerdict(answer)
            } else {
                v
            }
        }
        is ApiResult.Err -> localVerdict(answer)
    }
}

/** 解析模型两行回复；看不懂返回 null（交给本地宽松判）。 */
private fun parseVerdict(text: String): Verdict? {
    val lines = text.trim().lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return null
    val head = lines.first().take(60)
    val tail = lines.drop(1).joinToString(" ").trim().take(80)
    val no = head.contains("不通过") || head.contains("未通过") || head.contains("不行") ||
        head.contains("不合格") || head.contains("无效") || head.startsWith("否") ||
        head.equals("no", true) || head.startsWith("no ", true)
    val yes = head.contains("通过") || head.contains("合格") || head.contains("有效") ||
        head.contains("可以") || head.startsWith("是") ||
        head.equals("yes", true) || head.startsWith("yes", true) || head.contains("pass", true)
    return when {
        no -> Verdict(false, tail.ifBlank { "再具体一点：接下来先做什么？" })
        yes -> Verdict(true, tail.ifBlank { "记下了。" })
        else -> null
    }
}

/**
 * 本地宽松判（没配 AI / 模型失败时用）：
 * 去掉空白后至少 12 个字，并且出现“先 / 然后 / 下一步 / 分钟 / 做完 / 停”这类词里的任意一个就算过。
 * 标准故意很松：要的是“停下来想了一步”，不是考试。
 */
private val LOCAL_MARKERS = listOf(
    "先", "然后", "接着", "下一步", "第一步", "回来", "打开", "看一下", "看多久",
    "写", "做", "查", "搜", "回复", "分钟", "秒", "做完", "之后", "计时", "定个", "设个", "停",
)

private fun localVerdict(answer: String): Verdict {
    val text = answer.replace(Regex("\\s+"), "")
    if (text.length < 12) {
        return Verdict(false, "太短了（${text.length} 字）：具体说一句接下来先做什么，至少 12 个字。")
    }
    val hit = LOCAL_MARKERS.any { it in text }
    if (!hit) {
        return Verdict(false, "太笼统了：加上“先做什么 / 大概多久 / 做完怎么停”。")
    }
    return Verdict(true, "记下了：这一趟按你说的做。")
}
