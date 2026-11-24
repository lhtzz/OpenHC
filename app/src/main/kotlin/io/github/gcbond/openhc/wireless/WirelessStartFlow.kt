package io.github.gcbond.openhc.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.WirelessPreferences

/**
 * 从界面开始无线连接：先拿运行时权限（见 [WirelessPermissions]），拿到了再交给调度；被拒时把原因交给调度显示。
 * 开着「无线自动回连」时顺带申请蓝牙广播权限（安卓 12 起）；它被拒不挡扫码，只是不发广播。
 * 另外提供两个跳系统页面的入口：本应用的系统设置（权限被永久拒绝时）、请系统打开蓝牙。
 *
 * 必须在 Activity 构造时创建（结果回调要在 onCreate 之前注册）。
 */
internal class WirelessStartFlow(
    private val activity: ComponentActivity,
    private val controller: () -> WirelessController,
) {
    private val settings by lazy { AppSettings(activity) }
    private val wirelessPrefs by lazy { WirelessPreferences(activity) }

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionResult()
    }

    /** 「开始无线连接」。 */
    fun start() {
        val sdk = Build.VERSION.SDK_INT
        // 只用数据线连接开着、或系统太老开不了本地热点：不用申请任何权限，交给调度直接说明原因。
        if (wirelessPrefs.wiredOnly || !WirelessNetworkPlan.hotspotSupported(sdk)) {
            controller().start()
            return
        }
        // 安卓 6～7 用旧接口开热点要「修改系统设置」：它是 appop 权限，运行时弹窗申请不到，只能拉起系统设置页
        // 让用户打开。不先拦住的话，下面的申请列表会把 WRITE_SETTINGS 过滤掉、空数组一申请就立刻回调，
        // 然后被 [WirelessPermissions.afterRequest] 判成「系统不再弹框」——而应用权限页里根本没有这个开关。
        if (WirelessPermissions.needsWriteSettings(sdk) && !canWriteSettings()) {
            Log.i(TAG, "安卓 8.0 以下开热点要「修改系统设置」：拉起系统设置页")
            controller().reportBlocked(WirelessNotice.WriteSettingsRequired)
            openWriteSettings()
            return
        }
        val missing = WirelessPermissions.missing(sdk, ::granted)
        // 通知权限只问一次（和有线共用这个记号），拒绝了也照常连接。
        val askNotifications = sdk >= Build.VERSION_CODES.TIRAMISU && !settings.notificationPermissionAsked &&
            !granted(WirelessPermissions.POST_NOTIFICATIONS)
        // 广播权限被拒过也照样再问（系统拒绝两次后自己就不再弹框）：没有它就不能自动回连。
        val askAdvertise = wirelessPrefs.autoReconnect && WirelessPermissions.advertise(sdk).any { !granted(it) }
        if (missing.isEmpty() && !askNotifications && !askAdvertise) {
            controller().start()
            return
        }
        if (askNotifications) settings.notificationPermissionAsked = true
        val request = if (missing.isEmpty()) {
            (if (askAdvertise) WirelessPermissions.advertise(sdk) else emptyList()) +
                (if (askNotifications) listOf(WirelessPermissions.POST_NOTIFICATIONS) else emptyList())
        } else {
            WirelessPermissions.toRequest(sdk, askNotifications, askAdvertise)
        }
        Log.i(TAG, "申请权限：${request.joinToString()}")
        try {
            launcher.launch(request.toTypedArray())
        } catch (e: ActivityNotFoundException) {
            controller().reportBlocked(WirelessNotice.StartFailed("系统没有权限申请页"))
        }
    }

    private fun onPermissionResult() {
        val denied = WirelessPermissions.afterRequest(Build.VERSION.SDK_INT, ::granted) {
            activity.shouldShowRequestPermissionRationale(it)
        }
        if (denied == null) {
            controller().start()
        } else {
            Log.i(TAG, "权限没有给全：$denied")
            controller().reportBlocked(denied)
        }
    }

    /** 打开本应用的系统设置页，让用户手动开运行时权限（「修改系统设置」另见 [openWriteSettings]）。 */
    fun openAppSettings() = openSettingsPage(withWriteSettings = false)

    /** 打开「修改系统设置」的系统设置页（安卓 6～7 用旧接口开热点要它）；没有这个页面时退到应用详情页。 */
    fun openWriteSettings() = openSettingsPage(withWriteSettings = true)

    /**
     * 依次试几个系统页面，第一个能起来的就打开。
     *
     * 车机 rom 把系统设置改得比较多：`ACTION_APPLICATION_DETAILS_SETTINGS` 有的机器上根本没有对应页面，
     * 有的机器上 `startActivity` 不抛异常但界面也不切换（表现就是「点了没反应」）。所以先问 `resolveActivity`
     * 有没有这个页面，再按「最相关 → 最兜底」的顺序试；全都起不来时用 Toast 告诉车主手动去哪里开。
     */
    private fun openSettingsPage(withWriteSettings: Boolean) {
        val candidates = ArrayList<Pair<String, String>>(4)
        if (withWriteSettings) candidates += Settings.ACTION_MANAGE_WRITE_SETTINGS to "修改系统设置页"
        candidates += Settings.ACTION_APPLICATION_DETAILS_SETTINGS to "应用详情页"
        // 老车机（尤其定制 rom）常常没有应用详情页，但一般还有「应用列表」：进去还能找到 OpenHC。
        candidates += Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS to "应用列表"
        candidates += Settings.ACTION_SETTINGS to "系统设置"
        for ((action, what) in candidates) {
            val intent = Intent(action)
            // 只有前两个是「针对某个包」的页面；后两个是整机的设置页，带上包名反而可能被系统判成不合法。
            if (action == Settings.ACTION_MANAGE_WRITE_SETTINGS || action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                intent.data = Uri.fromParts("package", activity.packageName, null)
            }
            if (launchQuietly(intent, what)) {
                Log.i(TAG, "已打开$what")
                return
            }
        }
        Log.w(TAG, "这台车机没有能打开的系统设置页")
        Toast.makeText(activity, R.string.wireless_settings_page_missing, Toast.LENGTH_LONG).show()
    }

    /** 现在有没有「修改系统设置」（appop，见 [WirelessPermissionCheck]）。 */
    private fun canWriteSettings(): Boolean = WirelessPermissionCheck.canWriteSettings(activity)

    /**
     * 请系统打开蓝牙（系统弹框）。安卓 12 起要先有「附近的设备」权限：没有就先走 [start] 申请。
     * 车机系统没有这个页面时只记日志，界面上的提示仍然让用户自己去打开。
     */
    // 上面先判断了权限，lint 认不出这种写法。
    @SuppressLint("MissingPermission")
    fun requestEnableBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(WirelessPermissions.BLUETOOTH_CONNECT)) {
            start()
            return
        }
        launchQuietly(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), "打开蓝牙的系统框")
    }

    /** 起一个系统页面；系统没有这个页面或起不来时返回 false，由调用方换下一个。 */
    private fun launchQuietly(intent: Intent, what: String): Boolean {
        // 先问系统有没有这个页面：定制 rom 上 startActivity 有时不抛异常，但界面根本不切换。
        if (intent.resolveActivity(activity.packageManager) == null) {
            Log.i(TAG, "系统没有$what")
            return false
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "系统没有$what")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "打不开$what：${e.message}")
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "打不开$what：${e.javaClass.simpleName}：${e.message}")
            false
        }
    }

    private fun granted(permission: String) =
        if (permission == WirelessPermissions.WRITE_SETTINGS) {
            WirelessPermissionCheck.canWriteSettings(activity)
        } else {
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }

    private companion object {
        const val TAG = "OpenHC-Wireless"
    }
}
