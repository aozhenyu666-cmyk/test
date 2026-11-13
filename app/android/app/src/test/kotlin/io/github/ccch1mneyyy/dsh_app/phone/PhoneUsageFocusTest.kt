package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/**
 * `usage`（读系统使用账）和 `focus`（把跟他说好的计划钉住）都在 PhoneCore 里；
 * Android 那侧全是 fake，这里只验参数解析、应用名到包名的解析，以及交给模型的文本。
 */
class PhoneUsageFocusTest {
    private val apps = listOf(
        AppEntry("微信", "com.tencent.mm"),
        AppEntry("哔哩哔哩", "tv.danmaku.bili"),
        AppEntry("Notion", "notion.id"),
        AppEntry("Notion 日历", "notion.calendar"),
    )

    private var report: UsageReport? = UsageReport(emptyList(), emptyList())
    private var usageMinutes = -1
    private var workCall: Triple<String, List<String>, Int>? = null
    private var workAnswer: String? = "已进入工作模式。"

    private val core = PhoneCore(object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = PhonePolicies()
        override fun isLocked() = false
        override fun gate(): UserGate? = null
        override fun driver(screen: PhoneScreen): PhoneDriver? = null
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "unavailable"
        override fun zone() = ZoneId.of("UTC")
        override fun notifyFix(kind: String) {}
        override fun sleep(ms: Long) {}
        override fun system() = object : SystemApi {
            override fun apps() = apps
            override fun isInstalled(pkg: String) = true
            override fun launch(app: AppEntry) = error("unexpected launch")
            override fun urlTarget(url: String) = error("unexpected URL")
            override fun openUrl(url: String, target: UrlTarget) = error("unexpected URL open")
            override fun intent(spec: IntentSpec, shareTo: AppEntry?) = error("unexpected intent")
            override fun notify(title: String, text: String) = error("unexpected notify")
            override fun clipboardSet(text: String) = error("unexpected clipboard")
            override fun clipboardGet() = error("unexpected clipboard")
            override fun usage(minutes: Int, now: Long): UsageReport? {
                usageMinutes = minutes
                return report
            }
        }

        override fun workMode(mode: String, allow: List<String>, minutes: Int, why: String?): String? {
            workCall = Triple(mode, allow, minutes)
            return workAnswer
        }
    }, PhoneTasks(), ActionLog())

    @Test
    fun `both tools are in the catalog`() {
        assertTrue(PhoneToolCatalog.names.contains(PhoneToolCatalog.USAGE))
        assertTrue(PhoneToolCatalog.names.contains(PhoneToolCatalog.FOCUS))
    }

    @Test
    fun `usage asks the system for the default window`() {
        core.call(PhoneToolCatalog.USAGE, emptyMap())
        assertEquals(24 * 60, usageMinutes)
    }

    @Test
    fun `usage takes minutes when the question is about right now`() {
        core.call(PhoneToolCatalog.USAGE, mapOf("minutes" to 20))
        assertEquals(20, usageMinutes)
    }

    @Test
    fun `usage caps the window at a week`() {
        core.call(PhoneToolCatalog.USAGE, mapOf("minutes" to 999_999))
        assertEquals(7 * 24 * 60, usageMinutes)
    }

    @Test
    fun `usage caps hours the same way`() {
        core.call(PhoneToolCatalog.USAGE, mapOf("hours" to 9999))
        assertEquals(7 * 24 * 60, usageMinutes)
    }

    @Test
    fun `usage puts what is on screen right now before the totals`() {
        val now = System.currentTimeMillis()
        report = UsageReport(
            listOf(AppUsage("com.baidu.netdisk", "百度网盘", 30, 2)),
            emptyList(),
            current = "com.baidu.netdisk",
            currentSince = now - 7 * 60_000L,
        )
        val text = core.call(PhoneToolCatalog.USAGE, emptyMap()).text
        assertTrue(text, text.contains("现在在：百度网盘"))
        assertTrue(text, text.contains("已连续"))
        // 当下这一段必须在总量之前：判断走神先看现在。
        assertTrue(text, text.indexOf("现在在") < text.indexOf("按前台分钟数排"))
    }

    @Test
    fun `usage says nothing about now when the phone is not in use`() {
        report = UsageReport(listOf(AppUsage("com.tencent.mm", "微信", 5, 1)), emptyList())
        val text = core.call(PhoneToolCatalog.USAGE, emptyMap()).text
        assertFalse(text, text.contains("现在在"))
    }

    @Test
    fun `usage says how to grant the permission instead of guessing`() {
        report = null
        val out = core.call(PhoneToolCatalog.USAGE, emptyMap())
        assertTrue(out.isError)
        assertTrue(out.text.contains("使用情况访问"))
    }

    @Test
    fun `usage reports minutes, opens and the switch order`() {
        report = UsageReport(
            listOf(AppUsage("tv.danmaku.bili", "哔哩哔哩", 12, 3)),
            // SystemActions 交给上层时已经是新→旧
            listOf(UsageSwitch(60_000L, "com.tencent.mm"), UsageSwitch(0L, "tv.danmaku.bili")),
        )
        val text = core.call(PhoneToolCatalog.USAGE, emptyMap()).text
        assertTrue(text, text.contains("哔哩哔哩"))
        assertTrue(text, text.contains("tv.danmaku.bili"))
        assertTrue(text, text.contains("12 分钟"))
        assertTrue(text, text.contains("打开 3 次"))
        // 新→旧：最后一条事件排在最前面（只看切换段，上面的时长行也含包名）
        val switches = text.substringAfter("最近的切换")
        assertTrue(text, switches.indexOf("com.tencent.mm") < switches.indexOf("tv.danmaku.bili"))
    }

    @Test
    fun `focus resolves a name to its package and keeps the order`() {
        val out = core.call(
            PhoneToolCatalog.FOCUS,
            mapOf("mode" to "on", "allow" to listOf("微信", "tv.danmaku.bili"), "minutes" to 90),
        )
        assertFalse(out.isError)
        assertEquals(Triple("on", listOf("com.tencent.mm", "tv.danmaku.bili"), 90), workCall)
    }

    @Test
    fun `focus accepts one name without a list`() {
        core.call(PhoneToolCatalog.FOCUS, mapOf("mode" to "on", "allow" to "微信", "minutes" to 30))
        assertEquals(Triple("on", listOf("com.tencent.mm"), 30), workCall)
    }

    @Test
    fun `focus asks for a package when the name is ambiguous`() {
        val out = core.call(PhoneToolCatalog.FOCUS, mapOf("mode" to "on", "allow" to listOf("noti"), "minutes" to 30))
        assertTrue(out.isError)
        assertTrue(out.text, out.text.contains("notion.id"))
        assertTrue(out.text, out.text.contains("notion.calendar"))
        assertNull(workCall)
    }

    @Test
    fun `focus names the app it could not find`() {
        val out = core.call(PhoneToolCatalog.FOCUS, mapOf("mode" to "on", "allow" to listOf("没有这个"), "minutes" to 30))
        assertTrue(out.isError)
        assertTrue(out.text, out.text.contains("没有这个"))
        assertNull(workCall)
    }

    @Test
    fun `focus without mode is a status query`() {
        core.call(PhoneToolCatalog.FOCUS, emptyMap())
        assertEquals("", workCall?.first)
    }

    @Test
    fun `focus rejects a mode it does not know`() {
        val out = core.call(PhoneToolCatalog.FOCUS, mapOf("mode" to "pause"))
        assertTrue(out.isError)
        assertNull(workCall)
    }

    @Test
    fun `focus passes the engine refusal through`() {
        workAnswer = null
        val out = core.call(PhoneToolCatalog.FOCUS, mapOf("mode" to "off"))
        assertTrue(out.isError)
    }
}
