package com.yishou.app.think

import com.yishou.app.data.Bet
import com.yishou.app.data.Deed
import com.yishou.app.data.DrillRow
import com.yishou.app.data.Material
import com.yishou.app.data.PracticeDao
import com.yishou.app.data.Recall
import com.yishou.app.data.ThinkDao
import com.yishou.app.deed.Compare
import com.yishou.app.deed.Deeds
import com.yishou.app.deed.Dim
import com.yishou.app.deed.Goods
import com.yishou.app.deed.Spec
import com.yishou.app.llm.DrillGen
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordsTest {

    @Test
    fun fillerDoesNotCount() {
        assertTrue(Words.substance("嗯，那个，就是，我觉得吧……") < 3)
        assertEquals(6, Words.substance("城市应多种树"))
    }

    @Test
    fun quoteMustBeInAnswer() {
        val answer = "我觉得它是说，城市应该多种行道树，因为能降噪。"
        assertTrue(Words.quoted(answer, "城市应该多种行道树"))
        assertTrue(Words.quoted(answer, "城市应该多种行道树。"))
        // 一两个字的转写差异
        assertTrue(Words.quoted(answer, "城市应当多种行道树"))
        assertFalse(Words.quoted(answer, "行道树只是装饰"))
        assertFalse(Words.quoted(answer, ""))
    }

    @Test
    fun chineseNumbers() {
        assertEquals(listOf(3000.0, 5000.0), Words.numbers("大概三千到五千"))
        assertEquals(listOf(12000.0), Words.numbers("1.2万"))
        assertEquals(listOf(3500.0), Words.numbers("三千五"))
        assertEquals(listOf(200.0), Words.numbers("两百"))
        assertEquals(listOf(12.0), Words.numbers("十二"))
        assertEquals(listOf(105.0), Words.numbers("一百零五"))
        assertEquals(listOf(3e8), Words.numbers("3亿"))
        assertEquals(listOf(1.5), Words.numbers("一点五"))
        assertEquals(listOf(50000.0), Words.numbers("五万"))
    }
}

class LensTest {

    @Test
    fun suggestPicksAFit() {
        assertEquals(Lens.STANCE, Lens.suggest("年轻人该不该买房？").first())
        assertEquals(Lens.ASSUME, Lens.suggest("因为大家都在用，所以它一定好用。只有这样才能跟上。").first())
        assertEquals(Lens.GIST, Lens.suggest("今天天气不错，出去走走。").first())
        assertEquals(Lens.MATERIAL_LENSES.size, Lens.suggest("x").size)
    }

    @Test
    fun keyParseAndRoundTrip() {
        val o = JSONObject("""{"main":"多种行道树","points":["能降噪","能吸尘"],"explain":"转折在第三句"}""")
        val k = DrillKey.parse(Lens.GIST, o)
        assertEquals(listOf("多种行道树", "能降噪", "能吸尘"), k.points)
        assertEquals(k, DrillKey.fromJson(k.toJson()))
        val e = DrillKey.parse(Lens.ESTIMATE, JSONObject("""{"question":"北京到上海多少公里","value":1200,"unit":"公里"}"""))
        assertEquals(1200.0, e.value!!, 0.0)
        assertEquals(e, DrillKey.fromJson(e.toJson()))
    }

    @Test(expected = BadKey::class)
    fun keyNeedsPoints() {
        DrillKey.parse(Lens.ASSUME, JSONObject("""{"claim":"x","accepted":[]}"""))
    }

    @Test
    fun judgeRejectsInventedQuotes() {
        val answer = "它说要多种树"
        assertTrue(Judge.verify("主干", RawHit(true, "要多种树", ""), answer).ok)
        val fake = Judge.verify("主干", RawHit(true, "因为能降噪所以多种", ""), answer)
        assertFalse(fake.ok)
        assertTrue(fake.note.contains("不在你的回答里"))
        assertFalse(Judge.verify("主干", null, answer).ok)
    }

    @Test
    fun scores() {
        val ok = Check("a", true)
        val no = Check("b", false)
        assertEquals(100, Judge.gistScore(listOf(ok, ok, ok)))
        assertEquals(80, Judge.gistScore(listOf(ok, ok, no)))
        assertEquals(40, Judge.gistScore(listOf(no, ok, ok)))
        assertEquals(100, Judge.assumeScore(listOf(no, ok)))
        assertEquals(0, Judge.assumeScore(listOf(no, no)))
        assertEquals(60, Judge.stanceScore(listOf(ok, ok, ok, no)))
    }

    @Test
    fun thinAnswersAreNotJudged() {
        assertNotNull(Judge.tooThin(Lens.GIST, "嗯，就是那个"))
        assertNull(Judge.tooThin(Lens.GIST, "城市应该多种行道树来降噪"))
        val parts = StanceItem.entries.associateWith { "具体的一句理由" } + (StanceItem.FLIP to "嗯")
        assertTrue(Judge.stanceThin(parts)!!.contains("改主意"))
    }
}

class EstimateTest {

    @Test
    fun rangeFromSpeech() {
        assertEquals(3000.0 to 5000.0, Estimate.range("五千到三千吧"))
        val one = Estimate.range("大概1000")!!
        assertTrue(one.first < 1000 && one.second > 1000)
        assertNull(Estimate.range("不知道"))
    }

    @Test
    fun scoring() {
        assertEquals(100, Estimate.score(1000.0, 1500.0, 1200.0))
        assertEquals(60, Estimate.score(200.0, 1500.0, 1200.0))
        assertEquals(20, Estimate.score(1300.0, 2000.0, 1200.0))
        assertEquals(0, Estimate.score(5000.0, 9000.0, 1200.0))
        assertTrue(Estimate.agree(1200.0, 1500.0))
        assertFalse(Estimate.agree(1200.0, 3000.0))
        assertEquals("1.2万", Estimate.show(12000.0))
    }

    @Test
    fun calibrationLine() {
        assertTrue(Estimate.calibration(listOf(true, false)).contains("攒够"))
        assertTrue(Estimate.calibration(List(10) { it < 5 }).contains("自信过头"))
        assertTrue(Estimate.calibration(List(10) { it < 9 }).contains("挺准"))
    }
}

class CompareTest {

    @Test
    fun specs() {
        assertEquals(Spec(500.0, Dim.MASS), Spec.parse("500g"))
        assertEquals(Spec(2500.0, Dim.MASS), Spec.parse("2.5kg"))
        assertEquals(Spec(250.0, Dim.MASS), Spec.parse("半斤"))
        assertEquals(Spec(100.0, Dim.MASS), Spec.parse("2两"))
        assertEquals(Spec(1000.0, Dim.VOLUME), Spec.parse("250ml×4"))
        assertEquals(Spec(1500.0, Dim.VOLUME), Spec.parse("1.5L"))
        assertEquals(Spec(6.0, Dim.COUNT), Spec.parse("6个装"))
        assertEquals(Spec(2.0, Dim.COUNT), Spec.parse("两瓶"))
        assertNull(Spec.parse("大份"))
    }

    @Test
    fun bestByUnitPrice() {
        val goods = listOf(
            Goods("五花肉", 25.0, "500g"),
            Goods("五花肉家庭装", 44.0, "1kg"),
            Goods("鸡蛋", 12.0, "10枚"),
            Goods("前腿肉", 30.0, "750g"),
        )
        // 三件按重量比：25/斤、22/斤、20/斤
        assertEquals(3, Compare.best(goods))
        assertEquals(true, Compare.correct(goods, 3))
        assertEquals(false, Compare.correct(goods, 1))
        assertEquals(10, Compare.overpay(goods, 1))
        // 鸡蛋和肉没法比
        assertEquals(false, Compare.correct(goods, 2))
        assertNull(Compare.best(listOf(Goods("a", 1.0, "1斤"))))
    }
}

class DeedsTest {

    private fun deed(e: Double, a: Double?) = Deed(title = "买菜", plan = "", estimate = e, unit = "元", createdAt = 0, actual = a, resolvedAt = 1)

    @Test
    fun errorAndVerdict() {
        assertEquals("准", Deeds.verdict(95.0, 100.0))
        assertEquals("接近", Deeds.verdict(80.0, 100.0))
        assertEquals("偏了", Deeds.verdict(50.0, 100.0))
        assertTrue(Deeds.good(80.0, 100.0))
    }

    @Test
    fun summaryShowsBias() {
        val s = Deeds.summary(listOf(deed(80.0, 100.0), deed(70.0, 100.0), deed(90.0, 100.0), deed(100.0, null)))
        assertEquals(3, s.resolved)
        assertEquals(20, s.medianError)
        assertTrue(Deeds.line(s).contains("习惯估低"))
        assertTrue(Deeds.line(Deeds.summary(emptyList())).contains("还没有"))
    }
}

class FakeThinkDao : ThinkDao() {
    val materials = mutableListOf<Material>()
    val drills = mutableListOf<DrillRow>()
    val deeds = mutableListOf<Deed>()

    override suspend fun insertMaterial(m: Material): Long {
        val id = materials.size + 1L
        materials += m.copy(id = id)
        return id
    }
    override suspend fun updateMaterial(m: Material) {
        materials.replaceAll { if (it.id == m.id) m else it }
    }
    override suspend fun deleteMaterial(m: Material) {
        materials.removeAll { it.id == m.id }
    }
    override suspend fun getMaterial(id: Long) = materials.firstOrNull { it.id == id }
    override fun observeMaterials(): Flow<List<Material>> = emptyFlow()
    override suspend fun recentMaterials(n: Int) = materials.takeLast(n).reversed()
    override suspend fun insertDrill(d: DrillRow): Long {
        val id = drills.size + 1L
        drills += d.copy(id = id)
        return id
    }
    override suspend fun updateDrill(d: DrillRow) {
        drills.replaceAll { if (it.id == d.id) d else it }
    }
    override suspend fun drillsSince(since: Long) = drills.filter { it.createdAt >= since }
    override suspend fun drillsFor(id: Long) = drills.filter { it.materialId == id }.reversed()
    override fun observeDrillsSince(since: Long): Flow<List<DrillRow>> = emptyFlow()
    override suspend fun insertDeed(d: Deed): Long {
        val id = deeds.size + 1L
        deeds += d.copy(id = id)
        return id
    }
    override suspend fun updateDeed(d: Deed) {
        deeds.replaceAll { if (it.id == d.id) d else it }
    }
    override suspend fun deleteDeed(d: Deed) {
        deeds.removeAll { it.id == d.id }
    }
    override fun observeDeeds(): Flow<List<Deed>> = emptyFlow()
    val keys = mutableMapOf<Long, com.yishou.app.data.MaterialKey>()
    override suspend fun upsertKey(k: com.yishou.app.data.MaterialKey) {
        keys[k.materialId] = k
    }
    override suspend fun getKey(id: Long) = keys[id]
    override fun observeKeys(): Flow<List<com.yishou.app.data.MaterialKey>> = emptyFlow()
}

class FakePracticeDao : PracticeDao() {
    val bets = mutableListOf<Bet>()
    override suspend fun insertBet(bet: Bet): Long {
        bets += bet.copy(id = bets.size + 1L)
        return bets.size.toLong()
    }
    override suspend fun updateBet(bet: Bet) {}
    override fun observeBets(): Flow<List<Bet>> = emptyFlow()
    override suspend fun resolvedBets(): List<Bet> = emptyList()
    override suspend fun upsertRecall(recall: Recall) {}
    override suspend fun recallsSince(since: Long): List<Recall> = emptyList()
    override fun observeRecalls(): Flow<List<Recall>> = emptyFlow()
}

class FakeDrillGen : DrillGen {
    var keys = ArrayDeque<LlmResult<DrillKey>>()
    var reestimates = ArrayDeque<Double>()
    var points: List<RawHit?> = emptyList()
    var stance: List<RawHit?> = emptyList()
    var replyHit = RawHit(true, "", "")
    var judged = 0

    override suspend fun material(topic: String?, avoid: List<String>) = LlmResult.Ok("标题" to "正文")
    override suspend fun key(lens: Lens, material: String?) = keys.removeFirst()
    override suspend fun reestimate(question: String, material: String?): LlmResult<Double> = LlmResult.Ok(reestimates.removeFirst())
    override suspend fun judgePoints(key: DrillKey, answer: String): LlmResult<List<RawHit?>> {
        judged++
        return LlmResult.Ok(points)
    }
    override suspend fun judgeStance(question: String, parts: Map<StanceItem, String>) = LlmResult.Ok(stance to "可是房价会跌")
    override suspend fun judgeReply(question: String, counter: String, reply: String) = LlmResult.Ok(replyHit)
    var rubric: List<RawHit?> = emptyList()
    override suspend fun judgeRubric(question: String, items: List<Pair<String, String>>, parts: List<String>): LlmResult<List<RawHit?>> = LlmResult.Ok(rubric)
}

class DrillSessionTest {

    private val mat = Material(id = 1, title = "行道树", text = "行道树能遮阴。但还能降噪。因此应多种。", source = "typed", createdAt = 0)

    private fun setup(): Triple<DrillSession, FakeDrillGen, FakeThinkDao> {
        val gen = FakeDrillGen()
        val dao = FakeThinkDao().apply { materials += mat }
        return Triple(DrillSession(gen, dao, FakePracticeDao(), clock = { 1000L }), gen, dao)
    }

    @Test
    fun gistFlowVerifiesQuotesAndSaves() = runTest {
        val (s, gen, dao) = setup()
        gen.keys += LlmResult.Ok(DrillKey(Lens.GIST, Lens.GIST.ask, listOf("应多种行道树", "能降噪", "能遮阴")))
        s.start(mat, Lens.GIST)
        assertEquals(DrillStep.ANSWER, s.state.value.step)
        assertEquals(1, dao.materials[0].uses)

        // 口水不判
        s.answer("嗯，就是那个吧")
        assertNotNull(s.state.value.thin)
        assertEquals(0, gen.judged)

        // 主干引文是真的，要点 1 的引文是编的
        gen.points = listOf(RawHit(true, "应该多种行道树", ""), RawHit(true, "可以吸尘降噪", ""), RawHit(false, "", "没提遮阴"))
        s.answer("我觉得它说的是应该多种行道树")
        val st = s.state.value
        assertEquals(DrillStep.REVEAL, st.step)
        assertEquals(listOf(true, false, false), st.checks.map { it.ok })
        assertEquals(60, st.score)
        assertEquals(1, dao.drills.size)

        s.takeaway("先找“因此”")
        s.dispute()
        assertEquals("先找“因此”", dao.drills[0].takeaway)
        assertTrue(dao.drills[0].disputed)
        assertEquals(1, dao.drills.size)
    }

    @Test
    fun stanceHasOneCounterRound() = runTest {
        val (s, gen, dao) = setup()
        gen.keys += LlmResult.Ok(DrillKey(Lens.STANCE, "该不该多种行道树"))
        s.start(mat, Lens.STANCE)
        val parts = mapOf(
            StanceItem.SIDE to "该多种",
            StanceItem.WHY to "能降噪还能吸尘",
            StanceItem.AGAINST to "维护要花不少钱",
            StanceItem.FLIP to "如果维护费超过收益",
        )
        gen.stance = listOf(RawHit(true, "该多种", ""), RawHit(true, "能降噪", ""), RawHit(true, "维护要花", ""), RawHit(false, "", "条件不具体"))
        s.stance(parts)
        assertEquals(DrillStep.COUNTER, s.state.value.step)
        assertEquals(60, s.state.value.score)
        assertEquals(1, dao.drills.size)

        gen.replyHit = RawHit(true, "可以选耐旱的树种", "")
        s.reply("可以选耐旱的树种，维护费会低很多")
        assertEquals(DrillStep.REVEAL, s.state.value.step)
        assertEquals(80, s.state.value.score)
        assertEquals(80, dao.drills[0].score)
        assertEquals(1, dao.drills.size)
    }

    @Test
    fun estimateNeedsAgreeingKeys() = runTest {
        val (s, gen, _) = setup()
        val k = DrillKey(Lens.ESTIMATE, "北京到上海多少公里", value = 1200.0, unit = "公里")
        gen.keys += LlmResult.Ok(k)
        gen.reestimates += 5000.0
        gen.keys += LlmResult.Ok(k)
        gen.reestimates += 1100.0
        s.start(null, Lens.ESTIMATE)
        assertEquals(DrillStep.ANSWER, s.state.value.step)
        s.range(1500.0, 1000.0)
        val st = s.state.value
        assertEquals(1000.0, st.low!!, 0.0)
        assertTrue(st.checks.single().ok)
        assertEquals(100, st.score)
    }

    @Test
    fun estimateGivesUpWhenKeysDisagree() = runTest {
        val (s, gen, _) = setup()
        val k = DrillKey(Lens.ESTIMATE, "q", value = 1200.0, unit = "公里")
        repeat(2) {
            gen.keys += LlmResult.Ok(k)
            gen.reestimates += 9000.0
        }
        s.start(null, Lens.ESTIMATE)
        assertNotNull(s.state.value.error)
        assertNull(s.state.value.key)
    }

    @Test
    fun predictGoesToBetBook() = runTest {
        val gen = FakeDrillGen()
        val practice = FakePracticeDao()
        val s = DrillSession(gen, FakeThinkDao(), practice, clock = { 1000L })
        gen.keys += LlmResult.Ok(DrillKey(Lens.PREDICT, "下个月会不会降价"))
        s.start(mat, Lens.PREDICT)
        s.predict("下个月会降价一成", 70)
        assertEquals(DrillStep.REVEAL, s.state.value.step)
        assertEquals(-1, s.state.value.score)
        assertEquals(70, practice.bets.single().confidence)
    }

    @Test
    fun lensWithoutMaterialIsRefused() = runTest {
        val (s, _, _) = setup()
        s.start(null, Lens.GIST)
        assertNotNull(s.state.value.error)
    }

    @Test
    fun keyErrorCanRetry() = runTest {
        val (s, gen, _) = setup()
        gen.keys += LlmResult.Err(LlmError.Network("x"))
        gen.keys += LlmResult.Ok(DrillKey(Lens.ASSUME, "应多种", listOf("降噪值得花钱")))
        s.start(mat, Lens.ASSUME)
        assertNotNull(s.state.value.error)
        s.retry()
        assertEquals(DrillStep.ANSWER, s.state.value.step)
    }

    @Test
    fun statsSkipDisputed() {
        fun row(lens: Lens, score: Int, disputed: Boolean = false) =
            DrillRow(materialId = 1, lens = lens.name, key = "", answer = "", checks = "[]", score = score, disputed = disputed, durationMs = 0, createdAt = 0)
        val lines = DrillStats.lines(listOf(row(Lens.GIST, 100), row(Lens.GIST, 0, disputed = true), row(Lens.GIST, 60)))
        assertEquals(80, lines.first { it.lens == Lens.GIST }.avg)
        assertNull(lines.first { it.lens == Lens.ASSUME }.avg)
    }
}

class MaterialTextTest {

    @Test
    fun cleansShareTails() {
        val raw = "年轻人该不该买房？\n\n  很多人觉得租房更自由。但房租每年都在涨。https://example.com/a?b=1\n复制这条信息，打开App查看"
        val t = MaterialText.clean(raw)
        assertFalse(t.contains("http"))
        assertFalse(t.contains("复制这条信息"))
        assertEquals("年轻人该不该买房", MaterialText.title(t))
        assertTrue(MaterialText.usable(t))
        assertFalse(MaterialText.usable("太短"))
        assertEquals(16, MaterialText.title("一二三四五六七八九十一二三四五六七八九十").length)
    }
}
