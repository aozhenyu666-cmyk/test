package com.yishou.app.healthwire

import com.yishou.app.health.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地规则（规范 §4）：字段一个不能少、只能改自己的规则、原子语义由 PolicyStore 负责、
 * 过期必须停止执行、紧急暂停不删规则。
 */
class LocalPolicyTest {

    private fun policy(
        id: String = "p1",
        revision: Int = 1,
        issuer: String = "yishou",
        start: Long = 1_000,
        end: Long = 10_000,
        enabled: Boolean = true,
        required: Set<String> = setOf("com.yishou.app"),
        lockAt: Long? = null,
        failure: String = "LOCK",
        emergency: String = "NONE",
    ) = Policy(
        policyId = id,
        revision = revision,
        issuer = issuer,
        requiredApps = required,
        startsAtWall = start,
        endsAtWall = end,
        lockAtWall = lockAt,
        failureAction = failure,
        repairGraceMs = 120_000,
        emergencyState = emergency,
        enabled = enabled,
    )

    @Test
    fun `字段完整往返 JSON 不丢`() {
        val p = policy(
            lockAt = 5_000,
            required = setOf("com.zongkong.app", "com.yishou.app", "cn.companion.pet"),
        )
        val text = LocalPolicy.toJson(LocalPolicy.Doc(policies = listOf(p)))
        val back = LocalPolicy.parse(text)
        assertEquals(LocalPolicy.SCHEMA_VERSION, back.schemaVersion)
        assertEquals(1, back.policies.size)
        val r = back.policies.single()
        assertEquals(p, r)
        // 规范 §4 的字段一个不能少
        assertTrue(text.contains("schemaVersion"))
        assertTrue(text.contains("policyId"))
        assertTrue(text.contains("revision"))
        assertTrue(text.contains("issuer"))
        assertTrue(text.contains("requiredApps"))
        assertTrue(text.contains("startsAtWall"))
        assertTrue(text.contains("endsAtWall"))
        assertTrue(text.contains("lockAtWall"))
        assertTrue(text.contains("failureAction"))
        assertTrue(text.contains("repairGraceMs"))
        assertTrue(text.contains("emergencyState"))
        assertTrue(text.contains("enabled"))
    }

    @Test
    fun `坏文件退回空规则 不崩`() {
        val doc = LocalPolicy.parse("{这不是 json")
        assertTrue(doc.policies.isEmpty())
        assertEquals(0, doc.revision)
        assertTrue(LocalPolicy.parse(null).policies.isEmpty())
        assertTrue(LocalPolicy.parse("").policies.isEmpty())
    }

    @Test
    fun `窗口过期必须停止执行`() {
        val p = policy(start = 1_000, end = 10_000)
        assertTrue(p.effective(5_000))
        assertFalse("窗口结束必须停", p.effective(10_000))
        assertFalse(p.effective(999))
    }

    @Test
    fun `没启用的规则不生效`() {
        val p = policy(enabled = false)
        assertFalse(p.effective(5_000))
        assertTrue(LocalPolicy.Doc(policies = listOf(p)).effectiveAt(5_000).isEmpty())
    }

    @Test
    fun `只接受 issuer 是自己的规则`() {
        val doc = LocalPolicy.Doc(policies = emptyList())
        val (after, error) = LocalPolicy.merge(doc, policy(issuer = "zongkong"))
        assertNotNull(error)
        assertTrue(error!!.contains("yishou"))
        assertTrue(after.policies.isEmpty())
    }

    @Test
    fun `旧 revision 不覆盖新规则`() {
        val base = LocalPolicy.Doc(policies = listOf(policy(revision = 5, end = 20_000)))
        val (after, error) = LocalPolicy.merge(base, policy(revision = 4, end = 9_999))
        assertNull("旧版本只是忽略，不算错误", error)
        assertEquals(20_000L, after.policies.single().endsAtWall)
    }

    @Test
    fun `新 revision 覆盖并保持一条`() {
        val base = LocalPolicy.Doc(policies = listOf(policy(revision = 1, end = 10_000)))
        val (after, error) = LocalPolicy.merge(base, policy(revision = 2, end = 20_000))
        assertNull(error)
        assertEquals(1, after.policies.size)
        assertEquals(20_000L, after.policies.single().endsAtWall)
        assertEquals(2, after.revision)
    }

    @Test
    fun `形态讲不通的规则被拒绝`() {
        val doc = LocalPolicy.Doc()
        assertNotNull(LocalPolicy.validate(policy(start = 0).copy(startsAtWall = 0)))
        assertNotNull(LocalPolicy.validate(policy(start = 10_000, end = 1_000)))
        assertNotNull(LocalPolicy.validate(policy(required = emptySet())))
        assertNotNull(LocalPolicy.validate(policy(lockAt = 500, start = 1_000)))

        val (_, error) = LocalPolicy.merge(doc, policy(start = 10_000, end = 1_000))
        assertNotNull(error)
    }

    @Test
    fun `紧急暂停标 STOPPED 但不删规则`() {
        val doc = LocalPolicy.Doc(
            policies = listOf(policy(id = "a"), policy(id = "b")),
        )
        val stopped = LocalPolicy.stopAll(doc)
        assertEquals(2, stopped.policies.size)
        assertTrue(stopped.policies.all { it.emergencyState == "STOPPED" })
        assertTrue(LocalPolicy.anyStopped(stopped))

        val resumed = LocalPolicy.resumeAll(stopped)
        assertFalse(LocalPolicy.anyStopped(resumed))
        assertEquals(2, resumed.policies.size)
    }

    @Test
    fun `revision 取所有规则里最大的`() {
        val doc = LocalPolicy.Doc(policies = listOf(policy(id = "a", revision = 3), policy(id = "b", revision = 7)))
        assertEquals(7, doc.revision)
    }

    @Test
    fun `requiredApps 与 lockAtWall 0 值解析正确`() {
        val text = """
            {"schemaVersion":2,"policies":[{
              "policyId":"x","revision":1,"issuer":"yishou",
              "requiredApps":["com.zongkong.app","com.yishou.app","cn.companion.pet"],
              "startsAtWall":1000,"endsAtWall":2000,"lockAtWall":0,
              "failureAction":"NOTIFY_ONLY","repairGraceMs":120000,
              "emergencyState":"NONE","enabled":true}]}
        """.trimIndent()
        val r = LocalPolicy.parse(text).policies.single()
        assertEquals(3, r.requiredApps.size)
        assertNull("lockAtWall=0 表示没有", r.lockAtWall)
        assertEquals("NOTIFY_ONLY", r.failureAction)
        assertNull(LocalPolicy.validate(r))
    }
}
