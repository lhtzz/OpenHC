package io.github.gcbond.openhc.projection

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 让 Android 管理状态栏和导航栏，不请求把窗口内容强行画到可见栏后面。
 * 全屏只是请求隐藏：老车机 ROM 不一定允许，剩余可见区域由 [ProjectionFrame] 再检查。
 */
internal object SystemBars {
    private val BARS = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()

    /** 关闭全屏时保留两边系统栏；开启时只尝试隐藏，不使用 LAYOUT_FULLSCREEN。 */
    fun applyProjectionMode(window: Window, view: View, fullScreen: Boolean) {
        if (!fullScreen) {
            show(window, view)
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
        // 安卓 4.1～4.3 没有真正的沉浸式模式，额外走旧窗口全屏标志隐藏普通状态栏。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        // 安卓 9 起：屏幕有挖孔时内容也铺到挖孔两侧的短边上（车机屏一般没有挖孔，这里只是不留黑边）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            if (attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = attributes
            }
        }
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(BARS)
        }
    }

    fun show(window: Window, view: View) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.getInsetsController(window, view).show(BARS)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            if (attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT) {
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                window.attributes = attributes
            }
        }
    }
}
