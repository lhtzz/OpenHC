package io.github.gcbond.openhc.platform.wifi

import android.annotation.SuppressLint
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import java.lang.reflect.Method
import java.security.SecureRandom
import java.util.BitSet

/**
 * 安卓 8.0 以下（API 16～25）开热点用的旧接口。
 *
 * 系统从安卓 8.0 才提供「只供本地通信」的 `startLocalOnlyHotspot`；更早的版本只有隐藏接口
 * `WifiManager.setWifiApEnabled(WifiConfiguration, boolean)`（安卓 4.1～7.1 上是 `@hide`，不在公开 SDK 里），
 * 它没有「只给本地用」的语义，就是把车机变成普通个人热点：**手机走这个热点上网时用的是车机的数据网络**
 * （车机没有联网时手机也上不了网，这一点和安卓 8.0 的本地热点不同）。
 *
 * 权限（AOSP 源码核实）：
 * - 安卓 4.0～5.1：`CHANGE_WIFI_STATE`（安装即授予）就够；这几个版本的 `WifiService.setWifiApEnabled` 只 `enforceChangePermission`；
 * - 安卓 6.0～7.1：`WifiServiceImpl.setWifiApEnabled` 还会调 `ConnectivityManager.enforceTetherChangePermission`，
 *   它要求 `WRITE_SETTINGS` 的 appop（用户在系统设置里授权，`Settings.ACTION_MANAGE_WRITE_SETTINGS`）。
 *
 * 名字、密码由我们自己定（`OpenHC-xxxx` + 8 位随机数字），不用像新接口那样等系统生成再读回来。
 * 系统没有「热点开好了」的回调，只能轮询 `getWifiApState()`。
 *
 * 反射读隐藏接口：安卓 9 起才有非公开接口限制，这些车机（≤ 7.1）上不存在这个问题；
 * 万一以后的系统把接口拿掉，[enable] 会抛 [UnsupportedOperationException]，由调用方按失败报。
 */
object LegacyHotspot {
    /** 热点名前缀，后面接 4 位数字。 */
    private const val SSID_PREFIX = "OpenHC-"

    /** 热点密码长度（WPA2 要求 8 位以上）。 */
    private const val PASSPHRASE_LENGTH = 8

    /** 只有安卓 8.0 以下才走这里。 */
    fun isLegacy(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.O

    /** 这个系统版本走旧接口时还要不要用户去系统设置里授权 `WRITE_SETTINGS`（安卓 6.0 起要）。 */
    fun needsWriteSettings(sdkInt: Int): Boolean = sdkInt in Build.VERSION_CODES.M until Build.VERSION_CODES.O

    /** 旧接口能不能用（反射找得到 `setWifiApEnabled`）。找不到时无线连接只能用新接口。 */
    fun available(): Boolean = try {
        setApMethod() != null
    } catch (e: RuntimeException) {
        false
    }

    /**
     * 生成一份热点配置：WPA2-PSK、2.4 GHz、由系统挑信道。
     *
     * `apBand`、`apChannel` 是隐藏字段（不在公开 SDK 里），不写就是它们的默认值 0 = 2.4 GHz、0 = 由系统挑信道，
     * 所以这里不用反射去设，只读的时候才反射（见 [bandHint]）。
     */
    @Suppress("DEPRECATION")
    fun configuration(random: SecureRandom = SecureRandom()): WifiConfiguration {
        val config = WifiConfiguration()
        config.SSID = SSID_PREFIX + (1000 + random.nextInt(9000))
        config.preSharedKey = randomPassphrase(random)
        config.allowedKeyManagement = BitSet().apply { set(WifiConfiguration.KeyMgmt.WPA2_PSK) }
        return config
    }

    /** 读旧配置里的频段、信道（隐藏字段，反射读；读不到传 null）。频段 0 = 2.4 GHz、1 = 5 GHz。 */
    fun bandHint(config: WifiConfiguration?): io.github.gcbond.openhc.platform.wifi.HotspotBandHint? {
        if (config == null) return null
        return io.github.gcbond.openhc.platform.wifi.LocalHotspotRules.bandHintFromLegacy(
            intField(config, "apBand"),
            intField(config, "apChannel"),
        )
    }

    private fun intField(target: Any, name: String): Int? = try {
        target.javaClass.getField(name).getInt(target)
    } catch (e: NoSuchFieldException) {
        null
    } catch (e: IllegalAccessException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun randomPassphrase(random: SecureRandom): String =
        (1..PASSPHRASE_LENGTH).map { DIGITS[random.nextInt(DIGITS.length)] }.joinToString("")

    /**
     * 开或关热点。[config] 为 null 表示关掉。返回系统有没有收下这次调用（`false` = 系统说没成功）。
     *
     * @throws SecurityException 没有 `CHANGE_WIFI_STATE`，或安卓 6.0 起没有 `WRITE_SETTINGS`。
     * @throws UnsupportedOperationException 这个系统没有旧接口（安卓 8.0 起）。
     */
    @SuppressLint("MissingPermission")
    fun enable(manager: WifiManager, config: WifiConfiguration?, enabled: Boolean): Boolean {
        val method = setApMethod() ?: throw UnsupportedOperationException("这个系统没有 setWifiApEnabled（安卓 8.0 起用本地热点）")
        return try {
            method.invoke(manager, config, enabled) as? Boolean ?: false
        } catch (e: java.lang.reflect.InvocationTargetException) {
            when (val cause = e.cause) {
                is SecurityException -> throw cause
                is RuntimeException -> throw cause
                else -> throw IllegalStateException("开热点失败：${cause?.javaClass?.simpleName}：${cause?.message}", cause)
            }
        } catch (e: IllegalAccessException) {
            throw UnsupportedOperationException("读不到旧的热点接口：${e.message}", e)
        }
    }

    /** 热点现在的状态（[WifiManager.WIFI_AP_STATE_ENABLED] 等）；读不到返回 -1。 */
    fun state(manager: WifiManager): Int = try {
        val method = WifiManager::class.java.getMethod("getWifiApState")
        method.invoke(manager) as? Int ?: -1
    } catch (e: Exception) {
        -1
    } catch (e: RuntimeException) {
        -1
    }

    /** 系统现在的热点配置（读回来核对名字和密码）；读不到返回 null。 */
    fun currentConfiguration(manager: WifiManager): WifiConfiguration? = try {
        val method = WifiManager::class.java.getMethod("getWifiApConfiguration")
        method.invoke(manager) as? WifiConfiguration
    } catch (e: Exception) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** 反射拿 `setWifiApEnabled(WifiConfiguration, boolean)`；拿不到返回 null。 */
    private fun setApMethod(): Method? = try {
        WifiManager::class.java.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
    } catch (e: NoSuchMethodException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private const val DIGITS = "0123456789"
}
