package cn.companion.pet.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import cn.companion.pet.core.device.DeviceCommand
import cn.companion.pet.core.device.DeviceController
import cn.companion.pet.core.device.DeviceObservation
import cn.companion.pet.core.device.DevicePolicy
import cn.companion.pet.core.device.DeviceResult
import cn.companion.pet.core.device.DeviceSnapshot
import cn.companion.pet.core.device.DeviceStatus
import cn.companion.pet.core.device.NodeRef
import cn.companion.pet.core.device.ShizukuProbe
import cn.companion.pet.data.Settings
import java.util.UUID

/**
 * 薄 DeviceController：只走原生 Intent / AccessibilityNodeInfo / dispatchGesture。
 * Shizuku 仅提供状态，不执行 shell；不绕过 FLAG_SECURE 或锁屏。
 */
class AndroidDeviceController(
    private val context: Context,
    private val settings: Settings,
    private val shizuku: ShizukuProbe,
) : DeviceController {
    @Volatile private var latest: DeviceObservation? = null

    override fun snapshot(now: Long): DeviceSnapshot = DeviceSnapshot(
        foregroundPackage = PerceptionHub.trail.foreground,
        unlocked = runCatching {
            context.getSystemService(PowerManager::class.java).isInteractive &&
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        }.getOrDefault(false),
        accessibilityConnected = PerceptionHub.service != null,
        shizuku = shizuku.diagnostic().status,
        activeWindowPackage = activePackage(),
        ownPackage = context.packageName,
    )

    /**
     * 探测某个包名**当前前台实例**的 Android userId。
     *
     * 为什么需要：之前只比包名，"主实例"与"应用分身"在代码里是同一个东西。
     * 实测 com.tencent.mm 同时有 u0_a410（机主）与 u999_a410（vivo 应用分身）两个进程。
     *
     * 做法：从运行中的进程里找出该包名、且处于前台重要级的进程，读它的 uid，
     * 再换算成 userId（uid / 100000）。返回 null 表示**探测不到**——不确定就如实说不确定。
     */
    fun probeUserId(pkg: String): Int? = runCatching {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
        // runningAppProcesses 只返回"本用户可见"的进程；分身进程通常不在里面，
        // 所以拿不到就直接返回 null，绝不用 0 冒充"就是机主"。
        val infos = am.runningAppProcesses.orEmpty()
        val hit = infos.filter { it.processName == pkg || it.processName.startsWith(pkg + ":") }
            .maxByOrNull { it.importance }
        hit?.uid?.let { uid -> if (uid <= 0) null else uid / 100000 }
    }.getOrNull()

    /** 真正接收操作的窗口。感知记录会忽略桌宠自己，这里不会。 */
    private fun activePackage(): String? =
        runCatching { PerceptionHub.service?.rootInActiveWindow?.packageName?.toString() }.getOrNull()

    override fun observe(now: Long): DeviceResult {
        val service = PerceptionHub.service ?: return DeviceResult(DeviceStatus.UNAVAILABLE, "无障碍服务未连接")
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
            ?: return DeviceResult(DeviceStatus.UNAVAILABLE, "没有活动窗口")
        if (root.packageName?.toString() == context.packageName) return DeviceResult(DeviceStatus.UNAVAILABLE, "不能操作桌宠自己的窗口")
        val nodes = ArrayList<NodeRef>()
        collect(root, nodes, UUID.randomUUID().toString(), now, 0)
        val observation = DeviceObservation(
            id = UUID.randomUUID().toString(), packageName = root.packageName?.toString() ?: "unknown",
            capturedAt = now, expiresAt = now + 45_000, nodes = nodes,
        )
        latest = observation
        return DeviceResult(DeviceStatus.SUCCESS, "已观察 " + nodes.size + " 个节点",
            observationId = observation.id, evidence = mapOf("foreground" to observation.packageName))
    }

    override fun lastObservation(): DeviceObservation? = latest

    override fun execute(command: DeviceCommand, now: Long): DeviceResult {
        val snap = snapshot(now)
        val obs = latest
        val policy = DevicePolicy.check(command, snap, obs, settings.deviceAllowedApps, now)
        if (!policy.allowed) return DeviceResult(DeviceStatus.UNAVAILABLE, policy.reason, obs?.id)
        val raw = perform(command, snap, obs, now)
        return if (raw.status == DeviceStatus.UNVERIFIED) verifyAfter(command, obs, raw) else raw
    }

    /**
     * 动作后等界面稳定，再观察一次核对（审计 P2-5）。打开应用/输入文字能给出 SUCCESS 或 FAILED；
     * 点击/滚动/返回只附“页面是否变化”，仍是 UNVERIFIED。只在工作线程调用。
     */
    private fun verifyAfter(command: DeviceCommand, before: DeviceObservation?, raw: DeviceResult): DeviceResult {
        runCatching { Thread.sleep(VERIFY_DELAY_MILLIS) }
        val again = observe(System.currentTimeMillis())
        val after = if (again.status == DeviceStatus.SUCCESS) latest else null
        val verdict = cn.companion.pet.core.device.DeviceVerifier.verify(command, before, after, activePackage())
        return DeviceResult(verdict.status, verdict.reason, after?.id ?: raw.observationId, raw.actionId, raw.evidence + verdict.evidence)
    }

    private fun perform(command: DeviceCommand, snap: DeviceSnapshot, obs: DeviceObservation?, now: Long): DeviceResult {
        return when (command) {
            DeviceCommand.GetState -> DeviceResult(DeviceStatus.SUCCESS, "状态读取成功",
                evidence = mapOf("foreground" to (snap.foregroundPackage ?: "?"), "unlocked" to snap.unlocked.toString(),
                    "accessibility" to snap.accessibilityConnected.toString(), "shizuku" to snap.shizuku.name))
            DeviceCommand.LastObservation -> {
                val current = obs?.takeIf { it.valid(now) }
                    ?: return DeviceResult(DeviceStatus.UNAVAILABLE, "没有有效观察")
                DeviceResult(DeviceStatus.SUCCESS, "最近观察 " + current.id, observationId = current.id)
            }
            is DeviceCommand.FindNode -> {
                val found = obs?.nodes?.filter {
                    if (command.byResourceId) it.resourceId.contains(command.query, true)
                    else it.text.contains(command.query, true)
                }.orEmpty()
                if (found.isEmpty()) DeviceResult(DeviceStatus.FAILED, "没有找到节点", obs?.id)
                else DeviceResult(DeviceStatus.SUCCESS, "找到 " + found.size + " 个节点", obs?.id,
                    evidence = found.take(3).associate { it.id to (it.text.take(80) + "|" + it.resourceId) })
            }
            is DeviceCommand.OpenApp -> open(command.packageName, obs?.id)
            is DeviceCommand.TapNode -> tap(command.nodeId, obs)
            is DeviceCommand.Scroll -> scroll(command, obs)
            is DeviceCommand.InputText -> input(command, obs)
            DeviceCommand.Back -> {
                val ok = PerceptionHub.service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
                DeviceResult(if (ok) DeviceStatus.UNVERIFIED else DeviceStatus.FAILED,
                    if (ok) "已请求返回；结果需重新观察" else "返回失败", obs?.id)
            }
        }
    }

    private fun open(pkg: String, observationId: String?): DeviceResult {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return DeviceResult(DeviceStatus.UNAVAILABLE, "未安装或不支持启动：" + pkg, observationId)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            DeviceResult(DeviceStatus.UNVERIFIED, "系统接受启动请求；需重新观察确认前台", observationId)
        }.getOrElse { DeviceResult(DeviceStatus.FAILED, "系统拒绝启动", observationId) }
    }

    private fun tap(nodeId: String, obs: DeviceObservation?): DeviceResult {
        val node = findById(nodeId) ?: return DeviceResult(DeviceStatus.FAILED, "节点已变化，拒绝旧坐标", obs?.id)
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) return DeviceResult(DeviceStatus.UNVERIFIED, "已点击；结果需重新观察", obs?.id)
        val ref = obs?.nodes?.firstOrNull { it.id == nodeId } ?: return DeviceResult(DeviceStatus.FAILED, "节点缺少坐标", obs?.id)
        val service = PerceptionHub.service ?: return DeviceResult(DeviceStatus.UNAVAILABLE, "无障碍断开", obs?.id)
        val path = Path().apply { moveTo((ref.left + ref.right) / 2f, (ref.top + ref.bottom) / 2f) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val dispatched = service.dispatchGesture(gesture, null, null)
        return DeviceResult(if (dispatched) DeviceStatus.UNVERIFIED else DeviceStatus.FAILED,
            if (dispatched) "已发送点击手势；结果需重新观察" else "点击手势失败", obs?.id)
    }

    private fun scroll(command: DeviceCommand.Scroll, obs: DeviceObservation?): DeviceResult {
        val node = command.nodeId?.let { findById(it) } ?: PerceptionHub.service?.rootInActiveWindow
            ?: return DeviceResult(DeviceStatus.FAILED, "没有可滚动节点", obs?.id)
        val ok = node.performAction(if (command.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        return DeviceResult(if (ok) DeviceStatus.UNVERIFIED else DeviceStatus.FAILED,
            if (ok) "已请求滚动；结果需重新观察" else "节点不可滚动", obs?.id)
    }

    private fun input(command: DeviceCommand.InputText, obs: DeviceObservation?): DeviceResult {
        val node = findById(command.nodeId) ?: return DeviceResult(DeviceStatus.FAILED, "输入框已变化", obs?.id)
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.text) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return DeviceResult(if (ok) DeviceStatus.UNVERIFIED else DeviceStatus.FAILED,
            if (ok) "已写入文字；结果需重新观察" else "输入框拒绝写入", obs?.id)
    }

    private fun findById(id: String): AccessibilityNodeInfo? {
        val root = PerceptionHub.service?.rootInActiveWindow ?: return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 800) {
            val node = stack.removeLast(); visited++
            val ref = toRef(node, "", 0)
            if (ref.id == id) {
                val expected = latest?.nodes?.firstOrNull { it.id == id } ?: return null
                val same = ref.packageName == expected.packageName && ref.clickable == expected.clickable &&
                    ref.editable == expected.editable && ref.left == expected.left && ref.top == expected.top &&
                    ref.right == expected.right && ref.bottom == expected.bottom
                return if (same) node else null
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<NodeRef>, observationId: String, now: Long, depth: Int) {
        if (depth > 40 || out.size >= 300 || node.isPassword) return
        if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() || node.isClickable || node.isEditable) {
            out += toRef(node, observationId, now)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, observationId, now, depth + 1) }
    }

    private fun toRef(node: AccessibilityNodeInfo, observationId: String, now: Long): NodeRef {
        val text = (node.text ?: node.contentDescription)?.toString().orEmpty().take(120)
        val rid = node.viewIdResourceName.orEmpty()
        val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
        val raw = (node.packageName?.toString() ?: "") + "|" + rid + "|" + text + "|" + rect.toShortString()
        val id = Integer.toHexString(raw.hashCode()) + "-" + raw.length
        return NodeRef(id, observationId, node.packageName?.toString() ?: "", text, rid,
            node.className?.toString() ?: "", node.isClickable, node.isEditable, node.isScrollable,
            rect.left, rect.top, rect.right, rect.bottom)
    }

    private companion object {
        const val VERIFY_DELAY_MILLIS = 700L
    }
}
