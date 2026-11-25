package io.github.gcbond.openhc.connection

import android.annotation.SuppressLint
import android.content.Context
import kotlin.math.roundToInt

/** 连接时向手机声明的画布尺寸。运行时再按真正可见的窗口区域等比显示，不裁掉视频。 */
internal object DisplayArea {
    /** 本次连接使用的设置，中途开关只在下一次连接生效。 */
    @Volatile
    var activeFullScreen: Boolean = true

    fun screenFor(context: Context, fullScreen: Boolean): ScreenSpec {
        val screen = DeviceInfo.screen(context)
        if (fullScreen) return screen
        return withBarsReserved(screen, statusBarHeight(context), navigationBarHeight(context))
    }

    /** 估算顶部：系统资源未给时留出一行标准状态栏。全屏时不强加此留边。 */
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
