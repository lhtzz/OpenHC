package io.github.gcbond.openhc.platform.usb

import android.os.Build
import java.io.IOException

/** 只用系统已经授权并打开的 USB fd；与 API 21 的 UsbDeviceConnection.setInterface 走同一个内核 ioctl。 */
internal object LegacyUsbInterface {
    private val loadError: LinkageError? by lazy {
        try {
            System.loadLibrary("openhc_legacy_usb")
            null
        } catch (e: LinkageError) {
            e
        }
    }

    fun requireLoaded() {
        loadError?.let { throw linkageFailure("加载", it) }
    }

    fun setAlternate(fd: Int, interfaceId: Int, alternate: Int) {
        requireLoaded()
        val errno = try {
            setInterfaceNative(fd, interfaceId, alternate)
        } catch (e: LinkageError) {
            throw linkageFailure("调用", e)
        }
        if (errno != 0) throw IOException("USB 数据口 $interfaceId 切到备用 $alternate 失败（errno=$errno）")
    }

    @Suppress("DEPRECATION")
    private fun linkageFailure(step: String, error: LinkageError): IOException {
        val detail = error.message?.trim()?.take(400)?.takeIf { it.isNotEmpty() } ?: "系统未提供详细错误"
        return IOException(
            "无法${step}安卓 4.x USB 网卡切换组件（API ${Build.VERSION.SDK_INT}，ABI ${Build.CPU_ABI}）：" +
                "${error.javaClass.simpleName}: $detail",
            error,
        )
    }

    @JvmStatic
    private external fun setInterfaceNative(fd: Int, interfaceId: Int, alternate: Int): Int
}
