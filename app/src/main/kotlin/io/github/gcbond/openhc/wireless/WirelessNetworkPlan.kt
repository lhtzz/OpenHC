package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.core.session.WirelessTimeouts

/**
 * 无线连接里 WiFi 那一段：车机开热点，手机像连普通 WiFi 一样连上来。纯逻辑，不引用安卓类，由 JVM 单元测试覆盖；
 * 接线见 WirelessController。
 *
 * - 系统要求：安卓 8.0 起用系统的「只供本地通信」热点（`startLocalOnlyHotspot`，手机只借用这条链路、不影响车机上网）；
 *   安卓 8.0 以下退回隐藏接口 `setWifiApEnabled`，开的是**普通个人热点**——手机走这个热点上网时用的是车机的数据网络，
 *   车机没联网时手机也上不了网。这个隐藏接口从安卓 4.0.3 起就有，本应用的 minSdk（安卓 4.1）起都能试；
 *   真能不能开由运行时反射探测（`LegacyHotspot.available()`）决定，不按版本号硬判。
 * - 开始前不要求车机 WiFi 开着：开热点时单射频车机自己连着的 WiFi 本来就会断。
 * - 手机认证通过以后才开热点：等扫码、等回连、没通过认证的连接都不动车机的 WiFi。
 * - 开热点的时限 15 秒：含两次开热点之间至少 3.5 秒的间隔、等热点网卡出现地址。
 */
object WirelessNetworkPlan {
    /**
     * 旧接口（`setWifiApEnabled`）的版本下限，取本应用的 minSdk（安卓 4.1 / API 16）。
     *
     * AOSP 源码核实：`WifiManager.setWifiApEnabled` 从安卓 4.0.3 起就有，`WifiService.setWifiApEnabled` 在
     * 4.0～4.4 上只校验 `CHANGE_WIFI_STATE`（安装即授予），安卓 6.0 起才多要 `WRITE_SETTINGS`。
     * 按版本号卡没有意义——能不能开交给运行时反射探测（`LegacyHotspot.available()`），探测不到时
     * `LocalHotspot` 会报「这台车机的系统没有旧的热点接口，开不了热点」。
     */
    const val LEGACY_HOTSPOT_MIN_SDK = 16

    /** 系统的本地热点从安卓 8.0（API 26）起才有。 */
    const val HOTSPOT_MIN_SDK = 26

    /** 开热点的时限：间隔最多 3.5 秒 + 系统开热点（实测一两秒）+ 等网卡地址最多 3 秒，再留余量。 */
    const val HOTSPOT_NETWORK_START_MILLIS = 15_000L

    /** 这个系统版本能不能开热点；不能就用不了无线连接。 */
    fun hotspotSupported(sdkInt: Int): Boolean = sdkInt >= LEGACY_HOTSPOT_MIN_SDK

    /**
     * 这个系统版本开的是不是普通个人热点（安卓 8.0 以下走旧接口）。是的话热点会占用车机的数据网络、
     * 安卓 6～7 还要用户去系统设置里给「修改系统设置」权限，界面上要提醒。
     */
    fun usesLegacyHotspot(sdkInt: Int): Boolean = sdkInt in LEGACY_HOTSPOT_MIN_SDK until HOTSPOT_MIN_SDK

    /** 各步时限：开热点的时限放宽到 [HOTSPOT_NETWORK_START_MILLIS]，其余用会话的默认值。 */
    fun timeouts(): WirelessTimeouts = WirelessTimeouts(networkStartMillis = HOTSPOT_NETWORK_START_MILLIS)
}
