package com.dshdriver.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dshdriver.core.Phase
import com.dshdriver.core.Profile

/**
 * 首页：现在的状态、开始陪练、拦截娱乐 App、要的权限、连接大肥鱼、我的主线、上一次。
 *
 * 界面全部用代码搭，不依赖 androidx 库；样式集中在 [Ui]。
 * 页面按"状态切换时重建、每秒只改几个字"来做，倒计时不会让整页闪一下、也不会把滚动位置弹回顶部。
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var scroller: ScrollView
    private lateinit var body: LinearLayout

    /** 上一次检查连接的结果（后台线程查，主线程显示）。 */
    @Volatile private var ping: DshLink.Ping? = null
    @Volatile private var pinging = false

    /** 已经画出来的那一版是"陪练中"还是"待机"，只在切换时重建整页。 */
    private var builtLocked: Boolean? = null

    // 陪练中每秒会变的地方：只改文字，不重建
    private var clock: TextView? = null
    private var phaseChip: TextView? = null
    private var meter: Bar? = null
    private var statGrounded: TextView? = null
    private var statResponded: TextView? = null
    private var askBlock: LinearLayout? = null
    private var askText: TextView? = null

    private val refresher = object : Runnable {
        override fun run() {
            render()
            if (Engine.locked) main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this, Palette.light)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(20), ui.px(26), ui.px(20), ui.px(40))
            setBackgroundColor(Palette.light.bg)
        }
        scroller = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.light.bg)
            addView(body)
        }
        setContentView(scroller)
        window.statusBarColor = Palette.light.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Engine.onChange = { render(); if (Engine.locked) { main.removeCallbacks(refresher); main.post(refresher) } }
        // 先自愈再渲染：无障碍被关掉时，"写回去"和"显示成已开"要在同一次刷新里完成，
        // 否则会先闪一下"未开"，让人以为还得手点一次
        A11yGuard.ensure(this)
        WatchdogJob.schedule(this)
        render(force = true)
        main.removeCallbacks(refresher)
        main.post(refresher)
        checkLink()
    }

    override fun onPause() {
        Engine.onChange = null
        main.removeCallbacks(refresher)
        super.onPause()
    }

    // ───────────── 页面骨架 ─────────────

    private fun render(force: Boolean = false) {
        val locked = Engine.locked
        if (force || builtLocked != locked || body.childCount == 0) rebuild(locked)
        refreshLive()
    }

    private fun rebuild(locked: Boolean) {
        val y = scroller.scrollY
        builtLocked = locked
        clock = null; phaseChip = null; meter = null
        statGrounded = null; statResponded = null; askBlock = null; askText = null
        body.removeAllViews()
        ui.reset(body)

        header(locked)
        if (locked) session() else idle()
        ui.footnote("只读当前前台应用的包名和窗口类型，不读屏幕文字、不录音。所有数据只留在本机 files/driver/。")
        // 重建后把滚动位置放回去，别让人以为页面跳了
        main.post { scroller.scrollTo(0, y) }
    }

    private fun header(locked: Boolean) {
        val s = Engine.snapshot
        ui.kicker("思维驾驶员")
        ui.headline(
            when {
                !locked -> "先钉住一件事"
                else -> s?.let { it.task.ifBlank { it.topic } }?.ifBlank { "陪练中" } ?: "陪练中"
            },
        )
        ui.lead(
            if (locked) "断点在这儿，接回去就好，不用一次想完。"
            else "开着 Gemini Live 学习时，我在顶部浮一张小卡追问你。不抢你的屏，不判你切走。",
        )
    }

    // ───────────── 陪练中 ─────────────

    private fun session() {
        val s = Engine.snapshot ?: return
        ui.card {
            phaseChip = ui.chip(phaseLabel(s.phase), phaseTone(s.phase), align = Gravity.START)
            clock = ui.display("0:00", 40f)
            ui.caption("剩余时间")
            meter = ui.meter().also { b ->
                b.trackColor = Palette.light.border
                b.fillColor = Palette.light.accent
            }
            askBlock = ui.card(muted = true) {
                ui.kicker("这一问")
                askText = ui.text("", 16f, Palette.light.text, top = 2f, bottom = 0f)
            }.also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = ui.px(14) }
            ui.spacer(6f)
            ui.stats(listOf("0" to "有依据", "0" to "回应")).also {
                statGrounded = it[0]
                statResponded = it[1]
            }
            ui.primary("回到 Live") { Engine.relaunchAnchor(this) }
            ui.row {
                ui.cell(weight = 1f, right = 5f) {
                    ui.secondary("休息…") { askPause() }
                }
                ui.cell(weight = 1f) {
                    ui.quiet("紧急结束", Palette.light.danger) { confirmStop() }
                }
            }
        }
    }

    private fun refreshLive() {
        if (!Engine.locked) return
        val s = Engine.snapshot ?: return
        val now = System.currentTimeMillis()
        val total = (s.endsAt - s.startedAt).coerceAtLeast(1L)
        val left = ((s.endsAt - now) / 1000).coerceAtLeast(0)
        clock?.text = "${left / 60}:${"%02d".format(left % 60)}"
        meter?.fraction = ((now - s.startedAt).toFloat() / total).coerceIn(0f, 1f)
        phaseChip?.let { ui.styleChip(it, phaseLabel(s.phase), phaseTone(s.phase)) }
        statGrounded?.text = s.summary.grounded.toString()
        statResponded?.text = s.summary.responded.toString()
        val card = s.card?.takeIf { !it.answered }
        askBlock?.visibility = if (card == null) View.GONE else View.VISIBLE
        askText?.text = card?.text.orEmpty()
    }

    private fun phaseLabel(phase: Phase): String = when (phase) {
        Phase.LOCKED -> "进行中"
        Phase.INTERRUPTED_BY_SWITCH -> "等你接回断点"
        Phase.PAUSED -> "休息中"
        Phase.WRAPPING -> "等一句结论"
        Phase.ENDED -> "已结束"
        Phase.IDLE -> "待机"
    }

    private fun phaseTone(phase: Phase): Tone = when (phase) {
        Phase.LOCKED -> Tone.OK
        Phase.PAUSED, Phase.ENDED, Phase.IDLE -> Tone.NEUTRAL
        else -> Tone.WARN
    }

    // ───────────── 未在陪练 ─────────────

    private fun idle() {
        val store = Engine.store
        val settings = store.settings()

        ui.tiles(
            listOf(
                Tile("拦截", blockTileValue(), blockTileTone()),
                Tile("大脑", linkTileValue(), linkTileTone()),
                Tile("无障碍", if (A11yGuard.isOn(this)) "已开" else "未开", if (A11yGuard.isOn(this)) Tone.OK else Tone.BAD),
            ),
        )

        startCard(settings.minutes, store)
        blockingCard()
        guardCard()
        permissionCard()
        linkCard()
        profileCard()
        historyCards()
        diagnosticsCard()
    }

    private fun startCard(minutes: Int, store: Store) {
        val options = listOf(10, 15, 25, 45)
        val last = store.last()
        ui.card(title = "开始一次陪练") {
            ui.caption("开着 Gemini Live，正常学。卡片浮在上面问一句，不会盖住屏幕。")
            ui.spacer(8f)
            val topic = ui.field("主题，例：行测 · 图形推理", last?.topic.orEmpty())
            val task = ui.field("这一节只钉一件事，例：第 3 题", "")
            ui.caption("多久")
            var pick = options.indexOf(minutes).takeIf { it >= 0 } ?: 1
            ui.choice(options.map { "$it 分钟" }, pick) { i -> pick = i }
            ui.primary("开始陪练") {
                val m = options[pick]
                store.saveSettings(store.settings().copy(minutes = m))
                Engine.start(topic.text.toString().trim(), task.text.toString().trim(), m)
                main.postDelayed({ Engine.relaunchAnchor(this) }, 600)
            }
        }
    }

    // ───────────── 拦截娱乐 App（内置，不依赖外部拦截器） ─────────────

    private fun blockTileValue(): String {
        val b = Engine.store.blocking()
        return when {
            !b.enabled -> "已关"
            b.blocked.isEmpty() -> "没选 App"
            !A11yGuard.isOn(this) -> "无障碍没开"
            else -> "拦 ${b.blocked.size} 个"
        }
    }

    private fun blockTileTone(): Tone {
        val b = Engine.store.blocking()
        return when {
            !b.enabled -> Tone.NEUTRAL
            !A11yGuard.isOn(this) -> Tone.BAD
            b.blocked.isEmpty() -> Tone.WARN
            else -> Tone.OK
        }
    }

    private fun blockingCard() {
        val b = Engine.store.blocking()
        val a11y = A11yGuard.isOn(this)
        ui.card(
            title = "拦截娱乐 App",
            chip = if (b.enabled) "开着" else "关着",
            tone = if (b.enabled && a11y) Tone.OK else Tone.WARN,
        ) {
            ui.caption("打开下面这些 App 时，我把你送回桌面、弹一道题；答对才放行一段时间。拦截和判卷都在本 APP 里，不用再装别的拦截器。")
            ui.spacer(6f)
            ui.choice(listOf("关", "开"), if (b.enabled) 1 else 0) { i ->
                Engine.store.saveBlocking(b.copy(enabled = i == 1))
                Intercept.invalidate()
                render(force = true)
            }
            ui.divider()
            val names = b.blocked.map { label(it) }
            ui.text(
                if (names.isEmpty()) "还没选要拦的 App。"
                else "拦着 ${names.size} 个：${names.take(4).joinToString("、")}${if (names.size > 4) " 等" else ""}",
                14f, Palette.light.text, top = 2f,
            )
            ui.secondary(if (names.isEmpty()) "选择要拦的 App" else "改一下拦哪些") { pickApps() }
            ui.divider()
            ui.caption("答对之后放行多久")
            val options = listOf(5, 10, 20, 30)
            ui.choice(options.map { "$it 分钟" }, options.indexOf(b.minutes).takeIf { it >= 0 } ?: 1) { i ->
                Engine.store.saveBlocking(Engine.store.blocking().copy(minutes = options[i]))
                Intercept.invalidate()
            }
            if (b.enabled && !a11y) ui.callout("无障碍没开，现在拦不住任何东西。", Tone.BAD)
        }
    }

    private fun pickApps() {
        val apps = launchableApps()
        if (apps.isEmpty()) { toast("没找到可启动的 App"); return }
        val blocked = Engine.store.blocking().blocked.toSet()
        val checked = BooleanArray(apps.size) { apps[it].first in blocked }
        AlertDialog.Builder(this)
            .setTitle("选择要拦的 App")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("保存") { _, _ ->
                val picked = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }
                Engine.store.saveBlocking(Engine.store.blocking().copy(blocked = picked))
                Intercept.invalidate()
                render(force = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchableApps(): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                pkg to runCatching { ri.loadLabel(packageManager).toString() }.getOrDefault(pkg)
            }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    // ───────────── 别让它被系统关掉（无障碍自愈） ─────────────

    private fun guardCard() {
        val on = A11yGuard.isOn(this)
        val canWrite = A11yGuard.canWriteDirectly(this)
        val shOn = Shell.available()
        val shGranted = Shell.granted()
        ui.card(
            title = "别让它被系统关掉",
            chip = if (on) "已开" else "未开",
            tone = if (on && (canWrite || shGranted)) Tone.OK else if (on) Tone.WARN else Tone.BAD,
        ) {
            ui.caption("无障碍是绑在进程上的：被划掉或被强制停止后，系统会把它从已启用列表里删掉。下面三层决定它多久能自己回来。")
            ui.permission("无障碍服务", on, "拦截靠它读前台包名；关着就完全不拦。") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            ui.divider()
            ui.permission(
                "写系统设置", canWrite,
                if (shGranted) "还没授予。下面那个按钮可以借 Shizuku 一次授予，之后就再也不麻烦它了。"
                else "没授予，Shizuku 也没连上，所以现在只能靠手动重开。",
            ) {
                if (shGranted) {
                    A11yGuard.harden(this) { ok ->
                        main.post {
                            toast(if (ok) "已授予，以后能自己写回无障碍" else "授权失败，看看 Shizuku 还连着没有")
                            render(force = true)
                        }
                    }
                } else toast("先打开 Shizuku 并授权")
            }
            ui.divider()
            ui.permission(
                "Shizuku", shGranted,
                if (!shOn) "没连上：重启手机后要在 Shizuku 里重新启动一次服务。" else "已连上，还没授权给本 APP。",
            ) {
                if (shOn) { Shell.request(); main.postDelayed({ render(force = true) }, 1_500) } else toast("先打开 Shizuku")
            }
            ui.spacer(6f)
            ui.callout(A11yGuard.describe(this), if (on && (canWrite || shGranted)) Tone.OK else Tone.WARN)
            ui.footnote("看门狗每 15 分钟查一次；开机、覆盖安装后也会自动写回。强制停止打不过，只能等下次打开 APP。")
        }
    }

    // ───────────── 要的权限 ─────────────

    // ───────────── 权限 ─────────────

    private fun permissionCard() {
        val pm = getSystemService(PowerManager::class.java)
        val settings = Engine.store.settings()
        val installed = settings.anchors.filter { Engine.isInstalled(this, it) }
        ui.card(title = "要的权限") {
            ui.permission(
                "悬浮窗", Settings.canDrawOverlays(this),
                "用来把卡片浮在 Live 上面；没有也能用，只是改成普通页面。",
            ) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            ui.divider()
            ui.permission(
                "不被省电杀掉", pm?.isIgnoringBatteryOptimizations(packageName) == true,
                "陪练要在后台一直跑。小米 / 华为还要在系统设置里手动开「自启动」「后台弹出界面」。",
            ) {
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            }
            ui.divider()
            ui.text(
                if (installed.isEmpty()) "「回到 Live」暂时没有可打开的应用，装 Gemini 或 ChatGPT 后自动认。"
                else "「回到 Live」会打开 ${label(installed.first())}。",
                13f, Palette.light.textSecondary, bottom = 0f,
            )
        }
    }

    // ───────────── 连接大肥鱼（DSH Go） ─────────────

    private fun linkTileValue(): String {
        val pairing = Engine.store.pairing() ?: return "未配对"
        val p = ping ?: return if (pinging) "检查中" else "已配对"
        return if (p.ok) "已连上" else "连不上"
    }

    private fun linkTileTone(): Tone {
        Engine.store.pairing() ?: return Tone.NEUTRAL
        val p = ping ?: return Tone.NEUTRAL
        return if (p.ok) Tone.OK else Tone.BAD
    }

    private fun linkCard() {
        val store = Engine.store
        val pairing = store.pairing()
        val p = ping
        ui.card(
            title = "大肥鱼（可选）",
            chip = when {
                pairing == null -> "未配对"
                p?.ok == true -> "已连上"
                p == null -> "已配对"
                else -> "连不上"
            },
            tone = when {
                pairing == null || p == null -> Tone.NEUTRAL
                p.ok -> Tone.OK
                else -> Tone.BAD
            },
        ) {
            if (pairing == null) {
                ui.caption("连上之后追问由它结合自己的记忆来写；不连也能用，追问用模板。")
                ui.text("① 复制安装说明，发给大肥鱼。它会自己装好插件，回你一个配对码。", 13.5f, Palette.light.textSecondary, top = 8f)
                ui.secondary("复制安装说明") {
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("安装说明", Install.TEXT))
                    toast("已复制。打开 DSH Go，粘贴发给大肥鱼。")
                }
                ui.spacer(6f)
                ui.caption("② 把配对码填在这里")
                val code = ui.field("例：3080:K7PQ-M3XA")
                ui.primary("配对") {
                    val text = code.text.toString()
                    Thread {
                        val r = DshLink.pair(text, Build.MODEL ?: "phone")
                        r.onSuccess { store.savePairing(it) }
                        main.post {
                            toast(r.fold({ "配对成功" }, { it.message ?: "配对失败" }))
                            render(force = true)
                            checkLink()
                        }
                    }.start()
                }
                return@card
            }
            when {
                pinging && p == null -> ui.caption("正在检查连接…")
                p == null -> ui.text("已配对，端口 ${pairing.port}。", 13.5f, Palette.light.textSecondary)
                !p.ok -> ui.callout(p.error.orEmpty().ifBlank { "连不上大肥鱼" }, Tone.BAD)
                else -> {
                    ui.callout("已连上，它的记忆 ${p.memories} 条。", Tone.OK)
                    if (p.brainConfigured) {
                        ui.text("出题对话已指定。", 13.5f, Palette.light.textSecondary)
                    } else {
                        ui.callout("还没指定出题的对话：在 DSH Go 里打开想让它出题的那个对话，说一句「驾驶员就用这个会话」。", Tone.WARN)
                    }
                }
            }
            ui.secondary("检查连接") { checkLink() }
            ui.quiet("解除配对", Palette.light.danger) {
                AlertDialog.Builder(this).setTitle("解除和大肥鱼的配对？")
                    .setMessage("之后追问改用模板。要再连，需要让大肥鱼重新生成配对码。")
                    .setPositiveButton("解除") { _, _ -> store.clearPairing(); ping = null; render(force = true) }
                    .setNegativeButton("取消", null).show()
            }
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
            main.post { if (!Engine.locked) render(force = true) }
        }.start()
    }

    // ───────────── 主线与死穴 ─────────────

    private fun profileCard() {
        val p = Engine.store.profile()
        ui.card(title = "我的主线和老毛病") {
            ui.caption("驾驶员追问时会点破这两样。一行一条。")
            ui.spacer(8f)
            val goals = ui.field("主线目标，例：12 月省考行测", p.goals.joinToString("\n"), multiline = true, lines = 2)
            ui.spacer(4f)
            val habits = ui.field("惯性死穴，例：抓到一半就下结论", p.habits.joinToString("\n"), multiline = true, lines = 2)
            ui.secondary("保存") {
                fun lines(e: EditText) = e.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.take(8)
                Engine.store.saveProfile(Profile(lines(goals), lines(habits)))
                toast("已保存")
                render(force = true)
            }
        }
    }

    // ───────────── 上一次 + 还挂着的 ─────────────

    private fun historyCards() {
        val store = Engine.store
        store.last()?.let { l ->
            val how = when {
                l.reason == "completed" -> "完成"
                l.reason.startsWith("stopped") -> "主动结束"
                else -> "没给出结论"
            }
            ui.card(title = "上一次", chip = how, tone = if (l.reason == "completed") Tone.OK else Tone.NEUTRAL) {
                ui.text(l.task.ifBlank { l.topic }.ifBlank { "没写主题" }, 15.5f, Palette.light.text, bottom = 2f)
                ui.spacer(6f)
                ui.stats(listOf("${l.summary.grounded}" to "有依据", "${l.summary.responded}" to "回应"))
                l.conclusion?.takeIf { it.isNotBlank() }?.let { ui.callout(it, Tone.NEUTRAL) }
            }
        }
        val open = store.agenda().open()
        if (open.isNotEmpty()) {
            ui.card(title = "还挂着没解决的", chip = "${open.size} 条", tone = Tone.NEUTRAL) {
                open.take(6).forEach { item -> ui.item(item.text, item.anchor.takeIf { a -> a.isNotBlank() }) }
                if (open.size > 6) ui.caption("另外还有 ${open.size - 6} 条，下次开同一主题会先问起。")
                else ui.caption("下次开同一个主题，第一问就会问你要不要接着做。")
            }
        }
    }

    // ───────────── 诊断 ─────────────

    private fun diagnosticsCard() {
        ui.card(title = "诊断", muted = true) {
            if (!A11yGuard.isOn(this)) {
                ui.caption("打开「思维驾驶员 · 前台记录」后，这里会列出最近读到的前台应用。只用来排查误判，不拦截任何东西。")
                ui.quiet("去打开无障碍", Palette.light.accent) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                return@card
            }
            val list = Engine.recentForeground()
            if (list.isEmpty()) {
                ui.caption("还没有记录。切几个应用再回来。")
                return@card
            }
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            list.take(8).forEach { seen ->
                ui.text("${fmt.format(java.util.Date(seen.at))}   ${label(seen.pkg)}", 12.5f, Palette.light.textSecondary, bottom = 1f)
            }
        }
    }

    // ───────────── 对话框 / 杂项 ─────────────

    private fun askPause() {
        val m = intArrayOf(3, 5, 10, 20)
        AlertDialog.Builder(this).setTitle("休息多久？到点会把你拉回断点")
            .setItems(m.map { "$it 分钟" }.toTypedArray()) { _, i -> Engine.pause(m[i]) }.show()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this).setTitle("结束这次陪练？")
            .setMessage("会记为主动结束，不算完成。")
            .setPositiveButton("结束") { _, _ -> Engine.stop("emergency") }
            .setNegativeButton("不结束", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun label(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
