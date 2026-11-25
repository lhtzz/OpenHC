package io.github.gcbond.openhc.connection

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import io.github.gcbond.openhc.settings.DiagnosticLog
import kotlin.math.roundToInt

/** 连接时向手机声明的画布尺寸。运行时再按真正可见的窗口区域等比显示，不裁掉视频。 */
internal object DisplayArea {
    /** 本次连接使用的设置，中途开关只在下一次连接生效。 */
    @Volatile
    var activeFullScreen: Boolean = true

    fun screenFor(context: Context, fullScreen: Boolean): ScreenSpec {
        val screen = DeviceInfo.screen(context)
        val (area, source) = if (fullScreen) {
            screen to "整屏"
        } else {
            // 安卓 4.x 的配置给出应用可用的宽高：会排除系统实际占用的栏，也能反映侧栏。
            // 不需要等 Activity 布局，插线自动启动和开机静默连接时也能读取。
            val resources = context.resources
            val config = resources.configuration
            val configured = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                withConfiguredArea(screen, config.screenWidthDp, config.screenHeightDp, resources.displayMetrics.density)
            } else null
            if (configured != null) configured to "系统可用区"
            else withBarsReserved(screen, statusBarHeight(context), navigationBarHeight(context)) to "系统栏资源估算"
        }
        DiagnosticLog.log(
            "OpenHC-Projection",
            "投屏协商区域：${area.width}x${area.height}（$source），屏幕 ${screen.width}x${screen.height}，" +
                "全屏=${if (fullScreen) "开" else "关"}",
        )
        return area
    }

    internal fun withConfiguredArea(
        screen: ScreenSpec,
        widthDp: Int,
        heightDp: Int,
        density: Float,
    ): ScreenSpec? {
        if (widthDp <= 0 || heightDp <= 0 || !density.isFinite() || density <= 0f) return null
        val width = (widthDp * density).roundToInt()
        val height = (heightDp * density).roundToInt()
        if (width !in 2..screen.width || height !in 2..screen.height) return null
        return screen.copy(width = width, height = height)
    }

    /** 没有检测到顶部偏移且需要保留系统栏时的最后估算。 */
    fun statusBarHeight(context: Context): Int =
        androidDimension(context, "status_bar_height").takeIf { it > 0 }
            ?: (24f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    fun navigationBarHeight(context: Context): Int = androidDimension(context, "navigation_bar_height")

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun androidDimension(context: Context, resource: String): Int {
        val id = context.resources.getIdentifier(resource, "dimen", "android")
        return if (id != 0) context.resources.getDimensionPixelSize(id).coerceAtLeast(0) else 0
    }

    internal fun withBarsReserved(screen: ScreenSpec, top: Int, bottom: Int): ScreenSpec {
        // 给 DisplayPlan 留出至少两个像素，以便继续按偶数边长协商。
        val remaining = screen.height.toLong() - top.coerceAtLeast(0) - bottom.coerceAtLeast(0)
        return screen.copy(height = (remaining.coerceAtLeast(2).toInt() and 1.inv()))
    }
}
