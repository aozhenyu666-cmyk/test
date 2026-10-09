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
import com.yishou.app.ui.ResumeCard
import com.yishou.app.ui.TypingBubble
import com.yishou.app.ui.UserBubble
import com.yishou.app.ui.VerdictLine
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
                val attachment by vm.attachment.state.collectAsStateWithLifecycle()
                val startersPair by vm.starters.collectAsStateWithLifecycle()
                GateScreen(
                    s = s,
                    starters = startersPair?.takeIf { it.first == s.coachMove }?.second.orEmpty(),
                    attachment = attachment,
                    onAttach = vm::attach,
                    onClearAttachment = vm.attachment::clear,
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

@Composable
private fun GateScreen(
    s: GateState,
    starters: List<String>,
    attachment: Attachment?,
    onAttach: (Uri) -> Unit,
    onClearAttachment: () -> Unit,
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
                Text(
                    if (s.group == GateActivity.WINDOW_GROUP) {
                        "陪练窗口进行中，「${s.appLabel}」不在这一局允许的应用里。想歇一下就在陪练里点“先停”。"
                    } else {
                        "陪练窗口进行中。窗口里不放行，先回到陪练。"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onOpenWindow, modifier = Modifier.fillMaxWidth()) { Text("回到陪练") }
                OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("回到桌面") }
                return@Column
            }

            Text(task.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            s.resumeFrom?.let { last ->
                ResumeCard(
                    gapMinutes = (System.currentTimeMillis() - last.createdAt) / 60_000,
                    lastAnswer = last.userAnswer,
                    stuck = s.breakpoint?.stuck,
                )
            }

            s.lastRound?.let { r ->
                UserBubble(r.userAnswer)
                VerdictLine(r)
            }

            val passMinutes = s.passMinutes
            if (passMinutes != null) {
                Text("放行 $passMinutes 分钟。马上回到「${s.appLabel}」。", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onReturn, modifier = Modifier.fillMaxWidth()) { Text("现在回去") }
                return@Column
            }

            when {
                s.moveLoading -> TypingBubble()
                s.coachMove != null -> {
                    s.moveError?.let {
                        Text("陪练暂时出不了题（$it），先回答下面这个问题：", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    CoachBubble(s.coachMove) { s.breakpoint?.updatedAt?.let { ElapsedClock(it) } }
                }
            }

            if (s.coachMove != null) {
                AnswerComposer(
                    starters = starters,
                    answer = s.answer,
                    onAnswerChange = onAnswerChange,
                    attachment = attachment,
                    onAttach = onAttach,
                    onClearAttachment = onClearAttachment,
                    submitting = s.submitting,
                    onSubmit = onSubmit,
                    hint = s.hint,
                    error = s.judgeError,
                )
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
