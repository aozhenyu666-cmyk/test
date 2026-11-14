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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptDrill
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptRules
import io.github.ccch1mneyyy.dsh_app.loop.focus.FocusRuntime
import io.github.ccch1mneyyy.dsh_app.loop.proactive.ProactiveOverlay
import io.github.ccch1mneyyy.dsh_app.loop.proactive.ProactiveRuntime
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.pet.OverlayPalette

/**
 * 娱乐 APP 锁机页 (entertainment lock). Bound to the guard-minted eventId; in strict/focus-linked
 * modes it disables digit drills and whitelist bypasses entirely, and in training mode it enforces
 * a real calm reflection countdown before the 4-digit drill can unlock a temporary release.
 */
class InterceptPageActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var eventId: String? = null
    private var packageName: String? = null
    private var why: String? = null
    private var lockMode: String = InterceptRules.MODE_TRAINING
    private var todayCount: Int = 1
    private var reflectionWaitMs: Long = 15_000L
    private var reflectionEndWall: Long = 0L
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
    private var reflectionLabel: TextView? = null
    private var reflectionBar: ProgressBar? = null
    private var drillPromptView: TextView? = null
    private var drillStatus: TextView? = null
    private val drillButtons = LinkedHashMap<Int, Button>()

    /** P3: 跟大肥鱼讲道理 right here; a real reason makes the companion drop the lock. */
    private var chatLog: TextView? = null
    private var chatScroll: ScrollView? = null
    private var chatInput: EditText? = null
    private var chatSend: ImageView? = null
    private var chatStatus: TextView? = null
    private var chatting = false

    /** 追问锁：0 = 这一刻没有追问锁，页面照旧能被划走。 */
    private var followupUntil = 0L
    private var followupBackTo: String? = null
    private var followupQuestion: String? = null
    private var followupLabel: TextView? = null

    /** D7 只读面板：主状态 / 义务 / 额度，全部取插件最后一次 `/plan` 的快照。 */
    private var panelLabel: TextView? = null

    /** 一秒一格：面板重读快照，追问锁与冷静倒计时自己倒数；页面还在就一直排下一格。 */
    private val tick = object : Runnable {
        override fun run() {
            panelLabel?.text = ProactiveRuntime.panelText()
            val until = followupUntil
            // 到点：追问锁结束，页面交还普通拦截逻辑（门由插件按 until 判定，手机不擅自收回拦击记录）。
            if (until > 0 && until - System.currentTimeMillis() <= 0) followupUntil = 0L
            if (followupUntil <= 0) followupLabel?.visibility = android.view.View.GONE
            else followupLabel?.text = followupCopy(followupUntil)
            if (lockMode != InterceptRules.MODE_STRICT) render()
            main.postDelayed(this, 1_000L)
        }
    }

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
        lockMode = InterceptRules.normalizeLockMode(
            start.getStringExtra(EXTRA_LOCK_MODE) ?: InterceptRuntime.effectiveLockMode(),
        )
        todayCount = start.getIntExtra(EXTRA_TODAY_COUNT, InterceptRuntime.guardOrNull()?.state?.countToday ?: 1)
        reflectionWaitMs = start.getLongExtra(EXTRA_TRAINING_MS, InterceptRuntime.config().trainingMs).coerceAtLeast(15_000L)
        val openSince = start.getLongExtra(EXTRA_OPEN_SINCE, 0L)
        val baseWall = if (openSince > 0L) openSince else System.currentTimeMillis()
        reflectionEndWall = baseWall + reflectionWaitMs
        // 追问锁（接口约定 §5）：active 期间窗口不可划走，到 `until` 自己结束，问完按 `backTo` 回主状态。
        followupUntil = ProactiveRuntime.followupUntil()
        followupBackTo = ProactiveRuntime.followupBackTo()
        followupQuestion = ProactiveRuntime.followupQuestion()
        drill = InterceptRuntime.drill(id)
        live[id] = this
        setContentView(content(label))
        render()
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
    override fun onBackPressed() {
        // 追问锁期间窗口不可划走：只能回答问题，或等 followup.until 到点。
        if (followupUntil > System.currentTimeMillis()) return
        exit()
    }

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
        main.removeCallbacks(tick)
        if (onScreenId == eventId) onScreenId = null
        // Destroyed without a report (the system reclaimed it, or it was restored and finished): the
        // lock is not over - it resurfaces with the app. Only the screen slot is given back.
        // Free the shared visible-surface slot before unregistering: whichever way this page ended
        // (released, exited, paused, back-press, destroyed by the system), the screen is free again.
        InterceptRuntime.onPageGone(eventId)
        unregister()
        super.onDestroy()
    }


    private fun reflectionRemainingMs(now: Long = System.currentTimeMillis()): Long =
        (reflectionEndWall - now).coerceAtLeast(0L)

    private fun release(how: String) {
        val id = eventId ?: return
        if (reported) return
        if (lockMode == InterceptRules.MODE_STRICT) return
        if (how == InterceptRuntime.RELEASE_TRAINING && reflectionRemainingMs() > 0L) return
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

    private fun returnToTaskOrDsh() {
        val id = eventId ?: return
        if (reported) return
        reported = true
        main.removeCallbacks(proof)
        InterceptRuntime.onExited(id)
        if (!isFinishing) finish()
        val taskPkg = if (FocusRuntime.isActive()) FocusRuntime.taskPackage() else ""
        val launch = if (taskPkg.isNotBlank()) packageManager.getLaunchIntentForPackage(taskPkg) else null
        runCatching {
            if (launch != null) {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
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
        column.addView(NoPasteEditText(ctx).apply {
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

    /** 急事 3 分钟通行：DSH Go 雾蓝玻璃选择卡 -> 请求 /emergency-pass -> 顶部悬浮倒计时胶囊 -> 到点回收。 */
    private fun showEmergencyPassDialog() {
        val ctx = this
        val reasons = arrayOf("回紧急消息", "付款 / 扫码", "查验证码 / 文件", "接电话 / 联系")
        var selected = reasons[0]
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(palette.fill)
                setStroke(dp(1).coerceAtLeast(1), palette.stroke)
            }
        }
        column.addView(TextView(ctx).apply {
            text = "每日限 2 次 · 到点自动收回"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(palette.onTonal)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(palette.tonal)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        column.addView(TextView(ctx).apply {
            text = "急事 3 分钟放行"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(palette.ink)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        column.addView(TextView(ctx).apply {
            text = "选定当前急事类型。放行期间顶部保留轻量倒计时胶囊，结束后自动拉回。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(palette.muted)
            setLineSpacing(0f, 1.25f)
            setPadding(0, 0, 0, dp(12))
        })
        val chipViews = ArrayList<TextView>(reasons.size)
        fun syncChips() {
            chipViews.forEachIndexed { idx, tv ->
                val active = reasons[idx] == selected
                tv.setTextColor(if (active) palette.onTonal else palette.ink)
                tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (active) palette.tonal else palette.pressed)
                    setStroke(dp(if (active) 2 else 1).coerceAtLeast(1), if (active) palette.accent else palette.stroke)
                }
            }
        }
        for (rowIdx in 0 until reasons.size step 2) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (colIdx in 0..1) {
                val idx = rowIdx + colIdx
                if (idx >= reasons.size) break
                val label = reasons[idx]
                val chip = TextView(ctx).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setPadding(dp(10), dp(11), dp(10), dp(11))
                    setOnClickListener {
                        selected = label
                        syncChips()
                    }
                }
                chipViews.add(chip)
                row.addView(chip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (colIdx == 0) marginEnd = dp(8)
                })
            }
            column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        syncChips()
        val noteBox = EditText(ctx).apply {
            hint = "补充说明（可选，会记入当日台账）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
            minLines = 2
            maxLines = 3
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(palette.pressed)
                setStroke(dp(1).coerceAtLeast(1), palette.stroke)
            }
        }
        column.addView(noteBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(12)
        })
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(ScrollView(ctx).apply { addView(column) })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        lateinit var confirmBtn: Button
        confirmBtn = pillButton("放行 3 分钟", primary = true, weight = 1.5f, onTap = {
            confirmBtn.isEnabled = false
            confirmBtn.text = "申请中…"
            val extra = noteBox.text?.toString()?.trim().orEmpty()
            val fullReason = if (extra.isNotEmpty()) "$selected：$extra" else selected
            ProactiveRuntime.requestEmergencyPass(fullReason) { ok, _, errMsg ->
                if (isFinishing || isDestroyed) return@requestEmergencyPass
                if (!ok) {
                    confirmBtn.isEnabled = true
                    confirmBtn.text = "放行 3 分钟"
                    android.widget.Toast.makeText(ctx, errMsg ?: "急事放行失败", android.widget.Toast.LENGTH_SHORT).show()
                    return@requestEmergencyPass
                }
                dialog.dismiss()
                reported = true
                eventId?.let { InterceptRuntime.onDismissed(it, "emergency:$selected") }
                val pkg = packageName
                if (!isFinishing) finish()
                if (pkg != null) InterceptRuntime.openTarget(ctx, pkg)
            }
        })
        val cancelBtn = pillButton("取消", secondary = true, weight = 1f, onTap = { dialog.dismiss() })
        column.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
            addView(confirmBtn, LinearLayout.LayoutParams(0, dp(46), 1.5f))
        })
        dialog.show()
    }

    private fun submitBreakglass(note: String) {
        val button = breakSubmit ?: return
        button.isEnabled = false
        button.text = AppStrings.get(this, R.string.intercept_break_sending)
        ProactiveRuntime.breakglass(steps, note) { ok, used ->
            if (isFinishing || isDestroyed) return@breakglass
            if (!ok) {
                button.text = AppStrings.get(this, R.string.intercept_break_submit)
                refreshBreak()
                val msg = if (used) R.string.intercept_break_used else R.string.intercept_break_failed
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
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
                    // 双人格：插件在每句话前标〔鱼丸〕/〔鲸澜〕；墙的话以〔墙〕开头，姐妹原样转述。拆出说话人再显示。
                    !reply.isNullOrBlank() -> ProactiveOverlay.splitSpeaker(reply).let { (who, body) ->
                        if (who != null) "$who：$body" else AppStrings.get(this, R.string.proactive_overlay_reply, reply)
                    }
                    error == "agent-busy" || error == "desk-busy" -> AppStrings.get(this, R.string.proactive_overlay_busy)
                    error == "timeout" -> AppStrings.get(this, R.string.proactive_overlay_slow_model)
                    else -> AppStrings.get(this, R.string.proactive_overlay_failed, error ?: "unknown")
                },
            )
        }
    }

    /** 追问锁剩余时间 + 问完回到哪个主状态（接口约定 §2 `backTo`）。 */
    private fun followupCopy(until: Long): String {
        val total = ((until - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L)
        val left = if (total >= 60) "${total / 60} 分 ${total % 60} 秒" else "${total} 秒"
        val back = ProactiveRuntime.stateLabel(followupBackTo)
        return if (back != null) "追问锁：还剩 $left，问完回到$back" else "追问锁：还剩 $left"
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
        chatScroll?.visibility = android.view.View.VISIBLE
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
        if (lockMode == InterceptRules.MODE_STRICT) return
        val remainingMs = reflectionRemainingMs()
        val remainingSec = ((remainingMs + 999L) / 1000L).toInt()
        val readyAfterCalm = remainingMs == 0L
        val releaseMinutes = (InterceptRuntime.releaseMs() / 60_000L).coerceAtLeast(1L).toInt()

        reflectionLabel?.text = if (readyAfterCalm) {
            "冷静完成 · 请按顺序完成下方数字训练"
        } else {
            "先停下来冷静 $remainingSec 秒（倒计时结束前禁用数字解锁）"
        }
        reflectionBar?.let { bar ->
            val maxMs = reflectionWaitMs.toInt().coerceAtLeast(1000)
            if (bar.max != maxMs) bar.max = maxMs
            bar.progress = (reflectionWaitMs - remainingMs).toInt().coerceIn(0, maxMs)
        }

        val d = drill
        val solved = d?.solved == true
        trainingButton?.let { b ->
            b.text = when {
                !readyAfterCalm -> "先冷静 $remainingSec 秒"
                solved -> "冷静训练通过 · 临时放行 $releaseMinutes 分钟"
                else -> "完成上方数字训练后放行"
            }
            b.isEnabled = shown && readyAfterCalm && solved
            b.alpha = if (b.isEnabled) 1f else 0.45f
        }
        drillStatus?.let { t ->
            t.text = when {
                d == null -> ""
                !readyAfterCalm -> "等待冷静倒计时结束（$remainingSec 秒）…"
                solved -> "训练通过，下方按钮已解锁"
                d.wrongAttempts > 0 && d.cursor == 0 -> AppStrings.get(this, R.string.intercept_drill_wrong)
                else -> AppStrings.get(this, R.string.intercept_drill_progress, d.cursor, d.candidates.size)
            }
        }
        renderDrillButtons(readyAfterCalm)
    }

    /** The candidate buttons: locked during reflection countdown, pressed ones go quiet. */
    private fun renderDrillButtons(readyAfterCalm: Boolean) {
        val d = drill ?: return
        val order = if (d.ascending) d.candidates.sorted() else d.candidates.sortedDescending()
        val done = order.take(d.cursor).toSet()
        drillButtons.forEach { (value, button) ->
            button.isEnabled = shown && readyAfterCalm && !d.solved && value !in done
            button.alpha = if (button.isEnabled) 1f else 0.4f
        }
    }

    /** One press: forwarded to the guard, which is the only thing that can accept a pass. */
    private fun tapDrill(value: Int) {
        if (lockMode == InterceptRules.MODE_STRICT || reflectionRemainingMs() > 0L) return
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
        for ((idx, value) in d.candidates.withIndex()) {
            row.addView(pillButton(value.toString(), weight = 1f, onTap = { tapDrill(value) }).apply {
                minimumWidth = dp(56)
                drillButtons[value] = this
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (idx < d.candidates.lastIndex) marginEnd = dp(6)
            })
        }
        return row
    }

    private fun modeChipText(): String = when {
        lockMode == InterceptRules.MODE_STRICT && InterceptRuntime.config().lockMode == InterceptRules.MODE_LINKED -> "专注联动 · 严格锁"
        lockMode == InterceptRules.MODE_STRICT -> "严格锁机"
        else -> "冷静训练模式"
    }

    private fun content(label: String): android.view.View {
        val ctx = this
        drillButtons.clear()
        reflectionLabel = null
        reflectionBar = null
        trainingButton = null
        drillStatus = null
        val isStrict = lockMode == InterceptRules.MODE_STRICT
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(palette.fill)
                setStroke(dp(1).coerceAtLeast(1), palette.stroke)
            }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
                addView(TextView(ctx).apply {
                    text = "DSH Go · 娱乐锁机"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(palette.accent)
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    text = modeChipText()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(if (isStrict) palette.onAccent else palette.onTonal)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(9), dp(4), dp(9), dp(4))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(if (isStrict) palette.accent else palette.tonal)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(6) }
                })
                addView(TextView(ctx).apply {
                    text = "今日第 ${todayCount.coerceAtLeast(1)} 次"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(palette.muted)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(palette.pressed)
                        setStroke(dp(1).coerceAtLeast(1), palette.stroke)
                    }
                })
            })
            addView(TextView(ctx).apply {
                text = AppStrings.get(ctx, R.string.intercept_title, label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(palette.ink)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                text = if (isStrict) {
                    "已锁定「$label」。当前处于严格锁机模式：已禁用数字放行、规则直通与加入白名单。先收手回到任务或 DSH。"
                } else {
                    "你打开了「$label」。必须先完成冷静倒计时，再完成序列训练才能临时放行（不会永久跳过）。"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(palette.muted)
                setLineSpacing(0f, 1.3f)
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
            // 追问锁（接口约定 §5）：窗口不可划走，这里说清楚问的是什么、还剩多久、问完回到哪。
            if (followupUntil > System.currentTimeMillis()) {
                addView(TextView(ctx).apply {
                    followupLabel = this
                    val q = followupQuestion?.trim().orEmpty()
                    text = if (q.isEmpty()) followupCopy(followupUntil) else "$q\n" + followupCopy(followupUntil)
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
            // D7 只读面板（接口约定 §2 `state` / §5 `obligations`）：主状态、义务、额度各一行；
            // 追问锁剩余在上一行跟着倒计时。手机只显示插件算好的快照，不在这里判断锁不锁。
            ProactiveRuntime.panelText()?.let { panel ->
                addView(TextView(ctx).apply {
                    panelLabel = this
                    text = panel
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.muted)
                    setLineSpacing(0f, 1.3f)
                    setPadding(0, dp(2), 0, dp(10))
                })
            }
            // 他主动的两件事（接口约定 §3）：以雾蓝次级容器收纳，不和下方放行按钮混杂。
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(palette.pressed)
                    setStroke(dp(1).coerceAtLeast(1), palette.stroke)
                }
                addView(TextView(ctx).apply {
                    text = "主动切回专注 · 直接告知大肥鱼"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(palette.muted)
                    setPadding(dp(2), 0, 0, dp(6))
                })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(pillButton("冲刺", secondary = true, weight = 1f, onTap = {
                        ProactiveRuntime.startSprint(null, 0, false)
                        android.widget.Toast.makeText(ctx, "已开始冲刺", android.widget.Toast.LENGTH_SHORT).show()
                    }), LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
                    addView(pillButton("长冲刺", secondary = true, weight = 1f, onTap = {
                        ProactiveRuntime.startSprint(null, 0, true)
                        android.widget.Toast.makeText(ctx, "已开始长冲刺", android.widget.Toast.LENGTH_SHORT).show()
                    }), LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
                    addView(pillButton("申请验收", secondary = true, weight = 1f, onTap = {
                        ProactiveRuntime.requestReview()
                        android.widget.Toast.makeText(ctx, "验收申请已发出", android.widget.Toast.LENGTH_SHORT).show()
                    }), LinearLayout.LayoutParams(0, dp(42), 1f))
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
            main.removeCallbacks(tick)
            main.postDelayed(tick, 1_000L)
            // P3: 直接讲道理。判球是真反驳时，插件会把力度降到 1，本页由手机侧关闭。
            val log = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(palette.ink)
                setLineSpacing(0f, 1.2f)
            }
            chatLog = log
            addView(ScrollView(ctx).apply {
                chatScroll = this
                visibility = android.view.View.GONE
                addView(log)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)).apply {
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
            if (isStrict) {
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(palette.tonal)
                        setStroke(dp(1).coerceAtLeast(1), palette.stroke)
                    }
                    addView(TextView(ctx).apply {
                        text = "严格锁机生效中"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        setTextColor(palette.ink)
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(ctx).apply {
                        text = "已彻底禁用数字放行、规则放行与白名单直通。如需调整锁机模式，请返回 DSH Go → 娱乐锁机。"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setTextColor(palette.muted)
                        setLineSpacing(0f, 1.3f)
                        setPadding(0, dp(4), 0, 0)
                    })
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(6)
                })
                // Gemini 互动舱：主按钮是"回 Gemini"，否则一键回到任务 / DSH。
                if (ProactiveRuntime.sessionActive()) {
                    ProactiveRuntime.sessionApp()?.let { gem ->
                        val gemLabel = if (ProactiveRuntime.sessionIsGemini()) R.string.intercept_return_gemini else R.string.intercept_return_session
                        addView(pillButton(AppStrings.get(ctx, gemLabel), primary = true, onTap = {
                            reported = true
                            eventId?.let { InterceptRuntime.onDismissed(it, "gemini") }
                            if (!isFinishing) finish()
                            InterceptRuntime.openTarget(ctx, gem)
                        }))
                    }
                } else {
                    val backLabel = if (FocusRuntime.isActive() && FocusRuntime.taskPackage().isNotBlank()) {
                        "回到当前任务"
                    } else {
                        "收手 · 回到 DSH Go"
                    }
                    addView(pillButton(backLabel, primary = true, onTap = { returnToTaskOrDsh() }))
                }
            } else {
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(palette.pressed)
                        setStroke(dp(1).coerceAtLeast(1), palette.stroke)
                    }
                    addView(TextView(ctx).apply {
                        reflectionLabel = this
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setTextColor(palette.ink)
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, 0, 0, dp(6))
                    })
                    addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                        reflectionBar = this
                        progressTintList = ColorStateList.valueOf(palette.accent)
                        progressBackgroundTintList = ColorStateList.valueOf(palette.stroke)
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(6)
                })
                // The 训练题: locked during reflection countdown, solved only via guard validation.
                drill?.let { d ->
                    addView(TextView(ctx).apply {
                        drillPromptView = this
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
                if (ProactiveRuntime.sessionActive()) {
                    ProactiveRuntime.sessionApp()?.let { gem ->
                        val gemLabel = if (ProactiveRuntime.sessionIsGemini()) R.string.intercept_return_gemini else R.string.intercept_return_session
                        addView(pillButton(AppStrings.get(ctx, gemLabel), primary = true, onTap = {
                            reported = true
                            eventId?.let { InterceptRuntime.onDismissed(it, "gemini") }
                            if (!isFinishing) finish()
                            InterceptRuntime.openTarget(ctx, gem)
                        }))
                    }
                }
                addView(pillButton("", primary = true, onTap = {
                    if (drill?.solved == true && reflectionRemainingMs() == 0L) {
                        release(InterceptRuntime.RELEASE_TRAINING)
                    }
                }).apply { trainingButton = this })
            }
            // 破窗与急事 3 分钟并排收纳，彻底移除一键白名单/授权放行/暂停。
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pillButton(AppStrings.get(ctx, R.string.intercept_break), weight = 1f, onTap = { showBreakglass() }),
                    LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        topMargin = dp(8)
                        if (!ProactiveRuntime.inEmergencyPass()) marginEnd = dp(6)
                    })
                if (!ProactiveRuntime.inEmergencyPass()) {
                    addView(pillButton("急事 3 分钟", weight = 1f, onTap = { showEmergencyPassDialog() }),
                        LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                            topMargin = dp(8)
                        })
                }
            })
            if (!isStrict || ProactiveRuntime.sessionActive()) {
                addView(pillButton(AppStrings.get(ctx, R.string.intercept_exit), secondary = true, onTap = { exit() }))
            }
            // 急事底线：电话 / 短信 / 地图 / 支付永远从这里能打开。
            quickRow(ctx)?.let { addView(it) }
            refreshChatSend()
        }
        return ScrollView(ctx).apply {
            isFillViewport = false
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(card)
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
        private const val EXTRA_LOCK_MODE = "intercept_lock_mode"
        private const val EXTRA_OPEN_SINCE = "intercept_open_since"
        private const val EXTRA_TODAY_COUNT = "intercept_today_count"

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
        fun start(
            context: Context,
            eventId: String,
            pkg: String,
            label: String,
            trainingMs: Long,
            deadline: Long,
            why: String? = null,
            lockMode: String = InterceptRules.MODE_TRAINING,
            openSinceWall: Long = System.currentTimeMillis(),
            todayCount: Int = 1,
        ): Boolean = runCatching {
            context.startActivity(
                Intent(context, InterceptPageActivity::class.java)
                    .putExtra(EXTRA_EVENT_ID, eventId).putExtra(EXTRA_PACKAGE, pkg)
                    .putExtra(EXTRA_LABEL, label.take(64)).putExtra(EXTRA_TRAINING_MS, trainingMs)
                    .putExtra(EXTRA_DEADLINE, deadline).putExtra(EXTRA_WHY, why?.take(200))
                    .putExtra(EXTRA_LOCK_MODE, InterceptRules.normalizeLockMode(lockMode))
                    .putExtra(EXTRA_OPEN_SINCE, openSinceWall)
                    .putExtra(EXTRA_TODAY_COUNT, todayCount)
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

