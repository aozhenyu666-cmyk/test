package com.yishou.app.partner

import android.os.SystemClock
import androidx.room.withTransaction
import com.yishou.app.YishouApp
import com.yishou.app.data.EchoKind
import com.yishou.app.data.PartnerSession
import com.yishou.app.gate.GateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 外部伴读只计时和记录包名，不推测用户有没有说话。 */
data class PartnerView(
    val loading: Boolean = true,
    val row: PartnerSession? = null,
    val closing: Boolean = false,
    val text: String = "",
    val error: String? = null,
    /** Live 模式：开 Live 后说出口的那一句 */
    val spoken: String = "",
)

class PartnerSessions(private val app: YishouApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val dao get() = app.database.partners()
    private val _state = MutableStateFlow(PartnerView())
    val state = _state.asStateFlow()
    private var timer: PartnerTimer? = null
    private var watch: LiveWatch? = null
    private var materials: Set<String> = emptySet()
    private var lastFg: String? = null
    @Volatile
    private var pendingAct: LiveWatch.Act? = null
    private val restored = scope.launch {
        try {
            val pending = dao.pending()
            // 进程不在的这段无法观察，停止计时并留收线，不把它补算成在场。
            if (pending != null && pending.endedAt == null) {
                dao.end(pending.id, minOf(System.currentTimeMillis(), pending.startedAt + pending.minutes * 60_000L), pending.presentMs, pending.leaves)
            }
            _state.value = PartnerView(loading = false, row = pending?.let { dao.get(it.id) }, closing = pending != null)
        } catch (e: Exception) {
            _state.value = PartnerView(loading = false, error = "伴读记录没读出来，请重新打开一手")
        }
    }

    /** 语音伙伴那边留下了待收线的一场：外部伴读没在计时时，重新读一遍，让收线页显示出来 */
    suspend fun reload() {
        restored.join()
        lock.withLock {
            if (timer != null) return@withLock
            val p = dao.pending()?.takeIf { it.endedAt != null }
            _state.value = PartnerView(loading = false, row = p, closing = p != null)
        }
    }

    suspend fun pendingClose(): PartnerSession? {
        restored.join()
        tick()
        return dao.pending()?.takeIf { it.endedAt != null }
    }

    /**
     * live：这一场用 Gemini Live，靠“麦克风有没有在用”判断 Live 还开着没；
     * pull：Live 断了、人也走了，过一会儿把 Gemini 拉回前台（最多 3 次）。
     */
    suspend fun start(plan: PartnerPlan, materialApps: Set<String>, live: Boolean = false, pull: Boolean = false): String? {
        restored.join()
        return lock.withLock {
            if (_state.value.error != null || dao.pending() != null) return@withLock null
            val last = dao.latestClosed()
            val row = PartnerSession(material = plan.material.name, goal = plan.goal.trim(), minutes = plan.minutes,
                gear = plan.gear.name, startedAt = System.currentTimeMillis(), route = if (live) "live" else "gemini")
            val id = dao.insert(row)
            val clock = PartnerTimer(plan.minutes, SystemClock.elapsedRealtime(), materialApps + app.packageName + EXTERNAL_PACKAGE + LiveWatch.GEMINI)
            materials = materialApps
            watch = LiveWatch(live, pull, strict = plan.gear == Gear.STRICT)
            pendingAct = null
            lastFg = GateService.foreground
            clock.foreground(lastFg, clock.started)
            timer = clock
            _state.value = PartnerView(loading = false, row = row.copy(id = id), text = clock.text(clock.started),
                spoken = if (live) Opening.spoken(plan, last?.nextStep) else "")
            Opening.build(plan, last?.closeLine, last?.nextStep)
        }
    }

    /** 仅由现有无障碍回调传入包名；允许集合只在这一场有效。 */
    fun foreground(pkg: String?) {
        val clock = timer ?: return
        lastFg = pkg
        observe(clock)
    }

    /** 盯场要做的事（提醒、拉回），计时服务每秒取一次 */
    fun takeAct(): LiveWatch.Act? = pendingAct.also { pendingAct = null }

    /** 看一眼前台和麦克风：喂给盯场，再按盯场的结论记在场 */
    private fun observe(clock: PartnerTimer) {
        val now = SystemClock.elapsedRealtime()
        val w = watch
        val pkg = lastFg
        if (w == null) {
            clock.foreground(pkg, now)
        } else {
            val seen = LiveWatch.Seen(
                pkg = pkg,
                inGemini = pkg != null && pkg in LiveWatch.GEMINI,
                material = pkg != null && pkg in materials,
                neutral = pkg != null && pkg !in LiveWatch.GEMINI && (pkg == app.packageName || GateService.neutral(pkg)),
                mic = w.live && micBusy(),
            )
            val act = w.step(now, seen)
            if (act != LiveWatch.Act.None) pendingAct = act
            when (w.state) {
                LiveWatch.State.ON, LiveWatch.State.IDLE -> clock.foreground(EXTERNAL_PACKAGE, now)
                LiveWatch.State.UNKNOWN -> clock.foreground(null, now)
                else -> if (!seen.neutral) clock.foreground(AWAY, now)
            }
        }
        publish(clock)
    }

    /** 此刻有没有应用在用麦克风。系统只告诉“有”，不告诉是谁、录了什么。 */
    private fun micBusy(): Boolean = try {
        app.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isNotEmpty()
    } catch (e: Exception) {
        false
    }

    fun allows(pkg: String): Boolean = timer?.allows(pkg, SystemClock.elapsedRealtime()) == true

    private fun publish(clock: PartnerTimer) {
        val now = SystemClock.elapsedRealtime()
        val base = clock.text(now)
        val live = if (clock.expired(now)) null else watch?.label(now)
        _state.value = _state.value.copy(text = if (live == null) base else "$live · $base")
    }

    suspend fun tick(checkpoint: Boolean = false) {
        restored.join()
        lock.withLock {
            val clock = timer ?: return@withLock
            if (clock.expired(SystemClock.elapsedRealtime())) finishLocked(clock)
            else {
                observe(clock)
                if (checkpoint) _state.value.row?.let { dao.checkpoint(it.id, clock.presence.presentMs, clock.presence.leaves) }
            }
        }
    }

    suspend fun finish() {
        restored.join()
        lock.withLock { timer?.let { finishLocked(it) } }
    }

    private suspend fun finishLocked(clock: PartnerTimer) {
        clock.finish(SystemClock.elapsedRealtime())
        val row = _state.value.row ?: return
        val elapsed = (SystemClock.elapsedRealtime() - clock.started).coerceIn(0, row.minutes * 60_000L)
        dao.end(row.id, row.startedAt + elapsed, clock.presence.presentMs, clock.presence.leaves)
        timer = null
        watch = null
        pendingAct = null
        _state.value = PartnerView(loading = false, row = dao.get(row.id), closing = true, text = "到这里，回来收线")
    }

    suspend fun close(line: String, next: String): Boolean {
        if (!Closing.ok(line, next)) return false
        finish()
        return lock.withLock {
            val row = _state.value.row ?: return@withLock false
            val saved = app.database.withTransaction {
                if (dao.close(row.id, line.trim(), next.trim()) != 1) false else {
                    app.echo(line, EchoKind.CONCLUSION, "partner")
                    true
                }
            }
            if (saved) _state.value = PartnerView(loading = false)
            saved
        }
    }

    suspend fun deletePending() {
        finish()
        lock.withLock {
            _state.value.row?.let { dao.delete(it.id) }
            _state.value = PartnerView(loading = false)
        }
    }

    /** 服务被系统终止时仍保留未收线记录；不自动重开这一场。 */
    fun serviceStopped(id: Long?) { scope.launch {
        if (id != null && _state.value.row?.id == id) finish()
    } }

    companion object {
        const val EXTERNAL_PACKAGE = "com.google.android.apps.bard"
        const val EXTERNAL_URL = "https://gemini.google.com/app"
        /** 喂给在场统计的“不在场”占位 */
        private const val AWAY = "__away__"
    }
}
