package com.zongkong.core.director

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 真导演（施工队 B）。实现 [Director]，替换 [FakeDirector] 只需换构造那一行：
 *
 *   DirectorEngine(facts = 你的FactsSource, ask = 你的Ask, store = 你的MindStore)
 *
 * 一次思考：事件 → 读局面 → 本地推理（假设 + 一张模板卡）→ 能不能打扰 → 需要时让模型改写问法 → 进议程 → 记住。
 * 模型（[ask]）可以为空或失败，所有路径都有本地兜底；花费有每日字数上限。
 * 规矩：导演只出问题和建议，不放宽任何规则、不发娱乐时间（它根本没有这样的工具）。
 */
class DirectorEngine(
    private val facts: FactsSource,
    private val ask: Ask?,
    private val store: MindStore,
    private val config: () -> DirectorConfig = { DirectorConfig() },
) : Director {
    private val lock = Mutex()
    private var mind: MindState = MindCodec.decode(store.load())

    /** 调试页用：此刻的记忆快照。 */
    fun snapshot(): MindState = mind

    // ---------------------------------------------------------------- 事件

    override suspend fun onSense(sense: Sense): Card? = lock.withLock {
        val cfg = config()
        remember(sense)
        when (sense.kind) {
            SenseKind.NIGHT -> { night(sense.at); return@withLock null }
            SenseKind.INBOX -> { inbox(sense); return@withLock null }
            else -> Unit
        }
        val s = Situation.read(facts.facts(sense.at), cfg)
        val due = followUp(s, sense.at)
        val a = due ?: Reason.assess(sense, s, mind, cfg) ?: return@withLock save(null)
        a.incident?.let { inc -> mind = mind.copy(incidents = mind.incidents + inc) }
        a.hypothesis?.let { h -> mind = mind.copy(hypotheses = mind.hypotheses + h) }
        if (!mayPush(a, s, cfg)) {
            // 不打扰时只排队；这件事已经在议程里就保留原来那张卡（用户可能正看着它）。
            val existing = mind.agenda.firstOrNull { it.topic == a.topic && it.expiresAt > sense.at }
            enqueue(a.topic, a.priority, existing?.card ?: a.card, sense.at)
            return@withLock save(null)
        }
        val card = refine(a, s, cfg)
        enqueue(a.topic, a.priority, card, sense.at)
        pushed(a.topic, sense.at)
        save(withQueue(card, sense.at))
    }

    override fun currentCard(now: Long): Card? {
        val top = liveAgenda(now).maxByOrNull { it.priority } ?: return null
        return withQueue(top.card, now)
    }

    // ---------------------------------------------------------------- 回应

    override suspend fun onReply(reply: Reply): Card? = lock.withLock {
        val cfg = config()
        val now = reply.at
        val item = mind.agenda.firstOrNull { it.card.id == reply.cardId }
        val topic = item?.topic.orEmpty()
        if (reply !is Reply.Dismissed) contacted(topic)
        val next: Card? = when (reply) {
            is Reply.Continue -> null
            is Reply.Stuck -> { markStuck(""); resolve(topic, "对了"); Cards.smaller("c-$now", mind.baton, now, cfg.talkMin) }
            is Reply.Conclusion -> { done(topic); conclude(reply.text, item?.card, now) }
            is Reply.Rest -> { done(topic); rest(if (reply.minutes > 0) reply.minutes else 20, now) }
            is Reply.Wrong -> {
                resolve(topic, "错了", reply.note)
                mind = mind.copy(incidents = mind.incidents + Incident("i-$now", "猜错", now, "${item?.card?.ask.orEmpty()} ${reply.note}".trim()))
                done(topic)
                Cards.wrongGuess("c-$now", now)
            }
            is Reply.Dismissed -> {
                ignored(topic)
                mind = mind.copy(incidents = mind.incidents + Incident("i-$now", "被划掉", now, (if (reply.timeout) "超时没回应：" else "划掉：") + item?.card?.ask.orEmpty()))
                null
            }
            is Reply.Pick -> pick(reply.key, topic, item?.card, now, cfg)
        }
        save(next?.let { enqueueTransient(it, now); withQueue(it, now) })
    }

    private fun pick(key: String, topic: String, card: Card?, now: Long, cfg: DirectorConfig): Card? = when (key) {
        "work", "w_work" -> { resolve(topic, "错了", "在做事"); snooze(topic, now, 60); null }
        "busy", "w_life" -> { resolve(topic, "错了", "在忙生活的事"); snooze(topic, now, 40); null }
        "rest", "w_rest" -> { resolve(topic, "说不清", "在休息"); done(topic); rest(20, now) }
        "rest_more" -> rest(10, now)
        "stuck" -> { resolve(topic, "对了"); markStuck(""); Cards.smaller("c-$now", mind.baton, now, cfg.talkMin) }
        "play" -> { snooze(topic, now, 30); null }
        "later" -> { snooze(topic, now, 20); null }
        CHOICE_GO, CHOICE_FIX, "w_other" -> { done(topic); null }
        "go_on" -> {
            done(topic)
            val b = mind.baton
            if (b == null) null else Card("c-$now", Mode.TRAIN, "墨灵 · 接着想", b.nextAsk.ifBlank { b.question }, batonId = b.id, createdAt = now)
        }
        "started" -> { finishFollowUp(topic, "开始了"); null }
        "moved" -> { finishFollowUp(topic, "改时间了"); null }
        "notyet" -> { finishFollowUp(topic, "还没开始"); Cards.smaller("c-$now", mind.baton, now, cfg.talkMin) }
        "s_material", "s_idea", "s_unwilling" -> {
            val label = card?.choices?.firstOrNull { it.key == key }?.label ?: key
            markStuck(label)
            done(topic)
            // v4：不再只说“去聊”，先把第一问摆出来（来自知识库 / 接力棒），用户照着念就行
            val plan = Openings.plan(mind.activeTopic, mind.baton, mind.baton?.question ?: "这一块", cfg.talkMin)
            Card(
                "c-$now", Mode.START, "墨灵 · 拆小一点",
                plan.asks.first(),
                sayLines = plan.says,
                choices = listOf(Choice(CHOICE_GO, "去聊 ${cfg.talkMin} 分钟"), Choice("later", "20 分钟后")),
                actor = Actor.GEMINI_LIVE, minutes = cfg.talkMin, batonId = mind.baton?.id.orEmpty(), createdAt = now,
            )
        }
        "s_tired" -> { markStuck("累"); done(topic); rest(20, now) }
        "c_old", "c_new", "c_none" -> { settleConflict(topic, key, now); done(topic); null }
        "kb_yes", "kb_no" -> { markKb(topic, key == "kb_yes", now); done(topic); null }
        else -> { done(topic); null }
    }

    // ---------------------------------------------------------------- 会话

    override suspend fun opening(actor: Actor, cardId: String?, now: Long): Opening = lock.withLock {
        val cfg = config()
        val s = Situation.read(facts.facts(now), cfg)
        val card = mind.agenda.firstOrNull { it.card.id == cardId }?.card
        val baton = mind.baton
        val goal = Openings.goal(card, baton, s.facts)
        val minutes = card?.minutes?.takeIf { it > 0 } ?: cfg.talkMin
        val sid = "s-$now"

        // 没有知识库时，第一场就建一个：用主线当这一块事的名字。空架子的问题从这一步开始修。
        val kb0 = mind.activeTopic ?: s.facts.mainTitle.takeIf { it.isNotBlank() }?.let { Knowledge.ensure(mind.topicsKB, it, goal, now) }
        if (kb0 != null) mind = mind.copy(topicsKB = Knowledge.upsert(mind.topicsKB, kb0), activeTopicId = kb0.id)
        val kb = kb0

        val plan = Openings.plan(kb, baton, goal, minutes)
        var cards = Openings.localCards(sid, actor, plan, minutes, now, baton?.id.orEmpty())
        val reply = model(Prompts.system(cfg), Prompts.session(s, baton, goal, actor, minutes, kb), now)
        if (reply != null) {
            val t = Tags.parse(reply)
            val qs = listOfNotNull(t["第一问"], t["第二问"], t["第三问"]).filter { it.length in 4..80 }
            val says = listOfNotNull(t["念一"], t["念二"]).filter { it.length in 4..80 }
            if (qs.size == 3) {
                cards = qs.mapIndexed { i, q ->
                    cards[i].copy(ask = q, sayLines = if (i == 0 && says.isNotEmpty()) says else cards[i].sayLines)
                } + cards.last()
            }
        }
        val diary = mind.diary.lastOrNull()
        val mode = if (card?.mode == Mode.TRAIN) Mode.TRAIN else Mode.WORK
        Opening(sid, actor, Openings.text(cfg, actor, s, baton, plan, minutes, diary, kb), cards, minutes, mode).also { save(null) }
    }

    override suspend fun onSession(log: SessionLog): Card? = lock.withLock {
        mind = mind.copy(sessions = mind.sessions + log)
        if (!log.handoffOk) mind = mind.copy(incidents = mind.incidents + Incident("i-${log.endedAt}", "交接失败", log.endedAt, "${log.actor.label}：${log.handoff}"))
        val card = if (log.conclusion.isNotBlank()) {
            contacted("")
            conclude(log.conclusion, null, log.endedAt, source = log.actor.label)
        } else {
            mind = mind.copy(incidents = mind.incidents + Incident("i-${log.endedAt}", "没收线", log.endedAt, "${log.actor.label} 待了 ${log.presentMs / 60_000} 分钟，离开 ${log.leaves} 次"))
            null
        }
        save(card?.let { enqueueTransient(it, log.endedAt); withQueue(it, log.endedAt) })
    }

    override fun nowText(now: Long): String {
        val cfg = config()
        val s = Situation.read(facts.facts(now), cfg)
        return buildString {
            appendLine("墨灵·此刻（${dayKey(now)}）")
            appendLine(s.lines(mind.baton))
            val ag = liveAgenda(now).sortedByDescending { it.priority }.take(3)
            if (ag.isNotEmpty()) appendLine("想跟你说的：" + ag.joinToString("；") { it.card.ask })
            mind.diary.lastOrNull()?.let { appendLine("她的日记（${it.day}）：${it.text}") }
            val pats = mind.memories.filter { it.kind == MemoryKind.PATTERN && it.confidence != "低" }.take(3)
            if (pats.isNotEmpty()) appendLine("她注意到：" + pats.joinToString("；") { it.text })
        }.trim()
    }

    // ---------------------------------------------------------------- 收线与接力棒

    private suspend fun conclude(text: String, card: Card?, now: Long, source: String = "用户"): Card {
        val modelReply = model(Prompts.system(config()), Prompts.closing(text, mind.baton), now)
        val c = if (modelReply != null) Conclude.fromModel(modelReply, text, now) else Conclude.local(text, now)
        return apply(c, text, card, now, source)
    }

    /** 把一次理解好的收线写进接力棒，并安排回头看。 */
    private fun apply(c: Closing, text: String, card: Card?, now: Long, source: String): Card {
        val cfg = config()
        val b0 = mind.baton
        val question = b0?.question ?: card?.ask?.take(40) ?: "今天的主线"
        val b = (b0 ?: Baton(id = "b-$now", question = question)).let {
            it.copy(
                known = listOf(it.known, c.conclusion).filter { x -> x.isNotBlank() }.joinToString("；").takeLast(300),
                stuck = c.open.ifBlank { if (c.substantial) "" else it.stuck },
                nextAsk = c.nextStep.ifBlank { it.nextAsk },
                updatedAt = now,
                version = it.version + 1,
                source = source,
            )
        }
        mind = mind.copy(batons = mind.batons.filter { it.id != b.id } + b, currentBatonId = b.id)
        // v4：收线写回知识库 —— 结论进“已知”，未解进“还说不清”，这一问问得好也记下
        val kb = mind.activeTopic
        if (kb != null && c.substantial) {
            val node = Knowledge.learn(kb, c.conclusion, c.open, card?.ask.orEmpty(), now)
            mind = mind.copy(topicsKB = Knowledge.upsert(mind.topicsKB, node), activeTopicId = node.id)
        }
        if (!c.substantial) {
            mind = mind.copy(incidents = mind.incidents + Incident("i-$now", "收线没内容", now, text.take(60)))
            return Card("c-$now", Mode.WORK, "墨灵 · 收线", "这句有点空。再说一句：具体聊出了什么？", createdAt = now)
        }
        if (c.nextStep.isNotBlank()) {
            val due = (if (c.at > now) c.at else now) + cfg.followUpMin * 60_000L
            val fu = FollowUp("f-$now", b.id, c.nextStep, due, since = if (c.at > now) c.at else now)
            mind = mind.copy(followUps = mind.followUps + fu)
        }
        val whenText = if (c.at > 0) "（${fmt(c.at, now)}）" else ""
        return Card(
            "c-$now-ok", Mode.WORK, "墨灵 · 记下了",
            "记下了：${c.conclusion.take(40)}。" + if (c.nextStep.isNotBlank()) "下一步「${c.nextStep.take(30)}」$whenText，到时候我回头看。" else "下一步是什么？",
            choices = listOf(Choice("ok", "好")),
            batonId = b.id,
            createdAt = now,
        )
    }

    /** 到点的回头看：下一步之后有没有进展。有进展就悄悄记“开始了”，没有才问。 */
    private fun followUp(s: Situation, now: Long): Assessment? {
        val fu = mind.followUps.firstOrNull { !it.done && now >= it.dueAt } ?: return null
        val progressed = s.facts.lastProgressAt > fu.since
        if (progressed) {
            mind = mind.copy(followUps = mind.followUps.map { if (it.id == fu.id) it.copy(done = true) else it })
            return null
        }
        return Assessment("followup:${fu.id}", 70, Cards.followUp("c-$now", fu.what, now))
    }

    private fun finishFollowUp(topic: String, note: String) {
        val id = topic.removePrefix("followup:")
        mind = mind.copy(followUps = mind.followUps.map { if (it.id == id) it.copy(done = true) else it })
        if (note == "还没开始") {
            val fu = mind.followUps.firstOrNull { it.id == id }
            mind = mind.copy(incidents = mind.incidents + Incident("i-f-$id", "说好的没开始", fu?.dueAt ?: 0, fu?.what.orEmpty()))
        }
        done(topic)
    }

    /** 知识体检的回答：说“弄明白了”就把那条从“还说不清”挪到“已知”。 */
    private fun markKb(topic: String, yes: Boolean, now: Long) {
        val id = topic.removePrefix("kb:")
        val node = mind.topicById(id) ?: return
        val first = node.open.firstOrNull() ?: return
        val nn = if (yes) node.copy(known = (node.known + first).takeLast(8), open = node.open - first) else node
        mind = mind.copy(topicsKB = Knowledge.upsert(mind.topicsKB, nn.copy(lastAt = now)))
    }

    private fun markStuck(label: String) {
        val b = mind.baton ?: return
        val nb = b.copy(stuck = label.ifBlank { b.stuck.ifBlank { "还没说清" } }, version = b.version + 1)
        mind = mind.copy(batons = mind.batons.map { if (it.id == b.id) nb else it })
    }

    private fun rest(min: Int, now: Long): Card {
        val main = facts.facts(now).mainTitle
        val fu = FollowUp("f-rest-$now", mind.currentBatonId, if (main.isNotBlank()) "休息结束，回到「$main」" else "休息结束", now + min * 60_000L, since = now)
        mind = mind.copy(followUps = mind.followUps + fu)
        return Cards.rest("c-$now", min, now)
    }

    // ---------------------------------------------------------------- 收件箱和记忆

    /**
     * 外部内容（Notion / Tasks / 剪贴板 / 字幕）。约定的写法：
     *   “收线｜结论｜下一步｜时间” → 当作一次收线；
     *   “记住[话题]：内容” → 一条未确认的记忆（同一话题说法不同，晚上会排进议程问用户）。
     * 其他内容只进感觉记忆，不改状态。演员说的话永远只是“说法”。
     */
    private suspend fun inbox(sense: Sense) {
        val text = sense.payload["text"].orEmpty().trim()
        val src = sense.payload["source"].orEmpty().ifBlank { sense.source }
        if (text.startsWith("收线")) {
            // 固定格式：收线｜结论｜下一步｜时间。字段齐的直接用，不再当自由文本猜。
            val parts = text.lineSequence().first().split('｜', '|').map { it.trim() }
            val concl = parts.getOrElse(1) { "" }
            if (concl.isNotBlank()) {
                val next = parts.getOrElse(2) { "" }.takeUnless { it == "无" }.orEmpty()
                val c = Closing(concl, next, Conclude.timeOf(parts.getOrElse(3) { "" }, sense.at), substantial = concl.length >= 2)
                apply(c, text, null, sense.at, src)
            }
        }
        Regex("""记住\s*[\[【]([^\]】]{1,12})[\]】]\s*[：:]\s*(.+)""").findAll(text).forEach { m ->
            val key = m.groupValues[1].trim()
            val body = m.groupValues[2].trim().take(120)
            val exists = mind.memories.any { it.key == key && it.text == body }
            if (exists) {
                mind = mind.copy(memories = mind.memories.map { if (it.key == key && it.text == body) it.copy(lastSeenAt = sense.at, hits = it.hits + 1) else it })
            } else {
                mind = mind.copy(memories = mind.memories + MemoryItem("m-${sense.at}-${mind.memories.size}", MemoryKind.PREFERENCE, key, body, src, "低", false, sense.at, sense.at))
            }
        }
        save(null)
    }

    private fun settleConflict(topic: String, key: String, now: Long) {
        val memKey = topic.removePrefix("confirm:")
        val group = mind.memories.filter { it.key == memKey && !it.confirmed }.sortedBy { it.createdAt }
        if (group.size < 2) return
        val keep = when (key) { "c_old" -> group.first(); "c_new" -> group.last(); else -> null }
        mind = mind.copy(
            memories = mind.memories.filter { it.key != memKey || it.confirmed } +
                listOfNotNull(keep?.copy(confirmed = true, confidence = "高", lastSeenAt = now)),
        )
    }

    // ---------------------------------------------------------------- 夜间整理

    private suspend fun night(now: Long) {
        val cfg = config()
        val day = dayKey(now)
        if (mind.lastNightDay == day) return
        val digest = Night.digest(mind, day)
        val diaryText = model(Prompts.system(cfg), Prompts.diary(cfg, day, digest), now)?.let { Tags.parse(it)["日记"] ?: it.trim() }?.takeIf { it.length in 10..400 }
        val diary = DiaryEntry(day, diaryText ?: Night.localDiary(mind, day), now, diaryText != null)
        val tallies = Night.tally(mind, day)
        var memories = Night.decay(Night.patterns(mind, tallies, day, now), now)
        mind = mind.copy(
            episodes = mind.episodes + Episode(day, digest, now),
            diary = mind.diary.filter { it.day != day } + diary,
            tallies = tallies,
            lastNightDay = day,
        )
        if (Night.isWeeklyReviewDay(day)) {
            val r = model(Prompts.system(cfg), Prompts.weekly(Night.weekDigest(mind, day)), now)
            val t = r?.let { Tags.parse(it) }
            val exp = t?.get("试验")
            if (!exp.isNullOrBlank()) {
                memories = memories + MemoryItem(
                    "m-w-$now", MemoryKind.TACTIC, "weekly:$day",
                    "${t["最可能的原因"].orEmpty()} → $exp（有效标准：${t["怎么算有效"].orEmpty()}）", "导演", "低", false, now, now,
                )
            }
        }
        mind = mind.copy(memories = memories)
        // v4：把今天聊出来的东西收进知识库（不只靠收线）
        mind = mind.copy(topicsKB = harvestKnowledge(mind, day, now, facts.facts(now).mainTitle))
        // 每周一次知识体检：只有“还说不清”、一条已知都没有的节点，问一句
        if (Night.isWeeklyReviewDay(day)) {
            mind.topicsKB.firstOrNull { it.open.isNotEmpty() && it.known.isEmpty() }?.let { kb ->
                enqueue("kb:${kb.id}", 45, Cards.kbCheck("c-kb-$now", kb, now), now, ttlMin = 24 * 60)
            }
        }
        Night.conflicts(memories).firstOrNull()?.let { (a, b) ->
            val card = Cards.confirm("c-conf-$now", a.text, b.text, now)
            enqueue("confirm:${a.key}", 45, card, now, ttlMin = 24 * 60)
        }
        save(null)
    }

    // ---------------------------------------------------------------- 议程、打扰节律

    private fun mayPush(a: Assessment, s: Situation, cfg: DirectorConfig): Boolean {
        val now = s.facts.now
        if (!s.canInterrupt && !a.urgent) return false
        if (s.facts.quiet) return false
        val day = dayKey(now)
        val pushes = if (mind.pushesDay == day) mind.pushesToday else 0
        if (pushes >= cfg.pushesPerDay && !a.urgent) return false
        val t = mind.topics[a.topic]
        if (t != null && t.day == day) {
            if (t.ignored >= cfg.topicPatience) return false
            if (now - t.lastAt < cfg.topicGapMin * 60_000L) return false
        }
        return true
    }

    private suspend fun refine(a: Assessment, s: Situation, cfg: DirectorConfig): Card {
        if (a.card.mode == Mode.DEGRADED) return a.card
        val r = model(Prompts.system(cfg), Prompts.card(s, mind.baton, a.card, a.hypothesis), s.facts.now) ?: return a.card
        val t = Tags.parse(r)
        val q = t["问"]?.takeIf { it.length in 4..60 } ?: return a.card
        val speak = t["朗读"]?.takeIf { it.length in 2..40 } ?: a.card.speak
        return a.card.copy(ask = q, speak = if (a.card.speak.isBlank()) "" else speak)
    }

    private fun enqueue(topic: String, priority: Int, card: Card, now: Long, ttlMin: Int = 120) {
        val item = AgendaItem(topic, priority, card, now, now + ttlMin * 60_000L)
        mind = mind.copy(agenda = mind.agenda.filter { it.topic != topic && it.expiresAt > now } + item)
    }

    /** 回应后接着出的卡也放进议程（短时有效），这样它的回应能找到来源。 */
    private fun enqueueTransient(card: Card, now: Long) = enqueue("reply:${card.id}", 95, card, now, ttlMin = 30)

    private fun liveAgenda(now: Long) = mind.agenda.filter { it.expiresAt > now && (mind.topics[it.topic]?.let { t -> t.lastAt <= now } ?: true) }

    private fun withQueue(card: Card, now: Long): Card = card.copy(queued = (liveAgenda(now).size - 1).coerceAtLeast(0))

    private fun pushed(topic: String, now: Long) {
        val day = dayKey(now)
        val t = mind.topics[topic]?.takeIf { it.day == day } ?: TopicState(day = day)
        mind = mind.copy(
            topics = mind.topics + (topic to t.copy(lastAt = now, countToday = t.countToday + 1)),
            pushesDay = day,
            pushesToday = (if (mind.pushesDay == day) mind.pushesToday else 0) + 1,
        )
    }

    private fun contacted(topic: String) {
        if (topic.isBlank()) return
        val t = mind.topics[topic] ?: return
        mind = mind.copy(topics = mind.topics + (topic to t.copy(ignored = 0)))
    }

    private fun ignored(topic: String) {
        if (topic.isBlank()) return
        val t = mind.topics[topic] ?: TopicState()
        mind = mind.copy(topics = mind.topics + (topic to t.copy(ignored = t.ignored + 1)))
    }

    /** 暂时不提这件事：把“最近一次”推到将来，议程里也先藏起来。 */
    private fun snooze(topic: String, now: Long, min: Int) {
        if (topic.isBlank()) return
        val t = mind.topics[topic] ?: TopicState(day = dayKey(now))
        mind = mind.copy(topics = mind.topics + (topic to t.copy(lastAt = now + min * 60_000L)))
    }

    private fun done(topic: String) {
        if (topic.isBlank()) return
        mind = mind.copy(agenda = mind.agenda.filter { it.topic != topic })
    }

    private fun resolve(topic: String, outcome: String, note: String = "") {
        val i = mind.hypotheses.indexOfLast { it.topic == topic && it.outcome == "待验证" }
        if (i < 0) return
        val list = mind.hypotheses.toMutableList()
        list[i] = list[i].copy(outcome = outcome, outcomeNote = note)
        mind = mind.copy(hypotheses = list)
    }

    // ---------------------------------------------------------------- 模型与花费

    /** 调模型：超出今天的字数预算、没配置、失败都返回 null。 */
    private suspend fun model(system: String, user: String, now: Long): String? {
        val a = ask ?: return null
        val cfg = config()
        val day = dayKey(now)
        val c = mind.costToday(day)
        val inChars = (system.length + user.length).toLong()
        if (c.charsIn + c.charsOut + inChars > cfg.dailyChars) return null
        val r = runCatching { a.ask(system, user) }.getOrNull()
        val nc = c.copy(calls = c.calls + 1, charsIn = c.charsIn + inChars, charsOut = c.charsOut + (r?.length ?: 0), failures = c.failures + if (r == null) 1 else 0)
        mind = mind.copy(costs = mind.costs.filter { it.day != day } + nc)
        if (r == null && c.failures + 1 == 3) mind = mind.copy(incidents = mind.incidents + Incident("i-m-$now", "模型不可用", now, "今天第 3 次调用失败，改用本地模板"))
        return r?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- 存取

    private fun remember(sense: Sense) {
        val slim = sense.copy(payload = sense.payload.mapValues { (_, v) -> v.take(200) })
        mind = mind.copy(recent = mind.recent + slim)
    }

    private fun <T> save(value: T): T {
        mind = MindCodec.trim(mind)
        runCatching { store.save(MindCodec.encode(mind)) }
        return value
    }

    private fun fmt(at: Long, now: Long): String {
        val t = java.time.Instant.ofEpochMilli(at).atZone(BEIJING)
        val today = java.time.Instant.ofEpochMilli(now).atZone(BEIJING).toLocalDate()
        val d = when (t.toLocalDate()) { today -> "今天"; today.plusDays(1) -> "明天"; else -> "${t.monthValue}月${t.dayOfMonth}日" }
        return "$d ${"%02d:%02d".format(t.hour, t.minute)}"
    }
}
