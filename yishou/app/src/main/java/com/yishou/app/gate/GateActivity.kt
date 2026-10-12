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
                val p by vm.practice.state.collectAsStateWithLifecycle()
                LaunchedEffect(s.passMinutes) {
                    if (s.passMinutes != null) {
                        delay(1_800)
                        returnToApp()
                    }
                }
                GateScreen(
                    s = s,
                    p = p,
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
    s: GateState,
    p: com.yishou.app.training.PracticeState,
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
            when {
                s.loading -> MatrixLoader("接续当前任务")
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
                s.followUp != null -> {
                    Text("上次你说看完要", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("「${s.followUp}」", style = MaterialTheme.typography.headlineSmall)
                    Text("做了吗？", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.answerFollowUp(true) }) { Text("做了") }
                        OutlinedButton(onClick = { vm.answerFollowUp(false) }) { Text("没做") }
                    }
                }
                else -> {
                    com.yishou.app.ui.PracticePanel(vm.practice, showHistory = false)
                    if (p.task == null) {
                        Button(onClick = { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("去主页选择任务与材料") }
                    }
                    val effective = p.last?.let { it.effective && it.judged && it.createdAt >= s.startedAt } == true
                    if (effective) {
                        if (s.isTest) OutlinedButton(onClick = onHome) { Text("关闭测试") }
                        else {
                            Text("看完要做的第一件事是？", style = MaterialTheme.typography.titleMedium)
                            ExitField(s, vm)
                            Button(onClick = vm::pass, enabled = s.exit.isNotBlank() && !p.busy, modifier = Modifier.fillMaxWidth()) { Text("说好了，放行") }
                        }
                    } else if (p.error != null && !s.isTest && p.task != null) {
                        Text("接口暂时无法判定。可以保存原话并离线放行 ${s.offlineMinutes} 分钟，今天还剩 ${s.offlineLeft} 次。")
                        ExitField(s, vm)
                        Button(onClick = vm::passOffline, enabled = s.exit.isNotBlank() && (p.answer.isNotBlank() || vm.practice.attachment.readyText != null) && s.offlineLeft > 0 && !p.busy) { Text("保存原话并离线放行") }
                    }
                    OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(if (s.isTest) "关闭测试" else "不打开了") }
                }
            }
        }
    }
}

@Composable
private fun ExitField(s: GateState, vm: GateViewModel) {
    VoiceTextField(s.exit, vm::onExitChange, "例如：回去继续当前任务，或把菜收好", Modifier.fillMaxWidth())
}
