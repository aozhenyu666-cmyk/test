package com.yishou.app.scroll

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.yishou.app.YishouApp
import com.yishou.app.outside.AiApps
import com.yishou.app.system.ExternalGrace
import com.yishou.app.ui.Launch

/**
 * 刷着练的“跳板”：没有界面，做完一件事就关掉。
 * - 开始：弹系统的看屏授权，同意后开 [ScrollService]，再回到你原来的应用。
 * - 考我一下：从通知、快捷开关点进来。点的是页面，系统会顺手收起通知栏，底下的抖音露出来，服务再截。
 * - 接着聊：把这一问发进 Gemini / ChatGPT。
 */
class ScrollActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            ScrollService.start(this, r.resultCode, data)
            Toast.makeText(this, "刷着练开了。想练就下拉通知点“考我一下”。", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "没有同意看屏，刷着练没开。", Toast.LENGTH_SHORT).show()
        }
        back()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        when (intent.action) {
            ACTION_ASK -> {
                if (ScrollService.running.value) {
                    // 页面关掉、通知栏收起之后再截
                    ScrollService.ask(this, ASK_DELAY_MS)
                    quietFinish()
                } else {
                    begin()
                }
            }
            ACTION_DEEP -> {
                val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
                val app = application as YishouApp
                val apps = AiApps.parse(app.settings.app.value.bankLinks).ifEmpty { AiApps.parse(AiApps.DEFAULT) }
                val pick = apps.firstOrNull { it.name.contains("Gemini", ignoreCase = true) && Launch.installed(this, it.pkg) }
                    ?: apps.firstOrNull { Launch.installed(this, it.pkg) }
                    ?: apps.first()
                Toast.makeText(this, Launch.sendPrompt(this, pick, prompt, "这一问"), Toast.LENGTH_LONG).show()
                quietFinish()
            }
            else -> begin()
        }
    }

    private fun begin() {
        val app = application as YishouApp
        if (!app.settings.vision.value.isComplete) {
            Toast.makeText(this, "先到 设置 → 识图模型 填好接口，一手才看得懂屏幕。", Toast.LENGTH_LONG).show()
            back()
            return
        }
        if (ScrollService.running.value) {
            back()
            return
        }
        ExternalGrace.open()
        consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    /** 从思考页过来的：回到刚才要打开的应用；否则直接关掉，回到原来的地方 */
    private fun back() {
        val pkg = intent.getStringExtra(EXTRA_RETURN)
        if (pkg != null) {
            ExternalGrace.open()
            try {
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } catch (e: Exception) {
                com.yishou.app.log.RunLog.e("刷着练", "回不到 $pkg", e)
            }
        }
        quietFinish()
    }

    private fun quietFinish() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val ACTION_START = "com.yishou.app.scroll.START"
        private const val ACTION_ASK = "com.yishou.app.scroll.ASK"
        private const val ACTION_DEEP = "com.yishou.app.scroll.DEEP"
        private const val EXTRA_RETURN = "return"
        private const val EXTRA_PROMPT = "prompt"
        private const val ASK_DELAY_MS = 800L

        /** 开始刷着练；returnTo：同意后回到哪个应用 */
        fun startIntent(context: Context, returnTo: String? = null): Intent =
            Intent(context, ScrollActivity::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RETURN, returnTo)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun askIntent(context: Context): Intent =
            Intent(context, ScrollActivity::class.java).setAction(ACTION_ASK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun askPending(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 47, askIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun deepPending(context: Context, prompt: String): PendingIntent = PendingIntent.getActivity(
            context, 48,
            Intent(context, ScrollActivity::class.java).setAction(ACTION_DEEP).putExtra(EXTRA_PROMPT, prompt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
