package com.reelix.app.ui.component

/**
 * 屏幕坐标下的控件矩形，用于跨行焦点判定。
 */
data class FocusBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
}

/**
 * 纯几何的上下跨行判定：只回答"该落到哪个候选控件"，不产生副作用。
 *
 * 规则：
 * 1. 只在方向正确的候选里找垂直间距最小的那一"行"（允许行内轻微高度差）；
 * 2. 同一行里取水平距离最近的控件，重叠视为 0；
 * 3. 找不到就返回 null，由调用方决定是否交还给系统。
 *
 * 之所以放在纯函数里，是因为"跳行"问题只能靠可断言的规则锁住，
 * 焦点框实际走位仍然要上电视看。
 */
object FocusRowGeometry {

    fun pickVertical(
        current: FocusBox,
        candidates: List<FocusBox>,
        up: Boolean,
        bandTolerance: Int = 24,
        edgeTolerance: Int = 2,
    ): Int? {
        if (candidates.isEmpty()) return null
        val gaps = LinkedHashMap<Int, Int>()
        candidates.forEachIndexed { index, box ->
            val gap = if (up) current.top - box.bottom else box.top - current.bottom
            if (gap >= -edgeTolerance) gaps[index] = gap.coerceAtLeast(0)
        }
        if (gaps.isEmpty()) return null
        val minGap = gaps.values.min()
        val band = gaps.filterValues { it <= minGap + bandTolerance }.keys
        return band.minWithOrNull(
            compareBy(
                { horizontalGap(current, candidates[it]) },
                { kotlin.math.abs(current.centerX - candidates[it].centerX) },
            ),
        )
    }

    private fun horizontalGap(current: FocusBox, box: FocusBox): Int =
        maxOf(0, maxOf(box.left - current.right, current.left - box.right))
}
