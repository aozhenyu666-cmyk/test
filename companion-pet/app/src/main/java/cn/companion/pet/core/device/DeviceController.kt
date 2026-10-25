package cn.companion.pet.core.device

enum class DeviceStatus { SUCCESS, UNVERIFIED, UNAVAILABLE, FAILED, CANCELLED }

data class NodeRef(
    val id: String,
    val observationId: String,
    val packageName: String,
    val text: String,
    val resourceId: String,
    val role: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class DeviceObservation(
    val id: String,
    val packageName: String,
    val capturedAt: Long,
    val expiresAt: Long,
    val nodes: List<NodeRef>,
) {
    fun valid(now: Long): Boolean = now <= expiresAt
}

data class DeviceSnapshot(
    /** 感知服务记录的前台；它会忽略桌宠自己，所以在桌宠界面里时可能还是上一个 App。 */
    val foregroundPackage: String?,
    val unlocked: Boolean,
    val accessibilityConnected: Boolean,
    val shizuku: ShizukuStatus,
    /** 此刻真正接收操作的窗口（rootInActiveWindow）的包名；动作就落在它上面。 */
    val activeWindowPackage: String? = null,
    val ownPackage: String? = null,
) {
    /** 策略判断以真实活动窗口为准；拿不到时才退回感知记录。 */
    val effectivePackage: String? get() = activeWindowPackage ?: foregroundPackage
}

data class DeviceResult(
    val status: DeviceStatus,
    val reason: String,
    val observationId: String? = null,
    val actionId: String? = null,
    val evidence: Map<String, String> = emptyMap(),
)

sealed interface DeviceCommand {
    data object GetState : DeviceCommand
    data class FindNode(val query: String, val byResourceId: Boolean = false) : DeviceCommand
    data class OpenApp(val packageName: String) : DeviceCommand
    data class TapNode(val nodeId: String) : DeviceCommand
    data class Scroll(val nodeId: String? = null, val forward: Boolean = true) : DeviceCommand
    data class InputText(val nodeId: String, val text: String) : DeviceCommand
    data object Back : DeviceCommand
    data object LastObservation : DeviceCommand
}

interface DeviceController {
    fun snapshot(now: Long = System.currentTimeMillis()): DeviceSnapshot
    fun observe(now: Long = System.currentTimeMillis()): DeviceResult
    fun execute(command: DeviceCommand, now: Long = System.currentTimeMillis()): DeviceResult
    /** 最近一次 [observe] 得到的观察（技能找元素用）；没有就 null。 */
    fun lastObservation(): DeviceObservation? = null
}

data class DevicePolicyDecision(val allowed: Boolean, val reason: String)

object DevicePolicy {
    private val sensitivePackages = setOf(
        "com.android.settings", "com.android.vending", "com.google.android.gms",
    )

    fun check(
        command: DeviceCommand,
        snapshot: DeviceSnapshot,
        observation: DeviceObservation?,
        allowedPackages: Set<String>,
        now: Long,
    ): DevicePolicyDecision {
        if (!snapshot.unlocked) return DevicePolicyDecision(false, "设备锁屏")
        if (!snapshot.accessibilityConnected) return DevicePolicyDecision(false, "无障碍服务未连接")
        val pkg = when (command) {
            is DeviceCommand.OpenApp -> command.packageName
            is DeviceCommand.GetState, is DeviceCommand.LastObservation -> snapshot.foregroundPackage
            else -> observation?.packageName
        }
        if (pkg.isNullOrBlank()) return DevicePolicyDecision(false, "没有当前应用")
        if (pkg !in allowedPackages) return DevicePolicyDecision(false, "应用不在授权名单")
        if (pkg in sensitivePackages) return DevicePolicyDecision(false, "敏感系统/商店应用")
        if (command !is DeviceCommand.GetState && command !is DeviceCommand.OpenApp) {
            if (observation == null || !observation.valid(now)) return DevicePolicyDecision(false, "观察已过期")
            // 动作落在真实活动窗口上：它若是桌宠自己的界面（设置页/能力面板），就不能拿别的 App 的观察去操作（审计 P1-4）。
            if (snapshot.ownPackage != null && snapshot.activeWindowPackage == snapshot.ownPackage) {
                return DevicePolicyDecision(false, "当前是桌宠自己的界面，不能操作别的 App 的旧观察")
            }
            if (snapshot.effectivePackage != observation.packageName) return DevicePolicyDecision(false, "前台已切换，拒绝旧观察")
            val target = when (command) {
                is DeviceCommand.TapNode -> observation.nodes.firstOrNull { it.id == command.nodeId }
                is DeviceCommand.InputText -> observation.nodes.firstOrNull { it.id == command.nodeId }
                else -> null
            }
            if ((command is DeviceCommand.TapNode || command is DeviceCommand.InputText) && target == null) {
                return DevicePolicyDecision(false, "节点不存在")
            }
            if (command is DeviceCommand.InputText && target?.editable != true) {
                return DevicePolicyDecision(false, "目标不是可编辑输入框")
            }
            if (target != null && (target.right <= target.left || target.bottom <= target.top)) {
                return DevicePolicyDecision(false, "节点边界无效")
            }
        }
        if (command is DeviceCommand.InputText && command.text.length > 300) {
            return DevicePolicyDecision(false, "输入文本过长")
        }
        if (command is DeviceCommand.TapNode && observation?.nodes?.none { it.id == command.nodeId } == true) {
            return DevicePolicyDecision(false, "节点不存在或已变化")
        }
        return DevicePolicyDecision(true, "允许执行")
    }
}

/**
 * 动作之后重新观察的核对规则。只在有明确判据时给 SUCCESS/FAILED：
 * 打开应用看活动窗口包名；输入文字看同一个输入框里的文字。点击/滚动/返回无法从界面断定语义，
 * 只附上“页面是否变化”的证据，仍是 UNVERIFIED。
 */
object DeviceVerifier {
    data class Verdict(val status: DeviceStatus, val reason: String, val evidence: Map<String, String> = emptyMap())

    fun verify(command: DeviceCommand, before: DeviceObservation?, after: DeviceObservation?, afterActivePackage: String?): Verdict =
        when (command) {
            is DeviceCommand.OpenApp ->
                if (afterActivePackage == command.packageName) Verdict(DeviceStatus.SUCCESS, "已打开，重新观察确认前台是它")
                else Verdict(DeviceStatus.UNVERIFIED, "系统接受了启动请求，但前台还不是它")
            is DeviceCommand.InputText -> {
                val target = before?.nodes?.firstOrNull { it.id == command.nodeId }
                val now = target?.let { t ->
                    after?.nodes?.firstOrNull {
                        it.editable && it.resourceId == t.resourceId && it.left == t.left && it.top == t.top &&
                            it.right == t.right && it.bottom == t.bottom
                    }
                }
                when {
                    now == null -> Verdict(DeviceStatus.UNVERIFIED, "已写入，但重新观察没找到这个输入框")
                    now.text == command.text.take(120) -> Verdict(DeviceStatus.SUCCESS, "已写入，重新观察确认输入框里是这段文字")
                    else -> Verdict(DeviceStatus.FAILED, "重新观察发现输入框里不是这段文字")
                }
            }
            is DeviceCommand.TapNode, is DeviceCommand.Scroll, DeviceCommand.Back -> {
                if (after == null) Verdict(DeviceStatus.UNVERIFIED, "已执行；没能重新观察")
                else {
                    val changed = before == null || after.packageName != before.packageName || signature(after) != signature(before)
                    Verdict(DeviceStatus.UNVERIFIED, if (changed) "已执行，页面有变化；请看一眼是否符合预期" else "已执行，但页面看起来没有变化",
                        mapOf("pageChanged" to changed.toString()))
                }
            }
            else -> Verdict(DeviceStatus.UNVERIFIED, "无需核对")
        }

    private fun signature(o: DeviceObservation): Int = o.nodes.map { it.id }.sorted().hashCode()
}
