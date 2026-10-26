package cn.companion.pet.a11y

import cn.companion.pet.core.scene.ActivityTrail
import cn.companion.pet.core.scene.StuckDetector
import cn.companion.pet.core.text.TextCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 无障碍服务和桌宠服务之间的交接点。全部在主线程上访问。
 *
 * 无障碍服务只往这里写“前台是谁、什么时候滚动了”，屏幕文字不存在这里，
 * 只有桌宠服务在需要时通过 [readScreen] 现读一次。
 */
object PerceptionHub {
    val trail = ActivityTrail()
    val stuck = StuckDetector()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** 无障碍服务连接后设置；断开时清空。 */
    internal var reader: (() -> List<TextCandidate>)? = null
    internal var service: PerceptionService? = null

    /** 有滚动时通知桌宠服务（划过留痕用）。只传包名，不传内容。 */
    var onScroll: ((String) -> Unit)? = null

    /** 防抖后用真实活动窗口确认过的前台切换（专注守护用）；只传包名。 */
    var onForegroundConfirmed: ((String) -> Unit)? = null

    /**
     * 健康挑战通道：由执行路径自己（主线程 Handler）应答。
     *
     * 为什么必须走这里：入口直接回显 nonce 只能证明“Binder/Provider 还活着”，
     * 不能证明真正干活的那条队列没卡住。所以挑战必须投递到与前台事件同一队列，
     * 处理完再回执；旁路线程代答会被 [cn.companion.pet.core.training.HealthChallengeJudge] 拒绝。
     */
    internal var challengeResponder: ((String, String) -> Unit)? = null

    /** 健康回执的接收方（由 PetBrain 注册）。 */
    var onHealthReceipt: ((cn.companion.pet.core.training.HealthReceipt) -> Unit)? = null

    /** 投递一次挑战；不可用时返回 false，调用方应判为 UNKNOWN 或降级。 */
    fun postChallenge(challengeId: String, nonce: String): Boolean {
        val responder = challengeResponder ?: return false
        val svc = service ?: return false
        return runCatching {
            // 投递到无障碍服务自己的主线程队列，与前台事件同一条路径。
            svc.mainHandler().post { responder(challengeId, nonce) }
            true
        }.getOrDefault(false)
    }

    fun setConnected(value: Boolean) { _connected.value = value }

    /** 现读一次当前窗口的文字。服务没连上或读失败返回空列表。 */
    fun readScreen(): List<TextCandidate> = runCatching { reader?.invoke() }.getOrNull().orEmpty()

    /** 当前真正活动窗口的包名；服务没连上或读失败返回 null。 */
    fun activePackage(): String? = runCatching { service?.rootInActiveWindow?.packageName?.toString() }.getOrNull()
}
