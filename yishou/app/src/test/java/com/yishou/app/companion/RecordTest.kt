package com.yishou.app.companion

import com.yishou.app.companion.core.AttachmentCleanup
import com.yishou.app.companion.core.CompanionKeys
import com.yishou.app.companion.core.RecallPlan
import com.yishou.app.data.CompanionSchemaSql
import com.yishou.app.data.CompanionTurnRow
import com.yishou.app.summary.LedgerFrom
import com.yishou.app.summary.LedgerMark
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 记录：幂等键、账本口径、回响候选、迁移语句和 schema 对不对得上 */
class RecordTest {

    @Test
    fun `同一问的键一样，不同问的不一样`() {
        assertEquals(CompanionKeys.turn(3, 7), CompanionKeys.turn(3, 7))
        assertFalse(CompanionKeys.turn(3, 7) == CompanionKeys.turn(3, 8))
        assertFalse(CompanionKeys.turn(2, 7) == CompanionKeys.turn(3, 7))
    }

    @Test
    fun `跳过和没判成不算答错`() {
        assertTrue(RecallPlan.countedAsWrong("MISS"))
        assertFalse(RecallPlan.countedAsWrong("NEAR"))
        assertFalse(RecallPlan.countedAsWrong("HIT"))
        assertFalse(RecallPlan.countedAsWrong("NONE"))
        assertFalse(RecallPlan.countedAsWrong("SKIP"))
    }

    @Test
    fun `有差值的才进回响候选`() {
        val c = RecallPlan.candidate("NEAR", "他缺哪个条件？", "少了抽样这一步", "11,12")
        assertNotNull(c)
        assertEquals(listOf(11L, 12L), c!!.evidenceIds)
    }

    @Test
    fun `跳过不进候选`() {
        assertNull(RecallPlan.candidate("SKIP", "一问", "差值", "11"))
        assertNull(RecallPlan.candidate("NONE", "一问", "差值", "11"))
    }

    @Test
    fun `没答的和没差值的都不进候选`() {
        assertNull(RecallPlan.candidate("HIT", "一问", "", "11"))
        assertNull(RecallPlan.candidate("MISS", "", "差值", "11"))
    }

    @Test
    fun `证据编号写坏了也不崩，只是没编号`() {
        val c = RecallPlan.candidate("MISS", "一问", "差值", "11,坏,12")
        assertEquals(listOf(11L, 12L), c!!.evidenceIds)
    }

    @Test
    fun `删记录时附件路径去重且去掉空值`() {
        val paths = AttachmentCleanup.of(listOf("/a.jpg", null, " /a.jpg ", "", "/b.jpg"))
        assertEquals(listOf("/a.jpg", "/b.jpg"), paths)
    }

    private fun turn(verdict: String, answered: Boolean, delta: String = "差了抽样这一步") = CompanionTurnRow(
        sessionId = 1,
        turnId = 1,
        askedAt = 1_000,
        move = "找条件",
        question = "他缺哪个条件？",
        criteria = "缺抽样",
        evidenceIds = "11,12",
        sourceText = "材料原文",
        answer = if (answered) "缺样本代表性" else "",
        verdict = verdict,
        delta = delta,
        answeredAt = if (answered) 2_000 else null,
        dedupeKey = CompanionKeys.turn(1, 1),
    )

    @Test
    fun `账本口径：没答的不进账`() {
        assertNull(LedgerFrom.companionTurn(turn("HIT", answered = false)))
    }

    @Test
    fun `账本口径：跳过和没判成记一笔，不是没接住`() {
        assertEquals(LedgerMark.NONE, LedgerFrom.companionTurn(turn("SKIP", true))!!.mark)
        assertEquals(LedgerMark.NONE, LedgerFrom.companionTurn(turn("NONE", true))!!.mark)
        assertEquals(LedgerMark.MISS, LedgerFrom.companionTurn(turn("MISS", true))!!.mark)
        assertEquals(LedgerMark.HIT, LedgerFrom.companionTurn(turn("HIT", true))!!.mark)
        assertEquals(LedgerMark.NEAR, LedgerFrom.companionTurn(turn("NEAR", true))!!.mark)
    }

    @Test
    fun `账本里原文和推断分开存`() {
        val e = LedgerFrom.companionTurn(turn("NEAR", true))!!
        assertEquals("视频陪练·找条件：他缺哪个条件？", e.what)
        assertEquals("缺样本代表性", e.said)
        assertEquals("差了抽样这一步", e.note)
    }

    // ---- 迁移语句 vs Room 导出的 schema ----

    private fun schema(): JSONObject? {
        val f = File("schemas/com.yishou.app.data.AppDatabase/15.json")
        if (!f.exists()) return null
        return JSONObject(f.readText())
    }

    @Test
    fun `迁移建的表和 Room 导出的 schema 对得上`() {
        val json = schema()
        assumeTrue("没有导出 15.json，跳过（CI 会在打包时导出）", json != null)
        val entities = json!!.getJSONObject("database").getJSONArray("entities")
        val sql = CompanionSchemaSql.CREATE.joinToString("\n")

        var checked = 0
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            if (table != CompanionSchemaSql.SESSION_TABLE && table != CompanionSchemaSql.TURN_TABLE) continue
            checked++
            val cols = e.getJSONArray("fields")
            for (j in 0 until cols.length()) {
                val c = cols.getJSONObject(j)
                val name = c.getString("columnName")
                val notNull = c.getBoolean("notNull")
                assertTrue("$table 少了列 $name", sql.contains("`$name`"))
                // 非空列在 CREATE 里必须带 NOT NULL；可空列不能误写成 NOT NULL
                val at = sql.indexOf("`$name`")
                val comma = sql.indexOf(',', at).let { if (it < 0) sql.length else it }
                val columnDef = sql.substring(at, comma)
                assertEquals("$table.$name 的可空性和 schema 不一致（$columnDef）", notNull, columnDef.contains("NOT NULL"))
            }
            val indices = e.optJSONArray("indices")
            if (indices != null) {
                for (j in 0 until indices.length()) {
                    val idx = indices.getJSONObject(j)
                    val idxName = idx.getString("name")
                    assertTrue("$table 少了索引 $idxName", sql.contains("`$idxName`"))
                    if (idx.getBoolean("unique")) {
                        assertTrue("$idxName 应该是 UNIQUE", sql.contains("UNIQUE INDEX IF NOT EXISTS `$idxName`"))
                    }
                }
            }
        }
        assertTrue("schema 里没找到陪练的两张表", checked == 2)
    }

    @Test
    fun `新版本号比上一版大一`() {
        val json = schema()
        assumeTrue(json != null)
        assertEquals(15, json!!.getJSONObject("database").getInt("version"))
    }
}
