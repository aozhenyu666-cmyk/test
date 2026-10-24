package cn.companion.pet.core.device

import cn.companion.pet.core.action.ActionAdapter
import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionRequest
import cn.companion.pet.core.action.ActionResult
import cn.companion.pet.core.action.ActionStatus

/** 把 ActionRunner 的窄动作映射到 DeviceController；不提供任意 shell。 */
class DeviceActionAdapter(private val controller: DeviceController) : ActionAdapter {
    override fun execute(request: ActionRequest): ActionResult {
        val command = when (request.kind) {
            ActionKind.GET_DEVICE_STATE -> DeviceCommand.GetState
            ActionKind.FIND_NODE -> DeviceCommand.FindNode(request.args["query"].orEmpty(), request.args["by_resource_id"] == "true")
            ActionKind.OPEN_APP -> DeviceCommand.OpenApp(request.args["package"].orEmpty())
            ActionKind.TAP_NODE -> DeviceCommand.TapNode(request.args["node_id"].orEmpty())
            ActionKind.SCROLL -> DeviceCommand.Scroll(request.args["node_id"], request.args["forward"] != "false")
            ActionKind.INPUT_TEXT -> DeviceCommand.InputText(request.args["node_id"].orEmpty(), request.args["text"].orEmpty())
            ActionKind.BACK -> DeviceCommand.Back
            ActionKind.GET_LAST_OBSERVATION -> DeviceCommand.LastObservation
            else -> return ActionResult(request.id, request.kind, ActionStatus.UNAVAILABLE, "不是设备动作")
        }
        // 节点定位/最近观察必须先有当前观察；不把“没有观察”伪装成找到了。
        if (command is DeviceCommand.FindNode || command is DeviceCommand.LastObservation) {
            val observed = runCatching { controller.observe() }.getOrNull()
            if (observed == null || observed.status != DeviceStatus.SUCCESS) {
                return ActionResult(request.id, request.kind, ActionStatus.UNAVAILABLE,
                    observed?.reason ?: "无法观察当前页面")
            }
        }
        val result = runCatching { controller.execute(command) }.getOrElse {
            return ActionResult(request.id, request.kind, ActionStatus.FAILED, "设备动作异常")
        }
        val status = when (result.status) {
            DeviceStatus.SUCCESS -> ActionStatus.SUCCESS
            DeviceStatus.UNAVAILABLE -> ActionStatus.UNAVAILABLE
            DeviceStatus.CANCELLED -> ActionStatus.CANCELLED
            DeviceStatus.UNVERIFIED, DeviceStatus.FAILED -> ActionStatus.UNAVAILABLE
        }
        val data = result.evidence.toMutableMap()
        result.observationId?.let { data["observationId"] = it }
        if (result.status == DeviceStatus.UNVERIFIED) data["unverified"] = "true"
        return ActionResult(request.id, request.kind, status, result.reason, data)
    }
}
