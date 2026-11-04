package com.dshdriver.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var ui: Ui

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
        val reflex = i.getBooleanExtra(EXTRA_REFLEX, false)
        val text = i.getStringExtra(EXTRA_TEXT).orEmpty()
        val a = i.getStringExtra(EXTRA_A)
        val b = i.getStringExtra(EXTRA_B)

        val p = Palette.dark
        ui = Ui(this, p)
        window.statusBarColor = p.bg

        // 内容整体居中：全屏页上只有一句话，贴顶会显得空
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 靠上一点：拦截 / 提醒页是要立刻读的，居中的话上半屏会空掉
            setPadding(ui.px(22), ui.px(88), ui.px(22), ui.px(28))
            setBackgroundColor(p.bg)
        }
        ui.reset(page)

        ui.kicker(
            when {
                level >= 2 -> "强提醒 · 还在陪练里"
                reflex -> "你切走了"
                else -> "驾驶员"
            },
            color = if (level >= 2) p.warn else p.textTertiary,
        )
        ui.headline(text, 24f)

        if (a != null && b != null) {
            ui.option("A   $a") { answer(null, "A", "tap") }
            ui.option("B   $b") { answer(null, "B", "tap") }
        }

        if (cardId != BARE) {
            ui.spacer(12f)
            val input = ui.field("或者打一句 / 两个词", multiline = true, lines = 2)
            ui.primary("发送") {
                input.text?.toString()?.takeIf { it.isNotBlank() }?.let { answer(it, null, "text") }
            }
        } else {
            ui.primary("回到 Live") { Engine.relaunchAnchor(this) }
        }

        ui.divider()
        ui.row {
            ui.micro("回到 Live") { Engine.relaunchAnchor(this) }
            ui.micro("休息…") { askPause() }
            ui.micro("紧急结束", Tone.BAD) { confirmStop() }
        }

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })

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
}
