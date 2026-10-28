package cn.companion.pet.core.partner

data class OverlayBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class BubblePlacement(val x: Int, val y: Int)

object OverlayPlacement {
    fun place(safe: OverlayBounds, petX: Int, petY: Int, petSize: Int, width: Int, height: Int, rightSide: Boolean, inset: Int = 0): BubblePlacement {
        val x = if (rightSide) petX - width + inset else petX + petSize - inset
        return BubblePlacement(x.coerceIn(safe.left, (safe.right - width).coerceAtLeast(safe.left)),
            petY.coerceIn(safe.top, (safe.bottom - height).coerceAtLeast(safe.top)))
    }
}
