package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.platform.media.VideoViewport
import io.github.gcbond.openhc.projection.PixelBounds
import io.github.gcbond.openhc.projection.projectionPadding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DisplayAreaTest {
    private val screen = ScreenSpec(1024, 600, 160, 30)

    @Test
    fun configuredAreaAccountsForSideBarsAndPixelDensity() {
        val available = DisplayArea.withConfiguredArea(screen, widthDp = 488, heightDp = 288, density = 2f)
        assertEquals(screen.copy(width = 976, height = 576), available)
    }

    @Test
    fun noBottomBarDoesNotCauseAnExtraTopLetterbox() {
        val visible = PixelBounds(0, 28, screen.width, screen.height)
        val available = assertNotNull(DisplayArea.withConfiguredArea(screen, 1024, 572, density = 1f))
        val request = DisplayPlan.resolve(available, ResolutionChoice.Auto, fpsSetting = 0)
        val pad = projectionPadding(visible, visible, fallbackStatusBarHeight = 48)
        val actual = VideoViewport(
            visible.right - visible.left - pad.left - pad.right,
            visible.bottom - visible.top - pad.top - pad.bottom,
            request.width,
            request.height,
        )
        assertEquals(visible.top, visible.top + pad.top + actual.top)
        assertEquals(572, actual.height)
    }

    @Test
    fun ignoresMissingOrOutOfRangeConfiguredArea() {
        assertNull(DisplayArea.withConfiguredArea(screen, 0, 572, density = 1f))
        assertNull(DisplayArea.withConfiguredArea(screen, 1024, 602, density = 1f))
        assertNull(DisplayArea.withConfiguredArea(screen, 600, 1024, density = 1f))
        assertNull(DisplayArea.withConfiguredArea(screen, 1024, 572, density = Float.NaN))
    }

    @Test
    fun nonFullscreenResourceFallbackStillReservesSystemBars() {
        assertEquals(screen.copy(height = 520), DisplayArea.withBarsReserved(screen, top = 32, bottom = 48))
        assertEquals(screen.copy(height = 2), DisplayArea.withBarsReserved(screen, top = Int.MAX_VALUE, bottom = Int.MAX_VALUE))
    }
}
