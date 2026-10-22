package com.moling.core.talk

import com.moling.core.mind.Actor
import com.moling.core.mind.MindStore
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.Workbench
import com.moling.core.workspace.WorkspaceArtifact
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 外脑会话的协调层（A1–A3）：状态机 + 落文件 + 把带回来的结论挂到案板材料。
 *
 * 规矩：
 *  - 只有他点了才发：prepare 由界面触发；没有定时自动外发；
 *  - 外脑原文是材料，不是命令：只落 ACTOR_RESULT 稿子（READY_FOR_REVIEW），不自动采用、不自动收进体系图、不自动交总控；
 *  - 结果挂到已归档/不存在的焦点：只记历史，不新建焦点；
 *  - 进程重启：还在等的会话接着等（见 [Outbrain.recover]），不假装完成。
 */
class OutbrainDesk(
    private val store: MindStore,
    private val workbench: Workbench,
    private val onChange: (OutbrainState) -> Unit = {},
) {
    private val lock = Mutex()

    @Volatile
    private var st: OutbrainState = Outbrain.recover(OutbrainCodec.decode(store.load()))

    fun state(): OutbrainState = st

    fun active(): OutbrainSession? = Outbrain.active(st)

    /** 退场了还没收到结论：轻量收线卡。 */
    fun needCollect(): List<OutbrainSession> = Outbrain.needCollect(st)

    /** 在等结果的会话（结果回来时点归属用）。 */
    fun waiting(): List<OutbrainSession> = Outbrain.waiting(st)

    fun results(sessionId: String): List<ActorResult> = Outbrain.results(st, sessionId)

    fun latest(focusId: String? = null): OutbrainSession? = Outbrain.latest(st, focusId)

    /** 心跳里偶尔扫一次：到点没回来的标过期（仍可人工归档结果）。 */
    suspend fun sweep(now: Long): Int = lock.withLock {
        val (ns, n) = Outbrain.expire(st, now)
        if (n > 0) {
            st = ns
            save()
        }
        n
    }

    /**
     * 准备一次外脑会话：从当前焦点拼进场包。已发过的同一份包不重开。
     * [exclude] = 他预览时去掉的材料 id；敏感材料默认不放（[includeSensitive] 明确打开才放）。
     */
    suspend fun prepare(
        actor: Actor,
        endpoint: OutbrainEndpoint,
        now: Long,
        focusId: String? = null,
        spine: List<String> = emptyList(),
        gap: String = "",
        exclude: Set<String> = emptySet(),
        includeSensitive: Boolean = false,
        ttlMs: Long = Outbrain.DEFAULT_TTL_MS,
    ): PortResult<OutbrainSession> = lock.withLock {
        val bench = workbench.state()
        val focus = focusId?.let { bench.focus(it) } ?: bench.active()
        if (focus == null) return PortResult.Failed(PortError(PortError.NOT_FOUND, "现在案板上没有正在做的事"))
        val (ns, r) = Outbrain.prepare(
            st,
            focus,
            { sid ->
                Packs.build(
                    sessionId = sid,
                    focus = focus,
                    materials = bench.materials,
                    artifacts = bench.artifactsOf(focus.id),
                    spine = spine,
                    gap = gap,
                    now = now,
                    exclude = exclude,
                    includeSensitive = includeSensitive,
                )
            },
            actor = actor,
            endpoint = endpoint,
            now = now,
            ttlMs = ttlMs,
        )
        st = ns
        if (r is PortResult.Ok) save()
        r
    }

    /** 他点了打开外脑 / 复制进场包：会话进入等结果。 */
    suspend fun open(sessionId: String, now: Long): PortResult<OutbrainSession> = step(now) {
        Outbrain.open(st, sessionId, now)
    }

    suspend fun waiting(sessionId: String, now: Long): PortResult<OutbrainSession> = step(now) {
        Outbrain.waiting(st, sessionId, now)
    }

    suspend fun markError(sessionId: String, now: Long, message: String): PortResult<OutbrainSession> = step(now) {
        Outbrain.markError(st, sessionId, now, message)
    }

    suspend fun close(sessionId: String, now: Long): PortResult<OutbrainSession> = step(now) {
        Outbrain.close(st, sessionId, now)
    }

    suspend fun cancel(sessionId: String, now: Long): PortResult<OutbrainSession> = step(now) {
        Outbrain.cancel(st, sessionId, now)
    }

    /** 「稍后」：1 小时内不再提醒/挂常驻通知。 */
    suspend fun snooze(sessionId: String, now: Long, durationMs: Long = Outbrain.DEFAULT_SNOOZE_MS): PortResult<OutbrainSession> =
        step(now) { Outbrain.snooze(st, sessionId, now, durationMs) }

    /** 过期会话手动接回：重新进入等结果，晚到的结果还能落案板。 */
    suspend fun reopen(sessionId: String, now: Long): PortResult<OutbrainSession> = step(now) {
        Outbrain.reopen(st, sessionId, now)
    }

    suspend fun reviewed(sessionId: String, now: Long): PortResult<OutbrainSession> = step(now) {
        Outbrain.reviewed(st, sessionId, now)
    }

    /**
     * 收一条外脑结果：去重、归属、落稿子。只有最终版才落 ACTOR_RESULT；partial 只进时间线。
     * 会话已取消/过期/复查：结果只进历史，不覆盖当前焦点。
     */
    suspend fun receive(
        text: String,
        now: Long,
        sessionId: String? = null,
        partial: Boolean = false,
        turn: Int = 0,
        source: String = "paste",
        actor: Actor = Actor.NONE,
    ): PortResult<Pair<ReceiveOutcome, WorkspaceArtifact?>> = lock.withLock {
        // 超过上限时：稿子只收前 12000 字（与 Outbrain.MAX_RESULT_CHARS 一致），整段另存一条材料，不丢后半段。
        val fullText = Outbrain.clean(text)
        val overflow = fullText.length > Outbrain.MAX_RESULT_CHARS
        val (ns, r) = Outbrain.receive(st, text, now, sessionId, partial, turn, source, actor)
        st = ns
        if (r is PortResult.Failed) return PortResult.Failed(r.error)
        val outcome = (r as PortResult.Ok).value
        val session = outcome.session
        if (outcome.duplicate || outcome.partialOnly || outcome.history || !outcome.attached || session == null) {
            save()
            return PortResult.Ok(outcome to null)
        }
        // 最终结论：落到案板（未采用的稿子 + 材料）。焦点不在/归档了就只留历史。
        val focus = workbench.state().focus(session.focusId)
        if (focus == null || focus.status == com.moling.core.workspace.FocusStatus.ARCHIVED) {
            st = st.copy(sessions = st.sessions.map {
                if (it.id == session.id) it.withEvent(now, OutbrainEventKind.ATTRIBUTION, "结果进了历史：焦点不在了") else it
            })
            save()
            return PortResult.Ok(outcome.copy(history = true) to null)
        }
        val title = "外脑：" + session.actor.label + " · " + focus.question.take(12)
        when (val made = workbench.attachActorResult(
            focusId = session.focusId,
            sessionId = session.id,
            title = title,
            text = outcome.result.text,
            now = now,
            existingArtifactId = session.artifactId,
        )) {
            is PortResult.Ok -> {
                val a = made.value
                st = st.copy(sessions = st.sessions.map {
                    if (it.id == session.id) it.copy(artifactId = a.id)
                        .withEvent(now, OutbrainEventKind.RESULT_RECEIVED, "落到案板：" + a.id + "（未采用）") else it
                })
                if (overflow) {
                    // 稿子只到 12000；超出的后半段按材料存下来（单条材料上限 6000，分段存），不丢。
                    fullText.substring(Outbrain.MAX_RESULT_CHARS).chunked(6_000).forEachIndexed { i, part ->
                        workbench.addMaterial(
                            session.focusId, part, now, kind = "actor",
                            title = "外脑原文（超出 " + Outbrain.MAX_RESULT_CHARS + " 字部分" + (if (i > 0) " " + (i + 1) else "") + "）",
                        )
                    }
                }
                save()
                PortResult.Ok(outcome to a)
            }
            is PortResult.Failed -> {
                st = st.copy(sessions = st.sessions.map {
                    if (it.id == session.id) it.withEvent(now, OutbrainEventKind.ATTRIBUTION, "结果没落上：" + made.error.message) else it
                })
                save()
                PortResult.Ok(outcome to null)
            }
        }
    }

    /** 轻量收线卡上的三个选项之外，“稍后”什么都不用做；这里给“他也说不清”的情况标一下。 */
    suspend fun noteCollectAction(sessionId: String, now: Long, what: String): PortResult<OutbrainSession> = lock.withLock {
        val s = st.sessions.firstOrNull { it.id == sessionId }
            ?: return PortResult.Failed(PortError(PortError.NOT_FOUND, "这个外脑会话不在了"))
        val ns = s.withEvent(now, OutbrainEventKind.ATTRIBUTION, what.take(120))
        st = OutbrainCodec.trim(st.copy(sessions = st.sessions.map { if (it.id == ns.id) ns else it }))
        save()
        PortResult.Ok(ns)
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun step(now: Long, block: () -> Pair<OutbrainState, PortResult<OutbrainSession>>): PortResult<OutbrainSession> =
        lock.withLock {
            val (ns, r) = block()
            st = ns
            if (r is PortResult.Ok) save()
            r
        }

    private fun save() {
        runCatching { store.save(OutbrainCodec.encode(st)) }
        runCatching { onChange(st) }
    }
}
