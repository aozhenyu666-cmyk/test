package com.dshdriver.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dshdriver.core.Phase
import com.dshdriver.core.Profile

/**
 * 首页：权限检查、开始锁定、锁定中的状态、我的主线与死穴、议程、上一次结果。
 * 界面全部用代码搭，不依赖任何 androidx 库。
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private val dp get() = resources.displayMetrics.density

    /** 上一次检查连接的结果（后台线程查，主线程显示）。 */
    @Volatile private var ping: DshLink.Ping? = null
    @Volatile private var pinging = false

    private val refresher = object : Runnable {
        override fun run() {
            render()
            if (Engine.locked) main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt())
        }
        setContentView(ScrollView(this).apply { addView(body) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Engine.onChange = { render(); if (Engine.locked) { main.removeCallbacks(refresher); main.post(refresher) } }
        main.removeCallbacks(refresher)
        main.post(refresher)
        checkLink()
    }

    override fun onPause() {
        Engine.onChange = null
        main.removeCallbacks(refresher)
        super.onPause()
    }

    private fun render() {
        body.removeAllViews()
        title("思维驾驶员", 24f)
        note("开着 Gemini Live / ChatGPT 学习时：切走就把你拉回原断点，不说话就给最省力的一步。")
        if (Engine.locked) renderLocked() else renderIdle()
    }

    // ───────────── 锁定中 ─────────────

    private fun renderLocked() {
        val s = Engine.snapshot ?: return
        section("锁定中")
        val left = ((s.endsAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        val phase = when (s.phase) {
            Phase.LOCKED -> "进行中"
            Phase.INTERRUPTED_BY_SWITCH -> "切走了，等你接回断点"
            Phase.PAUSED -> "休息中"
            Phase.WRAPPING -> "时间到，等一句结论"
            else -> s.phase.name
        }
        line("${s.task.ifBlank { s.topic }}｜$phase｜剩 ${left / 60}:${"%02d".format(left % 60)}")
        line("有依据的思考 ${s.summary.grounded} 次｜回应 ${s.summary.responded} 次｜切屏 ${s.summary.interruptions} 次")
        s.card?.takeIf { !it.answered }?.let { line("当前这一问：${it.text}") }
        button("回到 Live") { Engine.relaunchAnchor(this) }
        button("休息…") {
            val m = intArrayOf(3, 5, 10, 20)
            AlertDialog.Builder(this).setTitle("休息多久？到点会把你拉回断点").setItems(m.map { "$it 分钟" }.toTypedArray()) { _, i -> Engine.pause(m[i]) }.show()
        }
        button("紧急结束") {
            AlertDialog.Builder(this).setTitle("紧急结束这次锁定？").setMessage("会记为主动结束，不算完成。")
                .setPositiveButton("结束") { _, _ -> Engine.stop("emergency") }.setNegativeButton("不结束", null).show()
        }
    }

    // ───────────── 未锁定 ─────────────

    private fun renderIdle() {
        val store = Engine.store
        val settings = store.settings()

        section("准备")
        val a11y = a11yEnabled()
        check("切屏检测（无障碍）", a11y, "必需：没有它就发现不了你切走") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        check("悬浮窗", Settings.canDrawOverlays(this), "用来在 Live 上面浮一张小卡；没有也能用，只是改成全屏卡") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val pm = getSystemService(PowerManager::class.java)
        check("不被省电杀掉", pm?.isIgnoringBatteryOptimizations(packageName) == true, "锁定期间要一直在后台；小米/华为还要手动开「自启动」「后台弹出界面」") {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        }
        val installed = settings.anchors.filter { Engine.isInstalled(this, it) }
        line(if (installed.isEmpty()) "⚠ 没找到 Gemini / ChatGPT，锁定后无法自动回到 Live" else "允许的应用：${installed.joinToString("、") { label(it) }}")

        section("开始一次锁定")
        val topic = input("主题（例：行测·图形推理）")
        val task = input("这一段只钉一件事（例：第3题）")
        val minutes = input("时长（分钟）", settings.minutes.toString(), InputType.TYPE_CLASS_NUMBER)
        button(if (a11y) "开始锁定" else "先打开切屏检测") {
            if (!a11yEnabled()) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return@button }
            val m = minutes.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: settings.minutes
            store.saveSettings(settings.copy(minutes = m))
            Engine.start(topic.text.toString().trim(), task.text.toString().trim(), m)
            main.postDelayed({ Engine.relaunchAnchor(this) }, 600)
        }

        renderLink()

        store.last()?.let { l ->
            section("上一次")
            val how = when {
                l.reason == "completed" -> "完成"
                l.reason.startsWith("stopped") -> "主动结束"
                else -> "没给出结论"
            }
            line("${l.task.ifBlank { l.topic }}｜$how")
            line("有依据的思考 ${l.summary.grounded} 次｜回应 ${l.summary.responded} 次｜切屏 ${l.summary.interruptions} 次｜接回 ${l.summary.resumed} 次")
            l.conclusion?.let { line("结论：$it") }
        }

        val open = store.agenda().open()
        if (open.isNotEmpty()) {
            section("还挂着没解决的")
            open.take(8).forEach { line("· ${it.text}") }
            note("下次开同一个主题，第一问就会问你要不要接着做。")
        }

        section("我的主线和老毛病")
        note("驾驶员追问时会点破这些。一行一条。")
        val p = store.profile()
        val goals = input("主线目标（例：12 月省考行测）", p.goals.joinToString("\n"), multiline = true)
        val habits = input("惯性死穴（例：抓到一半就下结论）", p.habits.joinToString("\n"), multiline = true)
        button("保存") {
            fun lines(e: EditText) = e.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.take(8)
            store.saveProfile(Profile(lines(goals), lines(habits)))
            render()
        }

        section("隐私")
        note("只读当前前台应用的包名和窗口类型，不读屏幕文字、不录音。数据只存在本机 APP 目录里。")
    }

    // ───────────── 连接大肥鱼（DSH Go） ─────────────

    private fun renderLink() {
        val store = Engine.store
        section("连接大肥鱼（DSH Go）")
        val pairing = store.pairing()
        if (pairing == null) {
            note("连上之后，追问会由大肥鱼结合它的记忆来写；不连也能用，追问用模板。")
            line("① 点下面按钮复制安装说明，发给大肥鱼。它会自己装好插件，告诉你一个配对码。")
            button("复制给大肥鱼的安装说明") {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("安装说明", Install.TEXT))
                toast("已复制。打开 DSH Go，粘贴发给大肥鱼。")
            }
            line("② 把大肥鱼告诉你的配对码填在这里：")
            val code = input("配对码（例：3080:K7PQ-M3XA）")
            button("配对") {
                val text = code.text.toString()
                Thread {
                    val r = DshLink.pair(text, Build.MODEL ?: "phone")
                    r.onSuccess { store.savePairing(it) }
                    main.post {
                        toast(r.fold({ "配对成功" }, { it.message ?: "配对失败" }))
                        render()
                        checkLink()
                    }
                }.start()
            }
            return
        }
        val p = ping
        when {
            pinging && p == null -> line("正在检查连接…")
            p == null -> line("已配对（端口 ${pairing.port}）")
            !p.ok -> line("⚠ ${p.error}")
            else -> {
                line("✓ 已连上大肥鱼｜它的记忆 ${p.memories} 条")
                if (!p.brainConfigured) line("⚠ 还没指定出题的对话：在 DSH Go 里打开你想让它出题的那个对话，说一句「驾驶员就用这个会话」。")
                else line("✓ 出题对话已指定")
            }
        }
        button("检查连接") { checkLink() }
        button("解除配对") {
            AlertDialog.Builder(this).setTitle("解除和大肥鱼的配对？").setMessage("之后追问改用模板。要再连，需要让大肥鱼重新生成配对码。")
                .setPositiveButton("解除") { _, _ -> store.clearPairing(); ping = null; render() }.setNegativeButton("取消", null).show()
        }
    }

    private fun checkLink() {
        val pairing = Engine.store.pairing() ?: return
        if (pinging) return
        pinging = true
        Thread {
            val r = DshLink.ping(pairing)
            ping = r
            pinging = false
            main.post { if (!Engine.locked) render() }
        }.start()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ───────────── 小部件 ─────────────

    private fun a11yEnabled(): Boolean {
        val me = ComponentName(this, WatchService::class.java).flattenToString()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return on.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun label(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun title(t: String, size: Float) = body.addView(TextView(this).apply { text = t; textSize = size; setTypeface(typeface, Typeface.BOLD) })

    private fun section(t: String) = body.addView(TextView(this).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt())
    })

    private fun line(t: String) = body.addView(TextView(this).apply { text = t; textSize = 15f; setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt()) })

    private fun note(t: String) = body.addView(TextView(this).apply { text = t; textSize = 13f; setTextColor(0xFF6B7280.toInt()) })

    private fun check(name: String, ok: Boolean, why: String, fix: () -> Unit) {
        body.addView(TextView(this).apply { text = "${if (ok) "✓" else "✗"} $name"; textSize = 15f })
        if (!ok) {
            note(why)
            button("去打开") { fix() }
        }
    }

    private fun button(t: String, onClick: () -> Unit): View = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }.also { body.addView(it) }

    private fun input(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT, multiline: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else type
        if (multiline) minLines = 2
    }.also { body.addView(it) }
}
