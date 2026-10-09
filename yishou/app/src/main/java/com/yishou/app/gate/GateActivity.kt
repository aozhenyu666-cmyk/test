package com.yishou.app.gate

import android.content.Context
import android.content.Intent
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
import com.yishou.app.ui.ResultCard
import com.yishou.app.ui.theme.YishouTheme
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
                LaunchedEffect(s.passMinutes) {
                    if (s.passMinutes != null) {
                        delay(2_500)
                        returnToApp()
                    }
                }
                GateScreen(
                    s = s,
                    onAnswerChange = vm::onAnswerChange,
                    onSubmit = vm::submit,
                    onSaveOffline = vm::saveOffline,
                    onReturn = ::returnToApp,
                    onHome = { if (s.isTest) finish() else goHome() },
                    onOpenWindow = {
                        startActivity(Intent(this, WindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finishAndRemoveTask()
                    },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

@Composable
private fun GateScreen(
    s: GateState,
    onAnswerChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSaveOffline: () -> Unit,
    onReturn: () -> Unit,
    onHome: () -> Unit,
    onOpenWindow: () -> Unit,
    onOpenApp: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (s.isTest) "思考页（测试，不放行）" else "打开「${s.appLabel}」之前，先应一手",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            if (s.loading) {
                CircularProgressIndicator()
                return@Column
            }

            val task = s.task
            if (task == null) {
                Text("还没有当前任务。先在「一手」里填一个真实的学习任务。")
                Button(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) { Text("打开一手") }
                OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("回到桌面") }
                return@Column
            }

            if (s.inWindow) {
                Text("陪练窗口进行中。窗口里不放行，先回到陪练。", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onOpenWindow, modifier = Modifier.fillMaxWidth()) { Text("回到陪练") }
                OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("回到桌面") }
                return@Column
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    s.breakpoint?.nextQuestion?.takeIf { it.isNotBlank() }?.let {
                        Text("下一问：$it", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            s.lastRound?.let { ResultCard(it) }

            val passMinutes = s.passMinutes
            if (passMinutes != null) {
                Text("放行 $passMinutes 分钟。马上回到「${s.appLabel}」。", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onReturn, modifier = Modifier.fillMaxWidth()) { Text("现在回去") }
                return@Column
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("陪练的一手", style = MaterialTheme.typography.labelLarge)
                    if (s.moveLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("陪练在想……")
                        }
                    } else {
                        s.moveError?.let {
                            Text("陪练暂时出不了题（$it），先回答下面这个问题：", color = MaterialTheme.colorScheme.error)
                        }
                        s.coachMove?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }

            if (s.coachMove != null) {
                OutlinedTextField(
                    value = s.answer,
                    onValueChange = onAnswerChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    enabled = !s.submitting,
                    label = { Text("你的一手") },
                    supportingText = {
                        Text("已写 ${s.answerChars} 字，至少 ${AnswerRules.MIN_CHARS} 字。可以用输入法的语音输入。")
                    },
                )
                s.hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                s.judgeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = onSubmit, enabled = !s.submitting, modifier = Modifier.fillMaxWidth()) {
                    if (s.submitting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("判定中……")
                    } else {
                        Text(if (s.judgeError != null) "重试判定" else "应一手")
                    }
                }
                if (s.judgeError != null && !s.isTest) {
                    if (s.offlineLeft > 0) {
                        OutlinedButton(onClick = onSaveOffline, enabled = !s.submitting, modifier = Modifier.fillMaxWidth()) {
                            Text("保存并通过（${s.offlineMinutes} 分钟，今天还剩 ${s.offlineLeft} 次）")
                        }
                    } else {
                        Text("今天的离线放行已经用完，只能重试或回到桌面。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(if (s.isTest) "关闭" else "回到桌面") }
        }
    }
}
