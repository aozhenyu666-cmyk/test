package com.moling.core

import com.moling.core.atlas.Atlas
import com.moling.core.atlas.Edge
import com.moling.core.atlas.Graph
import com.moling.core.atlas.Node
import com.moling.core.atlas.NodeKind
import com.moling.core.atlas.Relation
import com.moling.core.atlas.Topic
import com.moling.core.flow.Flow
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkStatus
import com.moling.core.live.Activity
import com.moling.core.live.LiveConfig
import com.moling.core.live.Trigger
import com.moling.core.mind.Ask
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource
import com.moling.core.mind.MindStore
import com.moling.core.spar.Chesses
import com.moling.core.spar.MindAct
import com.moling.core.spar.MindMark
import com.moling.core.spar.Rpm
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** v12：思维转速、三手棋、过路费、拓扑检索、跨域连线、采蜜、规律。 */
class SparTest {
    private fun at(h: Int, mi: Int = 0, d: Int = 21) = LocalDateTime.of(2026, 10, d, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class Zk : ZkPort {
        override fun status() = ZkStatus()
        override fun send(command: ZkCommand) = ZkReceipt(true, "收下了", 0)
    }

    private class Router(val rules: List<Pair<String, String>>) : Ask {
        val prompts = mutableListOf<String>()
        override suspend fun ask(system: String, user: String): String? { prompts += user; return rules.firstOrNull { user.contains(it.first) }?.second }
    }

    private val phone = FactsSource { Facts(now = it, minute = Flow.minuteOf(it)) }
    private fun brain(ask: Ask?) = Brain(phone, ask, MemStore(), MemStore(), Zk())

    private val atlas = Atlas(
        topics = listOf(Topic("t1", "申论")),
        nodes = listOf(
            Node("n1", "t1", "开头卡住是因为没先列三点提纲", NodeKind.JUDGMENT, "笔记", createdAt = at(9, d = 10)),
            Node("n2", "t1", "列提纲能让开头更快", NodeKind.HYPOTHESIS, "笔记", check = "下次计时", createdAt = at(9, d = 10)),
            Node("n3", "t1", "每天写一段保持手感", NodeKind.METHOD, "笔记", createdAt = at(9, d = 10)),
        ),
        edges = listOf(Edge("n2", "n1", Relation.SUPPORTS)),
    )

    /** 先用 ingest 塞进同样的体系图（经过真实的合并），返回大脑。 */
    private suspend fun brainWithAtlas(ask: Router): Brain {
        val b = brain(ask)
        b.ingest("x", "笔记", "申论", at(8))
        return b
    }

    @Test fun rpm() {
        val now = at(15)
        val marks = listOf(MindMark(at(13), MindAct.CHESS), MindMark(at(14), MindAct.TAP), MindMark(at(10), MindAct.QUIZ))
        assertEquals(3, Rpm.score(marks, now))
        assertFalse(Rpm.low(marks, now))
        assertTrue(Rpm.low(marks.take(1).map { it.copy(act = MindAct.TAP) }, now))
        assertTrue(Rpm.line(marks, now).contains("下了一手棋 1"))
        assertEquals(2, Rpm.byHour(marks, Flow.dayStart("2026-10-21"))[13])
    }

    @Test fun graphRecallFollowsEdgesAndGaps() {
        val r = Graph.recall("开头又卡住了", atlas)
        assertTrue(r.first().contains("开头卡住是因为没先列三点提纲"))
        assertTrue(r.any { it.contains("列提纲能让开头更快 —支撑→ 开头卡住") })
        assertTrue(r.any { it.startsWith("  ⚠ 缺口") })
        assertEquals(atlas, Graph.link(atlas, "n2", "n1", Relation.SUPPORTS, "重复的线不加"))
        assertEquals(2, Graph.link(atlas, "n3", "n1", Relation.CAUSES, "x").edges.size)
    }

    @Test fun chessParseAndLocal() {
        val node = atlas.nodes[0]
        val c = Chesses.parseOpen("【暴论】只要列了三点提纲，开头就一定不会卡\n【漏洞】“一定”太绝对\n【选项】说得太绝对｜提纲没用｜三点太少", node, at(10), 1)!!
        assertEquals("说得太绝对", c.answer)
        assertEquals(4, c.options.size)
        assertEquals(Chesses.FREE, c.options.last())
        assertTrue(Chesses.firstRight(c, "说得太绝对"))
        val l = Chesses.local(atlas.nodes[0].copy(claim = "开头卡住是因为没先列 3 点提纲"), at(10), 0)!!
        assertTrue(l.bait.contains("7"))
    }

    @Test fun threeMovesLightUpTheAtlas() = runTest {
        val ask = Router(
            listOf(
                "【他体系图里的一条】" to "【暴论】只要列了三点提纲，开头就一定不会卡\n【漏洞】“一定”太绝对\n【选项】说得太绝对｜提纲没用｜三点太少",
                "【他第 1 手】" to "【说】推得对。\n【分】3\n【结论】列提纲能降低开头卡住的概率，但不是保证｜判断｜支撑｜中",
                "【他这一手】说得太绝对" to "【说】点中了，就是“一定”。\n【选】会更快开头｜会卡在第二段｜没变化",
                "把新材料拆成论断" to "【论断】n1｜判断｜开头卡住是因为没先列三点提纲｜中｜无\n【论断】n2｜假设｜列提纲能让开头更快｜中｜下次计时\n【关系】n2｜支撑｜n1｜",
            ),
        )
        val b = brainWithAtlas(ask)
        val t = b.spar(at(15))!!
        assertTrue(t.chess)
        assertTrue(t.line.contains("这步棋有个破绽"))
        val m1 = b.tap(t.choices.first { it == "说得太绝对" }, at(15, 1))
        assertTrue(m1.chess)
        assertTrue(m1.line.contains("第二手"))
        assertTrue(m1.choices.contains("会卡在第二段"))
        val m2 = b.tap("会卡在第二段", at(15, 2))
        assertTrue(m2.line.contains("新结论挂进体系图了"))
        assertTrue(m2.choices.isEmpty())
        assertNull(b.state().chess)
        assertEquals(3, b.state().chessDone.single().score)
        val added = b.state().atlas.nodes.single { it.source == "三手棋" }
        assertTrue(b.state().atlas.edges.any { it.from == added.id })
        assertEquals(2, b.state().mindMarks.count { it.act == MindAct.CHESS })
    }

    @Test fun sparAndTollTriggers() = runTest {
        val b = brainWithAtlas(Router(listOf("把新材料拆成论断" to "【论断】n1｜判断｜开头卡住是因为没先列三点提纲｜中｜无\n【论断】n2｜假设｜列提纲能让开头更快｜中｜下次计时\n【论断】n3｜方法｜每天写一段保持手感｜中｜无")))
        val home = Activity("launcher", "桌面", "OTHER", 3)
        assertEquals("闲着、好久没动脑", Trigger.SPAR, b.liveTrigger(home, at(15)))
        assertNull("太早不下棋", b.liveTrigger(home, at(8)))
        assertNull("“少”档不下棋", b.liveTrigger(home, at(15), cfg = LiveConfig.of(com.moling.core.live.Eagerness.LOW, true)))
        assertEquals("刚刷完一大段", Trigger.TOLL, b.liveTrigger(home, at(15), leftPlayMin = 25))
        assertNull("在娱乐里不收过路费", b.liveTrigger(home.copy(category = "ENTERTAINMENT", minutes = 1), at(15), leftPlayMin = 25))
    }

    @Test fun thinkLinksNewToOld() = runTest {
        // 同一个大脑：先放旧的再放新的
        val ask4 = object : Ask {
            var step = 0
            val prompts = mutableListOf<String>()
            override suspend fun ask(system: String, user: String): String? {
                prompts += user
                if (user.contains("把新材料拆成论断")) {
                    step++
                    return if (step == 1) "【论断】n1｜判断｜早上写比晚上写快｜中｜无" else "【论断】n1｜事实｜今天早上写得很快｜高｜无"
                }
                if (user.contains("自己的思考时间")) {
                    val newId = Regex("""(n-\d+-\d+)：今天早上写得很快""").find(user)?.groupValues?.get(1) ?: return null
                    val oldId = Regex("""(n-\d+-\d+)：早上写比晚上写快""").find(user)?.groupValues?.get(1) ?: return null
                    return "【连线】$newId｜支撑｜$oldId｜早上状态好"
                }
                return null
            }
        }
        val b4 = brain(ask4)
        b4.ingest("x", "笔记", "申论", at(9, d = 19))
        b4.ingest("y", "笔记", "申论", at(9, d = 21))
        b4.think(at(10, d = 21))
        assertEquals(1, b4.state().atlas.edges.size)
        assertEquals(Relation.SUPPORTS, b4.state().atlas.edges.single().relation)
        assertTrue(ask4.prompts.last().contains("【以前的、可能相关的】"))
    }

    @Test fun forageAndKeepBrief() = runTest {
        val ask = Router(
            listOf(
                "正反两面" to "【条】支持：先列提纲能减少开头犹豫（经验）\n【条】反对：提纲太细反而拖慢（猜测）\n【条】验证：下次两篇各计时（常识）",
                "简报（墨灵采蜜，待核）" to "【论断】n1｜判断｜提纲太细会拖慢开头｜低｜两篇计时",
                "把新材料拆成论断" to "【论断】n1｜判断｜开头卡住是因为没先列三点提纲｜中｜无\n【论断】n2｜假设｜列提纲能让开头更快｜中｜下次计时",
            ),
        )
        val b = brainWithAtlas(ask)
        val brief = b.forage(at(14))!!
        assertEquals(3, brief.lines.size)
        assertTrue("同时挂上黑板问 GPT", b.state().questions.any { it.text.contains("验证") || it.text.contains("依据") || it.text.isNotBlank() })
        assertNull("一天一次", b.forage(at(16)))
        assertEquals(1, b.keepBrief(brief.id, true, at(15)))
        assertEquals("低", b.state().atlas.nodes.single { it.source == "采蜜（待核）" }.confidence)
        assertEquals("kept", b.state().briefs.single().status)
    }

    @Test fun rulesNeedHisYes() = runTest {
        val ask = Router(
            listOf(
                "当……，就……" to "【规律】晚上 10 点后在刷｜就别讲道理，直接给三选一｜一周 5 次破雾里 4 次点了选项",
                "你被叫醒的原因" to "【出声】否",
            ),
        )
        val b = brain(ask)
        b.react(com.moling.core.live.Modality.PEEK, com.moling.core.live.Reaction.REPLIED, 2000, at(10))
        val r = b.proposeRules(at(23, 40)).single()
        assertEquals("proposed", r.status)
        b.live(Trigger.UNLOCK, null, at(23, 10), roll = 0.9)
        assertFalse("没点对之前不进提示词", ask.prompts.last().contains("她摸清的规律"))
        b.setRule(r.id, true, at(23, 41))
        b.live(Trigger.UNLOCK, null, at(23, 20, d = 21), roll = 0.9)
        assertTrue(ask.prompts.last().contains("当晚上 10 点后在刷，别讲道理，直接给三选一"))
    }
}
