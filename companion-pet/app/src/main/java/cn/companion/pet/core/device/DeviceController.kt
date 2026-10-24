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
    val foregroundPackage: String?,
    val unlocked: Boolean,
    val accessibilityConnected: Boolean,
    val shizuku: ShizukuStatus,
)

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
            if (snapshot.foregroundPackage != observation.packageName) return DevicePolicyDecision(false, "前台已切换，拒绝旧观察")
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
