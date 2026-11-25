package io.github.gcbond.openhc.projection

internal data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val hasArea: Boolean get() = right > left && bottom > top
}

internal data class ProjectionPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 用屏幕坐标求交集，只补系统还没让出的部分。窗口可见边界或布局已经给出顶部位置时，
 * 不把资源高度当成最低限度。只有两者都没提供顶部偏移时，保留系统栏的模式才用估算值。
 */
internal fun projectionPadding(
    view: PixelBounds,
    reportedVisible: PixelBounds,
    fallbackStatusBarHeight: Int,
): ProjectionPadding {
    if (!view.hasArea) return ProjectionPadding(0, 0, 0, 0)
    val visible = PixelBounds(
        maxOf(view.left, reportedVisible.left), maxOf(view.top, reportedVisible.top),
        minOf(view.right, reportedVisible.right), minOf(view.bottom, reportedVisible.bottom),
    ).takeIf { it.hasArea } ?: view
    val top = if (visible.top <= 0 && fallbackStatusBarHeight in 1 until visible.bottom) {
        fallbackStatusBarHeight
    } else visible.top
    return ProjectionPadding(visible.left - view.left, top - view.top, view.right - visible.right, view.bottom - visible.bottom)
}
