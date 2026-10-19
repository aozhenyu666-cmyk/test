package com.yishou.app.gate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.MainActivity
import com.yishou.app.round.AnswerRules
import com.yishou.app.system.Attachment
import com.yishou.app.ui.AnswerComposer
import com.yishou.app.ui.CoachBubble
import com.yishou.app.ui.ElapsedClock
import com.yishou.app.ui.FaceChip
import com.yishou.app.ui.ResumeCard
import com.yishou.app.ui.TypingBubble
import com.yishou.app.ui.UserBubble
import com.yishou.app.ui.VerdictLine
import com.yishou.app.ui.theme.YishouTheme
import com.yishou.app.ui.theme.StoneMark
import com.yishou.app.ui.CardRoundView
import com.yishou.app.ui.MatrixLoader
import com.yishou.app.ui.VoiceTextField
import com.yishou.app.ui.BrakeButton
import com.yishou.app.ui.EchoCard
import com.yishou.app.ui.EndgameBoard
import com.yishou.app.system.Variant
import com.yishou.app.card.SessionState
import com.yishou.app.window.WindowActivity
import kotlinx.coroutines.delay

/**
 * 入口思考页。由无障碍服务在用户打开关注的应用时启动（本应用自己的 Activity，不用悬浮窗）。
 * 在独立的任务里运行，关闭后回到原应用；按返回键回到桌面，不会绕过思考页。
 */
class GateActivity : ComponentActivity() {

    private val vm: GateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startFromIntent(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (vm.state.value.isTest) finish() else goHome()
            }
        })
        setContent {
            YishouTheme {
                val s by vm.state.collectAsStateWithLifecycle()
                val c by vm.card.collectAsStateWithLifecycle()
                val ln by vm.line.collectAsStateWithLifecycle()
                LaunchedEffect(s.passMinutes) {
                    if (s.passMinutes != null) {
                        delay(1_800)
                        returnToApp()
                    }
                }
                GateScreen(
                    ln = ln,
                    s = s,
                    c = c,
                    vm = vm,
                    onReturn = ::returnToApp,
                    onHome = { if (s.isTest) finish() else goHome() },
                    onOpenWindow = {
                        startActivity(Intent(this, WindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finishAndRemoveTask()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
        vm.refreshPartnerClose()
    }

    override fun onPause() {
        isShowing = false
        super.onPause()
    }

    private fun startFromIntent(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val group = intent.getStringExtra(EXTRA_GROUP).orEmpty()
        val label = pkg?.let { appLabel(it) } ?: "测试"
        vm.start(pkg, group, label)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** 回到原应用。先关掉思考页，再把原应用的任务带回前台（不会重开它）。 */
    private fun returnToApp() {
        val pkg = vm.state.value.triggerPackage
        finishAndRemoveTask()
        if (pkg != null) {
            try {
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) {
                Log.w(TAG, "无法回到 $pkg", e)
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finishAndRemoveTask()
    }

    companion object {
        private const val TAG = "GateActivity"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_GROUP = "group"

        /** 开局规则拦下的应用用这个组名打开思考页：只显示“回到陪练” */
        const val WINDOW_GROUP = "__window__"

        /** 思考页正在前台。无障碍服务据此避免重复打开。 */
        @Volatile
        var isShowing = false
            private set

        fun intent(context: Context, pkg: String, group: String): Intent =
            Intent(context, GateActivity::class.java)
                .putExtra(EXTRA_PACKAGE, pkg)
                .putExtra(EXTRA_GROUP, group)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** 权限页“测试思考页”：走一遍流程，但不发放行。 */
        fun testIntent(context: Context): Intent =
            Intent(context, GateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * 思考页：先答上次说好的事，再做一张题卡，最后说一句看完做什么。
 * 简洁、一步一屏，不跟你聊天。
 */
@Composable
private fun GateScreen(
    ln: com.yishou.app.line.LineState,
    s: GateState,
    c: SessionState,
    vm: GateViewModel,
    onReturn: () -> Unit,
    onHome: () -> Unit,
    onOpenWindow: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoneMark(22.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (s.isTest) "思考页（测试，不放行）" else "打开「${s.appLabel}」之前",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            val passed = s.passMinutes
            val needsDelta = c.done && vm.needsDelta
            val echo = s.echo
            when {
                s.loading -> MatrixLoader("准备一张题")
                s.partnerPending -> {
                    Text("上次伴读还没收线", style = MaterialTheme.typography.headlineSmall)
                    Text("留一句聊出了什么，再说下一步做什么。")
                    Button(onClick = {
                        context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "partner"))
                    }, modifier = Modifier.fillMaxWidth()) { Text("回一手收线") }
                    OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("先回桌面") }
                }
                passed != null -> {
                    Text("放行 $passed 分钟", style = MaterialTheme.typography.headlineMedium)
                    Text("说好看完：${s.exit}", style = MaterialTheme.typography.bodyLarge)
                    Text("到点会提醒你。下次打开先问你做了没有。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onReturn, modifier = Modifier.fillMaxWidth()) { Text("回到「${s.appLabel}」") }
                }
                s.inWindow -> {
                    Text(
                        if (s.group == GateActivity.WINDOW_GROUP) "陪练窗口进行中，「${s.appLabel}」不在这一局允许的应用里。想歇一下就在陪练里点“先停”。"
                        else "陪练窗口进行中。窗口里不放行，先回到陪练。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onOpenWindow, modifier = Modifier.fillMaxWidth()) { Text("回到陪练") }
                    OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("回到桌面") }
                }
                echo != null -> EchoCard(echo, onConfirm = vm::confirmEcho)
                s.followUp != null -> {
                    Text("上次你说看完要", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("「${s.followUp}」", style = MaterialTheme.typography.headlineSmall)
                    Text("做了吗？", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.answerFollowUp(true) }) { Text("做了") }
                        OutlinedButton(onClick = { vm.answerFollowUp(false) }) { Text("没做") }
                    }
                }
                s.endgame != null -> {
                    EndgameBoard(s.endgame, shoot = false, onFlip = { hit, _ -> vm.endgameFlipped(hit) }, onLaw = vm::saveLaw)
                    if (s.endgameDone) {
                        if (s.isTest) OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                        else PassBlock(s, vm, needsDelta = false)
                    }
                    OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("不打开了") }
                }
                s.useLine -> {
                    LineGate(ln, vm)
                    if (ln.caught > 0 || (ln.parked && !Variant.chalu)) {
                        if (s.isTest) {
                            OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                        } else {
                            PassBlock(s, vm, needsDelta)
                        }
                    }
                    OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("不打开了") }
                }
                !s.gateCard -> {
                    if (s.isTest) {
                        Text("现在是只打卡模式：不出题，写一句看完做什么就放行。", style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                    } else {
                        PassBlock(s, vm, needsDelta)
                        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("不打开了") }
                    }
                }
                c.error != null && c.round == null -> {
                    Text("这次出不了题（${c.error}），只打卡。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.retry() }) { Text("再试一次出题") }
                    if (s.isTest) {
                        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                    } else {
                        PassBlock(s, vm, needsDelta)
                        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("不打开了") }
                    }
                }
                c.round == null -> MatrixLoader("准备一张题")
                else -> {
                    CardRoundView(c, vm.actions)
                    if (c.done) {
                        if (s.isTest) {
                            OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                        } else {
                            PassBlock(s, vm, needsDelta)
                            OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("不打开了") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExitField(s: GateState, vm: GateViewModel) {
    VoiceTextField(s.exit, vm::onExitChange, if (Variant.chalu) "例如：回去点开资料分析第 3 题" else "例如：回去做两道资料分析", Modifier.fillMaxWidth())
}

/**
 * 放行那一块：说好看完做什么（岔路问“回来第一步点哪儿”，放行后挂在通知栏），
 * 岔路里答错了先写差值；放行按钮停 2 秒再落。
 */
@Composable
private fun PassBlock(s: GateState, vm: GateViewModel, needsDelta: Boolean) {
    if (needsDelta) {
        Text("这张错了。写下差值再走：", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        VoiceTextField(s.delta, vm::onDeltaChange, com.yishou.app.line.Echoes.DELTA_HINT, Modifier.fillMaxWidth(), minLines = 2)
        Text("下次打开「${s.appLabel}」，开局先推这一句给你。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(if (Variant.chalu) "回来第一步点哪儿？" else "看完要做的第一件事是？", style = MaterialTheme.typography.titleMedium)
    ExitField(s, vm)
    val ready = s.exit.isNotBlank() && (!needsDelta || com.yishou.app.line.Echoes.usable(s.delta))
    BrakeButton(if (needsDelta && !com.yishou.app.line.Echoes.usable(s.delta)) "先写差值" else "说好了，放行", enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = vm::pass)
}


/** 入口的主线一手：问你在下的那一局的下一问，过了本机规则才算 */
@Composable
private fun LineGate(ln: com.yishou.app.line.LineState, vm: GateViewModel) {
    val line = ln.line ?: return
    var text by androidx.compose.runtime.saveable.rememberSaveable(ln.question, ln.moves) { androidx.compose.runtime.mutableStateOf("") }
    Text("「${line.anchor}」", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
    if (ln.caught > 0) {
        Text("接住了这一手。", style = MaterialTheme.typography.titleMedium)
        Text("断点记好了：${ln.question}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (ln.parked) {
        Text("这一手先放着，记下了。", style = MaterialTheme.typography.titleMedium)
        if (Variant.chalu) {
            Text("岔路里“先放着”不放行。换个小问题接着下，接住一手就走。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = vm::resumeLine, modifier = Modifier.fillMaxWidth()) { Text("接着下") }
        }
        return
    }
    if (ln.say.isNotBlank() && ln.say != ln.question) Text(ln.say.substringBefore(ln.question).trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
    Text(ln.question, style = MaterialTheme.typography.headlineSmall)
    val normal = ln.ask == com.yishou.app.line.Ask.NORMAL || ln.ask == com.yishou.app.line.Ask.ANCHOR
    if (Variant.chalu) Text(com.yishou.app.ui.beatLabel(ln), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    VoiceTextField(text, { text = it }, if (Variant.chalu && normal) "只有当＿＿，才＿＿" else "先说结论，再说为什么", Modifier.fillMaxWidth(), minLines = 2)
    val rules = when {
        !normal -> com.yishou.app.line.TalkRules.SMALL
        Variant.chalu -> com.yishou.app.line.TalkRules.CUT
        else -> com.yishou.app.line.TalkRules.of(ln.level)
    }
    if (text.isNotBlank()) com.yishou.app.ui.TalkMeter(text, rules)
    if (ln.busy) MatrixLoader("在看这一手")
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.move(text) }, enabled = text.isNotBlank() && !ln.busy, modifier = Modifier.weight(1f).height(50.dp)) { Text("下这一手") }
        OutlinedButton(onClick = { vm.move("想不出") }, enabled = !ln.busy, modifier = Modifier.height(50.dp)) { Text("想不出") }
    }
}
