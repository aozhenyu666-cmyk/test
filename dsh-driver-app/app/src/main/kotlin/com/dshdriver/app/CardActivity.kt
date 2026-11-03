package com.dshdriver.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
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
import android.widget.ScrollView
import android.widget.TextView
import com.dshdriver.core.Card
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * 断点卡 / 强提醒页，全屏盖在你切去的那个应用上。
 *
 * 解除它只有三种方式（这就是"高权限"）：
 *   1. 给一条有效微反馈（点 A/B、念两个词、写一句依据）→ 自动回到 Live；
 *   2. 声明休息并选恢复时间；
 *   3. 紧急结束（来电、急事）——永远可用，但记为主动结束。
 * level ≥ 1 时返回键无效。level 2 时震动 + 语音把题念出来。
 * "页面创建成功 ≠ 你看见了"：窗口真的拿到焦点才回报 displayed。
 */
class CardActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_A = "a"
        private const val EXTRA_B = "b"
        private const val EXTRA_LEVEL = "level"
        private const val EXTRA_REFLEX = "reflex"
        private const val BARE = "bare"
        private var current: WeakReference<CardActivity>? = null

        fun start(context: Context, card: Card, level: Int, reflex: Boolean): Boolean {
            val i = Intent(context, CardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_ID, card.id).putExtra(EXTRA_KIND, card.kind).putExtra(EXTRA_TEXT, card.text)
                .putExtra(EXTRA_A, card.options?.a).putExtra(EXTRA_B, card.options?.b)
                .putExtra(EXTRA_LEVEL, level).putExtra(EXTRA_REFLEX, reflex)
            return runCatching { context.startActivity(i) }.isSuccess.also { ok ->
                if (!ok) Engine.surface(card.id, "not_shown", "page")
            }
        }

        /** 还没有任何卡时被切走（开场题还没出来）：只给"回到 Live"。 */
        fun startBare(context: Context) {
            val i = Intent(context, CardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ID, BARE).putExtra(EXTRA_KIND, "open").putExtra(EXTRA_TEXT, "锁定中。先回到 Live。").putExtra(EXTRA_LEVEL, 1)
            runCatching { context.startActivity(i) }
        }

        fun withdraw() { current?.get()?.finish() }

        fun withdrawIf(cardId: String) { current?.get()?.takeIf { it.cardId == cardId }?.finish() }
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
        val text = i.getStringExtra(EXTRA_TEXT).orEmpty()
        val a = i.getStringExtra(EXTRA_A)
        val b = i.getStringExtra(EXTRA_B)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
            setBackgroundColor(if (level >= 2) 0xFF2B1B1B.toInt() else 0xFF1B2230.toInt())
        }
        root.addView(TextView(this).apply {
            this.text = when { level >= 2 -> "强提醒 · 还在锁定中"; i.getBooleanExtra(EXTRA_REFLEX, false) -> "你切走了"; else -> "驾驶员" }
            setTextColor(0xFFB8C2D9.toInt()); textSize = 14f
        })
        root.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt()); textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (12 * dp).toInt(), 0, (24 * dp).toInt())
        })
        if (a != null && b != null) {
            root.addView(button("A  $a", 18f) { answer(null, "A", "tap") })
            root.addView(button("B  $b", 18f) { answer(null, "B", "tap") })
        }
        if (cardId != BARE) {
            val input = EditText(this).apply {
                hint = "或者打一句 / 两个词"
                setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF7F8AA3.toInt())
            }
            root.addView(input)
            root.addView(button("发送", 16f) { input.text?.toString()?.takeIf { it.isNotBlank() }?.let { answer(it, null, "text") } })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, 0)
            addView(button("回到 Live", 13f) { Engine.relaunchAnchor(this@CardActivity) })
            addView(button("休息…", 13f) { askPause() })
            addView(button("紧急结束", 13f) { confirmStop() })
        })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })

        if (level >= 2) {
            vibrate()
            speak(text + if (a != null && b != null) "。A，$a。B，$b。" else "")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !shownReported && cardId.isNotEmpty() && cardId != BARE) {
            shownReported = true
            Engine.surface(cardId, "displayed", "page")
        }
    }

    private fun answer(text: String?, choice: String?, channel: String) {
        Engine.reply(cardId, text, choice, channel)
        Engine.relaunchAnchor(this)
        finish()
    }

    private fun askPause() {
        val minutes = intArrayOf(3, 5, 10, 20)
        AlertDialog.Builder(this).setTitle("休息多久？到点会把你拉回这个断点")
            .setItems(minutes.map { "$it 分钟" }.toTypedArray()) { _, i -> Engine.pause(minutes[i]); finish() }
            .show()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this).setTitle("紧急结束这次锁定？").setMessage("会记为主动结束，不算完成。")
            .setPositiveButton("结束") { _, _ -> Engine.stop("emergency"); finish() }
            .setNegativeButton("不结束", null).show()
    }

    // level ≥ 1：返回键不放行
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && level >= 1) true else super.onKeyDown(keyCode, event)

    @Deprecated("Activity API")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (level < 1) super.onBackPressed()
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)) }
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
        Engine.selfSpeaking(true)
        t.speak(s, TextToSpeech.QUEUE_FLUSH, null, cardId)
        pendingSpeech = null
    }

    override fun onDestroy() {
        tts?.shutdown()
        Engine.selfSpeaking(false)
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun button(label: String, size: Float, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = size; isAllCaps = false; setOnClickListener { onClick() }
    }
}
