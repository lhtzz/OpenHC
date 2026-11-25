package io.github.gcbond.openhc.projection

internal data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val hasArea: Boolean get() = right > left && bottom > top
}

internal data class ProjectionPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 窗口可能已由系统让出一部分栏。用屏幕坐标求交集，只补仍与栏重叠的部分，
 * 避免系统已经把画布往下移了却再减一次状态栏高度。
 */
internal fun projectionPadding(view: PixelBounds, reportedVisible: PixelBounds, minimumScreenTop: Int): ProjectionPadding {
    if (!view.hasArea) return ProjectionPadding(0, 0, 0, 0)
    val visible = PixelBounds(
        maxOf(view.left, reportedVisible.left), maxOf(view.top, reportedVisible.top),
        minOf(view.right, reportedVisible.right), minOf(view.bottom, reportedVisible.bottom),
    ).takeIf { it.hasArea } ?: view
    val top = maxOf(visible.top, minimumScreenTop).coerceAtMost(visible.bottom - 1)
    return ProjectionPadding(visible.left - view.left, top - view.top, view.right - visible.right, view.bottom - visible.bottom)
}
