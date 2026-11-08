package io.github.ccch1mneyyy.dsh_app.loop.intercept

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptDrill
import io.github.ccch1mneyyy.dsh_app.loop.proactive.ProactiveRuntime
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.pet.OverlayPalette

/**
 * 濞变箰 APP 鎷︽埅椤?(round 4, M1). One page per interception, bound to the eventId the guard minted
 * when the rule matched 鈥?the same discipline as ProactivePageActivity: the page never invents an id
 * or a timestamp, and every report names the interception it belongs to.
 *
 * **onResume alone is not "the page is on screen".** The intercept ledger counts a *block* only
 * when this page proves it is really in front of the person (decor attached + shown + laid out +
 * focused), because "we showed a blocking page" is a claim about the person's attention. A page
 * started behind the lock screen, or covered at once, must not be recorded as a block.
 *
 * The five ways out, all reported to the guard (which owns the policy and the receipt):
 *
 *   training passed  -> Released("training")   the training page stayed up long enough
 *   authorization    -> Released("rule")       passage for InterceptConfig.releaseMs
 *   whitelist        -> Released("whitelist")  a standing decision, not a timer
 *   pause            -> Paused                 every target let through for a while
 *   emergency exit   -> Exited                 leave at once; never a release
 */
class InterceptPageActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var eventId: String? = null
    private var packageName: String? = null
    private var why: String? = null
    private var reported = false

    /** The block was proven on screen and reported; the page may now offer its ways out. */
    private var shown = false
    private var attempts = 0
    private val proof = Runnable { proveVisible() }

    /**
     * 训练题: the checkable door. The guard mints it from the eventId and only accepts
     * [InterceptRuntime.RELEASE_TRAINING] once every candidate was pressed in order, so this page
     * renders and forwards presses - it never decides whether training was passed.
     */
    private var drill: InterceptDrill? = null
    private var trainingButton: Button? = null
    private var drillStatus: TextView? = null
    private val drillButtons = LinkedHashMap<Int, Button>()

    /** P3: 跟大肥鱼讲道理 right here; a real reason makes the companion drop the lock. */
    private var chatLog: TextView? = null
    private var chatScroll: ScrollView? = null
    private var chatInput: EditText? = null
    private var chatSend: ImageView? = null
    private var chatStatus: TextView? = null
    private var chatting = false

    /** 计步破窗（第 8 节）：走够 150 步 + 手打 100 字违约说明，换 10 分钟放风。 */
    private var breakDialog: android.app.Dialog? = null
    private var breakStepsView: TextView? = null
    private var breakNoteView: EditText? = null
    private var breakCountView: TextView? = null
    private var breakSubmit: Button? = null
    private var stepSensor: Sensor? = null
    private var stepBaseline: Float? = null
    private var steps = 0
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val value = event?.values?.firstOrNull() ?: return
            val base = stepBaseline
            if (base == null) {
                stepBaseline = value
                steps = 0
            } else {
                steps = (value - base).toInt().coerceAtLeast(0)
            }
            refreshBreak()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** The native side's 雾蓝 palette (same one the pet bubble / AskBar use). */
    private val palette by lazy { OverlayPalette.of(this) }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The card draws its own 18 dp surface; the dialog window behind it must not paint a
        // rectangle behind those corners.
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setDimAmount(0.45f)
        setFinishOnTouchOutside(false)
        if (savedInstanceState != null) { finish(); return } // restored by the system: the interception is gone
        bind(intent)
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        // singleInstance: a newer interception replaces this one; the old eventId simply goes stale.
        unregister()
        reported = false
        shown = false
        setIntent(next)
        bind(next)
    }

    private fun bind(start: Intent) {
        val id = start.getStringExtra(EXTRA_EVENT_ID)
        val label = start.getStringExtra(EXTRA_LABEL)
        if (id == null || label == null || start.getLongExtra(EXTRA_DEADLINE, 0) <= System.currentTimeMillis()) {
            finish(); return
        }
        eventId = id
        packageName = start.getStringExtra(EXTRA_PACKAGE)
        why = start.getStringExtra(EXTRA_WHY)
        drill = InterceptRuntime.drill(id)
        live[id] = this
        setContentView(content(label))
        awaitVisible()
    }

    override fun onResume() {
        super.onResume()
        onScreenId = eventId
        if (!reported && !shown) awaitVisible()
    }

    override fun onPause() {
        if (onScreenId == eventId) onScreenId = null
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !reported && !shown) awaitVisible()
    }

    private fun awaitVisible() {
        attempts = 0
        main.removeCallbacks(proof)
        main.post(proof)
    }

    /** Same proof as the proactive page: attached, shown, laid out and focused - nothing less. */
    private fun proveVisible() {
        val id = eventId ?: return
        if (reported || shown) return
        val decor = runCatching { window?.decorView }.getOrNull()
        val visible = decor != null && decor.isAttachedToWindow && decor.isShown &&
            decor.width > 0 && decor.height > 0 && decor.hasWindowFocus()
        if (visible) {
            shown = true
            main.removeCallbacks(proof)
            InterceptRuntime.onPageShown(id)
            render()
            return
        }
        if (++attempts <= MAX_PROOF_ATTEMPTS) main.postDelayed(proof, PROOF_STEP_MS)
    }

    /** Leaving the page without choosing anything is an exit: the person went away from the app. */
    @Deprecated("Activity back handling; targetSdk 28")
    override fun onBackPressed() = exit()

    override fun onStop() {
        super.onStop()
        // 灭屏不清锁: with the screen off the page is only stopped and the lock stays open in the
        // guard; when the locked app comes back (亮屏、解锁、切回游戏) InterceptRuntime shows this same
        // lock again. With the screen on, being pushed away (home key, recents) is leaving by the exit.
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (!reported && !isChangingConfigurations && screenOn && !isFinishing) exit()
    }

    override fun onDestroy() {
        main.removeCallbacks(proof)
        if (onScreenId == eventId) onScreenId = null
        // Destroyed without a report (the system reclaimed it, or it was restored and finished): the
        // lock is not over - it resurfaces with the app. Only the screen slot is given back.
        // Free the shared visible-surface slot before unregistering: whichever way this page ended
        // (released, exited, paused, back-press, destroyed by the system), the screen is free again.
        InterceptRuntime.onPageGone(eventId)
        unregister()
        super.onDestroy()
    }


    private fun release(how: String) {
        val id = eventId ?: return
        if (reported) return
        reported = true
        main.removeCallbacks(proof)
        val opened = InterceptRuntime.onReleased(id, how)
        val pkg = packageName
        if (!isFinishing) finish()
        // A release is meant to work: if the guard accepted it, put the app back in front.
        if (opened && pkg != null) InterceptRuntime.openTarget(this, pkg)
    }

    private fun exit() {
        val id = eventId ?: return
        if (reported) return
        reported = true
        main.removeCallbacks(proof)
        InterceptRuntime.onExited(id)
        if (!isFinishing) finish()
        // The emergency exit goes home, never back into the app it just refused.
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun pause() {
        val id = eventId ?: return
        if (reported) return
        reported = true
        main.removeCallbacks(proof)
        InterceptRuntime.onPaused(id)
        val pkg = packageName
        if (!isFinishing) finish()
        if (pkg != null) InterceptRuntime.openTarget(this, pkg)
    }

    /**
     * 计步破窗：替代"点一下就跑"的紧急退出。走够 150 步、手打 100 字违约说明，
     * 手机把 steps / note 报给插件；插件放风 10 分钟、扣信用分，晚上对账会问。
     */
    private fun showBreakglass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQUEST_STEPS)
            return
        }
        steps = 0
        stepBaseline = null
        val ctx = this
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        column.addView(TextView(ctx).apply {
            text = AppStrings.get(ctx, R.string.intercept_break_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(palette.ink)
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(ctx).apply {
            text = AppStrings.get(ctx, R.string.intercept_break_body)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.muted)
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(8), 0, dp(10))
        })
        column.addView(TextView(ctx).apply {
            breakStepsView = this
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(palette.ink)
        })
        column.addView(EditText(ctx).apply {
            breakNoteView = this
            hint = AppStrings.get(ctx, R.string.intercept_break_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 6
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(palette.pressed)
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = refreshBreak()
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        column.addView(TextView(ctx).apply {
            breakCountView = this
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(palette.muted)
            setPadding(0, dp(4), 0, dp(8))
        })
        column.addView(pillButton(AppStrings.get(ctx, R.string.intercept_break_submit), primary = true, onTap = {
            val note = breakNoteView?.text?.toString()?.trim().orEmpty()
            if (steps < BREAK_STEPS) {
                android.widget.Toast.makeText(ctx, R.string.intercept_break_wait, android.widget.Toast.LENGTH_SHORT).show()
                return@pillButton
            }
            submitBreakglass(note)
        }).apply { breakSubmit = this })
        column.addView(pillButton(AppStrings.get(ctx, R.string.intercept_break_cancel), secondary = true, onTap = {
            breakDialog?.dismiss()
        }))

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(ScrollView(ctx).apply { addView(column) })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            val sensor = (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            stepSensor = sensor
            if (sensor == null) {
                breakStepsView?.text = AppStrings.get(ctx, R.string.intercept_break_no_sensor)
            } else {
                (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.registerListener(
                    stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL,
                )
            }
            refreshBreak()
        }
        dialog.setOnDismissListener {
            (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(stepListener)
            stepSensor = null
            if (breakDialog === dialog) breakDialog = null
        }
        breakDialog = dialog
        dialog.show()
    }

    private fun refreshBreak() {
        breakStepsView?.let { if (stepSensor != null) it.text = AppStrings.get(this, R.string.intercept_break_steps, steps) }
        val length = breakNoteView?.text?.toString()?.trim()?.length ?: 0
        breakCountView?.text = AppStrings.get(this, R.string.intercept_break_note, length)
        breakSubmit?.let {
            val ready = steps >= BREAK_STEPS && length >= BREAK_CHARS && stepSensor != null
            it.isEnabled = ready
            it.alpha = if (ready) 1f else 0.5f
        }
    }

    private fun submitBreakglass(note: String) {
        val button = breakSubmit ?: return
        button.isEnabled = false
        button.text = AppStrings.get(this, R.string.intercept_break_sending)
        ProactiveRuntime.breakglass(steps, note) { ok ->
            if (isFinishing || isDestroyed) return@breakglass
            if (!ok) {
                button.text = AppStrings.get(this, R.string.intercept_break_submit)
                refreshBreak()
                android.widget.Toast.makeText(this, R.string.intercept_break_failed, android.widget.Toast.LENGTH_SHORT).show()
                return@breakglass
            }
            breakDialog?.dismiss()
            // 破窗是允许的离开：不算紧急退出，但锁要正式结束（否则下次锁不上），复查在放风期间不算违抗。
            reported = true
            eventId?.let { InterceptRuntime.onDismissed(it, "breakglass") }
            val pkg = packageName
            if (!isFinishing) finish()
            if (pkg != null) InterceptRuntime.openTarget(this, pkg)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STEPS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) showBreakglass()
        else android.widget.Toast.makeText(this, R.string.intercept_break_denied, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 讲道理: the line goes into 大肥鱼's session as the person's own words. If it accepts the reason
     * (a real pushback), the plugin drops /plan's force below 3 and ProactiveRuntime releases this
     * very page — the page itself never decides that.
     */
    private fun sendChat() {
        val box = chatInput ?: return
        val text = box.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || chatting) return
        box.setText("")
        appendChat(AppStrings.get(this, R.string.proactive_overlay_you, text))
        chatting = true
        refreshChatSend()
        setChatStatus(AppStrings.get(this, R.string.proactive_overlay_thinking))
        ProactiveRuntime.say(text) { reply, error ->
            if (isFinishing || isDestroyed) return@say
            chatting = false
            refreshChatSend()
            setChatStatus(null)
            appendChat(
                when {
                    !reply.isNullOrBlank() -> AppStrings.get(this, R.string.proactive_overlay_reply, reply)
                    error == "agent-busy" || error == "desk-busy" -> AppStrings.get(this, R.string.proactive_overlay_busy)
                    error == "timeout" -> AppStrings.get(this, R.string.proactive_overlay_slow_model)
                    else -> AppStrings.get(this, R.string.proactive_overlay_failed, error ?: "unknown")
                },
            )
        }
    }

    private fun setChatStatus(text: String?) {
        chatStatus?.text = text ?: ""
        chatStatus?.visibility = if (text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Same 40 dp circular send key as 问一句, so the two chat entries look alike. */
    private fun refreshChatSend() {
        val key = chatSend ?: return
        val can = !chatting && !chatInput?.text.isNullOrBlank()
        key.isEnabled = can
        key.alpha = if (can) 1f else 0.4f
        key.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (can) palette.accent else palette.pressed)
        }
        key.imageTintList = ColorStateList.valueOf(if (can) palette.onAccent else palette.muted)
    }

    private fun appendChat(line: String) {
        val view = chatLog ?: return
        view.append(if (view.text.isNullOrEmpty()) line else "\n\n$line")
        chatScroll?.post { chatScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * 急事底线：电话、短信、地图、支付在锁屏页上也能直接打开（和 ProactiveRuntime.NEVER_LOCK 同一组）。
     * 只列出真的装了的 App；打开时静默关页，不算紧急退出、不触发违抗复查。
     */
    private fun quickRow(ctx: Context): LinearLayout? {
        val groups = listOf(
            R.string.intercept_quick_call to listOf("com.android.dialer", "com.google.android.dialer", "com.miui.dialer"),
            R.string.intercept_quick_sms to listOf("com.android.mms", "com.google.android.apps.messaging", "com.miui.message"),
            R.string.intercept_quick_maps to listOf(
                "com.google.android.apps.maps", "com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map",
            ),
            R.string.intercept_quick_pay to listOf("com.eg.android.AlipayGphone", "com.unionpay"),
        )
        val available = groups.mapNotNull { (label, pkgs) ->
            pkgs.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }?.let { label to it }
        }
        if (available.isEmpty()) return null
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            available.forEach { (label, pkg) ->
                addView(pillButton(AppStrings.get(ctx, label), secondary = true, weight = 1f, onTap = {
                    reported = true
                    eventId?.let { InterceptRuntime.onDismissed(it, "quick:" + pkg) }
                    if (!isFinishing) finish()
                    InterceptRuntime.openTarget(ctx, pkg)
                }), LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    topMargin = dp(8)
                    marginEnd = dp(6)
                })
            }
        }
    }

    private fun unregister() { eventId?.let { if (live[it] === this) live.remove(it) } }

    private fun render() {
        val d = drill
        val solved = d?.solved == true
        trainingButton?.let { b ->
            b.text = AppStrings.get(
                this,
                if (solved) R.string.intercept_training else R.string.intercept_training_locked,
            )
            // The button only ever offers what the guard will accept: a solved drill.
            b.isEnabled = shown && solved
            b.alpha = if (b.isEnabled) 1f else 0.5f
        }
        drillStatus?.let { t ->
            t.text = when {
                d == null -> ""
                solved -> AppStrings.get(this, R.string.intercept_training)
                d.wrongAttempts > 0 && d.cursor == 0 -> AppStrings.get(this, R.string.intercept_drill_wrong)
                else -> AppStrings.get(this, R.string.intercept_drill_progress, d.cursor, d.candidates.size)
            }
        }
        renderDrillButtons()
    }

    /** The candidate buttons: pressed ones go quiet, and the order is never hinted. */
    private fun renderDrillButtons() {
        val d = drill ?: return
        val order = if (d.ascending) d.candidates.sorted() else d.candidates.sortedDescending()
        val done = order.take(d.cursor).toSet()
        drillButtons.forEach { (value, button) ->
            button.isEnabled = shown && !d.solved && value !in done
            button.alpha = if (button.isEnabled) 1f else 0.5f
        }
    }

    /** One press: forwarded to the guard, which is the only thing that can accept a pass. */
    private fun tapDrill(value: Int) {
        val id = eventId ?: return
        if (InterceptRuntime.tapDrill(id, value)) {
            drill = InterceptRuntime.drill(id)
            render()
        }
    }

    private fun drillRow(ctx: android.content.Context): LinearLayout {
        val d = drill ?: return LinearLayout(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        for (value in d.candidates) {
            row.addView(pillButton(value.toString(), weight = 1f, onTap = { tapDrill(value) }).apply {
                minimumWidth = dp(56)
                drillButtons[value] = this
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(6)
            })
        }
        return row
    }


    private fun content(label: String): LinearLayout {
        val ctx = this
        val releaseMinutes = (InterceptRuntime.releaseMs() / 60_000L).coerceAtLeast(1L).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(palette.fill)
                setStroke(dp(1).coerceAtLeast(1), palette.stroke)
            }
            addView(TextView(ctx).apply {
                text = AppStrings.get(ctx, R.string.intercept_title, label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(palette.ink)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                text = AppStrings.get(ctx, R.string.intercept_body, label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(palette.muted)
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(8), 0, dp(12))
            })
            // 大肥鱼自己的理由（/plan 的 force.why），不是一句固定的"娱乐拦截"。
            val whyLine = why
            if (!whyLine.isNullOrBlank()) {
                addView(TextView(ctx).apply {
                    text = AppStrings.get(ctx, R.string.proactive_overlay_reply, whyLine)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(palette.onTonal)
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(palette.tonal)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                })
            }
            // P3: 直接讲道理。判球是真反驳时，插件会把力度降到 1，本页由手机侧关闭。
            val log = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(palette.ink)
                setLineSpacing(0f, 1.2f)
            }
            chatLog = log
            addView(ScrollView(ctx).apply {
                chatScroll = this
                addView(log)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140)).apply {
                bottomMargin = dp(8)
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                val box = EditText(ctx).apply {
                    hint = AppStrings.get(ctx, R.string.proactive_overlay_hint)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(palette.ink)
                    setHintTextColor(palette.muted)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    maxLines = 4
                    imeOptions = EditorInfo.IME_ACTION_SEND
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(palette.pressed)
                    }
                    setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false }
                }
                chatInput = box
                box.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) = refreshChatSend()
                })
                addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(ctx).apply {
                    chatSend = this
                    setImageResource(R.drawable.ic_pet_send)
                    scaleType = ImageView.ScaleType.CENTER
                    contentDescription = AppStrings.get(ctx, R.string.pet_ask_send)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { sendChat() }
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(8)
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
            addView(TextView(ctx).apply {
                chatStatus = this
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(palette.muted)
                visibility = android.view.View.GONE
                setPadding(0, dp(2), 0, dp(4))
            })
            // The 训练题: the only door that does not spend money or time, and it has to be earned.
            drill?.let { d ->
                addView(TextView(ctx).apply {
                    text = AppStrings.get(
                        ctx, R.string.intercept_drill_prompt,
                        AppStrings.get(
                            ctx,
                            if (d.ascending) R.string.intercept_drill_ascending else R.string.intercept_drill_descending,
                        ),
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(palette.muted)
                    setPadding(0, dp(4), 0, dp(8))
                })
                addView(drillRow(ctx))
                addView(TextView(ctx).apply {
                    drillStatus = this
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.muted)
                    setPadding(0, dp(2), 0, dp(4))
                })
            }
            // Gemini 互动舱：主按钮是"回 Gemini"，不是回桌面。点它静默关页，不触发违抗复查。
            if (ProactiveRuntime.sessionActive()) {
                ProactiveRuntime.sessionApp()?.let { gem ->
                    addView(pillButton(AppStrings.get(ctx, R.string.intercept_return_gemini), primary = true, onTap = {
                        reported = true
                        eventId?.let { InterceptRuntime.onDismissed(it, "gemini") }
                        if (!isFinishing) finish()
                        InterceptRuntime.openTarget(ctx, gem)
                    }))
                }
            }
            // The ways out, in increasing order of commitment: the drill, timed pass, standing pass,
            // pause everything, leave. Every button names this page's eventId, never a new one.
            addView(pillButton("", primary = true, onTap = {
                if (drill?.solved == true) release(InterceptRuntime.RELEASE_TRAINING)
            }).apply { trainingButton = this })
            addView(pillButton(AppStrings.get(ctx, R.string.intercept_rule, releaseMinutes), onTap = {
                release(InterceptRuntime.RELEASE_RULE)
            }))
            addView(pillButton(AppStrings.get(ctx, R.string.intercept_whitelist), onTap = {
                release(InterceptRuntime.RELEASE_WHITELIST)
            }))
            // 破窗：走够步数 + 写清违约说明，换 10 分钟放风（比"点一下就退出"贵，但留下了记录）。
            addView(pillButton(AppStrings.get(ctx, R.string.intercept_break), onTap = { showBreakglass() }))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(pillButton(AppStrings.get(ctx, R.string.intercept_pause), secondary = true, weight = 1f, onTap = { pause() }), LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    topMargin = dp(8); marginEnd = dp(6)
                })
                addView(pillButton(AppStrings.get(ctx, R.string.intercept_exit), secondary = true, weight = 1f, onTap = { exit() }), LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    topMargin = dp(8)
                })
            })
            // 急事底线：电话 / 短信 / 地图 / 支付永远从这里能打开。
            quickRow(ctx)?.let { addView(it) }
            refreshChatSend()
        }
    }

    /** One DSH-Go pill button: accent for the main door, tonal for the rest, outline for the way out. */
    private fun pillButton(
        label: String,
        primary: Boolean = false,
        secondary: Boolean = false,
        weight: Float = 0f,
        onTap: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(if (primary) palette.onAccent else if (secondary) palette.ink else palette.onTonal)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (primary) palette.accent else if (secondary) palette.fill else palette.tonal)
            if (secondary) setStroke(dp(1).coerceAtLeast(1), palette.stroke)
        }
        stateListAnimator = null
        backgroundTintList = null
        elevation = 0f
        layoutParams = if (weight > 0f) {
            LinearLayout.LayoutParams(0, dp(46), weight)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) }
        }
        setOnClickListener { onTap() }
    }

    companion object {
        private const val EXTRA_EVENT_ID = "intercept_event_id"
        private const val EXTRA_PACKAGE = "intercept_package"
        private const val EXTRA_LABEL = "intercept_label"
        private const val EXTRA_WHY = "intercept_why"
        private const val EXTRA_TRAINING_MS = "intercept_training_ms"
        private const val EXTRA_DEADLINE = "intercept_deadline"

        /** ~2 s of retries for the visibility proof, like the proactive page. */
        private const val PROOF_STEP_MS = 100L
        private const val MAX_PROOF_ATTEMPTS = 20

        /** 计步破窗：150 步 + 100 字违约说明（第 8 节）。 */
        private const val REQUEST_STEPS = 411
        private const val BREAK_STEPS = 150
        private const val BREAK_CHARS = 100

        /** Pages on screen by eventId, so a withdrawal can close them. Main thread only. */
        private val live = HashMap<String, InterceptPageActivity>()

        /** The eventId whose page is resumed right now (between onResume and onPause); null otherwise. */
        @Volatile private var onScreenId: String? = null

        /** True while the page for [eventId] is in front of the person (not stopped by 灭屏, not behind). */
        fun isOnScreen(eventId: String): Boolean = onScreenId == eventId && live[eventId] != null

        /** Started from DSH's own process; false if the system refused at once. */
        fun start(context: Context, eventId: String, pkg: String, label: String, trainingMs: Long, deadline: Long, why: String? = null): Boolean = runCatching {
            context.startActivity(
                Intent(context, InterceptPageActivity::class.java)
                    .putExtra(EXTRA_EVENT_ID, eventId).putExtra(EXTRA_PACKAGE, pkg)
                    .putExtra(EXTRA_LABEL, label.take(64)).putExtra(EXTRA_TRAINING_MS, trainingMs)
                    .putExtra(EXTRA_DEADLINE, deadline).putExtra(EXTRA_WHY, why?.take(200))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }.isSuccess

        fun withdraw(eventId: String) { live.remove(eventId)?.let { it.reported = true; it.finish() } }

        fun withdrawAll() {
            live.values.toList().forEach { page ->
                // Withdrawn by the runtime: the lock record ends too, or the next lock would find it "open".
                if (!page.reported) page.eventId?.let { InterceptRuntime.onDismissed(it, "withdrawn") }
                page.reported = true
                page.finish()
            }
            live.clear()
            InterceptRuntime.onPageGone(null)
        }
    }
}

