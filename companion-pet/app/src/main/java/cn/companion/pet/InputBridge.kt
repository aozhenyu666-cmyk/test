package cn.companion.pet

/**
 * 文字输入的桥：悬浮窗里的输入框没有 Activity 窗口令牌，
 * 会被系统当成“无宿主输入”，挂一个隔离的输入法实例（不共享剪贴板/词库）。
 * 所以把输入搬进真正的 Activity，结果通过这里回传。
 */
object InputBridge {
    /** 输入完成回调；由 PetBrain 在打开输入前设置。 */
    @Volatile var onSubmit: ((String) -> Unit)? = null

    /** 用户取消或不输入直接返回。 */
    @Volatile var onCancel: (() -> Unit)? = null

    /** 当前提示语，例如“点输入法上的话筒说话”。 */
    @Volatile var hint: String = ""

    fun submit(text: String) {
        val cb = onSubmit
        clear()
        cb?.invoke(text)
    }

    fun cancel() {
        val cb = onCancel
        clear()
        cb?.invoke()
    }

    fun clear() {
        onSubmit = null
        onCancel = null
        hint = ""
    }
}
