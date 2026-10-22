package com.moling.core.talk

import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import kotlinx.serialization.Serializable

/*
 * 限定捕获（v13 第十二批 A4）：只做接口 + 拒绝降级 + 文档。
 *
 * 重要：这一版**不申请**无障碍 / 屏幕录制权限，也不读任何真实节点。
 * 原因是加权限不在本批允许范围；没有权限时这里给出明确的 DENIED / UNSUPPORTED 和降级路线（分享 / 粘贴），
 * 外脑闭环不依赖捕获，捕获不可用也照常走分享收线。
 *
 * 协议按 v2.capture.begin/poll/end 的语义实现纯逻辑：
 *  - 用户先在总控里选应用/会话/TTL，产生 [CaptureSelection]；墨灵请求不能扩大这个范围（begin 只接受用户选择）。
 *  - Binder 调用方：UID → 包名 + 固定证书校验；不接受 payload 自报的包名。
 *  - 可见节点是 Android 节点树，不是 DOM：扁平 id/parentId/role/text/bounds；最多 64 节点 / 8192 字。
 *  - poll 最多 4 快照、整包 ≤ 32KiB UTF-8；超出分页，cursor 可恢复，漏页 gap=true。
 *  - 坐标含窗口修订/尺寸/旋转；两秒后、窗口变化/旋转即失效。只给自己的 HUD 定位，不跨 App 自动点击。
 *  - 只把实际观察到的滚动/窗口切换当信号；没有手势数据时不推算滑动方差或精确手指坐标。
 */

@Serializable
data class CaptureBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun inside(width: Int, height: Int): Boolean =
        left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height
}

/** 节点角色：TITLE / PARAGRAPH / INPUT / CAPTION / BUTTON / OTHER。 */
@Serializable
data class CaptureNode(
    val id: String,
    val parentId: String? = null,
    val role: String,
    val text: String = "",
    val bounds: CaptureBounds? = null,
)

/** 窗口：包名、修订、尺寸、旋转。坐标只在同一个窗口修订 + 2 秒内有效。 */
@Serializable
data class CaptureWindow(val pkg: String, val revision: Long, val width: Int, val height: Int, val rotation: Int = 0)

@Serializable
data class CaptureSnapshot(
    val at: Long,
    val window: CaptureWindow,
    val nodes: List<CaptureNode> = emptyList(),
    /** 实际观察到的滚动事件（不是推算出来的）。 */
    val scrolled: Boolean = false,
    /** 实际观察到的窗口切换。 */
    val switched: Boolean = false,
)

@Serializable
enum class CaptureStatus { OFF, RUNNING, STOPPED, EXPIRED, REVOKED, DENIED, OUT_OF_SCOPE, UNSUPPORTED }

/** 用户明确选的应用/会话；TTL 由用户定，墨灵不能自己加大。 */
@Serializable
data class CaptureSelection(
    val pkg: String,
    val certSha256: String = "",
    val uid: Int = -1,
    val titleOnly: Boolean = true,
    val visibleText: Boolean = false,
    /** 外脑会话里可见的回合（需要无障碍读取时另外算能力）。 */
    val actorTurns: Boolean = false,
    val ttlMs: Long = 10 * 60_000L,
    /** 用户自己选的具体会话/页面标题（可空）。 */
    val sessionLabel: String = "",
)

@Serializable
data class CapturePolicy(
    /** 默认关；只有用户在总控里明确开了才可能是 true。 */
    val enabled: Boolean = false,
    val maxTtlMs: Long = 30 * 60_000L,
)

@Serializable
data class CaptureLease(
    val id: String,
    val pkg: String,
    val certSha256: String,
    val uid: Int,
    val titleOnly: Boolean = true,
    val visibleText: Boolean = false,
    val actorTurns: Boolean = false,
    val sessionLabel: String = "",
    val createdAt: Long,
    val expiresAt: Long,
    val status: CaptureStatus = CaptureStatus.RUNNING,
    val lastPollAt: Long = 0,
    val polls: Int = 0,
    val stopReason: String = "",
) {
    fun running(): Boolean = status == CaptureStatus.RUNNING
}

@Serializable
data class CaptureCursor(val offset: Int = 0, val polls: Int = 0)

@Serializable
data class CapturePage(
    val nodes: List<CaptureNode>,
    val cursor: CaptureCursor,
    /** 有漏页（快照太多 / 游标对不上）。 */
    val gap: Boolean = false,
    /** 这一页是不是已经到底。 */
    val done: Boolean = false,
    val chars: Int = 0,
    val bytes: Int = 0,
    val partial: Boolean = false,
)

/** 一次探针的结果：给调试页/设置页显示“这个应用支不支持”。 */
@Serializable
data class CaptureProbe(
    val supported: Boolean,
    val hasTitle: Boolean = false,
    val hasParagraphs: Boolean = false,
    val hasInput: Boolean = false,
    val hasCaption: Boolean = false,
    val reason: String = "",
)

/** 捕获不可用时的降级：路线和一句人话。 */
@Serializable
data class CaptureDegrade(val status: CaptureStatus, val route: String, val message: String)

/** 停止原因；null = 还在范围内。 */
data class CaptureStop(val status: CaptureStatus, val reason: String)

object Capture {
    const val MAX_NODES = 64
    const val MAX_CHARS = 8192
    const val MAX_POLL_SNAPSHOTS = 4
    const val MAX_PACKET_BYTES = 32 * 1024
    const val BOUNDS_TTL_MS = 2_000L

    /** 用户明确选了应用、总控也开了，才给租约；否则拒绝并说明原因。 */
    fun begin(policy: CapturePolicy, selection: CaptureSelection, now: Long, id: String = ""): PortResult<CaptureLease> {
        if (!policy.enabled) {
            return PortResult.Failed(PortError("CAPTURE_OFF", "限定捕获没开（默认关，要你在总控里明确选应用/会话）"))
        }
        if (selection.pkg.isBlank()) {
            return PortResult.Failed(PortError("DENIED", "没选应用：墨灵不能自己扩大捕获范围"))
        }
        val ttl = selection.ttlMs.coerceIn(60_000L, policy.maxTtlMs.coerceAtLeast(60_000L))
        val lease = CaptureLease(
            id = id.ifBlank { "cap-" + now },
            pkg = selection.pkg,
            certSha256 = selection.certSha256,
            uid = selection.uid,
            titleOnly = selection.titleOnly,
            visibleText = selection.visibleText,
            actorTurns = selection.actorTurns,
            sessionLabel = selection.sessionLabel,
            createdAt = now,
            expiresAt = now + ttl,
        )
        return PortResult.Ok(lease)
    }

    /**
     * Binder 调用方校验：调用方 UID 必须等于租约里的 UID；UID 对应的包名必须等于租约包名；
     * 证书指纹（安装时固定）必须一致。**不信 payload 自报的包名**。
     * 真实实现里 callerPkg/cert 由 PackageManager 按 UID 查，不能由对方传。
     */
    fun verifyCaller(lease: CaptureLease, callerUid: Int, callerPkg: String, certSha256: String): PortResult<Unit> = when {
        lease.uid < 0 -> PortResult.Failed(PortError("DENIED", "租约没有绑定调用方 UID"))
        callerUid != lease.uid -> PortResult.Failed(PortError("DENIED", "调用方 UID 不对"))
        !callerPkg.equals(lease.pkg, ignoreCase = true) -> PortResult.Failed(PortError("DENIED", "调用方包名和用户选的不一致"))
        lease.certSha256.isNotBlank() && !certSha256.equals(lease.certSha256, ignoreCase = true) ->
            PortResult.Failed(PortError("DENIED", "证书指纹不一致"))
        else -> PortResult.Ok(Unit)
    }

    /** 开始/运行/停止状态可见：切到范围外、停止、过期、权限撤销都立刻停。 */
    fun check(lease: CaptureLease, now: Long, foregroundPkg: String?, permission: Boolean, enabled: Boolean): CaptureStop? {
        if (lease.status != CaptureStatus.RUNNING) {
            return CaptureStop(lease.status, lease.stopReason.ifBlank { "已经不跑了" })
        }
        if (!enabled) return CaptureStop(CaptureStatus.STOPPED, "限定捕获被关了")
        if (!permission) return CaptureStop(CaptureStatus.DENIED, "无障碍 / 屏幕权限不在")
        if (now >= lease.expiresAt) return CaptureStop(CaptureStatus.EXPIRED, "租约到点")
        if (foregroundPkg == null || !foregroundPkg.equals(lease.pkg, ignoreCase = true)) {
            return CaptureStop(CaptureStatus.OUT_OF_SCOPE, "切到范围外的应用了")
        }
        return null
    }

    fun stop(lease: CaptureLease, now: Long, status: CaptureStatus, reason: String): CaptureLease =
        lease.copy(status = status, lastPollAt = now, stopReason = reason)

    /**
     * poll：最多 [MAX_POLL_SNAPSHOTS] 个快照；节点去重（同 id 后到的正文覆盖）；按标题/可见文本/回合过滤；
     * 64 节点 / 8192 字 / 32KiB 封顶，超出用 cursor 分页；[cursor] 对不上或 [lostPages] 就标 gap=true。
     * 坐标不在这里推算，原样带出并由 [boundsValid] 判断失效。
     */
    fun poll(
        lease: CaptureLease,
        snapshots: List<CaptureSnapshot>,
        cursor: CaptureCursor,
        now: Long,
        foregroundPkg: String?,
        permission: Boolean,
        enabled: Boolean,
        lostPages: Boolean = false,
    ): PortResult<CapturePage> {
        check(lease, now, foregroundPkg, permission, enabled)?.let { s ->
            return PortResult.Failed(PortError(s.status.name, s.reason))
        }
        if (snapshots.isEmpty()) return PortResult.Ok(CapturePage(emptyList(), cursor, done = true))
        if (now - snapshots.last().at > 10_000L) {
            return PortResult.Failed(PortError("STALE", "快照太旧了，不做数"))
        }
        val window = snapshots.last().window
        if (lease.pkg.isNotBlank() && !window.pkg.equals(lease.pkg, ignoreCase = true)) {
            return PortResult.Failed(PortError("OUT_OF_SCOPE", "快照不是用户选的应用"))
        }
        val capLoss = snapshots.size > MAX_POLL_SNAPSHOTS
        var gap = lostPages || capLoss
        val flat = LinkedHashMap<String, CaptureNode>()
        for (s in snapshots.take(MAX_POLL_SNAPSHOTS)) {
            s.nodes.forEach { n ->
                if (!wanted(n, lease)) return@forEach
                // 同 id 重复：保留后到的那份（更完整的正文）；parent 变了也按后到。
                flat[n.id] = n
            }
        }
        val all = flat.values.toList()
        if (cursor.offset !in 0..all.size) {
            gap = true
            return page(all, CaptureCursor(0, cursor.polls + 1), gap = true, now = now, lease = lease)
        }
        return page(all.drop(cursor.offset), CaptureCursor(cursor.offset, cursor.polls + 1), gap, now, lease, totalOffset = cursor.offset, allSize = all.size)
    }

    /** 只把实际观察到的信号带出来；没有手势数据就不推算滑动方差/手指坐标。 */
    fun signals(snapshots: List<CaptureSnapshot>): List<String> = buildList {
        if (snapshots.any { it.scrolled }) add("SCROLL_OBSERVED")
        if (snapshots.any { it.switched }) add("WINDOW_SWITCH")
    }

    /** 坐标是否还能用：2 秒内、同一窗口修订 + 尺寸/旋转、且落在窗口内。 */
    fun boundsValid(node: CaptureNode, snapshot: CaptureSnapshot, now: Long, current: CaptureWindow): Boolean {
        val b = node.bounds ?: return false
        if (now - snapshot.at > BOUNDS_TTL_MS) return false
        if (snapshot.window.pkg != current.pkg || snapshot.window.revision != current.revision) return false
        if (snapshot.window.width != current.width || snapshot.window.height != current.height) return false
        if (snapshot.window.rotation != current.rotation) return false
        return b.inside(current.width, current.height)
    }

    /** 探针：没有节点 / 游戏画面 / 视频帧看不到 → UNSUPPORTED，回退分享/手动材料。 */
    fun probe(snapshot: CaptureSnapshot?): CaptureProbe {
        if (snapshot == null || snapshot.nodes.isEmpty()) {
            return CaptureProbe(false, reason = "这个应用看不到节点（游戏 / 视频帧 / 未开无障碍）")
        }
        val texts = snapshot.nodes.filter { it.text.isNotBlank() }
        return CaptureProbe(
            supported = texts.isNotEmpty(),
            hasTitle = snapshot.nodes.any { it.role == "TITLE" && it.text.isNotBlank() },
            hasParagraphs = snapshot.nodes.any { it.role == "PARAGRAPH" && it.text.isNotBlank() },
            hasInput = snapshot.nodes.any { it.role == "INPUT" },
            hasCaption = snapshot.nodes.any { it.role == "CAPTION" && it.text.isNotBlank() },
            reason = if (texts.isEmpty()) "节点在，但没有可读文字" else "",
        )
    }

    /** 捕获不可用 → 降级路线（外脑闭环不停，只是改用分享/粘贴）。 */
    fun degrade(status: CaptureStatus, reason: String): CaptureDegrade = CaptureDegrade(
        status = status,
        route = "分享 / 粘贴",
        message = when (status) {
            CaptureStatus.UNSUPPORTED -> "这个应用看不到（$reason）：改用分享或粘贴把材料带回来"
            CaptureStatus.DENIED -> "没有权限（$reason）：不申请新权限，改用分享或粘贴"
            CaptureStatus.OUT_OF_SCOPE -> "切到范围外了（$reason）：捕获已停，外脑会话照常等结果"
            CaptureStatus.EXPIRED -> "租约到点（$reason）：要接着看就重新选一次"
            else -> "捕获没开（$reason）：走分享或粘贴"
        },
    )

    // ------------------------------------------------------------------ 内部

    private fun wanted(n: CaptureNode, lease: CaptureLease): Boolean {
        if (!lease.visibleText && !lease.actorTurns) {
            // 默认只取标题（+ 用户明确勾了可见文本才取正文）。
            return lease.titleOnly && (n.role == "TITLE" || n.role == "HEADER")
        }
        if (!lease.actorTurns) return true
        return true
    }

    private fun page(
        nodes: List<CaptureNode>,
        cursor: CaptureCursor,
        gap: Boolean,
        now: Long,
        lease: CaptureLease,
        totalOffset: Int = cursor.offset,
        allSize: Int = nodes.size,
    ): PortResult<CapturePage> {
        val out = mutableListOf<CaptureNode>()
        var chars = 0
        var bytes = 0
        var cut = false
        for (n in nodes) {
            val t = n.text
            val b = t.toByteArray().size
            if (out.size >= MAX_NODES || chars + t.length > MAX_CHARS || bytes + b > MAX_PACKET_BYTES) {
                cut = true
                break
            }
            out += n
            chars += t.length
            bytes += b
        }
        val consumed = out.size
        return PortResult.Ok(
            CapturePage(
                nodes = out,
                cursor = CaptureCursor(totalOffset + consumed, cursor.polls),
                gap = gap,
                done = !cut && totalOffset + consumed >= allSize,
                chars = chars,
                bytes = bytes,
                partial = cut,
            ),
        )
    }
}
