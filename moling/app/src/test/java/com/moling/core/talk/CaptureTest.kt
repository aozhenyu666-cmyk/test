package com.moling.core.talk

import com.moling.core.workspace.PortResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第十二批 A4：限定捕获只做接口 + 拒绝降级（不申请权限）。 */
class CaptureTest {
    private val policy = CapturePolicy(enabled = true, maxTtlMs = 600_000)
    private val selection = CaptureSelection(
        pkg = "com.example.chat",
        certSha256 = "CERT-A",
        uid = 10_123,
        titleOnly = false,
        visibleText = true,
        ttlMs = 120_000,
    )

    private fun lease(now: Long = 1_000, sel: CaptureSelection = selection): CaptureLease =
        (Capture.begin(policy, sel, now) as PortResult.Ok).value

    private fun window(revision: Long = 1, rotation: Int = 0) = CaptureWindow("com.example.chat", revision, 1080, 2400, rotation)

    private fun node(id: String, text: String = "x", role: String = "PARAGRAPH", bounds: CaptureBounds? = null) =
        CaptureNode(id, null, role, text, bounds)

    @Test
    fun beginNeedsExplicitUserSelectionAndEnable() {
        assertEquals(
            "CAPTURE_OFF",
            ((Capture.begin(CapturePolicy(enabled = false), selection, 1)) as PortResult.Failed).error.code,
        )
        assertEquals(
            "DENIED",
            ((Capture.begin(policy, selection.copy(pkg = " "), 1)) as PortResult.Failed).error.code,
        )
        val l = lease()
        assertEquals(CaptureStatus.RUNNING, l.status)
        assertEquals("用户选的 TTL 不能被墨灵放大", 120_000, l.expiresAt - l.createdAt)
        val capped = (Capture.begin(policy.copy(maxTtlMs = 60_000), selection, 1) as PortResult.Ok).value
        assertEquals("超过总控上限就压到上限", 60_000, capped.expiresAt - capped.createdAt)
    }

    @Test
    fun callerIsVerifiedByUidPackageAndCertificate() {
        val l = lease()
        assertTrue(Capture.verifyCaller(l, 10_123, "com.example.chat", "CERT-A") is PortResult.Ok)
        assertEquals("UID 不对就拒", "DENIED", ((Capture.verifyCaller(l, 99, "com.example.chat", "CERT-A")) as PortResult.Failed).error.code)
        assertEquals("包名对不上就拒", "DENIED", ((Capture.verifyCaller(l, 10_123, "com.evil.chat", "CERT-A")) as PortResult.Failed).error.code)
        assertEquals("证书对不上就拒", "DENIED", ((Capture.verifyCaller(l, 10_123, "com.example.chat", "CERT-B")) as PortResult.Failed).error.code)
        val noUid = (Capture.begin(policy, selection.copy(uid = -1), 1) as PortResult.Ok).value
        assertEquals("没绑 UID 的租约不认", "DENIED", ((Capture.verifyCaller(noUid, 1, "com.example.chat", "CERT-A")) as PortResult.Failed).error.code)
    }

    @Test
    fun pollPagesByNodeCharAndByteLimits() {
        val l = lease()
        val many = (1..70).map { node("n-" + it, "0123456789") }
        val snap = CaptureSnapshot(1_000, window(), many)
        val p1 = (Capture.poll(l, listOf(snap), CaptureCursor(), 1_000, "com.example.chat", permission = true, enabled = true) as PortResult.Ok).value
        assertEquals(Capture.MAX_NODES, p1.nodes.size)
        assertTrue(p1.partial)
        assertFalse(p1.done)
        assertEquals(Capture.MAX_NODES, p1.cursor.offset)
        val p2 = (Capture.poll(l, listOf(snap), p1.cursor, 1_000, "com.example.chat", permission = true, enabled = true) as PortResult.Ok).value
        assertEquals(6, p2.nodes.size)
        assertTrue(p2.done)
        assertFalse(p2.gap)
        // 字节/字数上限：40 条 1000 字，第一页装不下
        val fat = (1..40).map { node("f-" + it, "y".repeat(1000)) }
        val pf = (Capture.poll(l, listOf(CaptureSnapshot(1_000, window(), fat)), CaptureCursor(), 1_000, "com.example.chat", permission = true, enabled = true) as PortResult.Ok).value
        assertTrue("8192 字封顶", pf.chars <= Capture.MAX_CHARS)
        assertTrue("32KiB 封顶", pf.bytes <= Capture.MAX_PACKET_BYTES)
        assertTrue(pf.partial)
        // 游标对不上：标 gap 并从头来，不假装连续
        val bad = (Capture.poll(l, listOf(snap), CaptureCursor(9_999), 1_000, "com.example.chat", permission = true, enabled = true) as PortResult.Ok).value
        assertTrue(bad.gap)
        assertEquals("从头分页，下一页从这一页末尾接着走", Capture.MAX_NODES, bad.cursor.offset)
    }

    @Test
    fun tooManySnapshotsMarkGapAndOnlyFourAreUsed() {
        val l = lease()
        val snaps = (1..6).map { i -> CaptureSnapshot(1_000, window(), listOf(node("s-" + i))) }
        val p = (Capture.poll(l, snaps, CaptureCursor(), 1_000, "com.example.chat", permission = true, enabled = true) as PortResult.Ok).value
        assertTrue("超过 4 个快照：漏页", p.gap)
        assertEquals("只用前 4 个快照", 4, p.nodes.size)
        val lost = (Capture.poll(l, listOf(CaptureSnapshot(1_000, window(), listOf(node("z")))), CaptureCursor(), 1_000, "com.example.chat", permission = true, enabled = true, lostPages = true) as PortResult.Ok).value
        assertTrue(lost.gap)
    }

    @Test
    fun onlyObservedSignalsNoGestureGuess() {
        assertTrue(Capture.signals(listOf(CaptureSnapshot(1, window()))).isEmpty())
        assertEquals(listOf("SCROLL_OBSERVED"), Capture.signals(listOf(CaptureSnapshot(1, window(), scrolled = true))))
        assertEquals(listOf("WINDOW_SWITCH"), Capture.signals(listOf(CaptureSnapshot(1, window(), switched = true))))
    }

    @Test
    fun boundsExpireAfterTwoSecondsAndOnWindowChange() {
        val w = window()
        val b = CaptureBounds(10, 20, 200, 300)
        val n = node("b-1", bounds = b)
        val snap = CaptureSnapshot(1_000, w, listOf(n))
        assertTrue(Capture.boundsValid(n, snap, 2_000, w))
        assertFalse("两秒后失效", Capture.boundsValid(n, snap, 3_001, w))
        assertFalse("旋转失效", Capture.boundsValid(n, snap, 1_500, w.copy(rotation = 90)))
        assertFalse("窗口修订变了失效", Capture.boundsValid(n, snap, 1_500, w.copy(revision = 2)))
        assertFalse("窗口尺寸变了失效", Capture.boundsValid(n, snap, 1_500, w.copy(width = 500)))
        assertFalse("越界坐标不算", Capture.boundsValid(node("x", bounds = CaptureBounds(0, 0, 5_000, 5_000)), snap, 1_500, w))
        assertFalse("没有坐标就不定位", Capture.boundsValid(node("y"), snap, 1_500, w))
    }

    @Test
    fun stopReasonsAreVisible() {
        val l = lease()
        assertNull("正常在范围里", Capture.check(l, 1_500, "com.example.chat", permission = true, enabled = true))
        assertEquals(CaptureStatus.STOPPED, Capture.check(l, 1_500, "com.example.chat", permission = true, enabled = false)!!.status)
        assertEquals(CaptureStatus.DENIED, Capture.check(l, 1_500, "com.example.chat", permission = false, enabled = true)!!.status)
        assertEquals(CaptureStatus.EXPIRED, Capture.check(l, 200_000, "com.example.chat", permission = true, enabled = true)!!.status)
        assertEquals(CaptureStatus.OUT_OF_SCOPE, Capture.check(l, 1_500, "com.other", permission = true, enabled = true)!!.status)
        val stopped = Capture.stop(l, 1_600, CaptureStatus.REVOKED, "用户撤权")
        assertEquals(CaptureStatus.REVOKED, Capture.check(stopped, 1_700, "com.example.chat", permission = true, enabled = true)!!.status)
    }

    @Test
    fun pollRejectsOutOfScopeAndCallerStops() {
        val l = lease()
        val snap = CaptureSnapshot(1_000, window(), listOf(node("n-1")))
        val out = Capture.poll(l, listOf(snap), CaptureCursor(), 1_000, "com.example.chat", permission = true, enabled = false)
        assertTrue(out is PortResult.Failed)
        val other = Capture.poll(l, listOf(snap), CaptureCursor(), 1_000, "com.other", permission = true, enabled = true)
        assertEquals("OUT_OF_SCOPE", (other as PortResult.Failed).error.code)
        val wrongPkg = Capture.poll(l, listOf(CaptureSnapshot(1_000, window().copy(pkg = "com.other"), listOf(node("n-1")))), CaptureCursor(), 1_000, "com.example.chat", permission = true, enabled = true)
        assertEquals("快照不是用户选的应用也拒", "OUT_OF_SCOPE", (wrongPkg as PortResult.Failed).error.code)
    }

    @Test
    fun titleOnlyByDefaultAndProbeDegrade() {
        val l = (Capture.begin(policy, selection.copy(titleOnly = true, visibleText = false), 1_000) as PortResult.Ok).value
        val snap = CaptureSnapshot(1_000, window(), listOf(node("t-1", "标题", "TITLE"), node("p-1", "正文段")))
        val p = (Capture.poll(l, listOf(snap), CaptureCursor(), 1_000, "com.example.chat", permission = true, enabled = true) as PortResult.Ok).value
        assertEquals("默认只拿标题", listOf("标题"), p.nodes.map { it.text })

        val none = Capture.probe(null)
        assertFalse(none.supported)
        assertTrue(none.reason.contains("看不到"))
        val probe = Capture.probe(snap)
        assertTrue(probe.supported)
        assertTrue(probe.hasTitle)
        assertTrue(probe.hasParagraphs)
        val degrade = Capture.degrade(CaptureStatus.UNSUPPORTED, "游戏画面")
        assertEquals("分享 / 粘贴", degrade.route)
        assertTrue(degrade.message.contains("分享"))
        assertEquals("UNSUPPORTED", Capture.degrade(CaptureStatus.UNSUPPORTED, "x").status.name)
        assertNotNull(Capture.degrade(CaptureStatus.DENIED, "没权限"))
    }
}
