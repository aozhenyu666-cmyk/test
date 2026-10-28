package com.yishou.app.healthwire

import com.yishou.app.settings.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 把用户自己配置的陪练窗口翻译成本地规则（规范 §4）。
 * 关键：**不替用户编造每天几点锁屏**，只用用户填的时段；不生成定时锁屏事件。
 */
class RuleBuilderTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun prefs(
        windowEnabled: Boolean = true,
        hour: Int = 20,
        minute: Int = 0,
        minutes: Int = 45,
        manualStart: Long = 0,
        strict: Boolean = true,
    ) = AppPrefs(
        windowEnabled = windowEnabled,
        windowHour = hour,
        windowMinute = minute,
        windowMinutes = minutes,
        manualWindowStart = manualStart,
        windowStrict = strict,
    )

    private fun wallAt(hour: Int, minute: Int = 0): Long =
        LocalDate.of(2026, 10, 25).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `没有配置窗口时没有规则`() {
        val rules = RuleBuilder.build(
            prefs(windowEnabled = false),
            wallAt(12),
            zone,
        )
        assertTrue("不替用户编造时段", rules.isEmpty())
    }

    @Test
    fun `配置了定时窗口就生成一条对应时段的规则`() {
        val now = wallAt(19, 40)   // 窗口 20:00 开始，20 分钟内
        val rules = RuleBuilder.build(prefs(hour = 20, minute = 0), now, zone)
        val p = rules.single { it.startsAtWall == wallAt(20) }
        assertEquals(wallAt(20), p.startsAtWall)
        assertEquals(wallAt(20) + 45 * 60_000L, p.endsAtWall)
        assertEquals(LocalPolicy.ISSUER, p.issuer)
        assertEquals(RuleBuilder.REQUIRED_APPS, p.requiredApps)
        assertTrue("30 分钟内要开始 → enabled", p.enabled)
        assertNull("不编造定时锁屏事件", p.lockAtWall)
        assertEquals("LOCK", p.failureAction)
    }

    @Test
    fun `三个必需应用一个不少`() {
        val p = RuleBuilder.build(prefs(), wallAt(19, 40), zone).first()
        assertEquals(3, p.requiredApps.size)
        assertTrue(p.requiredApps.contains("com.zongkong.app"))
        assertTrue(p.requiredApps.contains("com.yishou.app"))
        assertTrue(p.requiredApps.contains("cn.companion.pet"))
    }

    @Test
    fun `窗口还很远时先入库但不启用`() {
        val rules = RuleBuilder.build(prefs(hour = 20), wallAt(8), zone)
        val later = rules.firstOrNull { it.startsAtWall == wallAt(20) }
        assertTrue("远期窗口不该提前启用", later == null || !later.enabled)
    }

    @Test
    fun `正在进行的手动窗口生成已启用的规则`() {
        val start = wallAt(20)
        val now = start + 5 * 60_000L
        val rules = RuleBuilder.build(prefs(windowEnabled = false, manualStart = start), now, zone)
        val p = rules.single()
        assertTrue(p.enabled)
        assertTrue(p.effective(now))
        assertEquals(start, p.startsAtWall)
    }

    @Test
    fun `NOTIFY_ONLY 会带进规则`() {
        val p = RuleBuilder.build(prefs(), wallAt(19, 40), zone, failureAction = "NOTIFY_ONLY").first()
        assertEquals("NOTIFY_ONLY", p.failureAction)
    }

    @Test
    fun `规则里不出现任何屏幕文字或正文内容`() {
        val text = LocalPolicy.toJson(
            LocalPolicy.Doc(policies = RuleBuilder.build(prefs(), wallAt(19, 40), zone)),
        )
        // 健康检查只带包名与时间，不收集屏幕文字（规范 §2.2 / 透明原则）
        assertTrue(text.contains("com.yishou.app"))
        assertFalse(text.contains("screen"))
        assertFalse(text.contains("text"))
        assertFalse(text.contains("content"))
    }

    @Test
    fun `重复生成同样的规则 revision 保持 1 不无限增长`() {
        val now = wallAt(19, 40)
        val a = RuleBuilder.build(prefs(), now, zone)
        val b = RuleBuilder.build(prefs(), now, zone)
        assertEquals(a, b)
        assertEquals(1, a.first().revision)
    }
}
