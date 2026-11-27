package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Android 系统分享（ACTION_SEND text/plain）透明接收器：
 * 在 Gemini App 或任何网页/笔记中点「分享 → 喂进大肥鱼知识海」，
 * 0.5 秒原生直通 V6 /v6 pool_ingest，绝不拉起完整 Flutter 界面打断当下语境。
 */
class ShareFeedActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var done = false

    private val timeoutFinish = Runnable {
        if (!done && !isFinishing) {
            done = true
            Toast.makeText(applicationContext, "已转入大肥鱼后台入池", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rawText = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val payload = when {
            rawText.isNotEmpty() -> rawText
            subject.isNotEmpty() -> subject
            else -> ""
        }
        if (payload.isBlank()) {
            Toast.makeText(applicationContext, "未识别到可喂鱼的文字内容", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        main.postDelayed(timeoutFinish, 4_000L)
        ProactiveRuntime.ensure(applicationContext)
        ProactiveRuntime.callV6Op(
            mapOf(
                "action" to "pool_ingest",
                "source" to "gemini",
                "text" to payload.take(2400),
            ),
            refreshPlanAfter = true,
        ) { res ->
            main.post {
                main.removeCallbacks(timeoutFinish)
                if (done || isFinishing || isDestroyed) return@post
                done = true
                val kind = res?.get("kind") as? String
                val msg = when {
                    kind == "contract" -> {
                        val c = res?.get("contract") as? Map<*, *>
                        val target = (c?.get("target") as? String).orEmpty().ifBlank { "今日契约" }
                        "📜 已自动签收【今日契约】并上锁：$target"
                    }
                    res?.get("ok") == true -> {
                        val added = (res["addedCount"] as? Number)?.toInt() ?: 1
                        "🐟 已切片喂入知识海 +$added 条"
                    }
                    else -> "分享内容已在知识海中或过短（无需重复入库）"
                }
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(timeoutFinish)
        super.onDestroy()
    }
}
