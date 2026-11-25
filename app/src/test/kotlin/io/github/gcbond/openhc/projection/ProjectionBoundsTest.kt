package io.github.gcbond.openhc.projection

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectionBoundsTest {
    private val display = PixelBounds(0, 0, 1024, 600)
    private val visible = PixelBounds(0, 32, 1024, 552)
    private val none = ProjectionPadding(0, 0, 0, 0)

    @Test
    fun fullscreenReallyHidesBarsLeavesNoPadding() {
        assertEquals(none, projectionPadding(display, display, fallbackStatusBarHeight = 0))
    }

    @Test
    fun visibleTopAndBottomBarsAreAvoidedEvenWhenFullscreenWasRequested() {
        assertEquals(ProjectionPadding(0, 32, 0, 48), projectionPadding(display, visible, fallbackStatusBarHeight = 0))
    }

    @Test
    fun alreadyFittedWindowIsNotPaddedTwice() {
        assertEquals(none, projectionPadding(visible, visible, fallbackStatusBarHeight = 48))
        assertEquals(ProjectionPadding(0, 8, 0, 0),
            projectionPadding(PixelBounds(0, 24, 1024, 552), visible, fallbackStatusBarHeight = 16))
    }

    @Test
    fun smallReportedStatusBarIsNotEnlargedToResourceHeight() {
        val withSmallBar = PixelBounds(0, 12, 1024, 600)
        assertEquals(ProjectionPadding(0, 12, 0, 0), projectionPadding(display, withSmallBar, fallbackStatusBarHeight = 32))
        assertEquals(none, projectionPadding(withSmallBar, withSmallBar, fallbackStatusBarHeight = 32))
    }

    @Test
    fun layoutOffsetAloneAlreadyAccountsForTheStatusBar() {
        val movedBySystem = PixelBounds(0, 12, 1024, 600)
        val missing = PixelBounds(0, 0, 0, 0)
        assertEquals(none, projectionPadding(movedBySystem, display, fallbackStatusBarHeight = 32))
        assertEquals(none, projectionPadding(movedBySystem, missing, fallbackStatusBarHeight = 32))
    }

    @Test
    fun nonFullscreenProtectsTheTopEvenIfTheRomReportsAnUnobstructedWindow() {
        assertEquals(ProjectionPadding(0, 24, 0, 0), projectionPadding(display, display, fallbackStatusBarHeight = 24))
        assertEquals(ProjectionPadding(0, 24, 0, 0),
            projectionPadding(display, PixelBounds(0, 0, 0, 0), fallbackStatusBarHeight = 24))
    }

    @Test
    fun respectsSideBarsAndDoesNotCollapseTheCanvasOnBadInsets() {
        assertEquals(ProjectionPadding(10, 32, 54, 48),
            projectionPadding(display, PixelBounds(10, 32, 970, 552), fallbackStatusBarHeight = 0))
        assertEquals(none, projectionPadding(display, display, fallbackStatusBarHeight = Int.MAX_VALUE))
    }
}
