package io.github.gcbond.openhc.projection

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import io.github.gcbond.openhc.connection.DisplayArea
import io.github.gcbond.openhc.settings.DiagnosticLog

/** 只修改已挂载投屏页的内边距。里面的 SurfaceView 不摘除，触摸映射跟着实际画面尺寸。 */
internal class ProjectionFrame(context: Context, fullScreen: Boolean) : FrameLayout(context) {
    private val minimumTop = if (fullScreen) 0 else DisplayArea.statusBarHeight(context)
    private val visibleFrame = Rect()
    private val location = IntArray(2)
    private var active = false
    private var lastPadding: ProjectionPadding? = null

    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        // 需要重新布局时等一帧，避免先按旧位置在通知栏下绘制一帧。
        !active || !updatePadding()
    }

    init {
        setBackgroundColor(Color.BLACK)
    }

    fun setProjectionActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) updatePadding()
        else lastPadding = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(beforeDraw)
    }

    override fun onDetachedFromWindow() {
        val observer = viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(beforeDraw)
        super.onDetachedFromWindow()
    }

    private fun updatePadding(): Boolean {
        if (width <= 0 || height <= 0) return false
        getLocationOnScreen(location)
        visibleFrame.setEmpty()
        getWindowVisibleDisplayFrame(visibleFrame)
        val next = projectionPadding(
            PixelBounds(location[0], location[1], location[0] + width, location[1] + height),
            PixelBounds(visibleFrame.left, visibleFrame.top, visibleFrame.right, visibleFrame.bottom),
            minimumTop,
        )
        if (next == lastPadding) return false
        lastPadding = next
        val changed = paddingLeft != next.left || paddingTop != next.top || paddingRight != next.right || paddingBottom != next.bottom
        if (changed) setPadding(next.left, next.top, next.right, next.bottom)
        DiagnosticLog.log(
            "OpenHC-Projection", "系统栏避让：上 ${next.top}px，下 ${next.bottom}px；窗口可见区顶部 ${visibleFrame.top}px",
        )
        return changed
    }
}
