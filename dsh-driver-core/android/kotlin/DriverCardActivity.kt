package io.github.ccch1mneyyy.dsh_app.loop.driver

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.loop.core.VisibleSurface
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * 断点卡 / 强提醒页。全屏盖在你切去的那个应用上面。
 *
 * 解除它只有三种方式（这就是"高权限"）：
 *   1. 给一条有效微反馈（点 A/B、念两个词、写一句依据）→ 自动回到 Live；
 *   2. 声明休息并选恢复时间；
 *   3. 紧急结束（来电、急事）——永远可用，但记为主动结束。
 * level ≥ 1 时返回键无效。level 2 时震动 + 语音把题念出来。
 *
 * "页面创建成功 ≠ 你看见了"：只有窗口真的拿到焦点才回报 displayed（和 fork 里拦截页同一条规矩）。
 */
class DriverCardActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_A = "a"
        private const val EXTRA_B = "b"
        private const val EXTRA_LEVEL = "level"
        private const val EXTRA_REFLEX = "reflex"
        private var current: WeakReference<DriverCardActivity>? = null

        fun start(context: Context, card: DriverRuntime.Card, reflex: Boolean): Boolean {
            val i = Intent(context, DriverCardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_ID, card.id).putExtra(EXTRA_KIND, card.kind).putExtra(EXTRA_TEXT, card.text)
                .putExtra(EXTRA_A, card.optionA).putExtra(EXTRA_B, card.optionB)
                .putExtra(EXTRA_LEVEL, card.level).putExtra(EXTRA_REFLEX, reflex)
            return runCatching { context.startActivity(i) }.isSuccess.also { ok ->
                if (!ok) DriverRuntime.onCardNotShown(card.id, "page")
            }
        }

        /** 还没有任何卡时被切走（开场题还没回来）：只给"回到 Live"。 */
        fun startBare(context: Context, anchor: String?) =
            start(context, DriverRuntime.Card("bare", "open", "锁定中。先回到 Live。", null, null, 1), reflex = true)

        fun withdraw() { current?.get()?.finish() }
    }

    private var cardId = ""
    private var level = 0
    private var shownReported = false
    private var tts: TextToSpeech? = null
    private var pendingSpeech: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(i: Intent) {
        cardId = i.getStringExtra(EXTRA_ID).orEmpty()
        level = i.getIntExtra(EXTRA_LEVEL, 1)
        shownReported = false
        VisibleSurface.claim(DriverRuntime.OWNER_DRIVER, cardId)
        val text = i.getStringExtra(EXTRA_TEXT).orEmpty()
        val a = i.getStringExtra(EXTRA_A)
        val b = i.getStringExtra(EXTRA_B)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 96, 56, 96)
            setBackgroundColor(if (level >= 2) 0xFF2B1B1B.toInt() else 0xFF1B2230.toInt())
        }
        root.addView(TextView(this).apply {
            this.text = when { level >= 2 -> "强提醒 · 还在锁定中"; i.getBooleanExtra(EXTRA_REFLEX, false) -> "你切走了"; else -> "驾驶员" }
            setTextColor(0xFFB8C2D9.toInt()); textSize = 14f
        })
        root.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt()); textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 24, 0, 40)
        })
        if (a != null && b != null) {
            root.addView(choice("A  $a") { answer(null, "A", "tap") })
            root.addView(choice("B  $b") { answer(null, "B", "tap") })
        }
        if (cardId != "bare") {
            val input = EditText(this).apply { hint = "或者打一句 / 两个词"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF7F8AA3.toInt()) }
            root.addView(input)
            root.addView(choice("发送") { input.text?.toString()?.takeIf { it.isNotBlank() }?.let { answer(it, null, "text") } })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 40, 0, 0)
            addView(small("回到 Live") { DriverRuntime.relaunchAnchor(this@DriverCardActivity) })
            addView(small("休息…") { askPause() })
            addView(small("紧急结束") { confirmStop() })
        })
        setContentView(root)

        if (level >= 2) {
            vibrate()
            speak(text + (if (a != null && b != null) "。A，$a。B，$b。" else ""))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !shownReported && cardId.isNotEmpty() && cardId != "bare") {
            shownReported = true
            DriverRuntime.onCardShown(cardId, "page")
        }
    }

    private fun answer(text: String?, choice: String?, channel: String) {
        DriverRuntime.onReply(cardId, text, choice, channel)
        DriverRuntime.relaunchAnchor(this)
        finish()
    }

    private fun askPause() {
        val minutes = intArrayOf(3, 5, 10, 20)
        AlertDialog.Builder(this).setTitle("休息多久？到点会把你拉回这个断点")
            .setItems(minutes.map { "$it 分钟" }.toTypedArray()) { _, i -> DriverRuntime.onPause(minutes[i]); finish() }
            .show()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this).setTitle("紧急结束这次锁定？").setMessage("会记为主动结束，不算完成。")
            .setPositiveButton("结束") { _, _ -> DriverRuntime.onEmergencyStop(); finish() }
            .setNegativeButton("不结束", null).show()
    }

    // level ≥ 1：返回键不放行
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && level >= 1) true else super.onKeyDown(keyCode, event)

    @Deprecated("Activity API")
    override fun onBackPressed() { if (level < 1) @Suppress("DEPRECATION") super.onBackPressed() }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }

    private fun speak(s: String) {
        pendingSpeech = s
        if (tts == null) tts = TextToSpeech(this, this) else onInit(TextToSpeech.SUCCESS)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        val s = pendingSpeech ?: return
        if (status != TextToSpeech.SUCCESS) return
        t.language = Locale.SIMPLIFIED_CHINESE
        DriverRuntime.selfSpeaking(true)
        t.speak(s, TextToSpeech.QUEUE_FLUSH, null, cardId)
        pendingSpeech = null
    }

    override fun onDestroy() {
        tts?.shutdown()
        DriverRuntime.selfSpeaking(false)
        VisibleSurface.release(DriverRuntime.OWNER_DRIVER, cardId)
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun choice(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun small(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 13f; isAllCaps = false; setOnClickListener { onClick() }
    }
}
