package io.github.gcbond.openhc.projection

import io.github.gcbond.openhc.connection.DisplayArea
import io.github.gcbond.openhc.connection.ScreenSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectionBoundsTest {
    private val display = PixelBounds(0, 0, 1024, 600)
    private val visible = PixelBounds(0, 32, 1024, 552)
    private val none = ProjectionPadding(0, 0, 0, 0)

    @Test
    fun fullscreenReallyHidesBarsLeavesNoPadding() {
        assertEquals(none, projectionPadding(display, display, minimumScreenTop = 0))
    }

    @Test
    fun visibleTopAndBottomBarsAreAvoidedEvenWhenFullscreenWasRequested() {
        assertEquals(ProjectionPadding(0, 32, 0, 48), projectionPadding(display, visible, minimumScreenTop = 0))
    }

    @Test
    fun alreadyFittedWindowIsNotPaddedTwice() {
        assertEquals(none, projectionPadding(visible, visible, minimumScreenTop = 24))
        assertEquals(ProjectionPadding(0, 8, 0, 0),
            projectionPadding(PixelBounds(0, 24, 1024, 552), visible, minimumScreenTop = 24))
    }

    @Test
    fun nonFullscreenProtectsTheTopEvenIfTheRomReportsAnUnobstructedWindow() {
        assertEquals(ProjectionPadding(0, 24, 0, 0), projectionPadding(display, display, minimumScreenTop = 24))
        assertEquals(ProjectionPadding(0, 24, 0, 0),
            projectionPadding(display, PixelBounds(0, 0, 0, 0), minimumScreenTop = 24))
    }

    @Test
    fun respectsSideBarsAndDoesNotCollapseTheCanvasOnBadInsets() {
        assertEquals(ProjectionPadding(10, 32, 54, 48),
            projectionPadding(display, PixelBounds(10, 32, 970, 552), minimumScreenTop = 0))
        assertEquals(ProjectionPadding(0, 599, 0, 0), projectionPadding(display, display, minimumScreenTop = Int.MAX_VALUE))
    }

    @Test
    fun nonFullscreenNegotiationReservesBothSystemBars() {
        val screen = ScreenSpec(1024, 600, 160, 30)
        assertEquals(screen.copy(height = 520), DisplayArea.withBarsReserved(screen, top = 32, bottom = 48))
        assertEquals(screen.copy(height = 2), DisplayArea.withBarsReserved(screen, top = Int.MAX_VALUE, bottom = Int.MAX_VALUE))
    }
}
