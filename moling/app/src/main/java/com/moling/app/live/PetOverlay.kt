package com.moling.app.live

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.moling.app.BrainHost
import com.moling.app.ui.Ink
import com.moling.app.ui.MolingFace
import com.moling.app.ui.MolingTheme
import com.moling.app.ui.ChessBait
import com.moling.app.ui.ChessHeader
import com.moling.app.ui.ChessScore
import com.moling.app.ui.Paper
import com.moling.app.ui.Seal
import com.moling.app.ui.art.InkRing
import com.moling.core.live.Modality
import com.moling.core.live.Mood
import com.moling.core.live.Reaction
import com.moling.core.spar.Chess
import com.moling.core.spar.Chesses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.pow

/*
 * 第六批 A 组：墨灵在他面前出现的几种样子。
 *
 *  - Z1 桌宠：只占墨滴 + 气泡那么大的小窗，从屏幕右边探头、落地、说一句；点她开卡片，拖走 / 长按 / 12 秒没人碰就收起。
 *  - Z2 盯着你：中上部 2 倍大的墨滴 + 45 秒圆环，不说话；点她才冒气泡并开卡片。
 *  - Z4 参谋弹幕：整屏但**不可触摸**的窗口（alpha = 0.8，Android 12 起的要求），四行从右往左飘，飘完桌宠带着“合起来”出现。
 *  - Z6 褪色：整屏不可触摸的灰层，10 分钟从 0 升到 0.35；每天按北京时间记分钟数（上限 20）。
 *  - Z8 演员胶囊：小胶囊，一问 + mm:ss，点开两行，可拖，每心跳更新一次。
 *  - Z3 耳语要用的“回她”小气泡（只说 8 秒，点了由 peek 接手）。
 *
 * 全部装在一个普通 Service 里，每种样子一个独立窗口（互不影响，可共存）。
 * 没有“显示在其他应用上层”权限时，[PetOverlay] 的每个方法都静默返回：不起服务、不崩、不提示。
 * 权限中途被收回、窗口加不上（厂商限制）、服务被系统杀掉，也都只记一行日志。
 */
object PetOverlay {
    private const val TAG = "PetOverlay"

    /** Z1 桌宠：墨滴从屏幕右边弹出 + 头顶气泡说一句；whisper=true 时是 Z3 的 8 秒“回她”气泡（不弹跳，只亮着）。 */
    fun show(context: Context, mood: Mood, line: String, whisper: Boolean = false) {
        // D2：心跳在 Appear.show 之前把这一轮的选项放进了 LiveScreens.nextChoices。
        // 这里只取一份副本、不清空——他点气泡本身打开卡片时，peek 还要用它。
        val choices = LiveScreens.nextChoices
        val kind = BrainHost.state.value.lastPlay?.kind.orEmpty()
        start(context, ACTION_PET) {
            it.putExtra(EXTRA_MOOD, mood.name)
            it.putExtra(EXTRA_LINE, line)
            it.putExtra(EXTRA_WHISPER, whisper)
            it.putStringArrayListExtra(EXTRA_CHOICES, ArrayList(choices))
            it.putExtra(EXTRA_KIND, kind)
        }
    }

    /** Z2 盯着你：中上部 2 倍大墨滴 + 45 秒圆环，不说话。 */
    fun stare(context: Context, mood: Mood, line: String) {
        val choices = LiveScreens.nextChoices
        start(context, ACTION_STARE) {
            it.putExtra(EXTRA_MOOD, mood.name)
            it.putExtra(EXTRA_LINE, line)
            it.putStringArrayListExtra(EXTRA_CHOICES, ArrayList(choices))
        }
    }

    /** Z4 参谋弹幕：lines 形如 listOf("教练：…","军师：…","对手：…","朋友：…")，从右往左飘，之后桌宠带 together 出来。 */
    fun council(context: Context, lines: List<String>, mood: Mood, together: String) {
        val choices = LiveScreens.nextChoices
        val kind = BrainHost.state.value.lastPlay?.kind.orEmpty()
        start(context, ACTION_COUNCIL) {
            it.putStringArrayListExtra(EXTRA_LINES, ArrayList(lines))
            it.putExtra(EXTRA_MOOD, mood.name)
            it.putExtra(EXTRA_TOGETHER, together)
            it.putStringArrayListExtra(EXTRA_CHOICES, ArrayList(choices))
            it.putExtra(EXTRA_KIND, kind)
        }
    }

    /** Z6 褪色：level = 0 收起；> 0 显示整屏灰层，透明度从 0 在 10 分钟里升到 0.35*level。 */
    fun fade(context: Context, level: Float) {
        if (!canDraw(context)) return
        // 没在褪色又收到 0：不要为了“什么都不用做”把服务拉起来。
        if (level <= 0f && !PetOverlayService.fadeActive) return
        start(context, ACTION_FADE) { it.putExtra(EXTRA_LEVEL, level) }
    }

    /** 今天已经褪色多少分钟（按日期存 SharedPreferences，给 Lead 判 20 分钟上限）。 */
    fun fadeMinutesToday(context: Context): Long = runCatching {
        overlayPrefs(context).getLong(FADE_PREFIX + beijingDay(), 0L).coerceAtLeast(0L)
    }.getOrDefault(0L)

    /** Z1 长按后一小时内不再用桌宠（Appear 会改成底部卡片）。只影响 PET，不影响 STARE / COUNCIL / WHISPER。 */
    fun petMuted(context: Context): Boolean = runCatching {
        overlayPrefs(context).getLong(KEY_PET_MUTE, 0L) > System.currentTimeMillis()
    }.getOrDefault(false)

    /** Z8 小胶囊：line（≤16 字）+ 从 startedAt 起走过的 mm:ss；点一下展开成两行，再点收起；可拖。 */
    fun capsule(context: Context, line: String, startedAt: Long) {
        start(context, ACTION_CAPSULE) {
            it.putExtra(EXTRA_LINE, line)
            it.putExtra(EXTRA_STARTED, startedAt)
        }
    }

    fun hideCapsule(context: Context) {
        if (!canDraw(context)) return
        if (!PetOverlayService.capsuleActive) return
        start(context, ACTION_HIDE_CAPSULE)
    }

    /** 收起桌宠 / 盯着 / 弹幕窗（褪色和胶囊不受影响）。 */
    fun hide(context: Context) {
        if (!canDraw(context)) return
        if (!PetOverlayService.alive) return
        start(context, ACTION_HIDE)
    }

    // ------------------------------------------------------------------ 内部

    private fun canDraw(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /** 起服务；没有权限、后台被限、服务被厂商拦都只记日志，不影响调用方。 */
    private fun start(context: Context, action: String, fill: (Intent) -> Unit = {}) {
        if (!canDraw(context)) return
        val app = context.applicationContext
        runCatching {
            app.startService(Intent(app, PetOverlayService::class.java).setAction(action).also(fill))
        }.onFailure { Log.w(TAG, "悬浮窗服务没有起来（静默降级）：${it.javaClass.simpleName}") }
    }
}

// ---------------------------------------------------------------------- 文件内部的常量 / 小工具

private const val TAG = "PetOverlay"
private const val OVERLAY_PREFS = "pet_overlay"
private const val KEY_PET_X = "pet_x"
private const val KEY_PET_Y = "pet_y"
private const val KEY_PET_MUTE = "pet_mute_until"
private const val KEY_CAPSULE_X = "capsule_x"
private const val KEY_CAPSULE_Y = "capsule_y"
private const val FADE_PREFIX = "fade_"
private const val FADE_DAILY_MAX = 20L

private const val ACTION_PET = "com.moling.app.pet.SHOW"
private const val ACTION_STARE = "com.moling.app.pet.STARE"
private const val ACTION_COUNCIL = "com.moling.app.pet.COUNCIL"
private const val ACTION_FADE = "com.moling.app.pet.FADE"
private const val ACTION_CAPSULE = "com.moling.app.pet.CAPSULE"
private const val ACTION_HIDE_CAPSULE = "com.moling.app.pet.HIDE_CAPSULE"
private const val ACTION_HIDE = "com.moling.app.pet.HIDE"

private const val EXTRA_MOOD = "mood"
private const val EXTRA_LINE = "line"
private const val EXTRA_WHISPER = "whisper"
private const val EXTRA_LINES = "lines"
private const val EXTRA_TOGETHER = "together"
private const val EXTRA_LEVEL = "level"
private const val EXTRA_STARTED = "started"
private const val EXTRA_CHOICES = "choices"
private const val EXTRA_KIND = "kind"

private val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

private fun overlayPrefs(context: Context) =
    context.applicationContext.getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)

/** 按北京时间算“今天”，褪色分钟数用它当 SharedPreferences 的键。 */
private fun beijingDay(now: Long = System.currentTimeMillis()): String =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), BEIJING).toLocalDate().toString()

private fun clockText(ms: Long): String {
    val total = (ms / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(total / 60L, total % 60L)
}

private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

// ---------------------------------------------------------------------- 服务

/**
 * 一个 Service 管着所有悬浮窗：桌宠、盯着你、参谋弹幕、褪色、演员胶囊。
 * 重复调用同一种样子时先拆旧窗口再建新窗口；褪色和胶囊是按状态更新（幂等）。
 */
class PetOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        /** 服务在不在跑（object 侧用来避免为“什么都不用做”的调用把服务拉起来）。 */
        @Volatile
        internal var alive = false

        /** 褪色窗口现在开没开。 */
        @Volatile
        internal var fadeActive = false

        /** 胶囊窗口现在在不在。 */
        @Volatile
        internal var capsuleActive = false
    }

    // Compose 在 Service 里需要 LifecycleOwner / SavedStateRegistryOwner（照 overlay/OverlayService.kt 的写法）。
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val saved = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var lastStartId = 0

    private val density get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    private fun px(dp: Float): Int = (dp * density).toInt()

    // ---------------- 桌宠（Z1 / Z3 气泡 / Z4 收尾共用）
    private var petView: ComposeView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var petEntranceJob: Job? = null
    private var petTimeoutJob: Job? = null
    private var petShownAt = 0L
    private var petMood by mutableStateOf(Mood.CALM)
    private var petLine by mutableStateOf("")
    private var petWhisper by mutableStateOf(false)
    private var petModality by mutableStateOf(Modality.PET)
    private var petLanded by mutableStateOf(false)
    /** D2：气泡里可点的选项（破雾 / 盲盒 / 小剧场 / 赌）。 */
    private var petChoices by mutableStateOf<List<String>>(emptyList())
    /** D3：这一次是盲盒 / 小剧场 / 赌（空 = 普通）。 */
    private var petKind by mutableStateOf("")
    /** E1 三手棋：正在下的一局 / 收官分数 / 新结论。 */
    private var petChess by mutableStateOf<Chess?>(null)
    private var petChessScore by mutableIntStateOf(-1)
    private var petChessConclusion by mutableStateOf("")

    // ---------------- 盯着你（Z2）
    private var stareView: ComposeView? = null
    private var stareParams: WindowManager.LayoutParams? = null
    private var stareJob: Job? = null
    private var stareShownAt = 0L
    private var stareMood by mutableStateOf(Mood.CALM)
    private var stareLine by mutableStateOf("")
    private var stareBubble by mutableStateOf(false)
    private var stareLeaving by mutableStateOf(false)
    /** D2：盯着你时点出来的气泡里也可点的选项。 */
    private var stareChoices by mutableStateOf<List<String>>(emptyList())

    // ---------------- 参谋弹幕（Z4）
    private var councilView: ComposeView? = null
    private var councilParams: WindowManager.LayoutParams? = null
    private var councilJob: Job? = null
    private var councilLines by mutableStateOf<List<String>>(emptyList())

    // ---------------- 褪色（Z6）
    private var fadeView: ComposeView? = null
    private var fadeParams: WindowManager.LayoutParams? = null
    private var fadeJob: Job? = null
    private var fadeTarget by mutableStateOf(0f)

    // ---------------- 演员胶囊（Z8）
    private var capsuleView: ComposeView? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var capsuleLine by mutableStateOf("")
    private var capsuleStartedAt by mutableLongStateOf(0L)
    private var capsuleExpanded by mutableStateOf(false)
    private var capsulePlaced = false
    private var capsuleMeasuredWidth = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        saved.performAttach()
        saved.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val windowManager: WindowManager? = getSystemService(WindowManager::class.java)
        if (windowManager == null) {
            // 理论上不会发生；真拿不到就安静退出，不崩。
            stopSelf()
            return
        }
        wm = windowManager
        // 权限中途被收回：清干净再退，别留下看不见的窗口。
        scope.launch {
            while (isActive) {
                delay(2_000)
                if (!runCatching { Settings.canDrawOverlays(this@PetOverlayService) }.getOrDefault(false)) {
                    detachAllWindows()
                    stopSelf()
                    break
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        runCatching { handle(intent) }
            .onFailure { Log.w(TAG, "悬浮窗动作失败：${it.javaClass.simpleName}", it) }
        return START_NOT_STICKY
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_PET -> {
                val whisper = intent.getBooleanExtra(EXTRA_WHISPER, false)
                startPet(
                    mood = moodOf(intent),
                    line = intent.getStringExtra(EXTRA_LINE).orEmpty(),
                    modality = if (whisper) Modality.WHISPER else Modality.PET,
                    whisper = whisper,
                    choices = intent.getStringArrayListExtra(EXTRA_CHOICES).orEmpty(),
                    kind = intent.getStringExtra(EXTRA_KIND).orEmpty(),
                )
            }
            ACTION_STARE -> startStare(
                moodOf(intent),
                intent.getStringExtra(EXTRA_LINE).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_CHOICES).orEmpty(),
            )
            ACTION_COUNCIL -> startCouncil(
                lines = intent.getStringArrayListExtra(EXTRA_LINES).orEmpty(),
                mood = moodOf(intent),
                together = intent.getStringExtra(EXTRA_TOGETHER).orEmpty(),
                choices = intent.getStringArrayListExtra(EXTRA_CHOICES).orEmpty(),
                kind = intent.getStringExtra(EXTRA_KIND).orEmpty(),
            )
            ACTION_FADE -> setFade(intent.getFloatExtra(EXTRA_LEVEL, 0f))
            ACTION_CAPSULE -> startCapsule(
                line = intent.getStringExtra(EXTRA_LINE).orEmpty(),
                startedAt = intent.getLongExtra(EXTRA_STARTED, System.currentTimeMillis()),
            )
            ACTION_HIDE_CAPSULE -> {
                removeCapsuleWindow()
                maybeStop()
            }
            ACTION_HIDE -> hideLive()
        }
    }

    private fun moodOf(intent: Intent): Mood =
        runCatching { Mood.valueOf(intent.getStringExtra(EXTRA_MOOD).orEmpty()) }.getOrDefault(Mood.CALM)

    // ------------------------------------------------------------------ Z1 桌宠

    private fun startPet(
        mood: Mood,
        line: String,
        modality: Modality,
        whisper: Boolean,
        choices: List<String> = emptyList(),
        kind: String = "",
    ) {
        // 同一时间只留一种“她本人”的窗口（胶囊和褪色不受影响）。
        removeStareWindow()
        removeCouncilWindow()
        removePetWindow()

        petMood = mood
        petLine = line
        petModality = modality
        petWhisper = whisper
        petChoices = choices
        petKind = kind
        petChess = BrainHost.state.value.chess
        petChessScore = -1
        petChessConclusion = ""
        // 耳语直接亮出来，不弹跳；普通桌宠从压扁状态回弹。
        petLanded = whisper
        petShownAt = System.currentTimeMillis()

        val prefs = overlayPrefs(this)
        val savedY = prefs.getInt(KEY_PET_Y, Int.MIN_VALUE)
        val y = if (savedY != Int.MIN_VALUE) savedY else (screenH * (if (whisper) 0.45f else 0.55f)).toInt()
        val p = windowParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            // 先放在屏幕外，量出宽度后再从右边探出来。
            x = screenW
            this.y = y
        }
        val v = composeView { PetContent() }
        petView = v
        petParams = p
        runCatching { wm.addView(v, p) }.onFailure {
            petView = null
            petParams = null
            Log.w(TAG, "桌宠窗口加不上：${it.javaClass.simpleName}")
            maybeStop()
            return
        }
        petEntranceJob = scope.launch { petEntrance(v, p, whisper) }
        petTimeoutJob = scope.launch {
            delay(if (whisper) 8_000L else 12_000L)
            petTimeoutJob = null
            val elapsed = System.currentTimeMillis() - petShownAt
            petEntranceJob?.cancel()
            petEntranceJob = null
            retractPetInline()
            removePetWindow()
            report(modality, Reaction.IGNORED, elapsed)
            maybeStop()
        }
    }

    /** 出场：先探头（露半个），0.4 秒后滑到目标位置，落地时靠 Compose 的 spring 压扁回弹。 */
    private suspend fun petEntrance(v: ComposeView, p: WindowManager.LayoutParams, whisper: Boolean) {
        val w = awaitWidth(v)
        val prefs = overlayPrefs(this)
        val savedX = prefs.getInt(KEY_PET_X, Int.MIN_VALUE)
        val maxY = (screenH - v.height).coerceAtLeast(0)
        p.y = p.y.coerceIn(0, maxY)

        if (whisper) {
            // 耳语：不探头、不弹跳，直接亮在记下的位置（没有就屏幕右侧中部）。
            p.x = (if (savedX != Int.MIN_VALUE) savedX else (screenW - w - px(12f)))
                .coerceIn(0, (screenW - w).coerceAtLeast(0))
            updateWindow(v, p)
            // 万一第一次量出来的宽度是 0，等一帧用真实宽度再摆一次。
            delay(60)
            if (petView === v && v.width > 0) {
                val rw = v.width
                p.x = (if (savedX != Int.MIN_VALUE) savedX else (screenW - rw - px(12f)))
                    .coerceIn(0, (screenW - rw).coerceAtLeast(0))
                updateWindow(v, p)
            }
            return
        }

        // 探头：从离落点最近的那条边露半个（上次拖到左边，就从左边出来，不横穿屏幕）
        if (petView !== v) return
        val guessX = if (savedX != Int.MIN_VALUE) savedX else (screenW - w - px(12f))
        val fromLeft = guessX + w / 2 < screenW / 2
        p.x = if (fromLeft) -w / 2 else (screenW - w / 2).coerceAtLeast(0)
        updateWindow(v, p)
        delay(400)
        if (petView !== v) return

        // 量出真实宽度后再算落点（避免首次测量落空时位置偏出去）。
        val realW = if (v.width > 0) v.width else w
        val endX = (if (savedX != Int.MIN_VALUE) savedX else (screenW - realW - px(12f)))
            .coerceIn(0, (screenW - realW).coerceAtLeast(0))

        // 整个跳出来
        val from = p.x
        val steps = 20
        for (i in 1..steps) {
            if (petView !== v) return
            p.x = (from + (endX - from) * easeOutCubic(i / steps.toFloat())).toInt()
            updateWindow(v, p)
            delay(16)
        }
        if (petView !== v) return
        p.x = endX
        updateWindow(v, p)
        petLanded = true
    }

    private fun onPetTap() {
        if (petView == null) return
        val mood = petMood
        val line = petLine
        val modality = petModality
        removePetWindow()
        // 卡片自己报反应；耳语时把原来那句完整的话交给她。
        runCatching { LiveScreens.peek(this, mood, line, modality) }
            .onFailure { Log.w(TAG, "底部卡片打不开：${it.javaClass.simpleName}") }
        maybeStop()
    }

    private fun onPetLongPress() {
        if (petView == null) return
        // 一小时内不再用桌宠（只对 PET 生效；耳语长按不会连累之后的桌宠）。
        if (petModality == Modality.PET) {
            overlayPrefs(this).edit()
                .putLong(KEY_PET_MUTE, System.currentTimeMillis() + 3_600_000L)
                .apply()
        }
        dismissPet(Reaction.DISMISSED)
    }

    private fun onPetDragEnd() {
        val v = petView ?: return
        val p = petParams ?: return
        val w = v.width
        // 松手吸附到左右边，记住位置。
        p.x = if (p.x + w / 2 < screenW / 2) 0 else (screenW - w).coerceAtLeast(0)
        updateWindow(v, p)
        overlayPrefs(this).edit().putInt(KEY_PET_X, p.x).putInt(KEY_PET_Y, p.y).apply()
        dismissPet(Reaction.DISMISSED)
    }

    private fun dismissPet(reaction: Reaction) {
        if (petView == null) return
        val elapsed = System.currentTimeMillis() - petShownAt
        val modality = petModality
        val r = if (reaction == Reaction.DISMISSED && elapsed < 3_000L) Reaction.DISMISSED_FAST else reaction
        petEntranceJob?.cancel()
        petEntranceJob = null
        petTimeoutJob?.cancel()
        petTimeoutJob = null
        scope.launch {
            retractPetInline()
            removePetWindow()
            report(modality, r, elapsed)
            maybeStop()
        }
    }

    /** 缩回边上消失。 */
    private suspend fun retractPetInline() {
        val v = petView ?: return
        val p = petParams ?: return
        val w = v.width
        val target = if (p.x + w / 2 < screenW / 2) -w else screenW
        val from = p.x
        for (i in 1..14) {
            if (petView !== v) return
            p.x = (from + (target - from) * i / 14f).toInt()
            updateWindow(v, p)
            delay(14)
        }
    }

    private fun movePet(dx: Float, dy: Float) {
        val v = petView ?: return
        val p = petParams ?: return
        val w = v.width
        val h = v.height
        p.x = (p.x + dx.toInt()).coerceIn(0, (screenW - w).coerceAtLeast(0))
        p.y = (p.y + dy.toInt()).coerceIn(0, (screenH - h).coerceAtLeast(0))
        updateWindow(v, p)
    }

    private fun removePetWindow() {
        petEntranceJob?.cancel()
        petEntranceJob = null
        petTimeoutJob?.cancel()
        petTimeoutJob = null
        val v = petView ?: return
        petView = null
        petParams = null
        detach(v)
    }

    // ------------------------------------------------------------------ Z2 盯着你

    private fun startStare(mood: Mood, line: String, choices: List<String> = emptyList()) {
        removePetWindow()
        removeCouncilWindow()
        removeStareWindow()

        stareMood = mood
        stareLine = line
        stareChoices = choices
        stareBubble = false
        stareLeaving = false
        stareShownAt = System.currentTimeMillis()

        val side = px(200f)
        val p = windowParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            x = ((screenW - side) / 2).coerceAtLeast(0)
            y = (screenH * 0.30f).toInt()
        }
        val v = composeView { StareContent() }
        stareView = v
        stareParams = p
        runCatching { wm.addView(v, p) }.onFailure {
            stareView = null
            stareParams = null
            Log.w(TAG, "盯着你的窗口加不上：${it.javaClass.simpleName}")
            maybeStop()
            return
        }
        stareJob = scope.launch {
            delay(45_000)
            stareJob = null
            // 叹口气，缩回去。
            stareMood = Mood.SAD
            delay(1_000)
            stareLeaving = true
            delay(420)
            val elapsed = System.currentTimeMillis() - stareShownAt
            removeStareWindow()
            report(Modality.STARE, Reaction.IGNORED, elapsed)
            maybeStop()
        }
    }

    private fun onStareTap() {
        if (stareView == null || stareBubble || stareLeaving) return
        stareBubble = true
        stareJob?.cancel()
        stareJob = scope.launch {
            // 先让气泡亮一下，再打开卡片（REPLIED 由 peek 自己报）。
            delay(240)
            runCatching { LiveScreens.peek(this@PetOverlayService, stareMood, stareLine, Modality.STARE) }
                .onFailure { Log.w(TAG, "盯着你：底部卡片打不开：${it.javaClass.simpleName}") }
            delay(600)
            removeStareWindow()
            maybeStop()
        }
    }

    /** D2：点盯着你气泡里的选项：她接一句、换选项；没有了就收起。 */
    private fun onStareChoice(choice: String) {
        if (stareView == null || stareLeaving) return
        // 点“先不聊了 / 别管我”不是回复：不报 REPLIED，由 Brain.tap 只给这次出现记 STOPPED。
        val exit = choice == com.moling.core.live.Live.EXIT || choice == "别管我"
        if (!exit) report(Modality.STARE, Reaction.REPLIED, System.currentTimeMillis() - stareShownAt)
        scope.launch {
            val turn = runCatching { BrainHost.tap(choice, Modality.STARE) }.getOrNull() ?: return@launch
            stareMood = turn.mood
            stareLine = turn.line
            stareChoices = turn.choices
            if (stareChoices.isEmpty()) {
                delay(2_000)
                removeStareWindow()
                maybeStop()
            }
        }
    }

    private fun removeStareWindow() {
        stareJob?.cancel()
        stareJob = null
        val v = stareView ?: return
        stareView = null
        stareParams = null
        detach(v)
    }

    // ------------------------------------------------------------------ Z4 参谋弹幕

    private fun startCouncil(
        lines: List<String>,
        mood: Mood,
        together: String,
        choices: List<String> = emptyList(),
        kind: String = "",
    ) {
        removePetWindow()
        removeStareWindow()
        removeCouncilWindow()

        councilLines = lines.filter { it.isNotBlank() }.take(4)
        val p = windowParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        ).apply {
            x = 0
            y = 0
            // Android 12 起，不可触摸的悬浮窗 alpha 必须 <= 0.8，否则会挡住他的操作。
            alpha = 0.8f
        }
        val v = composeView { CouncilContent() }
        councilView = v
        councilParams = p
        runCatching { wm.addView(v, p) }.onFailure {
            councilView = null
            councilParams = null
            Log.w(TAG, "弹幕窗口加不上：${it.javaClass.simpleName}")
            maybeStop()
            return
        }
        // 0.8 秒间隔先后出发 + 每行 8 秒，留一点尾巴。
        val total = 800L * (councilLines.size.coerceAtLeast(1) - 1) + 8_000L + 600L
        councilJob = scope.launch {
            delay(total)
            councilJob = null
            removeCouncilWindow()
            // 飘完：桌宠带着“合起来”的那句出来，之后的反应报 COUNCIL。
            startPet(mood, together, Modality.COUNCIL, whisper = false, choices = choices, kind = kind)
        }
    }

    private fun removeCouncilWindow() {
        councilJob?.cancel()
        councilJob = null
        val v = councilView ?: return
        councilView = null
        councilParams = null
        detach(v)
    }

    // ------------------------------------------------------------------ Z6 褪色

    private fun setFade(level: Float) {
        if (level <= 0f) {
            stopFade()
            return
        }
        // 屏幕上实际看到的灰 = 内容透明度 × 窗口的 0.8；要实际到 0.35，内容取 0.35 / 0.8。
        val target = (0.35f / 0.8f * level).coerceIn(0f, 0.35f / 0.8f)
        fadeTarget = target
        if (fadeView != null) return

        val p = windowParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        ).apply {
            x = 0
            y = 0
            // 同 Z4：不可触摸窗口的 alpha 必须 <= 0.8。
            alpha = 0.8f
        }
        val v = composeView { FadeContent() }
        fadeView = v
        fadeParams = p
        runCatching { wm.addView(v, p) }.onFailure {
            fadeView = null
            fadeParams = null
            fadeTarget = 0f
            Log.w(TAG, "褪色窗口加不上：${it.javaClass.simpleName}")
            maybeStop()
            return
        }
        fadeActive = true
        // 显示期间每满 60 秒，当天分钟数 +1（上限 20）。
        fadeJob = scope.launch {
            while (isActive) {
                delay(60_000)
                addFadeMinute()
            }
        }
    }

    private fun stopFade() {
        fadeJob?.cancel()
        fadeJob = null
        fadeTarget = 0f
        fadeActive = false
        val v = fadeView
        if (v != null) {
            fadeView = null
            fadeParams = null
            detach(v)
        }
        maybeStop()
    }

    private fun addFadeMinute() {
        runCatching {
            val prefs = overlayPrefs(this)
            val key = FADE_PREFIX + beijingDay()
            val used = prefs.getLong(key, 0L)
            if (used < FADE_DAILY_MAX) prefs.edit().putLong(key, used + 1).apply()
        }
    }

    // ------------------------------------------------------------------ Z8 演员胶囊

    private fun startCapsule(line: String, startedAt: Long) {
        capsuleLine = line.take(64)
        capsuleStartedAt = if (startedAt > 0L) startedAt else System.currentTimeMillis()
        // 幂等：每心跳只更新文字和时间，不重复 addView。
        if (capsuleView != null) return

        val prefs = overlayPrefs(this)
        val savedX = prefs.getInt(KEY_CAPSULE_X, Int.MIN_VALUE)
        capsulePlaced = savedX != Int.MIN_VALUE
        capsuleMeasuredWidth = 0
        val p = windowParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            x = if (savedX != Int.MIN_VALUE) savedX else screenW
            y = prefs.getInt(KEY_CAPSULE_Y, px(56f))
        }
        val v = composeView { CapsuleContent() }
        capsuleView = v
        capsuleParams = p
        runCatching { wm.addView(v, p) }.onFailure {
            capsuleView = null
            capsuleParams = null
            Log.w(TAG, "胶囊窗口加不上：${it.javaClass.simpleName}")
            maybeStop()
            return
        }
        capsuleActive = true
    }

    private fun onCapsuleTap() {
        capsuleExpanded = !capsuleExpanded
    }

    private fun onCapsuleMeasured(width: Int) {
        if (width <= 0 || width == capsuleMeasuredWidth) return
        capsuleMeasuredWidth = width
        val v = capsuleView ?: return
        v.post { clampCapsuleX() }
    }

    /** 贴右上角 / 展开变宽时往左让一点，别出屏。 */
    private fun clampCapsuleX() {
        val v = capsuleView ?: return
        val p = capsuleParams ?: return
        val w = if (capsuleMeasuredWidth > 0) capsuleMeasuredWidth else v.width
        if (w <= 0) return
        val maxX = (screenW - w - px(8f)).coerceAtLeast(0)
        val newX = if (!capsulePlaced) maxX else p.x.coerceIn(0, maxX)
        capsulePlaced = true
        if (newX != p.x) {
            p.x = newX
            updateWindow(v, p)
        }
    }

    private fun moveCapsule(dx: Float, dy: Float) {
        val v = capsuleView ?: return
        val p = capsuleParams ?: return
        val w = if (capsuleMeasuredWidth > 0) capsuleMeasuredWidth else v.width
        val h = v.height
        p.x = (p.x + dx.toInt()).coerceIn(0, (screenW - w).coerceAtLeast(0))
        p.y = (p.y + dy.toInt()).coerceIn(0, (screenH - h).coerceAtLeast(0))
        updateWindow(v, p)
    }

    private fun onCapsuleDragEnd() {
        val p = capsuleParams ?: return
        capsulePlaced = true
        overlayPrefs(this).edit().putInt(KEY_CAPSULE_X, p.x).putInt(KEY_CAPSULE_Y, p.y).apply()
    }

    private fun removeCapsuleWindow() {
        capsuleActive = false
        capsuleExpanded = false
        capsulePlaced = false
        capsuleMeasuredWidth = 0
        val v = capsuleView ?: return
        capsuleView = null
        capsuleParams = null
        detach(v)
    }

    // ------------------------------------------------------------------ 统一收尾

    /** hide()：收起桌宠 / 盯着 / 弹幕（褪色和胶囊不动）。 */
    private fun hideLive() {
        removePetWindow()
        removeStareWindow()
        removeCouncilWindow()
        maybeStop()
    }

    private fun report(modality: Modality, reaction: Reaction, elapsed: Long) {
        runCatching { BrainHost.react(modality, reaction, elapsed.coerceAtLeast(0L)) }
            .onFailure { Log.w(TAG, "反应没报上：${it.javaClass.simpleName}") }
    }

    private fun maybeStop() {
        if (petView != null || stareView != null || councilView != null || fadeView != null || capsuleView != null) return
        stopSelf(lastStartId)
    }

    /** 权限被收回 / 服务结束：所有窗口都 removeViewImmediate。 */
    private fun detachAllWindows() {
        fadeJob?.cancel()
        fadeJob = null
        fadeActive = false
        val fv = fadeView
        if (fv != null) {
            fadeView = null
            fadeParams = null
            detach(fv)
        }
        removeCapsuleWindow()
        removePetWindow()
        removeStareWindow()
        removeCouncilWindow()
    }

    // ------------------------------------------------------------------ 窗口 / Compose 小工具

    private fun windowParams(width: Int, height: Int, flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun composeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PetOverlayService)
            setViewTreeSavedStateRegistryOwner(this@PetOverlayService)
            setContent { MolingTheme { content() } }
        }

    private fun detach(view: ComposeView?) {
        if (view == null) return
        runCatching { wm.removeViewImmediate(view) }
        runCatching { view.disposeComposition() }
    }

    private fun updateWindow(view: ComposeView?, p: WindowManager.LayoutParams) {
        if (view == null) return
        runCatching { wm.updateViewLayout(view, p) }
    }

    private suspend fun awaitWidth(view: View): Int {
        val start = System.currentTimeMillis()
        while (view.width <= 0 && System.currentTimeMillis() - start < 800) delay(16)
        return view.width.coerceAtLeast(px(90f))
    }

    override fun onDestroy() {
        alive = false
        scope.cancel()
        detachAllWindows()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Compose 内容

    /** Z1 桌宠：气泡 + 墨滴；点 / 拖 / 长按都交给服务处理。 */
    @Composable
    private fun PetContent() {
        val text = if (petWhisper) "回她" else petLine
        // D3：小剧场按行画（“· 对手：…”），盲盒 / 赌在气泡上面加一个小标签。
        val shown = if (petKind == "drama" && !petWhisper) petLine.lines().joinToString("\n") { "· " + it } else text
        val squashX by animateFloatAsState(
            targetValue = if (petLanded) 1f else 1.16f,
            animationSpec = spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMediumLow),
            label = "petSquashX",
        )
        val squashY by animateFloatAsState(
            targetValue = if (petLanded) 1f else 0.82f,
            animationSpec = spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMediumLow),
            label = "petSquashY",
        )
        val pressScope = rememberCoroutineScope()
        Column(
            Modifier
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        // 按满 600 毫秒算长按。
                        var longPressed = false
                        val timer = pressScope.launch {
                            delay(600)
                            longPressed = true
                            onPetLongPress()
                        }
                        val released = tryAwaitRelease()
                        timer.cancel()
                        if (released && !longPressed) onPetTap()
                    })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            petEntranceJob?.cancel()
                            petEntranceJob = null
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            movePet(delta.x, delta.y)
                        },
                        onDragEnd = { onPetDragEnd() },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val ch = petChess
            if (ch != null || petChessScore >= 0) {
                ChessHeader(turn = ch?.turn ?: 3, done = ch == null)
            }
            if (ch != null && ch.moves.isEmpty()) {
                ChessBait(ch.bait)
            }
            if (ch == null && petChessScore >= 0) {
                ChessScore(petChessScore, petChessConclusion)
            }
            if (petKind == "blindbox" || petKind == "bet") {
                Tag(if (petKind == "blindbox") "盲盒" else "她下注了")
            }
            if (shown.isNotBlank()) {
                Bubble(shown)
                BubbleTail()
            }
            if (!petWhisper && petChoices.isNotEmpty()) {
                ChoiceCapsules(petChoices) { onPetChoice(it) }
            }
            Box(
                Modifier.graphicsLayer {
                    scaleX = squashX
                    scaleY = squashY
                },
                contentAlignment = Alignment.Center,
            ) {
                MolingFace(petMood, Modifier.size(92.dp))
            }
        }
    }

    /** Z2 盯着你：大墨滴 + 身后 45 秒的圆环；点她才冒气泡。 */
    @Composable
    private fun StareContent() {
        val progress = remember { Animatable(1f) }
        LaunchedEffect(Unit) {
            progress.animateTo(0f, animationSpec = tween(45_000, easing = LinearEasing))
        }
        val sway by rememberInfiniteTransition(label = "stareSway").animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_800), RepeatMode.Reverse),
            label = "stareSwayValue",
        )
        val leave by animateFloatAsState(
            targetValue = if (stareLeaving) 0f else 1f,
            animationSpec = tween(420),
            label = "stareLeave",
        )
        Column(
            Modifier
                .pointerInput(Unit) { detectTapGestures(onTap = { onStareTap() }) }
                .graphicsLayer { alpha = leave },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (stareBubble) {
                Bubble(stareLine)
                BubbleTail()
                if (stareChoices.isNotEmpty()) ChoiceCapsules(stareChoices) { onStareChoice(it) }
            }
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                InkRing(progress.value, Modifier.size(188.dp), color = Seal)
                MolingFace(
                    stareMood,
                    Modifier.size(140.dp).graphicsLayer { translationX = sway * 3.dp.toPx() },
                )
            }
        }
    }

    /** Z4 参谋弹幕：整屏只画不看（窗口不可触摸），四行在屏幕上 1/3 从右往左飘。 */
    @Composable
    private fun CouncilContent() {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth
            val third = constraints.maxHeight / 3f
            councilLines.forEachIndexed { i, text ->
                BarrageLine(
                    text = text,
                    index = i,
                    screenWidthPx = widthPx,
                    topPx = third * (0.08f + 0.24f * i),
                )
            }
        }
    }

    @Composable
    private fun BarrageLine(text: String, index: Int, screenWidthPx: Int, topPx: Float) {
        val progress = remember { Animatable(0f) }
        var barWidth by remember { mutableIntStateOf(0) }
        LaunchedEffect(index) {
            delay(800L * index)
            progress.animateTo(1f, animationSpec = tween(8_000, easing = LinearEasing))
        }
        Box(
            Modifier
                .onGloballyPositioned { barWidth = it.size.width }
                .graphicsLayer {
                    translationY = topPx
                    val travel = if (barWidth > 0) barWidth.toFloat() else screenWidthPx.toFloat()
                    translationX = screenWidthPx - (screenWidthPx + travel) * progress.value
                },
        ) {
            Box(
                Modifier
                    .background(Paper.copy(alpha = 0.78f), RoundedCornerShape(9.dp))
                    .border(0.6.dp, Ink.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .widthIn(max = 320.dp),
            ) {
                Text(
                    text = text,
                    color = Ink,
                    fontSize = 15.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /** Z6 褪色：整屏一层灰，10 分钟里慢慢升到屏幕上实际约 0.35（内容 0.44 × 窗口 0.8）。 */
    @Composable
    private fun FadeContent() {
        // 第一帧先用 0（否则 animateFloatAsState 会直接落在目标值），下一帧再开始往上升。
        var started by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) { started = 1f }
        val alpha by animateFloatAsState(
            targetValue = if (started > 0f) fadeTarget else 0f,
            animationSpec = tween(600_000, easing = LinearEasing),
            label = "fadeAlpha",
        )
        Box(Modifier.fillMaxSize().background(FadeGray.copy(alpha = alpha)))
    }

    /** Z8 胶囊：未展开一行（问 + mm:ss），点开两行；每秒自己刷新时间，不靠服务重建窗口。 */
    @Composable
    private fun CapsuleContent() {
        val expanded = capsuleExpanded
        val startedAt = capsuleStartedAt
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(startedAt) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        val shape = RoundedCornerShape(if (expanded) 12.dp else 24.dp)
        Column(
            Modifier
                .onGloballyPositioned { onCapsuleMeasured(it.size.width) }
                .pointerInput(Unit) { detectTapGestures(onTap = { onCapsuleTap() }) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            moveCapsule(delta.x, delta.y)
                        },
                        onDragEnd = { onCapsuleDragEnd() },
                    )
                }
                .background(Paper.copy(alpha = 0.95f), shape)
                .border(0.8.dp, Ink.copy(alpha = 0.4f), shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (!expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = capsuleLine.ifBlank { "聊完了就回来" },
                        color = Ink,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = clockText(now - startedAt),
                        color = Ink.copy(alpha = 0.62f),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = capsuleLine,
                    color = Ink,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                Text(
                    text = "聊完了就回来跟我说一句结论",
                    color = Ink.copy(alpha = 0.62f),
                    fontSize = 11.sp,
                )
            }
        }
    }

    /** D2：点桌宠气泡上的选项（点了算 REPLIED；没有新选项了 3 秒收起）。 */
    private fun onPetChoice(choice: String) {
        if (petView == null) return
        // E1：“我自己说一句”在小气泡里没法打字——把同一批选项交给底部卡片去接。
        if (choice == Chesses.FREE) {
            report(petModality, Reaction.REPLIED, System.currentTimeMillis() - petShownAt)
            val mood = petMood
            val line = petLine
            removePetWindow()
            LiveScreens.nextChoices = petChoices
            runCatching { LiveScreens.peek(this, mood, line, petModality) }
            maybeStop()
            return
        }
        // 点“先不聊了 / 别管我”不是回复：不报 REPLIED，由 Brain.tap 只给这次出现记 STOPPED。
        val exit = choice == com.moling.core.live.Live.EXIT || choice == "别管我"
        if (!exit) report(petModality, Reaction.REPLIED, System.currentTimeMillis() - petShownAt)
        val modality = petModality
        scope.launch {
            val turn = runCatching { BrainHost.tap(choice, modality) }.getOrNull() ?: return@launch
            petMood = turn.mood
            petLine = turn.line
            petChoices = turn.choices
            petKind = ""
            if (turn.chess) {
                val s = BrainHost.state.value
                petChess = s.chess
                if (s.chess == null) {
                    val last = s.chessDone.lastOrNull()
                    petChessScore = last?.score ?: -1
                    petChessConclusion = last?.conclusion.orEmpty()
                }
            }
            if (petChoices.isEmpty() && petChess == null) {
                petTimeoutJob?.cancel()
                petTimeoutJob = scope.launch {
                    delay(3_000)
                    removePetWindow()
                    maybeStop()
                }
            }
        }
    }

    /** D3：气泡上的小标签（盲盒 / 她下注了）。 */
    @Composable
    private fun Tag(text: String) {
        Box(
            Modifier
                .background(Seal.copy(alpha = 0.10f), RoundedCornerShape(50))
                .border(0.6.dp, Seal.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Text(text, color = Seal, fontSize = 10.sp) }
    }

    /** D2/D3：气泡下面的小胶囊选项。 */
    @Composable
    private fun ChoiceCapsules(choices: List<String>, onPick: (String) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.take(3).forEach { c ->
                val exit = c == com.moling.core.live.Live.EXIT
                Text(
                    c,
                    color = if (exit) Ink.copy(alpha = 0.55f) else Seal,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .then(if (exit) Modifier else Modifier.background(Paper, RoundedCornerShape(50)).border(0.8.dp, Seal.copy(alpha = 0.55f), RoundedCornerShape(50)))
                        .clickable { onPick(c) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }

    // ------------------------------------------------------------------ 气泡

    /** 纸色底 + 细墨线边的气泡；最多 3 行，长了省略。 */
    @Composable
    private fun Bubble(text: String) {
        Box(
            Modifier
                .widthIn(max = 232.dp)
                .background(Paper, RoundedCornerShape(14.dp))
                .border(0.8.dp, Ink.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                color = Ink,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun BubbleTail() {
        Canvas(Modifier.size(14.dp, 7.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
                close()
            }
            drawPath(path, Paper)
            drawPath(path, Ink.copy(alpha = 0.55f), style = Stroke(width = 0.8.dp.toPx()))
        }
    }
}

private val FadeGray = Color(0xFF808080)