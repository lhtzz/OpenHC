package io.github.gcbond.openhc.wireless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 哪些安卓版本能开热点、开的是哪种热点。 */
class WirelessNetworkPlanTest {

    @Test
    fun modernSystemsUseLocalOnlyHotspotWithoutProbing() {
        for (sdk in listOf(26, 28, 29, 30, 31, 33, 36)) {
            assertTrue(WirelessNetworkPlan.hotspotSupported(sdk) { error("不应探测旧接口") }, "安卓 API $sdk")
            assertFalse(WirelessNetworkPlan.usesLegacyHotspot(sdk))
        }
    }

    @Test
    fun android41Through71UseLegacyHotspotOnlyWhenTheRomExposesIt() {
        for (sdk in 16..25) {
            assertTrue(WirelessNetworkPlan.usesLegacyHotspot(sdk))
            assertTrue(WirelessNetworkPlan.hotspotSupported(sdk) { true }, "安卓 API $sdk")
            assertFalse(WirelessNetworkPlan.hotspotSupported(sdk) { false }, "安卓 API $sdk")
        }
    }

    @Test
    fun systemsBelowTheAppMinimumDoNotProbeTheLegacyInterface() {
        assertFalse(WirelessNetworkPlan.usesLegacyHotspot(15))
        assertFalse(WirelessNetworkPlan.hotspotSupported(15) { error("低于应用最低版本") })
    }

    @Test
    fun carHotspotOnApi17DoesNotRequireAnApplicationHotspotApi() {
        assertTrue(WirelessNetworkPlan.hotspotSupported(17, carHotspot = true) { error("不应调用旧热点接口") })
        assertFalse(WirelessNetworkPlan.hotspotSupported(15, carHotspot = true) { error("低于应用最低版本") })
    }

    @Test
    fun hotspotStartAllowanceLeavesRoomForTheSlowLegacyPath() {
        // 旧接口没有回调，要轮询状态，等它起来比新接口慢；时限要和会话里给的一致。
        assertEquals(15_000L, WirelessNetworkPlan.timeouts().networkStartMillis)
    }
}
