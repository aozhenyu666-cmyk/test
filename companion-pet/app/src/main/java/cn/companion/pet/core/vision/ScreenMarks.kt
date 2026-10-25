package cn.companion.pet.core.vision

import cn.companion.pet.core.device.NodeRef

/** 截图上的一个编号框（屏幕坐标）。 */
data class ScreenMark(
    val number: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val label: String,
    val kind: String,
)

/**
 * 给截图上的可点/可写元素编号（参考 mobilerun / OpenDroid 的 set-of-marks）：
 * 图上画框和数字，同时把同一张编号表发给视觉模型，模型说“点 3 号”就能对回同一个无障碍节点。
 * 只用节点上本来就显示在屏幕上的文字；密码框不进观察。
 */
object ScreenMarks {
    const val MAX_MARKS = 20
    private const val MIN_SIDE = 24

    fun plan(nodes: List<NodeRef>, max: Int = MAX_MARKS): List<ScreenMark> =
        nodes.asSequence()
            .filter { it.clickable || it.editable }
            .filter { it.right - it.left >= MIN_SIDE && it.bottom - it.top >= MIN_SIDE }
            .filter { it.text.isNotBlank() || it.resourceId.isNotBlank() || it.editable }
            .distinctBy { it.left.toString() + "," + it.top + "," + it.right + "," + it.bottom }
            .sortedWith(compareBy({ it.top / 40 }, { it.left }))
            .take(max)
            .mapIndexed { i, n ->
                ScreenMark(i + 1, n.left, n.top, n.right, n.bottom,
                    n.text.ifBlank { n.resourceId.substringAfterLast('/') }.replace('\n', ' ').take(20),
                    if (n.editable) "输入框" else "按钮")
            }
            .toList()

    /** 发给视觉模型的编号表；没有编号时返回空串。 */
    fun promptTable(marks: List<ScreenMark>): String {
        if (marks.isEmpty()) return ""
        return "截图上用红框和数字标出了可点或可写的元素（编号来自系统无障碍信息）：\n" +
            marks.joinToString("\n") { "[" + it.number + "] " + it.kind + (if (it.label.isBlank()) "" else "“" + it.label + "”") } +
            "\n提到这些元素时请带上编号，例如“[3] 发送按钮”。编号表只是参考，画面上看到的为准。"
    }

    /**
     * 屏幕坐标 → 截图像素。[originX]/[originY] 是截图左上角在屏幕上的位置（整屏截图是 0,0；窗口截图是窗口左上角），
     * [scale] 是截图相对原始尺寸的缩放。框完全落在图外时返回 null。
     */
    fun toImage(mark: ScreenMark, originX: Int, originY: Int, scale: Float, imageW: Int, imageH: Int): IntArray? {
        val l = ((mark.left - originX) * scale).toInt().coerceIn(0, imageW)
        val t = ((mark.top - originY) * scale).toInt().coerceIn(0, imageH)
        val r = ((mark.right - originX) * scale).toInt().coerceIn(0, imageW)
        val b = ((mark.bottom - originY) * scale).toInt().coerceIn(0, imageH)
        return if (r - l < 2 || b - t < 2) null else intArrayOf(l, t, r, b)
    }
}
