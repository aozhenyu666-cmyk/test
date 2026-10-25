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

    fun setConnected(value: Boolean) { _connected.value = value }

    /** 现读一次当前窗口的文字。服务没连上或读失败返回空列表。 */
    fun readScreen(): List<TextCandidate> = runCatching { reader?.invoke() }.getOrNull().orEmpty()

    /** 当前真正活动窗口的包名；服务没连上或读失败返回 null。 */
    fun activePackage(): String? = runCatching { service?.rootInActiveWindow?.packageName?.toString() }.getOrNull()
}
