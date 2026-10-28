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
    private var exposureRecorded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exposureRecorded = savedInstanceState?.getBoolean("exposureRecorded") ?: false
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
                        delay(1_800)
                        returnToApp()
                    }
                }
                GateScreen(
                    s = s,
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
        exposureRecorded = false
        startFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 实际页面负责记显示；启动请求可能因窗口过渡被取消，不能据此丢掉真实显示。
        if (!exposureRecorded && intent.getStringExtra(EXTRA_PACKAGE) != null) {
            val app = application as com.yishou.app.YishouApp
            app.stats.event(if (intent.getStringExtra(EXTRA_GROUP) == WINDOW_GROUP)
                com.yishou.app.stats.DayStats.WINDOW_BLOCK else com.yishou.app.stats.DayStats.GATE_SHOWN)
            exposureRecorded = true
        }
        isShowing = true
        PagePrompt.acknowledge()
        vm.refreshPartnerClose()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("exposureRecorded", exposureRecorded)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        isShowing = false
        vm.cancelWait()
        super.onPause()
    }

    private fun startFromIntent(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val group = intent.getStringExtra(EXTRA_GROUP).orEmpty()
        val label = pkg?.let { appLabel(it) } ?: if (group == "早上") "早上" else "测试"
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
        fun morningIntent(context: Context): Intent = Intent(context, GateActivity::class.java)
            .putExtra(EXTRA_GROUP, "早上").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun testIntent(context: Context): Intent =
            Intent(context, GateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun GateScreen(s: GateState, vm: GateViewModel, onReturn: () -> Unit, onHome: () -> Unit, onOpenWindow: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (s.isTest) "思考页（测试，不放行）" else "打开「${s.appLabel}」之前", style = MaterialTheme.typography.titleMedium)
            when {
                s.loading -> CircularProgressIndicator()
                s.partnerPending -> {
                    Text("上次伴读还没收线")
                    Button(onClick = { context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "partner")) }) { Text("回一手收线") }
                }
                s.passMinutes != null -> {
                    Text("放行 ${s.passMinutes} 分钟")
                    Button(onClick = onReturn) { Text("回到「${s.appLabel}」") }
                }
                s.inWindow -> {
                    Text("陪练窗口进行中，先回到陪练")
                    Button(onClick = onOpenWindow) { Text("回到陪练") }
                }
                s.followUp != null -> {
                    Text("上次说“${s.followUp}”，做了吗？")
                    Row { Button(onClick = { vm.answerFollowUp(true) }) { Text("做了") }; TextButton(onClick = { vm.answerFollowUp(false) }) { Text("没做") } }
                }
                s.waiting -> {
                    val secs = (s.waitRemainingMs + 999) / 1000
                    Text("还需 ${secs / 60} 分 ${secs % 60} 秒；离开页面重新计时")
                    if (s.waitRemainingMs == 0L) Button(onClick = vm::passAfterWait, enabled = !s.isTest && !s.busy) { Text("放行 5 分钟") }
                    OutlinedButton(onClick = vm::cancelWait) { Text("取消等待") }
                }
                vm.recall != null -> com.yishou.app.ui.RecallView(vm.recall!!, vm.recallSource(), s.busy, vm.recallFeedback, vm::submitRecall, vm::nextRecall, vm.recallingEcho)
                vm.ice != null -> IceView(vm.ice!!, vm::finishIce)
                s.mode == com.yishou.app.reflex.Main.WAIT -> {
                    Text("暂时没有能继续的题，可以等一会儿或离开手机")
                    Button(onClick = vm::startWait, enabled = !s.busy) { Text("等 10 分钟，换 5 分钟") }
                    OutlinedButton(onClick = vm::retryUnavailable, enabled = !s.busy) { Text("重新找题") }
                }
                else -> {
                    if (s.mode == com.yishou.app.reflex.Main.STRAND) vm.work.strand?.let { com.yishou.app.ui.StrandHeading(it) }
                    ProbeGateView(s, vm)
                    val rev = vm.review
                    if (rev?.verdict?.passed == true && rev.pullBack) {
                        Text("连上了，接着想这件事")
                        Button(onClick = vm::retry, enabled = !s.busy) { Text("接着来") }
                    } else if (rev?.verdict?.passed == true) {
                        if (!vm.triggerSaved) com.yishou.app.ui.TriggerEditor(rev.q, s.busy, vm::saveTrigger)
                        else {
                            Text("触发器已存好")
                            VoiceTextField(s.exit, vm::onExitChange, "看完做什么（可不填）", Modifier.fillMaxWidth())
                            Button(onClick = vm::pass, enabled = !s.busy && !s.isTest) { Text("通过，放行 15 分钟") }
                        }
                    }
                }
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(if (s.isTest) "关闭" else "先回桌面") }
        }
    }
}

@Composable
private fun IceView(ice: com.yishou.app.reflex.Ice, onDone: () -> Unit) {
    var left by androidx.compose.runtime.remember(ice) { androidx.compose.runtime.mutableStateOf(5) }
    LaunchedEffect(ice) { while (left > 0) { delay(1_000); left-- }; onDone() }
    Text("直觉看一眼，哪个更像鬼扯？（$left 秒）")
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(72.dp)) { Text(ice.left) }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(72.dp)) { Text(ice.right) }
}
