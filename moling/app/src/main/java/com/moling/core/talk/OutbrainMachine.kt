package com.moling.core.talk

import com.moling.core.mind.Actor
import com.moling.core.workspace.Focus
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult

/*
 * 外脑会话状态机（A1）：PREPARED → OPENED → WAITING_RESULT → RESULT_RECEIVED → REVIEWED，
 * 另有 CANCELLED / EXPIRED。纯函数：输入状态和数据，返回新状态 + 结果；不联网、不加锁、不读权限。
 *
 * 幂等：同一焦点版本 + 同一端点 + 同一份进场包只开一次；同一 (sessionId, turn, contentHash) 的结果只收一次。
 * 归属：结果有 sessionId 就按它；没有就只在“恰好一个会话在等”时认；多个就返回 NEEDS_ATTRIBUTION 让他点。
 * partial（流式上屏一半）不落成最终结论，也不覆盖最终版。
 */
object Outbrain {
    const val TIMELINE_KEEP = 60
    const val DEFAULT_TTL_MS = 30 * 60_000L
    const val DEFAULT_SNOOZE_MS = 3_600_000L
    const val MAX_RESULT_CHARS = 12_000

    /** B4：联络站黑板上一次最多放几个外脑会话、整段最多多少字（worker 黑板 20000 封顶，留余量给她的黑板）。 */
    const val RELAY_BOARD_MAX_SESSIONS = 2
    const val RELAY_BOARD_MAX_CHARS = 6_000

    // 会话 id 带时间戳：ob-<seq>-<now>（旧数据 ob-<seq> 也认）。
    private val tagRe = Regex("\\[\\[外脑:(ob-\\d+(?:-\\d+)?)\\]\\]")

    /** 进场包里让他带回来的标记；回来时靠它把结果对到会话上。 */
    fun tag(sessionId: String): String = "[[外脑:" + sessionId + "]]"

    /** 从带回来的文字里认出会话 id（没有就 null，不让界面按时间猜）。 */
    fun taggedSessionId(text: String): String? = tagRe.find(text)?.groupValues?.get(1)

    /** 落材料前去掉回来标记（标记只用来对会话）。 */
    fun clean(text: String): String = text.replace(tagRe, "").trim()

    // -------------------------------------------------------------- prepare

    /**
     * 准备一次外脑会话。同样的焦点版本 + 同一个端点 + 同一份进场包只开一次：
     * 重复点返回现有会话（不重复送出、不重复花钱）。
     */
    fun prepare(
        state: OutbrainState,
        focus: Focus,
        packFor: (sessionId: String) -> EntryPack,
        actor: Actor,
        endpoint: OutbrainEndpoint,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Pair<OutbrainState, PortResult<OutbrainSession>> {
        // 先按临时 id 拼一份算哈希（哈希不包含 sessionId）。
        val hash = Packs.payloadHash(packFor("pending"))
        state.sessions.lastOrNull {
            it.focusId == focus.id && it.focusRevision == focus.revision && it.endpointId == endpoint.id &&
                it.payloadHash == hash && it.status != OutbrainStatus.CANCELLED &&
                it.status != OutbrainStatus.EXPIRED && it.status != OutbrainStatus.REVIEWED
        }?.let { return state to PortResult.Ok(it) }
        var next = state.copy(seq = state.seq + 1)
        // 编号加时间戳：outbrain.json 丢了/读坏后重新从 1 数，也不会撞上旧会话 id。
        val sid = "ob-" + next.seq + "-" + now
        val pack = packFor(sid)
        val session = OutbrainSession(
            id = sid,
            focusId = focus.id,
            focusRevision = focus.revision,
            actor = actor,
            transport = endpoint.transport,
            endpointId = endpoint.id,
            status = OutbrainStatus.PREPARED,
            payloadHash = hash,
            pack = pack,
            createdAt = now,
            expiresAt = now + ttlMs.coerceIn(60_000L, 24 * 3_600_000L),
        ).withEvent(now, OutbrainEventKind.PREPARED, endpoint.label + " · " + pack.materials.size + " 条材料摘要")
        next = next.copy(
            sessions = next.sessions + session,
            log = (next.log + (now / 1000).toString() + " 准备了外脑会话 " + sid + "（" + endpoint.label + "）").takeLast(100),
        )
        return OutbrainCodec.trim(next) to PortResult.Ok(session)
    }

    // -------------------------------------------------------------- transitions

    fun open(state: OutbrainState, sessionId: String, now: Long): Pair<OutbrainState, PortResult<OutbrainSession>> =
        move(state, sessionId, now, setOf(OutbrainStatus.PREPARED, OutbrainStatus.OPENED, OutbrainStatus.WAITING_RESULT)) {
            it.copy(openedAt = if (it.openedAt == 0L) now else it.openedAt, status = OutbrainStatus.OPENED)
                .withEvent(now, OutbrainEventKind.OPENED)
        }

    /** 他点过「打开外脑 / 复制进场包」：开始等结果。 */
    fun waiting(state: OutbrainState, sessionId: String, now: Long): Pair<OutbrainState, PortResult<OutbrainSession>> =
        move(state, sessionId, now, setOf(OutbrainStatus.PREPARED, OutbrainStatus.OPENED, OutbrainStatus.WAITING_RESULT)) {
            it.copy(
                openedAt = if (it.openedAt == 0L) now else it.openedAt,
                status = if (it.status == OutbrainStatus.RESULT_RECEIVED) it.status else OutbrainStatus.WAITING_RESULT,
            ).withEvent(now, OutbrainEventKind.WAITING, "等他送出 / 等结果回来")
        }

    fun markError(state: OutbrainState, sessionId: String, now: Long, message: String): Pair<OutbrainState, PortResult<OutbrainSession>> =
        move(
            state, sessionId, now,
            setOf(OutbrainStatus.PREPARED, OutbrainStatus.OPENED, OutbrainStatus.WAITING_RESULT, OutbrainStatus.RESULT_RECEIVED),
        ) {
            it.copy(lastError = message.take(200)).withEvent(now, OutbrainEventKind.DEGRADED, message.take(120))
        }

    /** 收线：他离开外脑（或点了收线）。没有结果也留着等——显示轻量收线卡。 */
    fun close(state: OutbrainState, sessionId: String, now: Long): Pair<OutbrainState, PortResult<OutbrainSession>> {
        val s = state.sessions.firstOrNull { it.id == sessionId }
            ?: return state to fail(PortError.NOT_FOUND, "这个外脑会话不在了")
        if (s.closedAt > 0) return state to PortResult.Ok(s)
        val n = when (s.status) {
            // 还没送出就收：等于取消，不要留一个“等结果”的假会话。
            OutbrainStatus.PREPARED -> s.copy(status = OutbrainStatus.CANCELLED, closedAt = now)
                .withEvent(now, OutbrainEventKind.CLOSED, "还没送出就收了")
            OutbrainStatus.OPENED -> s.copy(status = OutbrainStatus.WAITING_RESULT, closedAt = now)
                .withEvent(now, OutbrainEventKind.CLOSED, "退场，还没收到结论")
            OutbrainStatus.WAITING_RESULT, OutbrainStatus.RESULT_RECEIVED -> s.copy(closedAt = now)
                .withEvent(now, OutbrainEventKind.CLOSED)
            else -> s.copy(closedAt = now)
        }
        return commit(state, n) to PortResult.Ok(n)
    }

    /** 「不要了」：过期会话也能清掉，不留一条永远挂着的待收线。 */
    fun cancel(state: OutbrainState, sessionId: String, now: Long): Pair<OutbrainState, PortResult<OutbrainSession>> =
        move(
            state, sessionId, now,
            setOf(OutbrainStatus.PREPARED, OutbrainStatus.OPENED, OutbrainStatus.WAITING_RESULT, OutbrainStatus.RESULT_RECEIVED, OutbrainStatus.EXPIRED),
        ) {
            it.copy(status = OutbrainStatus.CANCELLED, closedAt = if (it.closedAt == 0L) now else it.closedAt, snoozeUntil = 0)
                .withEvent(now, OutbrainEventKind.CANCELLED)
        }

    /** 「稍后」：1 小时内不再提醒、不挂常驻通知；会话状态不变，结果照样能收。 */
    fun snooze(
        state: OutbrainState,
        sessionId: String,
        now: Long,
        durationMs: Long = DEFAULT_SNOOZE_MS,
    ): Pair<OutbrainState, PortResult<OutbrainSession>> =
        move(
            state, sessionId, now,
            setOf(OutbrainStatus.PREPARED, OutbrainStatus.OPENED, OutbrainStatus.WAITING_RESULT, OutbrainStatus.RESULT_RECEIVED, OutbrainStatus.EXPIRED),
        ) {
            it.copy(snoozeUntil = now + durationMs.coerceIn(60_000L, 24 * 3_600_000L))
                .withEvent(now, OutbrainEventKind.SNOOZED, "1 小时后再提醒")
        }

    /** 过期会话手动接回：重新进入等结果，后来的结果还能落案板（只有他点才算）。 */
    fun reopen(
        state: OutbrainState,
        sessionId: String,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Pair<OutbrainState, PortResult<OutbrainSession>> =
        move(state, sessionId, now, setOf(OutbrainStatus.EXPIRED)) {
            it.copy(
                status = OutbrainStatus.WAITING_RESULT,
                closedAt = 0,
                expiresAt = now + ttlMs.coerceIn(60_000L, 24 * 3_600_000L),
                snoozeUntil = 0,
            ).withEvent(now, OutbrainEventKind.REOPENED, "手动接回，接着等")
        }

    /** 他看过了这条结论（采用与否另说；这里只结束会话）。 */
    fun reviewed(state: OutbrainState, sessionId: String, now: Long): Pair<OutbrainState, PortResult<OutbrainSession>> {
        val s = state.sessions.firstOrNull { it.id == sessionId }
            ?: return state to fail(PortError.NOT_FOUND, "这个外脑会话不在了")
        if (s.status == OutbrainStatus.REVIEWED) return state to PortResult.Ok(s)
        if (finalResults(state, s.id).isEmpty()) return state to fail("BAD_STATE", "还没有最终结论，先不标复查")
        val n = s.copy(status = OutbrainStatus.REVIEWED, reviewedAt = now).withEvent(now, OutbrainEventKind.REVIEWED)
        return commit(state, n) to PortResult.Ok(n)
    }

    /** 到点没回来：标过期。结果仍可人工归档（receive 仍然接，只进历史）。 */
    fun expire(state: OutbrainState, now: Long): Pair<OutbrainState, Int> {
        var out = state
        var n = 0
        state.sessions.filter {
            (it.status == OutbrainStatus.PREPARED || it.status == OutbrainStatus.OPENED ||
                it.status == OutbrainStatus.WAITING_RESULT) && it.expiresAt in 1..now
        }.forEach { s ->
            out = commit(out, s.copy(status = OutbrainStatus.EXPIRED).withEvent(now, OutbrainEventKind.EXPIRED, "到点没回来"))
            n++
        }
        return out to n
    }

    /** 重启恢复：还在等的会话接着等（不假装完成，也不丢）。 */
    fun recover(state: OutbrainState): OutbrainState = state.copy(
        sessions = state.sessions.map {
            if ((it.status == OutbrainStatus.OPENED || it.status == OutbrainStatus.WAITING_RESULT) && it.closedAt == 0L) {
                it.withEvent(it.createdAt, OutbrainEventKind.RECOVERED, "重启后接着等")
            } else it
        },
    )

    // -------------------------------------------------------------- receive

    /**
     * 外脑结果回来。
     *  - 明确 sessionId：优先用它；找不到会话就只进历史（不覆盖当前焦点）。
     *  - 没有 sessionId：只等一个会话时才认；两个以上返回 NEEDS_ATTRIBUTION，不凭时间猜。
     *  - 重复内容按 (sessionId, turn, contentHash) 去重；partial 不覆盖最终版，上屏一半不会被当结论。
     */
    fun receive(
        state: OutbrainState,
        text: String,
        now: Long,
        sessionId: String? = null,
        partial: Boolean = false,
        turn: Int = 0,
        source: String = "",
        actor: Actor = Actor.NONE,
    ): Pair<OutbrainState, PortResult<ReceiveOutcome>> {
        // 文本里带 [[外脑:ob-…]] 就以标记为准，优先于手选；落库前把标记去掉。
        val tagged = taggedSessionId(text)
        val clean = clean(text).take(MAX_RESULT_CHARS)
        if (clean.isBlank()) return state to fail(PortError.BAD_ACTION, "结果是空的")
        // 结果可能分几条回来：已经收到一条最终版的会话仍然算候选（不重复、不丢后半段）。
        val candidates = state.sessions.filter {
            it.status == OutbrainStatus.PREPARED || it.status == OutbrainStatus.OPENED ||
                it.status == OutbrainStatus.WAITING_RESULT || it.status == OutbrainStatus.RESULT_RECEIVED
        }
        val target = when {
            tagged != null -> state.sessions.firstOrNull { it.id == tagged }
            sessionId != null -> state.sessions.firstOrNull { it.id == sessionId }
            candidates.size == 1 -> candidates.first()
            candidates.size > 1 -> return state to fail(
                "NEEDS_ATTRIBUTION",
                "有 " + candidates.size + " 个外脑会话在等（" + candidates.joinToString("、") { it.id } + "），点一下这条属于哪一个",
            )
            else -> null
        }
        if (target == null) {
            // 没有会话：一样存进历史，不猜、不覆盖当前焦点。
            var s1 = state.copy(seq = state.seq + 1)
            val orphan = mkResult(s1.seq, tagged ?: sessionId ?: "orphan", clean, partial, turn, source, actor, now)
            s1 = s1.copy(
                results = (s1.results + orphan).takeLast(200),
                log = (s1.log + (now / 1000).toString() + " 收到一条对不上会话的外脑结果（只进历史）").takeLast(100),
            )
            return OutbrainCodec.trim(s1) to PortResult.Ok(ReceiveOutcome(null, orphan, attached = false, history = true))
        }
        val hash = Packs.hash(clean)
        val sameTurn = state.results.filter { it.sessionId == target.id && it.turn == turn }
        sameTurn.firstOrNull { it.contentHash == hash && it.partial == partial }?.let {
            return state to PortResult.Ok(ReceiveOutcome(target, it, attached = true, duplicate = true, partialOnly = partial))
        }
        var next = state
        if (!partial) {
            // 最终版到了：把同一轮流式的 partial 收掉，不把上屏一半当结论。
            next = next.copy(results = next.results.filterNot { it.sessionId == target.id && it.turn == turn && it.partial })
        } else {
            // 已经有最终版：迟到的 partial 不覆盖。
            sameTurn.firstOrNull { !it.partial }?.let {
                return state to PortResult.Ok(ReceiveOutcome(target, it, attached = true, duplicate = true, partialOnly = true))
            }
            // 同一轮里更长的 partial 替换短的；更短的（乱序）丢掉。
            val prev = sameTurn.filter { it.partial }.maxByOrNull { it.text.length }
            if (prev != null) {
                if (clean.length <= prev.text.length) {
                    return state to PortResult.Ok(ReceiveOutcome(target, prev, attached = true, duplicate = true, partialOnly = true))
                }
                next = next.copy(results = next.results.filterNot { it.id == prev.id })
            }
        }
        var s3 = next.copy(seq = next.seq + 1)
        val result = mkResult(s3.seq, target.id, clean, partial, turn, source, if (actor == Actor.NONE) target.actor else actor, now)
        s3 = s3.copy(results = (s3.results + result).takeLast(200))
        val history = target.status == OutbrainStatus.CANCELLED || target.status == OutbrainStatus.EXPIRED ||
            target.status == OutbrainStatus.REVIEWED
        val ns = if (partial) {
            target.copy(status = if (target.status == OutbrainStatus.PREPARED) OutbrainStatus.WAITING_RESULT else target.status)
                .withEvent(now, OutbrainEventKind.RESULT_PARTIAL, "第 " + turn + " 轮上屏一半（" + clean.length + " 字）")
                .copy(resultIds = target.resultIds + result.id)
        } else {
            target.copy(
                status = if (history) target.status else OutbrainStatus.RESULT_RECEIVED,
                resultIds = target.resultIds + result.id,
            ).withEvent(
                now,
                if (history) OutbrainEventKind.ATTRIBUTION else OutbrainEventKind.RESULT_RECEIVED,
                if (history) "迟到结果只进历史" else source.ifBlank { "结果回来" },
            )
        }
        s3 = commit(s3, ns).copy(
            log = (next.log + (now / 1000).toString() + " 收到外脑结果 " + result.id + (if (partial) "（partial）" else "")).takeLast(100),
        )
        return OutbrainCodec.trim(s3) to PortResult.Ok(
            ReceiveOutcome(ns, result, attached = !history, history = history, partialOnly = partial),
        )
    }

    // -------------------------------------------------------------- 读

    /** 正在进行的会话（没关、没取消、没复查）。 */
    fun active(state: OutbrainState): OutbrainSession? = state.sessions.lastOrNull {
        (it.status == OutbrainStatus.PREPARED || it.status == OutbrainStatus.OPENED ||
            it.status == OutbrainStatus.WAITING_RESULT) && it.closedAt == 0L
    }

    /** 要收线的会话：退场了、还没拿到最终结论。 */
    fun needCollect(state: OutbrainState): List<OutbrainSession> = state.sessions.filter { s ->
        (s.closedAt > 0 || s.status == OutbrainStatus.EXPIRED) &&
            s.status != OutbrainStatus.CANCELLED && s.status != OutbrainStatus.REVIEWED &&
            s.status != OutbrainStatus.RESULT_RECEIVED && finalResults(state, s.id).isEmpty()
    }

    /** 在等结果的会话：他的结果回来后点归属用。 */
    fun waiting(state: OutbrainState): List<OutbrainSession> = state.sessions.filter {
        it.status == OutbrainStatus.WAITING_RESULT || it.status == OutbrainStatus.OPENED ||
            it.status == OutbrainStatus.PREPARED
    }

    private const val RELAY_BOARD_HEADER =
        "【外脑会话 · 联络站】\n" +
            "下面是他按「推到联络站」推上来的进场包：按里面的【返回格式】做完，用 send_to_moling 把结论整段发回来，开头保留 [[外脑:ob-…]]。\n" +
            "推上黑板只代表到了这里；发回来也只到收件箱，不代表他看过任何回答。\n"

    /** B4：现在该在联络站黑板上的 RELAY 会话（推上去 / 还在等；结果回来、取消、到期就撤下）。 */
    /**
     * 能上黑板的 RELAY 会话：他点过「推到联络站」的（OPENED），和已经推上去、还在等结果的。
     * 旧版本“复制并打开”留下的、从没推过的 WAITING 不算——他没点过推，就不替他推。
     * 刚点的（OPENED）排在前面：黑板放不下时先挤掉旧的，不挤掉他刚点的。
     */
    private fun relayLive(state: OutbrainState, now: Long): List<OutbrainSession> = state.sessions.filter {
        it.transport == OutbrainTransport.RELAY &&
            (it.status == OutbrainStatus.OPENED || (it.status == OutbrainStatus.WAITING_RESULT && pushedToBoard(it))) &&
            (it.expiresAt == 0L || it.expiresAt > now)
    }.sortedBy { if (it.status == OutbrainStatus.OPENED) 0 else 1 }

    /** B4：这次黑板真正带得下的 RELAY 会话（宿主推送成功后按它把 OPENED 标成“推上去了”）。 */
    fun relayBoardSessions(state: OutbrainState, now: Long, maxChars: Int = RELAY_BOARD_MAX_CHARS): List<OutbrainSession> {
        val live = relayLive(state, now)
        if (live.isEmpty() || maxChars <= RELAY_BOARD_HEADER.length) return emptyList()
        val out = mutableListOf<OutbrainSession>()
        var len = RELAY_BOARD_HEADER.length
        for (s in live.take(RELAY_BOARD_MAX_SESSIONS)) {
            val t = s.pack.text()
            if (len + t.length + 2 > maxChars) break
            out += s
            len += t.length + 2
        }
        return out
    }

    /**
     * B4：联络站黑板上的外脑段。只放上面 [relayBoardSessions] 选中的会话，
     * 这样 MCP 那边 read_moling_board 能看到整包；段里写清“推上黑板”和“他看过回答”是两回事。
     */
    fun relayBoard(state: OutbrainState, now: Long, maxChars: Int = RELAY_BOARD_MAX_CHARS): String {
        val live = relayLive(state, now)
        if (live.isEmpty()) return ""
        val chosen = relayBoardSessions(state, now, maxChars)
        if (chosen.isEmpty()) return ""
        val out = StringBuilder(RELAY_BOARD_HEADER)
        chosen.forEach { out.append('\n').append(it.pack.text()).append('\n') }
        if (live.size > chosen.size) {
            out.append("…（还有 ").append(live.size - chosen.size).append(" 个外脑会话没放进黑板，先在收线卡里处理）\n")
        }
        val text = out.toString().trim()
        return if (text.length > maxChars) text.take(maxChars).trimEnd() else text
    }

    /** B4：这个会话是不是真的推上黑板过（“打开过”不算；看 relayPushed 记的时间线）。 */
    fun pushedToBoard(s: OutbrainSession): Boolean =
        s.timeline.any { it.kind == OutbrainEventKind.OPENED && it.note.startsWith("进场包推上黑板") }

    /**
     * B4：联络站的进场包已经推上黑板（只代表发出去了，不代表外脑读到了）。
     * 进 WAITING_RESULT；已经收到最终结论的会话不改状态。
     */
    fun relayPushed(state: OutbrainState, sessionId: String, now: Long): Pair<OutbrainState, PortResult<OutbrainSession>> =
        move(state, sessionId, now, setOf(OutbrainStatus.PREPARED, OutbrainStatus.OPENED, OutbrainStatus.WAITING_RESULT)) {
            it.copy(
                openedAt = if (it.openedAt == 0L) now else it.openedAt,
                status = OutbrainStatus.WAITING_RESULT,
            ).withEvent(now, OutbrainEventKind.OPENED, "进场包推上黑板了（等外脑来读，不是已读）")
        }

    /**
     * 该提醒/挂通知的那一条：跳过点了「稍后」且还没到点的。
     * 优先还在等的，其次退场/过期没收到结论的；没有就 null（宿主撤掉常驻通知）。
     */
    fun reminder(state: OutbrainState, now: Long): OutbrainSession? {
        val awake = state.sessions.filter { it.snoozeUntil <= now }
        return awake.lastOrNull { s ->
            (s.status == OutbrainStatus.PREPARED || s.status == OutbrainStatus.OPENED ||
                s.status == OutbrainStatus.WAITING_RESULT) && s.closedAt == 0L
        } ?: awake.lastOrNull { s ->
            (s.closedAt > 0 || s.status == OutbrainStatus.EXPIRED) &&
                s.status != OutbrainStatus.CANCELLED && s.status != OutbrainStatus.REVIEWED &&
                s.status != OutbrainStatus.RESULT_RECEIVED && finalResults(state, s.id).isEmpty()
        }
    }

    /**
     * 模型 API 只在「还没送出结果、也没有最终结论」时能问一次：
     * 已经取消/过期/收到结论，或者正在等，就不再调模型（防重复花钱）。
     * 「正在跑」的标记由宿主管（同一个会话同时只允许一个请求）。
     */
    fun apiAskable(state: OutbrainState, sessionId: String): Boolean {
        val s = state.sessions.firstOrNull { it.id == sessionId } ?: return false
        if (s.status != OutbrainStatus.PREPARED && s.status != OutbrainStatus.OPENED) return false
        return finalResults(state, sessionId).isEmpty()
    }

    fun latest(state: OutbrainState, focusId: String? = null): OutbrainSession? =
        state.sessions.lastOrNull { focusId == null || it.focusId == focusId }

    fun finalResults(state: OutbrainState, sessionId: String): List<ActorResult> =
        state.results.filter { it.sessionId == sessionId && !it.partial }

    fun results(state: OutbrainState, sessionId: String): List<ActorResult> =
        state.results.filter { it.sessionId == sessionId }

    // -------------------------------------------------------------- 内部

    private fun mkResult(
        seq: Long,
        sessionId: String,
        text: String,
        partial: Boolean,
        turn: Int,
        source: String,
        actor: Actor,
        now: Long,
    ) = ActorResult("r-" + seq, sessionId, actor, text, Packs.hash(text), partial, turn, source, now)

    private fun move(
        state: OutbrainState,
        sessionId: String,
        now: Long,
        from: Set<OutbrainStatus>,
        change: (OutbrainSession) -> OutbrainSession,
    ): Pair<OutbrainState, PortResult<OutbrainSession>> {
        val s = state.sessions.firstOrNull { it.id == sessionId }
            ?: return state to fail(PortError.NOT_FOUND, "这个外脑会话不在了")
        if (s.status !in from) return state to fail("BAD_STATE", "现在是 " + s.status + "，这一步做不了")
        val n = change(s)
        return commit(state, n) to PortResult.Ok(n)
    }

    private fun commit(state: OutbrainState, s: OutbrainSession): OutbrainState =
        state.copy(sessions = state.sessions.map { if (it.id == s.id) s else it })

    private fun fail(code: String, message: String): PortResult.Failed = PortResult.Failed(PortError(code, message))
}
