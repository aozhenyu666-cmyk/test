package com.zongkong.core.vein

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VeinTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val date = day.toString()
    private fun seeded() = Veins.seed(Vein(), date)

    @Test
    fun seedBuildsStructureWithRivalRoots() {
        val v = seeded()
        assertEquals(1, v.systems.size)
        assertEquals(listOf("加工层", "检验层", "输出层", "根因"), v.systems[0].levels)
        assertEquals(7, v.nodes.size)
        val stop = v.byTitle("第一反应停止")!!
        // 两个根因都“导致”第一反应停止 → 溯源找到两个根因
        val up = Veins.trace(v, stop.id, up = true)
        assertEquals(setOf("下一问没有产生", "单步成本高、停止信号来得早"), Veins.roots(up).map { it.title }.toSet())
        val down = Veins.trace(v, stop.id, up = false)
        assertEquals("推理链断", down.single().node.title)
        assertEquals("结论不落地", down.single().children.single().node.title)
        assertEquals(3, Veins.longestChain(Veins.trace(v, v.byTitle("结论不落地")!!.id, up = true), "结论不落地").size - 1)
    }

    @Test
    fun relationsOnlySevenTypesAndSymmetric() {
        var v = seeded()
        val a = v.byTitle("依据缺失")!!.id
        val b = v.byTitle("只看一面")!!.id
        assertTrue(Veins.link(v, a, b, "引发", "", date).isFailure)
        // 类似已经从 a 指向 b，反方向再加一条不会重复
        v = Veins.link(v, b, a, Rel.LIKE, "", date).getOrThrow()
        assertEquals(1, v.nodes.sumOf { n -> n.links.count { it.type == Rel.LIKE } })
    }

    @Test
    fun healthFlagsStoriesPosingAsRules() {
        var v = seeded()
        val root = v.byTitle("下一问没有产生")!!
        val h0 = Veins.health(v, "思维问题", day)
        // 示例里非根因的假设都没写验证方式
        assertTrue(h0.issues.any { it.key == "noVerify" })
        // 把一个假设的把握调到 90：被标“可能把故事当规则”
        v = Veins.put(v, root.copy(confidence = 90), date)
        val h1 = Veins.health(v, "思维问题", day)
        assertTrue(h1.issues.first { it.key == "overconfident" }.nodes.any { it.id == root.id })
        assertTrue(h1.score < h0.score)
        // 15 天没回填 → 过期
        assertTrue(Veins.health(v, null, day.plusDays(15)).issues.any { it.key == "stale" })
    }

    @Test
    fun backfillAndMerge() {
        var v = seeded()
        val root = v.byTitle("下一问没有产生")!!
        v = Veins.backfill(v, root.id, "记录 7 天：停下时 5 次说得出下一个问题", VStatus.OVERTURNED, 20, date).getOrThrow()
        val r = v.node(root.id)!!
        assertEquals("已推翻", r.status)
        assertEquals(20, r.confidence)
        assertEquals(1, r.checks.size)
        // 合并：B 并进 A，指向 B 的关系改指 A
        val a = v.byTitle("推理链断")!!
        val b = v.byTitle("结论不落地")!!
        v = Veins.merge(v, a.id, b.id, date).getOrThrow()
        assertNull(v.node(b.id))
        assertTrue(v.node(a.id)!!.note.contains("想完了没有变成下一步动作"))
        assertTrue(v.nodes.none { n -> n.links.any { it.to == b.id } })
    }

    @Test
    fun reviewSchedule() {
        var v = seeded()
        val due = Veins.dueReviews(v, day)
        // 假设优先
        assertEquals("假设", due.first().status)
        val id = due.first().id
        var (v2, days) = Veins.grade(v, id, Veins.Grade.GOOD, day)
        assertEquals(1, days)
        v = v2
        val r2 = Veins.grade(v, id, Veins.Grade.GOOD, day.plusDays(1))
        assertEquals(3, r2.second)
        val r3 = Veins.grade(r2.first, id, Veins.Grade.FORGOT, day.plusDays(4))
        assertEquals(1, r3.second)
        assertFalse(Veins.dueReviews(r3.first, day.plusDays(4)).any { it.id == id })
    }

    @Test
    fun markdownRoundTripMatchesMailuoFormat() {
        val v = seeded()
        val files = VeinMd.export(v, date)
        assertTrue(files.containsKey("思维问题/思维问题.md"))
        assertTrue(files.containsKey("思维问题/加工层/第一反应停止.md"))
        val text = files["思维问题/加工层/第一反应停止.md"]!!
        assertTrue(text, text.startsWith("---\nid: n-0001\ntitle: 第一反应停止\ntype: 论断\nsystem: 思维问题\nparent: 加工层\nkeywords: [二次加工, 停止信号, 依据]"))
        assertTrue(text, text.contains("  - {type: 导致, to: n-0002, why: 不加工就推不下去}"))
        assertTrue(text, text.contains("## 关系\n- 导致 [[推理链断]] — 不加工就推不下去"))
        // 读回来一模一样
        val (back, count) = VeinMd.import(Vein(), files, date)
        assertEquals(7, count)
        assertEquals(v.systems.map { it.levels }, back.systems.map { it.levels })
        v.nodes.forEach { n ->
            val m = back.node(n.id)!!
            assertEquals(n.title, m.title)
            assertEquals(n.keywords, m.keywords)
            assertEquals(n.links, m.links)
            assertEquals(n.claim, m.claim)
            assertEquals(n.verify, m.verify)
            assertEquals(n.confidence, m.confidence)
        }
        assertEquals(7, back.seq)
    }

    @Test
    fun readsMailuoAppFileWithQuotesAndChecks() {
        val text = """
            ---
            id: n-0001
            title: 第一反应停止
            type: 论断
            system: 思维问题
            parent: 加工层
            keywords: [二次加工, 依据, 停止信号]
            status: 假设
            confidence: 60
            verify: "记一周\"舒缓感后多做一步\"的次数"
            source: GPT 总结
            links:
              - {type: 导致, to: n-0002, why: 不加工则推不出 D}
            checks:
              - {date: 2026-10-10, result: 记录 7 天：多做一步 2 次, status: 假设, confidence: 55}
            created: 2026-10-10
            updated: 2026-10-10
            ---
            第一反应出现后不再继续加工，把念头当答案。

            ## 关系
            - 导致 [[推理链断]] — 不加工则推不出 D
        """.trimIndent() + "\n"
        val n = VeinMd.parseNode(text, "思维问题/加工层/第一反应停止.md")!!
        assertEquals("记一周\"舒缓感后多做一步\"的次数", n.verify)
        assertEquals(listOf(VLink("导致", "n-0002", "不加工则推不出 D")), n.links)
        assertEquals(55, n.checks.single().confidence)
        assertEquals("记录 7 天：多做一步 2 次", n.checks.single().result)
        assertEquals("第一反应出现后不再继续加工，把念头当答案。", n.claim)
        // 普通笔记（没有脉络字段）不算节点
        assertNull(VeinMd.parseNode("---\nid: 2023\ntitle: 日记\n---\n今天", "日记.md"))
    }

    @Test
    fun splitParsingDropsMadeUpRelations() {
        val reply = """
            ```json
            {"nodes":[
              {"tmp_id":"t1","title":"拖延源于任务模糊","type":"论断","claim":"任务没拆清楚时更容易拖。","keywords":["任务分解","模糊","拖延"],"status":"假设","confidence":65,"verify":"记一周拆清楚前后的启动时间","parent":"根因"},
              {"tmp_id":"t2","title":"五分钟启动","type":"规则","claim":"只承诺做 5 分钟。","keywords":"启动、承诺、微习惯","status":"已验证","confidence":"80%"},
            ],
             "links":[{"from":"t2","to":"t1","type":"反例","why":"拆小以后不拖"},{"from":"t1","to":"t2","type":"引发","why":"自造"}]}
            ```
        """.trimIndent()
        val json = VeinAi.extractJson(reply)!!
        val (cands, links) = VeinAi.parseSplit(json, "拖延", "测试", "c1")
        assertEquals(2, cands.size)
        assertEquals(listOf("启动", "承诺", "微习惯"), cands[1].keywords)
        assertEquals(80, cands[1].confidence)
        assertEquals("根因", cands[0].parent)
        assertEquals(1, links.size)
        assertEquals("c1-t2", links[0].from)
        // 接受两张：关系落地
        var v = Vein(candidates = cands, candLinks = links)
        v = Veins.accept(v, "c1-t1", date).first
        v = Veins.accept(v, "c1-t2", date).first
        assertEquals(2, v.nodes.size)
        assertEquals(Rel.COUNTER, v.byTitle("五分钟启动")!!.links.single().type)
        assertTrue(v.candidates.isEmpty() && v.candLinks.isEmpty())
    }

    @Test
    fun acceptAllSkipsHypothesisWithoutVerify() {
        val c1 = Candidate("a", "有验证的假设", "论断", "x", listOf("a", "b", "c"), "假设", 50, "做个实验", system = "S")
        val c2 = Candidate("b", "没验证的假设", "论断", "y", listOf("a", "b", "c"), "假设", 50, "", system = "S")
        val (v, skipped) = Veins.acceptAll(Vein(candidates = listOf(c1, c2)), date)
        assertEquals(1, skipped)
        assertEquals(listOf("有验证的假设"), v.nodes.map { it.title })
        assertEquals(listOf("b"), v.candidates.map { it.tmp })
    }

    @Test
    fun packStaysUnderBudgetAndAsksToSeparateRivals() {
        val v = seeded()
        val root = v.byTitle("下一问没有产生")!!
        val p = VeinAi.pack(v, root.id, "哪个根因更像真的？")!!
        assertTrue(p.text, p.text.contains("【路径】思维问题 › 根因 › 下一问没有产生"))
        assertTrue(p.text, p.text.contains("对立·竞争解释｜单步成本高、停止信号来得早"))
        assertTrue(p.text, p.text.contains("请说明怎样区分这两个解释"))
        assertTrue(p.tokens < p.libraryTokens)
        // 预算很紧：邻居先变短，节点和问题永远在
        val tight = VeinAi.pack(v, root.id, "哪个根因更像真的？", budget = 60)!!
        assertTrue(tight.text.contains("【节点】下一问没有产生"))
        assertTrue(tight.text.contains("问题：哪个根因更像真的？"))
        assertTrue(tight.tokens < p.tokens)
    }

    @Test
    fun answerParsesStructureFirstFormat() {
        val a = VeinAi.parseAnswer(
            """
            **核心判断**：更像是单步成本高。
            - 结构：疲劳 → 停止信号提前 → 第一反应停止
            - 另一种可能：其实是没有下一问
            - 如果我错了，最可能错在哪：把累当成了原因
            - 把握：六成
            - 验证：记一周停下时的疲劳分
            - 建议新增：
            导致｜睡眠不足｜睡不够时每步更累｜睡眠、疲劳、认知负荷
            前置｜问题清单｜先列好下一问｜提问、清单、下一问
            """.trimIndent(),
        )
        assertTrue(a.ok)
        assertEquals("更像是单步成本高。", a.core)
        assertEquals(60, a.confidencePct)
        assertEquals(2, a.suggest.size)
        assertEquals(Rel.CAUSE, a.suggest[0].type)
        assertEquals(listOf("睡眠", "疲劳", "认知负荷"), a.suggest[0].keywords)
        val v = seeded()
        val (c, l) = VeinAi.suggestionsToCandidates(v, v.byTitle("第一反应停止")!!.id, a.suggest, "q1")
        assertEquals(2, c.size)
        assertEquals(Rel.PRE, l[1].type)
    }

    @Test
    fun duplicateHint() {
        val v = seeded()
        assertNotNull(Veins.likelyDuplicate(v, "第一反应就停止", listOf("x")))
        assertNull(Veins.likelyDuplicate(v, "睡眠不足", listOf("睡眠")))
    }
}
