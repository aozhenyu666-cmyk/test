package com.yishou.app.look

/**
 * 两帧画面变没变：只比固定网格上的采样点，不做整图 diff，也不落盘。
 *
 * 高频陪练每拍都会拿到一帧，先用它在本机比一次：没变就既不识图、也不打扰用户。
 * 纯逻辑（只吃像素数组），可单测。
 */
object FrameDelta {
    /** 采样网格：16×16 = 256 个点，够分辨「换了一屏」和「同一屏微动」。 */
    const val GRID = 16

    /** 单个采样点 RGB 三通道差值之和超过它，才算这个点变了。 */
    const val PIXEL_TOLERANCE = 24

    /** 变化点占比达到它，才算整屏变了。 */
    const val CHANGE_RATIO = 0.02

    /** 在 w×h 的图上取网格采样点的坐标（同一张图反复用，调用方可缓存）。 */
    fun grid(w: Int, h: Int, grid: Int = GRID): IntArray {
        val n = grid.coerceAtLeast(2)
        val out = IntArray(n * n * 2)
        var i = 0
        for (gy in 0 until n) {
            val y = ((h - 1).toLong() * gy / (n - 1)).toInt()
            for (gx in 0 until n) {
                val x = ((w - 1).toLong() * gx / (n - 1)).toInt()
                out[i++] = x
                out[i++] = y
            }
        }
        return out
    }

    /** 从 ARGB 像素里抽一张「签名」：网格点上的颜色。 */
    fun signature(pixels: IntArray, w: Int, h: Int, grid: Int = GRID): IntArray {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return IntArray(0)
        val pts = grid(w, h, grid)
        val out = IntArray(pts.size / 2)
        var i = 0
        var p = 0
        while (i < out.size) {
            val x = pts[p]
            val y = pts[p + 1]
            out[i] = pixels[y * w + x]
            i++
            p += 2
        }
        return out
    }

    /** 两点颜色差（RGB 三通道绝对值之和）。 */
    fun distance(a: Int, b: Int): Int {
        val dr = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db
    }

    /** 变化点占比 0..1；签名尺寸不一致视为全变。 */
    fun ratio(a: IntArray?, b: IntArray?): Double {
        if (a == null || b == null || a.isEmpty() || a.size != b.size) return 1.0
        var moved = 0
        for (i in a.indices) if (distance(a[i], b[i]) > PIXEL_TOLERANCE) moved++
        return moved.toDouble() / a.size
    }

    /** 画面变了吗。没有上一帧（第一次看）当作变了，先看一次。 */
    fun changed(a: IntArray?, b: IntArray?, threshold: Double = CHANGE_RATIO): Boolean =
        a == null || b == null || b.isEmpty() || ratio(a, b) >= threshold
}
