package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.intercept

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptRules
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus.FocusRuntime
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ArbiterShadow
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ProactiveOverlay
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ProactiveRuntime
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.pet.OverlayPalette

/**
 * DSH Go V4 专属锁屏与专注直达页（彻底取代假退出/按数字/走 150 步/冲刺摆设）。
 *
 * 设计要点：
 * 1. 不再自动把用户踢回系统桌面（消除与 vivo BBK 桌面死循环互弹）。
 * 2. 顶部内嵌「当前可用 App 直达舱」与「回 DSH 控制台」，在锁屏页就能直接跳进正事 App。
 * 3. 中部接入「题库闯关解锁（默认答对 10 题解锁 5 分钟）」：答对真题立即放行并配倒计时胶囊。
 * 4. 底部保留人性化「电话 / 短信 / 地图 / 支付」快捷通道，并提供长按 3 秒「真·紧急退出回 DSH（暂停严格锁 10 分钟）」。
 */
class InterceptPageActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var eventId: String? = null
    private var packageName: String? = null
    private var why: String? = null
    private var unlockHow: String? = null
    private var lockMode: String = InterceptRules.MODE_TRAINING
    private var todayCount: Int = 1
    private var reported = false

    private var shown = false
    private var attempts = 0
    private val proof = Runnable { proveVisible() }

    // 讲道理聊天框
    private var chatLog: TextView? = null
    private var chatScroll: ScrollView? = null
    private var chatInput: EditText? = null
    private var chatSend: ImageView? = null
    private var chatStatus: TextView? = null
    private var chatting = false

    // 追问锁与 D7 只读面板
    private var followupUntil = 0L
    private var followupBackTo: String? = null
    private var followupQuestion: String? = null
    private var followupLabel: TextView? = null
    private var panelLabel: TextView? = null

    // 题库闯关解锁 UI 状态
    private var quizBox: LinearLayout? = null
    private var quizProgressView: TextView? = null
    private var quizStemView: TextView? = null
    private var quizFeedbackView: TextView? = null
    private var quizOptionsBox: LinearLayout? = null
    private var currentQuestionId: String? = null
    private var quizBusy = false

    private val tick = object : Runnable {
        override fun run() {
            panelLabel?.text = ProactiveRuntime.panelText()
            val until = followupUntil
            if (until > 0 && until - System.currentTimeMillis() <= 0) followupUntil = 0L
            if (followupUntil <= 0) followupLabel?.visibility = View.GONE
            else followupLabel?.text = followupCopy(followupUntil)
            main.postDelayed(this, 1_000L)
        }
    }

    private val palette by lazy { OverlayPalette.of(this) }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setDimAmount(0.45f)
        setFinishOnTouchOutside(false)
        if (savedInstanceState != null) {
            finish()
            return
        }
        bind(intent)
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
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
            finish()
            return
        }
        eventId = id
        packageName = start.getStringExtra(EXTRA_PACKAGE)
        why = start.getStringExtra(EXTRA_WHY)?.takeIf { it.isNotBlank() }
        unlockHow = start.getStringExtra(EXTRA_UNLOCK_HOW)?.takeIf { it.isNotBlank() }
        if (ArbiterShadow.enforceEnabled(this)) {
            val arbiter = ArbiterShadow.current()
            if (why.isNullOrBlank()) why = arbiter?.why?.takeIf { it.isNotBlank() }
            if (unlockHow.isNullOrBlank()) unlockHow = arbiter?.unlockHow?.takeIf { it.isNotBlank() }
        }
        lockMode = InterceptRules.normalizeLockMode(
            start.getStringExtra(EXTRA_LOCK_MODE) ?: InterceptRuntime.effectiveLockMode(),
        )
        todayCount = start.getIntExtra(EXTRA_TODAY_COUNT, InterceptRuntime.guardOrNull()?.state?.countToday ?: 1)
        followupUntil = ProactiveRuntime.followupUntil()
        followupBackTo = ProactiveRuntime.followupBackTo()
        followupQuestion = ProactiveRuntime.followupQuestion()
        live[id] = this
        setContentView(content(label))
        awaitVisible()
        loadQuizQuestion()
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
            return
        }
        if (++attempts <= MAX_PROOF_ATTEMPTS) main.postDelayed(proof, PROOF_STEP_MS)
    }

    @Deprecated("Activity back handling; targetSdk 28")
    override fun onBackPressed() {
        if (followupUntil > System.currentTimeMillis()) return
        // 不再发 CATEGORY_HOME 踢回桌面：直接离开本页但保留记录，用户可自如打开 DSH 或白名单应用。
        dismissToDshOrStay()
    }

    override fun onDestroy() {
        main.removeCallbacks(proof)
        main.removeCallbacks(tick)
        if (onScreenId == eventId) onScreenId = null
        InterceptRuntime.onPageGone(eventId)
        unregister()
        super.onDestroy()
    }

    private fun dismissToDshOrStay() {
        val id = eventId ?: return
        if (reported) return
        reported = true
        main.removeCallbacks(proof)
        InterceptRuntime.onDismissed(id, "return_dsh")
        if (!isFinishing) finish()
        openDshConsole()
    }

    private fun openDshConsole() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun launchAllowedApp(pkg: String) {
        val id = eventId ?: return
        if (reported) return
        reported = true
        main.removeCallbacks(proof)
        InterceptRuntime.onDismissed(id, "switch_allowed:$pkg")
        if (!isFinishing) finish()
        InterceptRuntime.openTarget(this, pkg)
    }

    /** 真·紧急退出：暂停严格锁 10 分钟 + 写入督办记录 + 直接回到 DSH 控制台，不再把用户丢去桌面死循环。 */
    private fun performRealEmergencyExit() {
        val id = eventId ?: return
        if (reported) return
        reported = true
        main.removeCallbacks(proof)
        ProactiveRuntime.suspendStrictForEmergency(10)
        InterceptRuntime.onDismissed(id, "emergency_dsh_suspend")
        if (!isFinishing) finish()
        openDshConsole()
    }

    /** 急事 3 分钟通行：DSH Go 雾蓝玻璃选择卡 -> POST /emergency-pass -> 顶部悬浮倒计时胶囊。 */
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
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
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

    /** 拉取题库闯关会话（答对 10 题解锁 5 分钟）。 */
    private fun loadQuizQuestion() {
        val box = quizBox ?: return
        quizBusy = true
        quizProgressView?.text = "题库读取中…"
        ProactiveRuntime.fetchBankQuizSession { res ->
            if (isFinishing || isDestroyed) return@fetchBankQuizSession
            main.post {
                quizBusy = false
                if (res == null || res["ok"] != true) {
                    quizProgressView?.text = "暂无题库或引擎未连接"
                    quizStemView?.text = "还没有导入解锁题库。请点击下方「回 DSH 导入题库」，导入考公/专业 JSON 或纯文本题库即可用「答 10 题换 5 分钟」。"
                    quizFeedbackView?.visibility = View.GONE
                    quizOptionsBox?.removeAllViews()
                    box.visibility = View.VISIBLE
                    return@post
                }
                renderQuizPayload(res)
            }
        }
    }

    private fun renderQuizPayload(res: Map<*, *>) {
        // 2026-11-21 自己对答案的题只算 0.7 题：进度可能是小数，整数时不带小数点。
        val solvedRaw = (res["solved"] as? Number)?.toDouble() ?: 0.0
        val solved = if (solvedRaw % 1.0 == 0.0) solvedRaw.toInt().toString() else String.format(java.util.Locale.US, "%.1f", solvedRaw)
        val required = (res["required"] as? Number)?.toInt() ?: 10
        val unlockMin = (res["unlockMinutes"] as? Number)?.toInt() ?: 5
        val bankTotal = (res["bankTotal"] as? Number)?.toInt() ?: 0
        if (bankTotal <= 0 && res["question"] == null) {
            quizProgressView?.text = "题库为空（0 题）"
            quizStemView?.text = "请先在 DSH Go 的「题库中心」粘贴或导入题目；之后遇到娱乐锁屏，只要做对 $required 题即可放行 $unlockMin 分钟。"
            quizFeedbackView?.visibility = View.GONE
            quizOptionsBox?.removeAllViews()
            return
        }
        val q = res["question"] as? Map<*, *>
        if (q == null) {
            quizProgressView?.text = "今日进度 $solved / $required 题"
            quizStemView?.text = "题库已全部刷完，可点「回到 DSH Go」补充新题库。"
            quizOptionsBox?.removeAllViews()
            return
        }
        currentQuestionId = q["id"] as? String
        quizProgressView?.text = "刷题解锁 $solved / $required 题 · 达标放行 $unlockMin 分钟"
        quizStemView?.text = (q["stem"] as? String).orEmpty()
        val rawOptions = q["options"] as? List<*> ?: emptyList<Any?>()
        val optBox = quizOptionsBox ?: return
        optBox.removeAllViews()
        if (rawOptions.isEmpty() && q["kind"] == "review") {
            // 2026-11-21 个人复习卡（没有选项）：先在心里答，再看答案，自己判「记得 / 忘了」。
            // 自判只改复习排期、只算 0.7 题；不算题库答对数。
            val ans = (q["answer"] as? String).orEmpty()
            optBox.addView(pillButton("想好了，看答案（自己对的只算 0.7 题）", secondary = false) {
                quizFeedbackView?.apply {
                    visibility = View.VISIBLE
                    setTextColor(palette.muted)
                    text = "答案：$ans"
                }
                optBox.removeAllViews()
                optBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(pillButton("忘了", secondary = true, weight = 1f) { submitQuizChoice("FORGOT") },
                        LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
                    addView(pillButton("记得", secondary = false, weight = 1f) { submitQuizChoice("REMEMBER") },
                        LinearLayout.LayoutParams(0, dp(46), 1f))
                })
            })
        } else if (rawOptions.isEmpty()) {
            // 无预设选项的简答题：只能自己核对答案——2026-11-21 起只算 0.7 题、不进题库答对数（题库类验收点不认）。
            val ansHint = (q["answer"] as? String).orEmpty()
            optBox.addView(pillButton("查看标准答案并记作已过（只算 0.7 题）", secondary = false) {
                submitQuizChoice(ansHint.ifBlank { "PASS" })
            })
        } else {
            val labels = listOf("A", "B", "C", "D", "E", "F")
            rawOptions.forEachIndexed { idx, opt ->
                val text = opt?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@forEachIndexed
                val letter = labels.getOrElse(idx) { "${idx + 1}" }
                val btn = pillButton("$letter. $text", secondary = true) {
                    submitQuizChoice(letter)
                }.apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(6) }
                }
                optBox.addView(btn)
            }
        }
    }

    private fun submitQuizChoice(answer: String) {
        val qid = currentQuestionId ?: return
        if (quizBusy) return
        quizBusy = true
        quizFeedbackView?.apply {
            visibility = View.VISIBLE
            text = "正在判题…"
            setTextColor(palette.muted)
        }
        ProactiveRuntime.submitBankQuizAnswer(qid, answer) { res ->
            if (isFinishing || isDestroyed) return@submitBankQuizAnswer
            main.post {
                quizBusy = false
                if (res == null) {
                    quizFeedbackView?.text = "暂未连上引擎，请稍后重试"
                    return@post
                }
                val correct = res["correct"] == true
                val unlocked = res["unlocked"] == true
                val explain = (res["explain"] as? String).orEmpty()
                val correctAns = (res["correctAnswer"] as? String).orEmpty()
                val unlockMin = (res["unlockMinutes"] as? Number)?.toInt() ?: 5
                if (unlocked) {
                    android.widget.Toast.makeText(
                        this,
                        "✓ 已答满目标题数，临时放行 $unlockMin 分钟！",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    reported = true
                    eventId?.let { InterceptRuntime.onDismissed(it, "quiz_unlock") }
                    val pkg = packageName
                    if (!isFinishing) finish()
                    if (pkg != null) InterceptRuntime.openTarget(this, pkg)
                    return@post
                }
                quizFeedbackView?.apply {
                    visibility = View.VISIBLE
                    setTextColor(if (correct) Color.parseColor("#1D8F63") else Color.parseColor("#C94A4A"))
                    text = if (correct) {
                        "✓ 回答正确！" + (if (explain.isNotBlank()) " $explain" else "")
                    } else {
                        "✗ 正确答案：$correctAns。" + (if (explain.isNotBlank()) "\n解析：$explain" else "")
                    }
                }
                // 短暂展示反馈并刷新下一题
                main.postDelayed({
                    if (!isFinishing && !isDestroyed) renderQuizPayload(res)
                }, if (correct) 450L else 1_300L)
            }
        }
    }

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

    private fun followupCopy(until: Long): String {
        val total = ((until - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L)
        val left = if (total >= 60) "${total / 60} 分 ${total % 60} 秒" else "${total} 秒"
        val back = ProactiveRuntime.stateLabel(followupBackTo)
        return if (back != null) "追问锁：还剩 $left，问完回到$back" else "追问锁：还剩 $left"
    }

    private fun setChatStatus(text: String?) {
        chatStatus?.text = text ?: ""
        chatStatus?.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

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
        chatScroll?.visibility = View.VISIBLE
        view.append(if (view.text.isNullOrEmpty()) line else "\n\n$line")
        chatScroll?.post { chatScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** 急事底线：电话、短信、地图、支付永远从锁屏直达（不触发违抗）。 */
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
                    eventId?.let { InterceptRuntime.onDismissed(it, "quick:$pkg") }
                    if (!isFinishing) finish()
                    InterceptRuntime.openTarget(ctx, pkg)
                }), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    topMargin = dp(8)
                    marginEnd = dp(6)
                })
            }
        }
    }

    private fun unregister() { eventId?.let { if (live[it] === this) live.remove(it) } }

    private fun modeChipText(): String = when {
        lockMode == InterceptRules.MODE_STRICT && InterceptRuntime.config().lockMode == InterceptRules.MODE_LINKED -> "专注联动 · 严格锁"
        lockMode == InterceptRules.MODE_STRICT -> "严格锁机"
        else -> "专注刷题门禁"
    }

    private fun content(label: String): View {
        val ctx = this
        val isStrict = lockMode == InterceptRules.MODE_STRICT
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(palette.fill)
                setStroke(dp(1).coerceAtLeast(1), palette.stroke)
            }
            // 顶部标签行
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
                addView(TextView(ctx).apply {
                    text = "DSH Go · 专注锁屏舱"
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
                text = "已拦住「$label」"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(palette.ink)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                text = if (isStrict) {
                    "当前契约正在严格保护期：下面列出了此刻可用正事 App；若真有急事或需要临时放松，可刷导入题库或长按紧急退出回 DSH。"
                } else {
                    "已屏蔽娱乐入口。可直接点上方正事 App 继续干活，或答对导入题库解锁 5 分钟。"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.muted)
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(6), 0, dp(10))
            })

            val whyLine = why
            val unlockLine = unlockHow
            if (!whyLine.isNullOrBlank() || !unlockLine.isNullOrBlank()) {
                val combined = buildString {
                    if (!whyLine.isNullOrBlank()) {
                        append(AppStrings.get(ctx, R.string.proactive_overlay_reply, whyLine))
                    }
                    if (!unlockLine.isNullOrBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("解锁方式：")
                        append(unlockLine)
                    }
                }
                addView(TextView(ctx).apply {
                    text = combined
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(palette.onTonal)
                    setLineSpacing(0f, 1.25f)
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(palette.tonal)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                })
            }

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
                    bottomMargin = dp(10)
                })
            }

            ProactiveRuntime.panelText()?.let { panel ->
                addView(TextView(ctx).apply {
                    panelLabel = this
                    text = panel
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.muted)
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, dp(2), 0, dp(8))
                })
            }

            // 1. 当前可用 App 专属直达区（无需回桌面）
            val allowed = ProactiveRuntime.allowedAppsForFocus()
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(palette.pressed)
                    setStroke(dp(1).coerceAtLeast(1), palette.stroke)
                }
                addView(TextView(ctx).apply {
                    text = if (allowed.isNotEmpty()) "专注可用 App（点击直达，不用回桌面）" else "安全控制台直达"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.ink)
                    setPadding(0, 0, 0, dp(6))
                })
                if (allowed.isNotEmpty()) {
                    for (chunk in allowed.chunked(2)) {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { bottomMargin = dp(6) }
                        }
                        chunk.forEachIndexed { idx, (pkgName, appTitle) ->
                            row.addView(pillButton("▶ $appTitle", primary = (idx == 0 && chunk == allowed.take(2)), secondary = true, weight = 1f) {
                                launchAllowedApp(pkgName)
                            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                                if (idx == 0 && chunk.size > 1) marginEnd = dp(6)
                            })
                        }
                        addView(row)
                    }
                }
                addView(pillButton("打开 DSH Go 工作台 / 题库", primary = allowed.isEmpty(), secondary = allowed.isNotEmpty()) {
                    dismissToDshOrStay()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })

            // 2. 题库闯关解锁（答对 10 题开 5 分钟，替代按数字与走 150 步）
            val qCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(palette.pressed)
                    setStroke(dp(1).coerceAtLeast(1), palette.stroke)
                }
                addView(TextView(ctx).apply {
                    quizProgressView = this
                    text = "刷题解锁 · 加载中…"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.accent)
                    setPadding(0, 0, 0, dp(6))
                })
                addView(TextView(ctx).apply {
                    quizStemView = this
                    text = ""
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(palette.ink)
                    setLineSpacing(0f, 1.3f)
                    setPadding(0, 0, 0, dp(6))
                })
                addView(TextView(ctx).apply {
                    quizFeedbackView = this
                    visibility = View.GONE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, 0, 0, dp(6))
                })
                addView(LinearLayout(ctx).apply {
                    quizOptionsBox = this
                    orientation = LinearLayout.VERTICAL
                })
            }
            quizBox = qCard
            addView(qCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })

            main.removeCallbacks(tick)
            main.postDelayed(tick, 1_000L)

            // 3. 和大肥鱼讲道理
            val log = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(palette.ink)
                setLineSpacing(0f, 1.2f)
            }
            chatLog = log
            addView(ScrollView(ctx).apply {
                chatScroll = this
                visibility = View.GONE
                addView(log)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(104)).apply {
                bottomMargin = dp(8)
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                val box = EditText(ctx).apply {
                    hint = AppStrings.get(ctx, R.string.proactive_overlay_hint)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
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
                visibility = View.GONE
                setPadding(0, dp(2), 0, dp(4))
            })

            // 4. 急事 3 分钟 + 真·紧急退出（长按 3 秒暂停严格锁 10 分钟并回 DSH）
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                if (!ProactiveRuntime.inEmergencyPass()) {
                    addView(pillButton("急事 3 分钟", secondary = true, weight = 1f, onTap = { showEmergencyPassDialog() }),
                        LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                            topMargin = dp(8)
                            marginEnd = dp(6)
                        })
                }
                addView(
                    holdButton("长按 3 秒 · 紧急退出回 DSH", holdMs = 3_000L, weight = 1.4f) {
                        performRealEmergencyExit()
                    },
                    LinearLayout.LayoutParams(0, dp(46), 1.4f).apply {
                        topMargin = dp(8)
                    },
                )
            })

            // 5. 人性化急事底栏（电话 / 短信 / 地图 / 支付）
            quickRow(ctx)?.let { addView(it) }
            refreshChatSend()
        }
        return ScrollView(ctx).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(card)
        }
    }

    private fun holdButton(label: String, holdMs: Long, weight: Float = 0f, onConfirmed: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(palette.ink)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(palette.tonal)
                setStroke(dp(1).coerceAtLeast(1), palette.stroke)
            }
            stateListAnimator = null
            backgroundTintList = null
            elevation = 0f
            layoutParams = if (weight > 0f) {
                LinearLayout.LayoutParams(0, dp(46), weight)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) }
            }
            var downAt = 0L
            var ticker: Runnable? = null
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downAt = SystemClock.elapsedRealtime()
                        val r = object : Runnable {
                            override fun run() {
                                if (downAt == 0L) return
                                val leftMs = (holdMs - (SystemClock.elapsedRealtime() - downAt)).coerceAtLeast(0L)
                                if (leftMs <= 0L) {
                                    downAt = 0L
                                    text = label
                                    onConfirmed()
                                } else {
                                    text = String.format(java.util.Locale.US, "按住确认… %.1fs", leftMs / 1000f)
                                    main.postDelayed(this, 100L)
                                }
                            }
                        }
                        ticker = r
                        main.post(r)
                        v.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        downAt = 0L
                        ticker?.let { main.removeCallbacks(it) }
                        ticker = null
                        text = label
                        v.isPressed = false
                        true
                    }
                    else -> false
                }
            }
        }

    private fun pillButton(
        label: String,
        primary: Boolean = false,
        secondary: Boolean = false,
        weight: Float = 0f,
        onTap: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 14.5f
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
        private const val EXTRA_UNLOCK_HOW = "intercept_unlock_how"

        private const val PROOF_STEP_MS = 100L
        private const val MAX_PROOF_ATTEMPTS = 20

        private val live = HashMap<String, InterceptPageActivity>()
        @Volatile private var onScreenId: String? = null

        fun isOnScreen(eventId: String): Boolean = onScreenId == eventId && live[eventId] != null

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
            unlockHow: String? = null,
        ): Boolean = runCatching {
            context.startActivity(
                Intent(context, InterceptPageActivity::class.java)
                    .putExtra(EXTRA_EVENT_ID, eventId).putExtra(EXTRA_PACKAGE, pkg)
                    .putExtra(EXTRA_LABEL, label.take(64)).putExtra(EXTRA_TRAINING_MS, trainingMs)
                    .putExtra(EXTRA_DEADLINE, deadline).putExtra(EXTRA_WHY, why?.take(200))
                    .putExtra(EXTRA_LOCK_MODE, InterceptRules.normalizeLockMode(lockMode))
                    .putExtra(EXTRA_OPEN_SINCE, openSinceWall)
                    .putExtra(EXTRA_TODAY_COUNT, todayCount)
                    .putExtra(EXTRA_UNLOCK_HOW, unlockHow?.take(200))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }.isSuccess

        fun withdraw(eventId: String) { live.remove(eventId)?.let { it.reported = true; it.finish() } }

        fun withdrawAll() {
            live.values.toList().forEach { page ->
                if (!page.reported) page.eventId?.let { InterceptRuntime.onDismissed(it, "withdrawn") }
                page.reported = true
                page.finish()
            }
            live.clear()
            InterceptRuntime.onPageGone(null)
        }
    }
}
