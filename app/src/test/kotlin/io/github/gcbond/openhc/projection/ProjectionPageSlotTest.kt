package io.github.gcbond.openhc.projection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProjectionPageSlotTest {
    private val wired = ProjectionOwner(wireless = false, attempt = 7)
    private val wireless = ProjectionOwner(wireless = true, attempt = 7)

    private class Page(val owner: ProjectionOwner)
    private val actions = mutableListOf<String>()
    private val slot = ProjectionPageSlot(
        { owner -> Page(owner).also { actions += "mount:$owner" } },
        { page -> actions += "unmount:${page.owner}" },
    )

    @Test
    fun stateRefreshAndMenuNavigationDoNotDetachCurrentCanvas() {
        val visible = slot.reconcile(wired) { it == wired }!!
        repeat(3) {
            assertSame(visible.page, slot.reconcile(wired) { it == wired }?.page)
            assertSame(visible.page, slot.reconcile(null) { it == wired }?.page)
        }
        assertSame(visible.page, slot.reconcile(wired) { it == wired }?.page)
        assertEquals(listOf("mount:$wired"), actions)
    }

    @Test
    fun endingWhileHomeUnmountsAndDoesNotMountANewCanvasInTheBackground() {
        slot.reconcile(wired) { it == wired }
        slot.reconcile(null) { it == wired }
        val next = wired.copy(attempt = wired.attempt + 1)
        assertNull(slot.reconcile(null) { it == next })
        assertNull(slot.reconcile(wired) { it == next })
        assertEquals(next, slot.reconcile(next) { it == next }?.owner)
        assertEquals(listOf("mount:$wired", "unmount:$wired", "mount:$next"), actions)
    }

    @Test
    fun differentTransportsWithSameAttemptNeverShareACanvas() {
        slot.reconcile(wired) { true }
        assertEquals(wireless, slot.reconcile(wireless) { true }?.owner)
        slot.close()
        slot.close()
        assertEquals(listOf("mount:$wired", "unmount:$wired", "mount:$wireless", "unmount:$wireless"), actions)
    }
}
