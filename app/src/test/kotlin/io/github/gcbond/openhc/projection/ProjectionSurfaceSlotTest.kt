package io.github.gcbond.openhc.projection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectionSurfaceSlotTest {
    private class Surface(var valid: Boolean = true)
    private val delivered = mutableListOf<Surface?>()
    private val slot = ProjectionSurfaceSlot<Surface>({ it.valid }) { delivered += it }

    @Test
    fun returningFromHomeRebindsEvenWhenSystemReusesTheSurface() {
        val surface = Surface()
        slot.setSurface(surface)
        slot.setHostStarted(false)
        assertTrue(slot.holderReady)
        assertNull(slot.output)
        slot.setHostStarted(true)
        assertSame(surface, slot.output)
        assertEquals(listOf(surface, null, surface), delivered)
    }

    @Test
    fun destroyedSurfaceDoesNotDuplicateTheNullOrBindWhileInBackground() {
        val before = Surface()
        val after = Surface()
        slot.setSurface(before)
        slot.setHostStarted(false)
        slot.setSurface(null)
        slot.setSurface(after)
        slot.setHostStarted(false)
        assertEquals(listOf(before, null), delivered)
        slot.setHostStarted(true)
        assertEquals(listOf(before, null, after), delivered)
    }

    @Test
    fun logicalPageCanStartBeforeTheNewSurfaceIsReady() {
        val before = Surface()
        slot.setSurface(before)
        slot.setSurface(null)
        slot.setHostStarted(false)
        slot.setHostStarted(true)
        assertNull(slot.output)
        before.valid = false
        slot.setSurface(before)
        assertNull(slot.output)
        before.valid = true
        slot.setSurface(before)
        assertSame(before, slot.output)
        assertEquals(listOf(before, null, before), delivered)
    }
}
