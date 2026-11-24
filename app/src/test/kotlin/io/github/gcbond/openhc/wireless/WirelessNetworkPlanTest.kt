package io.github.gcbond.openhc.wireless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 哪些安卓版本能开热点、开的是哪种热点。 */
class WirelessNetworkPlanTest {

    @Test
    fun olderSystemsFallBackToTheLegacyHotspot() {
        // 安卓 8.0 起用系统的本地热点（只供本地通信）。
        for (sdk in listOf(26, 28, 29, 30, 31, 33, 36)) {
            assertTrue(WirelessNetworkPlan.hotspotSupported(sdk), "安卓 $sdk")
            assertFalse(WirelessNetworkPlan.usesLegacyHotspot(sdk), "安卓 $sdk 不该走旧接口")
        }
        // 安卓 4.1～7.1 走旧的 setWifiApEnabled（普通个人热点）。
        for (sdk in listOf(16, 17, 18, 19, 20, 21, 22, 23, 24, 25)) {
            assertTrue(WirelessNetworkPlan.hotspotSupported(sdk), "安卓 $sdk")
            assertTrue(WirelessNetworkPlan.usesLegacyHotspot(sdk), "安卓 $sdk 要走旧接口")
        }
        // 比 minSdk 还低的只在单元测试里出现：按区间算仍然算「不支持」，实际装不上。
        for (sdk in listOf(14, 15)) {
            assertFalse(WirelessNetworkPlan.hotspotSupported(sdk), "安卓 $sdk")
            assertFalse(WirelessNetworkPlan.usesLegacyHotspot(sdk), "安卓 $sdk")
        }
    }

    @Test
    fun theMinimumIsTheOldInterfaceNotTheLocalOnlyHotspot() {
        assertEquals(16, WirelessNetworkPlan.LEGACY_HOTSPOT_MIN_SDK)
        assertEquals(26, WirelessNetworkPlan.HOTSPOT_MIN_SDK)
    }

    @Test
    fun hotspotStartAllowanceLeavesRoomForTheSlowLegacyPath() {
        // 旧接口没有回调，要轮询状态，等它起来比新接口慢；时限要和会话里给的一致。
        assertEquals(15_000L, WirelessNetworkPlan.timeouts().networkStartMillis)
    }
}
