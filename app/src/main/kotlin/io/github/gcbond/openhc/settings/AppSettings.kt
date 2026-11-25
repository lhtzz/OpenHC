package io.github.gcbond.openhc.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.BluetoothChoice
import io.github.gcbond.openhc.connection.DisplayPlan

/**
 * 应用设置（SharedPreferences）。改动在下一次连接时生效。任意线程可读写。
 */
class AppSettings internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    /** 声明给手机的帧率；0 表示按屏幕刷新率。 */
    var fps: Int
        get() = prefs.getInt(KEY_FPS, 0).coerceAtLeast(0)
        set(value) = prefs.edit { putInt(KEY_FPS, value.coerceAtLeast(0)) }

    /** 「界面大小」：声明给手机的 DPI 按这个百分比缩小，只认设置页列出的档位，别的值按默认。下次连接生效。 */
    var uiScalePercent: Int
        get() = prefs.getInt(KEY_UI_SCALE, DisplayPlan.DEFAULT_UI_SCALE).takeIf { it in DisplayPlan.UI_SCALE_CHOICES }
            ?: DisplayPlan.DEFAULT_UI_SCALE
        set(value) = prefs.edit { putInt(KEY_UI_SCALE, value) }

    /** 用户手填的车机蓝牙地址（原样保存，用的时候再校验）；没填为 null。 */
    var manualBluetoothAddress: String?
        get() = prefs.getString(KEY_MANUAL_BT, null)?.takeIf { it.isNotBlank() }
        set(value) = synchronized(wiredAddressLock) {
            prefs.edit { restoreAddress(KEY_MANUAL_BT, value?.trim()?.takeIf { it.isNotBlank() }) }
        }

    /** 已保存的有线专用身份，不能读作实际蓝牙地址。 */
    private val wiredLocalAddress: String?
        get() = prefs.getString(KEY_WIRED_LOCAL_BT, null)

    /** 同时读两个地址键，避免与「设为仅有线」交错。仅在真正开始连接时才可生成。 */
    internal fun wiredBluetoothChoice(systemAddress: String?, createIfMissing: Boolean = false): BluetoothChoice =
        synchronized(wiredAddressLock) {
            BluetoothAddressPolicy.chooseForWired(systemAddress, manualBluetoothAddress, wiredLocalAddress) {
                if (createIfMissing) saveNewWiredLocalAddress() else null
            }
        }

    private fun saveNewWiredLocalAddress(): String? {
        val previous = wiredLocalAddress
        val created = BluetoothAddressPolicy.newWiredLocalAddress()
        if (prefs.edit().putString(KEY_WIRED_LOCAL_BT, created).commit()) return created
        // SharedPreferences 写盘失败时，内存映射可能已经更新。恢复旧值，不能认未落盘的地址。
        prefs.edit { restoreAddress(KEY_WIRED_LOCAL_BT, previous) }
        return null
    }

    /** 用户确认只让 USB 用当前手填值：原子移动两个键；从旧版迁移时沿用原车机身份。 */
    internal fun useManualBluetoothForWiredOnly(): Boolean = synchronized(wiredAddressLock) {
        val raw = manualBluetoothAddress
        val address = BluetoothAddressPolicy.normalize(raw) ?: return@synchronized false
        val previous = wiredLocalAddress
        if (prefs.edit().putString(KEY_WIRED_LOCAL_BT, address).remove(KEY_MANUAL_BT).commit()) return@synchronized true
        prefs.edit {
            restoreAddress(KEY_WIRED_LOCAL_BT, previous)
            restoreAddress(KEY_MANUAL_BT, raw)
        }
        false
    }

    /** 只恢复有线地址的选取，不删除任何配对记录。 */
    internal fun clearWiredLocalAddress(): Boolean = synchronized(wiredAddressLock) {
        val previous = wiredLocalAddress ?: return@synchronized true
        if (prefs.edit().remove(KEY_WIRED_LOCAL_BT).commit()) return@synchronized true
        prefs.edit { restoreAddress(KEY_WIRED_LOCAL_BT, previous) }
        false
    }

    private fun SharedPreferences.Editor.restoreAddress(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    /**
     * 应用被「设备接入」拉起过：用户在系统框里同意过用本应用打开手机（勾了「默认」之后系统会自动授权）。
     * 这之后（「插线时不再弹窗」开着时）有线会话先等一会儿看系统会不会自动授权，再自己弹授权框，免掉一个多余的框。
     */
    var usbAttachAccepted: Boolean
        get() = prefs.getBoolean(KEY_USB_ATTACH_ACCEPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_USB_ATTACH_ACCEPTED, value) }

    /**
     * 「全屏显示」：开时尝试隐藏系统栏，画面避开可检测到的系统栏；关时保留顶栏和底栏。
     * 声明给手机的尺寸随设置变化，下次连接生效。
     */
    var fullScreen: Boolean
        get() = prefs.getBoolean(KEY_FULL_SCREEN, true)
        set(value) = prefs.edit { putBoolean(KEY_FULL_SCREEN, value) }

    /** 安卓 13 起的通知权限问过一次了（拒绝了也不再追问）。 */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_ASKED, value) }

    private companion object {
        const val FILE = "openhc-settings"
        const val KEY_FPS = "fps"
        const val KEY_UI_SCALE = "ui_scale_percent"
        const val KEY_MANUAL_BT = "manual_bluetooth_address"
        const val KEY_WIRED_LOCAL_BT = "wired_local_address"
        private val wiredAddressLock = Any()
        const val KEY_USB_ATTACH_ACCEPTED = "usb_attach_accepted"
        const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
        const val KEY_FULL_SCREEN = "full_screen"
    }
}
