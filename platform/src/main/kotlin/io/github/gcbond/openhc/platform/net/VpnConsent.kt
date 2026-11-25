package io.github.gcbond.openhc.platform.net

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * 有线连接前要先拿到用户的 VPN 授权（[WiredVpnService] 建 TUN 需要），与 USB 授权无关。
 * 以每次 [VpnService.prepare] 的结果为准：原生 Android 4.2.2 只在运行中的系统保留准备好的 VPN，
 * 整机重启后会再次请求确认；启动其他 VPN 也可能取代授权。
 *
 * 应用层用法（在开始有线会话之前）：
 * ```
 * when (val status = VpnConsent.check(context)) {
 *     VpnConsent.Status.Granted -> 开始会话
 *     is VpnConsent.Status.NeedsUserConsent -> 用 Activity 拉起 status.intent，结果为 RESULT_OK 即同意
 *     is VpnConsent.Status.Unavailable -> 提示这台车机不能用有线连接
 * }
 * ```
 * 用 ComponentActivity 时可以 `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`
 * 拉起 `status.intent`；也可以直接用 [request]。
 */
object VpnConsent {

    sealed class Status {
        /** 已授权。 */
        object Granted : Status()

        /** 需要用户在系统框里同意：用 Activity 拉起 [intent]，结果为 [Activity.RESULT_OK] 即同意。 */
        class NeedsUserConsent(val intent: Intent) : Status()

        /** 系统不让用 VPN（例如设备策略禁止、正处于别的 VPN 的锁定模式）。 */
        class Unavailable(val reason: String) : Status()
    }

    fun check(context: Context): Status = try {
        val intent = VpnService.prepare(context)
        if (intent == null) Status.Granted else Status.NeedsUserConsent(intent)
    } catch (e: RuntimeException) {
        Status.Unavailable("${e.javaClass.simpleName}: ${e.message}")
    }

    fun isGranted(context: Context): Boolean = check(context) === Status.Granted

    /**
     * 已授权返回 [Status.Granted]；否则用 [activity] 拉起系统授权框（结果在 `onActivityResult(requestCode, …)`），
     * 返回 [Status.NeedsUserConsent]。车机系统里没有授权框时返回 [Status.Unavailable]。
     */
    fun request(activity: Activity, requestCode: Int): Status {
        val status = check(activity)
        if (status is Status.NeedsUserConsent) {
            try {
                activity.startActivityForResult(status.intent, requestCode)
            } catch (e: ActivityNotFoundException) {
                return Status.Unavailable("系统里没有 VPN 授权页")
            }
        }
        return status
    }
}
