package com.moling.app.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.*
import com.moling.app.ui.MolingTheme
import com.moling.core.mind.*
import kotlinx.coroutines.*

/** 普通服务，由已有的前台心跳保活。窗口仅占圆点/卡片区域，不接管其他 App。 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val saved = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var view: ComposeView? = null
    private var expanded by mutableStateOf(false)
    private var clock by mutableLongStateOf(System.currentTimeMillis())
    private lateinit var params: WindowManager.LayoutParams
    private val density get() = resources.displayMetrics.density
    private var working by mutableStateOf(false)

    companion object {
        fun show(context: Context, expand: Boolean = false) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching { context.startService(Intent(context, OverlayService::class.java).putExtra("expand", expand)) }
                .onFailure { Presence.error(context, "小窗起不来，改看通知：${it.javaClass.simpleName}"); Presence.currentSessionCard()?.let { PresenceNotices.show(context, PresenceNotices.SESSION, "正在聊天", it.ask, true) } }
        }
        fun hide(context: Context) { runCatching { context.stopService(Intent(context, OverlayService::class.java)) } }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        saved.performAttach(); saved.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        wm = getSystemService(WindowManager::class.java)
        val prefs = PresenceStore(this).prefs
        params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("dot_x", 0); y = prefs.getInt("dot_y", (160 * density).toInt())
        }
        view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) { expanded = false; move(0f, 0f, true); false } else false
            }
            setContent { MolingTheme { Content() } }
        }
        runCatching { wm.addView(view, params) }.onFailure { Presence.error(this, "悬浮窗不可用：${it.javaClass.simpleName}"); stopSelf() }
        scope.launch {
            while (isActive) {
                clock = System.currentTimeMillis()
                val q = Presence.question.value
                if (q != null && clock >= q.until) Presence.timeoutQuestion(this@OverlayService)
                if (!Settings.canDrawOverlays(this@OverlayService)) { stopSelf(); break }
                delay(1000)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("expand", false) == true) expandWindow()
        return START_NOT_STICKY
    }
    private fun expandWindow() {
        params.x = params.x.coerceAtMost((resources.displayMetrics.widthPixels - 320 * density).toInt().coerceAtLeast(0))
        params.y = params.y.coerceAtMost((resources.displayMetrics.heightPixels - 450 * density).toInt().coerceAtLeast(0))
        move(0f, 0f)
        expanded = true
    }
    private fun move(dx: Float, dy: Float, snap: Boolean = false) {
        val metrics = resources.displayMetrics
        params.x = (params.x + dx).toInt().coerceIn(0, (metrics.widthPixels - 44 * density).toInt().coerceAtLeast(0))
        params.y = (params.y + dy).toInt().coerceIn(0, (metrics.heightPixels - 60 * density).toInt().coerceAtLeast(0))
        if (snap) params.x = if (params.x < metrics.widthPixels / 2) 0 else (metrics.widthPixels - 44 * density).toInt()
        runCatching { wm.updateViewLayout(view, params) }
        if (snap) PresenceStore(this).prefs.edit().putInt("dot_x", params.x).putInt("dot_y", params.y).apply()
    }
    @Composable private fun Content() {
        val session by Presence.session.collectAsState()
        val director by Presence.card.collectAsState()
        val question by Presence.question.collectAsState()
        if (!expanded) {
            val pulse by rememberInfiniteTransition(label = "墨灵在").animateFloat(.88f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "呼吸")
            Box(Modifier.size(44.dp).pointerInput(Unit) {
                detectDragGestures(onDragEnd = { move(0f, 0f, true) }) { change, delta -> change.consume(); move(delta.x, delta.y) }
            }.background(MaterialTheme.colorScheme.primary.copy(alpha = pulse), CircleShape).clickable {
                expandWindow()
            }, contentAlignment = Alignment.Center) { Text("墨", color = MaterialTheme.colorScheme.onPrimary) }
            return
        }
        Surface(Modifier.widthIn(max = 320.dp).heightIn(max = 450.dp), shape = MaterialTheme.shapes.large, shadowElevation = 6.dp) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = false; move(0f, 0f, true) }) { Text("收起") }
                val q = question
                if (q != null) {
                    Text(if (q.followup) "10 分钟到了，回「${q.channel.title}」吗？" else "现在是「${q.channel.title}」的时间。")
                    Text("${((q.until - clock).coerceAtLeast(0) + 999) / 1000} 秒", style = MaterialTheme.typography.labelSmall)
                    action("先做 15 分钟") { Presence.choose(this@OverlayService, "A") }
                    action("我卡住了") { Presence.choose(this@OverlayService, "B") }
                    if (Presence.canExtend(this@OverlayService)) action(if (q.followup) "再玩 10 分钟" else "我就玩 10 分钟") { Presence.choose(this@OverlayService, "C") }
                } else if (session != null) {
                    val s = session!!; val c = Presence.currentSessionCard()
                    Text("已聊 ${(clock - s.startedAt).coerceAtLeast(0) / 60_000} 分钟 / 共 ${s.opening.minutes} 分钟")
                    c?.let { Text(it.title, style = MaterialTheme.typography.titleMedium); Text(it.ask); it.sayLines.forEach { line -> Text(line) } }
                    TextButton(onClick = { Presence.nextQuestion(this@OverlayService) }) { Text("下一问") }
                    action("卡住了") { c?.let { Presence.respond(this@OverlayService, Reply.Stuck(it.id, clock)) } }
                    TextButton(onClick = { PresenceActivity.open(this@OverlayService, "conclude") }) { Text("收线") }
                } else director?.let { c ->
                    Text(c.title, style = MaterialTheme.typography.titleMedium); Text(c.ask)
                    c.sayLines.forEach { Text(it) }
                    if (c.choices.isNotEmpty()) c.choices.forEach { choice -> action(choice.label) { Presence.respond(this@OverlayService, Reply.Pick(c.id, clock, choice.key)) } }
                    else {
                        action("继续") { Presence.respond(this@OverlayService, Reply.Continue(c.id, clock)) }
                        action("卡住了") { Presence.respond(this@OverlayService, Reply.Stuck(c.id, clock)) }
                        TextButton(onClick = { PresenceActivity.open(this@OverlayService, "reply") }) { Text("有结论") }
                        action("休息") { Presence.respond(this@OverlayService, Reply.Rest(c.id, clock)) }
                    }
                    action("不用") { Presence.respond(this@OverlayService, Reply.Dismissed(c.id, clock)) }
                }
            }
        }
    }
    @Composable private fun action(label: String, block: suspend () -> Unit) {
        TextButton(enabled = !working, onClick = {
            working = true
            Presence.perform(applicationContext, block) { working = false; expanded = false }
        }) { Text(if (working) "在做…" else label) }
    }
    override fun onDestroy() {
        scope.cancel()
        runCatching { view?.let { wm.removeViewImmediate(it) } }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        view = null
        super.onDestroy()
    }
}
