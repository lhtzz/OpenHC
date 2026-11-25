package io.github.gcbond.openhc.projection

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import io.github.gcbond.openhc.connection.DisplayArea
import io.github.gcbond.openhc.platform.media.VideoViewport
import io.github.gcbond.openhc.settings.DiagnosticLog

/** 只修改已挂载投屏页的内边距。里面的 SurfaceView 不摘除，触摸映射跟着实际画面尺寸。 */
internal class ProjectionFrame(
    context: Context,
    private val fullScreen: Boolean,
    private val projection: ProjectionView,
) : FrameLayout(context) {
    private val fallbackStatusBarHeight = if (fullScreen) 0 else DisplayArea.statusBarHeight(context)
    private val visibleFrame = Rect()
    private val location = IntArray(2)
    private var active = false
    private var lastGeometry: Geometry? = null

    private data class Geometry(
        val canvas: PixelBounds,
        val visible: PixelBounds,
        val padding: ProjectionPadding,
        val picture: VideoViewport?,
    )

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
        else lastGeometry = null
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
        val canvas = PixelBounds(location[0], location[1], location[0] + width, location[1] + height)
        val visible = PixelBounds(visibleFrame.left, visibleFrame.top, visibleFrame.right, visibleFrame.bottom)
        val next = projectionPadding(canvas, visible, fallbackStatusBarHeight)
        if (paddingLeft != next.left || paddingTop != next.top || paddingRight != next.right || paddingBottom != next.bottom) {
            setPadding(next.left, next.top, next.right, next.bottom)
            return true
        }
        // 等内边距生效后再记：窗口坐标、实际避让、缩放黑边是三个不同的量。
        val geometry = Geometry(canvas, visible, next, projection.laidOutViewport)
        if (geometry != lastGeometry) {
            lastGeometry = geometry
            logGeometry(geometry)
        }
        return false
    }

    private fun logGeometry(geometry: Geometry) {
        val (canvas, visible, inset, viewport) = geometry
        val picture = viewport?.let {
            val x = canvas.left + inset.left
            val y = canvas.top + inset.top
            val bounds = PixelBounds(x + it.left, y + it.top, x + it.right, y + it.bottom)
            "视频=${it.videoWidth}x${it.videoHeight}，画面=${bounds.coordinates()}，等比上黑边=${it.top}px"
        } ?: "视频尺寸未就绪"
        DiagnosticLog.log(
            "OpenHC-Projection",
            "系统栏避让：全屏=${if (fullScreen) "开" else "关"}，画布=${canvas.coordinates()}，可见区=${visible.coordinates()}，" +
                "追加(左,上,右,下)=(${inset.left},${inset.top},${inset.right},${inset.bottom})px；$picture",
        )
    }

    private fun PixelBounds.coordinates(): String = "($left,$top)-($right,$bottom)"
}
