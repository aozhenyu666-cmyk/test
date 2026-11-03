package com.dshdriver.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 和插件 plugin/test 里的 .test.mjs 同一批场景：两份实现规则一致。 */
class DriverTest {
    private fun sec(s: Double) = (s * 1000).toLong()
    private fun sec(s: Int) = s * 1000L
    private val bard = "com.google.android.apps.bard"
    private val profile = Profile(goals = listOf("考公行测"), habits = listOf("抓到一半就下结论"))

    /** 假大脑：按水位和意图回一张卡。 */
    private val brain = Brain { r ->
        when {
            r.cog.state == Cog.HIGH_SPEED_SKIMMING -> Draft("one_word", "滑过去了——你又抓到一半就下结论。选D的依据是哪个词？", "evidence", anchor = "第3题·为什么选D", missing = "对称")
            r.intent == "resume" -> Draft("one_word", "回到第3题：除了对称，还有哪个规律？", "evidence", anchor = "第3题·第二个规律", missing = "数量")
            r.intent == "conclude" -> null
            r.intent == "open" -> Draft("open", "第3题你现在先看的是哪个规律？", "evidence", anchor = "第3题·先看哪个规律")
            else -> Draft("one_word", "对称之外，黑点数量怎么变？", "evidence", anchor = "第3题·黑点数量", missing = "递增")
        }
    }

    @Test fun mvpLoopFromProactiveOpenToConclusion() {
        val rows = mutableListOf<Map<String, Any?>>()
        val seen = mutableListOf<ComposeRequest>()
        val d = Driver(brain = { r -> seen += r; brain.compose(r) }, profile = profile, rng = { 0.5 }, sink = { rows += it })

        var out = d.start(0, "行测", "第3题图形推理", 15, listOf(bard))
        val c1 = out[0].card!!
        assertEquals(Directive.SHOW, out[0].type)
        d.surface(sec(1), c1.id, "displayed")

        out = d.reply(sec(2.5), c1.id, "选D")
        assertEquals(Cog.HIGH_SPEED_SKIMMING, d.state().cog!!.state)
        assertTrue("抓到一半就下结论" in seen.last().habits, "隐秘层进了出题请求")
        val c2 = out[0].card!!
        assertEquals("llm", c2.source)
        d.surface(sec(3), c2.id, "displayed")

        out = d.reply(sec(12), c2.id, "因为图形是左右对称的")
        assertEquals(Cog.ACTIVE_FLOW, d.state().cog!!.state)
        val c3 = out[0].card!!
        d.surface(sec(13), c3.id, "displayed")

        out = d.foreground(sec(40), "tv.danmaku.bili")
        assertEquals(Phase.INTERRUPTED_BY_SWITCH, d.state().phase)
        assertEquals(Directive.PULLBACK, out[0].type)
        assertEquals(1, out[0].level)
        assertEquals(bard, out[0].relaunch)
        assertEquals(c3.id, d.state().breakpoint!!.id)
        val pull = out[0].card!!
        assertTrue(pull.text.contains("第3题·黑点数量"))
        assertEquals("ab", pull.kind)
        d.surface(sec(41), pull.id, "displayed", "overlay")

        d.foreground(sec(45), bard)
        assertEquals(Phase.INTERRUPTED_BY_SWITCH, d.state().phase, "回到 Gemini 不等于接回")

        out = d.reply(sec(47), pull.id, choice = "A")
        assertEquals(Phase.LOCKED, d.state().phase)
        val c4 = out[0].card!!
        assertTrue(c4.text.contains("除了对称"))
        d.surface(sec(48), c4.id, "displayed")
        d.reply(sec(60), c4.id, "黑点数量每格加一")

        out = d.tick(sec(15 * 60))
        assertEquals(Phase.WRAPPING, d.state().phase)
        val cc = out[0].card!!
        assertEquals("conclusion", cc.kind)
        d.surface(sec(15 * 60 + 1), cc.id, "displayed")
        out = d.reply(sec(15 * 60 + 20), cc.id, "第3题选D，因为左右对称且黑点数量递增")
        assertEquals(Directive.END, out[0].type)
        assertEquals("completed", out[0].reason)

        val s = d.state().summary
        assertEquals(1, s.resumed)
        assertEquals(1, s.interruptions)
        assertTrue(s.advanced >= 4)
        assertTrue(s.grounded >= 3)
        assertEquals(0, s.displayedUnproven)
        val steps = rows.map { it["step"] }.toSet()
        for (k in listOf("GENERATED", "DISPLAYED", "REPLIED", "ADVANCED", "RESUMED")) assertTrue(k in steps, k)
    }

    @Test fun escalationPauseAndPullBackToSameBreakpoint() {
        val d = Driver(profile = profile, rng = { 0.5 })
        var out = d.start(0, task = "申论第1题", anchors = listOf(bard))
        val c1 = out[0].card!!
        d.surface(sec(1), c1.id, "displayed")
        out = d.foreground(sec(10), "tv.danmaku.bili")
        assertEquals(1, out[0].level)
        val pull = out[0].card!!
        assertTrue(d.tick(sec(30)).isEmpty())
        out = d.tick(sec(41))
        assertEquals(Directive.ESCALATE, out[0].type)
        assertEquals(2, out[0].level)
        out = d.tick(sec(62))
        assertEquals(Directive.ESCALATE, out[0].type, "level 2 每 20 秒重复")
        out = d.reply(sec(65), pull.id, "嗯")
        assertEquals(Phase.INTERRUPTED_BY_SWITCH, d.state().phase, "敷衍不算接回")
        assertEquals(Directive.PULLBACK, out[0].type)
        out = d.pause(sec(70), 5, "喝水")
        assertEquals(Directive.RELEASE, out[0].type)
        assertTrue(d.tick(sec(200)).isEmpty())
        out = d.tick(sec(370))
        assertEquals(Directive.PULLBACK, out[0].type)
        assertEquals(c1.id, d.state().breakpoint!!.id)
        d.reply(sec(380), out[0].card!!.id, choice = "B")
        assertEquals(Phase.LOCKED, d.state().phase)
        assertEquals(1, d.state().summary.resumed)
    }

    @Test fun silenceLadderSayToLiveThenAtomicAction() {
        val d = Driver(profile = profile, rng = { 0.5 })
        d.start(0, task = "第5题", anchors = listOf(bard))
        d.live(0, true)
        for (t in listOf(10, 25, 40, 55)) d.audio(sec(t), true)
        assertTrue(d.tick(sec(61)).isEmpty())
        assertTrue(d.tick(sec(100)).isEmpty())
        var out = d.tick(sec(115))
        assertEquals("say_to_live", out[0].card!!.kind)
        val say = out[0].card!!
        d.surface(sec(116), say.id, "displayed", "overlay")
        d.audio(sec(125), true)
        assertEquals(1, d.state().summary.advanced)
        out = d.tick(sec(185))
        assertTrue(out[0].card!!.kind in setOf("ab", "read_two_words"))
        assertTrue(out[0].card!!.intent.startsWith("dice:"))
    }

    @Test fun liveEndedCountsAsEscapeAndStopAlwaysWorks() {
        val d = Driver(brain = { null }, rng = { 0.5 })
        var out = d.start(0, task = "第7题", anchors = listOf(bard))
        assertEquals("template", out[0].card!!.source)
        out = d.live(sec(5), false)
        assertEquals("live_ended", out[0].reason)
        out = d.stop(sec(6), "call")
        assertEquals("stopped:call", out[0].reason)
        assertEquals(Phase.ENDED, d.state().phase)
    }

    /** 开一个会话，把开场卡和下一张都答掉（之后还会有一张在等）。 */
    private fun started(rng: Double = 0.0, rows: MutableList<Map<String, Any?>> = mutableListOf()): Driver {
        val d = Driver(profile = profile, rng = { rng }, sink = { rows += it })
        val out = d.start(0, "行测", "第3题", anchors = listOf(bard))
        d.surface(sec(0.5), out[0].card!!.id, "displayed")
        val r = d.reply(sec(1), out[0].card!!.id, "因为题干说只有一个条件成立")
        d.surface(sec(1.2), r[0].card!!.id, "displayed")
        d.reply(sec(2), r[0].card!!.id, "因为第二段给了前提")
        return d
    }

    private fun Driver.gemini(from: Int, to: Int) { audio(sec(from), true); audio(sec(to), false) }

    @Test fun turnGapNudgeAfterGeminiStops() {
        val rows = mutableListOf<Map<String, Any?>>()
        val d = started(rows = rows)
        d.gemini(10, 14)
        assertTrue(d.tick(sec(21)).isEmpty())
        val out = d.tick(sec(22))
        assertEquals("turn_gap", out[0].reason)
        assertEquals("say_to_live", out[0].card!!.kind)
        assertEquals(0, out[0].level)
        assertTrue(rows.any { it["kind"] == "superseded_by_turn_gap" }, "替换掉这段对话之前出的卡")

        val b = started()
        b.gemini(10, 14)
        b.audio(sec(18), true)
        assertTrue(b.tick(sec(22)).isEmpty(), "窗口里 Gemini 又开口就作废")

        val c = started()
        c.gemini(10, 11)
        assertTrue(c.tick(sec(30)).isEmpty(), "只说 1 秒不算一段")
    }

    @Test fun wakeCooldown() {
        val rows = mutableListOf<Map<String, Any?>>()
        val d = started(rows = rows)
        d.gemini(10, 14)
        val first = d.tick(sec(22))
        d.surface(sec(22.5), first[0].card!!.id, "displayed")
        d.gemini(25, 40)
        assertTrue(d.tick(sec(48)).isEmpty())
        assertTrue(rows.any { it["kind"] == "wake_skipped" && it["why"] == "cooldown" })
        d.gemini(110, 115)
        assertEquals("turn_gap", d.tick(sec(123))[0].reason)
    }

    @Test fun randomPushInterval() {
        for ((r, due) in listOf(0.0 to 120, 0.5 to 180, 1.0 to 240)) {
            val d = Driver(profile = profile, rng = { r })
            val o = d.start(0, task = "第3题", anchors = listOf(bard))
            d.reply(0, o[0].card!!.id, "因为第一段说了")
            d.reply(0, d.state().card!!.id, "因为第二段说了")
            var pushed: Pair<Int, Directive>? = null
            var t = 1
            while (t <= 250 && pushed == null) {
                if (t % 30 == 0) { d.audio(sec(t - 1), true); d.audio(sec(t - 0.5), false) }
                d.tick(sec(t)).firstOrNull()?.let { pushed = t to it }
                t++
            }
            assertEquals("periodic", pushed!!.second.reason, "rng=$r")
            assertEquals(due, pushed!!.first, "rng=$r")
        }
    }

    @Test fun noRepeatedQuestions() {
        val d = Driver(profile = profile, rng = { 0.0 })
        var out = d.start(0, task = "第3题", anchors = listOf(bard))
        val shown = mutableListOf<String>()
        for (i in 0 until 9) {
            val c = out[0].card!!
            d.surface(sec(i * 10 + 1), c.id, "displayed")
            shown += c.text
            out = d.reply(sec(i * 10 + 8), c.id, "因为第${i + 1}段说了前提")
        }
        for (i in 1 until shown.size) for (j in maxOf(0, i - 5) until i) {
            assertTrue(Similar.jaccard(shown[i], shown[j]) < 0.6, "「${shown[i]}」≈「${shown[j]}」")
        }
    }

    @Test fun brainRepeatingItselfIsReplaced() {
        val rows = mutableListOf<Map<String, Any?>>()
        val d = Driver(brain = { Draft("one_word", "黑点数量怎么变？", "evidence", anchor = "第3题") }, profile = profile, sink = { rows += it })
        var out = d.start(0, task = "第3题", anchors = listOf(bard))
        d.surface(500, out[0].card!!.id, "displayed")
        out = d.reply(sec(8), out[0].card!!.id, "因为题干说了")
        assertNotEquals("黑点数量怎么变？", out[0].card!!.text)
        assertEquals("template", out[0].card!!.source)
        assertTrue(rows.any { it["kind"] == "repeat_replaced" })
    }

    @Test fun threeTiers() {
        val d = Driver(profile = profile)
        d.start(0, task = "第3题", anchors = listOf(bard))
        d.foreground(sec(1), "tv.danmaku.bili")
        val pull = d.state().card!!
        d.surface(sec(2), pull.id, "displayed")
        val out = d.reply(sec(3), pull.id, choice = "A")
        assertEquals(1, d.state().summary.responded)
        assertEquals(0, d.state().summary.grounded, "\"A\" 不算思考")
        d.surface(sec(4), out[0].card!!.id, "displayed")
        d.reply(sec(10), out[0].card!!.id, "因为题干第二句给了条件")
        assertEquals(1, d.state().summary.grounded)

        val e = Driver(profile = profile)
        val o = e.start(0, task = "第3题", anchors = listOf(bard))
        e.surface(sec(1), o[0].card!!.id, "displayed")
        e.audio(sec(11), true)
        assertEquals(1, e.state().summary.awake)
        assertEquals(0, e.state().summary.responded)
    }

    @Test fun agendaCarriesUnresumedBreakpointToNextSession() {
        val agenda = Agenda(newId = { "A1" })
        val opener = Brain { r -> if (r.intent == "open") Draft("open", "黑点数量怎么变？", "evidence", anchor = "第3题·黑点数量", missing = "递增") else null }
        val d1 = Driver(brain = opener, profile = profile, agenda = agenda)
        val o1 = d1.start(0, "行测图形", "第3题", anchors = listOf(bard))
        d1.surface(sec(1), o1[0].card!!.id, "displayed")
        d1.foreground(sec(5), "tv.danmaku.bili")
        d1.stop(sec(6), "call")
        assertEquals(1, agenda.open().size)
        assertEquals("unresumed", agenda.open()[0].source)
        assertEquals("第3题·黑点数量", agenda.open()[0].anchor)

        val d2 = Driver(profile = profile, agenda = agenda)
        var out = d2.start(sec(100), "行测图形", "第三题", anchors = listOf(bard))
        val offer = out[0].card!!
        assertEquals("agenda", offer.intent)
        assertEquals(Options("从这接着做", "先做新的"), offer.options)
        out = d2.reply(sec(103), offer.id, choice = "A")
        assertTrue(out[0].card!!.text.contains("递增"))
        assertEquals("A1", d2.state().agendaFocus)
        d2.reply(sec(110), out[0].card!!.id, "黑点每格递增一个")
        assertEquals("done", agenda.items[0].status)
        assertNull(d2.state().agendaFocus)
    }

    @Test fun agendaDropsAfterThreeSkipsAndVerifiesQuotes() {
        val agenda = Agenda(listOf(Agenda.Item("X", "第5题：缺依据", "第5题", "missing", createdAt = 0, lastSeenAt = 0)))
        repeat(3) { i ->
            val d = Driver(profile = profile, agenda = agenda)
            val out = d.start(sec(i * 1000), task = "第5题", anchors = listOf(bard))
            assertEquals("agenda", out[0].card!!.intent)
            assertEquals("open", d.reply(sec(i * 1000 + 2), out[0].card!!.id, choice = "B")[0].card!!.intent)
        }
        assertEquals("dropped", agenda.items[0].status)

        val a = Agenda()
        assertNull(a.add("conclusion_doubt", "结论待确认", 1, quote = "好像选C", utterances = listOf("我觉得选B")))
        assertTrue(a.add("conclusion_doubt", "结论待确认", 1, quote = "好像选C", utterances = listOf("嗯 好像选C 吧")) != null)
    }

    @Test fun doubtfulConclusionGoesToAgenda() {
        val agenda = Agenda()
        val d = Driver(profile = profile, agenda = agenda)
        d.start(0, task = "第8题", minutes = 1, anchors = listOf(bard))
        val out = d.tick(sec(60))
        assertEquals(Phase.WRAPPING, d.state().phase)
        val end = d.reply(sec(70), out[0].card!!.id, "第8题可能选C，因为第二段说了前提")
        assertEquals("completed", end[0].reason)
        assertEquals(1, end[0].summary!!.grounded)
        assertTrue(agenda.open().any { it.source == "conclusion_doubt" && it.quote!!.contains("可能选C") })
    }
}

class RulesTest {
    @Test fun replyFeatures() {
        assertTrue(Text.features("嗯").filler)
        assertTrue(Text.features("知道了。").filler)
        assertEquals(0.0, Text.features("不知道").score)
        val ev = Text.features("因为题干说了只有一个条件成立")
        assertTrue(ev.evidence && ev.score >= 0.6)
        assertEquals("A", Text.choiceOf("选A"))
        assertEquals("B", Text.choiceOf("b吧"))
        assertNull(Text.choiceOf("ABC"))
        assertTrue(Text.features("排除", listOf("排除")).anchorHit)
    }

    private fun rep(text: String, latency: Long, expects: String = "evidence") = Reply(text, latency, Text.features(text), expects, "tap")

    @Test fun bandwidthStates() {
        fun c(inAnchor: Boolean = true, live: Boolean? = true, last: Long = 90_000, r: Reply? = null, recent: List<Reply> = emptyList()) =
            Bandwidth.classify(100_000, inAnchor, live, last, r, recent)
        assertEquals(Cog.ESCAPED, c(inAnchor = false).state)
        assertEquals(Cog.ESCAPED, c(live = false).state)
        assertEquals(Cog.THROTTLED_EMPTY, c(last = 0).state)
        assertEquals(Cog.THROTTLED_EMPTY, c(r = rep("嗯", 1000)).state)
        assertEquals(Cog.HIGH_SPEED_SKIMMING, c(r = rep("选D吧这个", 1500)).state)
        assertEquals(Cog.ACTIVE_FLOW, c(r = rep("因为第二段说了前提", 1500)).state)
        assertEquals(Cog.ACTIVE_FLOW, c(r = rep("A", 800, "choice")).state)
        assertEquals(Cog.THROTTLED_EMPTY, c(r = rep("好像是那个吧", 60_000)).state)
        val thin = rep("感觉是这个", 10_000)
        assertEquals("thin_trend", c(r = thin, recent = listOf(thin, thin)).reason)
        assertEquals(Cog.ACTIVE_FLOW, c(live = null).state)
    }

    @Test fun dice() {
        assertEquals("精简", Dice.pick(emptyList()))
        assertNotEquals("精简", Dice.pick(listOf("精简")))
        val h = mapOf("回想" to DieStat(4, 4), "精简" to DieStat(4, 0))
        assertEquals("回想", Dice.pick(listOf("预判"), h))
        for (d in Dice.ALL) {
            val c = Dice.card(d, "第3题")
            assertTrue(c.kind == "ab" || c.kind == "read_two_words")
            if (c.kind == "ab") assertTrue(c.options != null)
        }
    }

    @Test fun similarity() {
        assertEquals("第3题、第12题、第20题", Similar.normalizeNumbers("第三题、第十二题、第二十题"))
        assertEquals(1.0, Similar.jaccard("停在第三题", "停在第3题"))
        assertTrue(Similar.jaccard("你刚才那一步，依据是什么？一个词就行。", "拿这个依据往下走：它能排除掉哪一个？") < 0.6)
    }

    @Test fun draftValidation() {
        assertNull(Drafts.validate(Draft("open", "说说看", "evidence"), Cog.THROTTLED_EMPTY), "限速时不许开放题")
        assertNull(Drafts.validate(Draft("ab", "选哪个", "choice", Options("x", "")), Cog.ACTIVE_FLOW))
        val ok = Drafts.validate(Draft("ab", "更像哪个？", "?", Options("支持", "排除"), anchor = "第3题"), Cog.THROTTLED_EMPTY)!!
        assertEquals("choice", ok.expects)
        assertEquals("llm", ok.source)
    }
}
