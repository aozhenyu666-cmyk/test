package cn.companion.pet.a11y

import cn.companion.pet.core.scene.ActivityTrail
import cn.companion.pet.core.scene.StuckDetector
import cn.companion.pet.core.text.TextCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 无障碍服务和桌宠服务之间的交接点。全部在主线程上访问。
 *
 * 无障碍服务只往这里写“前台是谁、什么时候滚动了”，屏幕文字不存在这里，
 * 只有桌宠服务在需要时通过 [readScreen] 现读一次。
 *
 * 健康协议 v2 也挂在这里（契约 `_shared/WIRE-SPEC-v2.md` §3.2）：
 * [instanceId] 每次无障碍连接重建都会换新；[eventSeq]/[eventAtMono] 是可对照的事件进度，
 * 只记序号和时刻，不记任何屏幕内容。
 */
object PerceptionHub {
    val trail = ActivityTrail()
    val stuck = StuckDetector()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** 无障碍服务连接后设置；断开时清空。 */
    internal var reader: (() -> List<TextCandidate>)? = null
    internal var service: PerceptionService? = null

    /** 本次无障碍连接实例；重建必须换新（规范 §3.2）。没连上为 null。 */
    @Volatile
    var instanceId: String? = null
        private set

    private val eventSeq = AtomicLong(0)

    @Volatile
    private var eventAtMono = 0L

    /** 有滚动时通知桌宠服务（划过留痕用）。只传包名，不传内容。 */
    var onScroll: ((String) -> Unit)? = null

    /** 防抖后用真实活动窗口确认过的前台切换（专注守护用）；只传包名。 */
    var onForegroundConfirmed: ((String) -> Unit)? = null

    /**
     * 感知服务的真实执行线程（AccessibilityService 主线程 Looper 上的 Handler）。
     * 健康挑战必须投到这里处理；没连上时为 null，Provider 直接回 not connected。
     */
    @Volatile
    var healthExecutorHandler: android.os.Handler? = null
        private set

    fun bindExecutor(handler: android.os.Handler) {
        healthExecutorHandler = handler
    }

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    /** 无障碍服务连接：换一个全新的实例号。 */
    fun markConnected() {
        instanceId = UUID.randomUUID().toString()
        eventSeq.set(0)
        eventAtMono = 0
        setConnected(true)
    }

    /** 连接断开/服务销毁：实例号必须失效，健康协议据此报 not connected。 */
    fun markDisconnected() {
        instanceId = null
        healthExecutorHandler = null
        setConnected(false)
    }

    /** 无障碍事件处理完一次就 +1（只在比对类事件上调用）。 */
    fun noteEvent(nowMono: Long) {
        eventSeq.incrementAndGet()
        eventAtMono = nowMono
    }

    fun eventProgress(): Pair<Long, Long> = eventSeq.get() to eventAtMono

    fun isConnected(): Boolean = instanceId != null && _connected.value

    /** 现读一次当前窗口的文字。服务没连上或读失败返回空列表。 */
    fun readScreen(): List<TextCandidate> = runCatching { reader?.invoke() }.getOrNull().orEmpty()

    /** 当前真正活动窗口的包名；服务没连上或读失败返回 null。 */
    fun activePackage(): String? = runCatching { service?.rootInActiveWindow?.packageName?.toString() }.getOrNull()
}
